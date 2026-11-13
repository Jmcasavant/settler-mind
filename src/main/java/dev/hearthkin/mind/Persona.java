package dev.hearthkin.mind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Who a settler is. Stable for the settler's life, seeded deterministically from its
 * identity so the same settler is always the same person. Personality colours how it
 * talks, what it chooses to do when free, and how quickly it warms to or cools on
 * people. It can never relax safety rules.
 *
 * <p>Traits are Big-Five style values in [0, 1].
 */
public record Persona(
        String name,
        Map<String, Double> traits,
        List<String> values,
        String speechStyle,
        String backstory,
        String aspiration,
        String quirk) {

    public static final String OPENNESS = "openness";
    public static final String CONSCIENTIOUSNESS = "conscientiousness";
    public static final String EXTRAVERSION = "extraversion";
    public static final String AGREEABLENESS = "agreeableness";
    public static final String NEUROTICISM = "neuroticism";

    private static final String[] VALUES = {
            "hard work", "family", "freedom", "honesty", "comfort", "curiosity", "loyalty",
            "beauty", "tradition", "fairness", "independence", "craftsmanship", "safety", "community"};
    private static final String[] STYLES = {
            "plain-spoken and brief", "warm and chatty", "dry and sardonic", "formal and careful",
            "excitable, lots of exclamations", "gruff but kind", "thoughtful, asks questions",
            "quietly poetic", "practical, talks in lists"};
    private static final String[] ORIGINS = {
            "grew up on a windswept farm", "was a river trader's child", "left a crowded city to start over",
            "was raised by a stonemason", "spent years as a wandering shepherd", "comes from a family of miners",
            "survived a hard winter alone", "was an apprentice carpenter", "fled a village lost to raiders"};
    private static final String[] ASPIRATIONS = {
            "build a home worth being proud of", "become the settlement's best miner", "never go hungry again",
            "be trusted by everyone here", "see what lies beyond the mountains", "keep a perfect storehouse",
            "grow the finest farm around", "find a place to belong"};
    private static final String[] QUIRKS = {
            "hums while working", "names every tool", "distrusts caves", "loves sunrises",
            "collects pretty flowers", "counts things out loud", "hates getting wet", "talks to animals"};

    public double trait(String key) {
        return traits.getOrDefault(key, 0.5);
    }

    /** Deterministic persona for a given seed (e.g. the settler UUID's bits). */
    public static Persona generate(String name, long seed) {
        Random r = new Random(seed);
        Map<String, Double> t = new LinkedHashMap<>();
        for (String k : List.of(OPENNESS, CONSCIENTIOUSNESS, EXTRAVERSION, AGREEABLENESS, NEUROTICISM)) {
            // Triangular-ish distribution: most people are moderate, a few are extreme.
            t.put(k, Math.round(((r.nextDouble() + r.nextDouble()) / 2) * 100) / 100.0);
        }
        List<String> vals = new ArrayList<>();
        while (vals.size() < 2) {
            String v = VALUES[r.nextInt(VALUES.length)];
            if (!vals.contains(v)) vals.add(v);
        }
        return new Persona(name, t, vals,
                STYLES[r.nextInt(STYLES.length)],
                name + " " + ORIGINS[r.nextInt(ORIGINS.length)] + ".",
                ASPIRATIONS[r.nextInt(ASPIRATIONS.length)],
                QUIRKS[r.nextInt(QUIRKS.length)]);
    }

    /** Human-readable description used in prompts and /hk mind. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(backstory).append(" Values ").append(String.join(" and ", values)).append(". ");
        sb.append("Wants to ").append(aspiration).append(". Speaks in a way that is ").append(speechStyle)
                .append(". Quirk: ").append(quirk).append(".\nPersonality: ");
        traits.forEach((k, v) -> sb.append(k).append(' ').append(level(v)).append(", "));
        sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private static String level(double v) {
        if (v < 0.25) return "very low";
        if (v < 0.42) return "low";
        if (v < 0.58) return "moderate";
        if (v < 0.75) return "high";
        return "very high";
    }
}
