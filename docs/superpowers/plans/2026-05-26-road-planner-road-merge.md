# Road Planner Existing Road Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the road planner snap non-bridge planned routes into nearby existing same-nation, allied, or trade-partner road nodes without rebuilding the reused road segment.

**Architecture:** Add a server-authoritative merge candidate service that filters persisted `RoadNetworkRecord` paths by dimension, permissions, diplomacy, distance, and bridge exclusion. Add bounded packets for candidate and overlay sync, then let `RoadPlannerScreen` hold a small selected-candidate state and submit that selection to preview/build. Build registration stores a `roadnode:<roadId>:<pathIndex>` endpoint anchor while keeping path adjacency through the shared `BlockPos`.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, SimpleChannel packets, JUnit 5, Gradle.

---

## File Map

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/model/RoadPlannerMergeScope.java`
  - Planner toggle state: `OWN_NATION`, `ALLIED_OR_TRADE`, `DISABLED`.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/model/RoadPlannerMergeRelationship.java`
  - Candidate relationship class: `OWN`, `ALLIED`, `TRADE`.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/model/RoadPlannerMergeSelection.java`
  - Preview/build selected anchor: road id, path index, anchor position, scope.
- Create `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeService.java`
  - Server candidate filtering, relationship resolution, bridge exclusion, overlay filtering.
- Create `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeServiceTest.java`
  - Core tests for filtering/sorting/bridge exclusion.
- Create `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMergeCandidateRequestPacket.java`
  - Client asks server for candidates near route endpoint.
- Create `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/OpenRoadMergeCandidatesPacket.java`
  - Server returns candidate list to active planner screen.
- Create `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRoadOverlayRequestPacket.java`
  - Client asks server for visible road overlays for current viewport.
- Create `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRoadOverlaySyncPacket.java`
  - Server returns filtered own/allied/trade road paths.
- Modify `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
  - Register the four new packets.
- Modify `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
  - Route candidate and overlay packets into `RoadPlannerScreen`.
- Modify `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacket.java`
  - Encode/decode `RoadPlannerMergeSelection` and validate/snap on server.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java`
  - Store merge selection in preview snapshots and completed builds.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistry.java`
  - Persist merged endpoint as `roadnode:<roadId>:<pathIndex>`.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
  - Add merge scope toggle, candidate state, request triggers, candidate cycling, snap-aware preview submission, and overlay rendering.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTopToolbar.java`
  - Add route actions for merge scope and next merge candidate.
- Modify tests:
  - `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`
  - `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacketTest.java`
  - `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlServiceTest.java`
  - `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistryTest.java`
  - `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`

## Task 1: Domain Models And Server Merge Service

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/model/RoadPlannerMergeScope.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/model/RoadPlannerMergeRelationship.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/model/RoadPlannerMergeSelection.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeService.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Create `RoadPlannerRoadMergeServiceTest` with tests covering:

