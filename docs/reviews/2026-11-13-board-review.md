# Adversarial review: coordination board (2026-11-13)

Scope: `b477fa3~1..aeecde5`. An independent reviewer, not involved in writing the code, verified against
the code rather than the docs. Board-level CONFIRMED items were reproduced with a throwaway test. Fix
status is tracked at the end.

## HIGH

1. **Identity is the display name, which another owner can abuse.** Summon only checks the summoner's
   own settlers (SettlerCommands), and the board keys claims, credit, reservations and posts by name.
   - Bob's "Dot" shares Alice's "Dot"'s reservations and claim credit.
   - Dismissing Bob's Dot releases Alice's claims.
   - A settler named after a player shares that player's post quota, and the player can cancel its
     posts (cancel ignores `postedByKind`).

   CONFIRMED. *Fix:* key by stable id (settler UUID / player UUID) plus kind; use the name for display only.
2. **Privacy and prompt injection through task notes.**
   - Any player can post free-text notes without `/settler consent on`. The notes go into every
     settler's decide prompt, other owners' settlers included, and so to the remote provider (breaks AGENTS rule 4).
   - Only control characters and § are stripped, so `"` can break out of the `("note")` quoting.

   PLAUSIBLE. *Fix:* include a note only if the poster consented or no remote provider is configured;
   escape quotes; frame notes as untrusted words.
3. **Liveness.** CONFIRMED.
   - OPEN tasks never expire, settlers have no cancel step, and a dismissed settler's posts stay forever,
     so the board can fill permanently (`board_full`).
   - Views show 8 tasks, oldest first, so impossible early tasks hide every newer one.

   *Fix:* a TTL for OPEN tasks; a settler cancel step; owners can cancel their settlers' posts; cancel
   posts on dismiss; rank views by feasibility.

## MEDIUM

4. **Credit counts any inventory gain during BREAK→COLLECT**, including items the settler walks over, so
   the same logs can be credited twice. PLAUSIBLE. *Fix:* credit only drops spawned by this break,
   capped at what dropped.
5. **Spam and unbounded growth.** 5,000 post-then-cancel cycles leave 5,000 tasks (>500 KB in the save).
   Any player can loop it, and every post is announced to everyone. CONFIRMED. *Fix:* per-poster rate
   limit, a cap on closed tasks, less broadcasting.
6. **The eval simulation diverges from the host:**
   - Mining is instant, so reservations are never observed by others.
   - Thinking is synchronous, so stale claims caused by model latency are never exercised.
   - The `doubleClaims==0` check is a tautology.
   - Sim `post_task` skips the item check.

   *Fix:* multi-step mining, decision latency, double claims derived independently from the claim
   history, and an item check.

## LOW

7. `Board.load` accepts tasks that break the invariants (progress > count, count 0, bad item, CLAIMED with no claimant). CONFIRMED.
8. Names are compared case-insensitively in the parser and scripted mind but exactly in Board.
9. Reservations leak or lapse:
   - Leaks: not released on death, unload or dimension change; the key has no dimension; stale entries
     persist in the save.
   - Lapse: the TTL is not refreshed during a slow BREAK.
10. The `no_board` ablation still shows and enforces `reservedBy`.
11. Credit matches the item path only (`x:oak_log` counts toward `oak_log`). A cancel after the break earns no credit (fails safe).

Checked and found sound: all board mutations run on the server thread; revision fencing is wired end to
end in MindHost; the parser canonicalises task ids; items are regex- and registry-checked; board
operations never throw; credit never exceeds count; corrupt board JSON is handled; no new commands or
world edits.

## Fix status

(updated as fixes land)

Phase A (board core and mind; see docs/BOARD.md "Changes after review"):
- [x] 1. Identity: stable id + kind everywhere on the board; names display only.
- [x] 2. Notes, board side: sanitised, framed as the poster's words, `noteVisible` gate, system prompt rule.
  Host side (consent/provider gating) still open.
- [x] 3. Liveness: OPEN TTL (3 days), `cancel_task`, `cancelAllPostedBy` on dismissal, feasibility-ranked views.
- [ ] 4. Credit only from this break's drops (host).
- [x] 5. Rate limit per poster, closed-task cap (50), bounded history. Less broadcasting: host, open.
- [ ] 6. Eval simulation fidelity (sim).
- [x] 7. `Board.load` re-validates, repairs or drops, and bounds sizes.
- [x] 8. No case-insensitive name comparisons for authority (ids compared exactly).
- [ ] 9. Reservation leaks and lapses (host).
- [x] 10. `no_board` ablation hides and doesn't enforce `reservedBy`.
- [x] 11. Board side: full namespaced item ids, exact normalised credit. (Cancel-after-break credit: unchanged, fails safe.)
