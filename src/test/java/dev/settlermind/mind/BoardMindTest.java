package dev.settlermind.mind;

import dev.settlermind.mind.board.Board;
import dev.settlermind.mind.board.TaskView;
import dev.settlermind.mind.cognition.CognitionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The board as the mind sees it: validation, prompts, feature switch and the scripted mind. */
class BoardMindTest {
    static final MindFeatures NO_BOARD = MindFeatures.FULL.without("board");

    /** A board with T1 (3 oak_log, open), T2 (2 birch_log, claimed by Tess) and T3 (cobblestone, claimed by Bram). */
    static Board board() {
        Board b = new Board();
        b.post("Alex", "player", "oak_log", 3, null, 0);
        b.post("Alex", "player", "birch_log", 2, null, 0);
        b.post("Alex", "player", "cobblestone", 4, null, 0);
        assertTrue(b.claim("T2", "Tess", b.task("T2").revision, 1).ok());
        assertTrue(b.claim("T3", "Bram", b.task("T3").revision, 1).ok());
        return b;
    }

    static Perception seeing(List<TaskView> views, Perception.SeenBlock... blocks) {
        return new Perception(100, "morning", "clear", new int[]{0, 64, 0}, 20, 20, "nothing", Map.of(),
                "standing around", List.of(blocks), List.of(new Perception.SeenEntity("player", "Alex", 3, true)), views);
    }

    static Perception tessSees(Perception.SeenBlock... blocks) {
        return seeing(board().views("Tess", 2), blocks);
    }

    static DecisionParser.Result parse(String steps, Perception p, MindFeatures f) {
        return DecisionParser.parse("{\"steps\":[" + steps + "]}", p, f, "Tess");
    }

    static void accepted(DecisionParser.Result r) {
        assertTrue(r.clean(), "errors: " + r.errors());
        assertEquals(1, r.decision().steps().size());
    }

    static void rejected(DecisionParser.Result r) {
        assertFalse(r.clean());
        assertTrue(r.decision().steps().isEmpty());
    }

    // ---------------------------------------------------------------- validation

    @Test
    void claimTaskMustBeVisibleAndOpen() {
        Perception p = tessSees();
        var r = parse("{\"kind\":\"claim_task\",\"task\":\"T1\"}", p, MindFeatures.FULL);
        accepted(r);
        assertEquals("T1", r.decision().steps().get(0).task());
        accepted(parse("{\"kind\":\"claim_task\",\"task\":\"t1\"}", p, MindFeatures.FULL));
        rejected(parse("{\"kind\":\"claim_task\",\"task\":\"T2\"}", p, MindFeatures.FULL)); // already claimed (by me)
        rejected(parse("{\"kind\":\"claim_task\",\"task\":\"T3\"}", p, MindFeatures.FULL)); // not visible to Tess
        rejected(parse("{\"kind\":\"claim_task\",\"task\":\"T99\"}", p, MindFeatures.FULL));
        rejected(parse("{\"kind\":\"claim_task\",\"task\":null}", p, MindFeatures.FULL));
    }

    @Test
    void releaseTaskMustBeYours() {
        Perception p = tessSees();
        accepted(parse("{\"kind\":\"release_task\",\"task\":\"T2\"}", p, MindFeatures.FULL));
        rejected(parse("{\"kind\":\"release_task\",\"task\":\"T1\"}", p, MindFeatures.FULL)); // open, not mine
        rejected(parse("{\"kind\":\"release_task\",\"task\":\"T3\"}", p, MindFeatures.FULL));
        // Bram sees T3 as his own.
        Perception bram = seeing(board().views("Bram", 2));
        assertTrue(DecisionParser.parse("{\"steps\":[{\"kind\":\"release_task\",\"task\":\"T3\"}]}", bram,
                MindFeatures.FULL, "Bram").clean());
    }

