package dev.hearthkin.mind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A settler's long-term memory. Memories are ranked for recall by a mix of how recent,
 * how important and how relevant they are to the current situation (in the spirit of
 * "Generative Agents", Park et al. 2023, without embeddings so it runs offline).
 *
 * <p>Forgetting is part of the design: when full, the least valuable old memories are
 * dropped, but reflections and lessons are protected.
 */
public final class MemoryStream {
    public static final int CAPACITY = 400;
    /** Recency half-life: one Minecraft day. */
    private static final double HALF_LIFE_TICKS = 24000;
    private static final Pattern WORD = Pattern.compile("[a-z0-9_]{3,}");
    private static final Set<String> STOP = Set.of(
            "the", "and", "you", "that", "this", "with", "for", "are", "was", "have", "your", "not",
            "but", "can", "what", "all", "get", "got", "just", "from", "they", "there", "will", "please");

    private final List<Memory> memories = new ArrayList<>();
    private long nextId = 1;
    private int importanceSinceReflection;

    public Memory add(long tick, Memory.Kind kind, String text, int importance, Set<String> about,
                      String source, double confidence) {
        Set<String> keys = new HashSet<>(about);
        keys.addAll(keywords(text));
        Memory m = new Memory(nextId++, tick, kind, clip(text, 280), Math.max(1, Math.min(10, importance)),
                Set.copyOf(keys), source, confidence);
        memories.add(m);
        if (kind != Memory.Kind.REFLECTION) importanceSinceReflection += m.importance();
        if (memories.size() > CAPACITY) forget(tick);
        return m;
    }

    /** Top {@code k} memories for the situation described by {@code query}. */
    public List<Memory> recall(String query, Set<String> focus, long now, int k) {
        Set<String> q = new HashSet<>(keywords(query));
        q.addAll(focus);
        return memories.stream()
                .sorted(Comparator.comparingDouble((Memory m) -> -score(m, q, now)))
                .limit(k)
                .sorted(Comparator.comparingLong(Memory::tick))
                .toList();
    }

    /** The most recent memories, oldest first. */
    public List<Memory> recent(int k) {
        return memories.subList(Math.max(0, memories.size() - k), memories.size());
    }

    public List<Memory> ofKind(Memory.Kind kind) {
        return memories.stream().filter(m -> m.kind() == kind).toList();
    }

    double score(Memory m, Set<String> query, long now) {
        double recency = Math.pow(0.5, Math.max(0, now - m.tick()) / HALF_LIFE_TICKS);
        double importance = m.importance() / 10.0;
        double relevance = 0;
        if (!query.isEmpty()) {
            long hits = m.about().stream().filter(query::contains).count();
            relevance = Math.min(1.0, hits / 2.0);
        }
        // Relevance dominates when there is a clear match; reflections/lessons get a nudge.
        double kindBonus = (m.kind() == Memory.Kind.REFLECTION || m.kind() == Memory.Kind.LESSON) ? 0.15 : 0;
        return recency + importance + 1.5 * relevance + kindBonus;
    }

    /** True when enough has happened since the last reflection to be worth one. */
    public boolean reflectionDue() {
        return importanceSinceReflection >= 40;
    }

    public void markReflected() {
        importanceSinceReflection = 0;
    }

    public int size() { return memories.size(); }
    public List<Memory> all() { return List.copyOf(memories); }

    private void forget(long now) {
        Memory weakest = memories.stream()
                .filter(m -> m.kind() != Memory.Kind.REFLECTION && m.kind() != Memory.Kind.LESSON)
                .min(Comparator.comparingDouble(m -> score(m, Set.of(), now)))
                .orElse(memories.get(0));
        memories.remove(weakest);
    }

    public static Set<String> keywords(String text) {
        Set<String> out = new HashSet<>();
        var m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (!STOP.contains(w)) out.add(w);
        }
        return out;
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // --- persistence helpers (used by MindState) ---
    void restore(List<Memory> saved, long nextId, int importanceSinceReflection) {
        memories.clear();
        memories.addAll(saved);
        this.nextId = nextId;
        this.importanceSinceReflection = importanceSinceReflection;
    }

    long nextId() { return nextId; }
    int importanceSinceReflection() { return importanceSinceReflection; }
}
