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
| `--latency-steps` | 2 | Simulated seconds between a decision's perception snapshot and its result being applied (0 = same step) |
| `--break-steps` | 2 | Simulated seconds a block takes to break (a mine is reserve + break + collect) |

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
| Outdated view (fencing violations) | Claims carried out when the task's revision differed from the one in the perception the decision was made from. Every one must be rejected; granted ones are fencing violations. **Violations must be 0.** |
| Double claims | Derived from the recorded claim history (`ClaimHistory`: every granted claim, release, lapse, done and cancel, in order), not from board state: grants made while another settler's claim on the same task was still open. **Must be 0.** |
| Double credits | From the same history: extra verified gains from a block that had already yielded (two settlers credited for one block). **Must be 0.** |
| Reservation conflicts | Mines that failed with `reserved` (another settler held the block). |
| Wasted work | Mines that failed with `target_missing` (gone before starting) or `target_changed` (gone while breaking). |
| Mines cancelled | Mines dropped part-way because a new decision replaced the plan (the host cancels the running skill). |
| Calls per done task | All model calls during the run / tasks done. |

### How the simulation hosts the board

The eval `Simulation` mirrors the game host (docs/BOARD.md "Host integration"): one `Board` per run;
perception carries `board.views(settler, tick)` and each block's `reservedBy`; board steps
(`post_task`, `claim_task`, `release_task`, `cancel_task`) run instantly and report an `ActionFinished`
with the board's result code; a settler's `post_task` must name a valid namespaced id of an item the
simulated world knows (its blocks plus a short list of vanilla items), else `FAILED invalid_item`, like
the host's registry check; every 200 ticks `expire` lapses abandoned claims with a waking
`MindEvent.Board` to the former claimant, then `prune`. Under `no_board` the board still exists (seeded
tasks, reservations), but perception shows no tasks and board steps fail with `no_board` (the parser
already rejects them).

Timing follows the host (review finding 6):

- **Decision latency.** A model call is made from a perception snapshot, and its result is applied
  `--latency-steps` later (default 2 s), as the host applies a result on a later server tick. The plan's
  fencing tokens (`claim_task` revisions) come from that snapshot, so a claim made on a view that moved
  meanwhile is rejected (`stale` or `not_open`). No idle event fires while a thought is pending or in
  flight, as in the host.
- **Multi-step mining.** A `mine` checks the block (`target_missing`) and reserves it (`reserved` if
  someone else holds it), breaks it over `--break-steps` steps (`target_changed` if it changes
  meanwhile), and collects the drop on the next step: a verified `+1 item` credits `board.credit`.
  The reservation is held throughout, so others see `reservedBy`, and released when the mine ends. A new
  plan cancels a mine in progress, releasing the block (after the break, the drop is lost), like the host's
  `setPlan`.
- **Independent checks.** Double claims and double credits come from `ClaimHistory`, a log the sim writes
  from the results of the operations it performs, so a host that granted a second claimant would show up
  even though the board can hold only one.

`SimulationFidelityTest` covers each of these; scripted runs stay deterministic.

Scripted baseline (2026-11-13, `--scenarios cooperation --conditions full,no_board`, latency 2, break 2):

| Condition | Tasks done | Ticks to done | Needs met | Claims ok / rejected | Outdated view (violations) | Double claims / credits | Reservation conflicts | Wasted | Calls / done task |
|---|---|---|---|---|---|---|---|---|---|
| full | 2/2 | T1 780, T2 1740 | 100% | 2 / 3 (`not_open`) | 3 (0) | 0 / 0 | 0 | 0 | 14.5 |
| no_board | 0/2 | - | 100% | 0 / 0 | 0 (0) | 0 / 0 | 2 | 1 | - |

Before this fidelity work (instant mining, synchronous thinking) the same run gave T1 480, T2 1500, and
for `no_board` 0 reservation conflicts and 3 wasted. Tasks now take longer because mining takes 4 steps
and decisions land 2 s late (with `--latency-steps 0`: T1 660, T2 1620). Without the board, racing
settlers now mostly hit each other's reservations instead of finding the block gone. Elsewhere, call
counts rose slightly (e.g. `diffusion` 121 → 143 without interviews) because of the host-style idle gating.

With the board, all three settlers try T1 (the scripted mind takes the oldest open task it can do); the
CAS grants one and rejects two, whose views were outdated by then, and the losers pick up T2 when idle.
The scripted mind only shows that the rails hold; whether a real model splits the tasks up front is the
question for paid runs.

## First Claude run (2026-11-13)

`--provider claude --model claude-opus-5-5 --effort low --judge claude --max-calls 200`, report
`build/eval/2026-11-13T19-27-49.547467Z/` (not committed). Cost and latency are in docs/MODELS.md.

| Scenario | Measure | Scripted baseline | Claude |
|---|---|---|---|
| requests | Calls | 64 | 173 |
| requests | Complied / declined / ignored | 7 / 1 / 0 | 8 / 0 / 0 |
| requests | Parse errors | 0 | 14 (11%) |
| requests | Unfounded claims | 0 | 1 |
| requests | Judge char / grounded / natural | - | 4.3 / 4.2 / 4.3 |
| requests | Claims on outdated views (fencing violations) | - | 2 (0); double claims 0 |
| diffusion | Fact spread | 1/4 | 1/4 (truncated after 27 calls) |
| grounding, cooperation | | ran | not reached (cap) |

What it shows:
- **Personality flattens toward yes.** The scripted Gruff (agreeableness 0.05) refuses the blunt
  stranger. Claude's Gruff grumbles, then complies. Every settler complied with every request.
- **Speech is good, actions are chatty.** The judged answers are in character and mostly grounded.
  The flags are small embellishments ("took me in when I had nothing"). But settlers spent many calls
  on `wait` plans and remarks to silent players, and the board task (gather 8 oak_log) was claimed and
  then not progressed. No trees were in sight, and one claim lapsed.
- **Parse errors are fixable prompt or catalog issues:** `follow` aimed at another settler, and `go_to`
  beyond 32 blocks.
- **22 failed calls (11.7%) have no recorded cause.** Failed decide calls are now logged in the
  transcript (`call failed: ...`), so the next run will say whether they're `max_tokens` cut-offs or API errors.
- **Budget split:** one scenario took 173 of 200 calls. A follow-up should run scenarios separately
  (`--scenarios cooperation`, then `grounding`) with their own caps.

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
