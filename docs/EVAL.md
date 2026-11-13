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
| `--scenarios` | all | `requests`, `diffusion`, `grounding` |
| `--conditions` | `full` | `full`, `no_reflection`, `no_summary`, `no_relationships`, `no_lessons`, `no_memory`, or `all` |
| `--max-calls` | 200 if paid | Hard stop on model calls across the run (judge calls not included) |
| `--no-interview` | off | Skip interviews |

## What it measures

| Measure | How | Source of the method |
|---|---|---|
| **Persona fidelity / agency** | `requests`: four settlers with agreeableness from 0.05 to 0.95. The owner asks politely; a stranger orders bluntly. Each request is recorded as complied, declined or ignored. | Humanoid Agents; Project Sid (social module) |
| **Information diffusion** | `diffusion`: a fact is whispered to one settler. Count how many know it after a day, and from whom. | Generative Agents (party/election diffusion) |
| **Grounding and learning** | `grounding`: mining stone by hand yields nothing. Is the settler honest when asked, and does it adapt the second time? | Reflexion; Voyager self-verification |
| **Validity** | Parse errors and invalid steps per decision (DecisionParser). | (safety rail) |
| **Unfounded claims** | Says "done/got it" with no verified success in the last 30 s. | (safety rail) |
| **Interviews** | Five fixed questions per settler, judged 1–5 for *in character*, *grounded* (judge sees the full memory record and lists hallucinations) and *natural*. | Generative Agents interviews |
| **Ablations** | Same scenario with one feature off, to see which features earn their cost. | Generative Agents, Lyfe Agents |
| **Cost** | Calls, tokens, latency and estimated $ per route (UsageLedger). | docs/MODELS.md |

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
requests), facts to track, and interview questions. Keep each scenario focused on one question.

Ideas not yet built: settler-to-settler cooperation (needs the coordination board), long-run memory
drift over several days, and conflict or resentment after repeated insults.
