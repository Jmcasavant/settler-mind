# Settler Mind (codename)

AI settlers for Minecraft (Fabric 1.20.1): inhabitants that live, work and remember.
Settlers have personalities, needs, memories, relationships and daily plans. They hear chat,
decide for themselves (including saying no), act through verified skills, and learn from failure.
Settlers coordinate through a shared **settlement board**: players and settlers post gathering tasks,
settlers choose whether to take them, and progress counts only what the game verifies they gathered.
Read `AGENTS.md` first if you're working on the code.

**Status:** pre-release, in development on `feat/settler-minds` (draft PR #1). Unit and in-world tests
run in CI on every push. Not yet tested on the Homestead modpack: it needs an Open Parties and Claims
adapter first, and until then the claim guard fails closed and settlers won't mine. Details are in `docs/STATUS.md`.

## Build and test

Requires a JDK from 17 to 21 to run Gradle (CI uses Temurin 21; builds target Java 17). Gradle 8.14
can't run on JDK 25, so if that's your default, point `JAVA_HOME` at a 21.

```bash
./gradlew test           # unit tests: the mind, the board, the eval simulation (fast, no Minecraft)
./gradlew runGametest    # in-world tests on a headless server, from a fresh world each run
./gradlew build          # everything above, plus the mod jar in build/libs
./gradlew runEval        # evaluation report in build/eval/<run>/report.md (free and offline by default)
./gradlew runClient      # dev client to try it by hand
```

## Try it in game

| Command | Effect |
|---|---|
| `/settler summon <name>` | Settler appears at your feet, owned by you (max 4 per player) |
| `/settler mine <name> <x y z>` | Walk there, mine with the best tool carried, collect drops |
| `/settler status <name>` | Health, food, current skill and phase, last verified outcome, inventory |
| `/settler follow\|stay\|pause\|resume\|stop <name>` | Control |
| `/settler dismiss <name>` | Drops everything carried, then leaves (their mind is deleted) |
| *say their name in chat* | e.g. "Tess, could you chop that log?" They decide whether and how to respond |
| `/settler mind <name>` | Personality, mood, needs, summary, today's intentions, relationships, lessons, recent memories |
| `/settler consent on\|off` | Allow your chat to be sent to a remote AI provider (off by default) |
| `/settler board` | The settlement task board: open and taken tasks, progress, recently closed |
| `/settler task post <item> <count> [note]` | Ask for help, e.g. `/settler task post oak_log 8 for the fence`. Settlers choose whether to take it; progress counts only what they verifiably gather |
| `/settler task cancel <id>` | Cancel a task you posted, or one your settler posted (operators: any task) |
| `/settler usage` | (op) AI calls, tokens, latency and estimated cost per route and model |
| Right-click with an item / sneak + right-click | Give item / open their inventory (owner only) |

Players need the mod installed on the client too (custom entity).

## AI configuration

`config/settlermind.json` is created on first start. By default every route uses the free, offline
`scripted` mind. To use Claude, set `"provider": "claude"` on a route and provide `ANTHROPIC_API_KEY`: export it on the
server host, or for local dev runs (`runClient`, `runServer`, `runEval`) put it in a git-ignored `.env`
(`cp .env.example .env`; same convention as minecraft-civ-lab, so you can copy the key from there). Budgets cap calls and estimated spend; when a cap is reached, settlers fall
back to the scripted mind. See `docs/MODELS.md`.

Privacy: with a remote provider configured, a player's chat and board-task notes reach it only after
that player runs `/settler consent on`. `boardAnnouncements` (`all`, `nearby` or `off`) sets who is told
about new board posts.

## Evaluation

`./gradlew runEval` runs scripted scenarios (requests, gossip spreading, grounding, cooperation through
the board) in a simulated world with ablations, and reports believability, coordination and cost.
Paid runs with Claude are opt-in and capped with `--max-calls`. See `docs/EVAL.md`.

## Docs

| Doc | What |
|---|---|
| `AGENTS.md` | Rules for contributors and coding agents, and who decides what |
| `docs/MIND.md` | How settlers think, and the research behind it |
| `docs/BOARD.md` | The settlement board contract |
| `docs/MODELS.md` | Model and cost strategy |
| `docs/EVAL.md` | The evaluation harness |
| `docs/DESIGN.md` | Architecture |
| `docs/STATUS.md` | What is verified and what isn't |
| `docs/DECISIONS.md` | Dated decision log |
| `docs/HANDOFF.md` | Latest session handoff |
