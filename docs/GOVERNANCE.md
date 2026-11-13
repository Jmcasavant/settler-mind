# Free will, law and self-government

Status: **direction set by the owner (2026-11-13); open decisions at the end.** This is the purpose of
the mod. Every other surface follows from it: AGENTS.md, docs/HARNESS.md, docs/MIND.md, the README, and
in time the settlers' own prompts.

## The point of the mod

Settlers are free to think and act. They're limited the way people are: by the nature of the world,
by a small charter that protects real people, and by laws and permits. They can also obtain those
permits on their own, and their towns write those laws themselves. We build the conditions for a
society, not its contents.

## The layers

| Layer | What it is | Set by | Settlers can change it? | Enforced by |
|---|---|---|---|---|
| 1. Nature | What Minecraft physically allows | The game | No | The game |
| 2. Charter | Minimal protections for real people and their worlds (below) | The server admin, in config | No; they may petition the admin | Our validator, fail closed |
| 3. Settlement law | Rules, offices, taxes, sanctions, and the rules for making rules | Each settlement or town | Yes, by their own process | Mainly the settlers (socially); mechanically only for a town's own members in its own territory, and only as restrictions |
| 4. Rights and permits | Access, roles, allowances, delegated powers | Whoever holds the thing (player, settler, town) | Yes: ask, trade, agree, earn | The validator checks a permit exists; holders grant and revoke |
| 5. Choice | What to do now | The settler | Always | Nothing: comply, negotiate, protest, leave, break a town law and face its consequences |

### The charter (layer 2): small on purpose

These limits protect humans, so no settler or town can change them:
1. Model output never runs code or commands (AGENTS.md rule 1).
2. Players' privacy: a player's words leave the server, or go into public text, only with their consent.
3. A **player's** claims, property and settlers are theirs. Using them needs that player's permit.
4. Settlers never harm players.
5. The admin's limits: budgets, world-change caps, switches, pause and dismiss.
6. Everything settlers change in the world is journaled and reversible.

Everything else is open: what settlers do, what they build, and what rules their towns adopt.

### Permits (layer 4): limits you can get past legitimately

- **A refusal says what's needed.** When the validator refuses an action, it names a path when one exists:
  "this chest is Alex's; it needs Alex's permission". It says "never" only for the charter. The settler
  decides whether to ask, how to ask, or to look elsewhere.
- **Grants are unmistakable:**
  - a player grants or revokes with a click or a command, never through a model reading chat;
  - a settler grants for what it holds;
  - a town grants for what it holds, according to its own law (for example "the council may grant
    building permits in town").
- **Every permit records:**
  - holder and grantee;
  - scope: thing, area, action;
  - conditions, such as "in exchange for 8 bread";
  - expiry;
  - revocability.

  It is journaled and listed for admins.
- **Permits can be traded**, if the grantor allows it. Rights become goods, and an economy can form
  around them.

### Settlement law (layer 3): towns govern themselves

- **Founding:** settlers found a settlement (a name, members, territory) by agreement among themselves.
  Several can coexist. Towns and cities are just settlements that grew.
- **The rules for making rules are theirs too.** Rule by a chief, a council, a majority, or consensus.
  The harness has no built-in government. A new settlement starts with one default: a proposal binds the
  members who endorsed it. Everything else they invent, including how to change that default.
- **Law is text plus a record:** who proposed it, how it was adopted (under their own meta-rule), who's
  bound, and evidence of compliance and breaches.
- **Enforcement is mostly social, as with people.** Towns choose their own sanctions within the
  charter: fines in items, losing permits or offices, refusing trade, exile from the commons.
  Mechanical enforcement is opt-in per law. A town can ask the validator to enforce a law that
  *restricts* its own members in its own territory ("no breaking blocks in the square"). It can't
  enforce anything that grants powers, and it can't bind players who haven't joined that town.
- **Between towns:** treaties, trade, borders, alliances, rivalry. They're agreements between
  settlements, and nothing here scripts them.

## Gaps

Here's what's missing for this to work, grouped. Each one says what fills it, and none fills it with a
scripted behaviour.

**Self-government**
1. *Membership and identity:* who belongs to a settlement, joining and leaving, and whether players can
   be citizens. **Fill:** membership as agreement records; players join by an explicit command.
2. *Territory:* settlers own no land today. Claims belong to players (Open Parties and Claims on
   Homestead). **Fill:** settlement territory comes from an admin or player grant, which is a permit
   from humans. Founding on unclaimed land is an owner decision below. It needs a claims adapter.
3. *Law-making and records:* the agreement store (HARNESS §6) has to grow into versioned laws with an
   adoption record under each town's own meta-rule.
4. *Enforcement hooks:* opt-in mechanical enforcement of restrictive town laws in the validator, and
   sanctions as permit changes or item transfers.
5. *Disputes:* who judges whether a law was broken. **Fill:** that's theirs too (a judge, a vote, the
   chief). The harness only provides evidence (journal and memories) that they can cite.
