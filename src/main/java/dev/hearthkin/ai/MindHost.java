package dev.hearthkin.ai;

import dev.hearthkin.Hearthkin;
import dev.hearthkin.entity.SettlerEntity;
import dev.hearthkin.entity.SettlerEvents;
import dev.hearthkin.mind.Decision;
import dev.hearthkin.mind.Mind;
import dev.hearthkin.mind.MindEvent;
import dev.hearthkin.mind.Persona;
import dev.hearthkin.mind.cognition.ClaudeCognition;
import dev.hearthkin.mind.cognition.Cognition;
import dev.hearthkin.mind.cognition.CognitionRequest;
import dev.hearthkin.mind.cognition.CognitionResult;
import dev.hearthkin.mind.cognition.RouteConfig;
import dev.hearthkin.mind.cognition.ScriptedCognition;
import dev.hearthkin.mind.cognition.UsageLedger;
import dev.hearthkin.skill.Outcome;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static @Nullable MindHost instance;

    private final MinecraftServer server;
    private final HearthkinConfig config;
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

    private MindHost(MinecraftServer server) {
        this.server = server;
        this.config = HearthkinConfig.load();
        this.store = server.getOverworld().getPersistentStateManager()
                .getOrCreate(MindStore::fromNbt, MindStore::new, MindStore.ID);
        this.ledger = new UsageLedger(config.prices);
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "hearthkin-mind");
            t.setDaemon(true);
            return t;
        });
        providers.put("scripted", scripted);
        boolean wantsClaude = config.routes.values().stream().anyMatch(r -> r.provider().equals("claude"));
        if (wantsClaude) {
            String key = System.getenv(config.claude.apiKeyEnv);
            if (key == null || key.isBlank()) {
                Hearthkin.LOG.warn("Routes use Claude but {} is not set; those routes will use the offline scripted mind.",
                        config.claude.apiKeyEnv);
            } else {
                providers.put("claude", new ClaudeCognition(key, Duration.ofSeconds(config.claude.timeoutSeconds),
                        config.claude.refusalFallbacks, executor));
            }
        }
        Hearthkin.LOG.info("Hearthkin minds ready: {} stored, routes {}", store.minds.size(), config.routes);
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
            if (instance != null && server.getTicks() % THINK_EVERY_TICKS == 0) instance.tick();
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (instance != null) instance.onPlayerChat(sender, message.getContent().getString());
        });
    }

    public static @Nullable MindHost get() { return instance; }

    // ------------------------------------------------------------------ main loop

    private void tick() {
        for (ServerWorld world : server.getWorlds()) {
            for (SettlerEntity s : world.getEntitiesByType(Hearthkin.SETTLER, Entity::isAlive)) {
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

        CognitionRequest req = mind.nextRequest(now, () -> PerceptionBuilder.build(s));
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
        if (!res.ok()) Hearthkin.LOG.debug("{} could not think ({}): {}", mind.persona().name(), req.route(), res.error());
        Decision d = mind.onResult(req, res, now);
        if (d == null || s == null || !s.isAlive()) return;
        String say = mind.acceptSpeech(d, now);
        if (say != null) speak(s, say, d.sayTo());
        if (!d.steps().isEmpty()) s.setPlan(d.steps());
    }

    // ------------------------------------------------------------------ hearing and speaking

    private void onPlayerChat(ServerPlayerEntity sender, String text) {
        boolean shareable = !config.requireChatConsent || !config.anyRemoteProvider()
                || store.chatConsent.contains(sender.getUuid());
        String speaker = sender.getGameProfile().getName();
        for (SettlerEntity s : sender.getServerWorld().getEntitiesByType(Hearthkin.SETTLER, Entity::isAlive)) {
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
        for (SettlerEntity other : ((ServerWorld) s.getWorld()).getEntitiesByType(Hearthkin.SETTLER, Entity::isAlive)) {
            if (other == s || other.squaredDistanceTo(s) > r2) continue;
            boolean addressed = (to != null && to.equalsIgnoreCase(other.getSettlerName())) || mentions(text, other.getSettlerName());
            hear(other, s.getSettlerName(), "settler", text, addressed, true);
        }
        Hearthkin.LOG.info("<{}> {}", s.getSettlerName(), text);
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
    }

    @Override
    public void onHurt(SettlerEntity s, String attacker, float amount) {
        mindFor(s).observe(new MindEvent.Hurt(attacker, amount), s.getWorld().getTime());
    }

    @Override
    public void onDismissed(SettlerEntity s) {
        store.minds.remove(s.getUuid());
        lastBusyTick.remove(s.getUuid());
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
    public HearthkinConfig config() { return config; }
    public UsageLedger ledger() { return ledger; }

    private @Nullable SettlerEntity find(UUID id) {
        for (ServerWorld w : server.getWorlds()) {
            if (w.getEntity(id) instanceof SettlerEntity s) return s;
        }
        return null;
    }

    private void warnOnce(String key, String msg) {
        if (warned.add(key)) Hearthkin.LOG.warn(msg);
    }
}
