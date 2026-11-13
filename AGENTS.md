# AGENTS.md: instructions for coding agents working on Settler Mind

Read this first. It is the contract for every session (Claude Code, Codex, others).

## What this project is

Settler Mind (codename) is an AI game mode: **settlers whose minds are the product**. They have
personalities, needs, memories, relationships and plans; they decide for themselves, talk, refuse,
gossip, reflect and learn from failure. Minecraft (Fabric 1.20.1) is the first host world.

**The AI is the point.** Body skills (mining, walking) exist only so minds have something real to
act with. Do not keep polishing bot primitives at the expense of the mind. Before adding a skill, ask:
"What does this let a settler *decide, express or learn* that it couldn't before?" If nothing, don't.

## Working agreement: who decides what

The agent leads day-to-day. The owner reviews and approves where it matters. Everything stays in
sight and revertible.

**The agent decides and proceeds** (recording it in `docs/DECISIONS.md` when it's a real choice):
- Implementation that follows an agreed design doc (BOARD.md, MIND.md backlog, EVAL.md), plus tests,
  docs, refactors and bug fixes.
- Research-backed mind changes, provided an eval scenario or test shows the effect, or the source is cited.
- Opening and updating **draft** PRs, CI fixes, and dependency patch updates that keep the loader pin.
- Free, offline evaluation runs.

**Ask the owner first:**
- **Spending money:** any paid model or API run (state the call cap and estimated cost), and any new
  paid service.
- **Merging to `main`**, marking a PR ready for review, releases and tags.
- **Anything touching the live Homestead server**, its saves or its modpack (never without explicit approval).
- **Product calls:** the public name, the mod id (see Naming), game-design direction, changes to what
  settlers may do in the world (new action kinds), and defaults that change player experience.
- **Privacy, security or safety changes:** consent, what data leaves the server, the trust boundary, permissions.
- **Big or hard-to-undo choices:** new heavy dependencies, licensing, architecture shifts, deleting
  data or history, renames of saved ids.

**Keep it reviewable and revertible:**
- Work on a branch with small, focused commits and a draft PR. Never rewrite pushed history on shared branches.
- Put risky behaviour behind config, feature switches (`MindFeatures`) or routes, so it can be turned off without a revert.
- For each consequential decision, add a dated entry to `docs/DECISIONS.md`: what, why (evidence or
  source), alternatives, and how to revert.
- When asking for approval, give a short recommendation, the cost or risk, and what happens if the
  answer is no. Batch questions rather than interrupting.

## Naming

"Settler Mind" / `settler-mind` is a **codename**; the public name is undecided. Changing display text
and docs is free. **Do not change the mod id `settlermind`** (or registry ids like `settlermind:settler`,
the `settlermind_minds` save file, or NBT keys) once real worlds use the mod: saves store them, and
renaming would need a migration or settlers would vanish.

## Layout

| Path | What |
|---|---|
| `src/main/java/dev/settlermind/mind/` | **The mind.** Engine-agnostic: no Minecraft imports. Persona, Needs, MemoryStream, Relationships, Lessons, Prompts, DecisionParser (trust boundary), Mind (when and what to think). |
| `.../mind/cognition/` | Model providers behind `Cognition`: `ScriptedCognition` (offline, free, deterministic), `ClaudeCognition` (official Anthropic Java SDK). `UsageLedger` measures cost. |
| `src/main/java/dev/settlermind/ai/` | Bridge to Minecraft: `MindHost` (event loop, budgets, chat), `PerceptionBuilder`, `MindStore` (minds saved in the world save), `SettlerMindConfig`. |
| `src/main/java/dev/settlermind/entity/`, `skill/`, `protection/` | The body: entity, verified skills, fail-closed claim guard. |
| `src/test/java/...` | JUnit tests for the mind (no Minecraft needed). |
| `src/main/java/dev/settlermind/gametest/` | In-world tests on a headless server. |
| `docs/MIND.md` | Mind architecture and the research it's based on. Read before changing the mind. |
| `docs/MODELS.md` | Model and cost strategy. Read before touching providers or routes. |
| `docs/EVAL.md`, `src/test/java/dev/settlermind/eval/` | Evaluation harness: simulated world, scenarios, ablations, judged interviews, cost. |
| `docs/STATUS.md` | What is verified and what isn't. Keep it honest and current. |
| `docs/DECISIONS.md` | Dated decision log: what, why, alternatives, how to revert. |

## Model choice: open, measured, never hard-coded

- Every kind of thinking is a **route** (`decide`, `reflect`, `plan`; more may come). Each route picks
  provider, model, effort and token cap in `config/settlermind.json`. **No model id is baked into
  logic.** New providers implement `Cognition`.
- We have **not** settled on models. Choose per route from measurements: `/settler usage` (calls, tokens,
  latency, cache hits, estimated $) plus believability evals (docs/MIND.md). Re-evaluate when prices,
  models or features change. Cheaper is not automatically better: measure cost per *good* decision.
- The default config is free and offline (`scripted`). Paid inference is opt-in, budget-capped
  (per settler, per server, per day), and falls back to the scripted mind when a budget or provider runs out.
- Keep the system prompt stable per settler (prompt caching); put changing state in the user message.

## Evidence before opinions

Use the eval harness (`./gradlew runEval`, docs/EVAL.md) to justify mind changes and model choices.
When you change the mind, run the relevant scenarios before and after, and record what changed.
If a behaviour matters and no scenario covers it, add one.

## Research basis (keep it current)

The design follows published work on generative agents. Before a significant mind change, check
docs/MIND.md's research table and, if it's stale, survey newer work. Prefer approaches with ablation or
evaluation evidence over intuition, and record the source in docs/MIND.md.

## Non-negotiable rules

1. **Model output is untrusted.** It only reaches the world through `DecisionParser` validation and a
   closed `ActionCatalog`. Never add commands, op, shell, file, URL or code-execution actions.
2. **Never claim success without verification.** Skills return SUCCEEDED only when the game confirms it.
3. **No model calls on the server thread.** Async on the mind executor; apply via `server.execute`.
4. **Privacy:** players' chat goes to a remote provider only after `/settler consent on`. No keys in
   config or logs; keys come from environment variables.
5. **Fail closed** on claims/protection uncertainty.
6. **Never touch the live Homestead server** or its saves. Test on clones only.
7. **Java 17 language level** (Homestead runtime): no pattern-matching `switch`, etc.
8. **Keep the Fabric Loader pin.** Check `./gradlew dependencies --configuration runtimeClasspath | grep fabric-loader`
   after changing dependencies; a library must not silently raise it above what Homestead runs.
9. Don't skip or weaken a failing test to get green; fix the cause or record it in docs/STATUS.md.

## Build and test

```bash
./gradlew test           # mind unit tests (fast)
./gradlew runGametest    # in-world tests, headless server
./gradlew build          # everything + jar in build/libs
./gradlew runEval        # evaluation report in build/eval/ (free by default; see docs/EVAL.md)
```

CI (`.github/workflows/ci.yml`) runs the Fabric Loader pin check and `./gradlew build` (unit + in-world
tests) on every PR and push to main; test reports and the jar are uploaded as artifacts.

Before finishing a session: run both test suites, update `docs/STATUS.md` with evidence, and note
any decision in `docs/MIND.md` or `docs/MODELS.md`.
