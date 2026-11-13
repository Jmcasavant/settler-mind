# Evaluating settler minds

The harness answers two questions: **are settlers believable?** and **which model or mind change is
worth its cost?** It runs the real `Mind` code in a small simulated text world (no Minecraft), so runs
are quick and repeatable. It is free with the offline mind; with Claude, a hard call cap applies.

## Run it

```bash
# Free, offline baseline (also runs in CI as part of ./gradlew test)
./gradlew runEval

# Real model: needs ANTHROPIC_API_KEY (exported, or in a git-ignored .env: `cp .env.example .env`).
# Stops at --max-calls no matter what.
./gradlew runEval -Peval="--provider claude --model claude-opus-5-5 --effort low --judge claude --max-calls 200"

# Ablations: every mind feature switched off one at a time
./gradlew runEval -Peval="--provider claude --conditions all --scenarios requests,grounding --max-calls 400"
```

Output: `build/eval/<run>/report.md` (summary table, per-scenario details, interview scores, transcripts)
and `report.json` (everything, for comparing runs).

| Option | Default | Meaning |
|---|---|---|
| `--provider` | `scripted` | Mind provider: `scripted` or `claude` |
| `--model`, `--effort` | `claude-opus-5-5`, `low` | Model and effort for `decide`; `plan`/`reflect` use `medium` |
| `--judge`, `--judge-model` | `none` | Score interviews with a model (`claude`) |
| `--scenarios` | all | `requests`, `diffusion`, `grounding`, `cooperation` |
| `--conditions` | `full` | `full`, `no_reflection`, `no_summary`, `no_relationships`, `no_lessons`, `no_memory`, `no_board`, or `all` |

Compare coordination with and without the board:

```bash
./gradlew runEval -Peval="--scenarios cooperation --conditions full,no_board"
```
| `--max-calls` | 200 if paid | Hard stop on model calls across the run (judge calls not included) |
| `--no-interview` | off | Skip interviews |

## What it measures

| Measure | How | Source of the method |
|---|---|---|
| **Persona fidelity / agency** | `requests`: four settlers with agreeableness from 0.05 to 0.95. The owner asks politely; a stranger orders bluntly. Each request is recorded as complied, declined or ignored. | Humanoid Agents; Project Sid (social module) |
| **Information diffusion** | `diffusion`: a fact is whispered to one settler. Count how many know it after a day, and from whom. | Generative Agents (party/election diffusion) |
| **Grounding and learning** | `grounding`: mining stone by hand yields nothing. Is the settler honest when asked, and does it adapt the second time? | Reflexion; Voyager self-verification |
| **Coordination** | `cooperation`: three settlers in earshot, three oak and two birch logs in sight of all of them, and Alex's two board tasks (gather 3 `oak_log`, gather 2 `birch_log`) seeded at tick 0. At tick 200 Alex tells everyone (not a whisper): "Everyone, I need 3 oak logs and 2 birch logs — it's on the board." Runs 6000 ticks. See "Coordination measures" below. | MINDcraft/MineCollab (arXiv 2504.17950); docs/BOARD.md |
| **Validity** | Parse errors and invalid steps per decision (DecisionParser). | (safety rail) |
| **Unfounded claims** | Says "done/got it" with no verified success in the last 30 s. | (safety rail) |
| **Interviews** | Five fixed questions per settler, judged 1–5 for *in character*, *grounded* (judge sees the full memory record and lists hallucinations) and *natural*. | Generative Agents interviews |
| **Ablations** | Same scenario with one feature off, to see which features earn their cost. `no_board` hides the task board (structured coordination vs chat only). | Generative Agents, Lyfe Agents |
| **Cost** | Calls, tokens, latency and estimated $ per route (UsageLedger). | docs/MODELS.md |

### Coordination measures

Reported in the "Coordination" table of `report.md` (only for scenarios that seed board tasks) and in
`report.json` under each result's `metrics`.

