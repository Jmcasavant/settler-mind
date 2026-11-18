# Research: a harness for open-ended settler behaviour (2026-11)

**Question:** how can settlers do open-ended, believable things they were never scripted for, such as noticing a player join, forming a belief about where that player lives, and leaving them a sign or a chest note, while model output stays validated and the cost stays bounded?

**Short answer:** the literature agrees on a layered design.
- **Generic world interface:** a small set of generic verbs over typed, perceivable objects. Which verbs apply to which object comes from an affordance table derived from game data, not from hand-written behaviours.
- **Server as referee:** the server is the final judge of what an action does. This is the "game master" role in Concordia, made deterministic.
- **Event stream and beliefs:** a generic event stream with salience scoring, plus a belief store with evidence and time.
- **Goals and routines:** goals the agent sets for itself, filtered for novelty, and reusable routines kept as data rather than code.
- **Cost:** a fast/slow split, so the expensive model runs only when something salient happens.
- **Evaluation:** measure diversity, coherence and population spread rather than pass/fail scenarios.

Code generation of the Voyager kind is the most open-ended option, but it conflicts with the project's hard safety rule.

How sources were checked: claims marked **[abstract]** were checked only against the arXiv abstract page. **[unverified]** means a secondary summary that I could not confirm. Everything else was checked in the paper text or is a well-known result of a classic paper.

---

## 1. Action-space design

| Approach | Example | Open-endedness | Safety | Reliability |
|---|---|---|---|---|
| Closed, task-specific actions | current catalog; Generative Agents | low: every new behaviour needs a new action | high | high |
| Generic primitives over typed objects and affordances | SayCan (2204.01691); Odyssey skills; MINDcraft commands | medium-high: verbs × objects × free-text payloads combine | high if the world validates | high |
| Code generation | Voyager (2305.16291), Odyssey | highest | low: model output executes | medium; needs self-verification |
| Free-text intent grounded by a game master | Concordia (2312.03664; 2507.08892) | high | medium: an LLM decides outcomes | medium; can hallucinate effects |
| Tool use / MCP | general agent practice | same as the tool set | equals the tool validation | high |

**Findings that bear on the choice:**
- **Voyager** (Wang et al., 2023, arXiv 2305.16291) writes JavaScript skills, keeps them in a library, and checks them with self-verification.
  - It got about 3.3× more unique items and unlocked tech-tree milestones up to 15.3× faster than earlier methods.
  - Ablations: removing self-verification or the automatic curriculum cut performance sharply.
  - The open-endedness comes from executing generated code, which is exactly what our safety rule forbids.
- **Odyssey** (arXiv 2407.15325, v3 May 2025) replaces generated code with a hand-built library of 40 primitive and 183 compositional skills.
  - The authors say this lets open-source LLMs drive the agent and avoids GPT-4 costs.
  - They also report that open-source models hallucinated more, which hurt performance.
- **MINDcraft / MineCollab** (White et al., arXiv 2504.17950, Apr 2025) exposes parameterised commands to the model rather than raw code.
  - Its main finding: natural-language communication is the bottleneck. Success drops by up to 15% when agents must communicate detailed plans **[abstract]**.
- **Concordia** (Vezhnevets et al., arXiv 2312.03664, 2023) has agents state actions in free text. A game-master (GM) agent checks whether each action is plausible and narrates its effects.
  - The 2025 rewrite (arXiv 2507.08892, Jul 2025) makes the GM a configurable entity built from components **[abstract]**.
  - This gives the most expressive design, but grounding is done by an LLM, so effects can be inconsistent with the real world state. In Minecraft the server already is an exact game master, so we don't need an LLM to play that role.
- **Project Sid / PIANO** (Altera, arXiv 2411.00114, Oct 2024) reports that the "action awareness" module improved item progression. That module compares expected with observed outcomes, but the paper gives no ablation numbers for it.

**Implication:** keep the trust boundary (parser → validated action), but make what crosses it generic. Instead of `mine`, `follow` and so on as fixed behaviours, expose:
- `go_to(target)`, `look_at(target)`
- `use(target, verb, item?, text?)`: the verb must be in the target's affordance set (for example `sign:write`, `chest:put`, `chest:take`, `door:open`, `crop:harvest`)
- `give(entity, item)`, `say(text, to?)`, `break(block)`, `place(block, at)`

