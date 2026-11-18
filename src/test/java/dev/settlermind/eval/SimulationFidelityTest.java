package dev.settlermind.eval;

import dev.settlermind.mind.Decision;
import dev.settlermind.mind.MindEvent;
import dev.settlermind.mind.MindFeatures;
import dev.settlermind.mind.Perception;
import dev.settlermind.mind.board.Board;
import dev.settlermind.mind.board.BoardTask;
import dev.settlermind.mind.cognition.RouteConfig;
import dev.settlermind.mind.cognition.ScriptedCognition;
import dev.settlermind.mind.cognition.UsageLedger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The simulation behaves like the game host where coordination depends on it (review finding 6):
 * mining takes time and holds its reservation, decisions land late and are fenced, double claims are
 * derived from the claim history, and settler posts are item-checked.
 */
class SimulationFidelityTest {
    static Simulation sim(String scenario, MindFeatures features) {
        return new Simulation(Scenarios.byName(scenario), features, route -> new ScriptedCognition(),
                route -> new RouteConfig("scripted", "scripted", "low", 1000), new UsageLedger(Map.of()), 10_000);
    }

    // ------------------------------------------------------------ 1. multi-step mining

    @Test
    void miningTakesSeveralStepsAndOthersSeeTheReservation() {
        Simulation sim = sim("cooperation", MindFeatures.FULL);
        AtomicInteger seenByOthers = new AtomicInteger();
        sim.beforeStep = tick -> {
            for (Simulation.Agent miner : sim.agents.values()) {
                if (miner.job == null) continue;
                assertEquals(miner.name, sim.board.holderId(miner.job.key, tick), "a mine in progress holds its block");
                for (Simulation.Agent other : sim.agents.values()) {
                    if (other == miner) continue;
                    for (Perception.SeenBlock b : sim.perceive(other).blocks()) {
                        if (Scenario.key(b.x(), b.y(), b.z()).equals(miner.job.key) && miner.name.equals(b.reservedBy())) {
                            seenByOthers.incrementAndGet();
                        }
                    }
                }
            }
        };
        sim.run();
        assertTrue(seenByOthers.get() > 0, "other settlers perceive reservedBy while a mine is in progress");
        assertEquals(0, sim.metrics.doubleCredits);

        // Timing: the first successful mine finishes (breakSteps + 2) steps after its plan was applied:
        // reserve, break for breakSteps, collect.
        long planTick = -1;
        String miner = null;
        for (Simulation.LogLine l : sim.log) {
            if (l.kind().equals("plan") && l.text().contains("mine the block") && !l.text().contains("claim task")) {
                planTick = l.tick();
                miner = l.who();
                break;
            }
        }
        assertTrue(planTick >= 0, "someone plans a plain mine");
        String who = miner;
        long start = planTick;
        Simulation.LogLine done = sim.log.stream()
                .filter(l -> l.who().equals(who) && l.kind().equals("act") && l.tick() > start && l.text().startsWith("mine"))
                .findFirst().orElseThrow();
        if (done.text().contains("SUCCEEDED")) {
            assertEquals((sim.breakSteps + 2) * Simulation.STEP, done.tick() - start, done.toString());
        }
    }

    @Test
    void withoutTheBoardSettlersStillRunIntoEachOthersReservations() {
        // Under no_board the mind does not see reservedBy, but the host still enforces it: racing for the
        // same log now shows up as reservation conflicts, not only as wasted trips.
        Simulation sim = sim("cooperation", MindFeatures.FULL.without("board"));
        sim.run();
        assertTrue(sim.metrics.reservationConflicts > 0, "conflicts " + sim.metrics.reservationConflicts
                + " wasted " + sim.metrics.wastedWork);
        assertEquals(0, sim.metrics.doubleCredits);
    }

