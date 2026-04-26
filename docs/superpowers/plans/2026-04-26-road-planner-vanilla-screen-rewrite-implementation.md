# Road Planner Vanilla Screen Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the broken Elementa road planner UI with a vanilla Minecraft `Screen + GuiGraphics` planner and close the audited functional gaps for destination, right-click editing, and editor packet handling.

**Architecture:** Keep current backend models and services. Replace only the client UI/event layer with vanilla `Screen`, map canvas, RoadWeaver-style context menu, and text input. Add small server-side editor/session services so UI actions are not packet stubs.

**Tech Stack:** Java 17, Forge 47.2.0, Minecraft 1.20.1, vanilla `Screen`, `GuiGraphics`, `Button`, JUnit 5, Gradle.

---

## Execution Notes

- Worktree: `F:\Codex\sailboatmod\.worktrees\road-planner-rebuild`.
- Run Gradle with: `$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'`.
- Use `apply_patch` only for edits.
- User requested inline execution, not subagents.
- Commit after each task.
- Existing full `build` caveat: `CarriageRoutePlannerTest > planReturnsConnectorRoadConnectorSegments()` may fail with an unrelated NPE. Use `assemble` for jar if full `build` fails at tests.

## File Structure

### Client vanilla UI

- Replace: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
  - Vanilla `Screen`, not Elementa `WindowScreen`.
  - Owns buttons, map canvas, context menu, popup state, ESC handling.
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerVanillaLayout.java`
  - Pure responsive rect calculation from screen width/height.
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java`
  - Map render and input dispatch helper.
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerVanillaContextMenu.java`
  - RoadWeaver-style vanilla context menu.
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTextInputScreen.java`
  - Vanilla text input screen for rename.

### Destination/session/editor services

- Modify/Create: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerDestinationService.java`
  - Add per-player destination storage helpers.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerEditorService.java`
  - Rename/demolish intent handling against graph/queue models.

### Packets and entry

- Modify: `src/main/java/com/monpai/sailboatmod/item/RoadPlannerItem.java`
  - Shift+right-click stores current position destination.
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRenameRoadPacket.java`
  - Handler calls editor service.
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerDemolishRoadPacket.java`
  - Handler calls editor service.
- Modify: `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
  - Open vanilla `RoadPlannerScreen`.

---

### Task 1: Responsive Vanilla Layout Model

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerVanillaLayout.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerVanillaLayoutTest.java`

- [ ] **Step 1: Write failing layout tests**

Create `RoadPlannerVanillaLayoutTest` with tests for 1280x720 and 854x480. Expected API:

```java
RoadPlannerVanillaLayout layout = RoadPlannerVanillaLayout.compute(1280, 720);
assertTrue(layout.toolbar().width() >= 120);
assertTrue(layout.map().width() >= 420);
assertTrue(layout.sidebar().width() >= 220);
assertTrue(layout.toolbar().x() >= 8);
assertTrue(layout.map().x() > layout.toolbar().right());
assertTrue(layout.sidebar().x() > layout.map().right());
assertTrue(layout.bottomButtons().size() == 7);
assertTrue(layout.map().bottom() <= 704);
```

For small screens:

```java
RoadPlannerVanillaLayout layout = RoadPlannerVanillaLayout.compute(854, 480);
assertTrue(layout.map().width() >= 360);
assertTrue(layout.map().height() >= 260);
assertTrue(layout.sidebar().right() <= 846);
assertTrue(layout.bottomButtons().stream().allMatch(rect -> rect.bottom() <= 472));
```

- [ ] **Step 2: Run red test**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlannerVanillaLayoutTest"
```

Expected: compilation fails because `RoadPlannerVanillaLayout` is missing.

- [ ] **Step 3: Implement layout model**

Implement records:

```java
public record RoadPlannerVanillaLayout(Rect toolbar, Rect map, Rect sidebar, List<Rect> bottomButtons) {
    public static RoadPlannerVanillaLayout compute(int screenWidth, int screenHeight) { ... }
    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean contains(double mouseX, double mouseY) { ... }
    }
}
```

Rules:

- Margin 8-16 px.
- Toolbar width clamps 120-160.
- Sidebar width clamps 220-300.
- Map fills remaining width.
- Bottom buttons are 7 equal-ish rects below map, inside map panel width.

- [ ] **Step 4: Run green test and compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlannerVanillaLayoutTest"
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

- [ ] **Step 5: Commit**

Commit message: `Add vanilla road planner layout model`.

---

### Task 2: Vanilla Context Menu

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerVanillaContextMenu.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerVanillaContextMenuTest.java`

