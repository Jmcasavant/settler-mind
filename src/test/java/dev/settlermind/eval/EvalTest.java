package dev.settlermind.eval;

import dev.settlermind.mind.Memory;
import dev.settlermind.mind.MindFeatures;
import dev.settlermind.mind.cognition.RouteConfig;
import dev.settlermind.mind.cognition.ScriptedCognition;
import dev.settlermind.mind.cognition.UsageLedger;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the evaluation harness with the free scripted mind on every build. This checks the
 * harness and the safety rails (no invalid plans, no false "done" claims); it does not
 * measure intelligence. Real evaluations use --provider claude (see docs/EVAL.md).
 */
class EvalTest {
    static List<EvalRunner.Result> runAll(Path out) {
        EvalRunner.Options o = new EvalRunner.Options();
        o.conditions = EvalRunner.ALL_CONDITIONS;
        o.out = out;
        return EvalRunner.run(o);
    }

    @Test
    void scriptedBaselineIsCleanAndReportIsWritten() throws Exception {
        Path out = Path.of("build", "eval", "scripted-baseline");
        List<EvalRunner.Result> results = runAll(out);
        assertEquals(Scenarios.all().size() * EvalRunner.ALL_CONDITIONS.size(), results.size());
        for (EvalRunner.Result r : results) {
            assertEquals(0, r.metrics().parseErrors, r.scenario() + "/" + r.condition() + " parse errors");
            assertTrue(r.metrics().unfoundedClaims.isEmpty(), r.scenario() + " unfounded claims " + r.metrics().unfoundedClaims);
            assertFalse(r.truncated());
        }
        EvalRunner.Options o = new EvalRunner.Options();
        o.out = out;
        EvalRunner.write(o, results);
        assertTrue(Files.readString(out.resolve("report.md")).contains("| requests | full |"));
        assertTrue(Files.size(out.resolve("report.json")) > 1000);
    }

    @Test
    void complianceTracksPersonalityAndRelationship() {
        EvalRunner.Result r = EvalRunner.run(opts("requests", "full")).get(0);
        Metrics m = r.metrics();
        assertEquals(8, m.requests.size(), "every request answered or counted: " + m.requests);
        assertEquals("complied", m.result("Sunny", "Alex", 0), "very agreeable settler helps the owner: " + m.requests);
        assertEquals("declined", m.result("Gruff", "Zed", 0), "very disagreeable settler refuses a stranger: " + m.requests);
        long helpsOwner = m.requests.stream().filter(x -> x.requester().equals("Alex") && x.result().equals("complied")).count();
        long helpsStranger = m.requests.stream().filter(x -> x.requester().equals("Zed") && x.result().equals("complied")).count();
        assertTrue(helpsOwner >= helpsStranger, "owner " + helpsOwner + " vs stranger " + helpsStranger);
    }

    @Test
    void groundingFailureBecomesALesson() {
        EvalRunner.Result r = EvalRunner.run(opts("grounding", "full")).get(0);
        assertTrue(r.metrics().outcomes.getOrDefault("PARTIAL", 0) >= 1, "stone by hand is partial: " + r.metrics().outcomes);
        assertEquals(2, r.metrics().requests.size(), "both requests counted: " + r.metrics().requests);
    }

    @Test
    void ablationsActuallyRemoveTheFeature() {
        Scenario sc = Scenarios.byName("diffusion");
        Simulation sim = new Simulation(sc, MindFeatures.FULL.without("reflection"), route -> new ScriptedCognition(),
                route -> new RouteConfig("scripted", "scripted", "low", 1000), new UsageLedger(Map.of()), 1000);
        sim.run();
        sim.agents.values().forEach(a -> assertTrue(a.mind.memory().ofKind(Memory.Kind.REFLECTION).isEmpty(), a.name));
        Simulation full = new Simulation(sc, MindFeatures.FULL, route -> new ScriptedCognition(),
                route -> new RouteConfig("scripted", "scripted", "low", 1000), new UsageLedger(Map.of()), 1000);
        full.run();
        assertTrue(full.agents.values().stream().anyMatch(a -> !a.mind.intentions().isEmpty()), "full mind plans its day");
        assertTrue(sim.agents.values().stream().anyMatch(a -> !a.mind.intentions().isEmpty()), "plan still on in no_reflection");
    }

    @Test
    void cooperationWithTheBoardSplitsWorkWithoutDoubleClaims() {
        EvalRunner.Result r = EvalRunner.run(opts("cooperation", "full")).get(0);
        Metrics m = r.metrics();
        String board = m.boardFinal + " claims " + m.claimsGranted + " rejected " + m.claimsRejected;
        assertEquals(0, m.doubleClaims, "a task never has two claimants: " + board);
        assertTrue(m.claimsGranted >= 1, "the scripted mind uses the board when told about it: " + board);
        assertTrue(m.tasksDone >= 1, "at least one task completed by verified gathering: " + board);
        assertNotNull(m.needsMet);
        assertTrue(m.needsMet > 0, "needs met " + m.needsMet + " gathered " + m.gathered);
        assertEquals(2, m.tasksTotal, board);
        m.ticksToDone.values().forEach(t -> assertTrue(t > 0 && t <= 6000, "ticks to done " + m.ticksToDone));
        assertEquals(0, m.parseErrors);
        assertTrue(m.unfoundedClaims.isEmpty(), m.unfoundedClaims.toString());
    }

    @Test
    void cooperationWithoutTheBoardRunsAndNeverClaims() {
        EvalRunner.Result r = EvalRunner.run(opts("cooperation", "no_board")).get(0);
        Metrics m = r.metrics();
        assertEquals(0, m.claimAttempts, "the board is hidden: no claims " + m.claimsRejected);
        assertEquals(0, m.claimsGranted);
        assertEquals(0, m.boardPosts);
        assertEquals(0, m.tasksDone, "nothing is credited without claims: " + m.boardFinal);
        assertNotNull(m.needsMet, "needs met is measured without the board too");
        assertEquals(0, m.parseErrors);
        assertTrue(m.unfoundedClaims.isEmpty(), m.unfoundedClaims.toString());
    }

    @Test
    void boardInvariantsHoldInTheSimulation() {
        // Progress never exceeds count, and every claim attempt is either granted or rejected with a code.
        Scenario sc = Scenarios.byName("cooperation");
        Simulation sim = new Simulation(sc, MindFeatures.FULL, route -> new ScriptedCognition(),
                route -> new RouteConfig("scripted", "scripted", "low", 1000), new UsageLedger(Map.of()), 10_000);
        sim.run();
        sim.board.tasks().forEach(t -> assertTrue(t.progress <= t.count, t.id + " progress " + t.progress));
        assertTrue(sim.metrics.claimsRejected.values().stream().mapToInt(Integer::intValue).sum()
                + sim.metrics.claimsGranted == sim.metrics.claimAttempts);
    }

    @Test
    void callBudgetIsAHardStop() {
        EvalRunner.Options o = opts("diffusion", "full");
        o.maxCalls = 5;
        EvalRunner.Result r = EvalRunner.run(o).get(0);
        assertTrue(r.truncated());
        assertTrue(r.metrics().totalCalls() <= 5);
    }

    static EvalRunner.Options opts(String scenario, String condition) {
        EvalRunner.Options o = new EvalRunner.Options();
        o.scenarios = List.of(scenario);
        o.conditions = List.of(condition);
        o.interview = false;
        return o;
    }
}
