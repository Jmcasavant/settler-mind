# Settler Mind (codename)

AI settlers for Minecraft (Fabric 1.20.1): inhabitants that live, work and remember.
Settlers have personalities, needs, memories, relationships and daily plans. They hear chat,
decide for themselves (including saying no), act through verified skills, and learn from failure.
Read `AGENTS.md` first if you're working on the code.

## Build and test

Requires JDK 17+ (builds target Java 17).

```bash
./gradlew build          # compiles and runs the in-world game tests
./gradlew runGametest    # game tests only (headless server, exits non-zero on failure)
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
| `/settler usage` | (op) AI calls, tokens, latency and estimated cost per route and model |
| Right-click with an item / sneak + right-click | Give item / open their inventory (owner only) |

Players need the mod installed on the client too (custom entity).

## AI configuration

`config/settlermind.json` is created on first start. By default every route uses the free, offline
`scripted` mind. To use Claude, set `"provider": "claude"` on a route and export `ANTHROPIC_API_KEY`
before starting the server. Budgets cap calls and estimated spend; when a cap is reached, settlers fall
back to the scripted mind. See `docs/MODELS.md`.

See `docs/MIND.md` for how settlers think, `docs/DESIGN.md` for the architecture and `docs/STATUS.md` for what is verified.
