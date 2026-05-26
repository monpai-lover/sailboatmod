# Road Planner Built Road Tooltip Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show built roads on the road planner minimap and display route metadata in a hover tooltip.

**Architecture:** Extend the existing server-authoritative road overlay flow instead of adding per-hover network requests. Persist creator metadata on new road records, project display metadata server-side, send it through `RoadPlannerRoadOverlaySyncPacket`, then perform client-side map hit testing and tooltip rendering against synced overlay entries.

**Tech Stack:** Java 17, Forge 1.20.1, Minecraft `FriendlyByteBuf`, existing road planner screen and JUnit 5 tests.

---

## File Map

- Modify `src/main/java/com/monpai/sailboatmod/nation/model/RoadNetworkRecord.java`
  - Add creator UUID/name and created-at fields with backward-compatible NBT loading.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistry.java`
  - Populate new creator metadata for planner-built roads.
- Modify `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistryTest.java`
  - Verify new metadata persists through completed build registration.
- Modify `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeService.java`
  - Project display metadata into road overlays.
- Modify `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeServiceTest.java`
  - Verify overlay display name, length, creator fallback, and created-at fallback.
- Modify `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRoadOverlaySyncPacket.java`
  - Encode/decode overlay metadata.
- Modify `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRoadOverlayRequestPacket.java`
  - Map service overlay metadata into packet entries.
- Modify `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`
  - Verify overlay metadata round-trips and legacy convenience constructors remain usable.
- Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoadOverlayHitTester.java`
  - Find the closest road overlay line segment under the mouse.
- Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoadOverlayHitTesterTest.java`
  - Verify threshold, tie breaking, selected road preference, and own-road preference.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
  - Highlight hovered overlay road and render tooltip.
- Modify `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`
  - Verify screen exposes overlay tooltip state for matching road metadata.

## Task 1: Persist Road Creator Metadata

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/model/RoadNetworkRecord.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistry.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistryTest.java`

- [ ] **Step 1: Add failing registry metadata assertion**

Add assertions to `registerCompletedBuildCreatesDemolishableRoadRecordAndRuntimePlan`:

```java
assertEquals(ownerId.toString(), road.creatorUuid());
assertEquals("Builder", road.creatorName());
assertTrue(road.createdAt() > 0L);
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadRegistryTest"
```

Expected: compile failure or assertion failure because `creatorUuid`, `creatorName`, and `createdAt` do not exist or are blank.

- [ ] **Step 3: Extend `RoadNetworkRecord`**

Add record components after `updatedAt`:

```java
long createdAt,
String creatorUuid,
String creatorName,
```

In the compact constructor:

```java
createdAt = createdAt <= 0L ? updatedAt : createdAt;
creatorUuid = creatorUuid == null ? "" : creatorUuid.trim();
creatorName = creatorName == null ? "" : creatorName.trim();
```

Add a backward-compatible convenience constructor matching the old signature:

```java
public RoadNetworkRecord(String roadId, String nationId, String townId, String dimensionId,
                         String structureAId, String structureBId, List<BlockPos> path,
                         long updatedAt, String sourceType) {
    this(roadId, nationId, townId, dimensionId, structureAId, structureBId, path,
            updatedAt, updatedAt, "", "", sourceType);
}
```

Update `save()`:

```java
tag.putLong("CreatedAt", createdAt);
tag.putString("CreatorUuid", creatorUuid);
tag.putString("CreatorName", creatorName);
```

Update `load()` to read fallback values:

```java
long updatedAt = tag.getLong("UpdatedAt");
long createdAt = tag.contains("CreatedAt") ? tag.getLong("CreatedAt") : updatedAt;
String creatorUuid = tag.contains("CreatorUuid") ? tag.getString("CreatorUuid") : "";
String creatorName = tag.contains("CreatorName") ? tag.getString("CreatorName") : "";
```

Pass those values into the new canonical constructor.

- [ ] **Step 4: Populate metadata in `RoadPlannerBuiltRoadRegistry`**

Before constructing `RoadNetworkRecord`, derive creator fields:

```java
long now = System.currentTimeMillis();
String creatorUuid = build.ownerId() == null ? "" : build.ownerId().toString();
String creatorName = creatorName(data, build.ownerId());
```

Use `now` for both updated and created time in new road records.

Add helper:

