package dev.hearthkin.mind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a settler feels about each person or settler it knows. Feelings change slowly
 * and only with a reason: one compliment is not friendship, one bad day is not a feud.
 * Agreeable settlers forgive faster; neurotic ones hold on to slights.
 */
public final class Relationships {
    public static final class Bond {
        public String name;
        /** -1 (hostile) .. 1 (devoted). */
        public double affinity;
        /** 0 (none) .. 1 (complete). */
        public double trust;
        /** 0 (stranger) .. 1 (knows them well). */
        public double familiarity;
        public long lastSeen;
        public List<String> notes = new ArrayList<>();

        Bond(String name, double affinity, double trust) {
            this.name = name;
            this.affinity = affinity;
            this.trust = trust;
        }

        public String describe() {
            String feel = affinity > 0.6 ? "loves" : affinity > 0.25 ? "likes" : affinity > -0.25 ? "is neutral about"
                    : affinity > -0.6 ? "dislikes" : "can't stand";
            String tr = trust > 0.7 ? "trusts completely" : trust > 0.4 ? "mostly trusts" : trust > 0.2 ? "is wary of" : "does not trust";
            String fam = familiarity > 0.6 ? "knows well" : familiarity > 0.2 ? "is getting to know" : "barely knows";
            String why = notes.isEmpty() ? "" : " Because: " + String.join("; ", notes);
            return feel + ", " + tr + ", " + fam + "." + why;
        }
    }

    private final Map<String, Bond> bonds = new LinkedHashMap<>();

    public Bond get(String name) {
        return bonds.computeIfAbsent(name.toLowerCase(), k -> new Bond(name, 0.0, 0.3));
    }

    public boolean knows(String name) { return bonds.containsKey(name.toLowerCase()); }

    /** The owner starts with a head start: they took this settler in. */
    public void seedOwner(String name) {
        Bond b = get(name);
        b.affinity = Math.max(b.affinity, 0.35);
        b.trust = Math.max(b.trust, 0.55);
        b.familiarity = Math.max(b.familiarity, 0.3);
        if (b.notes.isEmpty()) b.notes.add("took me in");
    }

    /**
     * Apply a feeling change of {@code delta} in [-2, 2] with inertia.
     * Positive changes are scaled by agreeableness; negative ones by neuroticism.
     */
    public void feel(Persona p, String name, int delta, String why, long tick) {
        Bond b = get(name);
        int d = Math.max(-2, Math.min(2, delta));
        double scale = d > 0 ? 0.04 + 0.04 * p.trait(Persona.AGREEABLENESS)
                             : 0.04 + 0.05 * p.trait(Persona.NEUROTICISM);
        // Diminishing returns near the extremes.
        double room = d > 0 ? 1 - b.affinity : 1 + b.affinity;
        b.affinity = clamp(b.affinity + d * scale * Math.max(0.2, room), -1, 1);
        b.trust = clamp(b.trust + d * scale * 0.5, 0, 1);
        b.familiarity = clamp(b.familiarity + 0.02, 0, 1);
        b.lastSeen = tick;
        if (why != null && !why.isBlank() && Math.abs(d) >= 1) {
            b.notes.add(MemoryStream.clip(why.trim(), 80));
            if (b.notes.size() > 4) b.notes.remove(0);
        }
    }

    public void interacted(String name, long tick) {
        Bond b = get(name);
        b.familiarity = clamp(b.familiarity + 0.01, 0, 1);
        b.lastSeen = tick;
    }

    public Map<String, Bond> all() { return bonds; }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
