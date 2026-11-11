# Road corridor geometry

Replaces the road planner's route smoothing, centreline, height profile, surface and bridge
emitters with one pipeline in `roadplanner/corridor/`. Protocol and saved data are unchanged.

## What was wrong

- The search moves on an eight-direction grid and the old smoother barely rounded its output, so
  routes were chains of 45 degree doglegs.
- The centreline was a rounded-integer polyline and the road width was laid along one of eight
  directions, so bends were faceted.
- A long road is built in stages. Each stage kept only the columns in front of its own leg, which
  left a wedge of missing blocks on the outside of every bend, and each stage smoothed heights from
  its own short context, so neighbouring stages could disagree where they met.
- Bridges were a separate emitter joined to the road by special-case ramps.
- Walls and fences were placed as bare posts and never joined on one side.

## Pipeline

| Step | Class | What it does |
|---|---|---|
| Route | `RoadRouteRefiner` | Pulls the searched path taut wherever a straight line crosses no more water, climbs no more and is no steeper than the stretch it replaces, sweeps a curve through each remaining corner, and lays nodes at even spacing. |
| Plan | `RoadAlignment` | Centreline at a point is the average of the node polyline over 16 blocks either side. A lone corner becomes a tangent parabolic arc; dense or sparse nodes give the same curve. |
| Profile | `RoadCorridorProfile` | Spans narrow dips, cuts narrow humps, limits grade to 1 in 3, rounds every change of grade, then raises the line where boats need headroom. |
| Blocks | `RoadCorridorCompiler` | Assigns every column near the road to its nearest centreline point and builds it from that point's height and support. |

The road is a single ribbon; only its support changes along the way:

- **On the ground**: surface flush with the terrain, half slabs for half steps, fill beneath, and
  verges graded 1:1 into the surroundings and dressed with the local ground block.
- **On a deck**: used over water, or wherever the road stands 3 or more blocks clear of the ground.
  Two-block deck, parapet walls, piers every 16 blocks with the deck underside stepping down toward
  each so they read as arches.
- **Dips up to 28 blocks wide** are crossed level at rim height, which is what produces viaducts.

## Water

| Crossing | Road height above the water surface |
|---|---|
| Water narrower than 25 blocks along the road | 2 (3 on a leg marked as a bridge) |
| Wider water, from 4 blocks off each shore | 8 |
| Any water on a leg marked `BRIDGE_MAJOR` | 8 |

At 8 the deck leaves 6 clear blocks at the crown of each span and 5 across its middle ten blocks.
The climb to that height is a ramp at the grade limit, so it begins on the approaches.

A leg marked as a bridge also holds the straight line between its end nodes, so a dry valley too
wide to be spanned automatically is crossed level. A tunnel leg holds the same line underground.
`RoadPlannerRouteExpander` now keeps that line when it re-interpolates such legs on the client.

## Staged building

Every step above looks only a fixed distance along the route (`RoadCorridorCompiler.CONTEXT`, 116
blocks). `RoadPlannerStagedPlan.HALO_BLOCKS` is 120, so a stage compiled alone produces exactly the
blocks the whole road compiled at once would produce there. A column belongs to the stage that owns
its nearest centreline position, so each column is built once and bends have no gaps.

Consequences:

- `RoadPlannerStageCompiler` is stateless. The cross-stage bridge reference, and the checks that
  cancelled a build when real banks differed from the preview, are gone; a stage follows the terrain
  it finds.
- A long road over water no longer has to be marked as a bridge before it can be previewed.
- Consecutive short legs of one type now share a stage of up to 128 blocks, which offsets the
  larger halo for generated routes (previously one stage per 24 block leg).

## Limits

- The grade limit is exact for climbs of up to about 16 blocks within 50 blocks of route. A taller
  face is still crossed but more steeply, and steps of a full block can then appear.
- A corner with legs shorter than the smoothing radius is rounded less; neighbouring surface blocks
  can differ by a full block where such a corner coincides with a steep grade.
- The route refiner removes detours the grid forced on the search. It does not find a different
  route: where the search avoided water or a hill, the refined route still does.
- Judging shortcuts against terrain has its own 6 second allowance inside the 45 second request.
  If it runs out the route is still smoothed, but by distance from the searched path alone.
- Channel width is measured along the road, so a road running along a river is not treated as
  crossing a channel.
- Piers deeper than 96 blocks stop short of the bed.
- Wall and fence joining happens when a step is executed, so preview ghosts still show posts.

## Verification

Offline only; no client or dedicated server was run.

- `RoadPlannerStageCompilerTest`: a ten node route with hills, a river, sharp corners and a marked
  bridge, built stage by stage, equals the same route built at once, block for block, with no
  position or column written by two stages and no terrain read outside a stage's own chunks.
- `RoadCorridorCompilerTest`: no gaps in the surface at widths 3, 5 and 7; neighbouring surface
  blocks differ by at most half a block; headroom clear and surface supported; decks stay above
  water; piers stand on the bed; bridge edges are closed.
- `RoadAlignmentTest`, `RoadCorridorProfileTest`: window results equal whole-route results
  exactly; straights are unmoved; a route re-interpolated every 8 blocks keeps its shape.
- `RoadRouteRefinerTest`: grid doglegs collapse to a line; detours around a lake and a hill survive.
- `RoadCorridorGalleryTest` and `RoadRouteGalleryTest` render plans, profiles and isometric views to
  `build/road-evidence/`. The second runs the real search between two towns on synthetic terrain.

Synthetic heightmaps are not a live world: trees, caves, existing builds, claims and the client's
terrain estimate are not exercised.

## Removed

`BridgeStructureEmitter`, `BridgeTransitionProfile`, `BridgeEmissionResult`, `BridgeTemplateProvider`,
`RoadPlannerBridgeGeometryPlanner`, `RoadPlannerBridgeProfile`, `RoadSurfaceStepEmitter`,
`RoadFootprintPlanner`, `RoadBandRasterizer`, `CenterlineSegmentLocator`, `RoadCenterlineBuilder`,
`RoadHeightProfileSmoother`, `RoadTerrainSampler.BridgeWindow`, and their tests.