```java
@Test
void ownNationCandidatesSortByDistanceAndUsePersistedPathNodes() {
    NationSavedDataLike data = dataWithRoads(
            road("far", "nation-a", List.of(pos(40, 64, 0), pos(50, 64, 0))),
            road("near", "nation-a", List.of(pos(8, 64, 0), pos(16, 64, 0))));

    List<RoadPlannerRoadMergeService.Candidate> candidates =
            RoadPlannerRoadMergeService.findCandidatesForTest(
                    data,
                    "nation-a",
                    true,
                    "minecraft:overworld",
                    pos(10, 64, 1),
                    12,
                    RoadPlannerMergeScope.OWN_NATION,
                    RoadPlannerSegmentType.ROAD,
                    RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge());

    assertEquals(List.of("near"), candidates.stream().map(RoadPlannerRoadMergeService.Candidate::roadId).toList());
    assertEquals(pos(8, 64, 0), candidates.get(0).anchorPos());
    assertEquals(0, candidates.get(0).pathIndex());
    assertEquals(RoadPlannerMergeRelationship.OWN, candidates.get(0).relationship());
}

@Test
void alliedAndTradeRoadsRequireExternalScope() {
    NationSavedDataLike data = dataWithRoads(road("ally-road", "nation-b", List.of(pos(8, 64, 0), pos(16, 64, 0))));
    data.putDiplomacy(new NationDiplomacyRecord("nation-a", "nation-b", NationDiplomacyStatus.ALLIED.id(), 1L));

    assertTrue(RoadPlannerRoadMergeService.findCandidatesForTest(
            data, "nation-a", true, "minecraft:overworld", pos(8, 64, 1), 12,
            RoadPlannerMergeScope.OWN_NATION, RoadPlannerSegmentType.ROAD,
            RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge()).isEmpty());

    assertEquals(RoadPlannerMergeRelationship.ALLIED, RoadPlannerRoadMergeService.findCandidatesForTest(
            data, "nation-a", true, "minecraft:overworld", pos(8, 64, 1), 12,
            RoadPlannerMergeScope.ALLIED_OR_TRADE, RoadPlannerSegmentType.ROAD,
            RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge()).get(0).relationship());
}

@Test
void bridgeCurrentSegmentAndBridgeAnchorsAreRejected() {
    NationSavedDataLike data = dataWithRoads(road("road-a", "nation-a", List.of(pos(8, 64, 0), pos(16, 64, 0))));

    assertTrue(RoadPlannerRoadMergeService.findCandidatesForTest(
            data, "nation-a", true, "minecraft:overworld", pos(8, 64, 1), 12,
            RoadPlannerMergeScope.OWN_NATION, RoadPlannerSegmentType.BRIDGE_MAJOR,
            RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge()).isEmpty());

    assertTrue(RoadPlannerRoadMergeService.findCandidatesForTest(
            data, "nation-a", true, "minecraft:overworld", pos(8, 64, 1), 12,
            RoadPlannerMergeScope.OWN_NATION, RoadPlannerSegmentType.ROAD,
            anchor -> true).isEmpty());
}
```

Use a small test adapter around `NationSavedData` if direct construction is simpler in this codebase; keep the production API accepting real `NationSavedData`.

- [ ] **Step 2: Run failing tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPlannerRoadMergeServiceTest"
```

Expected: compile failure because the new model/service classes do not exist.

- [ ] **Step 3: Implement merge model classes**

Create:

```java
package com.monpai.sailboatmod.roadplanner.model;

public enum RoadPlannerMergeScope {
    OWN_NATION,
    ALLIED_OR_TRADE,
    DISABLED;

    public boolean allowsExternalRoads() {
        return this == ALLIED_OR_TRADE;
    }

    public boolean enabled() {
        return this != DISABLED;
    }
}
```

```java
package com.monpai.sailboatmod.roadplanner.model;

public enum RoadPlannerMergeRelationship {
    OWN,
    ALLIED,
    TRADE
}
```

```java
package com.monpai.sailboatmod.roadplanner.model;

import net.minecraft.core.BlockPos;

public record RoadPlannerMergeSelection(String roadId,
                                        int pathIndex,
                                        BlockPos anchorPos,
                                        RoadPlannerMergeScope scope) {
    public RoadPlannerMergeSelection {
        roadId = roadId == null ? "" : roadId.trim().toLowerCase(java.util.Locale.ROOT);
        pathIndex = Math.max(-1, pathIndex);
        anchorPos = anchorPos == null ? BlockPos.ZERO : anchorPos.immutable();
        scope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
    }

    public static RoadPlannerMergeSelection none() {
        return new RoadPlannerMergeSelection("", -1, BlockPos.ZERO, RoadPlannerMergeScope.DISABLED);
    }

    public boolean present() {
        return scope.enabled() && !roadId.isBlank() && pathIndex >= 0;
    }
}
```

- [ ] **Step 4: Implement `RoadPlannerRoadMergeService`**

Implement:

```java
public static List<Candidate> findCandidatesForTest(NationSavedData data,
                                                    String actorNationId,
                                                    boolean canManageOwnRoads,
                                                    String dimensionId,
                                                    BlockPos probe,
                                                    int radius,
                                                    RoadPlannerMergeScope scope,
                                                    RoadPlannerSegmentType currentSegmentType,
                                                    BridgeAnchorClassifier bridgeClassifier)