6. *Petitions to the admin:* a channel for settlers to ask for charter-level changes (more budget, land).
   The admin decides.

**Players in the society**
7. *Owner authority vs free will vs town law.* Today an owner can order a settler around with commands.
   **Direction:** owners keep the charter powers (pause, dismiss, budget). Requests are just requests,
   which settlers already may refuse. Command-style orders become requests, or stay only as an admin
   debugging tool.
8. *Manipulation:* players can write signs, books, notices and proposed laws that settlers read, which
   makes those a prompt-injection surface. **Fill:** all text from the world is framed as someone's
   words with its source. Nothing a settler reads can grant a power, only the permit system can.
9. *Players gaming the permit system:* grant spam, permits with abusive conditions. **Fill:** rate
   limits, a cap on open permits per grantor, and settlers judging offers by relationship and
   reputation.

**Economy and property**
10. *What settlers own:* items, chests they placed, buildings they made, and what happens to it when
    they leave or die. Today dismissal drops everything. **Fill:** ownership records with each artefact,
    and a will or a town law decides who inherits.
11. *Trustworthy exchange:* `give` is one-way. Trade needs an atomic swap (offer and accept), or deals
    rely on trust alone. Contracts are permits with conditions.
12. *Scarcity:* the world-change budget and model-call budget are real limits. They should be visible
    to settlers as allowances, which can themselves be granted or traded.

**Mind and body**
13. *Long projects:* building a house or running an office takes days. Memory forgets. **Fill:** a
    project or commitment memory that persists until it's done or abandoned.
14. *Space and travel:* `go_to` is capped at 32 blocks, and there are no maps or named places. Towns
    elsewhere are unreachable. **Fill:** places as beliefs, long-distance travel as a verified skill, and
    maps as written artefacts.
15. *Offscreen life:* settlers far from players sit in unloaded chunks, so their world freezes. Other
    towns would never live. **Fill:** an owner decision below (bounded chunk loading vs. an abstract
    simulation while unloaded).
16. *Building at scale:* placing one block per model call is impossibly expensive. **Fill:** blueprints
    as validated data, which a settler designs or learns from a book, then built by a routine
    without model calls.
17. *Body skills:* only walk, mine, eat and board steps exist today. The generic verbs need executors
    for use, place, craft and give, each verified in-world. This is the largest engineering cost.
18. *Communication at distance:* speech is local. Letters carried by hand, signs and messengers can
    cover distance, and that's emergent.

**Population and cost**
19. *Where new settlers come from:* players summon them today (max 4 each). Towns and cities need more.
    **Fill:** an owner decision below. Cost grows with population.
20. *Cost at town scale:* dozens of settlers at about $0.90 an hour per 4 is too much. **Fill:** HARNESS §7
    tiers, routines, a wake budget, a local-model provider, and a server-wide budget that settlers
    themselves experience as scarcity.

**Safety, operations and evidence**
21. *Admin tools:* see laws, permits, journals and agreements; revoke and undo; pause a town.
22. *Text moderation:* laws, signs and books are public. The text policy needs a decision on content
    standards for the server's audience.
23. *Save data:* beliefs, laws, permits and settlements are new saved data. Version them from day one;
    names in saves are permanent (AGENTS.md "Naming").
24. *Evidence:* nothing in the research covers this at weeks-long, multiplayer scale. Evals must
    measure self-government:
    - laws adopted, kept, broken and amended;
    - permits sought and granted;
    - sanctions applied, and whether they were applied consistently;
    - relations between towns.
    Multi-day simulations come first; the live server comes much later.
25. *Homestead specifics:* the Open Parties and Claims adapter, and affordances for modded blocks, before
    anything runs on a clone of the pack.

## Decisions for the owner

1. **Conflict:** may settlers and towns be hostile to each other, and how far? Recommendation:
   - never violence against players (charter);
   - between settlers and towns, non-violent sanctions only at first (fines, exile, refusing trade,
     borders);
   - revisit violence later as a server setting.
2. **Players and town law:** recommendation: town law binds a player only if that player joins the town,
   and only socially. Mechanical enforcement never applies to players.
3. **Land for new towns:** recommendation: settlements get territory by grant from the admin or a
   player. Founding on unclaimed land far from player claims is a server setting, off by default.
4. **New settlers:** recommendation: players still summon settlers. Towns may also "attract newcomers"
   (the server spawns a settler) up to a server cap, set by the admin, so cost stays under control.
5. **Offscreen life:** recommendation: an abstract daily summary for unloaded settlers (what they
   plausibly did, from their routines and goals, applied when the area loads), not chunk loading.
   Chunk loading costs server performance on a real modpack.
6. **Owner commands:** recommendation: `/settler mine`-style orders become requests the settler may
   refuse. Keep pause and dismiss as charter powers, and keep a debug override for admins only.
