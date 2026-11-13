# Status: 0.1.0 (2026-11-13)

Pinned: Minecraft 1.20.1, Yarn 1.20.1+build.10, Fabric Loader 0.16.14, Fabric API 0.92.12+1.20.1,
Fabric Language Kotlin 1.13.14+kotlin.2.4.20 (runtime for the SDK; chosen because it requires only loader 0.16.9), Anthropic Java SDK 3.8.0, Loom 1.11.8, Gradle
8.14.3, Java release 17.

## Verified

**Unit tests: 63/63 pass**: mind 13, board core 29, board in the mind 13, eval harness 8 (after the board review fixes, phase A: identity by id, OPEN TTL, rate limit, closed-task cap, load repair, namespaced items, note framing; docs/BOARD.md "Changes after review"). Game tests 11/11 pass on the same build (1 run).

**Mind unit tests (13)** (`./gradlew test`, no Minecraft needed)
- Relevant memories beat recent trivia; forgetting spares reflections and lessons.
- The trust boundary rejects unknown actions and blocks the settler can't see, strips links and
  formatting codes, survives non-JSON, and caps steps per decision.
- The owner's polite request gets a plan; a disagreeable settler refuses a stranger.
- A reply loop between two speakers is capped without model calls; repeated lines aren't spoken twice.
- Failures become lessons that appear in the next prompt.
- One compliment doesn't make a friendship.
- The system prompt stays byte-identical between calls (cache-friendly).
- The full mind survives a JSON save/load round trip.

**Evaluation harness: 5/5 harness tests pass, offline baseline runs in CI** (`./gradlew runEval`, docs/EVAL.md)
- 3 scenarios (requests, diffusion, grounding) × 6 conditions (full plus 5 ablations): 0 parse errors and 0 unfounded claims.
- Requests: all 8 resolved. The very disagreeable settler helps the owner and refuses the stranger; the others help both.
- The call budget is a hard stop; paid runs refuse to start without a key.
- Bugs it found and that were fixed:
  - the harness script wasn't sorted by time;
  - request results were keyed per requester, not per request;
  - **mind:** repeat suppression was not time-bounded, so a settler could never say the same thing
    twice, even an hour apart. It now uses a 1-minute window.

**In-world game tests: 11/11 pass, 4 consecutive runs** (the 9 below plus 2 board tests; the 9 had 6 earlier consecutive runs) (`./gradlew runGametest`, headless dedicated server)
- Body: mining with verified pickup, best tool, stone by hand reported as PARTIAL, bedrock refused,
  block changed mid-mine detected, eating and food choice, save/load without duplication, dismissal
  without item loss.
- **End to end with the offline mind:**
  1. Heard "Tess, could you chop that log please?"
  2. Decided (logged `<Tess> On it.`) and the plan passed validation.
  3. Mined and collected the log; the outcome was verified.
  4. Memories formed for what was heard, what it decided, and what it did. Affinity for the speaker rose.
- **Settlement board in the world** (docs/BOARD.md, own game-test batch because the board is world-wide):
  - Two settlers both hear "Name, anything on the board?" about Alex's "gather 2 oak_log". Both decide to
    claim it from the same revision; exactly one claim succeeds and the other fails `not_open`
    (logged `<Cal> Hm. That didn't work (not open).`). The claimant mines both logs. The task reaches DONE
    with progress 2/2, credited only from verified `+n minecraft:oak_log` outcomes.
  - A block reserved by one settler can't be mined by another (`FAILED(reserved)`). The reservation is
    released after the verified mine.
  - The board persists in the world save (`settlermind_minds.dat`, key `Board`): across runs of the
    reused test world, task ids continued (T2, T3, T4…) and leftover tasks were loaded.

**Build:** the mod jar nests 21 SDK runtime libraries. Kotlin comes from Fabric Language Kotlin, not bundled.

## Fixed during the rename to settler-mind

- **Drops just out of reach:** pathfinding stopped within a block of a drop, sometimes outside pickup
  range, giving `PARTIAL(broken_not_collected)` about 5 runs in 6 in one test. The settler now steers
  straight onto drops within 3 blocks. PARTIAL outcomes now say why (nothing dropped / drop vanished /
  ran out of time).
- **Loader version creep:** Fabric Language Kotlin 1.14.x pulled Fabric Loader up to 0.19.5 (with a
  different Mixin), causing flaky startup and risking Homestead compatibility. Pinned FLK 1.13.14,
  which needs only loader 0.16.9.

## Test isolation fix (2026-11-13)

The in-world tests had been reusing the saved test world between runs, so settler minds and the board
leaked from one run to the next. Earlier green runs were partly on stale state. After the review fixes
added a post rate limit, this showed up as a failure (`rate_limited` from a previous run's post).
Every `runGametest` now deletes the test world first (proven with a stale marker file). Result: 3
consecutive full builds green, 63 unit + 11 in-world tests.

## CI

GitHub Actions runs the loader-pin check, unit tests and in-world game tests on every PR and push to main.

## Not yet verified

- **Claude backend live.** `ClaudeCognition` compiles against SDK 3.8.0, but no real call has been
  made (no API key in this environment). Next: one capped live run, checking structured output, cache
  hits and the ledger figures.
- In a real client: rendering, the chat experience, how it feels.
- Homestead clone: whether ~314 mods tolerate the nested SDK libraries (Jackson, OkHttp) and FLK
  versions. Claims are fail-closed (settlers won't mine) until the Open Parties and Claims adapter exists.
- Long runs: memory growth, reflection quality, cost per settler-hour.
- Multiple settlers talking together over time (loop caps are unit-tested only).
- Board in a real client:
  - the `/settler board` and `/settler task post|cancel` commands (compiled; not run by a player yet);
  - lease expiry notices in a live world (expiry itself is unit-tested);
  - a settler posting tasks from a paid model.

## Next (see docs/MIND.md backlog)

1. Live Claude eval run (needs a key): `./gradlew runEval -Peval="--provider claude --judge claude"` → fill docs/MODELS.md.
2. More scenarios: multi-day memory drift, settler cooperation, repeated insults.
3. Danger preemption; `chat` route; local-model provider.
