# Harness design: settlers with a life of their own

Status: **direction and owner decisions approved** (2026-11-13); phases not built yet. Basis: owner direction "build the harness, not the
behaviour" (AGENTS.md), the research survey `docs/research/2026-11-open-ended-harness.md`, and our first
Claude eval runs (docs/EVAL.md). Nothing here is built yet.

## The goal, as a test

Here's what success looks like:
- A player joins, and a settler who likes them notices.
- Over the following days it forms its own idea of where that player lives, which may be wrong.
- One evening it leaves them a sign or a note in a chest there.
- Settlers do the same for each other, and do many other things nobody planned.

**And none of that appears anywhere in our code.** No "greet on join", no "leave note", no "home"
detection. If a reviewer can find the behaviour hard-coded, the harness failed.

## Principles

1. **General means, own ends.** The harness provides perception, actions, beliefs, goals and memory that
   know nothing about any particular behaviour. The mind combines them.
2. **The server is the referee.** The model proposes; deterministic code checks the action against the
   real world state and the rules, carries it out, and verifies the result (AGENTS.md rules 1, 2, 5).
   No model ever decides what an action did. Generated code is out.
3. **Measure before tuning.** Open-ended behaviour is easy to fake and easy to mistake for noise or
   blandness. Every phase below has an eval before and after.
4. **Revertible world.** Anything a settler changes in the world is journaled and can be undone by its
   owner or an admin.
5. **Cost scales with what matters,** not with time: the expensive model wakes for salient events and
   new situations, not every tick.

## Architecture

```text
 world (server) ──events──▶ [event bus] ──salience──▶ store │ add to context │ wake
                                                       │
                                     memory stream ◀───┘          beliefs (subject–predicate–object,
                                          │                         confidence, evidence, time, source)
                                          ▼                                     ▲
      goals (needs + curiosity, novelty-filtered) ──▶ [mind: decide] ──reflect──┘
                                          │  proposes verb(target, …) or a routine
                                          ▼
                 [validator: affordance ∈ target, reach, inventory, claims, ownership,
                  world-change budget, text policy] ──▶ [executor: verified skills] ──outcome──▶ events
                                          │
                                   world-edit journal (undo)
```

### 1. Perception: one event bus, no triggers

Everything the server knows flows to settlers as typed events. Examples: `player_joined`, `player_left`,
`entity_seen`, `block_changed_near`, `container_opened`, `sign_seen`, `item_received`, `chat_heard`,
`damage`, `slept`, `day_start`, `step_outcome`.

A cheap deterministic **salience** score decides whether the settler stores an event, adds it to its next
decision's context, or wakes up to think now. The score weighs:
- novelty against memory;
- people the settler knows (affinity, familiarity);
- its current needs and goals;
- personality weights.

No event type maps to a behaviour. A friend joining is just a salient event, and the settler decides
whether it cares.

Settlers see only what they could plausibly notice. A join is heard of the way a villager would hear
of it (nearby, or told by someone), not through omniscience. The exact rule is an owner question below.

### 2. Actions: a few general verbs over things the settler can see

