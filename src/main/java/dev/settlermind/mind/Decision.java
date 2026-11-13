package dev.settlermind.mind;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One validated decision from a settler's mind. Produced only by {@link DecisionParser};
 * every step has been checked against the action catalog and the settler's perception.
 */
public record Decision(
        String thought,
        @Nullable String say,
        @Nullable String sayTo,
        String mood,
        @Nullable String goal,
        List<Step> steps,
        List<Feeling> feelings,
        @Nullable Remember remember) {

    /** A concrete step for the body. Unused fields are null. */
    public record Step(String kind, @Nullable String target, @Nullable Integer x, @Nullable Integer y,
                       @Nullable Integer z, @Nullable Integer seconds) {
        public String describe() {
            return switch (kind) {
                case "mine" -> "mine the block at " + x + " " + y + " " + z;
                case "go_to" -> "walk to " + x + " " + y + " " + z;
                case "follow" -> "follow " + target;
                case "stay" -> "stay put";
                case "wait" -> "wait " + seconds + "s";
                default -> kind;
            };
        }
    }

    /** A change in how the settler feels about someone, in [-2, 2]. */
    public record Feeling(String who, int change, String why) {}

    /** Something the settler wants to remember on purpose. */
    public record Remember(String text, int importance) {}

    public static Decision idle(String thought) {
        return new Decision(thought, null, null, "calm", null, List.of(), List.of(), null);
    }
}
