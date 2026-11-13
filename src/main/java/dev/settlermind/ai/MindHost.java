package dev.settlermind.ai;

import dev.settlermind.SettlerMind;
import dev.settlermind.entity.SettlerEntity;
import dev.settlermind.entity.SettlerEvents;
import dev.settlermind.mind.Decision;
import dev.settlermind.mind.Mind;
import dev.settlermind.mind.MindEvent;
import dev.settlermind.mind.Perception;
import dev.settlermind.mind.Persona;
import dev.settlermind.mind.board.Board;
import dev.settlermind.mind.board.BoardTask;
import dev.settlermind.mind.board.TaskView;
import dev.settlermind.mind.cognition.ClaudeCognition;
import dev.settlermind.mind.cognition.Cognition;
import dev.settlermind.mind.cognition.CognitionRequest;
import dev.settlermind.mind.cognition.CognitionResult;
import dev.settlermind.mind.cognition.RouteConfig;
import dev.settlermind.mind.cognition.ScriptedCognition;
import dev.settlermind.mind.cognition.UsageLedger;
import dev.settlermind.skill.Outcome;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects minds to bodies. Runs on the server thread, except model calls, which run on
 * a small worker pool and hand their results back with {@code server.execute}.
 *
 * <p>Graceful degradation: if a route's provider is missing, or a budget is exhausted,
 * the request is served by the free scripted mind instead, and the reason is logged
 * once. Settlers never freeze waiting for a model.
 */
public final class MindHost implements SettlerEvents {
    private static final int THINK_EVERY_TICKS = 10;
    private static final int BOARD_UPKEEP_TICKS = 200;
    /** Evidence in a verified mining outcome, e.g. "+2 minecraft:oak_log". */
    private static final Pattern GAINED = Pattern.compile("\\+(\\d+) ([a-z0-9_.-]+):([a-z0-9_./-]+)");
    private static @Nullable MindHost instance;

    private final MinecraftServer server;
    private final SettlerMindConfig config;
    private final MindStore store;
    private final UsageLedger ledger;
    private final Map<String, Cognition> providers = new HashMap<>();
    private final Cognition scripted = new ScriptedCognition();
    private final ExecutorService executor;
    private final Map<UUID, Long> lastBusyTick = new HashMap<>();
    private final Map<UUID, Deque<Long>> callsBySettler = new HashMap<>();
    private final Deque<Long> serverCalls = new ArrayDeque<>();
    private long usdDayStart = System.currentTimeMillis();
    private double usdAtDayStart;
    private final java.util.Set<String> warned = new java.util.HashSet<>();
    /** Per settler: task id -> revision as seen in the perception behind its current plan (claim fencing). */
    private final Map<UUID, Map<String, Integer>> seenRevisions = new HashMap<>();