```java
private static String creatorName(NationSavedData data, UUID ownerId) {
    if (data == null || ownerId == null) {
        return "";
    }
    NationMemberRecord member = data.getMember(ownerId);
    return member == null || member.playerName() == null ? "" : member.playerName();
}
```

- [ ] **Step 5: Run registry test**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadRegistryTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/nation/model/RoadNetworkRecord.java src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistry.java src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistryTest.java
git commit -m "Persist road creator metadata"
```

## Task 2: Add Server Overlay Display Metadata

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeService.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeServiceTest.java`

- [ ] **Step 1: Add failing overlay metadata test**

Add a test that creates towns `alpha-town` and `beta-town`, a road with path `(0,64,0)->(3,64,4)`, creator fields, and calls `visibleRoadOverlaysForTest`. Assert:

```java
assertEquals("Alpha - Beta", overlay.displayName());
assertEquals(5, overlay.lengthBlocks());
assertEquals("Builder", overlay.creatorName());
assertEquals("creator-uuid", overlay.creatorUuid());
assertEquals(1234L, overlay.createdAt());
assertFalse(overlay.legacyMetadata());
```

Add a second legacy test using the old constructor and assert:

```java
assertEquals(99L, overlay.createdAt());
assertTrue(overlay.legacyMetadata());
assertEquals("", overlay.creatorName());
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPlannerRoadMergeServiceTest"
```

Expected: compile failure because `RoadOverlay` lacks metadata fields.

- [ ] **Step 3: Extend `RoadOverlay` record**

Replace the existing record with:

```java
public record RoadOverlay(String roadId,
                          RoadPlannerMergeRelationship relationship,
                          List<BlockPos> path,
                          String displayName,
                          int lengthBlocks,
                          String creatorName,
                          String creatorUuid,
                          long createdAt,
                          boolean legacyMetadata) {
    public RoadOverlay {
        roadId = roadId == null ? "" : roadId;
        relationship = relationship == null ? RoadPlannerMergeRelationship.OWN : relationship;
        path = path == null ? List.of() : List.copyOf(path);
        displayName = displayName == null || displayName.isBlank() ? roadId : displayName.trim();
        lengthBlocks = Math.max(0, lengthBlocks);
        creatorName = creatorName == null ? "" : creatorName.trim();
        creatorUuid = creatorUuid == null ? "" : creatorUuid.trim();
        createdAt = Math.max(0L, createdAt);
    }
}
```

- [ ] **Step 4: Project metadata in `visibleRoadOverlays`**

Replace overlay construction:

```java
overlays.add(new RoadOverlay(
        road.roadId(),
        relationship,
        visiblePath,
        displayName(data, road),
        lengthBlocks(road.path()),
        road.creatorName(),
        road.creatorUuid(),
        road.createdAt(),
        road.creatorUuid().isBlank() && road.creatorName().isBlank() && road.createdAt() == road.updatedAt()));
```

Add helpers:

```java
private static String displayName(NationSavedData data, RoadNetworkRecord road) {
    if (road == null) {
        return "";
    }
    String left = endpointName(data, road.structureAId());
    String right = endpointName(data, road.structureBId());
    if (!left.isBlank() && !right.isBlank() && !left.equals(right)) {
        return left + " - " + right;
    }
    return road.roadId();
}

private static String endpointName(NationSavedData data, String endpoint) {
    String value = endpoint == null ? "" : endpoint.trim();
    if (value.startsWith("town:")) {
        String townId = value.substring("town:".length());
        com.monpai.sailboatmod.nation.model.TownRecord town = data == null ? null : data.getTown(townId);
        return town == null || town.name().isBlank() ? townId : town.name();
    }
    if (value.startsWith("roadnode:")) {
        return "Road Link";
    }
    if (value.startsWith("planner:")) {
        return "Planner";
    }
    return value;
}

private static int lengthBlocks(List<BlockPos> path) {
    if (path == null || path.size() < 2) {
        return 0;
    }
    double length = 0.0D;
    for (int index = 1; index < path.size(); index++) {
        BlockPos previous = path.get(index - 1);
        BlockPos current = path.get(index);
        if (previous != null && current != null) {
            length += Math.sqrt(previous.distSqr(current));
        }
    }
    return (int) Math.round(length);
}
```

`endpointName` should resolve `town:<id>` through `data.getTown(id).name()`, return `Road Link` for `roadnode:`, compact planner anchors to `Planner`, and otherwise return the endpoint string.

