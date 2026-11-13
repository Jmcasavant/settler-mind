package dev.settlermind.eval;

import dev.settlermind.mind.Decision;
import dev.settlermind.mind.DecisionParser;
import dev.settlermind.mind.Memory;
import dev.settlermind.mind.Mind;
import dev.settlermind.mind.MindEvent;
import dev.settlermind.mind.MindFeatures;
import dev.settlermind.mind.Perception;
import dev.settlermind.mind.Persona;
import dev.settlermind.mind.board.Board;
import dev.settlermind.mind.board.BoardTask;
import dev.settlermind.mind.board.TaskView;
import dev.settlermind.mind.cognition.Cognition;
import dev.settlermind.mind.cognition.CognitionRequest;
import dev.settlermind.mind.cognition.CognitionResult;
import dev.settlermind.mind.cognition.RouteConfig;
import dev.settlermind.mind.cognition.UsageLedger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

/**
 * Runs minds in a small text world with no Minecraft: deterministic rules for what
 * actions do, speech routed by distance, and full logging of what each settler heard,
 * said, decided and did. Same Mind code as the game; only the body is simulated.
 *
 * <p>Host fidelity (docs/EVAL.md "How the simulation hosts the board"): a decision is computed from a
 * perception snapshot and applied {@link #decisionLatencySteps} steps later, as the host applies a model
 * result on a later server tick; mining reserves the block, breaks it over {@link #breakSteps} steps and
 * collects on the next, holding the reservation throughout, like the host's APPROACH, BREAK, COLLECT;
 * a new plan cancels a mine in progress; settler posts must name a known, namespaced item.
 */
public final class Simulation {
    static final int HEARING = 24;
    static final long STEP = 20;              // one simulated second per step
    static final long IDLE_TICKS = 1200;      // a minute of nothing -> idle event
    static final long BOARD_UPKEEP = 200;     // the host's ~10 s board expire/prune
    /** Default steps (seconds) between a decide call's perception snapshot and its result being applied. */
    public static final int DEFAULT_DECISION_LATENCY_STEPS = 2;
    /** Default steps a block takes to break (a log by hand takes about 3 s in the game). */
    public static final int DEFAULT_BREAK_STEPS = 2;
    /** Blocks that break by hand but drop nothing (the classic false-success trap). */
    static final List<String> NEEDS_PICKAXE = List.of("stone", "iron_ore", "coal_ore");
    /** Items the simulated world knows besides its own blocks (the host checks the item registry instead). */
    static final List<String> KNOWN_ITEMS = List.of("oak_log", "birch_log", "spruce_log", "oak_planks", "stick",
            "stone", "cobblestone", "iron_ore", "coal_ore", "coal", "raw_iron", "dirt", "wooden_pickaxe",
            "stone_pickaxe", "bread", "apple", "wheat", "torch", "crafting_table");
    private static final Pattern CLAIMS_DONE = Pattern.compile(
            "(?i)\\b(i('ve| have)? (got|mined|collected|chopped|finished|done) (it|that|the|some)|all done|here you go)\\b");

    public static final class Agent {
        public final String name;
        public final Mind mind;
        final int[] pos;
        final Map<String, Integer> inventory = new LinkedHashMap<>();
        final Deque<Decision.Step> plan = new ArrayDeque<>();
        /**
         * Task id -> revision as this settler saw it in the perception its current plan was decided from: the
         * fencing token {@code claim_task} passes to {@link Board#claim}. Like the host, taken from the request's
         * snapshot, not from the board when the result lands.
         */
        final Map<String, Integer> seenRevisions = new HashMap<>();
        /** A model call whose result has not been applied yet (decision latency). */
        Thought inFlight;
        /** The mine in progress, if any (it spans several steps). */
        MineJob job;
        long idleSince;
        long lastSuccessTick = Long.MIN_VALUE;

        Agent(String name, Mind mind, int[] pos) {
            this.name = name;
            this.mind = mind;
            this.pos = pos;
        }
    }

    /** One logged thing that happened, for the report and the judge. */
    public record LogLine(long tick, String who, String kind, String text) {}

    /** A model call made at {@code requestTick} from {@code req.perception()}, applied at {@code applyAt}. */
    record Thought(CognitionRequest req, CognitionResult res, long requestTick, long applyAt) {}

    /** A mine in progress: reserved at the start, broken after {@link #breakSteps} steps, collected on the next. */
    static final class MineJob {
        final Decision.Step step;
        final String key;
        final String blockId;
        int steps;
        boolean broken;

