# Road Planner Vanilla Screen Redesign

Date: 2026-04-26
Branch: feature/road-planner-rebuild

## Decision

Replace the current Elementa-based RoadPlannerScreen with a vanilla Minecraft `Screen + GuiGraphics` implementation. Keep the existing backend models and services: road plan data, map snapshots/cache, road graph, editor packets, build queue, rollback ledger, demolition planner, and bridge backend selector.

## Problem

The Elementa MVP screen is not acceptable for gameplay:

- Buttons are not wired as native Minecraft widgets.
- Layout scales poorly across GUI scale and resolution.
- ESC/focus behavior conflicts with Minecraft's normal screen lifecycle.
- The map editor needs direct mouse dispatch for click, drag, release, scroll, and right-click context menus.

RoadWeaver and Recruits already solve these UI concerns with vanilla `Screen` rendering and event handling. The new planner should follow that pattern.

## Goals

1. Opening the Road Planner item shows the new vanilla RoadPlanner screen, never the old town-target UI.
2. The UI is visibly usable at common GUI scales.
3. All primary controls are clickable vanilla widgets.
4. ESC closes popups/context menus first, then closes the planner screen.
5. The central minimap canvas receives mouse drag/right-click/scroll events without Elementa focus conflicts.
6. RoadWeaver-style context menus and text input are implemented with vanilla `GuiGraphics` rendering.
7. Recruits-style world map structure is used for map canvas layout, panning, zooming, and layered rendering.

## Non-Goals For This Pass

- Do not rewrite backend map snapshot generation.
- Do not implement final real chunk rendering if current snapshot data is not ready.
- Do not replace build queue, rollback, graph, bridge, or packet models.
- Do not keep Elementa as a wrapper around the map.

## Architecture

### `RoadPlannerScreen`

`RoadPlannerScreen` becomes a vanilla `Screen`.

Responsibilities:

- Compute responsive layout rectangles in `init()` or a layout helper.
- Create vanilla `Button` widgets for tools and actions.
- Render background panels, map canvas, route overlays, status panel, context menu, and popups.
- Dispatch mouse input to popup, context menu, map canvas, then buttons/super.
- Dispatch keyboard input so ESC closes topmost overlay before closing the screen.
- Return `false` from `isPauseScreen()` so gameplay does not pause unnecessarily.

### `RoadPlannerScreenLayout`

A pure layout model computes:

- Toolbar rect
- Map rect
- Status/sidebar rect
- Bottom action rects
- Tool button rects

It should use current `width` and `height`, not fixed `1280x720` absolute coordinates.

### `RoadPlannerMapCanvas`

A pure-ish UI helper inspired by Recruits `WorldMapScreen`.

Responsibilities:

- Render map background/grid/snapshot placeholder.
- Convert GUI mouse coordinates to world X/Z through existing `RoadPlannerMapComponent` / `RoadMapRegion`.
- Handle drag drawing for road nodes.
- Handle right-click road hit testing through `RoadPlannerMapInteraction`.
- Handle scroll zoom/pan state later.

### `RoadPlannerContextMenu`

Vanilla implementation based on RoadWeaver `ContextMenu`:

- `open(x, y)`
- `close()`
- `render(GuiGraphics, Font, mouseX, mouseY, screenW, screenH)`
- `mouseClicked(mouseX, mouseY, button)`
- Hover index, disabled items, separators, screen clamping.

It should use existing action enum and packets:

- Rename road
- Edit nodes
- Demolish edge
- Demolish branch
- Connect town
- View rollback ledger

### `RoadPlannerTextInputScreen`

Vanilla modal text input based on RoadWeaver `SimpleTextInputScreen`.

Used for:

- Rename road now.
- Future town connection/name fields.

Enter submits, ESC cancels and returns to parent planner screen.

## Data Flow

1. `RoadPlannerItem` sends `OpenRoadPlannerScreenPacket`.
2. Packet calls `RoadPlannerClientHooks.openNewPlannerEntry(sessionId)`.
3. Hook opens vanilla `RoadPlannerScreen`.
4. User clicks/drag map canvas to add nodes.
5. User right-clicks existing route line.
6. Screen asks `RoadPlannerMapInteraction` for hit test and opens context menu.
7. Rename/demolish actions send existing editor packets.
8. Confirm build uses existing confirm/build queue path.

## Error Handling

- If map snapshot is not ready, render a visible placeholder: "真实地形快照加载中".
- If no destination is set, show warning in sidebar and disable Confirm Build.
- If right-click misses any road edge, close context menu and do nothing.
- If ESC is pressed while context menu or text input is open, close only that overlay.
- If packet send cannot happen client-side, show a local warning line rather than crashing.

## Testing Plan

Pure tests:

- Layout scales toolbar/map/sidebar within screen bounds for 1280x720, 1920x1080, and smaller GUI sizes.
- ESC handling closes context menu before closing screen.
- Context menu layout clamps to screen bounds.
- Context menu click triggers correct action and ignores disabled items.
- Map canvas converts mouse coordinate to world coordinate using existing region math.

Compile validation:

- `./gradlew.bat test --tests "*RoadPlanner*" --tests "*RoadWeaverStyle*"`
- `./gradlew.bat compileJava`
- `./gradlew.bat assemble`

## Rollout

1. Add vanilla layout/context/menu tests.
2. Replace Elementa `RoadPlannerScreen` superclass and rendering.
3. Add vanilla context menu implementation.
4. Add vanilla text input screen for rename.
5. Wire tool/action buttons to client state and packets where safe.
6. Compile, run targeted tests, assemble jar.

## Manual QA Checklist

- Open Road Planner item: new vanilla planner appears.
- Buttons visibly highlight/click.
- ESC closes menu first, then screen.
- GUI scale changes preserve layout.
- Right-click menu looks like RoadWeaver style.
- Rename opens text box and returns to planner.
- Confirm button is visible but disabled/warned until requirements are ready.