    @Test
    void postTaskChecksItemAndCount() {
        Perception p = tessSees();
        var r = parse("{\"kind\":\"post_task\",\"item\":\"oak_planks\",\"count\":8}", p, MindFeatures.FULL);
        accepted(r);
        Decision.Step s = r.decision().steps().get(0);
        assertEquals("oak_planks", s.item());
        assertEquals(8, s.count());
        assertTrue(s.describe().contains("gather 8 oak_planks"));
        accepted(parse("{\"kind\":\"post_task\",\"item\":\"minecraft:stone\",\"count\":1}", p, MindFeatures.FULL));
        rejected(parse("{\"kind\":\"post_task\",\"item\":\"Oak Log!\",\"count\":3}", p, MindFeatures.FULL));
        rejected(parse("{\"kind\":\"post_task\",\"item\":\"" + "a".repeat(65) + "\",\"count\":3}", p, MindFeatures.FULL));
        rejected(parse("{\"kind\":\"post_task\",\"item\":null,\"count\":3}", p, MindFeatures.FULL));
        rejected(parse("{\"kind\":\"post_task\",\"item\":\"stone\",\"count\":0}", p, MindFeatures.FULL));
        rejected(parse("{\"kind\":\"post_task\",\"item\":\"stone\",\"count\":65}", p, MindFeatures.FULL));
        rejected(parse("{\"kind\":\"post_task\",\"item\":\"stone\"}", p, MindFeatures.FULL));
    }

    @Test
    void boardStepsRejectedWhenFeatureOff() {
        Perception p = tessSees();
        rejected(parse("{\"kind\":\"claim_task\",\"task\":\"T1\"}", p, NO_BOARD));
        rejected(parse("{\"kind\":\"release_task\",\"task\":\"T2\"}", p, NO_BOARD));
        rejected(parse("{\"kind\":\"post_task\",\"item\":\"stone\",\"count\":2}", p, NO_BOARD));
        accepted(parse("{\"kind\":\"wait\",\"seconds\":2}", p, NO_BOARD));
    }

    @Test
    void mineRespectsOtherSettlersReservations() {
        Perception p = tessSees(new Perception.SeenBlock("oak_log", 3, 64, 0, 3, "Bram"),
                new Perception.SeenBlock("oak_log", 4, 64, 0, 4, "Tess"),
                new Perception.SeenBlock("oak_log", 5, 64, 0, 5, null));
        rejected(parse("{\"kind\":\"mine\",\"x\":3,\"y\":64,\"z\":0}", p, MindFeatures.FULL));
        accepted(parse("{\"kind\":\"mine\",\"x\":4,\"y\":64,\"z\":0}", p, MindFeatures.FULL));
        accepted(parse("{\"kind\":\"mine\",\"x\":5,\"y\":64,\"z\":0}", p, MindFeatures.FULL));
        // Without knowing who "self" is, any reservation is someone else's (fail closed).
        assertFalse(DecisionParser.parse("{\"steps\":[{\"kind\":\"mine\",\"x\":4,\"y\":64,\"z\":0}]}", p).clean());
    }

    @Test
    void boardStepsStayInsideTheClosedCatalog() {
        for (String k : ActionCatalog.BOARD_KINDS) assertTrue(ActionCatalog.known(k));
        assertEquals(3, ActionCatalog.MAX_STEPS);
        assertTrue(Prompts.DECIDE_SCHEMA.contains("\"claim_task\""));
        assertTrue(Prompts.DECIDE_SCHEMA.contains("\"item\",\"count\",\"task\""));
    }

    // ---------------------------------------------------------------- prompts

    @Test
    void decidePromptShowsBoardOnlyWhenNonEmptyAndOn() {
        List<MindEvent> trig = List.of(new MindEvent.Idle("purpose"));
        Mind full = MindTest.mind(0.6);
        String withBoard = Prompts.decide(full, tessSees(), trig, 100);
        assertTrue(withBoard.contains("## Settlement task board"));
        assertTrue(withBoard.contains("T1 [open] gather 3 oak_log for Alex"));
        assertTrue(withBoard.contains("T2 [yours] gather 2 birch_log: 0/2"));
        assertFalse(Prompts.decide(full, seeing(List.of()), trig, 100).contains("Settlement task board"));

        Mind off = new Mind("id", MindTest.persona(0.6), "Alex", NO_BOARD);
        assertFalse(Prompts.decide(off, tessSees(), trig, 100).contains("Settlement task board"));
    }

