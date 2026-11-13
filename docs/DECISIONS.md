# Decision log

Newest first. Each entry: what, why (evidence/source), alternatives, how to revert. Owner-approved
decisions are marked **[approved]**; the rest were made by the agent under the working agreement in AGENTS.md.

## 2026-11-13

- **Harness design and its four owner decisions** [approved] (docs/HARNESS.md):
  1. Settlers change the world only where the claim guard allows, within a daily world-change budget per
     settler (about 64), and every change is journaled with `/settler undo`.
  2. Signs and notes are allowed, under the text policy and an admin listing; on by default, with a
     config switch for public servers.
  3. Settlers notice joins and leaves only when nearby or told.
  4. A settler may put into any chest the claim guard lets it use, and take only from its own chests
     or ones its owner shared.

  *Revert:* each ships behind `MindFeatures` and config switches.

- **Direction: settlers with a life of their own** [approved, owner]. Settlers recognise players who
  join, form beliefs about where people live, and can leave signs and notes for players and each other.
  Freedom comes from choosing goals within a growing, validated action catalog (AGENTS.md rule 1 stands).
  The owner then sharpened this into **build the harness, not the behaviour**: no scripted triggers or
  single-purpose features; general perception, actions and beliefs that the mind combines. Recorded in
  AGENTS.md and docs/MIND.md; the design follows a research survey. *Revert:* AGENTS.md section.

- **First paid eval run** [approved: 200-call cap, about $5–8]. Spent $2.82 estimated; see docs/MODELS.md
  "Measured" and docs/EVAL.md "First Claude run". No model choice changes yet: the scenarios that matter
  most (cooperation, grounding) didn't run, and 11.7% of calls failed for an unrecorded reason. Next:
  log failures (done), then run cooperation and grounding with separate caps (needs a new approval).
  Candidate mind work from it: requests flatten toward compliance (agreeableness isn't respected), and
  settlers wait and chatter instead of acting. *Revert:* docs only.

- **Board review phase B (host) and C (eval sim)** done locally after the cloud session's work did not
  arrive; docs/reviews/2026-11-13-board-review.md "Fix status". Choices made:
  - Mining credit is capped per item at what the break's own item entities held. *Alternative:* follow
    each drop entity to its pickup, which is more exact but breaks when drops merge. *Revert:* `MineBlockSkill.cap`.
  - Reservations are released on Fabric `ENTITY_UNLOAD` and cleared at server start, because work in
    progress isn't saved. *Revert:* the handler in `MindHost.register` and the line in the `MindHost` constructor.
  - An owner's cancel of a settler's post needs the settler loaded, because the owner is only known from
    the entity. It fails closed. *Alternative:* store the owner id on the task (a save-format change).
  - `boardAnnouncements` keeps the default `all`. Narrowing it to `nearby` changes player experience,
    so that's the owner's call.
  - Notes follow the chat consent rule (`MindHost.noteShareable`).

- **Working agreement** [approved]: the agent leads; the owner approves money, merges, the live server,
  product, privacy and big irreversible choices. *Revert:* edit AGENTS.md.
- **Coordination board v1 = GATHER tasks only** (docs/BOARD.md). *Why:* MINDcraft (arXiv 2504.17950)
  found natural language is the main collaboration bottleneck, and gathering is the only outcome the
  body can verify today. *Alternatives:* free-form tasks (unverifiable), chat-only coordination (the
  baseline it's measured against). *Revert:* `MindFeatures.board` off; the board steps are in their own catalog entries.
- **API key via env var or a git-ignored `.env`**, same convention as minecraft-civ-lab; a real API key
  rather than the subscription CLI login (terms and cost-tracking caveats). *Revert:* remove the `.env`
  loader block in build.gradle.
- **Eval harness in test sources, simulated text world.** *Why:* fast, free, repeatable; the same Mind code
  runs as in game. Paid runs are capped by `--max-calls`. *Alternative:* in-game evals (slow, flaky). *Revert:* delete `src/test/.../eval`.
- **Repeat suppression is time-bounded (1 min)**, found by the eval harness: settlers could never
  repeat a line. *Revert:* `Mind.REPEAT_WINDOW`.
- **Fabric Language Kotlin pinned to 1.13.14** so Fabric Loader stays 0.16.14; 1.14.x raised it to
  0.19.5 and made startup flaky. *Revert:* `flk_version` in gradle.properties.
- **Codename `settler-mind`, mod id `settlermind`** [approved]. The public name is undecided.
- **Model choice is open, per route, and measured** [approved]: no model id in game logic; defaults are
  offline. See docs/MODELS.md.
- **The mind is engine-agnostic; the LLM sets goals and talks while deterministic skills act**, with a
  closed action catalog and validated decisions. *Why:* Generative Agents, Voyager, Odyssey, MINDcraft,
  and the second-opinion review. See docs/MIND.md.
- **Body = custom entity plus a transient FakePlayer**, not a puppet real player. *Why:* the
  second-opinion review (puppet players touch every player-only hook across ~314 mods); PlayerEngine
  uses the same pattern. See docs/DESIGN.md.
