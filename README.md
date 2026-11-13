# Hearthkin (working title)

AI settlers for Minecraft (Fabric 1.20.1): inhabitants that live, work and remember.
This is the **spike** build: body, skills and protection only. No LLM yet.

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
| `/hk summon <name>` | Settler appears at your feet, owned by you (max 4 per player) |
| `/hk mine <name> <x y z>` | Walk there, mine with the best tool carried, collect drops |
| `/hk status <name>` | Health, food, current skill and phase, last verified outcome, inventory |
| `/hk follow\|stay\|pause\|resume\|stop <name>` | Control |
| `/hk dismiss <name>` | Drops everything carried, then leaves |
| Right-click with an item / sneak + right-click | Give item / open their inventory (owner only) |

Players need the mod installed on the client too (custom entity).

See `docs/DESIGN.md` for the architecture and `docs/STATUS.md` for what is verified.
