package dev.settlermind.mind.board;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The settlement coordination board (docs/BOARD.md). Engine-agnostic and single-threaded: the host calls
 * it on the game thread. Every rejection is a result code, never an exception.
 */
public final class Board {
    public static final long LEASE_TICKS = 6000;
    public static final long RESERVATION_TTL = 1200;
    public static final long PRUNE_AFTER_TICKS = 24000;
    public static final int MIN_COUNT = 1, MAX_COUNT = 64;
    public static final int MAX_ACTIVE_TASKS = 20;
    public static final int MAX_ACTIVE_POSTS_PER_POSTER = 5;
    public static final int MAX_CLAIMS_PER_SETTLER = 2;
    public static final int MAX_VIEWS = 8;
    public static final int MAX_NOTE = 80;
    public static final int MAX_ITEM_LENGTH = 64;
    public static final Pattern ITEM = Pattern.compile("[a-z0-9_]+");

    public static final String OK = "ok";
    public static final String INVALID_ITEM = "invalid_item";
    public static final String INVALID_COUNT = "invalid_count";
    public static final String INVALID_NAME = "invalid_name";
    public static final String BOARD_FULL = "board_full";
    public static final String TOO_MANY_POSTS = "too_many_posts";
    public static final String NOT_FOUND = "not_found";
    public static final String NOT_OPEN = "not_open";
    public static final String STALE = "stale";
    public static final String TOO_MANY_CLAIMS = "too_many_claims";
    public static final String NOT_YOURS = "not_yours";
    public static final String NOT_ALLOWED = "not_allowed";
    public static final String ALREADY_CLOSED = "already_closed";

    /** Outcome of a board operation: a code and, on success, the task. */
    public record Result(String code, @Nullable BoardTask task) {
        public boolean ok() { return OK.equals(code); }
        static Result of(String code) { return new Result(code, null); }
    }

    /** A claim that lapsed in {@link #expire(long)}. */
    public record Expired(BoardTask task, String formerClaimant) {}

    /** A block reservation (Gson-friendly). */
    public static final class Reservation {
        public String settler;
        public long tick;

        public Reservation() {}
        Reservation(String settler, long tick) { this.settler = settler; this.tick = tick; }
    }

    private final Map<String, BoardTask> tasks = new LinkedHashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private int nextId = 1;

    private static final Comparator<BoardTask> OLDEST_FIRST =
            Comparator.comparingLong((BoardTask t) -> t.createdTick).thenComparingInt(BoardTask::number);

    // ------------------------------------------------------------ tasks

    public Result post(String by, String byKind, String item, int count, @Nullable String note, long tick) {
        if (item == null || item.length() > MAX_ITEM_LENGTH || !ITEM.matcher(item).matches()) return Result.of(INVALID_ITEM);
        if (count < MIN_COUNT || count > MAX_COUNT) return Result.of(INVALID_COUNT);
        if (blank(by)) return Result.of(INVALID_NAME);
        int active = 0, byPoster = 0;
        for (BoardTask t : tasks.values()) {
            if (!t.active()) continue;
            active++;
            if (by.equals(t.postedBy)) byPoster++;
        }
        if (active >= MAX_ACTIVE_TASKS) return Result.of(BOARD_FULL);
        if (byPoster >= MAX_ACTIVE_POSTS_PER_POSTER) return Result.of(TOO_MANY_POSTS);

        BoardTask t = new BoardTask();
        t.id = "T" + nextId++;
        t.item = item;
        t.count = count;
        t.note = note == null ? "" : (note.length() > MAX_NOTE ? note.substring(0, MAX_NOTE) : note);
        t.postedBy = by;
        t.postedByKind = BoardTask.KIND_SETTLER.equals(byKind) ? BoardTask.KIND_SETTLER : BoardTask.KIND_PLAYER;
        t.status = BoardTask.Status.OPEN;
        t.createdTick = tick;
        t.changed(tick, "posted by " + by);
        tasks.put(t.id, t);
        return new Result(OK, t);
    }

    public Result claim(String id, String settler, int expectedRevision, long tick) {
        BoardTask t = id == null ? null : tasks.get(id);
        if (t == null) return Result.of(NOT_FOUND);
        if (blank(settler)) return Result.of(INVALID_NAME);
        if (t.status != BoardTask.Status.OPEN) return Result.of(NOT_OPEN);
        if (t.revision != expectedRevision) return Result.of(STALE);
        if (claimsOf(settler) >= MAX_CLAIMS_PER_SETTLER) return Result.of(TOO_MANY_CLAIMS);
        t.status = BoardTask.Status.CLAIMED;
        t.claimant = settler;
        t.claimedTick = tick;
        t.lastProgressTick = tick;
        t.changed(tick, "claimed by " + settler);
        return new Result(OK, t);
    }

