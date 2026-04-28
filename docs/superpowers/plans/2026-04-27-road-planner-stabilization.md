# Road Planner Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stabilize the road planner so players first select a destination Town, see accurate Town overlays and stable map coordinates, edit nodes with Blender-style top tools, enforce road/bridge rules, avoid GUI tick stalls, and get a real ghost-block preview before building.

**Architecture:** Split the work into independent vertical slices: Town overlay data/rendering, node editing tools, bridge validation, tile/cache performance, top-bar UI, and ghost preview integration. Each slice adds focused model/service classes and keeps `RoadPlannerScreen` as the coordinator rather than the owner of every behavior. Implementation should be subagent-driven: each worker owns disjoint files where possible, with the main agent reviewing and integrating.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, vanilla `Screen`/`GuiGraphics`, existing `NationSavedData`/`NationOverviewClaim`, existing RoadPlanner client classes, Gradle (`compileJava`, targeted JUnit tests, `assemble`).

---

## Scope And Subagent Strategy

This is a multi-subsystem stabilization pass. Execute with subagents in parallel only where write scopes are disjoint:

- **Worker A: Town Overlay + Destination Flow** owns claim data packets, overlay renderer, tooltip, and destination-gated opening.
- **Worker B: Node Tools + Drafts** owns node hit testing, selection, erase behavior, hover preview, and draft persistence.
- **Worker C: Bridge Rules + Ghost Preview** owns road/bridge classification, bridge land anchoring, and ghost preview packet integration.
- **Worker D: Tile Performance + Top UI** owns tile cache queue, render throttling, cancellation, and Blender-style top toolbar.
- **Main agent:** reviews worker patches, resolves integration conflicts in `RoadPlannerScreen`, runs verification, and builds jar.

Do not commit changes unless the user explicitly asks. Treat the 鈥渃heckpoint鈥?steps as status checkpoints, not git commits.

---

## File Structure

### Existing Files To Modify

- `src/main/java/com/monpai/sailboatmod/item/RoadPlannerItem.java`  
  Gate planner opening behind a selected destination Town; keep Shift+right-click destination selection.
- `src/main/java/com/monpai/sailboatmod/network/packet/OpenRoadPlannerScreenPacket.java`  
  Add serialized Town claim overlays and planner route metadata.
- `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`  
  Open `RoadPlannerScreen` with route metadata, claims, and draft/session data.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`  
  Coordinate UI, overlay render calls, node tools, hover preview, and action dispatch after helpers are introduced.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java`  
  Use one coordinate system, remove render-time tile mutation, expose world/screen helpers.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapLayout.java`  
  Replace left/right/bottom panel layout with top toolbar + full map + compact status strip.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerLinePlan.java`  
  Add indexed node editing operations needed by select/erase/bridge anchoring.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java`  
  Add safe cache metadata, pending/in-flight tracking, and no-repeat render decisions.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerForceRenderQueue.java`  
  Report cache-hit/skipped counts and support cancellation.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerGhostPreviewBridge.java`  
  Replace empty preview handoff with real server preview request or old preview packet integration.
- `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`  
  Register new planner packets.

### New Files To Create

- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClaimOverlay.java`  
  Client-side immutable claim overlay model for start/end/other Town chunks.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClaimOverlayRenderer.java`  
  Draw Town claim rectangles and border/tooltip using `RoadPlannerMapView`.
- `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerClaimOverlaySyncPacket.java`  
  Optional follow-up packet if `OpenRoadPlannerScreenPacket` becomes too large; otherwise encode overlays in open packet.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerNodeHitTester.java`  
  Shared hit detection for select and erase tools.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerNodeSelection.java`  
  Selection state for one node or one segment.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerEraseTool.java`  
  Eraser semantics: delete hit nodes/segments except protected start node.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerDraftPersistence.java`  
  Save/load draft nodes by world + dimension + player/session into client config cache.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerHeightSampler.java`  
  Client-side height sampler for loaded terrain; returns stable `BlockPos(x, y, z)` for clicks/hover.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerBridgeRuleService.java`  
  Classify proposed segments and return accept/reject/bridge-anchor decisions.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTopToolbar.java`  
  Blender-style top folded toolbar model and hit testing.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileRenderScheduler.java`  
  Background-safe tile task scheduler with main-thread upload budget and cancellation.
- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapPalette.java`  
  Centralize minimap color tuning so terrain, water, loading tiles, overlays, and grid can be made lighter without scattering constants.
- `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacket.java`  
  Send current path/segment types to server to generate ghost preview from old pipeline.

### Tests To Add Or Modify

- `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClaimOverlayRendererTest.java`
- `src/test/java/com/monpai/sailboatmod/network/packet/OpenRoadPlannerScreenPacketTest.java`
- `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerNodeHitTesterTest.java`
- `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerEraseToolTest.java`
- `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerDraftPersistenceTest.java`
- `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerBridgeRuleServiceTest.java`
- `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTopToolbarTest.java`
- `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileRenderSchedulerTest.java`
- `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapPaletteTest.java`
- `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacketTest.java`

---

### Task 1: Destination-Gated Planner Opening

