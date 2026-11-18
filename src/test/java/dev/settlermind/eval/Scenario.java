package dev.settlermind.eval;

import java.util.List;
import java.util.Map;

/**
 * A reproducible situation for evaluating minds: who lives there, what's around, what
 * players say and when, and what to measure.
 *
 * @param durationTicks game ticks to simulate (24000 = one Minecraft day)
 * @param boardTasks    tasks already on the settlement board at tick 0 (empty for most scenarios)
 */
public record Scenario(
        String name,
        String description,
        List<SettlerSpec> settlers,
        Map<String, String> blocks,
        List<String> playersPresent,
        List<Line> script,
        List<Fact> facts,
        List<String> interview,
        long durationTicks,
        List<TaskSpec> boardTasks) {

    /** A scenario with an empty task board. */
    public Scenario(String name, String description, List<SettlerSpec> settlers, Map<String, String> blocks,
                    List<String> playersPresent, List<Line> script, List<Fact> facts, List<String> interview,
                    long durationTicks) {
        this(name, description, settlers, blocks, playersPresent, script, facts, interview, durationTicks, List.of());
    }

    public Scenario {
        boardTasks = boardTasks == null ? List.of() : List.copyOf(boardTasks);
    }

    /**
     * A GATHER task seeded on the board at tick 0 (docs/BOARD.md). {@code item} is the drop id the simulated
     * body credits (sim blocks drop their own id, except stone, which drops cobblestone).
     *
     * @param postedByKind {@code "player"} or {@code "settler"}
     */
    public record TaskSpec(String postedBy, String postedByKind, String item, int count, String note) {}

    /** @param traits overrides for Big-Five traits (others come from the seed) */
    public record SettlerSpec(String name, long seed, Map<String, Double> traits, String owner, int[] pos) {}

    /**
     * Something a player says at a given tick.
     *
     * @param to        settler addressed by name, or null for everyone in earshot
     * @param whisper   only {@code to} hears it (used to seed facts for diffusion)
     * @param request   true if this asks for an action (counts toward compliance)
     */
    public record Line(long tick, String player, String to, String text, boolean whisper, boolean request) {}

    /** A piece of information to track: who ends up knowing it (all keywords present in a memory). */
    public record Fact(String id, String seededTo, List<String> keywords) {}

    /** "x,y,z" -> block id. */
    public static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
