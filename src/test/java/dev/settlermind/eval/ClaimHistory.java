package dev.settlermind.eval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An append-only record of who held which board task when, and who got items from which block. The
 * simulation writes it from the results of the operations it performs (a granted claim, a release, a lapse
 * reported by upkeep, a credit that finished a task, a cancel, a verified pickup), never from the board's
 * current state. Double claims and double credits are then derived from this history, so a board bug that
 * overwrote one claimant with another would still show up here even though the board itself can only
 * ever hold one claimant per task.
 */
public final class ClaimHistory {
    public enum Kind { CLAIMED, RELEASED, LAPSED, DONE, CANCELLED }

    /** One claim event. {@code settler} is null for CANCELLED (the cancel ends every holder's interval). */
    public record Event(long tick, String task, String settler, Kind kind) {}

    /** A verified gain of items from a broken block. */
    public record Gain(long tick, String block, String settler, String item) {}

    public final List<Event> events = new ArrayList<>();
    public final List<Gain> gains = new ArrayList<>();

    void claimed(long tick, String task, String settler) { events.add(new Event(tick, task, settler, Kind.CLAIMED)); }
    void released(long tick, String task, String settler) { events.add(new Event(tick, task, settler, Kind.RELEASED)); }
    void lapsed(long tick, String task, String settler) { events.add(new Event(tick, task, settler, Kind.LAPSED)); }
    void done(long tick, String task, String settler) { events.add(new Event(tick, task, settler, Kind.DONE)); }
    void cancelled(long tick, String task) { events.add(new Event(tick, task, null, Kind.CANCELLED)); }
    void gained(long tick, String block, String settler, String item) { gains.add(new Gain(tick, block, settler, item)); }

    /**
     * Claim grants made while a different settler's claim on the same task was still open in the history
     * (an interval opens at CLAIMED and closes at RELEASED, LAPSED or DONE for that settler, or CANCELLED).
     * Events are replayed in the order they were recorded. Must be 0.
     */
    public int doubleClaims() {
        return doubleClaimSamples(Integer.MAX_VALUE).size();
    }

    /** The overlapping grants behind {@link #doubleClaims()}, e.g. {@code T1 @220: Hugo while held by [Fern]}. */
    public List<String> doubleClaimSamples(int max) {
        List<String> out = new ArrayList<>();
        Map<String, Set<String>> holders = new HashMap<>();
        for (Event e : events) {
            Set<String> h = holders.computeIfAbsent(e.task(), k -> new LinkedHashSet<>());
            switch (e.kind()) {
                case CLAIMED -> {
                    Set<String> others = new LinkedHashSet<>(h);
                    others.remove(e.settler());
                    if (!others.isEmpty() && out.size() < max) {
                        out.add(e.task() + " @" + e.tick() + ": " + e.settler() + " while held by " + others);
                    }
                    h.add(e.settler());
                }
                case CANCELLED -> h.clear();
                default -> h.remove(e.settler());
            }
        }
        return out;
    }

    /** Blocks whose items were gained more than once (by anyone): each extra gain counts. Must be 0. */
    public int doubleCredits() {
        Map<String, Integer> perBlock = new LinkedHashMap<>();
        for (Gain g : gains) perBlock.merge(g.block(), 1, Integer::sum);
        int n = 0;
        for (int c : perBlock.values()) n += Math.max(0, c - 1);
        return n;
    }
}