    @Test
    void aNewPlanCancelsTheMineInProgressAndFreesTheBlock() {
        Simulation sim = sim("cooperation", MindFeatures.FULL);
        sim.breakSteps = 10; // a slow block, so the owner's word lands mid-mine
        String[] interrupted = new String[2]; // miner, block
        sim.beforeStep = tick -> {
            if (interrupted[0] != null) {
                Simulation.Agent m = sim.agents.get(interrupted[0]);
                if (m.job == null && sim.board.holderId(interrupted[1], tick) != null) {
                    assertNotEquals(m.name, sim.board.holderId(interrupted[1], tick), "a cancelled mine frees its block");
                }
                return;
            }
            for (Simulation.Agent a : sim.agents.values()) {
                if (a.job == null || a.job.broken || a.job.steps < 2) continue;
                a.mind.observe(new MindEvent.Heard("Alex", "player", a.name + ", stay here.", true, true), tick);
                interrupted[0] = a.name;
                interrupted[1] = a.job.key;
                return;
            }
        };
        sim.run();
        assertNotNull(interrupted[0], "someone was mid-mine");
        assertTrue(sim.metrics.minesCancelled >= 1, "the new plan cancels the mine: " + sim.log.stream()
                .filter(l -> l.who().equals(interrupted[0])).map(Simulation.LogLine::toString).toList());
        assertTrue(sim.log.stream().anyMatch(l -> l.who().equals(interrupted[0]) && l.text().endsWith("-> CANCELLED")));
    }

    // ------------------------------------------------------------ 2. decision latency and fencing

    @Test
    void latencyMakesClaimsRunOnOutdatedViewsAndFencingRejectsThem() {
        Simulation sim = sim("cooperation", MindFeatures.FULL);
        assertTrue(sim.decisionLatencySteps > 0, "latency is on by default");
        sim.run();
        Metrics m = sim.metrics;
        assertTrue(m.claimsOnOutdatedView >= 1, "some claim ran on an out-of-date view: " + m.claimsRejected);
        assertEquals(0, m.fencingViolations, "no claim on an outdated view was granted");
        assertEquals(0, m.doubleClaims, m.doubleClaimSamples.toString());
    }

    @Test
    void aBoardChangeWhileTheModelThinksMakesTheClaimStale() {
        Simulation sim = sim("cooperation", MindFeatures.FULL);
        sim.decisionLatencySteps = 3;
        AtomicBoolean fired = new AtomicBoolean();
        // While a decision made from a view with T1 open is still in flight, someone else claims T1 and lets it
        // go again: T1 is OPEN but its revision moved, which is exactly what fencing must catch.
        sim.beforeStep = tick -> {
            if (fired.get()) return;
            for (Simulation.Agent a : sim.agents.values()) {
                Simulation.Thought t = a.inFlight;
                if (t == null || !t.req().route().equals("decide")) continue;
                boolean sawT1Open = t.req().perception().board().stream()
                        .anyMatch(v -> v.id().equals("T1") && v.status() == BoardTask.Status.OPEN);
                BoardTask t1 = sim.board.task("T1");
                if (!sawT1Open || t1 == null || t1.status != BoardTask.Status.OPEN) continue;
                assertTrue(sim.board.claim("T1", "Zed", "Zed", t1.revision, tick).ok());
                assertTrue(sim.board.release("T1", "Zed", tick).ok());
                fired.set(true);
                return;
            }
        };
        sim.run();
        Metrics m = sim.metrics;
        assertTrue(fired.get(), "a decision was in flight with T1 open");
        assertTrue(m.claimsRejected.getOrDefault(Board.STALE, 0) >= 1, "stale claim fenced: " + m.claimsRejected);
        assertEquals(0, m.fencingViolations);
        assertEquals(0, m.doubleClaims, m.doubleClaimSamples.toString());
    }

    @Test
    void withoutLatencyTheSameRunAppliesDecisionsAtOnce() {
        Simulation sim = sim("cooperation", MindFeatures.FULL);
        sim.decisionLatencySteps = 0;
        AtomicInteger inFlight = new AtomicInteger();
        sim.beforeStep = tick -> sim.agents.values().forEach(a -> { if (a.inFlight != null) inFlight.incrementAndGet(); });
        sim.run();
        assertEquals(0, inFlight.get(), "latency 0: nothing is ever in flight between steps");
        assertEquals(0, sim.metrics.fencingViolations);
    }

    // ------------------------------------------------------------ 3. double claims from the claim history