        MineJob(Decision.Step step, String key, String blockId) {
            this.step = step;
            this.key = key;
            this.blockId = blockId;
        }
    }

    final Scenario scenario;
    final Map<String, Agent> agents = new LinkedHashMap<>();
    final Map<String, String> blocks;
    final Function<String, Cognition> providerForRoute;
    final Function<String, RouteConfig> routeConfig;
    final UsageLedger ledger;
    final int maxCalls;
    final MindFeatures features;
    final Set<String> knownItems = new HashSet<>();
    /** The settlement board (one per simulation), with the same semantics as the game host. */
    public final Board board = new Board();
    public final List<LogLine> log = new ArrayList<>();
    public final Metrics metrics = new Metrics();
    /** Steps between a decide call's snapshot and its result being applied; 0 applies it in the same step. */
    public int decisionLatencySteps = DEFAULT_DECISION_LATENCY_STEPS;
    /** Steps a block takes to break once started; the drop is collected on the step after. */
    public int breakSteps = DEFAULT_BREAK_STEPS;
    /** Test hook: runs at the start of every step, before players speak and settlers act. */
    LongConsumer beforeStep;
    long tick;
    int calls;
    public boolean truncated;

    public Simulation(Scenario scenario, MindFeatures features, Function<String, Cognition> providerForRoute,
                      Function<String, RouteConfig> routeConfig, UsageLedger ledger, int maxCalls) {
        this.scenario = scenario;
        this.blocks = new LinkedHashMap<>(scenario.blocks());
        this.providerForRoute = providerForRoute;
        this.routeConfig = routeConfig;
        this.ledger = ledger;
        this.maxCalls = maxCalls;
        this.features = features;
        for (String item : KNOWN_ITEMS) knownItems.add(Board.normalizeItem(item));
        for (String id : scenario.blocks().values()) {
            String full = Board.normalizeItem(id);
            if (full != null) knownItems.add(full);
        }
        for (Scenario.SettlerSpec spec : scenario.settlers()) {
            Persona p = Persona.generate(spec.name(), spec.seed());
            p.traits().putAll(spec.traits());
            agents.put(spec.name(), new Agent(spec.name(), new Mind(spec.name(), p, spec.owner(), features), spec.pos().clone()));
        }
        List<Scenario.TaskSpec> seeds = scenario.boardTasks();
        for (int i = 0; i < seeds.size(); i++) {
            Scenario.TaskSpec t = seeds.get(i);
            // Seeds are "already on the board" at tick 0; space them by the per-poster rate limit, the last at 0.
            long postedAt = -(seeds.size() - 1 - i) * Board.POST_INTERVAL_TICKS;
            // In the simulation names are unique, so a name doubles as the stable id.
            Board.Result r = board.post(t.postedBy(), t.postedByKind(), t.postedBy(), t.item(), t.count(), t.note(), postedAt);
            if (!r.ok()) throw new IllegalArgumentException("scenario " + scenario.name() + " seeds a bad task: " + r.code());
            log.add(new LogLine(0, t.postedBy(), "board", "posted " + r.task().id + ": gather " + t.count() + " " + t.item()));
        }
    }

    public void run() {
        List<Scenario.Line> ordered = new ArrayList<>(scenario.script());
        ordered.sort(java.util.Comparator.comparingLong(Scenario.Line::tick));
        Deque<Scenario.Line> script = new ArrayDeque<>(ordered);
        for (tick = 0; tick <= scenario.durationTicks() && !truncated; tick += STEP) {
            if (beforeStep != null) beforeStep.accept(tick);
            while (!script.isEmpty() && script.peekFirst().tick() <= tick) playerSays(script.pollFirst());
            if (tick > 0 && tick % BOARD_UPKEEP == 0) boardUpkeep();
            for (Agent a : agents.values()) stepAgent(a);
        }
        finishBoardMetrics();
    }

    /** The host's periodic board upkeep: lapsed claims go back to OPEN and their former claimant is told. */
    private void boardUpkeep() {
        for (Board.Affected e : board.expire(tick)) {
            if (!e.claimLapsed()) {
                log.add(new LogLine(tick, e.task().posterName, "board", e.task().id + " expired unclaimed"));
                continue;
            }
            metrics.expiredClaims++;
            BoardTask t = e.task();
            metrics.claimHistory.lapsed(tick, t.id, e.formerClaimantId());
            log.add(new LogLine(tick, e.formerClaimantName(), "board", "claim on " + t.id + " lapsed"));
            Agent a = agents.get(e.formerClaimantId());
            if (a != null && features.board()) {
                a.mind.observe(new MindEvent.Board("Your claim on " + t.id + " (gather " + t.count + " "
                        + t.item + ") lapsed after no progress.", true), tick);
            }
        }
        board.prune(tick);
    }