```

and production wrappers:

```java
public static List<Candidate> findCandidates(ServerPlayer player, BlockPos probe, int radius,
                                             RoadPlannerMergeScope scope, RoadPlannerSegmentType currentSegmentType)

public static Optional<Candidate> validateSelection(ServerPlayer player, BlockPos probe, int radius,
                                                    RoadPlannerMergeSelection selection,
                                                    RoadPlannerSegmentType currentSegmentType)
```

Rules:

- return empty when scope is `DISABLED`
- return empty when current segment is `BRIDGE_SMALL` or `BRIDGE_MAJOR`
- same dimension only
- own nation requires same `road.nationId()` and manage permission
- allied/trade requires `scope.allowsExternalRoads()` and diplomacy status
- candidate anchor must be an existing path node, not a projected segment point
- bridge classifier rejects anchors
- cap output at 16

- [ ] **Step 5: Run tests to green**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPlannerRoadMergeServiceTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/model src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeService.java src/test/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeServiceTest.java
git commit -m "Add road planner merge candidate service"
```

## Task 2: Merge Candidate And Road Overlay Packets

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMergeCandidateRequestPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/OpenRoadMergeCandidatesPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRoadOverlayRequestPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRoadOverlaySyncPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
- Modify: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`

- [ ] **Step 1: Write failing packet round-trip tests**

Add packet round trips for:

```java
OpenRoadMergeCandidatesPacket candidatePacket = new OpenRoadMergeCandidatesPacket(sessionId, List.of(
        new OpenRoadMergeCandidatesPacket.Entry("road_a", new BlockPos(8, 64, 0), 3, 5,
                "Alpha", "Beta", "nation-a", RoadPlannerMergeRelationship.OWN)
));
RoadPlannerMergeCandidateRequestPacket candidateRequest = new RoadPlannerMergeCandidateRequestPacket(
        sessionId, new BlockPos(8, 64, 1), 12, RoadPlannerMergeScope.OWN_NATION, RoadPlannerSegmentType.ROAD);
RoadPlannerRoadOverlayRequestPacket overlayRequest = new RoadPlannerRoadOverlayRequestPacket(
        sessionId, "world_a", "minecraft:overworld", new BlockPos(0, 64, 0), 256, RoadPlannerMergeScope.ALLIED_OR_TRADE);
RoadPlannerRoadOverlaySyncPacket overlaySync = new RoadPlannerRoadOverlaySyncPacket(sessionId, List.of(
        new RoadPlannerRoadOverlaySyncPacket.Entry("road_a", RoadPlannerMergeRelationship.TRADE,
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)))
));
```

Assert decoded packets equal originals.

- [ ] **Step 2: Run failing packet tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest"
```

Expected: compile failure because packet classes do not exist.

- [ ] **Step 3: Implement packet classes**

Use `RoadPlannerPacketCodec` for UUID, strings, and block pos lists. Caps:

- max candidates: 16
- max overlay roads: 128
- max path points per overlay road: 128
- string lengths: road id 128, names 96, nation id 64

Packet handlers:

- request packet calls `RoadPlannerRoadMergeService.findCandidates(...)` and sends `OpenRoadMergeCandidatesPacket`
- candidate sync calls `RoadPlannerClientHooks.applyRoadMergeCandidates(...)`
- overlay request calls `RoadPlannerRoadMergeService.visibleRoadOverlays(...)` and sends `RoadPlannerRoadOverlaySyncPacket`
- overlay sync calls `RoadPlannerClientHooks.applyRoadOverlays(...)`