    @Test
    void claimHistoryCountsOverlappingClaimants() {
        ClaimHistory h = new ClaimHistory();
        h.claimed(10, "T1", "Fern");
        h.claimed(20, "T1", "Fern");   // re-claim by the same settler: not a double
        h.claimed(30, "T1", "Hugo");   // Fern still holds T1: double
        h.released(40, "T1", "Fern");
        h.released(41, "T1", "Hugo");
        h.claimed(50, "T1", "Iris");   // free again: fine
        h.lapsed(60, "T1", "Iris");
        h.claimed(70, "T2", "Fern");
        h.done(80, "T2", "Fern");
        h.claimed(90, "T2", "Hugo");   // after done: fine (the board would refuse, but the history doesn't care)
        h.claimed(95, "T3", "Fern");
        h.cancelled(96, "T3");
        h.claimed(97, "T3", "Hugo");   // after cancel: fine
        assertEquals(1, h.doubleClaims(), h.doubleClaimSamples(5).toString());
        assertTrue(h.doubleClaimSamples(5).get(0).startsWith("T1 @30: Hugo"), h.doubleClaimSamples(5).toString());

        h.gained(10, "2,64,3", "Fern", "oak_log");
        h.gained(20, "5,64,4", "Hugo", "oak_log");
        assertEquals(0, h.doubleCredits());
        h.gained(30, "2,64,3", "Iris", "oak_log"); // the same block credited twice
        assertEquals(1, h.doubleCredits());
    }

    @Test
    void anInjectedDoubleClaimIsDetectedEvenThoughTheBoardCannotShowIt() {
        Simulation sim = sim("cooperation", MindFeatures.FULL);
        AtomicBoolean injected = new AtomicBoolean();
        sim.beforeStep = tick -> {
            BoardTask t1 = sim.board.task("T1");
            if (injected.get() || t1 == null || t1.status != BoardTask.Status.CLAIMED) return;
            // A buggy host granting a second claimant: the board still has a single claimant field.
            sim.metrics.claimHistory.claimed(tick, "T1", "Ghost");
            injected.set(true);
        };
        sim.run();
        assertTrue(injected.get());
        assertEquals(1, sim.metrics.doubleClaims, sim.metrics.doubleClaimSamples.toString());
        assertTrue(sim.metrics.doubleClaimSamples.get(0).contains("Ghost"));
    }

    // ------------------------------------------------------------ 4. post_task item check

    @Test
    void settlerPostsAreItemCheckedLikeTheHost() {
        Simulation sim = sim("cooperation", MindFeatures.FULL);
        assertTrue(sim.itemExists("oak_log"));
        assertTrue(sim.itemExists("minecraft:birch_log"));
        assertTrue(sim.itemExists("cobblestone"));
        assertFalse(sim.itemExists("unobtainium"), "unknown item");
        assertFalse(sim.itemExists("other:oak_log"), "unknown namespace");
        assertFalse(sim.itemExists("Oak_Log"), "ids are not case-folded");
        assertFalse(sim.itemExists("air"));
        assertFalse(sim.itemExists(""));
        assertFalse(sim.itemExists("oak log"));

        Simulation.Agent fern = sim.agents.get("Fern");
        int before = sim.board.tasks().size();
        assertEquals(Board.INVALID_ITEM, sim.boardStep(fern, post("unobtainium", 2)));
        assertEquals(Board.INVALID_ITEM, sim.boardStep(fern, post(null, 2)));
        assertEquals(before, sim.board.tasks().size(), "rejected posts never reach the board");
        assertEquals(Board.OK, sim.boardStep(fern, post("oak_log", 2)));
        assertEquals(before + 1, sim.board.tasks().size());
        assertEquals(2, sim.metrics.postsRejected.get(Board.INVALID_ITEM));
        assertEquals(1, sim.metrics.boardPosts);
    }

    private static Decision.Step post(String item, int count) {
        return new Decision.Step("post_task", null, null, null, null, null, item, count, null);
    }

    // ------------------------------------------------------------ determinism

    @Test
    void scriptedRunsAreDeterministic() {
        Simulation a = sim("cooperation", MindFeatures.FULL);
        Simulation b = sim("cooperation", MindFeatures.FULL);
        a.run();
        b.run();
        List<String> la = a.log.stream().map(l -> l.tick() + " " + l.who() + " " + l.kind() + " " + l.text()).toList();
        List<String> lb = b.log.stream().map(l -> l.tick() + " " + l.who() + " " + l.kind() + " " + l.text()).toList();
        assertEquals(la, lb);
        assertEquals(a.metrics.ticksToDone, b.metrics.ticksToDone);
        assertEquals(a.metrics.claimHistory.events, b.metrics.claimHistory.events);
    }
}