    /** Coordination measures at the end of the run (docs/BOARD.md "Evaluation"). */
    private void finishBoardMetrics() {
        Map<String, Integer> required = new LinkedHashMap<>();
        for (Scenario.TaskSpec t : scenario.boardTasks()) required.merge(t.item(), t.count(), Integer::sum);
        int need = 0, met = 0;
        for (Map.Entry<String, Integer> e : required.entrySet()) {
            int got = 0;
            for (Agent a : agents.values()) got += a.inventory.getOrDefault(e.getKey(), 0);
            metrics.gathered.put(e.getKey(), got);
            need += e.getValue();
            met += Math.min(got, e.getValue());
        }
        metrics.needsMet = need == 0 ? null : (double) met / need;
        metrics.tasksTotal = board.tasks().size();
        metrics.tasksDone = 0;
        metrics.ticksToDone.clear();
        metrics.boardFinal.clear();
        for (BoardTask t : board.tasks()) {
            if (t.status == BoardTask.Status.DONE) {
                metrics.tasksDone++;
                metrics.ticksToDone.put(t.id, t.doneTick - t.createdTick);
            }
            metrics.boardFinal.add(t.id + " " + t.status + " " + t.item + " " + t.progress + "/" + t.count
                    + (t.claimantName == null ? "" : " by " + t.claimantName));
        }
        metrics.callsPerDoneTask = metrics.tasksDone == 0 ? null : (double) metrics.totalCalls() / metrics.tasksDone;
        metrics.deriveFromHistory();
    }

    /** Ask every settler the interview questions, spaced out so reply limits don't interfere. */
    public Map<String, List<String[]>> interview() {
        Map<String, List<String[]>> answers = new LinkedHashMap<>();
        for (Agent a : agents.values()) {
            // Land any thought still in flight when the run ended, so the mind is free to answer.
            if (a.inFlight != null) {
                Thought t = a.inFlight;
                a.inFlight = null;
                apply(a, t);
            }
        }
        for (Agent a : agents.values()) {
            List<String[]> qa = new ArrayList<>();
            for (String q : scenario.interview()) {
                tick += 3000;
                a.mind.observe(new MindEvent.Heard("Interviewer", "player", q, true, true), tick);
                Thought t = request(a); // interviews are answered at once (no latency)
                Decision d = t == null ? null : resolve(a, t);
                String said = d == null ? null : a.mind.acceptSpeech(d, tick);
                qa.add(new String[]{q, said == null ? "(no answer)" : said});
                log.add(new LogLine(tick, a.name, "interview", q + " -> " + qa.get(qa.size() - 1)[1]));
                if (truncated) break;
            }
            answers.put(a.name, qa);
        }
        return answers;
    }

    private void playerSays(Scenario.Line line) {
        log.add(new LogLine(tick, line.player(), line.whisper() ? "whisper" : "say",
                (line.to() == null ? "" : "to " + line.to() + ": ") + line.text()));
        for (Agent a : agents.values()) {
            boolean target = line.to() != null && line.to().equals(a.name);
            if (line.whisper() && !target) continue;
            if (!line.whisper() && line.to() != null && !target && dist(a.pos, agents.get(line.to()).pos) > HEARING) continue;
            a.mind.observe(new MindEvent.Heard(line.player(), "player", line.text(), target || line.to() == null, true), tick);
            if (target && line.request()) metrics.requestAsked(a.name, line.player(), tick);
        }
    }

    private void stepAgent(Agent a) {
        // The body first: the host's entity ticks independently of the mind.
        if (a.job != null) {
            advanceMine(a);
            a.idleSince = tick;
        } else if (!a.plan.isEmpty()) {
            startStep(a, a.plan.pollFirst());
            a.idleSince = tick;
        } else if (tick - a.idleSince > IDLE_TICKS && !a.mind.hasPending() && !a.mind.isThinking()) {
            a.mind.observe(new MindEvent.Idle(a.mind.needs().mostPressing()), tick);
            a.idleSince = tick;
        }
        a.mind.needs().decay(a.mind.persona(), STEP / 20.0);
        // Then the mind: land a result that has arrived, and start a new call if the mind wants one.
        if (a.inFlight != null && tick >= a.inFlight.applyAt()) {
            Thought t = a.inFlight;
            a.inFlight = null;
            apply(a, t);
        }
        if (a.inFlight == null) {
            Thought t = request(a);
            if (t == null) return;
            if (t.applyAt() <= tick) apply(a, t);
            else a.inFlight = t;
        }
    }