- [ ] **Step 1: Write failing context menu tests**

Test RoadWeaver behavior:

```java
RoadPlannerVanillaContextMenu menu = RoadPlannerVanillaContextMenu.forRoadEdge(edgeId);
menu.open(840, 470);
menu.layout(900, 500, label -> label.length() * 6);
assertTrue(menu.bounds().right() <= 896);
assertTrue(menu.bounds().bottom() <= 496);
menu.updateHover(menu.bounds().x() + 8, menu.bounds().y() + 8);
assertEquals(RoadPlannerContextMenuAction.RENAME_ROAD, menu.hoveredAction().orElseThrow());
assertTrue(menu.click(menu.bounds().x() + 8, menu.bounds().y() + 8, 0).action().isPresent());
```

Test disabled item:

```java
menu.setEnabled(RoadPlannerContextMenuAction.DEMOLISH_BRANCH, false);
menu.updateHover(...coordinates over demolish branch...);
assertTrue(menu.click(...).action().isEmpty());
```

- [ ] **Step 2: Run red test**

Run targeted test. Expected missing class.

- [ ] **Step 3: Implement model and render method**

Implement menu state based on RoadWeaver:

- `PADDING = 6`
- `ITEM_HEIGHT = 16`
- `SEPARATOR_HEIGHT = 8`
- `open/close/isOpen`
- `layout(screenW, screenH, LabelWidth)` clamps to screen.
- `render(GuiGraphics, Font, mouseX, mouseY, screenW, screenH)` draws shadow, border, background, hover, separator, text.
- `mouseClicked` returns click result.

Use existing `RoadPlannerContextMenuAction` values.

- [ ] **Step 4: Run green test and compile**

Run targeted test and `compileJava`.

- [ ] **Step 5: Commit**

Commit message: `Add vanilla road planner context menu`.

---

### Task 3: Vanilla Map Canvas Event Helper

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvasTest.java`

- [ ] **Step 1: Write failing map canvas tests**

Expected API:

```java
RoadPlannerMapCanvas canvas = new RoadPlannerMapCanvas(layout.map(), component);
assertTrue(canvas.contains(layout.map().x() + 10, layout.map().y() + 10));
BlockPos world = canvas.mouseToWorld(layout.map().x() + layout.map().width() / 2, layout.map().y() + layout.map().height() / 2);
assertEquals(expectedX, world.getX());
```

Right-click graph hit:

```java
RoadPlannerMapInteractionResult result = canvas.rightClickGraph(state, graph, worldX, worldZ, mouseX, mouseY);
assertTrue(result.contextMenu().isPresent());
```

- [ ] **Step 2: Run red test**

Run targeted test. Expected missing class.

- [ ] **Step 3: Implement map canvas**

Responsibilities:

- Store `RoadPlannerVanillaLayout.Rect mapRect`.
- Use existing `RoadPlannerMapComponent` for coordinate conversion.
- `renderPlaceholder(GuiGraphics, Font)` draws map background, grid, loading text, start/destination markers.
- `rightClickGraph` delegates to `RoadPlannerMapInteraction`.

- [ ] **Step 4: Run green test and compile**

Run targeted test and `compileJava`.

- [ ] **Step 5: Commit**

Commit message: `Add vanilla road planner map canvas`.

---

### Task 4: Replace Elementa RoadPlannerScreen With Vanilla Screen

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`

- [ ] **Step 1: Write failing behavior tests**

Use pure helper methods exposed for test:

```java
RoadPlannerScreen screen = RoadPlannerScreen.forTest(sessionId, 1280, 720);
assertFalse(WindowScreen.class.isAssignableFrom(screen.getClass()));
assertEquals(RoadPlannerScreen.EscapeResult.CLOSE_CONTEXT_MENU, screen.handleEscapeForTest(true, false));
assertEquals(RoadPlannerScreen.EscapeResult.CLOSE_SCREEN, screen.handleEscapeForTest(false, false));
assertEquals(7, screen.layoutForTest().bottomButtons().size());
```

