package dev.settlermind.mind.board;

import java.util.ArrayList;
import java.util.List;

/**
 * One task on the settlement board. Plain mutable fields so Gson can persist it; only {@link Board}
 * should mutate it. See docs/BOARD.md.
 *
 * <p>Identity: posters and claimants are keyed by a stable id (a player or settler UUID string) plus
 * a kind; the names are for display only and never decide authority.
 */
public final class BoardTask {
    public enum Status { OPEN, CLAIMED, DONE, CANCELLED }

    public static final String KIND_PLAYER = "player";
    public static final String KIND_SETTLER = "settler";
    public static final int MAX_HISTORY = 8;

    /** "T1", "T2", ... (monotonic per board). */
    public String id;
    /** Full, normalised item id, e.g. {@code minecraft:oak_log} (see {@link Board#normalizeItem}). */
    public String item;
    public int count;
    /** Verified amount credited so far; never exceeds {@link #count}. */
    public int progress;
    /** Sanitised words of the poster (one line, no quotes or control characters); may be empty. */
    public String note = "";
    /** Stable id of the poster (player or settler UUID string) and its kind, {@link #KIND_PLAYER} or {@link #KIND_SETTLER}. */
    public String posterId;
    public String posterKind;
    /** Display only. */
    public String posterName;
    public Status status = Status.OPEN;
    /** Stable id of the claiming settler while CLAIMED (kept on DONE), else null. */
    public String claimantId;
    /** Display only. */
    public String claimantName;
    /** Incremented on every state change; the fencing token for claims. */
    public int revision;
    public long createdTick, claimedTick, lastProgressTick, doneTick;
    /** Tick the task became DONE or CANCELLED (used by prune); 0 while active. */
    public long closedTick;
    /** Short bounded log of state changes (at most {@link #MAX_HISTORY} lines, newest last). */
    public List<String> history = new ArrayList<>();

    public boolean active() { return status == Status.OPEN || status == Status.CLAIMED; }

    /** True if this task was posted by exactly this (kind, id). */
    public boolean postedBy(String kind, String id) {
        return id != null && kind != null && id.equals(posterId) && kind.equals(posterKind);
    }

    /** True if this task is CLAIMED by exactly this settler id. */
    public boolean claimedBy(String settlerId) {
        return status == Status.CLAIMED && settlerId != null && settlerId.equals(claimantId);
    }

    BoardTask copy() {
        BoardTask t = new BoardTask();
        t.id = id; t.item = item; t.count = count; t.progress = progress; t.note = note;
        t.posterId = posterId; t.posterKind = posterKind; t.posterName = posterName; t.status = status;
        t.claimantId = claimantId; t.claimantName = claimantName;
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