Targets are perceived object IDs with types, never coordinates the model invents. The affordance table is built from block and item tags and capabilities, not per feature. This is how "leave a note in a chest" emerges: `use(chest#12, put, written_book{text})`. Nobody writes a "leave note" behaviour.

Free-text payloads (sign and book text) are the new risk surface. Sanitise them, cap their length, and treat them as player-visible speech. Ownership and land claims still fail closed in the validator.

## 2. Perception and events

- **Generative Agents** (Park et al., 2023, arXiv 2304.03442) scores every observation for importance (1–10) and reflects when the summed importance crosses a threshold. Their ablations showed that removing observation, planning or reflection each lowered believability (TrueSkill).
- **Lyfe Agents** (arXiv 2310.02172, 2023) adds self-monitoring: a rolling narrative summary that weights novel and relevant events. Its option-action design keeps an agent inside a chosen option until that option's termination condition fires, so the controller isn't called on every step. The authors claim 10–100× lower cost than alternatives **[abstract; not independently verified]**.
- **PIANO** runs its modules concurrently over a shared agent state, with a "cognitive controller" as the bottleneck that keeps speech and action coherent. Reflexes use small non-LLM networks.

**Implication:** one typed event bus for everything the server already knows, such as `player_joined`, `entity_seen`, `block_changed_near`, `container_opened`, `sign_text_seen`, `chat_heard`, `item_received`, `damage`, `day_start` and `step_outcome`.
- Each event gets a cheap deterministic salience score: novelty against memory, involvement of known people, the settler's needs and goals, and personality weights.
- The score decides one of three outcomes: (a) store it only, (b) store it and add it to the next decision's context, or (c) wake the mind now.
- "A player joined" is then just a salient event, not a trigger. No event type maps directly to a behaviour.

## 3. Knowledge and beliefs

- **Zep/Graphiti** (Rasmussen et al., arXiv 2501.13956, Jan 2025) stores memory as a bi-temporal knowledge graph. Facts carry validity intervals, and a fact is invalidated rather than deleted when new evidence contradicts it. Retrieval mixes embeddings, BM25 and graph traversal.
  - It reports beating MemGPT on Deep Memory Retrieval.
  - LongMemEval gains of up to 18.5% accuracy and 90% lower latency are **[unverified]** (from a secondary summary).
- **A-MEM** (arXiv 2502.12110, 2025) uses Zettelkasten-style notes. The model writes the links and can revise older notes.
- **Mem0** (arXiv 2504.19413, Apr 2025) reports 91% lower p95 latency and more than 90% token savings against full-context memory **[abstract]**. Critics note that the baseline is generous and that the cost of extracting memories is excluded **[unverified]**.
- **MemGPT/Letta** (arXiv 2310.08560, 2023) pages memory in and out of the context window under the model's own control.
- **BeliefMem** (arXiv 2605.05583, May 2026) keeps several candidate conclusions per observation, each with a probability updated by Noisy-OR. It reports the best average results on LoCoMo and ALFWorld **[abstract; no numbers seen]**.
- **PoS** (arXiv 2610.01415, Oct 2026) keeps an explicit, consistency-validated belief state and detects "belief trapping", where the agent keeps acting without progress. Its ablations support both parts **[abstract]**.
- **Project Sid** found that social inferences tracked ground truth (perceived vs. true likeability r ≈ 0.61–0.91 with the social module), and that ablated agents' relationships drifted toward neutral. It also notes that hallucinations spread between agents. Our gossip-provenance rule already answers this.

**Implication:** a generic belief store, with no schema per topic.

```text
belief(subject, predicate, object, confidence, evidence[memory ids], first_seen, last_confirmed, source: seen|told|inferred)
```

Example: `(Bob, lives_near, pos#…)`, inferred from repeated `entity_seen` evidence near a bed or door at night.
- Reflection is the step that proposes inferred beliefs. A deterministic updater raises confidence on confirmation, decays it with time, and invalidates a belief when evidence contradicts it.
- Beliefs heard from others start at lower confidence and keep their source.
- Location beliefs become valid action targets only through a validator that resolves them to a currently perceivable or reachable object. If the belief is wrong, the action fails, and the failure counts as evidence against it.
- Start with the structure; add embeddings only if evals show retrieval misses, as already planned.