    /**
     * Starts a model call if the mind wants to think: the perception snapshot is taken now and the result is
     * due {@link #decisionLatencySteps} steps later. Null if there is nothing to think about or the budget is spent.
     */
    private Thought request(Agent a) {
        CognitionRequest req = a.mind.nextRequest(tick, () -> perceive(a));
        if (req == null) return null;
        if (calls >= maxCalls) {
            truncated = true;
            a.mind.abandon(req);
            return null;
        }
        calls++;
        RouteConfig route = routeConfig.apply(req.route());
        CognitionResult res = providerForRoute.apply(req.route()).complete(req, route).join();
        ledger.record(req.route(), res);
        metrics.call(req.route(), res);
        if (res.ok() && req.route().equals("decide")) {
            DecisionParser.Result parsed = DecisionParser.parse(res.text(), req.perception(), a.mind.features());
            metrics.parsed(parsed);
        }
        return new Thought(req, res, tick, tick + Math.max(0, decisionLatencySteps) * STEP);
    }

    /** Hands a result to the mind now, as the host's {@code apply} does on the server thread. */
    private Decision resolve(Agent a, Thought t) {
        Decision d = a.mind.onResult(t.req(), t.res(), tick);
        if (!t.req().route().equals("decide")) {
            log.add(new LogLine(tick, a.name, t.req().route(), t.res().ok() ? t.res().text() : "error: " + t.res().error()));
        }
        return d;
    }

    /** Applies an arrived result like MindHost.apply: speech, then a new plan replacing the old one. */
    private void apply(Agent a, Thought t) {
        Decision d = resolve(a, t);
        if (d == null) return;
        String said = a.mind.acceptSpeech(d, tick);
        if (said != null) speak(a, said, d.sayTo());
        metrics.decision(a.name, d, tick);
        if (!d.steps().isEmpty()) {
            cancelMine(a); // the host's setPlan cancels the running skill
            a.plan.clear();
            a.plan.addAll(d.steps());
            // The fencing token: revisions as seen in the snapshot this decision was made from, not as they are now.
            a.seenRevisions.clear();
            for (TaskView v : t.req().perception().board()) a.seenRevisions.put(v.id().toUpperCase(Locale.ROOT), v.revision());
            log.add(new LogLine(tick, a.name, "plan", d.steps().stream().map(Decision.Step::describe).toList()
                    + (t.requestTick() < tick ? " (decided on the view at " + t.requestTick() + ")" : "")));
        }
    }

    private void speak(Agent from, String text, String to) {
        log.add(new LogLine(tick, from.name, "say", (to == null ? "" : "to " + to + ": ") + text));
        if (CLAIMS_DONE.matcher(text).find() && tick - from.lastSuccessTick > 600) metrics.unfoundedClaim(from.name, text, tick);
        for (Agent other : agents.values()) {
            if (other == from || dist(other.pos, from.pos) > HEARING) continue;
            boolean addressed = to != null && to.equalsIgnoreCase(other.name)
                    || text.toLowerCase(Locale.ROOT).contains(other.name.toLowerCase(Locale.ROOT));
            other.mind.observe(new MindEvent.Heard(from.name, "settler", text, addressed, true), tick);
        }
    }

    /** Starts a plan step. Everything but mining finishes in the same step; a mine becomes a {@link MineJob}. */
    private void startStep(Agent a, Decision.Step step) {
        switch (step.kind()) {
            case "mine" -> {
                String k = Scenario.key(step.x(), step.y(), step.z());
                String id = blocks.get(k);
                // Like the host's APPROACH: a missing block fails first, then the reservation must be ours.
                if (id == null) {
                    metrics.wastedWork++;
                    finish(a, step, "FAILED", "target_missing", "nothing to mine", List.of());
                } else if (!board.reserve(k, a.name, a.name, tick)) {
                    metrics.reservationConflicts++;
                    finish(a, step, "FAILED", "reserved", board.holderName(k, tick) + " is working on it", List.of());
                } else {
                    a.job = new MineJob(step, k, id);
                }
            }
            case "go_to" -> {
                a.pos[0] = step.x(); a.pos[1] = step.y(); a.pos[2] = step.z();
                finish(a, step, "SUCCEEDED", "ok", "", List.of());
            }
            case "follow", "stay", "wait" -> finish(a, step, "SUCCEEDED", "ok", "", List.of());
            case "post_task", "claim_task", "release_task", "cancel_task" -> {
                String code = boardStep(a, step);
                finish(a, step, code.equals(Board.OK) ? "SUCCEEDED" : "FAILED", code, "", List.of());
            }
            default -> finish(a, step, "FAILED", "unknown_step", "", List.of());
        }
    }

