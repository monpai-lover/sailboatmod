# MineColonies Population and Workforce Integration Design

Date: 2026-06-12

## Goal

Add an optional MineColonies compatibility layer so Sailboat towns can display nearby MineColonies population and use nearby MineColonies workers as external construction workforce.

The first phase is deliberately read-mostly. MineColonies remains authoritative for its citizens, jobs, buildings, and AI. Sailboat reads snapshots and converts them into UI numbers and construction activity bonuses.

## Confirmed Scope

The approved first phase covers:

- Show MineColonies population, maximum population, citizen names, jobs, and happiness in Sailboat town/nation views.
- Associate a Sailboat town with one regional MineColonies colony by town core, town claim area, or explicit binding.
- Count nearby MineColonies workers as external workers for active Sailboat structure and road construction.
- Keep MineColonies optional. A server without MineColonies must still start and play normally.
- Never aggregate MineColonies population by player owner. MineColonies normally allows only one active owner colony; old abandoned colonies can still leave the player as an officer, so Sailboat towns must read only the one colony matched to that town's region.

Out of scope for the first phase:

- Do not directly control MineColonies citizen AI.
- Do not assign MineColonies citizens to Sailboat jobs.
- Do not write MineColonies citizens into `ResidentSavedData`.
- Do not mutate MineColonies buildings, work orders, citizen homes, jobs, permissions, or colony data.
- Do not add hard runtime dependency on MineColonies.

## API Feasibility

The installed MineColonies jar exposes the public API needed for this integration:

- `IColonyManager`: find colonies by world, position, owner, or list all colonies.
- `IColony`: read colony id, name, center, dimension, owner permissions, happiness, citizen manager, building manager, and work manager.
- `ICitizenManager`: read citizen list, current population, and maximum population.
- `ICitizenData`: read citizen id, name, gender, job, work building, last position, status, happiness handler, and entity reference.
- `IBuilding` and `IBuildingWorker`: read building position, type, level, assigned citizens, job name, and worker capability.
- `IJob`: read job registry entry, worker citizen, work building, building position, idling state, and activity counters.

This is enough for read-only snapshots and workforce detection. The API also exposes mutating methods, but this phase should not use them.

The bridge should use coordinate and region APIs as the primary lookup path:

- `getColonyByPosFromWorld` / `getIColony(level, pos)` for a concrete position.
- `IColony.isCoordInColony(level, pos)` to validate a candidate colony against a town core or claim sample.
- `getAllColonies` or `getColonies(level)` only for bounded scanning and cache refresh.

Do not use `getIColonyByOwner` for town population. It returns the single active owner colony and is not a correct town-region mapping, especially after a player abandons an old colony and remains an officer there.

## Approach Options

### Option A: Read-Only Snapshot Bridge

Create a `MineColoniesBridge` adapter that reads colony and citizen snapshots on a budgeted interval, then exposes plain Sailboat records to UI and construction systems.

Pros:

- Safest option.
- Keeps MineColonies optional.
- Avoids classloading failures when MineColonies is absent.
- Avoids corrupting either mod's persistent population model.
- Easy to cache and rate-limit.

Cons:

- MineColonies citizens are not true Sailboat residents.
- Sailboat cannot directly order MineColonies citizens to do specific jobs.

### Option B: Import MineColonies Citizens Into Sailboat Residents

Create or mirror `ResidentRecord` entries for MineColonies citizens.

Pros:

- Existing Sailboat UI and economy code would see one population list.
- More immersive if both systems should feel merged.

Cons:

- High risk of duplicate lifecycle bugs.
- Death, job, home, hunger, education, and ownership state can diverge.
- Hard to decide which mod owns the citizen.
- Rollback is hard once data is written.

### Option C: Full AI and Job Control

Use MineColonies citizens as controllable Sailboat workers by assigning jobs or manipulating MineColonies work orders.

Pros:

- Deepest gameplay integration.

Cons:

- Highest compatibility risk.
- Can break MineColonies expectations and player colonies.
- Requires detailed knowledge of MineColonies AI and request systems.
- Not appropriate until a stable read-only bridge exists.

