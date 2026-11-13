# Settlement coordination board: design contract

Why: MINDcraft/MineCollab (arXiv 2504.17950) found natural-language communication is the main
bottleneck for multi-agent collaboration in Minecraft. Settlers keep talking for flavour, but they
coordinate through a structured board: claims are atomic, completion is verified by the game, and
claims expire if abandoned. This is the "structured coordination channel" item in docs/MIND.md.

Agency is preserved: nobody is assigned work. Settlers *choose* to post, claim or ignore tasks,
shaped by their personality, needs and relationships.

## Scope of v1

- One board per world (the settlement). Tasks are **GATHER** only: "gather N of item X", because gathering
  is the one outcome the body can already verify (mining's `+n item` evidence). Deliver/build/farm kinds
  come later with the skills that can verify them.
- Players post with commands; settlers post, claim and release through their minds.

## Core model (engine-agnostic: `dev.settlermind.mind.board`, no Minecraft imports)

`BoardTask` (plain mutable fields, Gson-friendly):

| Field | Meaning |
|---|---|
| `id` | `"T1"`, `"T2"`, … (monotonic per board) |
| `item` | item id without namespace, e.g. `oak_log` (lowercase `[a-z0-9_]+`) |
| `count` | 1..64 required |
| `progress` | verified amount credited so far |
| `note` | optional short text from the poster (≤ 80 chars, sanitised by caller) |
| `postedBy`, `postedByKind` | name and `"player"` / `"settler"` |
| `status` | `OPEN`, `CLAIMED`, `DONE`, `CANCELLED` |
| `claimant` | settler name while CLAIMED (and kept on DONE) |
| `revision` | incremented on every state change (fencing for stale decisions) |
| `createdTick`, `claimedTick`, `lastProgressTick`, `doneTick` | game ticks |

`Board` operations: all single-threaded (the host calls them on the game thread), deterministic, and
every rejection returns a code, never an exception:

| Operation | Rules | Result codes |
|---|---|---|
| `post(by, byKind, item, count, note, tick)` | count 1..64; item matches `[a-z0-9_]+`; ≤ 20 OPEN+CLAIMED tasks on the board; ≤ 5 open tasks posted by the same poster | `ok` (with task), `invalid_item`, `invalid_count`, `board_full`, `too_many_posts` |
| `claim(id, settler, expectedRevision, tick)` | task must be OPEN; `expectedRevision` must equal current revision (compare-and-set); a settler holds ≤ 2 claims | `ok`, `not_found`, `not_open`, `stale`, `too_many_claims` |
| `release(id, settler, tick)` | only the claimant; back to OPEN, claimant cleared | `ok`, `not_found`, `not_yours` |
| `cancel(id, by, isAdmin, tick)` | poster or admin; OPEN or CLAIMED → CANCELLED | `ok`, `not_found`, `not_allowed`, `already_closed` |
| `credit(settler, item, amount, tick)` | adds verified gathered amount to that settler's CLAIMED tasks for `item`, oldest first, never past `count`; reaching `count` → DONE | returns the list of tasks that changed (for events) |
| `expire(tick)` | CLAIMED with `tick - lastProgressTick > LEASE_TICKS` (6000 = 5 min) → OPEN, claimant cleared | returns `(task, formerClaimant)` pairs |
| `prune(tick)` | removes DONE/CANCELLED older than 24000 ticks | |

Every successful mutation increments `revision` and appends a short line to a bounded per-task history (≤ 8).

**Block reservations** (prevent two settlers working the same block):
`reserve(posKey, settler, tick)` → true if free, expired, or already held by `settler`; TTL 1200 ticks.
`holder(posKey, tick)` → settler or null. `releaseAll(settler)`. `posKey` is `"x,y,z"`, with the dimension
handled by the host (v1: one board per world, keys within that world).

Views:
- `views(forSettler, tick)` → list of `TaskView(id, item, count, progress, status, claimant, postedBy, note,
  revision, mine)`, at most 8: the settler's own claims first, then OPEN tasks oldest first.
- `render(views)` → prompt lines, e.g. `T3 [open] gather 4 oak_log for Alex, rev 2` or
  `T2 [yours] gather 3 cobblestone: 1/3, rev 5`.

Persistence: `Board.Saved` (Gson DTO) with `save()` / `load()`, like `Mind.Saved`.

## Mind integration

- `Decision.Step` gains `item` (String), `count` (Integer) and `task` (String), all nullable. The decide
  schema adds them to the step object (required-and-nullable, like the other fields).
- `ActionCatalog` gains **mental/social** steps, executed instantly by the host (not by the body):
  - `post_task` (item, count): ask for help on the board.
  - `claim_task` (task): take an open task; the host passes the revision the settler saw.
  - `release_task` (task): give up a task you hold.
- `Perception` gains `List<TaskView> board`. `SeenBlock` gains nullable `reservedBy`.
- `DecisionParser` validation:
  - `claim_task`: the task is visible in perception and OPEN.
  - `release_task`: the task is visible and is yours.
  - `post_task`: item matches `[a-z0-9_]+`, count 1..64.
  - `mine`: rejected if `reservedBy` is someone else ("someone else is working on that block").
- `MindFeatures` gains `board` (`without("board")`): when off, the board section is omitted from
  prompts, the board steps are not offered, and validation rejects them, so ablations can compare
  board vs chat-only coordination.
- `MindEvent.Board(text, wakes)`: e.g. "Your task T2 (gather 3 cobblestone) is done" (wakes: false),
  "Your claim on T3 lapsed after no progress" (wakes: true).
- `Prompts`:
  - The system prompt gets a short, stable paragraph on how the board works: tasks are optional,
    claim only what you intend to do, release what you won't finish.
  - The decide prompt gets a `## Settlement task board` section when the board is non-empty.
- `ScriptedCognition`:
  - Addressed speech mentioning "board", "task" or "job" → if willing, claim the oldest open task whose
    item is visible, then mine one such visible, unreserved block.
  - Idle with `purpose` most pressing → the same.
  - When the settler holds a claim and its item is visible → keep mining.

## Host integration (Minecraft: `MindHost`; simulation: eval `Simulation`)

- The board lives in the world save (`MindStore`, key `Board`).
- When a settler's plan reaches a board step, the host executes it immediately and reports an
  `ActionFinished` with the result code. `claim_task` uses the revision from the settler's perception at
  decision time (the fencing token).
- `mine`: before breaking, the host reserves the block for the settler; a block held by someone else
  fails with `reserved`. On a verified `SUCCEEDED` mine whose detail is `+n <namespace:item>`, the host
  calls `board.credit(settler, item, n, tick)` and sends `MindEvent.Board` for each task that changed.
- Every ~10 s: `board.expire(now)` (notify former claimants) and `board.prune(now)`.
- Commands (permission: anyone may post and view; cancel by poster or op):
  `/settler board`, `/settler task post <item> <count> [note…]`, `/settler task cancel <id>`.

## Evaluation (`docs/EVAL.md`)

Scenario `cooperation`:
- 3 settlers, 3 oak logs and 2 birch logs nearby.
- Alex's board tasks are seeded at tick 0: gather 3 `oak_log` and gather 2 `birch_log`.
- At tick 200 Alex tells everyone: "Everyone, I need 3 oak logs and 2 birch logs; it's on the board."

Measures:
- tasks DONE and ticks to completion;
- "needs met" = Σ min(gathered, required) / Σ required, which works with or without the board;
- double claims (must be 0);
- reservation conflicts and `target_missing` failures (wasted work);
- model calls per completed task.

Compare `full` against `no_board` (same script; the board is hidden from minds).

## Invariants (tests must cover)

1. A task never has two claimants, and stale-revision claims are rejected.
2. Progress only comes from verified gathering, never from what a model says; progress never exceeds `count`.
3. Abandoned claims expire; release and cancel follow the ownership rules.
4. Board operations never throw on bad input; they return codes.
5. A save/load round trip preserves everything.
6. No board step can cause an action outside the closed catalog (the board adds no commands or world edits).

## Clarifications made during implementation (phase 1)

- **Poster limit:** "≤ 5 open tasks per poster" counts OPEN and CLAIMED tasks.
- **Input checks:** a blank name gives `invalid_name`; item ids are capped at 64 chars; notes are trimmed to 80.
- **Release:** anything other than a CLAIMED task held by the caller gives `not_yours`.
- **Cancel:** checks `not_allowed` before `already_closed`. It clears the claimant, so the host reads it first if it wants to notify them.
- **`closedTick`:** set on DONE or CANCELLED; `prune` uses it.
- **Revisions:** new tasks start at revision 1. "Oldest first" ties break by id number.
- **Timing:**
  - Leases lapse when `tick - lastProgressTick > LEASE_TICKS`.
  - Reservations expire once their age reaches `RESERVATION_TTL`.
  - Re-reserving by the same holder refreshes the TTL.
- **Render:** an OPEN task with partial progress shows `: p/c`; a note is shown as ` ("note")`.