    /** One step of a mine in progress: BREAK (the block must stay what it was), then COLLECT. */
    private void advanceMine(Agent a) {
        MineJob j = a.job;
        j.steps++;
        if (!j.broken) {
            if (!j.blockId.equals(blocks.get(j.key))) {
                metrics.wastedWork++;
                finish(a, j.step, "FAILED", "target_changed", "block changed while mining", List.of());
                return;
            }
            if (j.steps >= breakSteps) {
                blocks.remove(j.key);
                j.broken = true;
            }
            return;
        }
        if (NEEDS_PICKAXE.contains(j.blockId) && !a.inventory.containsKey("wooden_pickaxe")) {
            finish(a, j.step, "PARTIAL", "broken_not_collected", j.blockId + " by hand drops nothing", List.of());
            return;
        }
        String drop = j.blockId.equals("stone") ? "cobblestone" : j.blockId;
        a.inventory.merge(drop, 1, Integer::sum);
        metrics.claimHistory.gained(tick, j.key, a.name, drop);
        // Verified gathering is the only thing that moves a task forward.
        List<BoardTask> credited = board.credit(a.name, drop, 1, tick);
        for (BoardTask t : credited) {
            if (t.status == BoardTask.Status.DONE) metrics.claimHistory.done(tick, t.id, a.name);
        }
        finish(a, j.step, "SUCCEEDED", "ok", "+1 " + drop, credited);
    }

    /** A new plan replaced the current one: drop the mine in progress (no outcome event, as in the host). */
    private void cancelMine(Agent a) {
        MineJob j = a.job;
        if (j == null) return;
        a.job = null;
        releaseBlock(a, j.key);
        metrics.minesCancelled++;
        log.add(new LogLine(tick, a.name, "act", j.step.describe() + " -> CANCELLED"
                + (j.broken ? " cancelled_after_break (the drop is left behind)" : "")));
    }

    /** Like the host's releaseBlock: only if this settler holds it. */
    private void releaseBlock(Agent a, String key) {
        if (a.name.equals(board.holderId(key, tick))) board.releaseAll(a.name);
    }

    /** A step's outcome: release the block (mines), tell the mind, and pass on board news. */
    private void finish(Agent a, Decision.Step step, String status, String code, String detail, List<BoardTask> credited) {
        if (a.job != null && a.job.step == step) a.job = null;
        if (step.kind().equals("mine") && step.x() != null && step.y() != null && step.z() != null) {
            releaseBlock(a, Scenario.key(step.x(), step.y(), step.z()));
        }
        if (status.equals("SUCCEEDED")) a.lastSuccessTick = tick;
        else a.plan.clear();
        metrics.outcome(a.name, step.kind(), status, tick);
        log.add(new LogLine(tick, a.name, "act", step.describe() + " -> " + status + " " + code + (detail.isEmpty() ? "" : " (" + detail + ")")));
        a.mind.observe(new MindEvent.ActionFinished(step.describe(), step.kind(), status, code, detail, a.plan.isEmpty()), tick);
        for (BoardTask t : credited) {
            boolean done = t.status == BoardTask.Status.DONE;
            String text = done ? "Your task " + t.id + " (gather " + t.count + " " + t.item + ") is done."
                    : "Progress on your task " + t.id + ": " + t.progress + "/" + t.count + " " + t.item + ".";
            log.add(new LogLine(tick, a.name, "board", text));
            if (features.board()) a.mind.observe(new MindEvent.Board(text, false), tick);
        }
    }

