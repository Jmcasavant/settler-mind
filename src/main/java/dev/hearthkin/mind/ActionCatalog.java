package dev.hearthkin.mind;

import java.util.List;

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
            new Kind("wait", "seconds", "Pause for 1-60 seconds."));

    public static final int MAX_STEPS = 3;
    public static final int MAX_WALK = 32;

    private ActionCatalog() {}

    public static boolean known(String kind) {
        return KINDS.stream().anyMatch(k -> k.name().equals(kind));
    }

    public static String render() {
        StringBuilder sb = new StringBuilder();
        for (Kind k : KINDS) {
            sb.append("- ").append(k.name());
            if (!k.usage().isEmpty()) sb.append(" (").append(k.usage()).append(')');
            sb.append(": ").append(k.description()).append('\n');
        }
        return sb.toString();
    }
}