## 4. Self-directed goals and open-endedness

- **Voyager's automatic curriculum** proposes the next task from the agent's state and history. It was the largest single ablation loss in that paper.
- **OMNI-EPIC** (Faldor et al., arXiv 2405.15568, ICLR 2025) uses a "model of interestingness" to propose tasks that are learnable and new compared with an archive. It also generates environments and reward code. Its claims of "explosive creativity" are qualitative, and it ran in PyBullet only.
- **Project Sid**:
  - **Single agent:** 25 agents averaged 17 unique items in 30 minutes. A 49-agent run levelled off at about 320 items in 4 hours.
  - **Roles:** social goals regenerated every 5–10 s produced persistent, village-dependent roles. Without the social module, roles did not persist and were all much alike.
  - **Taxes:** tax compliance followed constitutional amendments (about 20% → 9%), and that response disappeared when the architecture was ablated.
  - **Culture:** memes and religion spread across 500 agents.
  - **Limitations the authors state:** only GPT-4o made this work, agents lack innate drives such as survival and curiosity, and more than 1,000 agents overloaded the server. Results are from a few runs (3–6 repeats); treat them as demonstrations, not robust estimates.
- **Static Sandboxes Are Inadequate** (arXiv 2510.13982, 2025) is a position paper arguing for open-ended co-evolution in multi-agent simulation. It contains no experiments.
- **Persona collapse** (arXiv 2604.24698, Apr 2026) found that the models with the highest fidelity to each persona produce the most homogeneous populations **[secondary summary]**. Open-endedness at population level does not come for free.

**Implication:** goals come from a cheap generator in the daily plan or reflection, conditioned on needs, aspiration, beliefs and relationships. A novelty filter compares each candidate goal with that settler's recent goal archive, and with the settlement's archive, using embeddings or keyword overlap. This is a lightweight version of the OMNI model of interestingness. Drives (hunger, safety, social, purpose, plus a curiosity need) keep goals grounded; Sid's authors name the lack of drives as their main gap.

## 5. Skill and procedure learning without generated code

- Voyager's skill library improves performance, but its skills are code.
- Odyssey shows that a fixed, curated skill library plus model planning gets most of the benefit and runs on cheaper models.
- **JARVIS-1** (arXiv 2311.05997) and **Optimus-1** (arXiv 2408.03615) improve long-horizon Minecraft tasks with multimodal experience memory and retrieved past plans. Their exact gains are not restated here **[not re-verified]**.

**Implication:** a **routine** is a named, parameterised sequence of catalog steps with pre- and postconditions, stored as data.
- It is promoted into the library only after it succeeds N times under the deterministic verifiers.
- It is retrieved by goal similarity and runs without a model call until a step fails. That is the Lyfe option idea: one model call buys many steps.
- The parser validates routines exactly like single steps, so nothing executable ever comes from the model.

## 6. Evaluating open-ended behaviour

What the literature measures instead of pass/fail:

- **Believability:**
  - Generative Agents used interviews judged pairwise and ranked with TrueSkill, plus emergent diffusion. In the original paper, awareness of the party grew from 1 agent to 13 of 25, and 5 of the 12 invited attended.
  - Xiao et al. (arXiv 2312.17115) define consistency and robustness metrics (SimulateBench).
  - Lei et al. (ICML 2026, PMLR v306) score rationality, consistency and diversity against human behaviour distributions **[abstract]**.
- **Diversity and novelty:**
  - Sid measured role entropy across agents.
  - NoveltyBench (2025) and the persona-collapse geometry metrics measure diversity across outputs and across the population **[secondary summary]**.
- **Social emergence:** Sid measured relationship accuracy against ground truth, role persistence, rule compliance after norms changed, and meme and belief spread.
- **Grounding:** invalid-action rate and hallucinated effects. Sid's action awareness targets the second.