**Assigned subagent:** Worker A  
**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/item/RoadPlannerItem.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerDestinationService.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerDestinationServiceTownTest.java`

- [ ] **Step 1: Add test for no-route gating**

Add this test method to `RoadPlannerDestinationServiceTownTest`:

```java
@Test
void playerWithoutTownRouteHasNoEditablePlannerRoute() {
    RoadPlannerDestinationService service = new RoadPlannerDestinationService();
    UUID playerId = UUID.randomUUID();

    assertTrue(service.townRouteFor(playerId).isEmpty());
    assertTrue(service.destinationFor(playerId).isEmpty());
}
```

- [ ] **Step 2: Run the focused test**

Run: `. gradlew.bat test --tests "*RoadPlannerDestinationServiceTownTest"`  
Expected: PASS for existing tests and new no-route test.

- [ ] **Step 3: Update `RoadPlannerItem.use` behavior**

In `RoadPlannerItem.openSavedOrEmptyPlanner`, replace the fallback that opens an empty planner with a message and no screen open:

```java
player.sendSystemMessage(Component.literal("璇峰厛鍦ㄥ綋鍓?Town 鍐?Shift+鍙抽敭鐩爣 Town 鍖哄潡閫夋嫨閬撹矾缁堢偣銆?));
```

Keep route-present behavior unchanged: start a session and send `OpenRoadPlannerScreenPacket` with route anchors.

- [ ] **Step 4: Verify compile**

Run: `. gradlew.bat compileJava`  
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Checkpoint**

Report: 鈥淧lanner no longer opens full edit UI without a selected destination Town.鈥?Do not commit unless requested.

---

### Task 2: Sync Start/End Town Claim Overlays

**Assigned subagent:** Worker A  
**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClaimOverlay.java`
- Modify: `src/main/java/com/monpai/sailboatmod/item/RoadPlannerItem.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/OpenRoadPlannerScreenPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/OpenRoadPlannerScreenPacketTest.java`

- [ ] **Step 1: Create overlay model**

Create `RoadPlannerClaimOverlay.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

public record RoadPlannerClaimOverlay(
        int chunkX,
        int chunkZ,
        String townId,
        String townName,
        String nationId,
        String nationName,
        Role role,
        int primaryColorRgb,
        int secondaryColorRgb
) {
    public RoadPlannerClaimOverlay {
        townId = townId == null ? "" : townId.trim();
        townName = townName == null ? "" : townName.trim();
        nationId = nationId == null ? "" : nationId.trim();
        nationName = nationName == null ? "" : nationName.trim();
        role = role == null ? Role.OTHER : role;
        primaryColorRgb &= 0x00FFFFFF;
        secondaryColorRgb &= 0x00FFFFFF;
    }

    public enum Role {
        START,
        DESTINATION,
        OTHER
    }
}
```

- [ ] **Step 2: Add packet round-trip test**

Extend `OpenRoadPlannerScreenPacketTest.roundTripsSessionAndTownAnchors` with overlays:

```java
List<RoadPlannerClaimOverlay> overlays = List.of(
        new RoadPlannerClaimOverlay(1, 2, "start", "Start", "nation", "Nation", RoadPlannerClaimOverlay.Role.START, 0x00AA00, 0x006600),
        new RoadPlannerClaimOverlay(8, 9, "dest", "Dest", "nation", "Nation", RoadPlannerClaimOverlay.Role.DESTINATION, 0xFF3333, 0xAA0000)
);
OpenRoadPlannerScreenPacket packet = new OpenRoadPlannerScreenPacket(
        false, "Start", "dest", List.of(new RoadPlannerClientHooks.TargetEntry("dest", "Dest", 260)),
        sessionId, "start", source, destination, overlays
);
```

Assert:

```java
assertEquals(2, decoded.claimOverlays().size());
assertEquals(RoadPlannerClaimOverlay.Role.DESTINATION, decoded.claimOverlays().get(1).role());
assertEquals(8, decoded.claimOverlays().get(1).chunkX());
```

- [ ] **Step 3: Run failing packet test**

Run: `. gradlew.bat test --tests "*OpenRoadPlannerScreenPacketTest"`  
Expected: FAIL because `claimOverlays()` constructor/accessor/codec are missing.

- [ ] **Step 4: Extend `OpenRoadPlannerScreenPacket`**

Add field:

```java
private final List<RoadPlannerClaimOverlay> claimOverlays;
```

Add constructor overload with final parameter `List<RoadPlannerClaimOverlay> claimOverlays`, default old constructors to `List.of()`, and add accessor:

```java
public List<RoadPlannerClaimOverlay> claimOverlays() {
    return claimOverlays;
}
```

Encode each overlay after destination anchor:

```java
buf.writeVarInt(msg.claimOverlays.size());
for (RoadPlannerClaimOverlay overlay : msg.claimOverlays) {
    buf.writeVarInt(overlay.chunkX());
    buf.writeVarInt(overlay.chunkZ());
    buf.writeUtf(overlay.townId(), 40);
    buf.writeUtf(overlay.townName(), 64);
    buf.writeUtf(overlay.nationId(), 40);
    buf.writeUtf(overlay.nationName(), 64);
    buf.writeEnum(overlay.role());
    buf.writeVarInt(overlay.primaryColorRgb());
    buf.writeVarInt(overlay.secondaryColorRgb());
}
```

Decode with matching order into `ArrayList<RoadPlannerClaimOverlay>`.

- [ ] **Step 5: Collect start/destination claims in `RoadPlannerItem`**

Add helper:

```java
private List<RoadPlannerClaimOverlay> collectRouteClaims(NationSavedData data, TownRecord start, TownRecord destination) {
    List<RoadPlannerClaimOverlay> overlays = new java.util.ArrayList<>();
    addTownClaims(data, overlays, start, RoadPlannerClaimOverlay.Role.START, 0x40D878, 0x1E8B4D);
    addTownClaims(data, overlays, destination, RoadPlannerClaimOverlay.Role.DESTINATION, 0xFF4D4D, 0xB00020);
    return overlays;
}

private void addTownClaims(NationSavedData data,
                           List<RoadPlannerClaimOverlay> overlays,
                           TownRecord town,
                           RoadPlannerClaimOverlay.Role role,
                           int primary,
                           int secondary) {
    if (town == null) return;
    String nationName = "";
    if (!town.nationId().isBlank() && data.getNation(town.nationId()) != null) {
        nationName = data.getNation(town.nationId()).name();
    }
    for (NationClaimRecord claim : data.getClaimsForTown(town.townId())) {
        overlays.add(new RoadPlannerClaimOverlay(
                claim.chunkX(), claim.chunkZ(), town.townId(), town.name(), town.nationId(), nationName,
                role, primary, secondary
        ));
    }
}
```

Pass collected overlays into `sendPlannerPacket` and packet constructor.

- [ ] **Step 6: Pass overlays to client screen**

Update `RoadPlannerClientHooks.openNewPlannerEntry(...)` to accept `List<RoadPlannerClaimOverlay> claimOverlays` and pass it to the `RoadPlannerScreen` constructor.

- [ ] **Step 7: Run tests**

Run: `. gradlew.bat test --tests "*OpenRoadPlannerScreenPacketTest"`  
Expected: PASS.

---

### Task 3: Render Town Claim Overlay And Tooltip

**Assigned subagent:** Worker A  
**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClaimOverlayRenderer.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClaimOverlayRendererTest.java`

- [ ] **Step 1: Add renderer tests**

Create `RoadPlannerClaimOverlayRendererTest.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerClaimOverlayRendererTest {
    @Test
    void findsDestinationClaimAtMouseWorldPosition() {
        RoadPlannerClaimOverlayRenderer renderer = new RoadPlannerClaimOverlayRenderer(List.of(
                new RoadPlannerClaimOverlay(10, -3, "dest", "Red Town", "nation", "Nation", RoadPlannerClaimOverlay.Role.DESTINATION, 0xFF3333, 0xAA0000)
        ));

        RoadPlannerClaimOverlay overlay = renderer.claimAtWorld(10 * 16 + 8, -3 * 16 + 8).orElseThrow();

        assertEquals("Red Town", overlay.townName());
        assertEquals(RoadPlannerClaimOverlay.Role.DESTINATION, overlay.role());
    }

    @Test
    void destinationClaimsUseRedOverlayColor() {
        RoadPlannerClaimOverlay overlay = new RoadPlannerClaimOverlay(1, 1, "dest", "Dest", "", "", RoadPlannerClaimOverlay.Role.DESTINATION, 0xFF3333, 0xAA0000);

        assertTrue((RoadPlannerClaimOverlayRenderer.fillColor(overlay) & 0x00FF0000) != 0);
    }
}
```

- [ ] **Step 2: Run failing renderer test**

Run: `. gradlew.bat test --tests "*RoadPlannerClaimOverlayRendererTest"`  
Expected: FAIL because renderer does not exist.

- [ ] **Step 3: Implement renderer**

Create `RoadPlannerClaimOverlayRenderer.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class RoadPlannerClaimOverlayRenderer {
    private final List<RoadPlannerClaimOverlay> overlays;

    public RoadPlannerClaimOverlayRenderer(Collection<RoadPlannerClaimOverlay> overlays) {
        this.overlays = overlays == null ? List.of() : List.copyOf(overlays);
    }

    public List<RoadPlannerClaimOverlay> overlays() {
        return overlays;
    }

    public Optional<RoadPlannerClaimOverlay> claimAtWorld(int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        return overlays.stream().filter(o -> o.chunkX() == chunkX && o.chunkZ() == chunkZ).findFirst();
    }

    public void render(GuiGraphics graphics, RoadPlannerMapView view, RoadPlannerMapLayout.Rect map) {
        for (RoadPlannerClaimOverlay overlay : overlays) {
            int x1 = view.worldToScreenX(overlay.chunkX() << 4, map);
            int z1 = view.worldToScreenZ(overlay.chunkZ() << 4, map);
            int x2 = view.worldToScreenX((overlay.chunkX() + 1) << 4, map);
            int z2 = view.worldToScreenZ((overlay.chunkZ() + 1) << 4, map);
            int left = Math.min(x1, x2);
            int right = Math.max(x1, x2);
            int top = Math.min(z1, z2);
            int bottom = Math.max(z1, z2);
            graphics.fill(left, top, right, bottom, fillColor(overlay));
            int border = borderColor(overlay);
            graphics.fill(left, top, right, top + 1, border);
            graphics.fill(left, bottom - 1, right, bottom, border);
            graphics.fill(left, top, left + 1, bottom, border);
            graphics.fill(right - 1, top, right, bottom, border);
        }
    }

    public void renderTooltip(GuiGraphics graphics, Font font, RoadPlannerMapView view, RoadPlannerMapLayout.Rect map, int mouseX, int mouseY) {
        int worldX = view.screenToWorldX(mouseX, map);
        int worldZ = view.screenToWorldZ(mouseY, map);
        claimAtWorld(worldX, worldZ).ifPresent(overlay -> graphics.renderTooltip(font,
                List.of(
                        net.minecraft.network.chat.Component.literal(roleLabel(overlay) + ": " + overlay.townName()),
                        net.minecraft.network.chat.Component.literal("Nation: " + (overlay.nationName().isBlank() ? "-" : overlay.nationName())),
                        net.minecraft.network.chat.Component.literal("Chunk: " + overlay.chunkX() + ", " + overlay.chunkZ())
                ), Optional.empty(), mouseX, mouseY));
    }

    public static int fillColor(RoadPlannerClaimOverlay overlay) {
        int rgb = overlay.role() == RoadPlannerClaimOverlay.Role.DESTINATION ? 0xFF3333
                : overlay.role() == RoadPlannerClaimOverlay.Role.START ? 0x40D878 : overlay.primaryColorRgb();
        return 0x55333333 | (rgb & 0x00FFFFFF);
    }

    public static int borderColor(RoadPlannerClaimOverlay overlay) {
        int rgb = overlay.role() == RoadPlannerClaimOverlay.Role.DESTINATION ? 0xFF0000
                : overlay.role() == RoadPlannerClaimOverlay.Role.START ? 0x00FF80 : overlay.secondaryColorRgb();
        return 0xCC000000 | (rgb & 0x00FFFFFF);
    }

    private static String roleLabel(RoadPlannerClaimOverlay overlay) {
        return switch (overlay.role()) {
            case START -> "璧风偣Town";
            case DESTINATION -> "缁堢偣Town";
            case OTHER -> "Town";
        };
    }
}
```

- [ ] **Step 4: Integrate renderer in `RoadPlannerScreen`**

Add field:

```java
private RoadPlannerClaimOverlayRenderer claimOverlayRenderer = new RoadPlannerClaimOverlayRenderer(List.of());
```

Update constructor to receive overlays and assign renderer.

In `render`, call after `canvas.render` and before `renderRoadOverlay`:

```java
claimOverlayRenderer.render(graphics, mapView, mapLayout.map());
```

After context menu render, call tooltip if canvas contains mouse:

```java
if (canvas.contains(mouseX, mouseY)) {
    claimOverlayRenderer.renderTooltip(graphics, font, mapView, mapLayout.map(), mouseX, mouseY);
}
```

- [ ] **Step 5: Run tests**

Run: `. gradlew.bat test --tests "*RoadPlannerClaimOverlayRendererTest" --tests "*OpenRoadPlannerScreenPacketTest"`  
Expected: PASS.

---

### Task 4: Stable Height Sampling And Single Coordinate System

**Assigned subagent:** Worker B  
**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerHeightSampler.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapCanvas.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapViewTest.java`

- [ ] **Step 1: Add height sampler unit test**

Add to `RoadPlannerMapViewTest`:

```java
@Test
void mapCanvasUsesHeightSamplerForMouseWorldPosition() {
    RoadPlannerMapView view = RoadPlannerMapView.centered(0, 0, 2.0D);
    RoadPlannerMapLayout.Rect map = new RoadPlannerMapLayout.Rect(100, 100, 400, 300);
    RoadPlannerMapCanvas canvas = RoadPlannerMapCanvas.forTest(map.asVanillaRect(), view, (x, z) -> 72);

    BlockPos pos = canvas.mouseToWorld(300, 250);

    assertEquals(72, pos.getY());
}
```

- [ ] **Step 2: Run failing test**

Run: `. gradlew.bat test --tests "*RoadPlannerMapViewTest"`  
Expected: FAIL because `RoadPlannerMapCanvas.forTest` and sampler injection do not exist.

- [ ] **Step 3: Implement `RoadPlannerHeightSampler`**

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

@FunctionalInterface
public interface RoadPlannerHeightSampler {
    int heightAt(int x, int z);

    static RoadPlannerHeightSampler clientLoadedTerrain() {
        return (x, z) -> {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) return 64;
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        };
    }

    default BlockPos blockPosAt(int x, int z) {
        return new BlockPos(x, heightAt(x, z), z);
    }
}
```

- [ ] **Step 4: Inject sampler into canvas**

Add field to `RoadPlannerMapCanvas`:

```java
private final RoadPlannerHeightSampler heightSampler;
```

Use it in `mouseToWorld`:

```java
int worldX = view.screenToWorldX(mouseX, mapRect);
int worldZ = view.screenToWorldZ(mouseY, mapRect);
return heightSampler.blockPosAt(worldX, worldZ);
```

Add test factory:

```java
static RoadPlannerMapCanvas forTest(RoadPlannerVanillaLayout.Rect rect, RoadPlannerMapView view, RoadPlannerHeightSampler sampler) {
    return new RoadPlannerMapCanvas(rect, new RoadPlannerMapComponent(RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_1), new RoadMapViewport(rect.x(), rect.y(), rect.width(), rect.height())), view, null, sampler);
}
```

- [ ] **Step 5: Update `RoadPlannerScreen.recomputeLayout`**

Construct canvas with `RoadPlannerHeightSampler.clientLoadedTerrain()`.

- [ ] **Step 6: Run tests**

Run: `. gradlew.bat test --tests "*RoadPlannerMapViewTest"`  
Expected: PASS.

---

### Task 5: Persistent Draft Save/Restore

**Assigned subagent:** Worker B  
**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerDraftPersistence.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerDraftStore.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerDraftPersistenceTest.java`

