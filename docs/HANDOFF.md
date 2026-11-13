# Session handoff (local)

Updated 2026-11-13 ~20:00 UTC by a local Claude Code session on Jaren's Windows PC. Read **AGENTS.md
first** (rules plus the working agreement on what needs owner approval), then this file.

## Where things stand

- **Repo:** `Jmcasavant/settler-mind`, private. Work is on `feat/settler-minds`; draft PR
  [Jmcasavant/settler-mind#1](https://github.com/Jmcasavant/settler-mind/pull/1) targets `main`, which
  holds only the first scaffold commit. Auto-fix is on for the PR in the local session.
- **Board review:** every finding in docs/reviews/2026-11-13-board-review.md is fixed. Phase B (host)
  and phase C (eval simulation) were redone locally, because the cloud session's versions never arrived.
- **Tests:** 73 unit and 13 in-world tests pass locally. In-world: 12 consecutive passing runs. Two
  earlier runs each had one failure while another Gradle build ran at the same time, and the failing
  test wasn't captured (docs/STATUS.md).
- **First paid eval done:** $2.82 of the approved run. The 200-call cap ran out after `requests` and
  part of `diffusion`, so `grounding` and `cooperation` didn't run. Results are in docs/MODELS.md
  ("Measured") and docs/EVAL.md ("First Claude run"). Main findings:
  - Settlers comply with everything, so agreeableness isn't respected.
  - Settlers wait and chatter instead of acting.
  - 9.4% of decisions had parse errors, and 11.7% of calls failed for an unrecorded reason. Failed calls
    are logged now.
- **Built:** the settler mind, model routes with budgets, the eval harness, the settlement board, CI,
  the decision log and the adversarial review.

## Next steps, in order

1. **Follow-up paid eval (needs owner approval):** run `cooperation` and `grounding` separately, each
   with its own cap, for example `--scenarios cooperation --max-calls 150`. Check the transcript's
   `call failed:` lines for the failure cause.
2. Mind work suggested by the run (docs/MIND.md backlog; run evals before and after each change):
   - Make agreeableness and the requester's standing actually shape replies to requests.
   - Discourage idle `wait` and chatter plans.
   - Tell the model the `follow` and `go_to` limits.
3. Then the existing backlog: danger preemption, a cheaper `chat` route, a local-model provider, and
   multi-day drift and cooperation scenarios.

## Needs owner approval (per AGENTS.md)

- Any further paid run (the approved one is used up).
- Merging PR #1 to `main` (it's still a draft). GitHub's front-page README updates only after the merge.
- The `boardAnnouncements` default: `all` today; the agent recommends `nearby`.
- Anything touching the live Homestead server or its modpack. Next would be testing the jar on a
  *clone* of the pack, which needs an Open Parties and Claims adapter. Until then the claim guard
  fails closed and settlers won't mine.
- Product calls: the public name, new kinds of world actions, defaults that change gameplay.

## Local environment notes

- **Java:** Gradle 8.14 needs JDK 17–21. This PC's default is JDK 25, which fails, so use the portable
  Temurin 21: `export JAVA_HOME="/c/Users/jaren/.jdks/jdk-21.0.13+9"` (bash). CI uses Temurin 21; the
  bytecode targets 17, as Homestead runs Java 17.
- **API key:** in the git-ignored `.env` (`ANTHROPIC_API_KEY`). Never commit or print it.
- **Fabric Loader is pinned to 0.16.14.** Fabric Language Kotlin is pinned to 1.13.14 because 1.14.x
  silently raises the loader. CI checks this.
- **Commands:**
  - `./gradlew test`: unit tests, fast.
  - `./gradlew runGametest`: in-world tests; starts from a fresh world each run.
  - `./gradlew runEval`: free scripted eval; add `-Peval="--provider claude ..."` for paid runs.
  - `./gradlew runClient`: try the mod in game. Start with `/settler summon <name>`, then talk using
    their name in chat and inspect them with `/settler mind <name>`.
- Don't run two Gradle builds at once on this PC: they share the Loom cache and can fail with "Failed
  to remap".

## Useful pointers

| Need | Where |
|---|---|
| Rules, decision rights | AGENTS.md |
| What's verified / not | docs/STATUS.md |
| Why things are the way they are | docs/DECISIONS.md |
| Mind design + research | docs/MIND.md |
| Model/cost strategy and measurements | docs/MODELS.md |
| Board contract | docs/BOARD.md |
| Evals and the first Claude run | docs/EVAL.md |
| Board review and fix status | docs/reviews/2026-11-13-board-review.md |
