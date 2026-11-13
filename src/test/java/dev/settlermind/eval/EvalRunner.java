package dev.settlermind.eval;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.settlermind.ai.SettlerMindConfig;
import dev.settlermind.mind.MindFeatures;
import dev.settlermind.mind.Persona;
import dev.settlermind.mind.cognition.ClaudeCognition;
import dev.settlermind.mind.cognition.Cognition;
import dev.settlermind.mind.cognition.RouteConfig;
import dev.settlermind.mind.cognition.ScriptedCognition;
import dev.settlermind.mind.cognition.UsageLedger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs scenarios x conditions (full mind and ablations), optionally judges interviews,
 * and writes {@code report.md} + {@code report.json}.
 *
 * <pre>
 * ./gradlew runEval                                   # free, offline (scripted mind)
 * ./gradlew runEval -Peval="--provider claude --model claude-opus-5-5 --judge claude --max-calls 300"
 * </pre>
 * Paid runs need ANTHROPIC_API_KEY and stop at --max-calls (default 200 when paid), whatever happens.
 */
public final class EvalRunner {
    public static final List<String> ALL_CONDITIONS = List.of(
            "full", "no_reflection", "no_summary", "no_relationships", "no_lessons", "no_memory", "no_board");

    public static final class Options {
        public String provider = "scripted";
        public String model = "claude-opus-5-5";
        public String effort = "low";
        public String judge = "none";
        public String judgeModel = "claude-opus-5-5";
        public List<String> scenarios = Scenarios.all().stream().map(Scenario::name).toList();
        public List<String> conditions = List.of("full");
        /** Hard cap on model calls; -1 = 200 for paid providers, unlimited for the free scripted mind. */
        public int maxCalls = -1;
        public Path out = Path.of("build", "eval", Instant.now().toString().replace(':', '-'));
        public boolean interview = true;
        /** Steps (simulated seconds) between a decision's perception snapshot and its result being applied. */
        public int latencySteps = Simulation.DEFAULT_DECISION_LATENCY_STEPS;
        /** Steps a block takes to break in the simulation. */
        public int breakSteps = Simulation.DEFAULT_BREAK_STEPS;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String v = i + 1 < args.length ? args[i + 1] : "";
                switch (args[i]) {
                    case "--provider" -> { o.provider = v; i++; }
                    case "--model" -> { o.model = v; i++; }
                    case "--effort" -> { o.effort = v; i++; }
                    case "--judge" -> { o.judge = v; i++; }
                    case "--judge-model" -> { o.judgeModel = v; i++; }
                    case "--scenarios" -> { o.scenarios = Arrays.asList(v.split(",")); i++; }
                    case "--conditions" -> { o.conditions = v.equals("all") ? ALL_CONDITIONS : Arrays.asList(v.split(",")); i++; }
                    case "--max-calls" -> { o.maxCalls = Integer.parseInt(v); i++; }
                    case "--out" -> { o.out = Path.of(v); i++; }
                    case "--no-interview" -> o.interview = false;
                    case "--latency-steps" -> { o.latencySteps = Integer.parseInt(v); i++; }
                    case "--break-steps" -> { o.breakSteps = Integer.parseInt(v); i++; }
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            return o;
        }
    }