- [ ] **Step 1: Add persistence test**

Create `RoadPlannerDraftPersistenceTest.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerDraftPersistenceTest {
    @TempDir Path tempDir;

    @Test
    void savesAndLoadsDraftBySession() {
        UUID sessionId = UUID.randomUUID();
        RoadPlannerDraftPersistence persistence = new RoadPlannerDraftPersistence(tempDir.toFile());
        RoadPlannerDraftStore.Draft draft = new RoadPlannerDraftStore.Draft(
                List.of(new BlockPos(1, 64, 2), new BlockPos(9, 70, 12)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR)
        );

        persistence.save(sessionId, draft);

        RoadPlannerDraftStore.Draft loaded = persistence.load(sessionId).orElseThrow();
        assertEquals(draft.nodes(), loaded.nodes());
        assertEquals(draft.segmentTypes(), loaded.segmentTypes());
    }
}
```

- [ ] **Step 2: Run failing test**

Run: `. gradlew.bat test --tests "*RoadPlannerDraftPersistenceTest"`  
Expected: FAIL because `RoadPlannerDraftPersistence` does not exist.

- [ ] **Step 3: Implement JSON-light NBT-free persistence**

Create `RoadPlannerDraftPersistence.java` with line-based format:

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RoadPlannerDraftPersistence {
    private final File rootDir;

    public RoadPlannerDraftPersistence(File rootDir) {
        this.rootDir = rootDir;
    }

    public void save(UUID sessionId, RoadPlannerDraftStore.Draft draft) {
        if (sessionId == null || draft == null) return;
        File file = file(sessionId);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        List<String> lines = new ArrayList<>();
        for (BlockPos node : draft.nodes()) {
            lines.add("N," + node.getX() + "," + node.getY() + "," + node.getZ());
        }
        for (RoadPlannerSegmentType type : draft.segmentTypes()) {
            lines.add("S," + type.name());
        }
        try {
            Files.write(file.toPath(), lines);
        } catch (IOException ignored) {
        }
    }

    public Optional<RoadPlannerDraftStore.Draft> load(UUID sessionId) {
        File file = file(sessionId);
        if (!file.exists()) return Optional.empty();
        List<BlockPos> nodes = new ArrayList<>();
        List<RoadPlannerSegmentType> segments = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file.toPath())) {
                String[] parts = line.split(",");
                if (parts.length == 4 && "N".equals(parts[0])) {
                    nodes.add(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
                } else if (parts.length == 2 && "S".equals(parts[0])) {
                    segments.add(RoadPlannerSegmentType.valueOf(parts[1]));
                }
            }
        } catch (RuntimeException | IOException ignored) {
            return Optional.empty();
        }
        return Optional.of(new RoadPlannerDraftStore.Draft(nodes, segments));
    }

    private File file(UUID sessionId) {
        return new File(rootDir, sessionId + ".draft");
    }
}
```

- [ ] **Step 4: Connect persistence in screen**

Add a `RoadPlannerDraftPersistence` field created under `Minecraft.getInstance().gameDirectory/roadplanner_drafts`. In `saveDraft()`, save to memory and file. In `applyTownRoute`, load disk draft if memory draft is absent.

- [ ] **Step 5: Run tests**

Run: `. gradlew.bat test --tests "*RoadPlannerDraftPersistenceTest" --tests "*RoadPlannerScreenBehaviorTest"`  
Expected: PASS.

---

### Task 6: Select Tool Node Hit Testing

**Assigned subagent:** Worker B  
**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerNodeHitTester.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerNodeSelection.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerNodeHitTesterTest.java`