- [ ] **Step 2: Run red test**

Expected failure because current class extends Elementa `WindowScreen` and helpers missing.

- [ ] **Step 3: Rewrite `RoadPlannerScreen`**

Make it:

```java
public class RoadPlannerScreen extends Screen {
    private RoadPlannerVanillaLayout layout;
    private RoadPlannerMapCanvas canvas;
    private RoadPlannerVanillaContextMenu contextMenu;
    ...
}
```

Implement:

- `init()` computes layout and creates vanilla `Button`s for tools/actions.
- `render(GuiGraphics, int, int, float)` draws panels/canvas/status/menu.
- `mouseClicked`, `mouseDragged`, `mouseReleased`, `mouseScrolled` dispatch to popup/menu/canvas/super.
- `keyPressed`: ESC closes context menu first, else calls `onClose()` and returns true.
- `isPauseScreen()` returns false.
- Tool buttons update `RoadPlannerClientState.activeTool`.
- Bottom buttons set local status line or send packet if already available.

- [ ] **Step 4: Run green test and compile**

Run behavior test, UI model tests, and compile.

- [ ] **Step 5: Commit**

Commit message: `Replace road planner screen with vanilla UI`.

---

### Task 5: Vanilla Rename Text Input

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTextInputScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTextInputScreenTest.java`

- [ ] **Step 1: Write failing tests**

Test model-level helpers:

```java
RoadPlannerTextInputScreen.SubmitResult result = RoadPlannerTextInputScreen.submitForTest(routeId, edgeId, " 港口大道 ");
assertEquals("港口大道", result.value());
assertEquals(routeId, result.routeId());
assertEquals(edgeId, result.edgeId());
```

Test blank names rejected:

```java
assertTrue(RoadPlannerTextInputScreen.submitForTest(routeId, edgeId, "   ").rejected());
```

- [ ] **Step 2: Run red test**

Expected missing class.

- [ ] **Step 3: Implement vanilla text input screen**

Based on RoadWeaver `SimpleTextInputScreen`:

- Extends `Screen`.
- Owns `EditBox`.
- OK and Cancel buttons.
- Enter submits.
- ESC cancels and returns to parent.
- Submit sends `RoadPlannerRenameRoadPacket` through `ModNetwork.CHANNEL.sendToServer` when in client runtime.
- Static `submitForTest` keeps tests independent from Minecraft client.

- [ ] **Step 4: Wire context menu rename**

In `RoadPlannerScreen`, when context action is `RENAME_ROAD`, open `RoadPlannerTextInputScreen` with current road name if known.

- [ ] **Step 5: Run green test and compile**

Run targeted tests and compile.

- [ ] **Step 6: Commit**

Commit message: `Add vanilla road rename input screen`.

---

### Task 6: Shift-Right-Click Destination Persistence

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerDestinationService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/item/RoadPlannerItem.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerDestinationServiceTest.java`

- [ ] **Step 1: Extend failing destination test**

Add:

```java
UUID playerId = UUID.randomUUID();
RoadPlannerDestinationService service = new RoadPlannerDestinationService();
BlockPos destination = new BlockPos(10, 70, -5);
service.saveCurrentPositionDestination(playerId, destination);
assertEquals(destination, service.destinationFor(playerId).orElseThrow());
assertEquals(RoadPlannerItem.EntryAction.SET_CURRENT_POSITION_DESTINATION, RoadPlannerItem.entryAction(true));
```

If service is instance-based, add static singleton helpers for runtime and keep instance tests.

- [ ] **Step 2: Run red test**

Expected missing methods or no persistence.

- [ ] **Step 3: Implement destination persistence**

Add in service:

- `saveCurrentPositionDestination(UUID playerId, BlockPos pos)`
- `destinationFor(UUID playerId)`
- `clearDestination(UUID playerId)`

Use `ConcurrentHashMap<UUID, BlockPos>` in this pass; saved-data persistence is outside this implementation plan.

Modify `RoadPlannerItem.use`:

- If sneaking server-side: store `serverPlayer.blockPosition()` through service and send success message.
- Return success without sending open-screen packet.
- If not sneaking: send open-screen packet.

- [ ] **Step 4: Run green test and compile**

Run destination test and compile.

- [ ] **Step 5: Commit**