    public Result release(String id, String settler, long tick) {
        BoardTask t = id == null ? null : tasks.get(id);
        if (t == null) return Result.of(NOT_FOUND);
        if (t.status != BoardTask.Status.CLAIMED || settler == null || !settler.equals(t.claimant)) {
            return Result.of(NOT_YOURS);
        }
        t.status = BoardTask.Status.OPEN;
        t.claimant = null;
        t.changed(tick, "released by " + settler);
        return new Result(OK, t);
    }

    public Result cancel(String id, String by, boolean isAdmin, long tick) {
        BoardTask t = id == null ? null : tasks.get(id);
        if (t == null) return Result.of(NOT_FOUND);
        if (!isAdmin && (by == null || !by.equals(t.postedBy))) return Result.of(NOT_ALLOWED);
        if (!t.active()) return Result.of(ALREADY_CLOSED);
        t.status = BoardTask.Status.CANCELLED;
        t.claimant = null;
        t.closedTick = tick;
        t.changed(tick, "cancelled by " + (blank(by) ? "admin" : by));
        return new Result(OK, t);
    }

    /**
     * Credits verified gathering (never model claims) to {@code settler}'s CLAIMED tasks for {@code item},
     * oldest first, never past each task's count. Returns the tasks that changed.
     */
    public List<BoardTask> credit(String settler, String item, int amount, long tick) {
        List<BoardTask> changed = new ArrayList<>();
        if (blank(settler) || item == null || amount <= 0) return changed;
        List<BoardTask> mine = new ArrayList<>();
        for (BoardTask t : tasks.values()) {
            if (t.status == BoardTask.Status.CLAIMED && settler.equals(t.claimant) && item.equals(t.item)) mine.add(t);
        }
        mine.sort(OLDEST_FIRST);
        int left = amount;
        for (BoardTask t : mine) {
            if (left <= 0) break;
            int add = Math.min(left, t.count - t.progress);
            if (add <= 0) continue;
            left -= add;
            t.progress += add;
            t.lastProgressTick = tick;
            if (t.progress >= t.count) {
                t.status = BoardTask.Status.DONE;
                t.doneTick = tick;
                t.closedTick = tick;
                t.changed(tick, "done by " + settler);
            } else {
                t.changed(tick, settler + " +" + add + " (" + t.progress + "/" + t.count + ")");
            }
            changed.add(t);
        }
        return changed;
    }

    /** Claims with no progress for more than {@link #LEASE_TICKS} go back to OPEN. */
    public List<Expired> expire(long tick) {
        List<Expired> out = new ArrayList<>();
        for (BoardTask t : tasks.values()) {
            if (t.status == BoardTask.Status.CLAIMED && tick - t.lastProgressTick > LEASE_TICKS) {
                String former = t.claimant;
                t.status = BoardTask.Status.OPEN;
                t.claimant = null;
                t.changed(tick, "claim by " + former + " lapsed");
                out.add(new Expired(t, former));
            }
        }
        return out;
    }