- [ ] **Step 4: Register packets**

Add imports and registrations in `ModNetwork` after existing road-planner packets. Candidate and overlay sync packets are `PLAY_TO_CLIENT`; request packets are client to server.

- [ ] **Step 5: Add client hook stubs**

Add to `RoadPlannerClientHooks`:

```java
public static void applyRoadMergeCandidates(UUID sessionId, List<OpenRoadMergeCandidatesPacket.Entry> candidates) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.screen instanceof RoadPlannerScreen screen) {
        screen.applyRoadMergeCandidates(sessionId, candidates);
    }
}

public static void applyRoadOverlays(UUID sessionId, List<RoadPlannerRoadOverlaySyncPacket.Entry> roads) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.screen instanceof RoadPlannerScreen screen) {
        screen.applyRoadOverlays(sessionId, roads);
    }
}
```

The `RoadPlannerScreen` methods can be no-op placeholders in this task if Task 4 has not implemented state yet.

- [ ] **Step 6: Run tests to green**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/network/packet/roadplanner src/main/java/com/monpai/sailboatmod/network/ModNetwork.java src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java
git commit -m "Add road planner merge packets"
```

## Task 3: Preview, Build, And Registry Merge Validation

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistry.java`
- Modify: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacketTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistryTest.java`

- [ ] **Step 1: Write failing preview packet and registry tests**

Add tests that prove:

- `RoadPlannerPreviewRequestPacket` round-trips `RoadPlannerMergeSelection`
- a preview request with selection exposes snapped nodes for build control
- completed build registration uses `roadnode:<roadId>:<pathIndex>` for the selected endpoint

Registry assertion:

```java
RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection(
        "existing-road", 4, new BlockPos(16, 64, 0), RoadPlannerMergeScope.OWN_NATION);
RoadPlannerBuiltRoadRegistry.register(level, new RoadPlannerBuildControlService.CompletedRoadBuild(
        "connector", ownerId, centerPath, buildSteps, rollbackEntries, Level.OVERWORLD, selection));

RoadNetworkRecord road = data.getRoadNetwork("connector");
assertEquals("roadnode:existing-road:4", road.structureBId());
```

- [ ] **Step 2: Run failing tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacketTest" --tests "com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadRegistryTest" --tests "com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlServiceTest"
```

Expected: compile failure or assertion failure because merge selection is not integrated.

- [ ] **Step 3: Extend preview request encoding**

Add a `RoadPlannerMergeSelection mergeSelection` field to `RoadPlannerPreviewRequestPacket`.

Encoding order after build settings:

```java
buffer.writeBoolean(packet.mergeSelection().present());
if (packet.mergeSelection().present()) {
    RoadPlannerPacketCodec.writeString(buffer, packet.mergeSelection().roadId(), 128);
    buffer.writeVarInt(packet.mergeSelection().pathIndex());
    buffer.writeBlockPos(packet.mergeSelection().anchorPos());
    buffer.writeEnum(packet.mergeSelection().scope());
}
```

Decode must treat missing trailing data as `RoadPlannerMergeSelection.none()` for compatibility.

- [ ] **Step 4: Validate and snap on server**

In `RoadPlannerPreviewRequestPacket.handle`, before starting preview:

```java
RoadPlannerPreviewRequestPacket safePacket = packet.withServerValidatedMerge(player);
RoadPlannerBuildControlService.global().startPreview(player.getUUID(), safePacket.nodes(), safePacket.segmentTypes(), safePacket.settings(), safePacket.mergeSelection());
ModNetwork.CHANNEL.sendTo(safePacket.toSafePreview(player.serverLevel()), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
```

`withServerValidatedMerge` calls `RoadPlannerRoadMergeService.validateSelection(...)`, replaces the final node with the validated anchor, and clears selection when invalid.

- [ ] **Step 5: Store selection through build control**

Add overloads:

