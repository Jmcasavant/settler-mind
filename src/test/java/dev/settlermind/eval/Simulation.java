package dev.settlermind.eval;

import dev.settlermind.mind.Decision;
import dev.settlermind.mind.DecisionParser;
import dev.settlermind.mind.Memory;
import dev.settlermind.mind.Mind;
import dev.settlermind.mind.MindEvent;
import dev.settlermind.mind.MindFeatures;
import dev.settlermind.mind.Perception;
import dev.settlermind.mind.Persona;
import dev.settlermind.mind.cognition.Cognition;
import dev.settlermind.mind.cognition.CognitionRequest;
import dev.settlermind.mind.cognition.CognitionResult;
import dev.settlermind.mind.cognition.RouteConfig;
import dev.settlermind.mind.cognition.UsageLedger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Runs minds in a small text world with no Minecraft: deterministic rules for what
 * actions do, speech routed by distance, and full logging of what each settler heard,
 * said, decided and did. Same Mind code as the game; only the body is simulated.
 */
public final class Simulation {
    static final int HEARING = 24;
    static final long STEP = 20;              // one simulated second per step
    static final long IDLE_TICKS = 1200;      // a minute of nothing -> idle event
    /** Blocks that break by hand but drop nothing (the classic false-success trap). */
    static final List<String> NEEDS_PICKAXE = List.of("stone", "iron_ore", "coal_ore");
    private static final Pattern CLAIMS_DONE = Pattern.compile(
            "(?i)\\b(i('ve| have)? (got|mined|collected|chopped|finished|done) (it|that|the|some)|all done|here you go)\\b");

    public static final class Agent {
        public final String name;
        public final Mind mind;
        final int[] pos;
        final Map<String, Integer> inventory = new LinkedHashMap<>();
        final Deque<Decision.Step> plan = new ArrayDeque<>();
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

    final Scenario scenario;
    final Map<String, Agent> agents = new LinkedHashMap<>();
    final Map<String, String> blocks;
    final Function<String, Cognition> providerForRoute;
    final Function<String, RouteConfig> routeConfig;
    final UsageLedger ledger;
    final int maxCalls;
    public final List<LogLine> log = new ArrayList<>();
    public final Metrics metrics = new Metrics();
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
        for (Scenario.SettlerSpec spec : scenario.settlers()) {
            Persona p = Persona.generate(spec.name(), spec.seed());
            p.traits().putAll(spec.traits());
            agents.put(spec.name(), new Agent(spec.name(), new Mind(spec.name(), p, spec.owner(), features), spec.pos().clone()));
        }
    }

    public void run() {
        List<Scenario.Line> ordered = new ArrayList<>(scenario.script());
        ordered.sort(java.util.Comparator.comparingLong(Scenario.Line::tick));
        Deque<Scenario.Line> script = new ArrayDeque<>(ordered);
        for (tick = 0; tick <= scenario.durationTicks() && !truncated; tick += STEP) {
            while (!script.isEmpty() && script.peekFirst().tick() <= tick) playerSays(script.pollFirst());
            for (Agent a : agents.values()) stepAgent(a);
        }
    }

    /** Ask every settler the interview questions, spaced out so reply limits don't interfere. */
    public Map<String, List<String[]>> interview() {
        Map<String, List<String[]>> answers = new LinkedHashMap<>();
        for (Agent a : agents.values()) {
            List<String[]> qa = new ArrayList<>();
            for (String q : scenario.interview()) {
                tick += 3000;
                a.mind.observe(new MindEvent.Heard("Interviewer", "player", q, true, true), tick);
                Decision d = think(a);
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
        if (!a.plan.isEmpty()) {
            execute(a, a.plan.pollFirst());
            a.idleSince = tick;
        } else if (tick - a.idleSince > IDLE_TICKS) {
            a.mind.observe(new MindEvent.Idle(a.mind.needs().mostPressing()), tick);
            a.idleSince = tick;
        }
        a.mind.needs().decay(a.mind.persona(), STEP / 20.0);
        Decision d = think(a);
        if (d == null) return;
        String said = a.mind.acceptSpeech(d, tick);
        if (said != null) speak(a, said, d.sayTo());
        metrics.decision(a.name, d, tick);
        if (!d.steps().isEmpty()) {
            a.plan.clear();
            a.plan.addAll(d.steps());
            log.add(new LogLine(tick, a.name, "plan", d.steps().stream().map(Decision.Step::describe).toList().toString()));
        }
    }

    /** One synchronous think if the mind wants to; null otherwise. */
    private Decision think(Agent a) {
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
            DecisionParser.Result parsed = DecisionParser.parse(res.text(), req.perception(), a.mind.features(), a.name);
            metrics.parsed(parsed);
        }
        Decision d = a.mind.onResult(req, res, tick);
        if (!req.route().equals("decide")) {
            log.add(new LogLine(tick, a.name, req.route(), res.ok() ? res.text() : "error: " + res.error()));
        }
        return d;
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

    private void execute(Agent a, Decision.Step step) {
        String status = "SUCCEEDED", code = "ok", detail = "";
        switch (step.kind()) {
            case "mine" -> {
                String k = Scenario.key(step.x(), step.y(), step.z());
                String id = blocks.get(k);
                if (id == null) {
                    status = "FAILED"; code = "target_missing";
                } else {
                    blocks.remove(k);
                    if (NEEDS_PICKAXE.contains(id) && !a.inventory.containsKey("wooden_pickaxe")) {
                        status = "PARTIAL"; code = "broken_not_collected"; detail = id + " by hand drops nothing";
                    } else {
                        String drop = id.equals("stone") ? "cobblestone" : id;
                        a.inventory.merge(drop, 1, Integer::sum);
                        detail = "+1 " + drop;
                    }
                }
            }
            case "go_to" -> { a.pos[0] = step.x(); a.pos[1] = step.y(); a.pos[2] = step.z(); }
            case "follow", "stay", "wait" -> { }
            default -> { status = "FAILED"; code = "unknown_step"; }
        }
        if (status.equals("SUCCEEDED")) a.lastSuccessTick = tick;
        else a.plan.clear();
        metrics.outcome(a.name, step.kind(), status, tick);
        log.add(new LogLine(tick, a.name, "act", step.describe() + " -> " + status + " " + code + (detail.isEmpty() ? "" : " (" + detail + ")")));
        a.mind.observe(new MindEvent.ActionFinished(step.describe(), step.kind(), status, code, detail, a.plan.isEmpty()), tick);
    }

    Perception perceive(Agent a) {
        List<Perception.SeenBlock> seen = new ArrayList<>();
        blocks.forEach((k, id) -> {
            String[] p = k.split(",");
            int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
            double d = dist(a.pos, new int[]{x, y, z});
            if (d <= 10) seen.add(new Perception.SeenBlock(id, x, y, z, d));
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
                a.plan.isEmpty() ? "standing around" : "busy", seen, ents, List.of());
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
