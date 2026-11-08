# Autonav hotfix4: exact endpoints versus cruise clearance

## Report and evidence limits

The user reported `WaterPath` search success followed by `[navfix3-shore] output=0 moved=0 repairedEdges=0`, even while the ship was in open water. The old refiner checks both the origin and the final destination before moving intermediate points or repairing edges. A terminal failure can therefore reject a voyage originating far out at sea.

The old log does not include endpoint coordinates, rejection stage, or water heights. It does not establish which endpoint or check rejected this particular live request. Do not describe the user's current position as obstructed based on this message alone.

## Reproduced defect

Old endpoint checks use a 3x3 footprint around `floor(x/z)+0.5` rather than the standard two-block hull at the exact endpoint. This can reject a valid endpoint at an integer coordinate along a bank.

A minimal synthetic example (water surface interface Y=65):

- All columns with x>=0 are dry; x<0 is water.
- Start=(-20.5,65,0.5), destination=(-1,65,20.5).
- The physical hull at the destination spans x=[-2,0], touching but not penetrating the bank.
- Old hotfix3 returns an empty path. The exact-endpoint hotfix4 refiner returns a verified departure/cruise/arrival path and preserves both exact endpoints.

The same issue was reproduced on another bank orientation, a corner, a near-bank departure and a translated coordinate system. This is a confirmed regression class, not a claim that these synthetic coordinates or terrain are the user's live world.

## Change

`ShoreRouteRefiner` now separately checks precise endpoint footprints, selects a safe cruise anchor within three blocks at each endpoint, refines the cruise path using the existing symmetric 5x5 footprint, and sweeps every returned edge. Only the final short arrival segment may enter the extra shore reserve; it still has to pass physical collision checks. Initial departures use the existing monotonic escape/clearance policy. Public routes and exact endpoints are unchanged.

Actual occupied endpoints, mismatched water heights, unavailable terrain and inadequate cruise clearance still fail. No unknown cell is treated as water; no server-thread chunk generation is added. The synthetic planning hull represents the standard two-block footprint; the running vessel's existing real collision gate is unchanged.

New `[navfix4-shore]` records include `stage`, `reason`, `detail`, `start`, `end`, and `waterY`. `START_FOOTPRINT` and `END_FOOTPRINT` distinguish the endpoints; `START_DEPARTURE` and `END_APPROACH` distinguish missing safe short transitions; `CRUISE`, `FINAL_SWEEP`, `TIMEOUT`, and height diagnostics distinguish other failures. Navigation steering logs may still say `navfix3`: that controller was not changed in hotfix4.

## Executed offline validation

- 39 new endpoint assertions.
- 448 bank-orientation, coordinate-translation and fractional-position combinations; returned edges are checked with the collision helper bytecode.
- Re-ran the existing 35 base checks, 42 steering checks (including 130 simplified motion scenarios), 12 wall scenarios, 24 shore checks and 3 ship-menu method integration checks.
- In eight old/new endpoint examples, old hotfix3 rejected five legal cases. Hotfix4 passed all eight.
- Built the helper with JDK21 and reran under `-Xverify:all` using synthetic terrain/world adapters and the relevant original JAR classes.
- Reproducible build output SHA-256: `c658029e2e9cca4ad3a4a8f9a889ae87192f976e1b7763c889095e573cc8446f`.
- Binary package changes only `ShoreRouteRefiner.class` and `ShoreRouteRefiner$1.class`; 2501 other entries are identical to hotfix3, with no added or removed entries. The JAR is not uploaded to the repository.
- This source file's Git blob is `b88d4aa84edfb2d2762d6ce9052e6111f76062d4`, verified equal to the locally compiled helper source.

These checks are not a complete NeoForge build or live Tellus/Sable server test. The entire draft source-port branch still needs that integration validation before merging. Stop the old voyage and reselect its destination on a backed-up test world; an existing voyage is not automatically recalculated. If rejection remains, inspect the complete new diagnostic line instead of assuming a collision at the ship's current position.