```java
public UUID startPreview(UUID playerId, List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes,
                         RoadPlannerBuildSettings settings, RoadPlannerMergeSelection mergeSelection)
```

Extend `PreviewSnapshot` and `CompletedRoadBuild` with `RoadPlannerMergeSelection`.

- [ ] **Step 6: Persist merge endpoint anchor**

In `RoadPlannerBuiltRoadRegistry.register`, use:

```java
String endAnchor = build.mergeSelection().present()
        ? "roadnode:" + build.mergeSelection().roadId() + ":" + build.mergeSelection().pathIndex()
        : plannerAnchorId("end", path.get(path.size() - 1));
```

Keep the start anchor unchanged.

- [ ] **Step 7: Run tests to green**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacketTest" --tests "com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadRegistryTest" --tests "com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlServiceTest"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacket.java src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistry.java src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacketTest.java src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlServiceTest.java src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistryTest.java
git commit -m "Validate road planner merge previews"
```

## Task 4: Planner Client State, Toolbar Actions, And Candidate Requests

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTopToolbar.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerGhostPreviewBridge.java`
- Modify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`

- [ ] **Step 1: Write failing client behavior tests**

Add tests to `RoadPlannerScreenBehaviorTest`:

```java
@Test
void mergeCandidatePacketSelectsNearestAndCyclesCandidates() {
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
    UUID session = screen.state().sessionId();

    screen.applyRoadMergeCandidates(session, List.of(
            new OpenRoadMergeCandidatesPacket.Entry("road-a", new BlockPos(8, 64, 0), 0, 8, "A", "B", "nation-a", RoadPlannerMergeRelationship.OWN),
            new OpenRoadMergeCandidatesPacket.Entry("road-b", new BlockPos(12, 64, 0), 1, 12, "C", "D", "nation-a", RoadPlannerMergeRelationship.OWN)));

    assertEquals("road-a", screen.selectedMergeRoadIdForTest());
    screen.handleActionForTest(RoadPlannerTopToolbar.ACTION_NEXT_MERGE);
    assertEquals("road-b", screen.selectedMergeRoadIdForTest());
}

@Test
void bridgeToolDoesNotRequestMergeCandidates() {
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
    RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
    clickToolbarTool(screen, RoadToolType.BRIDGE);

    screen.mouseClicked(map.x() + 120, map.y() + 120, 0);

    assertFalse(screen.lastMergeCandidateRequestForTest().isPresent());
}
```

- [ ] **Step 2: Run failing client tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest"
```

Expected: compile failure for missing actions/test helpers.

- [ ] **Step 3: Add toolbar actions**

In `RoadPlannerTopToolbar`, add:

```java
public static final String ACTION_MERGE_SCOPE = "吸附范围";
public static final String ACTION_NEXT_MERGE = "切换并入";
```

Add both to the `Group.ROUTE` dropdown after auto-complete and before confirm build. Increase route dropdown height to 5 rows.

- [ ] **Step 4: Add client state and packet handlers**

In `RoadPlannerScreen`, add fields:

```java
private RoadPlannerMergeScope mergeScope = RoadPlannerMergeScope.OWN_NATION;
private List<OpenRoadMergeCandidatesPacket.Entry> mergeCandidates = List.of();
private int selectedMergeCandidateIndex = -1;
private RoadPlannerMergeCandidateRequestPacket lastMergeCandidateRequest;
private List<RoadPlannerRoadOverlaySyncPacket.Entry> roadOverlays = List.of();
```

Implement:

```java
public void applyRoadMergeCandidates(UUID sessionId, List<OpenRoadMergeCandidatesPacket.Entry> candidates)
public void applyRoadOverlays(UUID sessionId, List<RoadPlannerRoadOverlaySyncPacket.Entry> roads)
private void requestMergeCandidates(BlockPos probe, RoadPlannerSegmentType segmentType)
private RoadPlannerMergeSelection selectedMergeSelection()
private void cycleMergeScope()
private void cycleMergeCandidate()
```