    /** Executes a board step instantly, as the host does (MindHost.onBoardStep), and returns the result code. */
    String boardStep(Agent a, Decision.Step step) {
        if (!features.board()) return "no_board"; // the parser already rejects these; keep the sim consistent
        String id = step.task() == null ? null : step.task().trim().toUpperCase(Locale.ROOT);
        Board.Result r;
        switch (step.kind()) {
            case "post_task" -> {
                // Like the host: the item must be a real item (here: one the simulated world knows).
                if (step.item() == null || !itemExists(step.item())) {
                    metrics.postsRejected.merge(Board.INVALID_ITEM, 1, Integer::sum);
                    log.add(new LogLine(tick, a.name, "board", "post_task " + step.item() + " -> " + Board.INVALID_ITEM));
                    return Board.INVALID_ITEM;
                }
                r = board.post(a.name, BoardTask.KIND_SETTLER, a.name, step.item(), step.count() == null ? 0 : step.count(), null, tick);
                if (r.ok()) metrics.boardPosts++;
                else metrics.postsRejected.merge(r.code(), 1, Integer::sum);
            }
            case "claim_task" -> {
                // The fencing token: the revision this settler saw in the snapshot it decided from. Never seen:
                // fail closed (MIN_VALUE never matches), as the host does.
                int seen = a.seenRevisions.getOrDefault(id, Integer.MIN_VALUE);
                BoardTask before = board.task(id);
                boolean outdated = before != null && before.revision != seen;
                r = board.claim(id, a.name, a.name, seen, tick);
                metrics.claim(r.code(), outdated);
                if (r.ok()) metrics.claimHistory.claimed(tick, r.task().id, a.name);
            }
            case "cancel_task" -> {
                r = board.cancel(id, a.name, BoardTask.KIND_SETTLER, false, tick);
                if (r.ok()) metrics.claimHistory.cancelled(tick, r.task().id);
            }
            default -> {
                r = board.release(id, a.name, tick);
                if (r.ok()) {
                    metrics.releases++;
                    metrics.claimHistory.released(tick, r.task().id, a.name);
                }
            }
        }
        log.add(new LogLine(tick, a.name, "board", step.kind() + " " + (id == null ? step.item() : id) + " -> " + r.code()));
        return r.code();
    }

    Perception perceive(Agent a) {
        List<Perception.SeenBlock> seen = new ArrayList<>();
        blocks.forEach((k, id) -> {
            String[] p = k.split(",");
            int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
            double d = dist(a.pos, new int[]{x, y, z});
            // Like the host: only someone else's reservation is shown (ids compared; here ids are names).
            String holder = board.holderId(k, tick);
            if (d <= 10) seen.add(new Perception.SeenBlock(id, x, y, z, d,
                    holder == null || holder.equals(a.name) ? null : board.holderName(k, tick)));
        });
        List<Perception.SeenEntity> ents = new ArrayList<>();
        for (String player : scenario.playersPresent()) {
            ents.add(new Perception.SeenEntity("player", player, 4, player.equals(ownerOf(a))));
        }
        for (Agent o : agents.values()) {
            double d = dist(a.pos, o.pos);
            if (o != a && d <= HEARING) ents.add(new Perception.SeenEntity("settler", o.name, d, false));
        }
        long t = tick % 24000;
        String time = t < 6000 ? "morning" : t < 12000 ? "afternoon" : t < 18000 ? "night" : "late night";
        return new Perception(tick, time, "clear", a.pos.clone(), 20, 20, "nothing", Map.copyOf(a.inventory),
                a.plan.isEmpty() && a.job == null ? "standing around" : "busy", seen, ents,
                features.board() ? board.views(a.name, tick,
                        item -> seen.stream().anyMatch(b -> Board.sameItem(b.id(), item)), task -> true)
                        : List.of()); // ablation: no board in sight
    }

    /** The sim's counterpart of MindHost.itemExists: a valid namespaced id of an item this world knows (not air). */
    boolean itemExists(String item) {
        String full = Board.normalizeItem(item);
        return full != null && !full.equals("minecraft:air") && knownItems.contains(full);
    }

    private String ownerOf(Agent a) {
        return a.mind.ownerName();
    }

    /** Who knows each tracked fact at the end (a memory containing all its keywords). */
    public Map<String, List<String>> factKnowledge() {
        Map<String, List<String>> out = new HashMap<>();
        for (Scenario.Fact f : scenario.facts()) {
            List<String> knowers = new ArrayList<>();
            for (Agent a : agents.values()) {
                for (Memory m : a.mind.memory().all()) {
                    String t = m.text().toLowerCase(Locale.ROOT);
                    if (f.keywords().stream().allMatch(t::contains)) { knowers.add(a.name); break; }
                }
            }
            out.put(f.id(), knowers);
        }
        return out;
    }

    static double dist(int[] a, int[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }
}