    /** Removes DONE/CANCELLED tasks closed more than {@link #PRUNE_AFTER_TICKS} ago. Returns how many. */
    public int prune(long tick) {
        int removed = 0;
        Iterator<BoardTask> it = tasks.values().iterator();
        while (it.hasNext()) {
            BoardTask t = it.next();
            if (!t.active() && tick - t.closedTick > PRUNE_AFTER_TICKS) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    public @Nullable BoardTask task(String id) { return id == null ? null : tasks.get(id); }

    public Collection<BoardTask> tasks() { return Collections.unmodifiableCollection(tasks.values()); }

    public int claimsOf(String settler) {
        int n = 0;
        for (BoardTask t : tasks.values()) {
            if (t.status == BoardTask.Status.CLAIMED && settler != null && settler.equals(t.claimant)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------ block reservations

    /** True if {@code posKey} was free, expired, or already held by {@code settler}; (re)starts the TTL. */
    public boolean reserve(String posKey, String settler, long tick) {
        if (posKey == null || blank(settler)) return false;
        String current = holder(posKey, tick);
        if (current != null && !current.equals(settler)) return false;
        reservations.put(posKey, new Reservation(settler, tick));
        return true;
    }

    /** The settler holding {@code posKey}, or null if free or expired. */
    public @Nullable String holder(String posKey, long tick) {
        if (posKey == null) return null;
        Reservation r = reservations.get(posKey);
        if (r == null) return null;
        if (tick - r.tick >= RESERVATION_TTL) {
            reservations.remove(posKey);
            return null;
        }
        return r.settler;
    }

    public void releaseAll(String settler) {
        if (settler == null) return;
        reservations.values().removeIf(r -> settler.equals(r.settler));
    }

    // ------------------------------------------------------------ views

    /** At most {@link #MAX_VIEWS}: the settler's own claims first, then OPEN tasks, each oldest first. */
    public List<TaskView> views(String forSettler, long tick) {
        List<BoardTask> mine = new ArrayList<>();
        List<BoardTask> open = new ArrayList<>();
        for (BoardTask t : tasks.values()) {
            if (t.status == BoardTask.Status.CLAIMED && forSettler != null && forSettler.equals(t.claimant)) mine.add(t);
            else if (t.status == BoardTask.Status.OPEN) open.add(t);
        }
        mine.sort(OLDEST_FIRST);
        open.sort(OLDEST_FIRST);
        List<TaskView> out = new ArrayList<>();
        for (BoardTask t : mine) if (out.size() < MAX_VIEWS) out.add(TaskView.of(t, forSettler));
        for (BoardTask t : open) if (out.size() < MAX_VIEWS) out.add(TaskView.of(t, forSettler));
        return out;
    }

    /** Prompt lines, e.g. {@code T3 [open] gather 4 oak_log for Alex, rev 2}. */
    public static List<String> render(List<TaskView> views) {
        List<String> lines = new ArrayList<>();
        if (views == null) return lines;
        for (TaskView v : views) {
            StringBuilder sb = new StringBuilder(v.id()).append(' ');
            if (v.mine()) {
                sb.append("[yours] gather ").append(v.count()).append(' ').append(v.item())
                        .append(": ").append(v.progress()).append('/').append(v.count());
            } else {
                String status;
                if (v.status() == BoardTask.Status.CLAIMED) status = "claimed by " + v.claimant();
                else status = v.status().name().toLowerCase(java.util.Locale.ROOT);
                sb.append('[').append(status).append("] gather ").append(v.count()).append(' ').append(v.item())
                        .append(" for ").append(v.postedBy());
                if (v.progress() > 0) sb.append(": ").append(v.progress()).append('/').append(v.count());
            }
            sb.append(", rev ").append(v.revision());
            if (v.note() != null && !v.note().isEmpty()) sb.append(" (\"").append(v.note()).append("\")");
            lines.add(sb.toString());
        }
        return lines;
    }

    // ------------------------------------------------------------ persistence

    /** Plain data snapshot for serialisation (Gson). */
    public static final class Saved {
        public int version = 1;
        public int nextId = 1;
        public List<BoardTask> tasks;
        public Map<String, Reservation> reservations;
    }

    public Saved save() {
        Saved s = new Saved();
        s.nextId = nextId;
        s.tasks = new ArrayList<>();
        for (BoardTask t : tasks.values()) s.tasks.add(t.copy());
        s.reservations = new HashMap<>();
        for (Map.Entry<String, Reservation> e : reservations.entrySet()) {
            s.reservations.put(e.getKey(), new Reservation(e.getValue().settler, e.getValue().tick));
        }
        return s;
    }

    public static Board load(@Nullable Saved s) {
        Board b = new Board();
        if (s == null) return b;
        int maxId = 0;
        if (s.tasks != null) {
            for (BoardTask t : s.tasks) {
                if (t == null || t.id == null || t.status == null) continue;
                BoardTask c = t.copy();
                if (c.note == null) c.note = "";
                b.tasks.put(c.id, c);
                if (c.number() != Integer.MAX_VALUE) maxId = Math.max(maxId, c.number());
            }
        }
        b.nextId = Math.max(Math.max(1, s.nextId), maxId + 1);
        if (s.reservations != null) {
            for (Map.Entry<String, Reservation> e : s.reservations.entrySet()) {
                Reservation r = e.getValue();
                if (e.getKey() != null && r != null && r.settler != null) {
                    b.reservations.put(e.getKey(), new Reservation(r.settler, r.tick));
                }
            }
        }
        return b;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
