# Status: 0.1.0 (2026-11-13)

Pinned: Minecraft 1.20.1, Yarn 1.20.1+build.10, Fabric Loader 0.16.14, Fabric API 0.92.12+1.20.1,
Fabric Language Kotlin 1.13.14+kotlin.2.4.20 (runtime for the SDK; chosen because it requires only loader 0.16.9), Anthropic Java SDK 3.8.0, Loom 1.11.8, Gradle
8.14.3, Java release 17.

## Verified

**Mind unit tests: 13/13 pass** (`./gradlew test`, no Minecraft needed)
- Relevant memories beat recent trivia; forgetting spares reflections and lessons.
- The trust boundary rejects unknown actions and blocks the settler can't see, strips links and
  formatting codes, survives non-JSON, and caps steps per decision.
- The owner's polite request gets a plan; a disagreeable settler refuses a stranger.
- A reply loop between two speakers is capped without model calls; repeated lines aren't spoken twice.
- Failures become lessons that appear in the next prompt.
- One compliment doesn't make a friendship.
- The system prompt stays byte-identical between calls (cache-friendly).
- The full mind survives a JSON save/load round trip.

**In-world game tests: 9/9 pass, 6 consecutive runs** (`./gradlew runGametest`, headless dedicated server)
- Body: mining with verified pickup, best tool, stone by hand reported as PARTIAL, bedrock refused,
  block changed mid-mine detected, eating and food choice, save/load without duplication, dismissal
  without item loss.
- **End to end with the offline mind:**
  1. Heard "Tess, could you chop that log please?"
  2. Decided (logged `<Tess> On it.`) and the plan passed validation.
  3. Mined and collected the log; the outcome was verified.
  4. Memories formed for what was heard, what it decided, and what it did. Affinity for the speaker rose.

**Build:** the mod jar nests 21 SDK runtime libraries. Kotlin comes from Fabric Language Kotlin, not bundled.

## Fixed during the rename to settler-mind

- **Drops just out of reach:** pathfinding stopped within a block of a drop, sometimes outside pickup
  range, giving `PARTIAL(broken_not_collected)` about 5 runs in 6 in one test. The settler now steers
  straight onto drops within 3 blocks. PARTIAL outcomes now say why (nothing dropped / drop vanished /
  ran out of time).
- **Loader version creep:** Fabric Language Kotlin 1.14.x pulled Fabric Loader up to 0.19.5 (with a
  different Mixin), causing flaky startup and risking Homestead compatibility. Pinned FLK 1.13.14,
  which needs only loader 0.16.9.

## Not yet verified

- **Claude backend live.** `ClaudeCognition` compiles against SDK 3.8.0, but no real call has been
  made (no API key in this environment). Next: one capped live run, checking structured output, cache
  hits and the ledger figures.
- In a real client: rendering, the chat experience, how it feels.
- Homestead clone: whether ~314 mods tolerate the nested SDK libraries (Jackson, OkHttp) and FLK
  versions. Claims are fail-closed (settlers won't mine) until the Open Parties and Claims adapter exists.
- Long runs: memory growth, reflection quality, cost per settler-hour.
- Multiple settlers talking together over time (loop caps are unit-tested only).

## Next (see docs/MIND.md backlog)

1. Live Claude run (needs a key) → fill docs/MODELS.md with measured numbers.
2. Evaluation harness (interviews, ablations, diffusion, persona fidelity).
3. Coordination board; danger preemption; `chat` route; local-model provider.