Recommendation: implement Option A first. It gives the requested population and worker benefit without taking ownership of MineColonies internals.

## Architecture

Add a new optional integration package:

`com.monpai.sailboatmod.integration.minecolonies`

Core classes:

- `MineColoniesBridge`: Sailboat-facing interface.
- `NoopMineColoniesBridge`: default implementation when MineColonies is absent or disabled.
- `MineColoniesReflectionBridge` or `MineColoniesApiBridge`: loaded only when MineColonies is present.
- `MineColoniesSnapshotService`: owns cached snapshots and tick budget.
- `ExternalColonySnapshot`: immutable data for one MineColonies colony.
- `ExternalCitizenSnapshot`: immutable data for one MineColonies citizen.
- `ExternalWorkforceSnapshot`: immutable summary of workers near active Sailboat construction.
- `TownMineColoniesBinding`: persistent Sailboat town to MineColonies colony association.

The bridge should expose only Sailboat-owned data types. No caller outside the integration package should keep references to MineColonies API objects.

## Optional Loading Strategy

Use runtime detection:

- Check `ModList.get().isLoaded("minecolonies")`.
- If false, use `NoopMineColoniesBridge`.
- If true, initialize the bridge in a guarded startup path.
- Catch `ClassNotFoundException`, linkage errors, and reflective failures.
- Disable the bridge after repeated fatal integration errors instead of crashing the server.

Two implementation paths are acceptable:

- Reflection, matching the existing `BlueMapIntegration` pattern. This minimizes hard dependency risk.
- `compileOnly` against the MineColonies API if a stable artifact is available, with runtime checks to prevent classloading when absent.

Given the current project already uses reflection for BlueMap and MineColonies API artifacts may vary, reflection is the safer first implementation.

## Town Binding and Regional Population

A Sailboat town can be linked to exactly one MineColonies colony for population statistics. The binding must be regional, not owner-based.

Resolution order:

1. Explicit binding:
   - If a town has a stored MineColonies dimension id and colony id, resolve that exact colony.
   - Validate that the colony still exists and still intersects the town core or town claim area.
   - If validation fails, clear or ignore the binding until a new match is found.

2. Town core containment:
   - If the Sailboat town core is inside a MineColonies colony, bind to that colony.
   - This is the strongest automatic match because it maps one Sailboat town center to one MineColonies region.

3. Town claim overlap:
   - Sample the Sailboat town's managed claim chunks in the same dimension.
   - Count which MineColonies colony owns or contains those sample positions.
   - Bind to the single colony with the strongest overlap.
   - If two or more colonies overlap with similar weight, do not aggregate them. Treat the result as ambiguous and require explicit binding.

4. Nearby fallback:
   - If no overlap exists, optionally bind to the nearest colony within a small configurable radius.
   - Do not use this fallback if more than one colony is similarly close.

Even when a player has access to an old abandoned colony as an officer, a Sailboat town still only reads the colony selected by this resolution order. It must not sum colonies by mayor, nation leader, officer access, or any shared MineColonies membership.

The first implementation can use town core containment plus claim-overlap matching. Explicit binding can be added as the correction path for ambiguous layouts.

## Snapshot Data

`ExternalColonySnapshot` should contain:

- `source`: `minecolonies`
- `dimensionId`
- `colonyId`
- `name`
- `ownerUuid`
- `ownerName`
- `center`
- `population`
- `maxPopulation`
- `overallHappiness`
- `activeWorkerCount`
- `builderCount`
- `buildingCount`
- `lastRefreshGameTime`

`ExternalCitizenSnapshot` should contain:

- `sourceCitizenId`
- `displayName`
- `jobName`
- `workBuildingPos`
- `lastKnownPos`
- `child`
- `idle`
- `working`
- `statusText`

These snapshots are display and scoring data only. They must not be persisted as Sailboat residents.

## Town UI Integration

Extend the town overview building path so it can include local and external population:

- Local population remains `ResidentSavedData.countResidentsForTown(townId)`.
- MineColonies population is shown as an external population line for the one matched regional colony only.
- Combined population may be displayed as `local + external`, but internal economy logic should keep both values separate.
- Employment rate should either stay local-only or show a separate external workforce rate.

