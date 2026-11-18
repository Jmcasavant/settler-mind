# Design

Independent of `minecraft-civ-lab`: no shared protocol, schemas or code.

## Decisions (and why)

| Decision | Reason |
|---|---|
| **Body = custom `PathAwareEntity`**, player-model renderer | A fake *real* player (Carpet-style) touches every player-only hook in a ~314-mod pack: quests, stats, player data, voice, tab list, sleep. The most-used AI companion mods (PlayerEngine / Player2NPC) also use a custom living entity. |
| **Borrowed `FakePlayer` per interaction** (`BorrowedHands`) | Block breaking, loot tables, enchantments and modded `onUse` expect a player. The fake has its own identity derived from the settler, **not** the owner's, so it never inherits the owner's claim rights. Its whole inventory is swept back to the settler afterwards. |
| **Skills are deterministic Java with verified outcomes** | `SUCCEEDED` only when the world or inventory really changed. Breaking without collecting is `PARTIAL(broken_not_collected)`, never success. |
| **Reflexes beat skills** | Eating (and later fleeing) pre-empts the active skill, with no LLM involved. |
| **Protection fails closed** | If a known claims mod is installed and we have no verified adapter, settlers refuse to change blocks (`claims_adapter_missing`). |
| **In-flight work after a restart is `UNCERTAIN`** | The settler re-observes; it never assumes the work completed. |
| **The LLM sets goals and talks; it never acts directly** (see MIND.md) | It emits a closed plan schema (`{reason, goal, steps[]}`) using only registered skill kinds. A Java validator checks it, owner and claim rules included. Commands, op, shell and URLs don't exist as tools. |
| **Everything in one JVM; memory in the world save** | No sidecar process. Backups and rollbacks keep memory consistent with the world. |

## Ideas adopted from the 2026-11 survey of AI mods

Sources: PlayerEngine, SecondBrain, nekostul, Mindcraft and others. Used as reference only; no code copied (several are LGPL or have unclear licences).

1. Parser first, LLM second. Plain commands ("follow", "store items") never call a model.
2. Two model tiers (cheap for banter, stronger for goals), each with per-world budgets. Degrade to deterministic behaviour when a budget runs out.
3. Settlers speak only when addressed or nearby. One in-flight request per settler, with turn gating to stop NPC-to-NPC chat loops.
4. A modpack capability index (registries, recipes, aliases) built at startup. Only relevant slices go into prompts.
5. Durable-fact memory with compaction, plus social memory and gossip between settlers.
6. Material and position reservations between settlers.
7. A repeated-failure breaker (stop and ask the owner); "stop" always overrides.

## Pathfinding

Vanilla mob navigation for now, behind the skill layer. Automatone (LGPL-3) has an unreleased `1.20` branch built on Quilt, not Fabric, with no published artifact, so it is not usable as-is. Revisit after the Homestead clone test.