Test mode stores requests in `lastMergeCandidateRequest`; non-test mode sends packets through `ModNetwork.CHANNEL`.

- [ ] **Step 5: Trigger candidate requests**

After successful non-bridge node placement and after `applyAutoCompleteResult`, call `requestMergeCandidates(lastNode, lastSegmentType)`.

Do not request candidates when:

- scope is `DISABLED`
- active tool is `BRIDGE` or `WATER_CROSSING`
- final segment type is `BRIDGE_SMALL`, `BRIDGE_MAJOR`, or `BLOCKED_REQUIRES_BRIDGE`

- [ ] **Step 6: Submit merge selection with preview**

Update `RoadPlannerGhostPreviewBridge.submitPreview` and `submitPreviewWithSettings` call sites to accept `RoadPlannerMergeSelection`. The selection must be sent through `RoadPlannerPreviewRequestPacket`; the editable `linePlan` remains unchanged.

- [ ] **Step 7: Run tests to green**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTopToolbar.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerGhostPreviewBridge.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java
git commit -m "Add road planner merge candidate controls"
```

## Task 5: Minimap Road Overlay Rendering

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Modify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`

- [ ] **Step 1: Write failing overlay tests**

Add tests that verify:

```java
@Test
void roadOverlayPacketReplacesVisibleOverlayStateForMatchingSession() {
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
    UUID session = screen.state().sessionId();

    screen.applyRoadOverlays(session, List.of(new RoadPlannerRoadOverlaySyncPacket.Entry(
            "road-a", RoadPlannerMergeRelationship.TRADE,
            List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)))));

    assertEquals(1, screen.roadOverlayCountForTest());
}

@Test
void staleRoadOverlayPacketIsIgnored() {
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);

    screen.applyRoadOverlays(UUID.randomUUID(), List.of(new RoadPlannerRoadOverlaySyncPacket.Entry(
            "road-a", RoadPlannerMergeRelationship.TRADE,
            List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)))));

    assertEquals(0, screen.roadOverlayCountForTest());
}
```

- [ ] **Step 2: Run failing tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest"
```

Expected: compile failure for missing helpers or assertion failure.

- [ ] **Step 3: Render overlay paths**

In `renderRoadOverlay`, draw synced built-road overlays before the editable route:

- `OWN`: existing built-road color or `0xCC8BD3FF`
- `ALLIED` and `TRADE`: shared other-road color `0xCCB18CFF`
- selected merge anchor: `0xFFFFF176` cross marker

Do not draw overlay roads when `mergeScope == DISABLED`.

- [ ] **Step 4: Request overlays on viewport/scope changes**

Add `requestRoadOverlays()` that sends `RoadPlannerRoadOverlayRequestPacket` for the current map center, region size, and `mergeScope`.

Call it:

- after initial map request
- after viewport map request
- after changing merge scope

In test mode, avoid network sends and only expose state through test helpers.

- [ ] **Step 5: Run tests to green**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java
git commit -m "Render road planner merge overlays"
```

## Task 6: Full Verification And Integration Review

**Files:**
- No planned code changes unless verification finds a bug.

- [ ] **Step 1: Run focused tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPlannerRoadMergeServiceTest" --tests "com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest" --tests "com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacketTest" --tests "com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadRegistryTest" --tests "com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlServiceTest" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest"
```

Expected: PASS.

- [ ] **Step 2: Compile Java**

```powershell
.\gradlew.bat compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Review changed files against spec**

Check:

- bridge segment never requests/applies merge
- allied/trade roads require `ALLIED_OR_TRADE`
- endpoint is a persisted path node
- terrain tile cache is not used for relationship-specific road overlay colors
- preview/build uses server-validated selection

- [ ] **Step 4: Commit verification fixes if needed**

Only if fixes are required:

```powershell
git add <changed-files>
git commit -m "Polish road planner merge integration"
```