- [ ] **Step 5: Run overlay service tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPlannerRoadMergeServiceTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeService.java src/test/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeServiceTest.java
git commit -m "Add road overlay display metadata"
```

## Task 3: Extend Road Overlay Packet Metadata

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRoadOverlaySyncPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRoadOverlayRequestPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`

- [ ] **Step 1: Add failing packet assertions**

Update `mergeCandidateAndOverlayPacketsRoundTrip` overlay entry to include:

```java
new RoadPlannerRoadOverlaySyncPacket.Entry(
        "road_a",
        RoadPlannerMergeRelationship.TRADE,
        List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
        "Alpha - Beta",
        8,
        "Builder",
        "uuid-a",
        1234L,
        false)
```

Assert decoded metadata equals the original entry.

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest"
```

Expected: compile failure because the packet entry lacks metadata constructor/components.

- [ ] **Step 3: Encode/decode metadata**

In `RoadPlannerRoadOverlaySyncPacket.encode`, after path:

```java
RoadPlannerPacketCodec.writeString(buffer, entry.displayName(), 128);
buffer.writeVarInt(entry.lengthBlocks());
RoadPlannerPacketCodec.writeString(buffer, entry.creatorName(), 64);
RoadPlannerPacketCodec.writeString(buffer, entry.creatorUuid(), 64);
buffer.writeLong(entry.createdAt());
buffer.writeBoolean(entry.legacyMetadata());
```

In `decode`, read the same fields after `readCappedBlockPosList(buffer)`.

Extend `Entry` with metadata fields and keep the old constructor:

```java
public Entry(String roadId, RoadPlannerMergeRelationship relationship, List<BlockPos> path) {
    this(roadId, relationship, path, roadId, 0, "", "", 0L, true);
}
```

- [ ] **Step 4: Map service overlays into packet metadata**

In `RoadPlannerRoadOverlayRequestPacket.handleOnServer`, map:

```java
new RoadPlannerRoadOverlaySyncPacket.Entry(
        overlay.roadId(),
        overlay.relationship(),
        overlay.path(),
        overlay.displayName(),
        overlay.lengthBlocks(),
        overlay.creatorName(),
        overlay.creatorUuid(),
        overlay.createdAt(),
        overlay.legacyMetadata())
```

- [ ] **Step 5: Run packet tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRoadOverlaySyncPacket.java src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRoadOverlayRequestPacket.java src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java
git commit -m "Sync road overlay tooltip metadata"
```

## Task 4: Add Client Road Overlay Hit Testing

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoadOverlayHitTester.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoadOverlayHitTesterTest.java`

- [ ] **Step 1: Write failing hit tester tests**

Create tests for:

```java
RoadPlannerRoadOverlayHitTester.Result hit = RoadPlannerRoadOverlayHitTester.find(
        50, 3, List.of(overlay("road_a", OWN, p(0,0), p(100,0))),
        pos -> pos.getX(), pos -> pos.getZ(), RoadPlannerMergeSelection.none(), 5);
assertTrue(hit.hit());
assertEquals("road_a", hit.entry().roadId());
```

Add tests for outside threshold, selected road tie preference, and own-road preference over trade when distances are equal.

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerRoadOverlayHitTesterTest"
```

Expected: compile failure because the hit tester does not exist.

- [ ] **Step 3: Implement hit tester**

Create a final utility with:

```java
public static Result find(double mouseX,
                          double mouseY,
                          List<RoadPlannerRoadOverlaySyncPacket.Entry> overlays,
                          ToIntFunction<BlockPos> screenX,
                          ToIntFunction<BlockPos> screenY,
                          RoadPlannerMergeSelection selectedMerge,
                          double thresholdPixels)
```

`Result` should contain:

```java
public record Result(RoadPlannerRoadOverlaySyncPacket.Entry entry,
                     int segmentIndex,
                     double distancePixels) {
    public boolean hit() { return entry != null; }
    public static Result miss() { return new Result(null, -1, Double.POSITIVE_INFINITY); }
}
```

Compute point-to-segment distance with clamped projection. Tie-breaking:

1. selected merge road id
2. smaller distance
3. own relationship before external
4. road id string

- [ ] **Step 4: Run hit tester tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerRoadOverlayHitTesterTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoadOverlayHitTester.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoadOverlayHitTesterTest.java
git commit -m "Add road overlay hover hit testing"
```

## Task 5: Render Hover Highlight And Tooltip In Road Planner Screen

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`

