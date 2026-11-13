package dev.hearthkin.mind;

import java.util.Set;

/**
 * One thing a settler remembers.
 *
 * @param tick       game tick when it was formed
 * @param importance 1 (mundane) to 10 (life-changing)
 * @param about      lower-case keywords: names, items, places; used for retrieval
 * @param source     who it came from: "self" for first-hand, otherwise the speaker's name
 * @param confidence 1.0 for first-hand verified events, lower for hearsay
 */
public record Memory(long id, long tick, Kind kind, String text, int importance,
                     Set<String> about, String source, double confidence) {

    public enum Kind {
        /** Something seen or that happened, verified by the game. */
        EVENT,
        /** Something heard someone say. */
        DIALOGUE,
        /** A choice the settler made and why. */
        DECISION,
        /** Something heard second-hand about someone or something else. */
        GOSSIP,
        /** A higher-level insight distilled from many memories. */
        REFLECTION,
        /** Something learned from success or failure that changes future plans. */
        LESSON
    }

    public String render(long now) {
        String age = ago(now - tick);
        String hearsay = source.equals("self") ? "" : " (heard from " + source + ")";
        return "[" + age + ", " + kind.name().toLowerCase() + ", importance " + importance + "] " + text + hearsay;
    }

    static String ago(long ticks) {
        long minutes = ticks / 1200;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long days = ticks / 24000;
        if (days < 1) return (minutes / 60) + " h ago";
        return days + " day" + (days == 1 ? "" : "s") + " ago";
    }
}
