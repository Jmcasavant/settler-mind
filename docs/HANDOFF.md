# Session handoff (local)

Written 2026-11-13 ~18:50 UTC by the cloud session that built this branch. A new session (local or
cloud) should read **AGENTS.md first** (rules plus the working agreement on what needs owner approval),
then this file.

## Where things stand

- **Repo:** `Jmcasavant/settler-mind`, private. Work is on `feat/settler-minds`; draft PR
  [Jmcasavant/settler-mind#1](https://github.com/Jmcasavant/settler-mind/pull/1) targets `main`, which
  holds only the first scaffold commit.
- **Last pushed commit:** `acc7121`, "game tests now start from a fresh world every run". At hand-off
  time its CI run was still in progress; the previous commit `674405c` was green. Check:

  ```bash
  gh pr checks 1 --repo Jmcasavant/settler-mind
  ```

- **Tests at `acc7121`:** 63 unit tests and 11 in-world tests pass. The full build was green 3 times in a row.
- **Built so far:**
  - The settler mind: persona, needs, memory, reflection, daily plan, relationships, lessons.
  - Model routes with budgets: the offline scripted mind by default, Claude opt-in.
  - The eval harness (`./gradlew runEval`).
  - The settlement coordination board (docs/BOARD.md).
  - CI, the decision log (docs/DECISIONS.md) and the adversarial review (docs/reviews/).

## In flight in the cloud session (not on GitHub yet)

The board review (docs/reviews/2026-11-13-board-review.md) is being fixed in three phases:

- **Phase A: done and pushed** (`674405c`). Findings 1, 2 (board side), 3, 5, 7, 8, 10 and 11 (board side).
- **Phase B: running** (Minecraft host side). Task notes are gated on consent, credit counts only what
  the block dropped, reservations get dimension keys and cleanup, owners can cancel their settlers'
  posts, and a test covers two settlers with the same name.
- **Phase C: running** (eval simulation fidelity, finding 6). Mining takes several steps, decisions are
  delayed, and double claims are checked independently.

If the cloud session finishes them, they arrive as new commits on `feat/settler-minds`. **Pull before
starting.** If they never arrive, redo phases B and C from the review file's "Fix status" list. Each item
there says what to do.

## Next steps, in order

1. `git pull`, then `./gradlew build`. Everything must be green; the game tests take about 30 s.
2. Confirm every finding in docs/reviews/2026-11-13-board-review.md is ticked, or explicitly deferred with a reason.
3. **Live Claude eval.** The owner has approved this run: a 200-call cap and roughly $5–8.
   ```bash
   cp .env.example .env
   ```
   ```bash
   # put ANTHROPIC_API_KEY=... in .env (git-ignored; the same key as in minecraft-civ-lab's .env, if it has one)
   ./gradlew runEval -Peval="--provider claude --model claude-opus-5-5 --effort low --judge claude --max-calls 200"
   ```
   The report is written to `build/eval/<run>/report.md`. Then:
   - Record the measured cost per decision, latency, parse-error rate and judge scores in
     **docs/MODELS.md** (replace the "Rough sizing" paragraph).
   - Add a dated entry to **docs/DECISIONS.md**.
   - Compare against the scripted baseline in docs/EVAL.md.
4. Use the results to plan the next mind work (docs/MIND.md backlog): danger preemption, a cheaper `chat`
   route, a local-model provider, and multi-day drift and cooperation scenarios. Run evals before and after each change.

## Needs owner approval (per AGENTS.md)

- Any paid run beyond the one approved above.
- Merging PR #1 to `main` (it is still a draft).
- Anything touching the live Homestead server or its modpack. Next would be testing the jar on a
  *clone* of the pack, which needs an Open Parties and Claims adapter; until then the claim guard
  fails closed and settlers won't mine.
- Product calls: the public name, new kinds of world actions, defaults that change gameplay.

## Local environment notes

- **Java:** JDK 17 or newer (CI uses Temurin 21). The bytecode targets 17, and Homestead runs Java 17.
- **Fabric Loader is pinned to 0.16.14.** Fabric Language Kotlin is pinned to 1.13.14 because 1.14.x
  silently raises the loader. CI checks this.
- **Commands:**
  - `./gradlew test`: unit tests, fast.
  - `./gradlew runGametest`: in-world tests; starts from a fresh world each run.
  - `./gradlew runClient`: try the mod in game. Start with `/settler summon <name>`, then talk using
    their name in chat and inspect them with `/settler mind <name>`.
- **Cloud environment:** the cloud session's environment is the shared "Default" one, and no API key is
  stored there. Running live evals in the cloud would need a settler-mind environment plus a key added
  through the credential form. Locally, the `.env` file is enough.

## Useful pointers

| Need | Where |
|---|---|
| Rules, decision rights | AGENTS.md |
| What's verified / not | docs/STATUS.md |
| Why things are the way they are | docs/DECISIONS.md |
| Mind design + research | docs/MIND.md |
| Model/cost strategy | docs/MODELS.md |
| Board contract | docs/BOARD.md |
| Evals | docs/EVAL.md |
| Open review findings | docs/reviews/2026-11-13-board-review.md |
