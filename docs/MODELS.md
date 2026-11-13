# Models and cost: strategy

**Status: undecided on purpose.** Settler Mind does not commit to one model. Choices are made per route,
from measurements, and revisited as the game, the prompts and the model market change.

## How it works

- **Routes.** Each kind of thinking is a route with its own provider, model, effort and token cap:

  | Route | When it runs | What matters |
  |---|---|---|
  | `decide` | Spoken to, plan finished or failed, hurt, idle | Character, judgement, valid JSON, **latency** (players are waiting) |
  | `reflect` | 2–3 times per game day when enough happened | Insight quality; latency doesn't matter |
  | `plan` | Once per game day | Coherence with persona and aspiration |

  Likely future routes: `chat` (pure conversation, no actions), `summarize` (memory compaction).

- **Config, not code.** `config/settlermind.json` → `routes`. Change a route's model and restart; no rebuild.
- **Providers.** `scripted` (offline, free) and `claude` (official Anthropic Java SDK, key from the
  `ANTHROPIC_API_KEY` env var; for local Gradle runs a git-ignored `.env` is loaded, see `.env.example`).
  Use a real API key, not a subscription CLI login: the lab's plan-login route has terms and cost-tracking caveats. A local-model provider (Ollama/LM Studio) is a planned addition behind
  the same `Cognition` interface.
- **Measurement.** `/settler usage` shows per route and model: calls, failures, input/output tokens, cache
  reads, average latency, estimated USD (prices in config; update them when they change).
- **Budgets.** Per settler per hour, per server per hour, and estimated USD per day. When a limit is
  hit, settlers fall back to the scripted mind. They keep working and never freeze.

## Current defaults

All routes use `scripted` (free) until someone opts in. When switching a route to `claude`, the config
template names `claude-opus-5-5` (the current default Claude model). Effort is set per route:
`low` for decide, `medium` for reflect and plan.

Candidates to evaluate per route (prices per million tokens, input/output, as of 2026-11; verify before relying on them):

| Model | $ in / out | Notes |
|---|---|---|
| `claude-opus-5-5` | 4 / 20 | Most capable default; adaptive thinking always on, so control depth with effort |
| `claude-sonnet-5-5` | 2 / 10 | Mid tier |
| `claude-haiku-5-5` | 0.10 / 0.50 | Very cheap; candidate for `decide` chatter and `reflect`/`summarize` if quality holds |

**Measured (2026-11-13, eval harness, `claude-opus-5-5`, effort low, 200-call cap; report
`build/eval/2026-11-13T19-27-49.547467Z/`).** Requests and diffusion (truncated) only; the cap ran out
before grounding and cooperation.

| Measure | Value |
|---|---|
| `decide` calls | 188, of which 22 failed (11.7%; cause not recorded by that build, see below) |
| Cost per `decide` call | $0.0110 (per successful call $0.0125), at the price table above |
| Tokens per `decide` call | ≈ 1.2k uncached input + 2.2k cache read (≈ 64% of input cached) + 256 output |
| Mean `decide` latency | 4.2 s (p50/p95 not recorded yet) |
| Parse errors | 14 of 149 decisions (9.4%): `follow` on a settler instead of a player, `go_to` beyond 32 blocks |
| Unfounded claims | 1 |
| Judge (character / grounded / natural, 1–5) | 4.3 / 4.2 / 4.3 over 20 interview answers; 3 were "(no answer)" |
| Run total | $2.82 estimated ($2.07 decide, $0.60 judge, $0.15 plan + reflect), 14.5 min |

Projection, not a measurement: at ~20 decide calls per settler-hour, 4 settlers on this route cost
≈ $0.90 an hour. The 200-call cap counts settler calls only; the judge's 20 calls came on top.

## How to decide (and re-decide)

1. Run the eval harness (docs/EVAL.md) for each candidate: `./gradlew runEval -Peval="--provider claude --model <id> --judge claude"`.
2. Run each route on 2–3 candidate models with the same seeds, prompts and scenarios.
3. Compare **cost per good decision**: quality score, validity rate (DecisionParser errors), p50/p95
   latency, and tokens. A cheaper model that produces more invalid plans or flat characters is not cheaper.
4. Record the result and date here. Revisit when a new model ships, prices change, or prompts change materially.

## Design rules that keep this flexible

- Never branch on a model id in game logic. Provider quirks live inside the provider class.
- Keep the system prompt stable per settler and changing state in the user message (cache-friendly on any provider).
- Keep outputs schema-constrained JSON so models are interchangeable.
- Log the provider, model, route and outcome of every call (the ledger) so comparisons are always possible.
