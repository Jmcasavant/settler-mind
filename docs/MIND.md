# The settler mind

The mind serves the mod's purpose (docs/GOVERNANCE.md): free will within a world of laws settlers can
shape. Where this document and that one differ, GOVERNANCE.md wins, and this one gets updated.

## Loop

```text
 events (heard / step finished / hurt / idle / new day / enough to reflect)
        │  Mind.observe: remember, update needs and relationships, maybe wake
        ▼
 Mind.nextRequest ── route: decide | plan | reflect
        │  Prompts: stable system (identity, rules, actions) + situation (summary, needs,
        │  relevant people, lessons, recalled memories, recent talk, perception, triggers)
        ▼
 Cognition (scripted | claude | …) ── async, budgeted, falls back to scripted
        ▼
 DecisionParser (trust boundary) ── closed action catalog, only perceived targets, sanitised speech
        ▼
 Mind.onResult: summary, feelings, memories ──► body: speak, run plan steps (verified skills)
                                                    └─► outcomes come back as events
```

## Components and their research basis

| Component | What it does | Basis |
|---|---|---|
| Memory stream + recall | Recency + importance + relevance (keyword/entity tags; no embeddings yet). Forgets weakest; protects reflections/lessons | Generative Agents (Park et al., 2023, arXiv 2304.03442) |
| Reflection | 2–3 times per game day when enough happened; insights become high-value memories | Generative Agents: removing reflection lowered believability |
| Daily plan | 3–5 intentions at dawn steer idle choices toward the settler's aspiration | Generative Agents (planning ablation); Humanoid Agents (2310.05418) |
| Rolling self-summary | Each decision updates "situation so far", carried forward at no extra call | Lyfe Agents (2310.02172): self-monitoring was the biggest ablation loss |
| Needs | Hunger and safety from the body; social and purpose decay by personality | Humanoid Agents ("System 1" needs and emotion) |
| Relationships | Affinity, trust and familiarity with inertia, scaled by traits, with evidence notes | Humanoid Agents (closeness); Project Sid (social module, 2411.00114) |
| Lessons | Failures keyed by action and result code, always shown | Reflexion (2303.11366) |
| Gossip provenance | Heard speech is stored as "X said …" with source and lower confidence, never as fact | Project Sid: hallucination spreading; Generative Agents: embellishment |
| Loop limits | Reply cap per partner per window; repeated lines are suppressed; no model call needed | Lyfe Agents (cheap termination); cost control |
| Closed actions + verified skills | Model picks from a catalog; deterministic skills verify outcomes | Voyager (2305.16291), Odyssey (2407.15325), MINDcraft (2504.17950) |
| Decision then broadcast | One decision drives both speech and action, so they stay consistent | Project Sid (cognitive-controller bottleneck) |

CoALA mapping (2309.02427):
- **Working memory:** perception plus recalled memories.
- **Episodic memory:** MemoryStream.
- **Semantic memory:** reflections and relationships.
- **Procedural memory:** skills plus lessons.
- **Actions:**
  - grounding: steps and speech;
  - retrieval: recall;
  - learning: remember and reflect.

## Backlog from the research (ranked)

**Should:**
1. **Structured coordination board** (claim/request/done tasks) visible in perception. MINDcraft found natural language is the main coordination bottleneck.
2. ~~Evaluation harness~~ (done: docs/EVAL.md). Next: pairwise TrueSkill comparisons, multi-day drift and cooperation scenarios.
3. **Near-duplicate memory merge / summarise-and-forget** (Lyfe Agents).
4. **Preemption:** a danger reflex (flee) that cancels plans without waiting for the model.
5. **`chat` route** for pure conversation, cheaper than `decide`.

**Later:**
6. Time-bounded beliefs, e.g. "Bob's house is at …" with a validity window (Zep/Graphiti, 2501.13956).
7. A small local embedder for hybrid keyword + vector recall (only if evals show retrieval misses).
8. Saved routines: verified compositions of catalog steps (Voyager-style skill library, but no generated code).
9. Settlement-level culture: shared norms, roles, elections. Only once individuals are convincing.

## Owner direction: settlers with a life of their own (2026-11-13)

The owner wants settlers to have free will: to do what they want, without an end to what they might do.
The example: a player joins and the settlers recognise them. One settler leaves a sign, or a note in a
chest, where it *thinks* that player lives. Settlers do the same for each other.

These are **examples of what should emerge, not features to build** (AGENTS.md, "Build the harness,
not the behaviour"). Nothing here gets a hand-written trigger or a single-purpose action. The harness
has to give settlers general ways to perceive, act and form beliefs, so that this and much else can
happen on their own initiative.

The harness design is docs/HARNESS.md (draft for owner review). It covers the action surface, event
perception, belief store, self-directed goals, skill learning, cost, and how to evaluate open-ended
behaviour. It's based on the research survey docs/research/2026-11-open-ended-harness.md.

## Evaluation

Implemented in the eval harness: see **docs/EVAL.md**. The methods are:

- **Interviews** (Generative Agents style): ask settlers fixed questions about self, memory, plans,
  reactions and reflections. Judge pairwise with a model and by hand, and score with TrueSkill.
- **Ablations:** no reflection / no summary / no relationships / no lessons against the full mind.
- **Information diffusion:** seed a fact with one settler and measure who knows it, and how accurately, after N days.
- **Persona fidelity:** do disagreeable settlers refuse more? Do extraverts talk more? Watch for drift toward bland politeness.
- **Grounding:** rate of invalid plans (DecisionParser errors), hallucinated recall, and "claimed but didn't do".
- **Cost:** tokens, latency and dollars per settler-hour and per good decision (see MODELS.md).