**Proposed metrics for us:**
- **Behaviour diversity:** count distinct (verb, target-type) pairs and action-sequence n-grams per settler-day, then compute entropy across settlers. Track whether "never-seen" combinations keep appearing over days, a novelty curve that should not flatten to zero.
- **Coherence:** a model judge on day summaries ("are actions consistent with stated goals, persona and beliefs?"), sampled and cross-checked by hand.
- **Belief accuracy:** compare `lives_near`-type beliefs with ground truth from server data, and check calibration of confidence.
- **Unprompted social acts:** count player-directed artefacts (signs, notes, gifts) per player-day, and judge whether each was contextually justified.
- **Drift:** compare persona fidelity on day 1 with day N, and check for persona collapse across settlers.
- **Cost per novel, coherent act:** combine the cost metric with the two above.

## 7. Cost and latency for many always-on agents

- **Lyfe Agents:** option-action and summarise-and-forget memory **[abstract]**.
- **Affordable Generative Agents** (arXiv 2402.02053, 2024) replaces repeated LLM interactions with learned policies and compresses dialogue. The reported token use was 31.1% of baseline for 3 agents and 42.7% for 25 agents **[unverified]**. The paper notes that 2–3 Stanford-Town agents cost several dollars per hour.
- **Mem0:** large token savings from retrieval rather than full context **[abstract]**.
- **PIANO:** reflexes use small non-LLM modules, so only cognition is slow.
- **Odyssey:** a skill library lets cheaper open models plan.
- **Prompt caching (provider feature):** cached prefix reads are billed at a fraction of the base input price. Our "stable system prompt + situation" split already matches this.

**Implication:** a tiered loop.
- **Tier 0 (no model):** reflexes, routines, salience scoring and belief updates.
- **Tier 1 (cheap model):** chat replies, routine selection, goal proposals.
- **Tier 2 (Opus):** reflection, belief inference, novel decisions where no routine matches.
- **Wake rule:** call a model only when an event's salience crosses the wake threshold or a routine ends or fails, with a per-settler hourly budget.

---

## Recommendations for Settler Mind (ranked)

1. **Generic action surface over typed affordances** (build first). Replace the specific actions with about 8 verbs and a data-driven affordance table: `use(target, verb, item?, text?)`, `give`, `place`, `break`, `go_to`, `say`, plus the board actions.
   - The server validates verb ∈ affordances(target), ownership and land claims (fail closed), reachability, and the text-payload policy.
   - Nothing in this needs a scenario-specific feature.
   - *Risk:* a bigger choice space lowers decision reliability. Mitigation: show only the affordances of perceived objects and keep lessons keyed by (verb, target type).
2. **Typed event bus plus deterministic salience** that decides store / context / wake. This is what lets joins, new signs and opened chests matter without triggers.
3. **Generic belief store** (subject, predicate, object, confidence, evidence, time, source), with inference during reflection and deterministic confirm, decay and invalidate.
   - Beliefs become action targets only via a resolver.
   - *Risk:* beliefs inferred by the model and confidently wrong. Mitigation: evidence counts and calibration in evals.
4. **Open-endedness evals before tuning:** diversity entropy, a novelty curve, coherence judging, belief accuracy, persona-collapse checks and cost per novel coherent act. Without these, "emergence" can't be told apart from noise or from blandness.
5. **Self-generated goals with a novelty archive and a curiosity need.**
6. **Routines as data:** verified compositions promoted after repeated success, executed without model calls.
7. **Model tiering and an event-driven wake budget.**

**What to avoid:**
- Generated code or command strings (Voyager-style). They break the safety rule, and Odyssey shows most of the benefit can be had without them.
- An LLM game master deciding outcomes. The server already knows the truth.
- Single-purpose actions or triggers for the owner's examples.
- Settlement-scale culture before individual settlers are convincing.

**Where the evidence is weak:**
- Most emergence claims (Project Sid, OMNI-EPIC, Concordia) are demonstrations with few runs and little statistics.
- Sid's results depended on GPT-4o.
- Several memory benchmarks are run by the vendors, against generous baselines.
- No study I found tests open-ended LLM agents on a live multiplayer server with real players over weeks.
- No 2025–2026 Minecraft "settler society" paper surfaced beyond MINDcraft and WISE (arXiv 2606.12852, single-agent).
- Our own eval harness will have to supply the evidence for this design.
