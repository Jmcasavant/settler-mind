package dev.settlermind.mind.board;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoardTest {
    static BoardTask post(Board b, String by, String item, int count, long tick) {
        Board.Result r = b.post(by, "player", item, count, null, tick);
        assertEquals(Board.OK, r.code());
        return r.task();
    }

    static BoardTask postClaimed(Board b, String settler, String item, int count, long tick) {
        BoardTask t = post(b, "Alex", item, count, tick);
        assertEquals(Board.OK, b.claim(t.id, settler, t.revision, tick).code());
        return t;
    }

    // ------------------------------------------------------------ post

    @Test
    void postCreatesOpenTaskWithMonotonicIds() {
        Board b = new Board();
        Board.Result r = b.post("Alex", "player", "oak_log", 4, "for the hut", 10);
        assertTrue(r.ok());
        BoardTask t = r.task();
        assertEquals("T1", t.id);
        assertEquals(BoardTask.Status.OPEN, t.status);
        assertEquals("oak_log", t.item);
        assertEquals(4, t.count);
        assertEquals(0, t.progress);
        assertEquals("for the hut", t.note);
        assertEquals("Alex", t.postedBy);
        assertEquals("player", t.postedByKind);
        assertEquals(10, t.createdTick);
        assertEquals(1, t.revision);
        assertNull(t.claimant);
        assertEquals("T2", post(b, "Alex", "stone", 1, 11).id);
        assertEquals("settler", b.post("Tess", "settler", "dirt", 1, null, 12).task().postedByKind);
    }

    @Test
    void postRejectsInvalidInputWithCodesNeverThrows() {
        Board b = new Board();
        assertEquals(Board.INVALID_ITEM, b.post("Alex", "player", null, 4, null, 0).code());
        assertEquals(Board.INVALID_ITEM, b.post("Alex", "player", "", 4, null, 0).code());
        assertEquals(Board.INVALID_ITEM, b.post("Alex", "player", "minecraft:oak_log", 4, null, 0).code());
        assertEquals(Board.INVALID_ITEM, b.post("Alex", "player", "Oak_Log", 4, null, 0).code());
        assertEquals(Board.INVALID_ITEM, b.post("Alex", "player", "oak log", 4, null, 0).code());
        assertEquals(Board.INVALID_ITEM, b.post("Alex", "player", "a".repeat(65), 4, null, 0).code());
        assertEquals(Board.INVALID_COUNT, b.post("Alex", "player", "oak_log", 0, null, 0).code());
        assertEquals(Board.INVALID_COUNT, b.post("Alex", "player", "oak_log", -3, null, 0).code());
        assertEquals(Board.INVALID_COUNT, b.post("Alex", "player", "oak_log", 65, null, 0).code());
        assertEquals(Board.INVALID_NAME, b.post(null, "player", "oak_log", 1, null, 0).code());
        assertEquals(Board.INVALID_NAME, b.post("  ", null, "oak_log", 1, null, 0).code());
        assertTrue(b.post("Alex", "player", "oak_log", 64, null, 0).ok());
        assertTrue(b.post("Alex", "player", "oak_log", 1, null, 0).ok());
        assertNull(b.post("Alex", "player", "x", 0, null, 0).task());
        assertEquals(2, b.tasks().size());
    }

    @Test
    void postTruncatesLongNotes() {
        Board b = new Board();
        BoardTask t = b.post("Alex", "player", "dirt", 1, "x".repeat(200), 0).task();
        assertEquals(Board.MAX_NOTE, t.note.length());
    }

    @Test
    void postLimitsPerPosterAndBoard() {
        Board b = new Board();
        for (int i = 0; i < 5; i++) post(b, "Alex", "dirt", 1, i);
        assertEquals(Board.TOO_MANY_POSTS, b.post("Alex", "player", "dirt", 1, null, 9).code());
        // Closing one frees a slot for the poster.
        assertTrue(b.cancel("T1", "Alex", false, 10).ok());
        assertTrue(b.post("Alex", "player", "dirt", 1, null, 11).ok());

        // Fill the board to 20 active tasks with other posters.
        for (int p = 0; p < 3; p++) for (int i = 0; i < 5; i++) post(b, "P" + p, "dirt", 1, 20);
        assertEquals(Board.BOARD_FULL, b.post("Zed", "player", "dirt", 1, null, 30).code());
        // Claimed tasks still count as active.
        postClaimedOnFull(b);
    }

    private static void postClaimedOnFull(Board b) {
        BoardTask t = b.task("T2");
        assertTrue(b.claim(t.id, "Tess", t.revision, 31).ok());
        assertEquals(Board.BOARD_FULL, b.post("Zed", "player", "dirt", 1, null, 32).code());
    }

    // ------------------------------------------------------------ claim

    @Test
    void claimIsSingleClaimantAndFencedByRevision() {
        Board b = new Board();
        BoardTask t = post(b, "Alex", "oak_log", 3, 0);
        int seen = t.revision;
        Board.Result first = b.claim(t.id, "Tess", seen, 5);
        assertTrue(first.ok());
        assertEquals(BoardTask.Status.CLAIMED, t.status);
        assertEquals("Tess", t.claimant);
        assertEquals(5, t.claimedTick);
        assertEquals(5, t.lastProgressTick);
        assertEquals(seen + 1, t.revision);

        // A second settler deciding on the same snapshot cannot take it.
        assertEquals(Board.NOT_OPEN, b.claim(t.id, "Bram", seen, 6).code());
        assertEquals("Tess", t.claimant);

        // After release the task is OPEN again, but the old revision is stale.
        assertTrue(b.release(t.id, "Tess", 7).ok());
        assertEquals(Board.STALE, b.claim(t.id, "Bram", seen, 8).code());
        assertEquals(BoardTask.Status.OPEN, t.status);
        assertTrue(b.claim(t.id, "Bram", t.revision, 9).ok());
        assertEquals("Bram", t.claimant);
    }

    @Test
    void claimRejectsUnknownAndClosed() {
        Board b = new Board();
        assertEquals(Board.NOT_FOUND, b.claim("T9", "Tess", 1, 0).code());
        assertEquals(Board.NOT_FOUND, b.claim(null, "Tess", 1, 0).code());
        BoardTask t = post(b, "Alex", "dirt", 1, 0);
        assertEquals(Board.INVALID_NAME, b.claim(t.id, null, t.revision, 0).code());
        b.cancel(t.id, "Alex", false, 1);
        assertEquals(Board.NOT_OPEN, b.claim(t.id, "Tess", t.revision, 2).code());
    }

    @Test
    void settlerHoldsAtMostTwoClaims() {
        Board b = new Board();
        BoardTask a = post(b, "Alex", "dirt", 1, 0);
        BoardTask c = post(b, "Alex", "dirt", 1, 0);
        BoardTask d = post(b, "Alex", "dirt", 1, 0);
        assertTrue(b.claim(a.id, "Tess", a.revision, 1).ok());
        assertTrue(b.claim(c.id, "Tess", c.revision, 1).ok());
        int before = d.revision;
        assertEquals(Board.TOO_MANY_CLAIMS, b.claim(d.id, "Tess", d.revision, 1).code());
        assertEquals(before, d.revision);
        assertEquals(BoardTask.Status.OPEN, d.status);
        assertEquals(2, b.claimsOf("Tess"));
        // Completing one frees a slot.
        b.credit("Tess", "dirt", 1, 2);
        assertTrue(b.claim(d.id, "Tess", d.revision, 3).ok());
    }

    // ------------------------------------------------------------ release / cancel

    @Test
    void releaseOnlyByClaimant() {
        Board b = new Board();
        BoardTask t = postClaimed(b, "Tess", "dirt", 2, 0);
        assertEquals(Board.NOT_FOUND, b.release("T9", "Tess", 1).code());
        assertEquals(Board.NOT_YOURS, b.release(t.id, "Bram", 1).code());
        assertEquals(Board.NOT_YOURS, b.release(t.id, null, 1).code());
        int rev = t.revision;
        assertTrue(b.release(t.id, "Tess", 2).ok());
        assertEquals(BoardTask.Status.OPEN, t.status);
        assertNull(t.claimant);
        assertEquals(rev + 1, t.revision);
        assertEquals(Board.NOT_YOURS, b.release(t.id, "Tess", 3).code());
    }

    @Test
    void cancelByPosterOrAdmin() {
        Board b = new Board();
        BoardTask t = post(b, "Alex", "dirt", 2, 0);
        assertEquals(Board.NOT_FOUND, b.cancel("T9", "Alex", true, 1).code());
        assertEquals(Board.NOT_ALLOWED, b.cancel(t.id, "Tess", false, 1).code());
        assertEquals(Board.NOT_ALLOWED, b.cancel(t.id, null, false, 1).code());
        assertTrue(b.cancel(t.id, "Alex", false, 2).ok());
        assertEquals(BoardTask.Status.CANCELLED, t.status);
        assertEquals(Board.ALREADY_CLOSED, b.cancel(t.id, "Alex", false, 3).code());

        BoardTask c = postClaimed(b, "Tess", "dirt", 2, 4);
        assertTrue(b.cancel(c.id, "Op", true, 5).ok());
        assertEquals(BoardTask.Status.CANCELLED, c.status);
        assertNull(c.claimant);
        assertEquals(0, b.claimsOf("Tess"));
    }

    // ------------------------------------------------------------ credit

    @Test
    void creditGoesOnlyToClaimantsMatchingTasksOldestFirstNeverPastCount() {
        Board b = new Board();
        BoardTask older = postClaimed(b, "Tess", "oak_log", 3, 0);
        BoardTask newer = postClaimed(b, "Tess", "oak_log", 2, 10);
        BoardTask other = postClaimed(b, "Bram", "oak_log", 5, 0);
        BoardTask open = post(b, "Alex", "oak_log", 5, 0);

        assertTrue(b.credit("Tess", "birch_log", 3, 20).isEmpty());
        assertTrue(b.credit("Tess", "oak_log", 0, 20).isEmpty());
        assertTrue(b.credit("Tess", "oak_log", -1, 20).isEmpty());
        assertTrue(b.credit(null, "oak_log", 1, 20).isEmpty());
        assertTrue(b.credit("Tess", null, 1, 20).isEmpty());

        List<BoardTask> changed = b.credit("Tess", "oak_log", 2, 30);
        assertEquals(List.of(older), changed);
        assertEquals(2, older.progress);
        assertEquals(30, older.lastProgressTick);
        assertEquals(0, newer.progress);

        // 10 more: 1 finishes older, 2 finish newer, the rest is discarded.
        changed = b.credit("Tess", "oak_log", 10, 40);
        assertEquals(List.of(older, newer), changed);
        assertEquals(3, older.progress);
        assertEquals(2, newer.progress);
        assertEquals(BoardTask.Status.DONE, older.status);
        assertEquals(BoardTask.Status.DONE, newer.status);
        assertEquals("Tess", older.claimant);
        assertEquals(40, older.doneTick);
        assertEquals(0, other.progress);
        assertEquals(0, open.progress);

        // DONE tasks take no further credit.
        assertTrue(b.credit("Tess", "oak_log", 5, 50).isEmpty());
        assertEquals(3, older.progress);
    }

    // ------------------------------------------------------------ expire

    @Test
    void claimsExpireAfterLeaseWithoutProgressAndProgressRenewsLease() {
        Board b = new Board();
        BoardTask t = postClaimed(b, "Tess", "dirt", 5, 100);
        assertTrue(b.expire(100 + Board.LEASE_TICKS).isEmpty());
        b.credit("Tess", "dirt", 1, 5000);
        assertTrue(b.expire(100 + Board.LEASE_TICKS + 1).isEmpty(), "progress resets the lease");
        assertEquals(BoardTask.Status.CLAIMED, t.status);

        int rev = t.revision;
        List<Board.Expired> lapsed = b.expire(5000 + Board.LEASE_TICKS + 1);
        assertEquals(1, lapsed.size());
        assertSame(t, lapsed.get(0).task());
        assertEquals("Tess", lapsed.get(0).formerClaimant());
        assertEquals(BoardTask.Status.OPEN, t.status);
        assertNull(t.claimant);
        assertEquals(1, t.progress, "verified progress is kept");
        assertEquals(rev + 1, t.revision);
        assertTrue(b.expire(99999).isEmpty());
    }

    // ------------------------------------------------------------ prune

    @Test
    void pruneRemovesOldClosedTasksOnly() {
        Board b = new Board();
        BoardTask done = postClaimed(b, "Tess", "dirt", 1, 0);
        b.credit("Tess", "dirt", 1, 100);
        BoardTask cancelled = post(b, "Alex", "dirt", 1, 0);
        b.cancel(cancelled.id, "Alex", false, 200);
        BoardTask open = post(b, "Alex", "dirt", 1, 0);

        assertEquals(0, b.prune(100 + Board.PRUNE_AFTER_TICKS));
        assertEquals(1, b.prune(150 + Board.PRUNE_AFTER_TICKS));
        assertNull(b.task(done.id));
        assertEquals(1, b.prune(1_000_000));
        assertNull(b.task(cancelled.id));
        assertSame(open, b.task(open.id));
        // Ids stay monotonic after pruning.
        assertEquals("T4", post(b, "Alex", "dirt", 1, 1_000_001).id);
    }

    // ------------------------------------------------------------ history

    @Test
    void everyMutationBumpsRevisionAndHistoryIsBounded() {
        Board b = new Board();
        BoardTask t = post(b, "Alex", "dirt", 64, 0);
        for (int i = 0; i < 10; i++) {
            assertTrue(b.claim(t.id, "Tess", t.revision, i).ok());
            assertTrue(b.release(t.id, "Tess", i).ok());
        }
        assertEquals(21, t.revision);
        assertEquals(BoardTask.MAX_HISTORY, t.history.size());
        assertTrue(t.history.get(t.history.size() - 1).contains("released by Tess"));
    }

    // ------------------------------------------------------------ reservations

    @Test
    void reservationsAreExclusiveReentrantAndExpire() {
        Board b = new Board();
        assertNull(b.holder("1,64,2", 0));
        assertTrue(b.reserve("1,64,2", "Tess", 0));
        assertEquals("Tess", b.holder("1,64,2", 10));
        assertFalse(b.reserve("1,64,2", "Bram", 10));
        assertTrue(b.reserve("1,64,2", "Tess", 1000), "re-entrant for the holder, renews TTL");
        assertEquals("Tess", b.holder("1,64,2", 1000 + Board.RESERVATION_TTL - 1));
        assertNull(b.holder("1,64,2", 1000 + Board.RESERVATION_TTL));
        assertTrue(b.reserve("1,64,2", "Bram", 1000 + Board.RESERVATION_TTL));
        assertEquals("Bram", b.holder("1,64,2", 2500));

        assertFalse(b.reserve(null, "Tess", 0));
        assertFalse(b.reserve("5,5,5", null, 0));
        assertNull(b.holder(null, 0));
    }

    @Test
    void releaseAllDropsOnlyThatSettlersReservations() {
        Board b = new Board();
        b.reserve("0,0,0", "Tess", 0);
        b.reserve("0,0,1", "Tess", 0);
        b.reserve("0,0,2", "Bram", 0);
        b.releaseAll("Tess");
        b.releaseAll(null);
        assertNull(b.holder("0,0,0", 1));
        assertNull(b.holder("0,0,1", 1));
        assertEquals("Bram", b.holder("0,0,2", 1));
    }

    // ------------------------------------------------------------ views / render

    @Test
    void viewsShowOwnClaimsFirstThenOpenOldestFirstAtMostEight() {
        Board b = new Board();
        BoardTask others = postClaimed(b, "Bram", "dirt", 1, 0);
        for (int i = 0; i < 10; i++) post(b, "P" + (i % 3), "stone", 1, 100 - i); // T2..T11, T11 oldest
        BoardTask mine = postClaimed(b, "Tess", "oak_log", 3, 200);
        BoardTask done = postClaimed(b, "Tess", "sand", 1, 5);
        b.credit("Tess", "sand", 1, 6);

        List<TaskView> v = b.views("Tess", 300);
        assertEquals(Board.MAX_VIEWS, v.size());
        assertEquals(mine.id, v.get(0).id());
        assertTrue(v.get(0).mine());
        assertEquals("T11", v.get(1).id());
        assertEquals("T10", v.get(2).id());
        for (int i = 1; i < v.size(); i++) {
            assertFalse(v.get(i).mine());
            assertEquals(BoardTask.Status.OPEN, v.get(i).status());
        }
        assertTrue(v.stream().noneMatch(x -> x.id().equals(others.id) || x.id().equals(done.id)));

        // Bram sees his own claim, not Tess's.
        List<TaskView> bv = b.views("Bram", 300);
        assertEquals(others.id, bv.get(0).id());
        assertTrue(bv.get(0).mine());
        assertTrue(bv.stream().noneMatch(x -> x.id().equals(mine.id)));
        assertTrue(new Board().views("Tess", 0).isEmpty());
    }

    @Test
    void renderFormat() {
        Board b = new Board();
        post(b, "Alex", "dirt", 1, 0);
        BoardTask t2 = postClaimed(b, "Tess", "cobblestone", 3, 1);
        b.credit("Tess", "cobblestone", 1, 2);
        BoardTask t3 = post(b, "Alex", "oak_log", 4, 3);
        b.claim(t3.id, "Bram", t3.revision, 4);
        b.release(t3.id, "Bram", 5);
        b.post("Alex", "player", "sand", 2, "for glass", 6);

        List<TaskView> views = b.views("Tess", 10);
        assertEquals(List.of(
                "T2 [yours] gather 3 cobblestone: 1/3, rev 3",
                "T1 [open] gather 1 dirt for Alex, rev 1",
                "T3 [open] gather 4 oak_log for Alex, rev 3",
                "T4 [open] gather 2 sand for Alex, rev 1 (\"for glass\")"), Board.render(views));
        assertEquals(t2.revision, views.get(0).revision());
        assertTrue(Board.render(null).isEmpty());
    }

    // ------------------------------------------------------------ persistence

    @Test
    void saveLoadRoundTripPreservesEverything() {
        Board b = new Board();
        BoardTask t1 = postClaimed(b, "Tess", "oak_log", 3, 10);
        b.credit("Tess", "oak_log", 1, 20);
        b.post("Tess", "settler", "dirt", 2, "please", 30);
        BoardTask t3 = post(b, "Alex", "sand", 1, 40);
        b.cancel(t3.id, "Alex", false, 41);
        b.reserve("1,2,3", "Tess", 50);

        Gson gson = new Gson();
        String json = gson.toJson(b.save());
        Board c = Board.load(gson.fromJson(json, Board.Saved.class));
        assertEquals(json, gson.toJson(c.save()));

        BoardTask r1 = c.task(t1.id);
        assertEquals(BoardTask.Status.CLAIMED, r1.status);
        assertEquals("Tess", r1.claimant);
        assertEquals(1, r1.progress);
        assertEquals(t1.revision, r1.revision);
        assertEquals(20, r1.lastProgressTick);
        assertEquals(t1.history, r1.history);
        assertEquals("please", c.task("T2").note);
        assertEquals("settler", c.task("T2").postedByKind);
        assertEquals(BoardTask.Status.CANCELLED, c.task("T3").status);
        assertEquals("Tess", c.holder("1,2,3", 60));
        assertEquals(b.views("Tess", 60), c.views("Tess", 60));
        assertEquals("T4", c.post("Alex", "player", "dirt", 1, null, 70).task().id);
        // The snapshot is independent of the live board.
        b.credit("Tess", "oak_log", 1, 80);
        assertEquals(1, c.task(t1.id).progress);
    }

    @Test
    void loadToleratesNullAndPartialSnapshots() {
        assertTrue(Board.load(null).tasks().isEmpty());
        Board.Saved s = new Gson().fromJson("{\"tasks\":[{\"id\":\"T7\",\"item\":\"dirt\",\"count\":1,\"status\":\"OPEN\"},null]}",
                Board.Saved.class);
        Board b = Board.load(s);
        assertEquals(1, b.tasks().size());
        assertEquals("", b.task("T7").note);
        assertEquals("T8", b.post("Alex", "player", "dirt", 1, null, 0).task().id);
    }
}
