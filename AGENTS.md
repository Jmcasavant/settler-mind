# AGENTS.md: instructions for coding agents working on Hearthkin

Read this first. It is the contract for every session (Claude Code, Codex, others).

## What this project is

Hearthkin (working title) is an AI game mode: **settlers whose minds are the product**. They have
personalities, needs, memories, relationships and plans; they decide for themselves, talk, refuse,
gossip, reflect and learn from failure. Minecraft (Fabric 1.20.1) is the first host world.

**The AI is the point.** Body skills (mining, walking) exist only so minds have something real to
act with. Do not keep polishing bot primitives at the expense of the mind. Before adding a skill, ask:
"What does this let a settler *decide, express or learn* that it couldn't before?" If nothing, don't.

## Layout

| Path | What |
|---|---|
| `src/main/java/dev/hearthkin/mind/` | **The mind.** Engine-agnostic: no Minecraft imports. Persona, Needs, MemoryStream, Relationships, Lessons, Prompts, DecisionParser (trust boundary), Mind (when and what to think). |
| `.../mind/cognition/` | Model providers behind `Cognition`: `ScriptedCognition` (offline, free, deterministic), `ClaudeCognition` (official Anthropic Java SDK). `UsageLedger` measures cost. |
| `src/main/java/dev/hearthkin/ai/` | Bridge to Minecraft: `MindHost` (event loop, budgets, chat), `PerceptionBuilder`, `MindStore` (minds saved in the world save), `HearthkinConfig`. |
| `src/main/java/dev/hearthkin/entity/`, `skill/`, `protection/` | The body: entity, verified skills, fail-closed claim guard. |
| `src/test/java/...` | JUnit tests for the mind (no Minecraft needed). |
| `src/main/java/dev/hearthkin/gametest/` | In-world tests on a headless server. |
| `docs/MIND.md` | Mind architecture and the research it's based on. Read before changing the mind. |
| `docs/MODELS.md` | Model and cost strategy. Read before touching providers or routes. |
| `docs/STATUS.md` | What is verified and what isn't. Keep it honest and current. |

## Model choice: open, measured, never hard-coded

- Every kind of thinking is a **route** (`decide`, `reflect`, `plan`; more may come). Each route picks
  provider, model, effort and token cap in `config/hearthkin.json`. **No model id is baked into
  logic.** New providers implement `Cognition`.
- We have **not** settled on models. Choose per route from measurements: `/hk usage` (calls, tokens,
  latency, cache hits, estimated $) plus believability evals (docs/MIND.md). Re-evaluate when prices,
  models or features change. Cheaper is not automatically better: measure cost per *good* decision.
- The default config is free and offline (`scripted`). Paid inference is opt-in, budget-capped
  (per settler, per server, per day), and falls back to the scripted mind when a budget or provider runs out.
- Keep the system prompt stable per settler (prompt caching); put changing state in the user message.

## Research basis (keep it current)

The design follows published work on generative agents. Before a significant mind change, check
docs/MIND.md's research table and, if it's stale, survey newer work. Prefer approaches with ablation or
evaluation evidence over intuition, and record the source in docs/MIND.md.

## Non-negotiable rules

1. **Model output is untrusted.** It only reaches the world through `DecisionParser` validation and a
   closed `ActionCatalog`. Never add commands, op, shell, file, URL or code-execution actions.
2. **Never claim success without verification.** Skills return SUCCEEDED only when the game confirms it.
3. **No model calls on the server thread.** Async on the mind executor; apply via `server.execute`.
4. **Privacy:** players' chat goes to a remote provider only after `/hk consent on`. No keys in
   config or logs; keys come from environment variables.
5. **Fail closed** on claims/protection uncertainty.
6. **Never touch the live Homestead server** or its saves. Test on clones only.
7. **Java 17 language level** (Homestead runtime): no pattern-matching `switch`, etc.
8. Don't skip or weaken a failing test to get green; fix the cause or record it in docs/STATUS.md.

## Build and test

```bash
./gradlew test           # mind unit tests (fast)
./gradlew runGametest    # in-world tests, headless server
./gradlew build          # everything + jar in build/libs
```

Before finishing a session: run both test suites, update `docs/STATUS.md` with evidence, and note
any decision in `docs/MIND.md` or `docs/MODELS.md`.