    private MindHost(MinecraftServer server) {
        this.server = server;
        this.config = SettlerMindConfig.load();
        this.store = server.getOverworld().getPersistentStateManager()
                .getOrCreate(MindStore::fromNbt, MindStore::new, MindStore.ID);
        this.ledger = new UsageLedger(config.prices);
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "settlermind-worker");
            t.setDaemon(true);
            return t;
        });
        providers.put("scripted", scripted);
        boolean wantsClaude = config.routes.values().stream().anyMatch(r -> r.provider().equals("claude"));
        if (wantsClaude) {
            String key = System.getenv(config.claude.apiKeyEnv);
            if (key == null || key.isBlank()) {
                SettlerMind.LOG.warn("Routes use Claude but {} is not set; those routes will use the offline scripted mind.",
                        config.claude.apiKeyEnv);
            } else {
                providers.put("claude", new ClaudeCognition(key, Duration.ofSeconds(config.claude.timeoutSeconds),
                        config.claude.refusalFallbacks, executor));
            }
        }
        SettlerMind.LOG.info("SettlerMind minds ready: {} stored, routes {}", store.minds.size(), config.routes);
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            instance = new MindHost(server);
            SettlerEntity.events = instance;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (instance != null) instance.executor.shutdownNow();
            instance = null;
            SettlerEntity.events = SettlerEvents.NONE;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (instance == null) return;
            if (server.getTicks() % THINK_EVERY_TICKS == 0) instance.tick();
            if (server.getTicks() % BOARD_UPKEEP_TICKS == 0) instance.boardUpkeep();
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (instance != null) instance.onPlayerChat(sender, message.getContent().getString());
        });
    }

    public static @Nullable MindHost get() { return instance; }

    // ------------------------------------------------------------------ main loop

    private void tick() {
        for (ServerWorld world : server.getWorlds()) {
            for (SettlerEntity s : world.getEntitiesByType(SettlerMind.SETTLER, Entity::isAlive)) {
                tickSettler(s, world.getTime());
            }
        }
    }

    private void tickSettler(SettlerEntity s, long now) {
        Mind mind = mindFor(s);
        mind.needs().hunger = s.getFoodLevel() / 20.0;
        mind.needs().safety = s.getHealth() / s.getMaxHealth();
        mind.needs().decay(mind.persona(), THINK_EVERY_TICKS / 20.0);
        if (s.isPaused()) return;

        UUID id = s.getUuid();
        if (s.isIdleForMind()) {
            long since = now - lastBusyTick.computeIfAbsent(id, k -> now);
            if (since > config.idleThinkSeconds * 20L && !mind.hasPending() && !mind.isThinking()) {
                mind.observe(new MindEvent.Idle(mind.needs().mostPressing()), now);
                lastBusyTick.put(id, now);
            }
        } else {
            lastBusyTick.put(id, now);
        }

        CognitionRequest req = mind.nextRequest(now, () -> PerceptionBuilder.build(s, store.board, boardNow()));
        if (req == null) return;
        RouteConfig route = config.route(req.route());
        Cognition cognition = choose(id, route);
        cognition.complete(req, route).whenComplete((res, err) -> {
            CognitionResult result = err != null
                    ? CognitionResult.failed(cognition.provider(), route.model(), 0, err.toString())
                    : res;
            server.execute(() -> apply(id, req, result));
        });
    }

    /** Pick the configured provider, or fall back to the scripted mind (missing provider, budget). */
    private Cognition choose(UUID settler, RouteConfig route) {
        Cognition c = providers.get(route.provider());
        if (c == null) {
            warnOnce("provider:" + route.provider(), "Provider '" + route.provider() + "' unavailable; using scripted mind");
            return scripted;
        }
        if (c == scripted) return c;
        String overBudget = budgetBlock(settler);
        if (overBudget != null) {
            warnOnce("budget:" + overBudget, "AI budget reached (" + overBudget + "); settlers use the scripted mind until it resets");
            return scripted;
        }
        long now = System.currentTimeMillis();
        callsBySettler.computeIfAbsent(settler, k -> new ArrayDeque<>()).addLast(now);
        serverCalls.addLast(now);
        return c;
    }

    private @Nullable String budgetBlock(UUID settler) {
        long now = System.currentTimeMillis();
        long hourAgo = now - 3_600_000L;
        Deque<Long> mine = callsBySettler.computeIfAbsent(settler, k -> new ArrayDeque<>());
        while (!mine.isEmpty() && mine.peekFirst() < hourAgo) mine.pollFirst();
        while (!serverCalls.isEmpty() && serverCalls.peekFirst() < hourAgo) serverCalls.pollFirst();
        if (now - usdDayStart > 86_400_000L) {
            usdDayStart = now;
            usdAtDayStart = ledger.totalUsd();
        }
        if (mine.size() >= config.budget.callsPerSettlerPerHour) return "per-settler hourly calls";
        if (serverCalls.size() >= config.budget.callsPerServerPerHour) return "server hourly calls";
        if (ledger.totalUsd() - usdAtDayStart >= config.budget.maxEstimatedUsdPerDay) return "daily spend";
        return null;
    }

    private void apply(UUID id, CognitionRequest req, CognitionResult res) {
        ledger.record(req.route(), res);
        Mind mind = store.minds.get(id);
        if (mind == null) return; // dismissed meanwhile
        SettlerEntity s = find(id);
        long now = s != null ? s.getWorld().getTime() : server.getOverworld().getTime();
        if (!res.ok()) SettlerMind.LOG.debug("{} could not think ({}): {}", mind.persona().name(), req.route(), res.error());
        Decision d = mind.onResult(req, res, now);
        if (d == null || s == null || !s.isAlive()) return;
        if (!d.steps().isEmpty()) rememberSeenBoard(id, req.perception());
        String say = mind.acceptSpeech(d, now);
        if (say != null) speak(s, say, d.sayTo());
        if (!d.steps().isEmpty()) s.setPlan(d.steps());
    }

    // ------------------------------------------------------------------ hearing and speaking

    private void onPlayerChat(ServerPlayerEntity sender, String text) {
        boolean shareable = !config.requireChatConsent || !config.anyRemoteProvider()
                || store.chatConsent.contains(sender.getUuid());
        String speaker = sender.getGameProfile().getName();
        for (SettlerEntity s : sender.getServerWorld().getEntitiesByType(SettlerMind.SETTLER, Entity::isAlive)) {
            if (s.squaredDistanceTo(sender) > (double) config.hearingRadius * config.hearingRadius) continue;
            boolean addressed = mentions(text, s.getSettlerName()) || mentionsEveryone(text);
            hear(s, speaker, "player", text, addressed, shareable);
        }
    }

    /** Deliver speech to a settler's mind (also used by tests). */
    public void hear(SettlerEntity s, String speaker, String kind, String text, boolean addressed, boolean shareable) {
        mindFor(s).observe(new MindEvent.Heard(speaker, kind, text, addressed, shareable), s.getWorld().getTime());
    }

    private void speak(SettlerEntity s, String text, @Nullable String to) {
        Text line = Text.literal("<" + s.getSettlerName() + "> ").formatted(Formatting.GOLD)
                .append(Text.literal(text).formatted(Formatting.WHITE));
        double r2 = (double) config.hearingRadius * config.hearingRadius;
        for (ServerPlayerEntity p : ((ServerWorld) s.getWorld()).getPlayers()) {
            if (p.squaredDistanceTo(s) <= r2) p.sendMessage(line, false);
        }
        for (SettlerEntity other : ((ServerWorld) s.getWorld()).getEntitiesByType(SettlerMind.SETTLER, Entity::isAlive)) {
            if (other == s || other.squaredDistanceTo(s) > r2) continue;
            boolean addressed = (to != null && to.equalsIgnoreCase(other.getSettlerName())) || mentions(text, other.getSettlerName());
            hear(other, s.getSettlerName(), "settler", text, addressed, true);
        }
        SettlerMind.LOG.info("<{}> {}", s.getSettlerName(), text);
    }

    static boolean mentions(String text, String name) {
        return Pattern.compile("(?i)(^|\\W)" + Pattern.quote(name) + "(\\W|$)").matcher(text).find();
    }

    static boolean mentionsEveryone(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("everyone") || t.contains("everybody") || t.contains("settlers");
    }

    // ------------------------------------------------------------------ body events

    @Override
    public void onStepFinished(SettlerEntity s, Decision.Step step, Outcome outcome, boolean planDone) {
        mindFor(s).observe(new MindEvent.ActionFinished(step.describe(), step.kind(), outcome.status().name(),
                outcome.code(), outcome.detail(), planDone), s.getWorld().getTime());
        if ("mine".equals(step.kind()) && outcome.status() == Outcome.Status.SUCCEEDED) creditGathering(s, outcome.detail());
    }

    @Override
    public void onHurt(SettlerEntity s, String attacker, float amount) {
        mindFor(s).observe(new MindEvent.Hurt(attacker, amount), s.getWorld().getTime());
    }

    @Override
    public void onDismissed(SettlerEntity s) {
        store.minds.remove(s.getUuid());
        lastBusyTick.remove(s.getUuid());
        seenRevisions.remove(s.getUuid());
        String name = s.getSettlerName();
        long now = boardNow();
        for (BoardTask t : List.copyOf(store.board.tasks())) {
            if (t.status == BoardTask.Status.CLAIMED && name.equals(t.claimant)) store.board.release(t.id, name, now);
        }
        store.board.releaseAll(name);
    }

    // ------------------------------------------------------------------ settlement board

    /** Board time: the overworld clock, so every dimension agrees. */
    private long boardNow() { return server.getOverworld().getTime(); }

    public Board board() { return store.board; }

    /** The revisions the settler saw when it made the decision now being carried out (the claim fencing token). */
    private void rememberSeenBoard(UUID id, Perception seen) {
        Map<String, Integer> revs = new HashMap<>();
        if (seen != null) for (TaskView v : seen.board()) revs.put(v.id().toUpperCase(Locale.ROOT), v.revision());
        seenRevisions.put(id, revs);
    }

    @Override
    public Outcome onBoardStep(SettlerEntity s, Decision.Step step) {
        String name = s.getSettlerName();
        long now = boardNow();
        Board.Result r;
        switch (step.kind()) {
            case "post_task" -> {
                if (step.item() == null || !itemExists(step.item())) {
                    return Outcome.fail(Board.INVALID_ITEM, "no such item: " + step.item());
                }
                r = store.board.post(name, BoardTask.KIND_SETTLER, step.item(), step.count() == null ? 0 : step.count(), null, now);
                if (r.ok()) announce(r.task().id + ": " + name + " asks for " + r.task().count + " " + r.task().item);
            }
            case "claim_task" -> {
                String id = step.task() == null ? "" : step.task().trim().toUpperCase(Locale.ROOT);
                Integer seen = seenRevisions.getOrDefault(s.getUuid(), Map.of()).get(id);
                // Never seen: fail closed as stale rather than claim blind (not_found/not_open still take precedence).
                r = store.board.claim(id, name, seen == null ? Integer.MIN_VALUE : seen, now);
            }
            case "release_task" -> {
                String id = step.task() == null ? "" : step.task().trim().toUpperCase(Locale.ROOT);
                r = store.board.release(id, name, now);
            }
            default -> {
                return Outcome.fail("unknown_step", step.kind());
            }
        }
        if (!r.ok()) return Outcome.fail(r.code(), step.describe());
        BoardTask t = r.task();
        return Outcome.ok(t.id + " gather " + t.count + " " + t.item + " (" + t.status.name().toLowerCase(Locale.ROOT)
                + ", rev " + t.revision + ")");
    }

    @Override
    public boolean reserveBlock(SettlerEntity s, BlockPos pos) {
        return store.board.reserve(PerceptionBuilder.posKey(s.getWorld(), pos), s.getSettlerName(), boardNow());
    }

    @Override
    public void releaseBlock(SettlerEntity s, BlockPos pos) {
        // A settler works one block at a time, so releasing its reservations releases this one.
        if (s.getSettlerName().equals(store.board.holder(PerceptionBuilder.posKey(s.getWorld(), pos), boardNow()))) {
            store.board.releaseAll(s.getSettlerName());
        }
    }

    /** Credit verified gathering ("+n namespace:item" in a SUCCEEDED outcome) to the settler's claims. */
    private void creditGathering(SettlerEntity s, String detail) {
        if (detail == null) return;
        Matcher m = GAINED.matcher(detail);
        long now = boardNow();
        while (m.find()) {
            int n;
            try {
                n = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            for (BoardTask t : store.board.credit(s.getSettlerName(), m.group(3), n, now)) {
                String what = t.id + " (gather " + t.count + " " + t.item + ")";
                if (t.status == BoardTask.Status.DONE) {
                    mindFor(s).observe(new MindEvent.Board("Your task " + what + " is done.", false), s.getWorld().getTime());
                    notifyPoster(t, "Task " + what + " is done, thanks to " + s.getSettlerName() + ".");
                } else {
                    mindFor(s).observe(new MindEvent.Board("Task " + what + " progress " + t.progress + "/" + t.count + ".", false),
                            s.getWorld().getTime());
                }
            }
        }
    }

    /** Lapse abandoned claims (telling the former claimant) and drop long-closed tasks. */
    private void boardUpkeep() {
        long now = boardNow();
        for (Board.Expired e : store.board.expire(now)) {
            SettlerEntity former = findByName(e.formerClaimant());
            if (former != null) {
                mindFor(former).observe(new MindEvent.Board("Your claim on " + e.task().id + " (gather " + e.task().count + " "
                        + e.task().item + ") lapsed after no progress; it is open again.", true), former.getWorld().getTime());
            }
        }
        store.board.prune(now);
    }

    /** Post a task for a player (command). Item must exist in the item registry. */
    public Board.Result postTask(String player, Identifier item, int count, String note) {
        if (!Registries.ITEM.containsId(item) || Registries.ITEM.get(item) == Items.AIR) return new Board.Result(Board.INVALID_ITEM, null);
        Board.Result r = store.board.post(player, BoardTask.KIND_PLAYER, item.getPath(), count, note, boardNow());
        if (r.ok()) announce(r.task().id + ": " + player + " asks for " + r.task().count + " " + r.task().item
                + (r.task().note.isEmpty() ? "" : " (\"" + r.task().note + "\")"));
        return r;
    }

    /** Cancel a task (command): poster or admin. Tells the claimant, if any. */
    public Board.Result cancelTask(String id, String by, boolean admin) {
        BoardTask before = store.board.task(id == null ? null : id.trim().toUpperCase(Locale.ROOT));
        String claimant = before == null ? null : before.claimant;
        Board.Result r = store.board.cancel(before == null ? id : before.id, by, admin, boardNow());
        if (r.ok() && claimant != null) {
            SettlerEntity c = findByName(claimant);
            if (c != null) {
                mindFor(c).observe(new MindEvent.Board(by + " cancelled task " + r.task().id + " (gather " + r.task().count + " "
                        + r.task().item + "); you no longer hold it.", true), c.getWorld().getTime());
            }
        }
        return r;
    }

    /** True if some registered item (any namespace, minecraft first) has this path. */
    static boolean itemExists(String path) {
        if (path == null || !Board.ITEM.matcher(path).matches() || path.equals("air")) return false;
        if (Registries.ITEM.containsId(new Identifier("minecraft", path))) return true;
        for (Identifier id : Registries.ITEM.getIds()) if (id.getPath().equals(path)) return true;
        return false;
    }

    /** New posts are public settlement news: tell every online player. */
    private void announce(String text) {
        Text line = Text.literal("[Board] ").formatted(Formatting.AQUA).append(Text.literal(text).formatted(Formatting.WHITE));
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) p.sendMessage(line, false);
        SettlerMind.LOG.info("[Board] {}", text);
    }

    private void notifyPoster(BoardTask t, String text) {
        if (BoardTask.KIND_SETTLER.equals(t.postedByKind)) {
            SettlerEntity poster = findByName(t.postedBy);
            if (poster != null && !poster.getSettlerName().equals(t.claimant)) {
                mindFor(poster).observe(new MindEvent.Board(text, false), poster.getWorld().getTime());
            }
        } else {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(t.postedBy);
            if (p != null) p.sendMessage(Text.literal("[Board] ").formatted(Formatting.AQUA)
                    .append(Text.literal(text).formatted(Formatting.WHITE)), false);
        }
    }

    private @Nullable SettlerEntity findByName(String name) {
        if (name == null) return null;
        for (ServerWorld w : server.getWorlds()) {
            for (SettlerEntity s : w.getEntitiesByType(SettlerMind.SETTLER, Entity::isAlive)) {
                if (s.getSettlerName().equals(name)) return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ access

    public Mind mindFor(SettlerEntity s) {
        return store.minds.computeIfAbsent(s.getUuid(), id -> {
            String owner = s.getOwnerUuid() == null ? null
                    : server.getUserCache() == null ? null
                    : server.getUserCache().getByUuid(s.getOwnerUuid()).map(p -> p.getName()).orElse(null);
            long seed = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
            return new Mind(id.toString(), Persona.generate(s.getSettlerName(), seed), owner);
        });
    }

    public @Nullable Mind existingMind(UUID id) { return store.minds.get(id); }

    public void setConsent(UUID player, boolean consent) {
        if (consent) store.chatConsent.add(player);
        else store.chatConsent.remove(player);
    }

    public boolean hasConsent(UUID player) { return store.chatConsent.contains(player); }
    public SettlerMindConfig config() { return config; }
    public UsageLedger ledger() { return ledger; }

    private @Nullable SettlerEntity find(UUID id) {
        for (ServerWorld w : server.getWorlds()) {
            if (w.getEntity(id) instanceof SettlerEntity s) return s;
        }
        return null;
    }

    private void warnOnce(String key, String msg) {
        if (warned.add(key)) SettlerMind.LOG.warn(msg);
    }
}
