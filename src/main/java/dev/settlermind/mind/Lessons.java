package dev.settlermind.mind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a settler has learned from its own verified outcomes, keyed by action kind and
 * result code (after Reflexion, Shinn et al. 2023). Lessons are recalled by key, not by
 * keyword search, so they reliably come back whenever that action is considered.
 */
public final class Lessons {
    public static final class Lesson {
        public String action;
        public String code;
        public String text;
        public int times;
        public long lastTick;
    }

    private final Map<String, Lesson> byKey = new LinkedHashMap<>();

    /** Record a failure (or a partial success) of {@code action} with {@code code}. */
    public Lesson record(String action, String code, String detail, long tick) {
        String key = action + ":" + code;
        Lesson l = byKey.computeIfAbsent(key, k -> new Lesson());
        l.action = action;
        l.code = code;
        l.times++;
        l.lastTick = tick;
        l.text = "\"" + action + "\" can end in " + code.replace('_', ' ')
                + (detail == null || detail.isBlank() ? "" : " (" + MemoryStream.clip(detail, 80) + ")")
                + (l.times > 1 ? "; happened " + l.times + " times" : "");
        return l;
    }

    /** Let the mind refine the wording of a lesson it learned. */
    public void annotate(String action, String code, String text) {
        Lesson l = byKey.get(action + ":" + code);
        if (l != null && text != null && !text.isBlank()) l.text = MemoryStream.clip(text, 160);
    }

    public List<Lesson> forAction(String action) {
        return byKey.values().stream().filter(l -> l.action.equals(action)).toList();
    }

    public List<Lesson> all() { return List.copyOf(byKey.values()); }

    public String render() {
        if (byKey.isEmpty()) return "(nothing yet)";
        StringBuilder sb = new StringBuilder();
        for (Lesson l : byKey.values()) sb.append("- ").append(l.text).append('\n');
        return sb.toString();
    }
}