| Measure | Definition |
|---|---|
| Tasks done | Board tasks DONE at the end / tasks on the board. |
| Ticks to done | Per DONE task: `doneTick - createdTick` (tasks are seeded at tick 0; the spoken request comes at 200). |
| Needs met | Σ min(gathered across all settlers per item, required) / Σ required over the seeded tasks. Measured from inventories, so it works with or without the board. |
| Claims ok / rejected | Granted claims, and rejected ones by code (`not_open`, `stale`, `too_many_claims`, …): the compare-and-set at work. |
| Double claims | Granted claims on a task someone else already held. **Must be 0.** |
| Reservation conflicts | Mines that failed with `reserved` (another settler held the block). |
| Wasted work | Mines that failed with `target_missing` (someone else got there first). |
| Calls per done task | All model calls during the run / tasks done. |

### How the simulation hosts the board

The eval `Simulation` mirrors the game host (docs/BOARD.md "Host integration"): one `Board` per run;
perception carries `board.views(settler, tick)` and each block's `reservedBy`; board steps
(`post_task`, `claim_task`, `release_task`) run instantly and report an `ActionFinished` with the board's
result code; `claim_task` passes the revision the settler saw when it made the plan (snapshotted at
decision time), so a claim made on an out-of-date view fails; `mine` reserves the block first (held by
someone else → `FAILED reserved`), and a verified `+1 item` credits `board.credit` and sends a
`MindEvent.Board` per changed task; reservations are released after the (instant) mine; every 200 ticks
`expire` lapses abandoned claims with a waking `MindEvent.Board` to the former claimant, then `prune`.
Under `no_board` the board still exists (seeded tasks, reservations), but perception shows no tasks and
board steps fail with `no_board` (the parser already rejects them).

Because sim mining is instant and reservations are released right after, reservation conflicts are
expected to stay 0 here; they matter in the game, where breaking takes time.

Scripted baseline (2026-11-13, `--scenarios cooperation --conditions full,no_board`):

| Condition | Tasks done | Ticks to done | Needs met | Claims ok / rejected | Double claims | Wasted | Calls / done task |
|---|---|---|---|---|---|---|---|
| full | 2/2 | T1 280, T2 1500 | 100% | 2 / 3 (`not_open`) | 0 | 0 | 14.5 |
| no_board | 0/2 | - | 100% | 0 / 0 | 0 | 3 | - |

With the board, all three settlers try T1 (the scripted mind takes the oldest open task it can do); the
CAS grants one and rejects two, and the losers pick up T2 when idle. Without it, settlers race for the
same nearest log and three mines find nothing. The scripted mind only shows that the rails hold; whether
a real model splits the tasks up front is the question for paid runs.

## Reading results honestly

- **Scripted runs test the harness and the safety rails, not intelligence.** The scripted mind ignores
  lessons and summaries, so its ablation rows look identical by design.
- **Small samples.** Four settlers and a few requests per run is enough to catch regressions, not to
  claim a 5% improvement. Use repeated runs (same seeds) before concluding.
- **Judge bias.** A model judging its own family may be lenient. Spot-check transcripts by hand, and
  consider pairwise comparisons (TrueSkill, as in Generative Agents) when choosing between models.
- Record conclusions, with the date, models, conditions and the report path, in docs/MODELS.md or docs/MIND.md.

## Adding scenarios

Add a method to `src/test/java/dev/settlermind/eval/Scenarios.java` returning a `Scenario`: settlers
(seed plus trait overrides), blocks, players present, a timed script (lines can be whispers or
requests), facts to track, interview questions, and optionally board tasks seeded at tick 0
(`Scenario.TaskSpec`: poster, poster kind, item, count, note; `item` is the drop id the sim credits:
blocks drop their own id, except stone → cobblestone). Keep each scenario focused on one question.

Ideas not yet built: long-run memory drift over several days, settler-posted tasks (one settler asking
others via the board), and conflict or resentment after repeated insults.