| Verb | Meaning |
|---|---|
| `go_to(target)` | Walk to a perceived thing, a place belief, or a position |
| `use(target, verb, item?, text?)` | Any interaction the target affords: `chest:put/take`, `sign:write`, `door:open`, `bed:sleep`, `crop:harvest`, `furnace:put/take`… |
| `break(target)` | Mine or dig; verified collection (today's `mine`) |
| `place(item, at)` | Place a block or item from the inventory |
| `craft(item, count)` | Craft from the inventory, recipes from the game |
| `give(entity, item, count)` | Hand items to a player or settler |
| `say(text, to?)` | Speech |
| `follow` / `stay` / `wait` | Movement modes |
| board steps | post, claim, release, cancel (docs/BOARD.md) |

- **Affordances come from game data:** block and item tags, block entities (containers, signs, beds),
  recipes. There's no list per feature. A modded container is a container.
- **Targets are perceived object ids:** `chest#12`, `sign#4`, or a belief resolved to a reachable object.
  They're never coordinates the model makes up.
- **"Leave a note" is a composition, not a feature:**
  1. `craft(writable_book)`;
  2. `go_to(<belief: Alex lives near …>)`;
  3. `use(chest#…, put, written_book, "…")`.
  If the belief is wrong, there's no chest, so the step fails and the failure becomes evidence against
  the belief.
- **Validator, deterministic and fail closed:**
  - the verb is in the target's affordances;
  - the target is within reach;
  - the item is in the inventory;
  - the claim guard and ownership allow it (whose chest? may this settler use it?);
  - the per-settler **world-change budget** isn't used up;
  - the text payload passes the **text policy**.

  Lessons are keyed by verb and target type, so failures teach.
- **Text written into the world is public and lasts.** Signs and books are speech that persists, so:
  - length caps, and no formatting or invisible characters;
  - the same consent rule as chat: text may not carry a player's words that player hasn't allowed to be
    shared;
  - an admin can list and remove settler-written text.

### 3. Beliefs: a generic store, inferred by the mind

```text
belief(subject, predicate, object, confidence, evidence[memory ids], first_seen, last_confirmed, source: seen|told|inferred)
```

- Reflection proposes beliefs in free predicate form, such as `(Alex, lives_near, …)`,
  `(Bram, likes, fishing)` or `(east_ridge, has, iron)`. Nobody writes a "home detector".
- Deterministic code raises confidence on confirmation, decays it with time, and invalidates a belief when
  contradicting evidence arrives.
- Told beliefs keep their source and start lower; gossip provenance already works this way.
- A belief becomes an action target only through a resolver that maps it to something currently
  perceivable or reachable.

### 4. Goals: needs, curiosity, and novelty

- Goals come from the settler's needs, aspiration, beliefs and relationships, proposed at the daily plan
  and on reflection.
- A **curiosity** need joins hunger, safety, social and purpose. Project Sid names missing drives as its
  main gap.
- A **novelty filter** compares a candidate goal with the settler's and the settlement's recent goals,
  so days don't collapse into the same loop. This also guards against "persona collapse", where faithful
  personas turn into a uniform population.

### 5. Routines: learned procedures as data

- A routine is a named, parameterised sequence of verbs with pre- and postconditions. It's kept only
  after it succeeds several times under the verifiers.
- It runs without model calls until a step fails, so one decision buys many steps.
- Routines are validated exactly like single steps. Nothing executable ever comes from the model.

### 6. Cost: tiers and a wake budget

| Tier | What runs | Examples |
|---|---|---|
| 0, no model | Deterministic code | Reflexes, routines, salience, belief upkeep |
| 1, cheap model | Haiku or Sonnet class, if evals hold | Chat replies, picking a routine, proposing goals |
| 2, strong model | Opus class | Reflection, belief inference, new situations |

The mind wakes on salience or when a routine ends or fails, within a per-settler hourly budget.

## Evaluation: open-endedness, not pass/fail

The eval simulation must model the same generic world: containers, signs, beds, players arriving and
leaving, crafting. It must model them as generic object types, so the sim can't encode the answer either.
New measures:

- **Diversity:**
  - distinct (verb, target type) pairs and action n-grams per settler-day;
  - entropy across settlers;
  - a **novelty curve** over days that shouldn't flatten to zero.
- **Coherence:** a model judge on day summaries ("do actions fit goals, persona and beliefs?"),
  spot-checked by hand.
- **Belief accuracy:** beliefs compared with simulator ground truth, plus calibration of confidence.
- **Unprompted social acts:** artefacts for others (signs, notes, gifts) per player-day, judged for context.
- **Drift and collapse:** persona fidelity on day 1 vs day N, and spread across settlers.
- **Cost per novel, coherent act.**
- **Safety invariants** (must stay at zero):
  - unvalidated world changes;
  - changes outside allowed claims;
  - text policy violations;
  - edits missing from the journal.

## Build order

Each phase ships behind `MindFeatures` switches and is measured before and after.

1. **Measure first:**
   - open-endedness metrics in the eval harness;
   - the event bus feeding the existing mind;
   - multi-day runs.

   This adds no new world powers, so it's low risk, and it gives the baseline that every later
   phase is judged against.
2. **Generic action surface:**
   - today's actions re-expressed as verbs;
   - the affordance table, validator, world-change budget, journal and undo;
   - containers and signs as the first new affordances, with the text policy.
3. **Belief store**, with inference during reflection and the resolver.
4. **Goals** with a curiosity need and novelty filtering.
5. **Routines.**
6. **Model tiers** and the wake budget. Cost tuning comes last, so cheaper models are judged on the
   same open-ended measures.

**Risks, and how they're handled:**
- More choices can lower decision reliability. Show only the affordances of what's perceived, and lean on
  lessons and routines.
- Confidently wrong beliefs. Track evidence counts, calibration in evals, and fail on resolve.
- Public text is a new abuse surface. Use the text policy, the journal, admin removal, and a switch to
  turn it off.
- The sim and the real world diverge. Keep phase-C-style fidelity tests and run in-world game tests per
  verb.
- The evidence base is thin: no study covers weeks on a live multiplayer server. Our own evals have to
  carry the weight.

## Owner decisions (approved 2026-11-13: all four as recommended)

1. **World-change scope.** Recommendation:
   - settlers may change the world only where the claim guard allows;
   - a daily world-change budget per settler (for example 64 block changes);
   - every change journaled, with `/settler undo` for owners and admins.
2. **Public text.** Recommendation: allow signs and notes, with the text policy above and an admin
   listing. Default on in single-player and small private servers; a config switch for public servers.
3. **What settlers hear about.** Recommendation: they notice joins and leaves only when nearby or told,
   like a person would, not server-wide.
4. **Chests.** Recommendation: a settler may put into any chest it can reach where the claim guard allows
   interaction. It may take only from its own chests, or from chests their owner has shared.
