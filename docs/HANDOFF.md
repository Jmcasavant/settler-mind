# Session handoff (local)

Updated 2026-11-13 ~21:00 UTC by a local Claude Code session on Jaren's Windows PC. Read **AGENTS.md
first**, then **docs/GOVERNANCE.md** (the mod's purpose), then this file.

## Where things stand

- **Repo:** `Jmcasavant/settler-mind`, private. Work is on `feat/settler-minds`; draft PR
  [Jmcasavant/settler-mind#1](https://github.com/Jmcasavant/settler-mind/pull/1) targets `main`, which
  holds only the first scaffold commit. Auto-fix is on for the PR in the local session.
- **Board review:** every finding in docs/reviews/2026-11-13-board-review.md is fixed. Phase B (host)
  and phase C (eval simulation) were redone locally, because the cloud session's versions never arrived.
- **Tests:** 73 unit and 13 in-world tests pass locally. In-world: 12 consecutive passing runs. Two
  earlier runs each had one failure while another Gradle build ran at the same time, and the failing
  test wasn't captured (docs/STATUS.md).
- **Purpose set by the owner:** free will within a world of laws settlers can shape.
  - **Layers:** nature, a minimal admin-set charter, settlement law that settlers make, obtainable
    permits, and choice (docs/GOVERNANCE.md).
  - **Harness design:** docs/HARNESS.md (approved). It covers the event bus, generic verbs over
    affordances, beliefs, goals, routines, systems settlers build themselves, and open-endedness evals.
  - **AGENTS.md** says: build the harness, not the behaviour.
- **Paid evals done ($5.65 of the approved runs):** all four scenarios, results in docs/EVAL.md and
  docs/MODELS.md.
  - **Cost:** about $0.011 per decision, about 4 s each.
  - **Good:** coordination is safe (0 double claims, 0 fencing violations) and fast. Grounding is strong.
    Settlers sometimes post their own tasks.
  - **Problems:**
    - settlers comply with every request;
    - they wait and chatter (about 4× the scripted mind's calls per finished task);
    - parse errors run 9–16%, mostly `go_to` beyond 32 blocks;
    - 6–9% of calls are refused, likely because the schema asks for "private reasoning". Refusals now
      record their category.
- **Built:** the settler mind, model routes with budgets, the eval harness, the settlement board, CI,
  the decision log and the adversarial review.

## Next steps, in order

1. **Mind fixes from the evals** (need a paid before-and-after run, so owner approval):
   - rephrase the `thought` field as in-character intent;
   - tell the model the `go_to` limit;
   - let agreeableness shape replies to requests.
2. **Harness phase 1** (docs/HARNESS.md "Build order"): open-endedness metrics, the event bus, and
   multi-day sim runs. Free and offline, so the agent may proceed.
3. Then phases 2–6, and the governance pieces in docs/GOVERNANCE.md, after its six open owner decisions.

## Needs owner approval (per AGENTS.md)

- Any further paid run (all approved runs are used up).
- The six decisions at the end of docs/GOVERNANCE.md: conflict, players and town law, land, new
  settlers, offscreen life, owner commands.
- Merging PR #1 to `main` (it's still a draft). GitHub's front-page README updates only after the merge.
- The `boardAnnouncements` default: `all` today; the agent recommends `nearby`.
- Pushing: the local permission check blocks unrequested pushes, so ask before `git push`.
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