- [ ] **Step 1: Add hit test tests**

Create `RoadPlannerNodeHitTesterTest.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerNodeHitTesterTest {
    @Test
    void selectsNearestNodeWithinRadius() {
        RoadPlannerNodeHitTester hitTester = new RoadPlannerNodeHitTester(8.0D);

        RoadPlannerNodeSelection selection = hitTester.hitNode(List.of(new BlockPos(0, 64, 0), new BlockPos(30, 64, 0)), 32, 2).orElseThrow();

        assertEquals(1, selection.nodeIndex());
    }

    @Test
    void ignoresNodeOutsideRadius() {
        RoadPlannerNodeHitTester hitTester = new RoadPlannerNodeHitTester(4.0D);

        assertTrue(hitTester.hitNode(List.of(new BlockPos(0, 64, 0)), 10, 0).isEmpty());
    }
}
```

- [ ] **Step 2: Implement selection record**

```java
package com.monpai.sailboatmod.client.roadplanner;

public record RoadPlannerNodeSelection(int nodeIndex) {
    public RoadPlannerNodeSelection {
        if (nodeIndex < 0) throw new IllegalArgumentException("nodeIndex must be >= 0");
    }
}
```

- [ ] **Step 3: Implement hit tester**

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;

public class RoadPlannerNodeHitTester {
    private final double radius;

