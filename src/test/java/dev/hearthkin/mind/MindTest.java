package dev.hearthkin.mind;

import com.google.gson.Gson;
import dev.hearthkin.mind.cognition.CognitionRequest;
import dev.hearthkin.mind.cognition.CognitionResult;
import dev.hearthkin.mind.cognition.RouteConfig;
import dev.hearthkin.mind.cognition.ScriptedCognition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MindTest {
    static final RouteConfig ROUTE = new RouteConfig("scripted", "scripted", "low", 1000);

    static Perception seeing(Perception.SeenBlock... blocks) {
        return new Perception(100, "morning", "clear", new int[]{0, 64, 0}, 20, 20, "nothing", Map.of(),
                "standing around", List.of(blocks), List.of(new Perception.SeenEntity("player", "Alex", 3, true)));
    }

    static Persona persona(double agreeableness) {
        Persona p = Persona.generate("Tess", 42);
        p.traits().put(Persona.AGREEABLENESS, agreeableness);
        return p;
    }

    static Mind mind(double agreeableness) {
        Mind m = new Mind("id", persona(agreeableness), "Alex");
        // Skip the start-of-day plan so tests exercise "decide" directly.
        CognitionRequest plan = m.nextRequest(0, () -> seeing());
        m.onResult(plan, new CognitionResult("{\"intentions\":[\"rest\"]}", "t", "t", 0, 0, 0, 0, 0, null), 0);
        return m;
    }

    static Decision think(Mind m, Perception p, long tick) {
        CognitionRequest req = m.nextRequest(tick, () -> p);
        assertNotNull(req, "mind should want to think");
        CognitionResult res = new ScriptedCognition().complete(req, ROUTE).join();
        return m.onResult(req, res, tick);
    }

    // ---------------------------------------------------------------- memory

    @Test
    void relevantMemoriesBeatRecentTrivia() {
        MemoryStream ms = new MemoryStream();
        ms.add(0, Memory.Kind.EVENT, "Bob gave me a diamond pickaxe", 7, Set.of("bob"), "self", 1);
        for (int i = 1; i <= 20; i++) ms.add(i * 100, Memory.Kind.EVENT, "Watched a cloud drift by " + i, 1, Set.of(), "self", 1);
        List<Memory> got = ms.recall("Bob is here", Set.of("bob"), 3000, 3);
        assertTrue(got.stream().anyMatch(m -> m.text().contains("diamond")), "relevant memory recalled: " + got);
    }

    @Test
    void forgettingSparesReflectionsAndLessons() {
        MemoryStream ms = new MemoryStream();
        ms.add(0, Memory.Kind.REFLECTION, "I trust Alex", 2, Set.of(), "self", 1);
        for (int i = 0; i < MemoryStream.CAPACITY + 50; i++) ms.add(i, Memory.Kind.EVENT, "noise " + i, 1, Set.of(), "self", 1);
        assertEquals(MemoryStream.CAPACITY, ms.size());
        assertEquals(1, ms.ofKind(Memory.Kind.REFLECTION).size());
    }

    // ---------------------------------------------------------------- validation (trust boundary)

    @Test
    void rejectsUnknownActionsAndUnseenBlocks() {
        Perception p = seeing(new Perception.SeenBlock("oak_log", 3, 64, 0, 3));
        var r = DecisionParser.parse("{\"steps\":[{\"kind\":\"op_me\"}]}", p);
        assertTrue(r.decision().steps().isEmpty());
        assertFalse(r.clean());
        r = DecisionParser.parse("{\"steps\":[{\"kind\":\"mine\",\"x\":99,\"y\":64,\"z\":0}]}", p);
        assertTrue(r.decision().steps().isEmpty(), "can't mine what it can't see");
        r = DecisionParser.parse("{\"steps\":[{\"kind\":\"mine\",\"x\":3,\"y\":64,\"z\":0}]}", p);
        assertEquals(1, r.decision().steps().size());
    }

    @Test
    void sanitisesSpeechAndSurvivesGarbage() {
        Perception p = seeing();
        var r = DecisionParser.parse("{\"say\":\"§cHi! see https://evil.example/x now\"}", p);
        assertEquals("Hi! see [link removed] now", r.decision().say());
        r = DecisionParser.parse("I am not JSON", p);
        assertFalse(r.clean());
        assertTrue(r.decision().steps().isEmpty());
    }

    @Test
    void capsStepsPerDecision() {
        String many = "{\"steps\":[" + "{\"kind\":\"wait\",\"seconds\":1},".repeat(6) + "{\"kind\":\"stay\"}]}";
        assertEquals(ActionCatalog.MAX_STEPS, DecisionParser.parse(many, seeing()).decision().steps().size());
    }

    // ---------------------------------------------------------------- behaviour

    @Test
    void agreesToReasonableRequestFromOwner() {
        Mind m = mind(0.6);
        Perception p = seeing(new Perception.SeenBlock("oak_log", 3, 64, 0, 3));
        m.observe(new MindEvent.Heard("Alex", "player", "Tess, chop that log please", true, true), 100);
        Decision d = think(m, p, 100);
        assertEquals(1, d.steps().size());
        assertEquals("mine", d.steps().get(0).kind());
        assertTrue(m.relationships().get("Alex").affinity > 0.35, "please was noticed");
    }

    @Test
    void disagreeableSettlerRefusesStranger() {
        Mind m = mind(0.0);
        Perception p = seeing(new Perception.SeenBlock("oak_log", 3, 64, 0, 3));
        m.observe(new MindEvent.Heard("Zed", "player", "Tess, chop that log", true, true), 100);
        Decision d = think(m, p, 100);
        assertTrue(d.steps().isEmpty());
        assertEquals("Do it yourself.", d.say());
    }

    @Test
    void replyLoopsAreCappedWithoutCallingAModel() {
        Mind m = mind(0.6);
        long t = 100;
        int woke = 0;
        for (int i = 0; i < 10; i++) {
            m.observe(new MindEvent.Heard("Bram", "settler", "Tess, lovely weather " + i, true, true), t);
            CognitionRequest req = m.nextRequest(t, MindTest::seeing);
            if (req != null) {
                woke++;
                Decision d = m.onResult(req, new ScriptedCognition().complete(req, ROUTE).join(), t);
                if (d != null) m.acceptSpeech(d, t);
            }
            t += Mind.MIN_THINK_GAP;
        }
        assertTrue(woke <= Mind.MAX_REPLIES_PER_PARTNER, "woke " + woke + " times");
    }

    @Test
    void repeatedLinesAreNotSpokenTwice() {
        Mind m = mind(0.6);
        Decision d = new Decision("", "Hello there.", null, "calm", null, List.of(), List.of(), null);
        assertNotNull(m.acceptSpeech(d, 1));
        assertNull(m.acceptSpeech(d, 2));
    }

    @Test
    void failuresBecomeLessonsThatAreAlwaysRecalled() {
        Mind m = mind(0.6);
        m.observe(new MindEvent.ActionFinished("mine the block at 3 64 0", "mine", "PARTIAL", "broken_not_collected", "stone by hand", true), 50);
        assertEquals(1, m.lessons().forAction("mine").size());
        CognitionRequest req = m.nextRequest(100, MindTest::seeing);
        assertTrue(req.user().contains("broken not collected"), "lesson is in the prompt");
    }

    @Test
    void oneComplimentIsNotAFriendship() {
        Relationships r = new Relationships();
        Persona p = persona(1.0);
        r.feel(p, "Zed", 2, "said something nice", 0);
        assertTrue(r.get("Zed").affinity < 0.25, "affinity " + r.get("Zed").affinity);
    }

    @Test
    void systemPromptIsStableForCaching() {
        Mind m = mind(0.6);
        m.observe(new MindEvent.Heard("Alex", "player", "Tess, hi", true, true), 100);
        CognitionRequest first = m.nextRequest(100, MindTest::seeing);
        m.onResult(first, CognitionResult.failed("t", "t", 0, "timeout"), 100);
        m.observe(new MindEvent.Heard("Alex", "player", "Tess, hello again", true, true), 200);
        CognitionRequest second = m.nextRequest(200, MindTest::seeing);
        assertEquals(first.system(), second.system());
    }

    @Test
    void survivesSaveAndLoad() {
        Mind m = mind(0.6);
        m.observe(new MindEvent.ActionFinished("mine x", "mine", "FAILED", "protected", "", true), 10);
        m.relationships().feel(m.persona(), "Alex", 2, "fed me", 10);
        Gson gson = new Gson();
        Mind back = Mind.load(gson.fromJson(gson.toJson(m.save()), Mind.Saved.class));
        assertEquals(m.memory().size(), back.memory().size());
        assertEquals(m.relationships().get("Alex").affinity, back.relationships().get("Alex").affinity, 1e-9);
        assertEquals(1, back.lessons().forAction("mine").size());
        assertEquals(m.persona(), back.persona());
    }
}
