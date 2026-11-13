package dev.settlermind.mind;

import java.util.List;
import java.util.Set;

/**
 * The closed set of things a settler's mind may ask its body to do. The model can only
 * choose from this list; anything else is rejected by {@link DecisionParser}. There is
 * deliberately no command, chat-as-op, file, URL or code-execution step.
 */
public final class ActionCatalog {
    public record Kind(String name, String usage, String description) {}

    public static final List<Kind> KINDS = List.of(
            new Kind("mine", "x,y,z", "Mine a block you can see and pick up what drops. Use the exact coordinates from what you see."),
            new Kind("go_to", "x,y,z", "Walk to a spot within 32 blocks."),
            new Kind("follow", "target", "Follow a player you can see, by name."),
            new Kind("stay", "", "Stop following and stay where you are."),
            new Kind("wait", "seconds", "Pause for 1-60 seconds."),
            new Kind("post_task", "item,count", "Ask for help on the settlement task board: gather count (1-64) of an item id like oak_log. Instant."),
            new Kind("claim_task", "task", "Take an open task from the board by its id, e.g. T3. Instant; then do the work yourself."),
            new Kind("release_task", "task", "Give up a task you hold, by its id, so someone else can take it. Instant."));

    /** Instant social/mental steps on the settlement board, executed by the host rather than the body. */
    public static final Set<String> BOARD_KINDS = Set.of("post_task", "claim_task", "release_task");

    public static final int MAX_STEPS = 3;
    public static final int MAX_WALK = 32;

    private ActionCatalog() {}

    public static boolean known(String kind) {
        return KINDS.stream().anyMatch(k -> k.name().equals(kind));
    }

    public static boolean isBoard(String kind) {
        return kind != null && BOARD_KINDS.contains(kind);
    }

    /** All kinds, board steps included. */
    public static String render() {
        return render(true);
    }

    /** The catalog text; board steps only when the board feature is on. */
    public static String render(boolean includeBoard) {
        StringBuilder sb = new StringBuilder();
        for (Kind k : KINDS) {
            if (!includeBoard && isBoard(k.name())) continue;
            sb.append("- ").append(k.name());
            if (!k.usage().isEmpty()) sb.append(" (").append(k.usage()).append(')');
            sb.append(": ").append(k.description()).append('\n');
        }
        return sb.toString();
    }
}
