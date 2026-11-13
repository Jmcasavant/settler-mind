package dev.settlermind.mind;

import dev.settlermind.mind.board.Board;
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

    /**
     * A concrete step. Unused fields are null. Body steps (mine, go_to, ...) are carried out by the body;
     * board steps (post_task, claim_task, release_task, cancel_task) are instant social/mental acts the host applies.
     */
    public record Step(String kind, @Nullable String target, @Nullable Integer x, @Nullable Integer y,
                       @Nullable Integer z, @Nullable Integer seconds, @Nullable String item,
                       @Nullable Integer count, @Nullable String task) {
        /** A body step with no board fields. */
        public Step(String kind, @Nullable String target, @Nullable Integer x, @Nullable Integer y,
                    @Nullable Integer z, @Nullable Integer seconds) {
            this(kind, target, x, y, z, seconds, null, null, null);
        }

        public String describe() {
            return switch (kind) {
                case "mine" -> "mine the block at " + x + " " + y + " " + z;
                case "go_to" -> "walk to " + x + " " + y + " " + z;
                case "follow" -> "follow " + target;
                case "stay" -> "stay put";
                case "wait" -> "wait " + seconds + "s";
                case "post_task" -> "post a task on the board: gather " + count + " " + Board.displayItem(item);
                case "claim_task" -> "claim task " + task + " on the board";
                case "release_task" -> "release task " + task + " on the board";
                case "cancel_task" -> "cancel my task " + task + " on the board";
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
