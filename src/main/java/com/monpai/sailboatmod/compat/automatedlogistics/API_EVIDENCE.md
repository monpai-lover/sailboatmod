# Hybrid flight custody: verified boundary and required extension

Status: **not connected**. The current release APIs do not supply the durable, exclusive
custody transaction required to switch an FC-controlled aircraft to virtual travel safely.
No storage/movement backend is installed by this document. Keep FC flight and the aircraft's
loading ticket active. Do not call `confirmStored`, debit virtual fuel, advance virtual distance,
or settle delivery merely because no player is nearby or a Sable body is temporarily missing.
Suggested diagnostic: `hybrid_unavailable:sable_custody_transaction_missing`.

## Inspected artifacts

- Automated Logistics 0.7.0, Modrinth version `ESwTkggq`, official artifact
  `create_aeronautics_automated_logistics-0.7.0.jar`, SHA-1
  `0da40850bdb4f3eb18340e6adb466d2af5fba50f`.
- Sable NeoForge 1.21.1 **2.0.6** release JAR.
- Flight Control 0.8.3, version `FSr0O2k8`; see the sibling FC API evidence document.

Evidence is public `javap` signatures and selected bytecode paths. No upstream source or
implementation has been copied into Sailboat. No live storage, crash, or restoration test ran.
These findings describe these artifacts, not every future version of the mods.

## Existing APIs that can be used

Sable's public API already contains the required low-level representation operations:

| Public class | Public methods | Verified behavior / limit |
| --- | --- | --- |
| `SubLevelSerializer` | `toData(ServerSubLevel,List<UUID>)`, `fromData(CompoundTag)`, `fullyLoad(ServerLevel,SubLevelData)` | Serializes the real plot, pose, UUID, loading dependencies, and motion; restores a real body. Not an ownership transaction. |
| `SubLevelHoldingChunkMap` | `moveToUnloaded(ServerSubLevel,ChunkPos)`, `getHoldingSubLevel(UUID)`, `saveAll()`, `snatchAndLoad(GlobalSavedSubLevelPointer,UUID)`, `loadHoldingSubLevel(HoldingSubLevel)` | Native holding-store operations; no durable custody token or auto-load reservation. |
| `SubLevelStorage` | `attemptSaveSubLevel(...)`, `attemptSaveHoldingChunk(...)`, `flush()` | The attempt methods catch IOExceptions internally; `saveAll()` also catches its flush failure. A normal return does not establish durable commit. |
| `SubLevelContainer` | `getSubLevel(UUID)`, `getAllSubLevels()`, `removeSubLevel(SubLevel,SubLevelRemovalReason)`, `processSubLevelRemovals()`, `addObserver(SubLevelObserver)` | Native lifecycle. Observers are notifications, not a veto/reservation hook before allocation. |
| `SubLevelHelper` | `getLoadingDependencyChain(ServerSubLevel)`, `getConnectedChain(SubLevel)` | The entire connected/loading group must be accounted for; a registrar UUID alone does not identify every body being removed. |
| `RigidBodyHandle` | `getLinearVelocity`, `getAngularVelocity`, `addLinearAndAngularVelocity`, `teleport`, `isValid` | One-time pose/momentum restoration is possible. These operations must never become the physical autopilot. |
| `ServerSubLevel` | `getTrackingPlayers`, `getLastSerializationPointer`, `getUserDataTag`, `setUserDataTag` | Useful identity/visibility data, not a durable exclusive-storage acknowledgment. |

Class prefixes: `dev.ryanhcode.sable.sublevel.storage.serialization` for serializer/storage/data,
`dev.ryanhcode.sable.sublevel.storage.holding` for holding types,
`dev.ryanhcode.sable.api.sublevel` for containers/observers,
`dev.ryanhcode.sable.api` for SubLevelHelper,
`dev.ryanhcode.sable.api.physics.handle` for RigidBodyHandle,
and `dev.ryanhcode.sable.sublevel` for ServerSubLevel.

Sable serialization changes the serialized pose rotation point to the ship's self center of mass
and transforms that center into the serialized position. FC's logical-pose origin can therefore
change across serialization even when the world-space blocks do not move. The restoration request
must define an unambiguous stable local anchor and world transform; do not reuse a stale
`FC origin - bounds.minY` offset after reload.

Sable serializes linear velocity only above its threshold and angular velocity only above its
threshold. `fullyLoad` multiplies both stored velocity vectors by
`SableConfig.VELOCITY_RETAINED_ON_LOAD`. Therefore a hybrid snapshot needs exact, finite motion
values captured with the body transform; it cannot rely on the ordinary unload NBT alone for
fixed-wing flight. A custody extension may restore precise momentum once, before publishing the
body to tracking and resuming FC. It must not alter the global Sable configuration.

## Why AL materialization is not a flight recovery backend

The 0.7.0 release really contains `DrivePlaybackMode.PHYSICAL` and `MODULE_OUTPUT`;
the latter drives AL's own modules and is not an FC lease integration.
`VehicleRoutePlaybackService.tickUnloadedTransit` and its runtime transition are private.
Starting an AL route would introduce a separate motion authority.

Public `SableStoredShipRepository.lookup(server,Optional<UUID>,dimension,shipId)` and
`StoredBodyLookupResult` distinguish `VERIFIED_SINGLE`, `VERIFIED_POINTER`, `AMBIGUOUS`,
`NOT_FOUND`, `READ_FAILED`, and `NOT_READY`. A readable candidate and its pointer are useful
recovery evidence, but they do not confer ownership or prevent native restoration.