Commit message: `Persist road planner current position destination`.

---

### Task 7: Editor Service And Packet Handlers

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerEditorService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRenameRoadPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerDemolishRoadPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerEditorServiceTest.java`

- [ ] **Step 1: Write failing editor service tests**

Test rename:

```java
RoadPlannerEditorService service = new RoadPlannerEditorService();
RoadNetworkGraph graph = sampleGraph(edgeId, "旧名");
RoadPlannerEditorService.RenameResult result = service.renameRoad(graph, edgeId, "新名");
assertTrue(result.success());
assertEquals("新名", result.edge().orElseThrow().metadata().roadName());
```

Test demolish:

```java
RoadPlannerEditorService.DemolishResult result = service.planDemolition(routeId, edgeId, RoadPlannerDemolishRoadPacket.Scope.EDGE, ledger, Map.of());
assertTrue(result.job().isPresent());
```

Test missing edge:

```java
assertTrue(service.renameRoad(graph, UUID.randomUUID(), "x").issues().get(0).blocking());
```

- [ ] **Step 2: Run red test**

Expected missing service or graph mutation helper.

- [ ] **Step 3: Implement editor service**

Keep it pure/testable:

- `renameRoad(RoadNetworkGraph graph, UUID edgeId, String newName)` returns updated edge/result.
- `planDemolition(UUID routeId, UUID edgeId, Scope scope, RoadRollbackLedger ledger, Map<BlockPos, Boolean> conflicts)` delegates to `RoadDemolitionPlanner`.

If `RoadNetworkGraph` lacks edge update, add `renameEdge(UUID edgeId, String roadName)` returning new `RoadGraphEdge`.

- [ ] **Step 4: Wire packet handlers**

Handlers should:

- `enqueueWork` server-side.
- Obtain sender.
- Call editor service through a runtime registry/singleton placeholder if persistent graph store is not yet available.
- Send sender warning on missing route/edge.

Do not silently no-op.

- [ ] **Step 5: Run green test and compile**

Run editor service tests, packet roundtrip tests, compile.

- [ ] **Step 6: Commit**

Commit message: `Wire road planner editor packet handlers`.

---

### Task 8: Right-Click Edge Hit Testing In Screen Flow

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapInteractionTest.java`

- [ ] **Step 1: Extend interaction tests**

Add test:

```java
RoadPlannerScreen screen = RoadPlannerScreen.forTest(sessionId, 1280, 720);
screen.setGraphForTest(sampleGraphWithEdge());
boolean consumed = screen.rightClickMapForTest(worldX, worldZ, mouseX, mouseY);
assertTrue(consumed);
assertTrue(screen.contextMenuForTest().isOpen());
```

- [ ] **Step 2: Run red test**

Expected helper missing or not wired.

- [ ] **Step 3: Implement screen graph wiring**

- Add `RoadNetworkGraph graph` field in screen.
- Expose a client hook in this pass so `RoadPlannerGraphSyncPacket` can update this graph.
- For now expose setter used by sync/test.
- In `mouseClicked` right button over map: convert mouse to world, run canvas hit test, open menu.

- [ ] **Step 4: Run green test and compile**

Run interaction tests and compile.

- [ ] **Step 5: Commit**

Commit message: `Wire road edge right click menu in vanilla screen`.

---

### Task 9: Final Validation And Jar

**Files:**
- No source changes unless validation exposes regressions.

- [ ] **Step 1: Run focused tests**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlanner*" --tests "*RoadWeaverStyle*"
```

Expected: PASS.

- [ ] **Step 2: Compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Assemble jar**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat assemble
```

Expected: BUILD SUCCESSFUL and jars under `build/libs`.

- [ ] **Step 4: Optional full build**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat build
```

If it fails only at known `CarriageRoutePlannerTest`, report that clearly and keep assemble jar as the deliverable.

- [ ] **Step 5: Report manual QA checklist**

Report:

- Open planner item -> vanilla planner screen.
- Buttons clickable.
- ESC closes context menu before screen.
- GUI scale layout preserved.
- Shift+right-click stores destination.
- Rename/demolish actions no longer empty stubs.
- Latest jar paths and timestamps.

- [ ] **Step 6: Commit validation fixes if needed**

Commit only source/test fixes, not generated jars.
