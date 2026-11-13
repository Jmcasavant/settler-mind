package dev.settlermind.mind;

import java.util.List;
import java.util.Map;

/**
 * What a settler can perceive right now: a bounded, engine-agnostic snapshot built on
 * the server thread. It deliberately contains only what the settler could plausibly
 * see or know: no hidden players, no unloaded areas, no other settlers' minds.
 */
public record Perception(
        long tick,
        String timeOfDay,
        String weather,
        int[] position,
        float health,
        int food,
        String holding,
        Map<String, Integer> inventory,
        String activity,
        List<SeenBlock> blocks,
        List<SeenEntity> entities) {

    /** A notable block the settler can see, e.g. a log, ore, crop, chest or bed. */
    public record SeenBlock(String id, int x, int y, int z, double distance) {}

    /** A creature nearby. {@code kind} is "player", "settler", "hostile", "animal" or "item". */
    public record SeenEntity(String kind, String name, double distance, boolean isOwner) {}

    public boolean canSeeBlock(int x, int y, int z) {
        return blocks.stream().anyMatch(b -> b.x() == x && b.y() == y && b.z() == z);
    }

    public boolean canSee(String kind, String name) {
        return entities.stream().anyMatch(e -> e.kind().equals(kind) && e.name().equalsIgnoreCase(name));
    }

    /** Compact text for the prompt. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Time: ").append(timeOfDay).append(", weather: ").append(weather).append('\n');
        sb.append("You are at ").append(position[0]).append(' ').append(position[1]).append(' ').append(position[2])
                .append(". Health ").append(Math.round(health)).append("/20, food ").append(food).append("/20.\n");
        sb.append("Holding: ").append(holding).append(". Carrying: ");
        if (inventory.isEmpty()) sb.append("nothing");
        else inventory.forEach((k, v) -> sb.append(v).append(' ').append(k).append(", "));
        sb.append("\nCurrently: ").append(activity).append('\n');
        sb.append("You can see:\n");
        if (entities.isEmpty() && blocks.isEmpty()) sb.append("- nothing notable\n");
        for (SeenEntity e : entities) {
            sb.append("- ").append(e.kind()).append(' ').append(e.name())
                    .append(e.isOwner() ? " (the one who took you in)" : "")
                    .append(", ").append(Math.round(e.distance())).append(" blocks away\n");
        }
        for (SeenBlock b : blocks) {
            sb.append("- ").append(b.id()).append(" at ").append(b.x()).append(' ').append(b.y()).append(' ').append(b.z())
                    .append(" (").append(Math.round(b.distance())).append(" blocks)\n");
        }
        return sb.toString();
    }
}