    @Test
    void systemPromptMentionsBoardOnlyWhenOnAndStaysStable() {
        Persona p = MindTest.persona(0.6);
        String on = Prompts.system(p, "Alex", MindFeatures.FULL);
        String off = Prompts.system(p, "Alex", NO_BOARD);
        assertTrue(on.contains("claim_task") && on.contains("settlement task board"));
        assertFalse(off.contains("claim_task") || off.contains("task board"));
        assertEquals(on, Prompts.system(p, "Alex", MindFeatures.FULL));
        assertEquals(on, Prompts.system(p, "Alex"));

        // Stable across calls even as the board changes.
        Mind m = MindTest.mind(0.6);
        m.observe(new MindEvent.Idle("purpose"), 100);
        CognitionRequest a = m.nextRequest(100, () -> tessSees());
        m.abandon(a);
        CognitionRequest b = m.nextRequest(200, () -> seeing(List.of()));
        assertEquals(a.system(), b.system());
    }

    @Test
    void featureOffHidesTheBoardFromTheMind() {
        Mind off = new Mind("id", MindTest.persona(0.6), "Alex", NO_BOARD);
        off.observe(new MindEvent.Heard("Alex", "player", "Tess, anything on the board?", true, true), 100);
        CognitionRequest req = off.nextRequest(100, () -> tessSees(new Perception.SeenBlock("oak_log", 3, 64, 0, 3)));
        assertNotNull(req);
        assertTrue(req.perception().board().isEmpty());
    }

    // ---------------------------------------------------------------- scripted mind

    @Test
    void scriptedSettlerClaimsVisibleOpenTaskWhenAsked() {
        Mind m = MindTest.mind(0.6);
        // Not holding anything: Tess sees only the open T1 (the board as seen by a settler with no claims).
        Board b = new Board();
        b.post("Alex", "player", "birch_log", 2, null, 0); // T1: nothing visible to gather
        b.post("Alex", "player", "oak_log", 3, null, 0);   // T2: oak logs in sight
        Perception p = seeing(b.views("Tess", 2),
                new Perception.SeenBlock("oak_log", 3, 64, 0, 3, "Bram"),
                new Perception.SeenBlock("oak_log", 5, 64, 0, 5));
        m.observe(new MindEvent.Heard("Alex", "player", "Tess, anything on the board?", true, true), 100);
        Decision d = MindTest.think(m, p, 100);
        assertNull(m.lastError(), m.lastError());
        assertEquals(2, d.steps().size(), "steps: " + d.steps());
        assertEquals("claim_task", d.steps().get(0).kind());
        assertEquals("T2", d.steps().get(0).task());
        assertEquals("mine", d.steps().get(1).kind());
        assertEquals(5, d.steps().get(1).x(), "skips the block Bram is working on");
        assertNotNull(d.say());
    }

    @Test
    void scriptedSettlerKeepsMiningWhileHoldingClaim() {
        Mind m = MindTest.mind(0.6);
        Board b = new Board();
        b.post("Alex", "player", "oak_log", 3, null, 0);
        assertTrue(b.claim("T1", "Tess", 1, 1).ok());
        Perception p = seeing(b.views("Tess", 2), new Perception.SeenBlock("oak_log", 3, 64, 0, 3));
        m.observe(new MindEvent.ActionFinished("mine the block at 2 64 0", "mine", "SUCCEEDED", "ok",
                "+1 minecraft:oak_log", true), 100);
        Decision d = MindTest.think(m, p, 100);
        assertEquals(1, d.steps().size(), "steps: " + d.steps());
        assertEquals("mine", d.steps().get(0).kind());
        assertEquals(3, d.steps().get(0).x());
    }

    @Test
    void boardEventIsRemembered() {
        Mind m = MindTest.mind(0.6);
        m.observe(new MindEvent.Board("Your task T2 (gather 3 cobblestone) is done", false), 100);
        assertFalse(m.hasPending());
        m.observe(new MindEvent.Board("Your claim on T3 lapsed after no progress", true), 110);
        assertTrue(m.hasPending());
        List<String> recent = m.memory().recent(5).stream().map(Memory::text).toList();
        assertTrue(recent.contains("Your task T2 (gather 3 cobblestone) is done"), recent.toString());
        assertTrue(recent.contains("Your claim on T3 lapsed after no progress"), recent.toString());
    }
}
