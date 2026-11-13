package dev.hearthkin.mind;

/**
 * Drives that push a settler to act when nobody is telling it what to do. Each value is
 * in [0, 1] where 1 means fully satisfied. Hunger and safety are read from the body;
 * social and purpose decay over time and are restored by talking and by finishing work.
 */
public final class Needs {
    public double hunger = 1.0;   // from food level
    public double safety = 1.0;   // from health and nearby threats
    public double social = 0.7;   // restored by conversation
    public double purpose = 0.7;  // restored by completing chosen work

    /** Advance by {@code seconds} of game time, at rates shaped by personality. */
    public void decay(Persona p, double seconds) {
        // Extraverts get lonely faster; conscientious settlers get restless faster.
        social = clamp(social - seconds * (0.0008 + 0.0016 * p.trait(Persona.EXTRAVERSION)));
        purpose = clamp(purpose - seconds * (0.0006 + 0.0016 * p.trait(Persona.CONSCIENTIOUSNESS)));
    }

    public void satisfySocial(double amount) { social = clamp(social + amount); }
    public void satisfyPurpose(double amount) { purpose = clamp(purpose + amount); }

    /** @return the name of the least-satisfied need, used to steer idle decisions. */
    public String mostPressing() {
        String name = "hunger";
        double min = hunger;
        if (safety < min) { min = safety; name = "safety"; }
        if (social < min) { min = social; name = "social"; }
        if (purpose < min) { name = "purpose"; }
        return name;
    }

    public String describe() {
        return "hunger " + word(hunger) + ", safety " + word(safety) + ", social " + word(social)
                + ", sense of purpose " + word(purpose) + " (most pressing: " + mostPressing() + ")";
    }

    private static String word(double v) {
        if (v < 0.2) return "desperate";
        if (v < 0.4) return "low";
        if (v < 0.7) return "okay";
        return "satisfied";
    }

    static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
}
