# Story decisions: foundation, not a playable story engine

This fork is preparing a lore-independent story layer that can support authored campaigns and bounded AI proposals. A content pack supplies its own people, places, factions, chronology and decisions. The server, rather than a language model, decides whether an action is allowed and what actually changes in the world.

## Implemented in this patch

`com.minecolonies.api.story.StoryDecisionLedger` is an original, dependency-free Java 21 core. It records one durable-in-principle choice per namespaced choice ID, including the acting player, source ID, content version, event ID, game tick and ordered revision.

- `inspect` is read-only. Looking at an option must not choose it.
- An exact event retry returns `REPLAYED`. A changed request with the same event ID is rejected.
- A new event cannot repeat a completed choice or replace it with the opposite outcome.
- Expected revisions reject proposals made against earlier decision history.
- Separate campaign IDs prevent a request from being replayed into another campaign.
- Snapshots are immutable. Reconstruction rejects unsupported schemas, missing or duplicate records, out-of-order revisions and foreign campaign data.
- A full ledger refuses new choices instead of evicting old ones and making them repeatable.

A record is evidence of a choice only. It does **not** prove that a citizen was rescued, a delivery arrived, ownership changed or an effect was applied. Snapshot reconstruction is implemented; disk/NBT persistence is not. No runtime system currently calls this class.

## Run focused checks

```sh
./scripts/check-story-decisions.sh
```

This uses an installed Java 21 compiler and no downloaded dependencies. It tests only the Java core, including simultaneous opposing proposals and snapshot reconstruction. It is not a mod compilation, a Minecraft save/reload test, a multiplayer test or a graphical playthrough.

## Use MineColonies' existing systems

MineColonies already has data-driven dialogue objectives, quest triggers, quest completion persistence and effects for happiness, relationships, research, items and raid timing. The next adapter should extend those systems, not install a competing quest runner or replace citizen jobs.

Relevant existing code:

- `core/quests/QuestManager`: colony-scoped quest persistence and availability.
- `core/quests/QuestInstance`: objective progression and completion.
- `core/quests/QuestTemplate`: authored objectives and reward selection.
- `core/quests/rewards`: world/citizen effects already supported by MineColonies.
- `api/quests/registries/QuestRegistries`: data-driven extension points.
- `core/entity/citizen/EntityCitizen`: citizen interactions and normal job behavior.

## Required adapter contract

Before making this feature playable:

1. Persist a campaign ID and ledger with the correct world/colony scope. Add versioned NBT serialization, old-save migration and corrupt-save handling. Preserve a save's decisions if a content pack is missing or upgraded.
2. Resolve authenticated players and real citizen UUIDs on the server. Check colony permission, dimension, range, visibility, current quest state and current physical prerequisites. A ledger revision is not a substitute for checking inventories, actors or loaded terrain.
3. Read immutable snapshots for AI work off the server thread. Treat all generated dialogue/actions as proposals. Never let model output execute console commands, invent resource transfers or grant ownership.
4. Validate all effects before commit. Build a durable transaction/recovery mechanism across the decision and each external effect. The ledger alone does not make an item grant, relationship change or block edit atomic or exactly-once.
5. Record applied effects separately from intended effects. Replays must return the saved result instead of awarding effects again. Interrupted work must resume or compensate without inventing success.
6. Expose branch conditions to authored quest data. Keep past choices separate from current world state and from what any NPC actually knows. Private conversations are not global colony knowledge.
7. Arbitrate any requested movement/work interruption against MineColonies' job, sleep, combat and navigation systems. Preserve existing GUI and inventory controls.

Do not wire an unvalidated remote provider directly to `commit`. No provider, HTTP call, secret, paid service or external-data transmission is part of this patch.

## First playable acceptance scenario

Use an original neutral scenario, separate from any licensed or private lore: a settlement informant asks the player to protect or expose a report's source. Both options must be playable, and the recorded choice should lead to different, observable NPC knowledge and a bounded settlement consequence through actual MineColonies adapters.

The proof must show normal, canceled, repeated, contradictory and interrupted choices; missing/dead/unloaded actors; two competing players; save/reload; and provider failure. Inspecting evidence must not advance the story. A public report must contain only what that branch actually discloses. A selected option and a changed line of dialogue alone do not establish the requested world simulation.

## Content separation

Keep source canon, original adaptations and actual playthrough history distinct. Content IDs must remain stable across revisions. A fact's provenance and knowledge audience must survive retrieval. A public framework does not imply permission to publish third-party characters, assets or private campaign material with it.

This patch contains no campaign lore and changes no live worlds. The full mod still requires its normal Gradle build plus client/server validation before release.
