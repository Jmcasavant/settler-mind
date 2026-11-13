# Status: spike 0.1.0 (2026-11-13)

Pinned: Minecraft 1.20.1, Yarn 1.20.1+build.10, Fabric Loader 0.16.14, Fabric API 0.92.12+1.20.1, Loom 1.11.8, Gradle 8.14.3, Java release 17.

## Verified (in-world game tests on a headless dedicated server: 8/8 pass)

- Mines a log, and succeeds only when exactly 1 oak log is in the inventory and the block is gone.
- Picks the best tool from its inventory; the pickaxe takes durability damage; stone yields cobblestone.
- Stone mined by hand yields nothing, and this is reported as `PARTIAL(broken_not_collected)`, not success.
- Bedrock is refused with `FAILED(unbreakable)` and left intact.
- A block swapped mid-mining is detected as `FAILED(target_changed)`, with no items gained.
- Eats bread when hungry and skips rotten flesh unless starving.
- NBT save and load keeps identity, name, food and inventory, with no duplication; an in-flight skill reloads as `UNCERTAIN`.
- Dismissal drops every carried item exactly once.

## Not yet verified

- **Rendering and client join.** The client code compiles but has not been looked at in a real client.
- **Homestead clone.** Not tested. Settlers will refuse block changes there until an Open Parties and Claims adapter exists (fail-closed by design).
- Pathfinding over real terrain, combat and fleeing, and chunk unloading.
- Multiplayer: two owners, and an owner going offline.
- Performance with spark.

## Next

1. Run on a clone of Homestead: does it load among ~314 mods, and how do the other mods react to `FakePlayer`?
2. Open Parties and Claims adapter built against its real API jar, then the tests "own claim allowed / someone else's claim denied".
3. More skills (craft via recipe registry, place, deposit, farm), then the plan schema and validator, then the LLM.