This prevents MineColonies population from silently changing Sailboat tax, culture, hunger, education, or resident systems.

Recommended UI labels:

- `Residents`: Sailboat residents.
- `MineColonies`: linked colony population.
- `External workers`: MineColonies workers currently helping construction.

The UI should show the matched colony name/id so the user can see which regional MineColonies colony is being counted for this town.

## Construction Workforce Integration

Use the existing Sailboat construction worker activity path:

- Active Sailboat jobs are already discovered by `StructureConstructionManager.findNearestSite`.
- Sailboat construction already counts workers through `reportWorkerActivity`.
- MineColonies workers can contribute by periodically reporting synthetic worker activity ids such as `minecolonies:<dimension>:<colonyId>:<citizenId>`.

Rules:

- Only count workers whose MineColonies entity or last known position is near the Sailboat construction site.
- Prefer MineColonies citizens with builder-like jobs or worker buildings.
- Do not teleport, retarget, pause, assign, or command the MineColonies citizen.
- Contribution is a bonus based on proximity and current work state, not AI ownership.
- Cap external contribution per job to avoid runaway progress, for example 2 to 4 effective workers.

This works for both structure and road construction because `reportWorkerActivity` already routes to active structure jobs or active road jobs based on job id.

## Tick Budget

The bridge must not scan all colonies and all citizens every tick.

Suggested budget:

- Refresh colony list every 5 to 10 seconds.
- Refresh linked town snapshots every 2 seconds.
- Refresh nearby construction workforce every 20 ticks while active construction exists.
- Limit citizens scanned per tick and resume next tick if a colony is large.
- Cache snapshots by dimension and colony id.
- Invalidate cache on server stop and world unload.

This follows the same watchdog-safe principle used in map and road tasks: staged work, bounded per-tick effort, and cached immutable snapshots.

## Error Handling

Failure policy:

- If MineColonies is absent, bridge is a no-op.
- If an API method is missing, log once and disable the bridge.
- If one colony snapshot fails, skip that colony for this refresh and keep the previous snapshot until it expires.
- If the bridge is disabled, UI falls back to local Sailboat resident counts.
- Never let MineColonies integration fail a server tick or prevent startup.

## Data Ownership

MineColonies owns:

- Citizens.
- Citizen jobs.
- Citizen homes.
- Colony buildings.
- Colony work orders.
- Colony permissions.

Sailboat owns:

- Town and nation records.
- Sailboat residents.
- Sailboat construction jobs.
- Display snapshots derived from MineColonies.
- Optional town-to-colony binding metadata.

The integration layer is the only boundary where MineColonies API objects are touched.

## Testing

Minimum validation:

- `compileJava` with MineColonies absent from the dev runtime.
- Dedicated server startup without MineColonies.
- Dedicated server startup with MineColonies installed.
- Open Sailboat town UI with no nearby MineColonies colony.
- Open Sailboat town UI near a MineColonies colony.
- Start Sailboat structure construction near MineColonies builders and verify bounded progress bonus.
- Start Sailboat road construction near MineColonies builders and verify bounded progress bonus.
- Confirm MineColonies citizens are not added to `ResidentSavedData`.
- Confirm server stop/start does not persist external citizens as Sailboat residents.

Regression checks:

- MineColonies absent must not cause classloading crashes.
- UI population numbers must distinguish local and external sources.
- Construction progress must not jump excessively when a large MineColonies colony is nearby.
- No watchdog-style full-colony scan should run inside a single server tick.

## Implementation Order

1. Add bridge interface, no-op implementation, and safe loader.
2. Add MineColonies snapshot records and cache service.
3. Add automatic town-to-colony lookup by core proximity.
4. Add town overview fields for external population and workforce.
5. Add UI display for external population.
6. Add budgeted workforce contribution into active construction jobs.
7. Add compile and startup verification.

## Open Decisions

- Whether explicit binding command should be included in the first implementation or left for a second pass.
- Whether combined population should affect any economy metric later. For this phase it should not.
- Whether MineColonies workers should only help if their colony owner or officer matches the Sailboat town mayor/nation permissions. The safe default is to require owner/member compatibility before granting construction bonus.
