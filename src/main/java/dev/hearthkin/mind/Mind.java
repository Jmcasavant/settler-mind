package dev.hearthkin.mind;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hearthkin.mind.cognition.CognitionRequest;
import dev.hearthkin.mind.cognition.CognitionResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One settler's mind: identity, needs, memory, relationships, lessons, plans, and the
 * logic for when to think. Engine-agnostic and single-threaded: the host calls it on
 * the game thread only; model calls happen elsewhere and come back via {@link #onResult}.
 *
 * <p>Thinking is event-driven, never per tick: someone speaks to the settler, a plan
 * finishes or fails, it gets hurt, it's idle, a new day starts, or enough has happened
 * to reflect on.
 */
public final class Mind {
    /** Minimum game ticks between two thoughts (prevents bursts). */
    static final int MIN_THINK_GAP = 20;
    /** At most this many replies to the same speaker per window (stops reply loops). */
    static final int MAX_REPLIES_PER_PARTNER = 3;
    static final long REPLY_WINDOW = 20 * 120;
    static final int DIALOGUE_LINES = 10;

    private final String id;
    private final Persona persona;
    private @Nullable String ownerName;
    private final Needs needs = new Needs();
    private final MemoryStream memory = new MemoryStream();
    private final Relationships relationships = new Relationships();
    private final Lessons lessons = new Lessons();
    private String summary = "";
    private List<String> intentions = new ArrayList<>();
    private long planDay = -1;
    private long lastReflectTick;
    private final Deque<String> dialogue = new ArrayDeque<>();
    private final Deque<String> mySaid = new ArrayDeque<>();

    // transient
    private final List<MindEvent> pending = new ArrayList<>();
    private boolean thinking;
    private long lastThinkTick = -MIN_THINK_GAP;
    private final Map<String, Deque<Long>> repliesTo = new HashMap<>();
    private @Nullable Decision lastDecision;
    private @Nullable String lastError;
    private String lastMood = "calm";

    public Mind(String id, Persona persona, @Nullable String ownerName) {
        this.id = id;
        this.persona = persona;
        this.ownerName = ownerName;
        if (ownerName != null) relationships.seedOwner(ownerName);
    }

    // ------------------------------------------------------------ perception of events

    /** Record an event; queue it for thinking if it should wake the mind. */
    public void observe(MindEvent e, long tick) {
        if (e instanceof MindEvent.Heard h) {
            if (h.speaker().equalsIgnoreCase(persona.name())) return;
            String line = h.shareable() ? h.speaker() + ": " + h.text() : h.speaker() + ": (words not shared)";
            pushDialogue(line);
            relationships.interacted(h.speaker(), tick);
            if (h.shareable()) {
                // What someone said is a claim attributed to them, not a fact.
                memory.add(tick, Memory.Kind.DIALOGUE, h.speaker() + " said \"" + h.text() + "\"",
                        h.addressedToMe() ? 4 : 2, Set.of(h.speaker().toLowerCase()), h.speaker(),
                        h.speakerKind().equals("player") ? 0.8 : 0.6);
            }
            if (h.addressedToMe()) {
                needs.satisfySocial(0.08);
                if (!replyAllowed(h.speaker(), tick)) return;
            }
        } else if (e instanceof MindEvent.ActionFinished a) {
            memory.add(tick, Memory.Kind.EVENT, a.describe(), a.failed() ? 5 : 3, Set.of(a.kind()), "self", 1.0);
            if (a.failed()) lessons.record(a.kind(), a.code(), a.detail(), tick);
            else needs.satisfyPurpose(0.15);
        } else if (e instanceof MindEvent.Hurt h) {
            memory.add(tick, Memory.Kind.EVENT, h.describe(), 6, Set.of(h.by().toLowerCase()), "self", 1.0);
        } else if (e instanceof MindEvent.Noticed n) {
            if (!relationships.knows(n.name())) {
                memory.add(tick, Memory.Kind.EVENT, "First met " + n.kind() + " " + n.name(), 4,
                        Set.of(n.name().toLowerCase()), "self", 1.0);
                relationships.interacted(n.name(), tick);
            }
        }
        if (e.wakes()) pending.add(e);
    }

    private boolean replyAllowed(String partner, long tick) {
        Deque<Long> times = repliesTo.computeIfAbsent(partner.toLowerCase(), k -> new ArrayDeque<>());
        while (!times.isEmpty() && tick - times.peekFirst() > REPLY_WINDOW) times.pollFirst();
        if (times.size() >= MAX_REPLIES_PER_PARTNER) return false;
        times.addLast(tick); // each wake-up to answer counts, whether or not words come out
        return true;
    }

    // ------------------------------------------------------------ deciding when to think

    public boolean isThinking() { return thinking; }
    public boolean hasPending() { return !pending.isEmpty(); }

    /** True at the first chance each new day. */
    public boolean planDue(long tick) { return tick / 24000 != planDay; }

    /** Two to three times a day, if enough has happened (calibrated per Generative Agents). */
    public boolean reflectionDue(long tick) {
        long since = tick - lastReflectTick;
        return (memory.reflectionDue() && since >= 6000) || (since >= 12000 && memory.size() >= 10 && memory.reflectionDue());
    }

    /**
     * Build the next request, if the mind wants to think now. The host supplies the
     * current perception lazily (it's only built when needed).
     */
    public @Nullable CognitionRequest nextRequest(long tick, java.util.function.Supplier<Perception> perceive) {
        if (thinking || tick - lastThinkTick < MIN_THINK_GAP) return null;
        String route;
        List<MindEvent> triggers = List.of();
        if (!pending.isEmpty()) {
            route = "decide";
            triggers = List.copyOf(pending);
            pending.clear();
        } else if (planDue(tick)) {
            route = "plan";
        } else if (reflectionDue(tick)) {
            route = "reflect";
        } else {
            return null;
        }
        Perception seen = perceive.get();
        String user = switch (route) {
            case "plan" -> Prompts.plan(this, seen, tick);
            case "reflect" -> Prompts.reflect(this, tick);
            default -> Prompts.decide(this, seen, triggers, tick);
        };
        String schema = switch (route) {
            case "plan" -> Prompts.PLAN_SCHEMA;
            case "reflect" -> Prompts.REFLECT_SCHEMA;
            default -> Prompts.DECIDE_SCHEMA;
        };
        List<String> recent = memory.recent(10).stream().map(Memory::text).toList();
        thinking = true;
        lastThinkTick = tick;
        return new CognitionRequest(route, persona.name(), Prompts.system(persona, ownerName), user, schema,
                persona, seen, triggers, relationships, recent);
    }

    /** Give back a request that will not be sent (e.g. over budget); triggers are re-queued. */
    public void abandon(CognitionRequest req) {
        thinking = false;
        pending.addAll(0, req.triggers());
    }

    // ------------------------------------------------------------ results

    /**
     * Apply a model result. Must run on the game thread.
     *
     * @return the decision for the body to carry out, or null if there is nothing to do
     */
    public @Nullable Decision onResult(CognitionRequest req, CognitionResult res, long tick) {
        thinking = false;
        if (!res.ok()) {
            lastError = res.error();
            return null;
        }
        switch (req.route()) {
            case "plan" -> {
                planDay = tick / 24000;
                JsonObject o = json(res.text());
                if (o == null || !o.has("intentions")) return null;
                List<String> list = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("intentions")) {
                    if (list.size() < 5 && e.isJsonPrimitive()) list.add(DecisionParser.clean(e.getAsString(), 100));
                }
                intentions = list;
                memory.add(tick, Memory.Kind.DECISION, "Today I intend to: " + String.join("; ", list), 4, Set.of(), "self", 1.0);
                return null;
            }
            case "reflect" -> {
                lastReflectTick = tick;
                memory.markReflected();
                JsonObject o = json(res.text());
                if (o == null || !o.has("insights")) return null;
                int n = 0;
                for (JsonElement e : o.getAsJsonArray("insights")) {
                    if (n++ >= 3 || !e.isJsonObject()) continue;
                    JsonObject i = e.getAsJsonObject();
                    String text = DecisionParser.clean(i.has("text") ? i.get("text").getAsString() : "", 240);
                    int imp = i.has("importance") && i.get("importance").isJsonPrimitive() ? i.get("importance").getAsInt() : 5;
                    if (text != null && !text.isBlank()) {
                        memory.add(tick, Memory.Kind.REFLECTION, text, imp, tags(i), "self", 0.9);
                    }
                }
                return null;
            }
            default -> {
                DecisionParser.Result parsed = DecisionParser.parse(res.text(), req.perception());
                if (!parsed.clean()) lastError = String.join("; ", parsed.errors());
                Decision d = parsed.decision();
                apply(d, res.text(), tick);
                return d;
            }
        }
    }

    private void apply(Decision d, String raw, long tick) {
        lastDecision = d;
        lastMood = d.mood();
        JsonObject o = json(raw);
        if (o != null && o.has("situation_summary") && o.get("situation_summary").isJsonPrimitive()) {
            String s = DecisionParser.clean(o.get("situation_summary").getAsString(), 600);
            if (s != null && !s.isBlank()) summary = s;
        }
        for (Decision.Feeling f : d.feelings()) {
            if (f.who().equalsIgnoreCase(persona.name())) continue;
            relationships.feel(persona, f.who(), f.change(), f.why(), tick);
        }
        if (d.remember() != null) {
            Set<String> about = new HashSet<>();
            if (o != null && o.has("remember") && o.get("remember").isJsonObject()) about.addAll(tags(o.getAsJsonObject("remember")));
            memory.add(tick, Memory.Kind.DECISION, d.remember().text(), d.remember().importance(), about, "self", 1.0);
        }
        if (!d.steps().isEmpty() || d.goal() != null) {
            String plan = d.steps().stream().map(Decision.Step::describe).reduce((a, b) -> a + ", then " + b).orElse("nothing");
            memory.add(tick, Memory.Kind.DECISION, "I decided to " + plan
                    + (d.goal() == null ? "" : " (goal: " + d.goal() + ")")
                    + (d.thought().isBlank() ? "" : ", because " + d.thought()), 3, Set.of(), "self", 1.0);
        }
    }

    /**
     * Returns the line to say, or null if it should be suppressed (empty, or a repeat of
     * something said very recently: loop protection that needs no model call).
     */
    public @Nullable String acceptSpeech(Decision d, long tick) {
        String say = d.say();
        if (say == null || say.isBlank()) return null;
        String norm = say.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
        if (mySaid.stream().anyMatch(prev -> prev.equals(norm))) return null;
        mySaid.addLast(norm);
        if (mySaid.size() > 5) mySaid.removeFirst();
        pushDialogue(persona.name() + " (you): " + say);
        if (d.sayTo() != null) needs.satisfySocial(0.05);
        return say;
    }

    private void pushDialogue(String line) {
        dialogue.addLast(MemoryStream.clip(line, 240));
        while (dialogue.size() > DIALOGUE_LINES) dialogue.removeFirst();
    }

    private static Set<String> tags(JsonObject o) {
        Set<String> out = new HashSet<>();
        if (o.has("about") && o.get("about").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("about")) {
                if (e.isJsonPrimitive() && out.size() < 8) out.add(e.getAsString().toLowerCase().trim());
            }
        }
        return out;
    }

    private static @Nullable JsonObject json(String raw) {
        try {
            JsonElement e = JsonParser.parseString(raw.trim());
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------ accessors

    public String id() { return id; }
    public Persona persona() { return persona; }
    public @Nullable String ownerName() { return ownerName; }
    public void setOwnerName(String name) { ownerName = name; relationships.seedOwner(name); }
    public Needs needs() { return needs; }
    public MemoryStream memory() { return memory; }
    public Relationships relationships() { return relationships; }
    public Lessons lessons() { return lessons; }
    public String summary() { return summary; }
    public List<String> intentions() { return intentions; }
    public Deque<String> dialogue() { return dialogue; }
    public @Nullable Decision lastDecision() { return lastDecision; }
    public @Nullable String lastError() { return lastError; }
    public String mood() { return lastMood; }

    // ------------------------------------------------------------ persistence

    /** Plain data snapshot for serialisation (Gson). */
    public static final class Saved {
        public int version = 1;
        public String id;
        public Persona persona;
        public String ownerName;
        public double social, purpose;
        public List<Memory> memories;
        public long nextMemoryId;
        public int importanceSinceReflection;
        public Map<String, Relationships.Bond> bonds;
        public List<Lessons.Lesson> lessons;
        public String summary;
        public List<String> intentions;
        public long planDay, lastReflectTick;
        public List<String> dialogue;
    }

    public Saved save() {
        Saved s = new Saved();
        s.id = id;
        s.persona = persona;
        s.ownerName = ownerName;
        s.social = needs.social;
        s.purpose = needs.purpose;
        s.memories = memory.all();
        s.nextMemoryId = memory.nextId();
        s.importanceSinceReflection = memory.importanceSinceReflection();
        s.bonds = relationships.all();
        s.lessons = lessons.all();
        s.summary = summary;
        s.intentions = intentions;
        s.planDay = planDay;
        s.lastReflectTick = lastReflectTick;
        s.dialogue = new ArrayList<>(dialogue);
        return s;
    }

    public static Mind load(Saved s) {
        Mind m = new Mind(s.id, s.persona, null);
        m.ownerName = s.ownerName;
        m.needs.social = s.social;
        m.needs.purpose = s.purpose;
        if (s.memories != null) m.memory.restore(s.memories, s.nextMemoryId, s.importanceSinceReflection);
        if (s.bonds != null) m.relationships.all().putAll(s.bonds);
        if (s.lessons != null) {
            for (Lessons.Lesson l : s.lessons) {
                Lessons.Lesson copy = m.lessons.record(l.action, l.code, "", l.lastTick);
                copy.times = l.times;
                copy.text = l.text;
            }
        }
        m.summary = s.summary == null ? "" : s.summary;
        m.intentions = s.intentions == null ? new ArrayList<>() : new ArrayList<>(s.intentions);
        m.planDay = s.planDay;
        m.lastReflectTick = s.lastReflectTick;
        if (s.dialogue != null) m.dialogue.addAll(s.dialogue);
        return m;
    }
}
