package dev.settlermind.mind;

import dev.settlermind.mind.board.TaskView;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * What a settler can perceive right now: a bounded, engine-agnostic snapshot built on
 * the server thread. It deliberately contains only what the settler could plausibly
 * see or know: no hidden players, no unloaded areas, no other settlers' minds.
 * {@code board} is the settlement task board as this settler sees it (empty when there is none).
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
        List<SeenEntity> entities,
        List<TaskView> board) {

    /**
     * A notable block the settler can see, e.g. a log, ore, crop, chest or bed. {@code reservedBy} is the display
     * name of <em>someone else</em> currently working on it, or null when it is free or held by this settler
     * (the host compares stable ids when filling it; the mind never compares names).
     */
    public record SeenBlock(String id, int x, int y, int z, double distance, @Nullable String reservedBy) {
        public SeenBlock(String id, int x, int y, int z, double distance) {
            this(id, x, y, z, distance, null);
        }
    }

    /** A creature nearby. {@code kind} is "player", "settler", "hostile", "animal" or "item". */
    public record SeenEntity(String kind, String name, double distance, boolean isOwner) {}

    public Perception {
        board = board == null ? List.of() : board;
    }

    public boolean canSeeBlock(int x, int y, int z) {
        return blockAt(x, y, z) != null;
    }

    public @Nullable SeenBlock blockAt(int x, int y, int z) {
        for (SeenBlock b : blocks) if (b.x() == x && b.y() == y && b.z() == z) return b;
        return null;
    }

    /** The visible board task with this id (case-insensitive), or null. */
    public @Nullable TaskView task(String id) {
        if (id == null) return null;
        for (TaskView t : board) if (t.id().equalsIgnoreCase(id.trim())) return t;
        return null;
    }

    /** The same perception with a different board. */
    public Perception withBoard(List<TaskView> newBoard) {
        return new Perception(tick, timeOfDay, weather, position, health, food, holding, inventory, activity,
                blocks, entities, newBoard);
    }

    /** The same perception with no trace of the board: no tasks and no block reservations (board ablation). */
    public Perception withoutBoard() {
        List<SeenBlock> plain = blocks.stream()
                .map(b -> b.reservedBy() == null ? b : new SeenBlock(b.id(), b.x(), b.y(), b.z(), b.distance(), null))
                .toList();
        return new Perception(tick, timeOfDay, weather, position, health, food, holding, inventory, activity,
                plain, entities, List.of());
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
                    .append(" (").append(Math.round(b.distance())).append(" blocks")
                    .append(b.reservedBy() == null ? "" : ", " + b.reservedBy() + " is working on it").append(")\n");
        }
        return sb.toString();
    }
}