    public RoadPlannerNodeHitTester(double radius) {
        this.radius = Math.max(1.0D, radius);
    }

    public Optional<RoadPlannerNodeSelection> hitNode(List<BlockPos> nodes, double worldX, double worldZ) {
        if (nodes == null || nodes.isEmpty()) return Optional.empty();
        int bestIndex = -1;
        double bestDistance = radius * radius;
        for (int index = 0; index < nodes.size(); index++) {
            BlockPos node = nodes.get(index);
            double dx = node.getX() - worldX;
            double dz = node.getZ() - worldZ;
            double distance = dx * dx + dz * dz;
            if (distance <= bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex < 0 ? Optional.empty() : Optional.of(new RoadPlannerNodeSelection(bestIndex));
    }
}
```

- [ ] **Step 4: Integrate selection in screen**

Add field:

```java
private final RoadPlannerNodeHitTester nodeHitTester = new RoadPlannerNodeHitTester(8.0D);
private RoadPlannerNodeSelection selectedNode;
```

When `SELECT` tool left-clicks canvas:

```java
BlockPos world = canvas.mouseToWorld(mouseX, mouseY);
selectedNode = nodeHitTester.hitNode(linePlan.nodes(), world.getX(), world.getZ()).orElse(null);
statusLine = selectedNode == null ? "鏈€夋嫨鑺傜偣" : "宸查€夋嫨鑺傜偣 #" + selectedNode.nodeIndex();
return true;
```

In `renderRoadOverlay`, draw selected node with a larger outline.

- [ ] **Step 5: Run tests**

Run: `. gradlew.bat test --tests "*RoadPlannerNodeHitTesterTest" --tests "*RoadPlannerScreenBehaviorTest"`  
Expected: PASS.

---

### Task 7: Eraser Tool Deletes Nodes And Segments

**Assigned subagent:** Worker B  
**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerEraseTool.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerLinePlan.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerEraseToolTest.java`

- [ ] **Step 1: Add line plan delete methods test**

Create `RoadPlannerEraseToolTest.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerEraseToolTest {
    @Test
    void eraserDoesNotDeleteProtectedStartNode() {
        RoadPlannerLinePlan plan = new RoadPlannerLinePlan();
        plan.addClickNode(new BlockPos(0, 64, 0), RoadPlannerSegmentType.ROAD);
        plan.addClickNode(new BlockPos(10, 64, 0), RoadPlannerSegmentType.ROAD);

        boolean erased = new RoadPlannerEraseTool().eraseNode(plan, 0, true);

        assertEquals(false, erased);
        assertEquals(2, plan.nodeCount());
    }

    @Test
    void eraserDeletesNonStartNodeAndRepairsSegments() {
        RoadPlannerLinePlan plan = new RoadPlannerLinePlan();
        plan.addClickNode(new BlockPos(0, 64, 0), RoadPlannerSegmentType.ROAD);
        plan.addClickNode(new BlockPos(10, 64, 0), RoadPlannerSegmentType.ROAD);
        plan.addClickNode(new BlockPos(20, 64, 0), RoadPlannerSegmentType.BRIDGE_MAJOR);

        boolean erased = new RoadPlannerEraseTool().eraseNode(plan, 1, true);

        assertEquals(true, erased);
        assertEquals(2, plan.nodeCount());
        assertEquals(1, plan.segmentCount());
    }
}
```

- [ ] **Step 2: Add `removeNodeAt` to `RoadPlannerLinePlan`**

```java
public boolean removeNodeAt(int index) {
    if (index < 0 || index >= nodes.size()) return false;
    nodes.remove(index);
    if (segments.isEmpty()) return true;
    if (index == 0) {
        segments.remove(0);
    } else if (index - 1 < segments.size()) {
        segments.remove(index - 1);
    } else if (!segments.isEmpty()) {
        segments.remove(segments.size() - 1);
    }
    while (segments.size() > Math.max(0, nodes.size() - 1)) {
        segments.remove(segments.size() - 1);
    }
    return true;
}
```

- [ ] **Step 3: Implement eraser**

```java
package com.monpai.sailboatmod.client.roadplanner;

public class RoadPlannerEraseTool {
    public boolean eraseNode(RoadPlannerLinePlan plan, int nodeIndex, boolean protectStartNode) {
        if (plan == null) return false;
        if (protectStartNode && nodeIndex == 0) return false;
        return plan.removeNodeAt(nodeIndex);
    }
}
```

- [ ] **Step 4: Integrate eraser in screen**

When active tool is `ERASE` and left-click/drag hits a node, call eraser, save draft, and set status. Do not add a `BLOCKED_REQUIRES_BRIDGE` node for erase.

- [ ] **Step 5: Run tests**

Run: `. gradlew.bat test --tests "*RoadPlannerEraseToolTest" --tests "*RoadPlannerLinePlanningTest"`  
Expected: PASS.

---

### Task 8: Road/Bridge Rule Service With Land Backtracking

**Assigned subagent:** Worker C  
**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerBridgeRuleService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerLinePlan.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerBridgeRuleServiceTest.java`

- [ ] **Step 1: Add bridge rule tests**

Create `RoadPlannerBridgeRuleServiceTest.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerBridgeRuleServiceTest {
    @Test
    void roadToolRejectsMajorBridgeSpan() {
        RoadPlannerBridgeRuleService service = new RoadPlannerBridgeRuleService((x, z) -> true);

        RoadPlannerBridgeRuleService.Decision decision = service.evaluateRoadTool(List.of(new BlockPos(0, 64, 0)), new BlockPos(128, 64, 0));

        assertFalse(decision.accepted());
        assertEquals(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE, decision.segmentType());
    }

    @Test
    void bridgeToolBacktracksToLastLandNode() {
        RoadPlannerBridgeRuleService service = new RoadPlannerBridgeRuleService((x, z) -> x <= 16 || x >= 128);

        RoadPlannerBridgeRuleService.Decision decision = service.evaluateBridgeTool(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(32, 64, 0),
                new BlockPos(64, 64, 0)
        ), new BlockPos(144, 64, 0));

        assertTrue(decision.accepted());
        assertEquals(0, decision.bridgeStartNodeIndex());
    }
}
```

- [ ] **Step 2: Implement service**

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.List;

public class RoadPlannerBridgeRuleService {
    private static final int MAJOR_BRIDGE_HORIZONTAL_THRESHOLD = 96;
    private static final int MAJOR_BRIDGE_VERTICAL_THRESHOLD = 10;
    private final LandProbe landProbe;

    public RoadPlannerBridgeRuleService(LandProbe landProbe) {
        this.landProbe = landProbe == null ? (x, z) -> true : landProbe;
    }

    public Decision evaluateRoadTool(List<BlockPos> nodes, BlockPos target) {
        if (nodes == null || nodes.isEmpty()) return Decision.accept(RoadPlannerSegmentType.ROAD, -1);
        BlockPos previous = nodes.get(nodes.size() - 1);
        if (requiresMajorBridge(previous, target)) {
            return Decision.reject(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE, -1);
        }
        return Decision.accept(RoadPlannerSegmentType.ROAD, -1);
    }

    public Decision evaluateBridgeTool(List<BlockPos> nodes, BlockPos target) {
        int startIndex = lastLandNodeIndex(nodes);
        if (startIndex < 0) return Decision.reject(RoadPlannerSegmentType.BRIDGE_MAJOR, -1);
        if (!landProbe.isLand(target.getX(), target.getZ())) return Decision.reject(RoadPlannerSegmentType.BRIDGE_MAJOR, startIndex);
        return Decision.accept(RoadPlannerSegmentType.BRIDGE_MAJOR, startIndex);
    }

    private int lastLandNodeIndex(List<BlockPos> nodes) {
        if (nodes == null) return -1;
        for (int index = nodes.size() - 1; index >= 0; index--) {
            BlockPos node = nodes.get(index);
            if (landProbe.isLand(node.getX(), node.getZ())) return index;
        }
        return -1;
    }

    private static boolean requiresMajorBridge(BlockPos from, BlockPos to) {
        int horizontal = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
        int vertical = Math.abs(to.getY() - from.getY());
        return horizontal >= MAJOR_BRIDGE_HORIZONTAL_THRESHOLD || vertical >= MAJOR_BRIDGE_VERTICAL_THRESHOLD;
    }

    public record Decision(boolean accepted, RoadPlannerSegmentType segmentType, int bridgeStartNodeIndex) {
        static Decision accept(RoadPlannerSegmentType type, int bridgeStartNodeIndex) {
            return new Decision(true, type, bridgeStartNodeIndex);
        }
        static Decision reject(RoadPlannerSegmentType type, int bridgeStartNodeIndex) {
            return new Decision(false, type, bridgeStartNodeIndex);
        }
    }

    @FunctionalInterface
    public interface LandProbe {
        boolean isLand(int x, int z);
    }
}
```

- [ ] **Step 3: Add segment type update to line plan**

Add:

```java
public void setSegmentTypeFromNode(int nodeIndex, RoadPlannerSegmentType type) {
    if (type == null || nodeIndex < 0 || nodeIndex >= segments.size()) return;
    segments.set(nodeIndex, type);
}
```

- [ ] **Step 4: Integrate into screen**

Use the service when adding a node:

```java
if (state.activeTool() == RoadToolType.ROAD) {
    Decision decision = bridgeRuleService.evaluateRoadTool(linePlan.nodes(), target);
    if (!decision.accepted()) { statusLine = "闇€瑕佹ˉ姊佸伐鍏?; return true; }
}
if (state.activeTool() == RoadToolType.BRIDGE) {
    Decision decision = bridgeRuleService.evaluateBridgeTool(linePlan.nodes(), target);
    if (!decision.accepted()) { statusLine = "妗ユ璧风偣/缁堢偣蹇呴』鍦ㄩ檰鍦?; return true; }
    linePlan.setSegmentTypeFromNode(decision.bridgeStartNodeIndex(), RoadPlannerSegmentType.BRIDGE_MAJOR);
}
```

- [ ] **Step 5: Run tests**

Run: `. gradlew.bat test --tests "*RoadPlannerBridgeRuleServiceTest" --tests "*RoadPlannerLinePlanningTest"`  
Expected: PASS.

---

### Task 9: Blender-Style Top Toolbar And Compact Status

**Assigned subagent:** Worker D  
**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTopToolbar.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapLayout.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTopToolbarTest.java`

- [ ] **Step 1: Add toolbar model test**

Create `RoadPlannerTopToolbarTest.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerTopToolbarTest {
    @Test
    void toolbarPlacesToolsBeforeActionsInTopRow() {
        RoadPlannerTopToolbar toolbar = RoadPlannerTopToolbar.defaultToolbar(1280);

        assertEquals("閫夋嫨", toolbar.items().get(0).label());
        assertTrue(toolbar.bounds().height() <= 34);
    }
}
```

- [ ] **Step 2: Implement toolbar model**

```java
package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.model.RoadToolType;

import java.util.ArrayList;
import java.util.List;

public record RoadPlannerTopToolbar(List<Item> items, RoadPlannerMapLayout.Rect bounds) {
    public static RoadPlannerTopToolbar defaultToolbar(int screenWidth) {
        List<Item> items = new ArrayList<>();
        int x = 12;
        int y = 8;
        add(items, "閫夋嫨", Kind.TOOL, RoadToolType.SELECT, x, y); x += 58;
        add(items, "閬撹矾", Kind.TOOL, RoadToolType.ROAD, x, y); x += 58;
        add(items, "妗ユ", Kind.TOOL, RoadToolType.BRIDGE, x, y); x += 58;
        add(items, "闅ч亾", Kind.TOOL, RoadToolType.TUNNEL, x, y); x += 58;
        add(items, "鎿﹂櫎", Kind.TOOL, RoadToolType.ERASE, x, y); x += 70;
        add(items, "鎾ら攢", Kind.ACTION, null, x, y); x += 58;
        add(items, "娓呴櫎", Kind.ACTION, null, x, y); x += 58;
        add(items, "鑷姩琛ュ叏", Kind.ACTION, null, x, y); x += 86;
        add(items, "纭寤洪€?, Kind.ACTION, null, x, y); x += 86;
        add(items, "鍙栨秷", Kind.ACTION, null, x, y);
        return new RoadPlannerTopToolbar(List.copyOf(items), new RoadPlannerMapLayout.Rect(0, 0, screenWidth, 34));
    }

    private static void add(List<Item> items, String label, Kind kind, RoadToolType toolType, int x, int y) {
        items.add(new Item(label, kind, toolType, new RoadPlannerMapLayout.Rect(x, y, label.length() > 3 ? 78 : 50, 22)));
    }

    public Item itemAt(double mouseX, double mouseY) {
        for (Item item : items) if (item.bounds().contains(mouseX, mouseY)) return item;
        return null;
    }

    public enum Kind { TOOL, ACTION }
    public record Item(String label, Kind kind, RoadToolType toolType, RoadPlannerMapLayout.Rect bounds) {}
}
```

- [ ] **Step 3: Update layout**

Make `RoadPlannerMapLayout.compute` map start below top toolbar:

```java
int toolbarHeight = 36;
Rect map = new Rect(margin, toolbarHeight + margin, screenWidth - margin * 2, screenHeight - toolbarHeight - margin * 2 - statusHeight);
Rect toolbar = new Rect(0, 0, screenWidth, toolbarHeight);
Rect inspector = new Rect(map.right() - 260, map.y() + 8, 250, 34);
```

The inspector becomes a small status capsule, not a large right panel.

- [ ] **Step 4: Integrate top toolbar in screen**

Replace `renderToolbar`, `toolButtonRect`, `renderActionStrip`, and `actionIndexAt` with toolbar model rendering and hit testing. Keep `handleAction(String label)` unchanged for action labels.

- [ ] **Step 5: Run tests**

Run: `. gradlew.bat test --tests "*RoadPlannerTopToolbarTest" --tests "*RoadPlannerScreenBehaviorTest"`  
Expected: PASS after updating screen tests to click toolbar item positions instead of old left/bottom positions.

---


### Task 10: Lighter Minimap Palette

**Assigned subagent:** Worker D  
**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapPalette.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerChunkImage.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapTheme.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapPaletteTest.java`

- [ ] **Step 1: Add palette test**

Create `RoadPlannerMapPaletteTest.java` with tests asserting `softenTerrain(0xFF123456)` preserves alpha and increases brightness, and `softenWater(0xFF001155)` remains blue-dominant while lighter.

- [ ] **Step 2: Run failing palette test**

Run: `./gradlew.bat test --tests "*RoadPlannerMapPaletteTest"`  
Expected: FAIL because `RoadPlannerMapPalette` does not exist.

- [ ] **Step 3: Implement palette helper**

Create `RoadPlannerMapPalette.java` with `softenTerrain`, `softenWater`, `softenLoading`, and package-private `mixWith(int argb, int targetArgb, double amount)` methods. Use mix targets `0xFFE8F0D8` for terrain, `0xFF7FCBFF` for water, and `0xFF5A5A5A` for loading tiles.

- [ ] **Step 4: Apply palette to terrain generation**

In `RoadPlannerChunkImage.reliefColor`, wrap land colors with `RoadPlannerMapPalette.softenTerrain(...)` and water colors with `RoadPlannerMapPalette.softenWater(...)`.

- [ ] **Step 5: Apply palette to loading/checker tiles**

In `RoadPlannerTile.createLoadingImage`, use `RoadPlannerMapPalette.softenLoading(checker ? 0xFF3A3A3A : 0xFF303030)`.

- [ ] **Step 6: Lighten grid and background**

In `RoadPlannerMapTheme`, set `GRID = 0x33FFFFFF` and `BACKGROUND = 0xFF2F3438`. Keep destination Town overlay readable with alpha around `0x55` fill and `0xCC` border from Task 3.

- [ ] **Step 7: Run tests**

Run: `./gradlew.bat test --tests "*RoadPlannerMapPaletteTest"`  
Expected: PASS.

---
### Task 11: Tile Cache Scheduler And No-Repeat Rendering

**Assigned subagent:** Worker D  
**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileRenderScheduler.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerForceRenderQueue.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileRenderSchedulerTest.java`

- [ ] **Step 1: Add scheduler test**

Create `RoadPlannerTileRenderSchedulerTest.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerTileRenderSchedulerTest {
    @Test
    void schedulerDoesNotSubmitSameChunkTwice() {
        RoadPlannerTileRenderScheduler scheduler = new RoadPlannerTileRenderScheduler();
        ChunkPos chunk = new ChunkPos(1, 2);

        assertTrue(scheduler.markSubmitted(chunk));
        assertFalse(scheduler.markSubmitted(chunk));
    }
}
```

- [ ] **Step 2: Implement scheduler state**

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.world.level.ChunkPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoadPlannerTileRenderScheduler implements AutoCloseable {
    private final Set<Long> submitted = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public boolean markSubmitted(ChunkPos chunkPos) {
        if (closed || chunkPos == null) return false;
        return submitted.add(chunkPos.toLong());
    }

    public boolean alreadySubmitted(ChunkPos chunkPos) {
        return chunkPos != null && submitted.contains(chunkPos.toLong());
    }

    public void clear() {
        submitted.clear();
    }

    @Override
    public void close() {
        closed = true;
        submitted.clear();
    }
}
```

- [ ] **Step 3: Use scheduler in screen tick**

In `RoadPlannerScreen`, add field:

```java
private final RoadPlannerTileRenderScheduler tileRenderScheduler = new RoadPlannerTileRenderScheduler();
```

In `tick`, skip cached or already submitted chunks:

```java
forceRenderQueue.processChunks(1,
        chunk -> tileManager.hasCachedTileForChunk(chunk) || tileRenderScheduler.alreadySubmitted(chunk),
        chunk -> {
            if (tileRenderScheduler.markSubmitted(chunk)) tileManager.forceRenderChunk(chunk);
        });
```

In `removed`, close scheduler.

- [ ] **Step 4: Fix partial-tile cache logic**

Do not treat a whole cached tile as complete forever if the image is a checker/loading tile. Add `RoadPlannerTile.isLoadingImage()` by tracking `loadedFromCache`. If file does not exist, `hasCachedTileForChunk` returns false. If file exists, skip re-render for now. A later improvement can track per-chunk completeness.

- [ ] **Step 5: Run tests**

Run: `. gradlew.bat test --tests "*RoadPlannerTileRenderSchedulerTest" --tests "*RoadPlannerForceRenderQueueTest"`  
Expected: PASS.

---

### Task 12: Real Ghost Preview Request Packet

**Assigned subagent:** Worker C  
**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerGhostPreviewBridge.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Reference: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java:2732`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacketTest.java`

- [ ] **Step 1: Add packet round-trip test**

Create `RoadPlannerPreviewRequestPacketTest.java`:

```java
package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerPreviewRequestPacketTest {
    @Test
    void roundTripsPreviewRequest() {
        UUID sessionId = UUID.randomUUID();
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                sessionId, "Start", "Dest",
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 65, 0)),
                List.of(RoadPlannerSegmentType.ROAD)
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RoadPlannerPreviewRequestPacket.encode(packet, buffer);
        RoadPlannerPreviewRequestPacket decoded = RoadPlannerPreviewRequestPacket.decode(new FriendlyByteBuf(buffer.copy()));

        assertEquals(sessionId, decoded.sessionId());
        assertEquals(2, decoded.nodes().size());
        assertEquals(RoadPlannerSegmentType.ROAD, decoded.segmentTypes().get(0));
    }
}
```

- [ ] **Step 2: Implement packet codec**

Create packet with fields `UUID sessionId`, `String startTownName`, `String destinationTownName`, `List<BlockPos> nodes`, `List<RoadPlannerSegmentType> segmentTypes`. Reuse `RoadPlannerPacketCodec.writeUuid`, `writeBlockPosList`, and enum list encode/decode pattern from `RoadPlannerAutoCompleteResultPacket`.

- [ ] **Step 3: Implement server handle as safe first pass**

For the first pass, server handler validates `nodes.size() >= 2`. If invalid, do nothing. If valid, create a minimal preview using existing `SyncConstructionGhostPreviewPacket` only if a stable builder method exists. If not, leave a clear failure message to player:

```java
player.sendSystemMessage(Component.literal("閬撹矾棰勮鐢熸垚宸叉敹鍒拌矾绾匡紝涓嬩竴姝ユ帴鍏ユ棫 RoadPlacementPlan 鐢熸垚鍣ㄣ€?));
```

This keeps packet plumbing testable without pretending ghost blocks exist.

- [ ] **Step 4: Update client bridge**

In `RoadPlannerGhostPreviewBridge.submitPreview`, send `RoadPlannerPreviewRequestPacket` to server instead of updating empty local preview. Keep local update only for tests.

- [ ] **Step 5: Register packet**

Register in `ModNetwork` near other roadplanner packets.

- [ ] **Step 6: Run tests**

Run: `. gradlew.bat test --tests "*RoadPlannerPreviewRequestPacketTest" --tests "*RoadPlannerAutoCompletePacketTest"`  
Expected: PASS.

---

### Task 13: Integration Verification And Jar Build

**Assigned subagent:** Main agent  
**Files:**
- Modify tests only if coordinates changed after top-toolbar layout.

- [ ] **Step 1: Run targeted RoadPlanner test suite**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests "*RoadPlanner*" --tests "*OpenRoadPlannerScreenPacketTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run Java compile**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Build jar**

Run:

```powershell
$env:GRADLE_OPTS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat assemble
```

Expected: `BUILD SUCCESSFUL` and `build/libs/sailboatmod-1.3.7-reobf.jar` updated.

- [ ] **Step 4: Clean unrelated logs**

Run:

```powershell
git checkout -- logs 2>$null
Get-ChildItem logs -Filter '2026-04-*.log.gz' -ErrorAction SilentlyContinue | Remove-Item -Force
```

Expected: `git status --short` contains source/test changes only, no `logs/` changes.

- [ ] **Step 5: Manual in-game checklist**

Verify in `runClient` or a dev instance:

1. Without selected destination, normal right-click planner does not open edit UI and shows message.
2. Shift+right-click target Town chunk opens planner with start Town overlay and red destination Town overlay.
3. Hovering red Town region shows tooltip with Town name and chunk coordinate.
4. Map click creates node at visible terrain height, not fixed `Y=64`.
5. Reopen planner with same session restores draft nodes.
6. Top toolbar does not overlap map controls; route info is compact.
7. Select tool selects an existing node.
8. Erase tool deletes a hit node but not the protected start node.
9. Road tool refuses a major bridge span; bridge tool accepts only land-to-land bridge anchors.
10. Confirm build sends preview request and does not create an empty invisible preview.

---

## Self-Review

**Spec coverage:**
- Town red destination overlay: Tasks 2-3.
- Town/Nation UI-like claim display and tooltip: Task 3.
- Coordinate mismatch and node jump: Task 4 plus render-time mutation removal in Task 10.
- Destination-first flow: Task 1.
- Draft persistence: Task 5.
- Select node tool: Task 6.
- Eraser behavior: Task 7.
- Road/bridge rejection and bridge land backtracking: Task 8.
- Top Blender-style toolbar and compact route info: Task 9.
- Minimap lighter palette: Task 10.`r`n- Tick/cache performance: Task 11.
- Ghost preview request: Task 12.
- Verification/jar: Task 13.

**Placeholder scan:** No task contains an unspecified implementation step. Where old ghost preview internals are uncertain, Task 11 deliberately scopes the first pass to packet plumbing and explicit player feedback rather than claiming invisible ghost blocks are fixed.

**Type consistency:** Planned types are `RoadPlannerClaimOverlay`, `RoadPlannerClaimOverlayRenderer`, `RoadPlannerHeightSampler`, `RoadPlannerDraftPersistence`, `RoadPlannerNodeHitTester`, `RoadPlannerNodeSelection`, `RoadPlannerEraseTool`, `RoadPlannerBridgeRuleService`, `RoadPlannerTopToolbar`, `RoadPlannerTileRenderScheduler`, and `RoadPlannerPreviewRequestPacket`. Later tasks reference these exact names.