`SableStoredShipMaterializer.materialize(Request)` accepts server, optional transponder ID,
dimension, ship UUID, local controller position, target controller position, description,
and optional expected storage pointer. After native `snatchAndLoad`, it resolves the controller,
calls `relocate(target,Optional.empty())`, then `hold(target,Optional.empty())`.
`SableSubLevelVehicleController.relocate` calls `stop` first; `stop` cancels both velocities,
then `relocate` teleports. There is no request field for a flight-ready velocity/heading.

This is unsuitable for an airborne fixed wing. Ground restoration at a verified runway might be
safe for a separate airport-to-airport storage workflow, but it does not satisfy early physical
takeover when a player approaches a virtual aircraft in flight. It also leaves the custody and
fuel-accounting gaps unresolved. Do not silently label this behavior seamless hybrid flight.

## Exact missing guarantees

1. **Persistent reservation and exclusive ownership.** `moveToUnloaded` serializes each body in
   the loading chain, adds it to holding storage, removes it with `UNLOADED`, and finally marks
   the holding chunk dirty. It is not an atomic group transaction. No public reservation prevents
   `processChanges`, chunk load, or another materializer from restoring the same stored body.
2. **Durable success or explicit failure.** Ordinary `saveAll`/`attemptSave*` return paths cannot
   establish whether all data and indexes reached disk. Merely calling `flush` later cannot recover
   an IO failure already caught by an earlier save operation.
3. **An acknowledged physics boundary.** There is no public per-group transaction acknowledgment
   combining stable pose/motion capture, completed physics removal, and durable storage commit.
   `isRemoved()`/missing lookup by itself is not a persistence or physics acknowledgment.
4. **Idempotent, fenced recovery.** There is no token/generation compare-and-set that consumes
   storage ownership exactly once and publishes a fully restored group with exact motion only after
   the target's loaded clearance/visibility validation and FC binding restoration are ready.
5. **Offline fuel debits.** AL does not expose a general FC-aircraft fuel debit transaction against
   the stored body's actual engines/inventory. FC cruise progress must not create free fuel.
   Unsupported fuel/hardware must reject virtual travel until an explicit energy policy is supplied.

## Minimum extension contract (proposed, not existing methods)

Implement this at the Sable storage/lifecycle boundary, either upstream or as a version-pinned
compatibility extension. This is a proposed contract, not a reflection API to guess at runtime:

```text
prepareStore(ownerMission, expectedGeneration, structureGroup, controlFence) -> Preparation
commitStore(preparation) -> StoredReceipt | ExplicitFailure
inspect(receipt) -> STORED | RESTORING | LIVE | AMBIGUOUS | IO_FAILED
debitFuel(receipt, expectedGeneration, idempotencyKey, requestedDistance) -> AuthorizedDistance
restore(receipt, expectedGeneration, recoveryTransform, exactMotion, clearancePermit)
    -> RestoredReceipt | ExplicitFailure
reconcile(ownerMission, expectedGeneration) -> AuthoritativeRepresentation
```

`StoredReceipt` must contain the mission/owner ID, generation, every body UUID, checksums/schema,
canonical storage handles, and a durable commit identity. It is returned only after the entire
group is absent from physics/tracking and storage has committed. The persistent reservation must
block all native auto-load and alternate restore entry points, including after server restart.
No second inventory should be created by the mission journal.

`restore` validates finite transform/motion and stable local anchor, loaded clear volume and
destination ownership, then restores the group behind a physics/tracking fence. Exact motion is
set once as part of this representation transition. It returns only when the physical group is
authoritative, storage ownership is consumed, and repeat calls return the same result. On partial
failure, `inspect/reconcile` reports the real authority; callers must never blindly retry loading.
An unsupported dependency, passenger/entity group, or uncontrolled contraption must fail preflight
before FC is paused. Support can initially be restricted to a verified single-body aircraft.

FC does **not** necessarily need an upstream patch for this extension. Its public
`NavigationControlCommitFence.withWriteLock(...)` and real terminal revision-checked actions can
disable automatic enablement and release the lease. The Sable custody acknowledgment must then
prove that no physical group remains able to receive further forces. Never infer this from FC's
`isContributing()` (which always returns false for the terminal). Lock ordering between FC's commit
fence and the new Sable physics boundary must be explicit and tested; do not hold a thread-local
scope across asynchronous ticks.

## Recovery and verification requirements

- Keep the aircraft physical if storage preflight cannot establish all required guarantees.
- Never PAUSE an airborne fixed wing because it is distant or terrain is unknown. Keep flight and
  loading tickets active; warn and replan toward verified space if that path can be established.
- Request recovery before any player can enter the visible/interacting radius, accounting for
  player and aircraft speed, chunk loading latency, and recovery time. If recovery is not ready,
  freeze virtual progress; never fabricate arrival. At landing, reserve/validate the real airport.
- Test crash/restart after every persistence and removal boundary, IO failure, two-body partial
  failure, duplicate restore, player/forced chunk reload during custody, changing control revisions,
  incompatible fuel hardware, and a blocked recovery volume. Check inventory and UUID conservation.
- Test all three aircraft types with real FC hardware. Fixed wing must retain controllable heading
  and airspeed at the first resumed physics step. Ground fallback must be explicit and cannot be
  reported as an airborne takeover.

Until those tests pass against an implemented extension, the front end must report hybrid flight
unavailable. The current Sailboat mission journal is useful bookkeeping, not proof of storage.
