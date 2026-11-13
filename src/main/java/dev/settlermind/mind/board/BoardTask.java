package dev.settlermind.mind.board;

import java.util.ArrayList;
import java.util.List;

/**
 * One task on the settlement board. Plain mutable fields so Gson can persist it; only {@link Board}
 * should mutate it. See docs/BOARD.md.
 */
public final class BoardTask {
    public enum Status { OPEN, CLAIMED, DONE, CANCELLED }

    public static final String KIND_PLAYER = "player";
    public static final String KIND_SETTLER = "settler";
    public static final int MAX_HISTORY = 8;

    /** "T1", "T2", ... (monotonic per board). */
    public String id;
    /** Item id without namespace, e.g. {@code oak_log}. */
    public String item;
    public int count;
    /** Verified amount credited so far; never exceeds {@link #count}. */
    public int progress;
    public String note = "";
    public String postedBy;
    public String postedByKind;
    public Status status = Status.OPEN;
    /** Settler name while CLAIMED (kept on DONE), else null. */
    public String claimant;
    /** Incremented on every state change; the fencing token for claims. */
    public int revision;
    public long createdTick, claimedTick, lastProgressTick, doneTick;
    /** Tick the task became DONE or CANCELLED (used by prune); 0 while active. */
    public long closedTick;
    /** Short bounded log of state changes (at most {@link #MAX_HISTORY} lines, newest last). */
    public List<String> history = new ArrayList<>();

    public boolean active() { return status == Status.OPEN || status == Status.CLAIMED; }

    BoardTask copy() {
        BoardTask t = new BoardTask();
        t.id = id; t.item = item; t.count = count; t.progress = progress; t.note = note;
        t.postedBy = postedBy; t.postedByKind = postedByKind; t.status = status; t.claimant = claimant;
        t.revision = revision; t.createdTick = createdTick; t.claimedTick = claimedTick;
        t.lastProgressTick = lastProgressTick; t.doneTick = doneTick; t.closedTick = closedTick;
        t.history = history == null ? new ArrayList<>() : new ArrayList<>(history);
        return t;
    }

    /** Records a successful mutation: bumps the revision and appends a history line. */
    void changed(long tick, String what) {
        revision++;
        if (history == null) history = new ArrayList<>();
        history.add(tick + ": " + what);
        while (history.size() > MAX_HISTORY) history.remove(0);
    }

    int number() {
        try {
            return Integer.parseInt(id.substring(1));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }
}