- [ ] **Step 1: Add failing screen behavior test**

Add a test that applies an overlay with metadata, calls a for-test hover method near the road line, and asserts:

```java
RoadPlannerScreen.RoadOverlayTooltipForTest tooltip = screen.roadOverlayTooltipForTest(mouseX, mouseY);
assertEquals("Alpha - Beta", tooltip.displayName());
assertTrue(tooltip.lines().contains("Length: 8 blocks"));
assertTrue(tooltip.lines().contains("Creator: Builder"));
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest"
```

Expected: compile failure because the for-test tooltip accessor does not exist.

- [ ] **Step 3: Add screen tooltip state helpers**

In `RoadPlannerScreen`, add:

```java
private static final double ROAD_OVERLAY_HOVER_THRESHOLD = 5.0D;
```

Add:

```java
private RoadPlannerRoadOverlayHitTester.Result hoveredRoadOverlay(int mouseX, int mouseY) {
    RoadPlannerMapLayout.Rect map = mapLayout.map();
    if (!map.contains(mouseX, mouseY)) {
        return RoadPlannerRoadOverlayHitTester.Result.miss();
    }
    return RoadPlannerRoadOverlayHitTester.find(
            mouseX,
            mouseY,
            roadOverlays,
            pos -> mapView.worldToScreenX(pos.getX(), map),
            pos -> mapView.worldToScreenZ(pos.getZ(), map),
            selectedMergeSelection(),
            ROAD_OVERLAY_HOVER_THRESHOLD);
}

private List<Component> roadOverlayTooltipLines(RoadPlannerRoadOverlaySyncPacket.Entry entry) {
    if (entry == null) {
        return List.of();
    }
    return List.of(
            Component.literal(entry.displayName()),
            Component.literal("Length: " + entry.lengthBlocks() + " blocks"),
            Component.literal("Creator: " + (entry.creatorName().isBlank() ? "Unknown" : entry.creatorName())),
            Component.literal("Created: " + roadOverlayCreatedText(entry)));
}
```

Use `mapView.worldToScreenX/Z` as the mapping functions.

- [ ] **Step 4: Render hover highlight and tooltip**

In `renderSyncedRoadOverlays`, compute the hovered road once and draw an extra line pass with a stronger color for that road.

In the screen `render` method after claim tooltip rendering, add:

```java
RoadPlannerRoadOverlayHitTester.Result roadHit = hoveredRoadOverlay(mouseX, mouseY);
if (roadHit.hit()) {
    graphics.renderComponentTooltip(font, roadOverlayTooltipLines(roadHit.entry()), mouseX, mouseY);
}
```

Format created time with a simple local date-time formatter using `java.time.Instant` and system zone. If timestamp is `0`, show `Created: Unknown`. If legacy, show `Created: Old road data`.

- [ ] **Step 5: Add for-test accessor**

Add:

```java
public RoadOverlayTooltipForTest roadOverlayTooltipForTest(int mouseX, int mouseY) {
    RoadPlannerRoadOverlayHitTester.Result hit = hoveredRoadOverlay(mouseX, mouseY);
    if (!hit.hit()) {
        return null;
    }
    List<String> lines = roadOverlayTooltipLines(hit.entry()).stream()
            .map(Component::getString)
            .toList();
    return new RoadOverlayTooltipForTest(hit.entry().roadId(), hit.entry().displayName(), lines);
}

public record RoadOverlayTooltipForTest(String roadId, String displayName, List<String> lines) { }
```

- [ ] **Step 6: Run screen behavior tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java
git commit -m "Show built road tooltips on planner map"
```

## Task 6: Final Verification

**Files:**
- No code edits unless verification exposes a defect.

- [ ] **Step 1: Run focused regression matrix**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadRegistryTest" --tests "com.monpai.sailboatmod.nation.service.RoadPlannerRoadMergeServiceTest" --tests "com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerRoadOverlayHitTesterTest" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run compile check**

Run:

```powershell
.\gradlew.bat compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Check scoped status**

Run:

```powershell
git status --short
```

Expected: only unrelated existing dirty files outside this feature remain.

- [ ] **Step 4: Report manual test list**

Report that the user should manually verify:

- built own roads appear on the planner minimap
- hover tooltip shows route name, length, creator, and time
- allied/trade roads appear when external scope is enabled
- snapping disabled does not prevent visual road hover
- bridge roads may display but cannot be selected as merge anchors
