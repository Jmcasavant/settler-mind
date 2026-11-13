package dev.settlermind.mind;

/**
 * Switches for the parts of a mind, so evaluations can measure what each one is worth
 * (ablations, as in Generative Agents and Lyfe Agents). Gameplay always uses {@link #FULL}.
 */
public record MindFeatures(
        boolean reflection,
        boolean dailyPlan,
        boolean summary,
        boolean relationships,
        boolean lessons,
        boolean memoryRecall) {

    public static final MindFeatures FULL = new MindFeatures(true, true, true, true, true, true);

    public MindFeatures without(String feature) {
        return switch (feature) {
            case "reflection" -> new MindFeatures(false, dailyPlan, summary, relationships, lessons, memoryRecall);
            case "plan" -> new MindFeatures(reflection, false, summary, relationships, lessons, memoryRecall);
            case "summary" -> new MindFeatures(reflection, dailyPlan, false, relationships, lessons, memoryRecall);
            case "relationships" -> new MindFeatures(reflection, dailyPlan, summary, false, lessons, memoryRecall);
            case "lessons" -> new MindFeatures(reflection, dailyPlan, summary, relationships, false, memoryRecall);
            case "memory" -> new MindFeatures(reflection, dailyPlan, summary, relationships, lessons, false);
            default -> throw new IllegalArgumentException("unknown feature: " + feature);
        };
    }
}
