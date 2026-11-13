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

**Rough sizing (assumptions, not measurements).** One `decide` call ≈ 2–3k input tokens (system prompt
cached) + ~300 output. With ~20 calls per settler-hour, 4 settlers ≈ 80 calls/h. Real numbers come
from `/settler usage`; replace this paragraph with measured figures.

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