    /** One scenario under one condition. */
    public record Result(String scenario, String condition, Metrics metrics, Map<String, List<String>> factKnowledge,
                         Map<String, Double> agreeableness, Map<String, List<String[]>> interview,
                         List<Judge.Score> scores, Map<String, UsageLedger.Line> usage, double estimatedUsd,
                         boolean truncated, List<Simulation.LogLine> log) {
        public double meanScore(java.util.function.ToIntFunction<Judge.Score> f) {
            return scores.stream().filter(s -> s.inCharacter() > 0).mapToInt(f).average().orElse(Double.NaN);
        }
    }

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args);
        List<Result> results = run(o);
        Path report = write(o, results);
        System.out.println("Report: " + report.toAbsolutePath());
    }

    public static List<Result> run(Options o) {
        SettlerMindConfig defaults = SettlerMindConfig.defaults();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Cognition mindProvider = provider(o.provider, executor);
            Cognition judgeProvider = o.judge.equals("none") ? null : provider(o.judge, executor);
            RouteConfig decide = new RouteConfig(o.provider, o.model, o.effort, 1500);
            RouteConfig slow = new RouteConfig(o.provider, o.model, "medium", 1500);
            RouteConfig judgeRoute = new RouteConfig(o.judge, o.judgeModel, "low", 1200);

            boolean free = o.provider.equals("scripted") && o.judge.equals("none");
            int budget = o.maxCalls >= 0 ? o.maxCalls : free ? Integer.MAX_VALUE : 200;
            List<Result> results = new ArrayList<>();
            for (String scenarioName : o.scenarios) {
                Scenario sc = Scenarios.byName(scenarioName);
                for (String condition : o.conditions) {
                    UsageLedger ledger = new UsageLedger(defaults.prices);
                    MindFeatures f = condition.equals("full") ? MindFeatures.FULL
                            : MindFeatures.FULL.without(condition.substring("no_".length()));
                    Simulation sim = new Simulation(sc, f, route -> mindProvider,
                            route -> route.equals("decide") ? decide : slow, ledger, budget);
                    sim.decisionLatencySteps = o.latencySteps;
                    sim.breakSteps = o.breakSteps;
                    sim.run();
                    Map<String, List<String[]>> answers = o.interview && !sim.truncated ? sim.interview() : Map.of();
                    sim.metrics.closeOpenRequests();
                    List<Judge.Score> scores = new ArrayList<>();
                    if (judgeProvider != null) {
                        Judge judge = new Judge(judgeProvider, judgeRoute, ledger);
                        answers.forEach((name, qa) -> scores.addAll(judge.score(sim.agents.get(name).mind, qa)));
                    }
                    budget -= sim.calls;
                    Map<String, Double> agree = new LinkedHashMap<>();
                    sim.agents.forEach((n, a) -> agree.put(n, a.mind.persona().trait(Persona.AGREEABLENESS)));
                    results.add(new Result(sc.name(), condition, sim.metrics, sim.factKnowledge(), agree, answers, scores,
                            ledger.snapshot(), ledger.totalUsd(), sim.truncated, sim.log));
                    if (budget <= 0) return results;
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    static Cognition provider(String name, ExecutorService executor) {
        if (name.equals("scripted")) return new ScriptedCognition();
        if (name.equals("claude")) {
            String key = System.getenv("ANTHROPIC_API_KEY");
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("ANTHROPIC_API_KEY is not set (export it, or put it in .env; see .env.example)");
            }
            System.out.println("Claude provider: ANTHROPIC_API_KEY is set (" + key.length() + " chars; value not shown)");
            return new ClaudeCognition(key, Duration.ofSeconds(60), true, executor);
        }
        throw new IllegalArgumentException("unknown provider " + name);
    }

    // ------------------------------------------------------------------ report

    static Path write(Options o, List<Result> results) throws IOException {
        Files.createDirectories(o.out);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("options", Map.of("provider", o.provider, "model", o.model, "effort", o.effort, "judge", o.judge,
                "judgeModel", o.judgeModel, "maxCalls", o.maxCalls, "latencySteps", o.latencySteps, "breakSteps", o.breakSteps));
        json.put("results", results);
        Files.writeString(o.out.resolve("report.json"), gson.toJson(json));
        Path md = o.out.resolve("report.md");
        Files.writeString(md, markdown(o, results));
        return md;
    }

    static String markdown(Options o, List<Result> results) {
        StringBuilder sb = new StringBuilder("# Settler Mind evaluation\n\n");
        sb.append("Mind: `").append(o.provider).append(o.provider.equals("scripted") ? "" : " / " + o.model + " / effort " + o.effort)
                .append("` · Judge: `").append(o.judge.equals("none") ? "none" : o.judgeModel).append("` · Max calls: ")
                .append(o.maxCalls < 0 ? "default" : String.valueOf(o.maxCalls))
                .append(" · Decision latency: ").append(o.latencySteps).append(" steps · Break: ").append(o.breakSteps)
                .append(" steps\n\n");
        if (o.provider.equals("scripted")) {
            sb.append("> Scripted mind: a deterministic baseline. Its numbers test the harness and the safety rails, "
                    + "not intelligence.\n\n");
        }
        sb.append("| Scenario | Condition | Calls | Parse errors | Unfounded claims | Requests (complied/declined/ignored) "
                + "| Fact spread | Judge char / grounded / natural | Est. $ |\n|---|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            int[] req = new int[3];
            r.metrics().requests.forEach(x -> req[x.result().equals("complied") ? 0 : x.result().equals("declined") ? 1 : 2]++);
            String spread = r.factKnowledge().isEmpty() ? "-" : r.factKnowledge().entrySet().stream()
                    .map(e -> e.getValue().size() + "/" + r.agreeableness().size()).reduce((a, b) -> a + ", " + b).orElse("-");
            String judge = r.scores().isEmpty() ? "-" : String.format(Locale.ROOT, "%.1f / %.1f / %.1f",
                    r.meanScore(Judge.Score::inCharacter), r.meanScore(Judge.Score::grounded), r.meanScore(Judge.Score::natural));
            sb.append(String.format(Locale.ROOT, "| %s | %s%s | %d | %d (%.0f%%) | %d | %d / %d / %d | %s | %s | %.4f |\n",
                    r.scenario(), r.condition(), r.truncated() ? " (truncated)" : "", r.metrics().totalCalls(),
                    r.metrics().parseErrors, 100 * r.metrics().parseErrorRate(), r.metrics().unfoundedClaims.size(),
                    req[0], req[1], req[2], spread, judge, r.estimatedUsd()));
        }
        List<Result> coordination = results.stream().filter(r -> r.metrics().needsMet != null).toList();
        if (!coordination.isEmpty()) {
            sb.append("\n### Coordination\n\n")
                    .append("Needs met = Σ min(gathered, required) / Σ required (works with or without the board). ")
                    .append("Double claims / double credits (from the recorded claim history) and fencing violations must be 0. ")
                    .append("Outdated = claims run on a view whose revision had moved. ")
                    .append("Wasted = mines whose block was gone or changed.\n\n")
                    .append("| Scenario | Condition | Tasks done | Ticks to done | Needs met | Gathered | Claims ok / rejected "
                            + "| Outdated view (fencing violations) | Double claims / credits | Reservation conflicts "
                            + "| Wasted | Mines cancelled | Calls per done task |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (Result r : coordination) {
                Metrics m = r.metrics();
                int rejected = m.claimsRejected.values().stream().mapToInt(Integer::intValue).sum();
                sb.append(String.format(Locale.ROOT,
                        "| %s | %s | %d/%d | %s | %.0f%% | %s | %d / %d%s | %d (%d) | %d / %d | %d | %d | %d | %s |\n",
                        r.scenario(), r.condition(), m.tasksDone, m.tasksTotal,
                        m.ticksToDone.isEmpty() ? "-" : m.ticksToDone.toString(), 100 * m.needsMet, m.gathered,
                        m.claimsGranted, rejected, m.claimsRejected.isEmpty() ? "" : " " + m.claimsRejected,
                        m.claimsOnOutdatedView, m.fencingViolations, m.doubleClaims, m.doubleCredits,
                        m.reservationConflicts, m.wastedWork, m.minesCancelled,
                        m.callsPerDoneTask == null ? "-" : String.format(Locale.ROOT, "%.1f", m.callsPerDoneTask)));
            }
        }
        for (Result r : results) {
            sb.append("\n## ").append(r.scenario()).append(" / ").append(r.condition()).append("\n\n");
            if (!r.metrics().requests.isEmpty()) {
                sb.append("**Requests** (settler, agreeableness, requester, tick → result)\n\n");
                r.metrics().requests.forEach(x -> sb.append("- ").append(x.settler())
                        .append(String.format(Locale.ROOT, " (%.2f) ", r.agreeableness().get(x.settler()))).append(x.requester())
                        .append(" @").append(x.tick()).append(" → ").append(x.result()).append('\n'));
                sb.append('\n');
            }
            if (!r.metrics().boardFinal.isEmpty()) {
                sb.append("**Board at the end:** ").append(String.join("; ", r.metrics().boardFinal)).append("\n\n");
            }
            if (!r.metrics().doubleClaimSamples.isEmpty()) {
                sb.append("**Double claims (claim history):** ").append(r.metrics().doubleClaimSamples).append("\n\n");
            }
            if (!r.factKnowledge().isEmpty()) {
                sb.append("**Who knows the fact:** ").append(r.factKnowledge()).append("\n\n");
            }
            if (!r.metrics().unfoundedClaims.isEmpty()) {
                sb.append("**Unfounded claims:**\n");
                r.metrics().unfoundedClaims.forEach(c -> sb.append("- ").append(c).append('\n'));
                sb.append('\n');
            }
            if (!r.metrics().parseErrorSamples.isEmpty()) {
                sb.append("**Parse error samples:** ").append(r.metrics().parseErrorSamples).append("\n\n");
            }
            if (!r.scores().isEmpty()) {
                sb.append("**Interview** (character / grounded / natural)\n\n");
                for (Judge.Score s : r.scores()) {
                    sb.append("- ").append(s.settler()).append(" — *").append(s.question()).append("* → \"").append(s.answer())
                            .append("\" (").append(s.inCharacter()).append('/').append(s.grounded()).append('/').append(s.natural()).append(')');
                    if (!s.hallucinations().isEmpty()) sb.append(" ⚠ ").append(s.hallucinations());
                    sb.append('\n');
                }
                sb.append('\n');
            } else if (!r.interview().isEmpty()) {
                sb.append("**Interview** (not judged)\n\n");
                r.interview().forEach((n, qa) -> qa.forEach(p -> sb.append("- ").append(n).append(" — *").append(p[0])
                        .append("* → \"").append(p[1]).append("\"\n")));
                sb.append('\n');
            }
            sb.append("<details><summary>Transcript (").append(r.log().size()).append(" lines)</summary>\n\n```\n");
            r.log().stream().limit(300).forEach(l -> sb.append(l.tick()).append(' ').append(l.who()).append(' ')
                    .append(l.kind()).append(": ").append(l.text().replace('\n', ' ')).append('\n'));
            sb.append("```\n</details>\n");
        }
        return sb.toString();
    }
}
