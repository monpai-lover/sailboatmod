# Road Network Reuse Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a durable road graph so new roads can reuse existing graph spans, render cleanly, route logistics/carriages, and refresh minimap tiles without black stale backgrounds.

**Architecture:** Add a new `RoadNetworkGraphSavedData` store as the source of truth for new roads while leaving legacy `RoadNetworkRecord` roads visible only. Port the RoadWeaver segment placement, spatial grid, and snapping/run-detection ideas into focused graph services, then route build completion, overlays, and logistics through those services.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, SavedData/NBT, JUnit 5, existing road planner map/tile packets, selected MIT-licensed RoadWeaver algorithms.

---

## Implementation Rules

- Work from `F:\Codex\sailboatmod`.
- Do not edit `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury`.
- Do not stage logs, draft files, generated jars, `roadplanner_drafts_test`, or unrelated user changes.
- Keep legacy `NationSavedData.RoadNetworks` readable and visible.
- Do not migrate legacy roads into the new graph.
- Use short imperative commit subjects without prefixes, matching repository history.
- Use TDD per task: write the failing test, run it, implement, rerun, commit.

## File Structure

Create or extend these focused units.

Graph persistence and model:

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphSegmentPlacement.java`
  - RoadWeaver-style `middlePos + positions` record with NBT helpers.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphReuseSpan.java`
  - Stores edge-to-edge reuse ranges.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphNodeRecord.java`
  - Persisted node data.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphEdgeRecord.java`
  - Persisted edge data, including centerline, display path, placements, owned blocks, reuse spans, metadata, status.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadNetworkGraphSavedData.java`
  - New `SavedData` store with `DATA_NAME = "sailboatmod_road_graphs"`.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphRepository.java`
  - Read/write facade around saved data and runtime indexes.

Indexing and reuse:

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphSpatialIndex.java`
  - Adapt RoadWeaver grid/LRU spatial indexing to graph records.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadReusePlan.java`
  - Immutable result for planner reuse detection.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphReusePlanner.java`
  - Adapt RoadWeaver run detection and direction compatibility.

Build lifecycle:

- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java`
  - Carry `RoadReusePlan` through preview confirmation.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistry.java`
  - Write graph edges/nodes and reuse spans.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadMapRefresh.java`
  - Accept graph placements/owned blocks as touched chunks.

Overlay and routing:

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphOverlayService.java`
  - Viewport query for graph roads plus weak legacy overlays.
- Modify `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeService.java`
  - Delegate new reuse candidates and overlays to graph services while keeping legacy overlay read path.
- Keep `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerRoadOverlaySyncPacket.java` unchanged in this first implementation.
  - Existing string road ids can carry graph edge UUID strings.
- Create `src/main/java/com/monpai/sailboatmod/route/RoadGraphRoutingService.java`
  - Graph shortest-path and corridor membership service.
- Modify `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
  - Use `RoadGraphRoutingService` instead of old road path adjacency.
- Modify `src/main/java/com/monpai/sailboatmod/route/CarriageRoutePlanner.java`
  - Classify graph corridor membership via graph service.

Map tile safety:

- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java`
  - Add known-pixel-safe merge behavior.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJob.java`
  - Preserve complete built-road refresh behavior and avoid emitting unsafe unknown pixels.

Tests:

- Create `src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadNetworkGraphSavedDataTest.java`
- Create `src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphSpatialIndexTest.java`
- Create `src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphReusePlannerTest.java`
- Create `src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphOverlayServiceTest.java`
- Create `src/test/java/com/monpai/sailboatmod/route/RoadGraphRoutingServiceTest.java`
- Modify `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistryTest.java`
- Modify `src/test/java/com/monpai/sailboatmod/route/CarriageRoutePlannerTest.java`
- Modify `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJobTest.java`
- Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileMergeRulesTest.java`

Docs and attribution:

- Create: `NOTICE`
  - Credit RoadWeaver MIT adaptations.

---

### Task 1: Graph Persistence Records

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphSegmentPlacement.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphReuseSpan.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphNodeRecord.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphEdgeRecord.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadNetworkGraphSavedData.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadNetworkGraphSavedDataTest.java`

- [ ] **Step 1: Write the failing persistence test**

Add this test class:

```java
package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadNetworkGraphSavedDataTest {
    @Test
    void savesAndLoadsNodesEdgesPlacementsOwnedBlocksAndReuseSpans() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        UUID sourceEdge = UUID.randomUUID();
        UUID edge = UUID.randomUUID();

        RoadNetworkGraphSavedData data = new RoadNetworkGraphSavedData();
        data.putNode(new RoadGraphNodeRecord(nodeA, "minecraft:overworld", new BlockPos(0, 64, 0),
                RoadGraphNodeRecord.Kind.TOWN_CONNECTION, "alpha", "town-a", "town:town-a", 10L, 11L));
        data.putNode(new RoadGraphNodeRecord(nodeB, "minecraft:overworld", new BlockPos(12, 64, 0),
                RoadGraphNodeRecord.Kind.JUNCTION, "alpha", "town-a", "", 10L, 11L));
        data.putEdge(new RoadGraphEdgeRecord(
                edge,
                nodeA,
                nodeB,
                "minecraft:overworld",
                "alpha",
                "town-a",
                "creator-uuid",
                "Planner",
                "Town A",
                "Town B",
                "Town A - Town B",
                5,
                CompiledRoadSectionType.ROAD,
                RoadGraphEdgeRecord.Status.BUILT,
                List.of(new BlockPos(0, 64, 0), new BlockPos(6, 64, 0), new BlockPos(12, 64, 0)),
                List.of(new BlockPos(0, 64, 0), new BlockPos(12, 64, 0)),
                List.of(new RoadGraphSegmentPlacement(new BlockPos(6, 64, 0),
                        List.of(new BlockPos(6, 64, -1), new BlockPos(6, 64, 0), new BlockPos(6, 64, 1)))),
                List.of(new BlockPos(6, 64, -1), new BlockPos(6, 64, 0), new BlockPos(6, 64, 1)),
                List.of(new RoadGraphReuseSpan(sourceEdge, 2, 5, 0, 3,
                        new BlockPos(0, 64, 0), new BlockPos(6, 64, 0),
                        RoadGraphReuseSpan.Relationship.OWN)),
                20L,
                21L));

        CompoundTag saved = data.save(new CompoundTag());
        RoadNetworkGraphSavedData loaded = RoadNetworkGraphSavedData.load(saved);

        assertEquals(2, loaded.nodes().size());
        assertEquals(1, loaded.edges().size());
        RoadGraphEdgeRecord loadedEdge = loaded.getEdge(edge).orElseThrow();
        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(12, 64, 0)), loadedEdge.displayPath());
        assertEquals(1, loadedEdge.placements().size());
        assertEquals(3, loadedEdge.ownedBlockPositions().size());
        assertEquals(sourceEdge, loadedEdge.reuseSpans().get(0).sourceEdgeId());
        assertFalse(loaded.isDirtyForTest());
    }

    @Test
    void invalidEdgesWithMissingNodesAreIgnoredOnLoad() {
        UUID edge = UUID.randomUUID();
        CompoundTag saved = new RoadNetworkGraphSavedData()
                .withEdgeForTest(new RoadGraphEdgeRecord(edge, UUID.randomUUID(), UUID.randomUUID(),
                        "minecraft:overworld", "alpha", "", "", "", "", "", "broken", 3,
                        CompiledRoadSectionType.ROAD, RoadGraphEdgeRecord.Status.BUILT,
                        List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)),
                        List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)),
                        List.of(), List.of(), List.of(), 1L, 1L))
                .save(new CompoundTag());

        RoadNetworkGraphSavedData loaded = RoadNetworkGraphSavedData.load(saved);

        assertFalse(loaded.getEdge(edge).isPresent());
    }
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraphSavedDataTest
```

Expected: compile failure because the new graph record classes do not exist.

- [ ] **Step 3: Add `RoadGraphSegmentPlacement`**

Use this class body. Keep the attribution comment because the data shape is copied from RoadWeaver's `RoadSegmentPlacement`.

```java
package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * Adapted from RoadWeaver's RoadSegmentPlacement model: center point plus footprint positions.
 */
public record RoadGraphSegmentPlacement(BlockPos middlePos, List<BlockPos> positions) {
    public RoadGraphSegmentPlacement {
        middlePos = middlePos == null ? BlockPos.ZERO : middlePos.immutable();
        positions = positions == null ? List.of() : positions.stream()
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Middle", middlePos.asLong());
        ListTag positionsTag = new ListTag();
        for (BlockPos position : positions) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", position.asLong());
            positionsTag.add(entry);
        }
        tag.put("Positions", positionsTag);
        return tag;
    }

    public static RoadGraphSegmentPlacement load(CompoundTag tag) {
        BlockPos middle = BlockPos.of(tag.getLong("Middle"));
        java.util.ArrayList<BlockPos> positions = new java.util.ArrayList<>();
        ListTag positionsTag = tag.getList("Positions", Tag.TAG_COMPOUND);
        for (int index = 0; index < positionsTag.size(); index++) {
            positions.add(BlockPos.of(positionsTag.getCompound(index).getLong("Pos")));
        }
        return new RoadGraphSegmentPlacement(middle, positions);
    }
}
```

- [ ] **Step 4: Add `RoadGraphReuseSpan`**

```java
package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

public record RoadGraphReuseSpan(UUID sourceEdgeId,
                                 int sourceFromIndex,
                                 int sourceToIndex,
                                 int plannedFromIndex,
                                 int plannedToIndex,
                                 BlockPos fromPos,
                                 BlockPos toPos,
                                 Relationship relationship) {
    public RoadGraphReuseSpan {
        sourceEdgeId = sourceEdgeId == null ? new UUID(0L, 0L) : sourceEdgeId;
        sourceFromIndex = Math.max(-1, sourceFromIndex);
        sourceToIndex = Math.max(-1, sourceToIndex);
        plannedFromIndex = Math.max(-1, plannedFromIndex);
        plannedToIndex = Math.max(-1, plannedToIndex);
        fromPos = fromPos == null ? BlockPos.ZERO : fromPos.immutable();
        toPos = toPos == null ? BlockPos.ZERO : toPos.immutable();
        relationship = relationship == null ? Relationship.OWN : relationship;
    }

    public boolean present() {
        return !sourceEdgeId.equals(new UUID(0L, 0L))
                && sourceFromIndex >= 0
                && sourceToIndex >= 0
                && plannedFromIndex >= 0
                && plannedToIndex >= 0
                && sourceFromIndex != sourceToIndex
                && plannedFromIndex != plannedToIndex;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("SourceEdgeId", sourceEdgeId);
        tag.putInt("SourceFromIndex", sourceFromIndex);
        tag.putInt("SourceToIndex", sourceToIndex);
        tag.putInt("PlannedFromIndex", plannedFromIndex);
        tag.putInt("PlannedToIndex", plannedToIndex);
        tag.putLong("FromPos", fromPos.asLong());
        tag.putLong("ToPos", toPos.asLong());
        tag.putString("Relationship", relationship.name());
        return tag;
    }

    public static RoadGraphReuseSpan load(CompoundTag tag) {
        return new RoadGraphReuseSpan(
                tag.hasUUID("SourceEdgeId") ? tag.getUUID("SourceEdgeId") : new UUID(0L, 0L),
                tag.getInt("SourceFromIndex"),
                tag.getInt("SourceToIndex"),
                tag.getInt("PlannedFromIndex"),
                tag.getInt("PlannedToIndex"),
                BlockPos.of(tag.getLong("FromPos")),
                BlockPos.of(tag.getLong("ToPos")),
                parseRelationship(tag.getString("Relationship")));
    }

    private static Relationship parseRelationship(String value) {
        if (value == null || value.isBlank()) {
            return Relationship.OWN;
        }
        try {
            return Relationship.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Relationship.OWN;
        }
    }

    public enum Relationship {
        OWN,
        ALLIED,
        TRADE
    }
}
```

- [ ] **Step 5: Add node and edge records**

Create `RoadGraphNodeRecord`:

```java
package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

public record RoadGraphNodeRecord(UUID nodeId,
                                  String dimensionId,
                                  BlockPos pos,
                                  Kind kind,
                                  String ownerNationId,
                                  String ownerTownId,
                                  String structureId,
                                  long createdAt,
                                  long updatedAt) {
    public RoadGraphNodeRecord {
        nodeId = nodeId == null ? UUID.randomUUID() : nodeId;
        dimensionId = normalizeText(dimensionId).toLowerCase(Locale.ROOT);
        pos = pos == null ? BlockPos.ZERO : pos.immutable();
        kind = kind == null ? Kind.NORMAL : kind;
        ownerNationId = normalizeText(ownerNationId).toLowerCase(Locale.ROOT);
        ownerTownId = normalizeText(ownerTownId).toLowerCase(Locale.ROOT);
        structureId = normalizeText(structureId);
        createdAt = Math.max(0L, createdAt);
        updatedAt = Math.max(createdAt, updatedAt);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("NodeId", nodeId);
        tag.putString("Dim", dimensionId);
        tag.putLong("Pos", pos.asLong());
        tag.putString("Kind", kind.name());
        tag.putString("OwnerNationId", ownerNationId);
        tag.putString("OwnerTownId", ownerTownId);
        tag.putString("StructureId", structureId);
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static RoadGraphNodeRecord load(CompoundTag tag) {
        return new RoadGraphNodeRecord(
                tag.hasUUID("NodeId") ? tag.getUUID("NodeId") : UUID.randomUUID(),
                tag.getString("Dim"),
                BlockPos.of(tag.getLong("Pos")),
                parseKind(tag.getString("Kind")),
                tag.getString("OwnerNationId"),
                tag.getString("OwnerTownId"),
                tag.getString("StructureId"),
                tag.getLong("CreatedAt"),
                tag.getLong("UpdatedAt"));
    }

    private static Kind parseKind(String value) {
        if (value == null || value.isBlank()) {
            return Kind.NORMAL;
        }
        try {
            return Kind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Kind.NORMAL;
        }
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    public enum Kind {
        NORMAL,
        JUNCTION,
        TOWN_CONNECTION,
        POST_STATION_CONNECTION,
        REUSE_ANCHOR
    }
}
```

Create `RoadGraphEdgeRecord`:

```java
package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public record RoadGraphEdgeRecord(UUID edgeId,
                                  UUID fromNodeId,
                                  UUID toNodeId,
                                  String dimensionId,
                                  String ownerNationId,
                                  String ownerTownId,
                                  String creatorUuid,
                                  String creatorName,
                                  String sourceTownName,
                                  String targetTownName,
                                  String roadName,
                                  int width,
                                  CompiledRoadSectionType sectionType,
                                  Status status,
                                  List<BlockPos> centerline,
                                  List<BlockPos> displayPath,
                                  List<RoadGraphSegmentPlacement> placements,
                                  List<BlockPos> ownedBlockPositions,
                                  List<RoadGraphReuseSpan> reuseSpans,
                                  long createdAt,
                                  long updatedAt) {
    public RoadGraphEdgeRecord {
        edgeId = edgeId == null ? UUID.randomUUID() : edgeId;
        fromNodeId = fromNodeId == null ? new UUID(0L, 0L) : fromNodeId;
        toNodeId = toNodeId == null ? new UUID(0L, 0L) : toNodeId;
        dimensionId = text(dimensionId).toLowerCase(Locale.ROOT);
        ownerNationId = text(ownerNationId).toLowerCase(Locale.ROOT);
        ownerTownId = text(ownerTownId).toLowerCase(Locale.ROOT);
        creatorUuid = text(creatorUuid);
        creatorName = text(creatorName);
        sourceTownName = text(sourceTownName);
        targetTownName = text(targetTownName);
        roadName = text(roadName);
        width = Math.max(1, width);
        sectionType = sectionType == null ? CompiledRoadSectionType.ROAD : sectionType;
        status = status == null ? Status.BUILT : status;
        centerline = positions(centerline);
        displayPath = displayPath == null || displayPath.isEmpty() ? centerline : positions(displayPath);
        placements = placements == null ? List.of() : placements.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        ownedBlockPositions = positions(ownedBlockPositions);
        reuseSpans = reuseSpans == null ? List.of() : reuseSpans.stream()
                .filter(java.util.Objects::nonNull)
                .filter(RoadGraphReuseSpan::present)
                .toList();
        createdAt = Math.max(0L, createdAt);
        updatedAt = Math.max(createdAt, updatedAt);
    }

    public boolean built() {
        return status == Status.BUILT;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("EdgeId", edgeId);
        tag.putUUID("FromNodeId", fromNodeId);
        tag.putUUID("ToNodeId", toNodeId);
        tag.putString("Dim", dimensionId);
        tag.putString("OwnerNationId", ownerNationId);
        tag.putString("OwnerTownId", ownerTownId);
        tag.putString("CreatorUuid", creatorUuid);
        tag.putString("CreatorName", creatorName);
        tag.putString("SourceTownName", sourceTownName);
        tag.putString("TargetTownName", targetTownName);
        tag.putString("RoadName", roadName);
        tag.putInt("Width", width);
        tag.putString("SectionType", sectionType.name());
        tag.putString("Status", status.name());
        tag.put("Centerline", writePositions(centerline));
        tag.put("DisplayPath", writePositions(displayPath));
        tag.put("Placements", writePlacements(placements));
        tag.put("OwnedBlocks", writePositions(ownedBlockPositions));
        tag.put("ReuseSpans", writeReuseSpans(reuseSpans));
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static RoadGraphEdgeRecord load(CompoundTag tag) {
        return new RoadGraphEdgeRecord(
                tag.hasUUID("EdgeId") ? tag.getUUID("EdgeId") : UUID.randomUUID(),
                tag.hasUUID("FromNodeId") ? tag.getUUID("FromNodeId") : new UUID(0L, 0L),
                tag.hasUUID("ToNodeId") ? tag.getUUID("ToNodeId") : new UUID(0L, 0L),
                tag.getString("Dim"),
                tag.getString("OwnerNationId"),
                tag.getString("OwnerTownId"),
                tag.getString("CreatorUuid"),
                tag.getString("CreatorName"),
                tag.getString("SourceTownName"),
                tag.getString("TargetTownName"),
                tag.getString("RoadName"),
                tag.getInt("Width"),
                parseSectionType(tag.getString("SectionType")),
                parseStatus(tag.getString("Status")),
                readPositions(tag, "Centerline"),
                readPositions(tag, "DisplayPath"),
                readPlacements(tag),
                readPositions(tag, "OwnedBlocks"),
                readReuseSpans(tag),
                tag.getLong("CreatedAt"),
                tag.getLong("UpdatedAt"));
    }

    private static List<BlockPos> positions(List<BlockPos> input) {
        return input == null ? List.of() : input.stream()
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static ListTag writePositions(List<BlockPos> positions) {
        ListTag list = new ListTag();
        for (BlockPos pos : positions == null ? List.<BlockPos>of() : positions) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", pos.asLong());
            list.add(entry);
        }
        return list;
    }

    private static List<BlockPos> readPositions(CompoundTag tag, String key) {
        java.util.ArrayList<BlockPos> positions = new java.util.ArrayList<>();
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            positions.add(BlockPos.of(list.getCompound(index).getLong("Pos")));
        }
        return List.copyOf(positions);
    }

    private static ListTag writePlacements(List<RoadGraphSegmentPlacement> placements) {
        ListTag list = new ListTag();
        for (RoadGraphSegmentPlacement placement : placements == null ? List.<RoadGraphSegmentPlacement>of() : placements) {
            list.add(placement.save());
        }
        return list;
    }

    private static List<RoadGraphSegmentPlacement> readPlacements(CompoundTag tag) {
        java.util.ArrayList<RoadGraphSegmentPlacement> placements = new java.util.ArrayList<>();
        ListTag list = tag.getList("Placements", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            placements.add(RoadGraphSegmentPlacement.load(list.getCompound(index)));
        }
        return List.copyOf(placements);
    }

    private static ListTag writeReuseSpans(List<RoadGraphReuseSpan> spans) {
        ListTag list = new ListTag();
        for (RoadGraphReuseSpan span : spans == null ? List.<RoadGraphReuseSpan>of() : spans) {
            if (span.present()) {
                list.add(span.save());
            }
        }
        return list;
    }

    private static List<RoadGraphReuseSpan> readReuseSpans(CompoundTag tag) {
        java.util.ArrayList<RoadGraphReuseSpan> spans = new java.util.ArrayList<>();
        ListTag list = tag.getList("ReuseSpans", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            RoadGraphReuseSpan span = RoadGraphReuseSpan.load(list.getCompound(index));
            if (span.present()) {
                spans.add(span);
            }
        }
        return List.copyOf(spans);
    }

    private static CompiledRoadSectionType parseSectionType(String value) {
        if (value == null || value.isBlank()) {
            return CompiledRoadSectionType.ROAD;
        }
        try {
            return CompiledRoadSectionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return CompiledRoadSectionType.ROAD;
        }
    }

    private static Status parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return Status.BUILT;
        }
        try {
            return Status.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Status.BUILT;
        }
    }

    public enum Status {
        PLANNED,
        BUILDING,
        BUILT,
        REMOVING,
        REMOVED,
        CANCELLED
    }
}
```

- [ ] **Step 6: Add `RoadNetworkGraphSavedData`**

```java
package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RoadNetworkGraphSavedData extends SavedData {
    public static final String DATA_NAME = "sailboatmod_road_graphs";

    private final Map<UUID, RoadGraphNodeRecord> nodes = new LinkedHashMap<>();
    private final Map<UUID, RoadGraphEdgeRecord> edges = new LinkedHashMap<>();
    private boolean dirtyForTest;

    public static RoadNetworkGraphSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new RoadNetworkGraphSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(RoadNetworkGraphSavedData::load, RoadNetworkGraphSavedData::new, DATA_NAME);
    }

    public static RoadNetworkGraphSavedData load(CompoundTag tag) {
        RoadNetworkGraphSavedData data = new RoadNetworkGraphSavedData();
        ListTag nodeTags = tag.getList("Nodes", Tag.TAG_COMPOUND);
        for (int index = 0; index < nodeTags.size(); index++) {
            RoadGraphNodeRecord node = RoadGraphNodeRecord.load(nodeTags.getCompound(index));
            data.nodes.put(node.nodeId(), node);
        }
        ListTag edgeTags = tag.getList("Edges", Tag.TAG_COMPOUND);
        for (int index = 0; index < edgeTags.size(); index++) {
            RoadGraphEdgeRecord edge = RoadGraphEdgeRecord.load(edgeTags.getCompound(index));
            if (data.nodes.containsKey(edge.fromNodeId()) && data.nodes.containsKey(edge.toNodeId())) {
                data.edges.put(edge.edgeId(), edge);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag nodeTags = new ListTag();
        for (RoadGraphNodeRecord node : nodes.values()) {
            nodeTags.add(node.save());
        }
        tag.put("Nodes", nodeTags);
        ListTag edgeTags = new ListTag();
        for (RoadGraphEdgeRecord edge : edges.values()) {
            edgeTags.add(edge.save());
        }
        tag.put("Edges", edgeTags);
        return tag;
    }

    public void putNode(RoadGraphNodeRecord node) {
        if (node == null) {
            return;
        }
        nodes.put(node.nodeId(), node);
        markDirty();
    }

    public void putEdge(RoadGraphEdgeRecord edge) {
        if (edge == null || !nodes.containsKey(edge.fromNodeId()) || !nodes.containsKey(edge.toNodeId())) {
            return;
        }
        edges.put(edge.edgeId(), edge);
        markDirty();
    }

    RoadNetworkGraphSavedData withEdgeForTest(RoadGraphEdgeRecord edge) {
        if (edge != null) {
            edges.put(edge.edgeId(), edge);
        }
        return this;
    }

    public Optional<RoadGraphNodeRecord> getNode(UUID nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public Optional<RoadGraphEdgeRecord> getEdge(UUID edgeId) {
        return Optional.ofNullable(edges.get(edgeId));
    }

    public Collection<RoadGraphNodeRecord> nodes() {
        return List.copyOf(nodes.values());
    }

    public Collection<RoadGraphEdgeRecord> edges() {
        return List.copyOf(edges.values());
    }

    public Collection<RoadGraphEdgeRecord> edgesForDimension(String dimensionId) {
        String normalized = dimensionId == null ? "" : dimensionId.trim().toLowerCase(java.util.Locale.ROOT);
        return edges.values().stream()
                .filter(edge -> normalized.equals(edge.dimensionId()))
                .toList();
    }

    public boolean removeEdge(UUID edgeId) {
        boolean removed = edgeId != null && edges.remove(edgeId) != null;
        if (removed) {
            markDirty();
        }
        return removed;
    }

    public boolean isDirtyForTest() {
        return dirtyForTest;
    }

    private void markDirty() {
        dirtyForTest = true;
        setDirty();
    }
}
```

- [ ] **Step 7: Run the persistence test**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraphSavedDataTest
```

Expected: PASS.

- [ ] **Step 8: Commit graph persistence**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphSegmentPlacement.java src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphReuseSpan.java src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphNodeRecord.java src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphEdgeRecord.java src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadNetworkGraphSavedData.java src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadNetworkGraphSavedDataTest.java
git commit -m "Add road graph persistence"
```

Expected: one commit containing only graph persistence files.

---

### Task 2: Graph Repository And Spatial Index

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphRepository.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphSpatialIndex.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphSpatialIndexTest.java`

- [ ] **Step 1: Write failing spatial index tests**

```java
package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGraphSpatialIndexTest {
    @Test
    void findsNearbyBuiltEdgeByPlacementFootprint() {
        RoadGraphSpatialIndex index = new RoadGraphSpatialIndex();
        RoadGraphEdgeRecord edge = edge(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0));
        index.rebuild(List.of(edge));

        List<RoadGraphSpatialIndex.EdgeHit> hits = index.near(new BlockPos(8, 64, 1), 3);

        assertEquals(edge.edgeId(), hits.get(0).edge().edgeId());
        assertTrue(hits.get(0).distanceSqr() <= 9);
    }

    @Test
    void queriesViewportByCenterlineAndFootprint() {
        RoadGraphSpatialIndex index = new RoadGraphSpatialIndex();
        RoadGraphEdgeRecord inside = edge(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0));
        RoadGraphEdgeRecord outside = edge(new BlockPos(500, 64, 500), new BlockPos(516, 64, 500));
        index.rebuild(List.of(inside, outside));

        List<RoadGraphEdgeRecord> visible = index.queryRect("minecraft:overworld", -32, -32, 32, 32);

        assertEquals(List.of(inside.edgeId()), visible.stream().map(RoadGraphEdgeRecord::edgeId).toList());
    }

    @Test
    void classifiesRoadCorridorMembershipFromFootprint() {
        RoadGraphSpatialIndex index = new RoadGraphSpatialIndex();
        RoadGraphEdgeRecord edge = edge(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0));
        index.rebuild(List.of(edge));

        assertTrue(index.isRoadCorridor("minecraft:overworld", new BlockPos(8, 64, 1), 1));
    }

    private static RoadGraphEdgeRecord edge(BlockPos from, BlockPos to) {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();
        List<BlockPos> centerline = interpolate(from, to);
        List<RoadGraphSegmentPlacement> placements = centerline.stream()
                .map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos, pos.north(), pos.south())))
                .toList();
        return new RoadGraphEdgeRecord(edgeId, a, b, "minecraft:overworld", "alpha", "", "", "",
                "", "", "test", 3, CompiledRoadSectionType.ROAD, RoadGraphEdgeRecord.Status.BUILT,
                centerline, List.of(from, to), placements, placements.stream().flatMap(p -> p.positions().stream()).toList(),
                List.of(), 1L, 1L);
    }

    private static List<BlockPos> interpolate(BlockPos from, BlockPos to) {
        java.util.ArrayList<BlockPos> points = new java.util.ArrayList<>();
        int dx = Integer.compare(to.getX() - from.getX(), 0);
        int dz = Integer.compare(to.getZ() - from.getZ(), 0);
        BlockPos cursor = from;
        points.add(cursor);
        while (!cursor.equals(to)) {
            cursor = cursor.offset(dx, Integer.compare(to.getY() - cursor.getY(), 0), dz);
            points.add(cursor);
        }
        return List.copyOf(points);
    }
}
```

- [ ] **Step 2: Run the spatial index test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.graph.RoadGraphSpatialIndexTest
```

Expected: compile failure because `RoadGraphSpatialIndex` does not exist.

- [ ] **Step 3: Implement `RoadGraphSpatialIndex`**

Use RoadWeaver's grid shape without external RoadWeaver dependencies:

```java
package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spatial grid adapted from RoadWeaver's RoadSpatialIndex for graph edge lookups.
 */
public final class RoadGraphSpatialIndex {
    static final int GRID_SHIFT = 3;
    static final int GRID_SIZE = 1 << GRID_SHIFT;

    private final Map<String, Map<Long, List<PointEntry>>> cellsByDimension = new LinkedHashMap<>();
    private final Map<UUID, RoadGraphEdgeRecord> edges = new LinkedHashMap<>();

    public void rebuild(List<RoadGraphEdgeRecord> sourceEdges) {
        cellsByDimension.clear();
        edges.clear();
        for (RoadGraphEdgeRecord edge : sourceEdges == null ? List.<RoadGraphEdgeRecord>of() : sourceEdges) {
            if (edge == null || !edge.built()) {
                continue;
            }
            edges.put(edge.edgeId(), edge);
            addEdge(edge);
        }
    }

    public List<EdgeHit> near(BlockPos pos, int radiusBlocks) {
        if (pos == null) {
            return List.of();
        }
        ArrayList<EdgeHit> all = new ArrayList<>();
        for (String dimension : cellsByDimension.keySet()) {
            all.addAll(near(dimension, pos, radiusBlocks));
        }
        all.sort(Comparator.comparingLong(EdgeHit::distanceSqr));
        return List.copyOf(all);
    }

    public List<EdgeHit> near(String dimensionId, BlockPos pos, int radiusBlocks) {
        if (pos == null) {
            return List.of();
        }
        String dim = normalizeDim(dimensionId);
        Map<Long, List<PointEntry>> cells = cellsByDimension.get(dim);
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        long radiusSqr = (long) radiusBlocks * (long) radiusBlocks;
        int gx = pos.getX() >> GRID_SHIFT;
        int gz = pos.getZ() >> GRID_SHIFT;
        int gridRadius = Math.max(1, (radiusBlocks >> GRID_SHIFT) + 1);
        Map<UUID, EdgeHit> best = new HashMap<>();
        for (int dx = -gridRadius; dx <= gridRadius; dx++) {
            for (int dz = -gridRadius; dz <= gridRadius; dz++) {
                List<PointEntry> entries = cells.get(gridKey(gx + dx, gz + dz));
                if (entries == null) {
                    continue;
                }
                for (PointEntry entry : entries) {
                    long dist2 = dist2XZ(pos, entry.pos());
                    if (dist2 > radiusSqr) {
                        continue;
                    }
                    EdgeHit current = best.get(entry.edge().edgeId());
                    if (current == null || dist2 < current.distanceSqr()) {
                        best.put(entry.edge().edgeId(), new EdgeHit(entry.edge(), entry.segmentIndex(), entry.pos(), dist2));
                    }
                }
            }
        }
        return best.values().stream()
                .sorted(Comparator.comparingLong(EdgeHit::distanceSqr))
                .toList();
    }

    public List<RoadGraphEdgeRecord> queryRect(String dimensionId, int minX, int minZ, int maxX, int maxZ) {
        String dim = normalizeDim(dimensionId);
        Map<Long, List<PointEntry>> cells = cellsByDimension.get(dim);
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        int minGridX = minX >> GRID_SHIFT;
        int maxGridX = maxX >> GRID_SHIFT;
        int minGridZ = minZ >> GRID_SHIFT;
        int maxGridZ = maxZ >> GRID_SHIFT;
        LinkedHashMap<UUID, RoadGraphEdgeRecord> visible = new LinkedHashMap<>();
        for (int gx = minGridX; gx <= maxGridX; gx++) {
            for (int gz = minGridZ; gz <= maxGridZ; gz++) {
                List<PointEntry> entries = cells.get(gridKey(gx, gz));
                if (entries == null) {
                    continue;
                }
                for (PointEntry entry : entries) {
                    BlockPos pos = entry.pos();
                    if (pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
                        visible.put(entry.edge().edgeId(), entry.edge());
                    }
                }
            }
        }
        return List.copyOf(visible.values());
    }

    public boolean isRoadCorridor(String dimensionId, BlockPos pos, int radiusBlocks) {
        return !near(dimensionId, pos, Math.max(0, radiusBlocks)).isEmpty();
    }

    private void addEdge(RoadGraphEdgeRecord edge) {
        String dim = normalizeDim(edge.dimensionId());
        Map<Long, List<PointEntry>> cells = cellsByDimension.computeIfAbsent(dim, ignored -> new HashMap<>());
        int index = 0;
        for (RoadGraphSegmentPlacement placement : edge.placements()) {
            addPoint(cells, edge, index, placement.middlePos());
            for (BlockPos pos : placement.positions()) {
                addPoint(cells, edge, index, pos);
            }
            index++;
        }
        for (BlockPos pos : edge.centerline()) {
            addPoint(cells, edge, index++, pos);
        }
    }

    private static void addPoint(Map<Long, List<PointEntry>> cells, RoadGraphEdgeRecord edge, int index, BlockPos pos) {
        if (pos == null) {
            return;
        }
        cells.computeIfAbsent(gridKey(pos.getX() >> GRID_SHIFT, pos.getZ() >> GRID_SHIFT), ignored -> new ArrayList<>())
                .add(new PointEntry(edge, index, pos.immutable()));
    }

    private static long gridKey(int gx, int gz) {
        return (((long) gx) << 32) | (gz & 0xFFFFFFFFL);
    }

    private static long dist2XZ(BlockPos left, BlockPos right) {
        long dx = (long) left.getX() - right.getX();
        long dz = (long) left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static String normalizeDim(String dimensionId) {
        return dimensionId == null ? "" : dimensionId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record PointEntry(RoadGraphEdgeRecord edge, int segmentIndex, BlockPos pos) {
    }

    public record EdgeHit(RoadGraphEdgeRecord edge, int segmentIndex, BlockPos pos, long distanceSqr) {
    }
}
```

- [ ] **Step 4: Implement `RoadGraphRepository`**

```java
package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class RoadGraphRepository {
    private final RoadNetworkGraphSavedData data;
    private final RoadGraphSpatialIndex spatialIndex = new RoadGraphSpatialIndex();
    private boolean indexDirty = true;

    public RoadGraphRepository(RoadNetworkGraphSavedData data) {
        this.data = data == null ? new RoadNetworkGraphSavedData() : data;
    }

    public static RoadGraphRepository forLevel(ServerLevel level) {
        return new RoadGraphRepository(RoadNetworkGraphSavedData.get(level));
    }

    public void putNode(RoadGraphNodeRecord node) {
        data.putNode(node);
        indexDirty = true;
    }

    public void putEdge(RoadGraphEdgeRecord edge) {
        data.putEdge(edge);
        indexDirty = true;
    }

    public Optional<RoadGraphNodeRecord> node(UUID nodeId) {
        return data.getNode(nodeId);
    }

    public Optional<RoadGraphEdgeRecord> edge(UUID edgeId) {
        return data.getEdge(edgeId);
    }

    public Collection<RoadGraphNodeRecord> nodes() {
        return data.nodes();
    }

    public Collection<RoadGraphEdgeRecord> edges() {
        return data.edges();
    }

    public List<RoadGraphEdgeRecord> edgesForDimension(String dimensionId) {
        return data.edgesForDimension(dimensionId).stream().toList();
    }

    public RoadGraphSpatialIndex spatialIndex() {
        if (indexDirty) {
            spatialIndex.rebuild(data.edges().stream().toList());
            indexDirty = false;
        }
        return spatialIndex;
    }

    public boolean removeEdge(UUID edgeId) {
        boolean removed = data.removeEdge(edgeId);
        if (removed) {
            indexDirty = true;
        }
        return removed;
    }
}
```

- [ ] **Step 5: Run spatial index tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.graph.RoadGraphSpatialIndexTest
```

Expected: PASS.

- [ ] **Step 6: Commit repository and index**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphRepository.java src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphSpatialIndex.java src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphSpatialIndexTest.java
git commit -m "Add road graph spatial index"
```

Expected: one commit containing repository/index files and tests.

---

### Task 3: RoadWeaver-Style Reuse Planner

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadReusePlan.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphReusePlanner.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphReusePlannerTest.java`

- [ ] **Step 1: Write failing reuse planner tests**

```java
package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGraphReusePlannerTest {
    @Test
    void detectsDirectionCompatibleReusableRun() {
        RoadGraphRepository repository = repositoryWith(edge(
                CompiledRoadSectionType.ROAD,
                new BlockPos(0, 64, 0),
                new BlockPos(20, 64, 0)));
        List<RoadGraphSegmentPlacement> planned = placements(new BlockPos(0, 64, 2), new BlockPos(20, 64, 2));

        RoadReusePlan plan = new RoadGraphReusePlanner(repository).planReuse(
                "minecraft:overworld",
                planned,
                List.of(new BlockPos(0, 64, 2), new BlockPos(20, 64, 2)),
                CompiledRoadSectionType.ROAD);

        assertEquals(1, plan.reuseSpans().size());
        assertTrue(plan.reuseSpans().get(0).plannedToIndex() - plan.reuseSpans().get(0).plannedFromIndex() >= 3);
        assertTrue(plan.ownedRanges().isEmpty());
    }

    @Test
    void rejectsPerpendicularNearbyRun() {
        RoadGraphRepository repository = repositoryWith(edge(
                CompiledRoadSectionType.ROAD,
                new BlockPos(0, 64, 0),
                new BlockPos(20, 64, 0)));
        List<RoadGraphSegmentPlacement> planned = placements(new BlockPos(10, 64, -10), new BlockPos(10, 64, 10));

        RoadReusePlan plan = new RoadGraphReusePlanner(repository).planReuse(
                "minecraft:overworld",
                planned,
                List.of(new BlockPos(10, 64, -10), new BlockPos(10, 64, 10)),
                CompiledRoadSectionType.ROAD);

        assertTrue(plan.reuseSpans().isEmpty());
        assertEquals(List.of(new RoadReusePlan.Range(0, planned.size() - 1)), plan.ownedRanges());
    }

    @Test
    void rejectsRoadToBridgeReuseByDefault() {
        RoadGraphRepository repository = repositoryWith(edge(
                CompiledRoadSectionType.BRIDGE,
                new BlockPos(0, 70, 0),
                new BlockPos(20, 70, 0)));
        List<RoadGraphSegmentPlacement> planned = placements(new BlockPos(0, 70, 1), new BlockPos(20, 70, 1));

        RoadReusePlan plan = new RoadGraphReusePlanner(repository).planReuse(
                "minecraft:overworld",
                planned,
                List.of(new BlockPos(0, 70, 1), new BlockPos(20, 70, 1)),
                CompiledRoadSectionType.ROAD);

        assertTrue(plan.reuseSpans().isEmpty());
        assertEquals("incompatible_section_type", plan.rejections().get(0).reason());
    }

    private static RoadGraphRepository repositoryWith(RoadGraphEdgeRecord edge) {
        RoadNetworkGraphSavedData data = new RoadNetworkGraphSavedData();
        data.putNode(new RoadGraphNodeRecord(edge.fromNodeId(), edge.dimensionId(), edge.centerline().get(0),
                RoadGraphNodeRecord.Kind.NORMAL, edge.ownerNationId(), edge.ownerTownId(), "", 1L, 1L));
        data.putNode(new RoadGraphNodeRecord(edge.toNodeId(), edge.dimensionId(), edge.centerline().get(edge.centerline().size() - 1),
                RoadGraphNodeRecord.Kind.NORMAL, edge.ownerNationId(), edge.ownerTownId(), "", 1L, 1L));
        data.putEdge(edge);
        return new RoadGraphRepository(data);
    }

    private static RoadGraphEdgeRecord edge(CompiledRoadSectionType type, BlockPos from, BlockPos to) {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        List<BlockPos> centerline = interpolate(from, to);
        List<RoadGraphSegmentPlacement> placements = centerline.stream()
                .map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos, pos.north(), pos.south())))
                .toList();
        return new RoadGraphEdgeRecord(UUID.randomUUID(), fromId, toId, "minecraft:overworld", "alpha", "",
                "", "", "", "", "source", 3, type, RoadGraphEdgeRecord.Status.BUILT,
                centerline, List.of(from, to), placements, List.of(), List.of(), 1L, 1L);
    }

    private static List<RoadGraphSegmentPlacement> placements(BlockPos from, BlockPos to) {
        return interpolate(from, to).stream()
                .map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos, pos.north(), pos.south())))
                .toList();
    }

    private static List<BlockPos> interpolate(BlockPos from, BlockPos to) {
        java.util.ArrayList<BlockPos> points = new java.util.ArrayList<>();
        int dx = Integer.compare(to.getX() - from.getX(), 0);
        int dz = Integer.compare(to.getZ() - from.getZ(), 0);
        BlockPos cursor = from;
        points.add(cursor);
        while (!cursor.equals(to)) {
            cursor = cursor.offset(dx, Integer.compare(to.getY() - cursor.getY(), 0), dz);
            points.add(cursor);
        }
        return List.copyOf(points);
    }
}
```

- [ ] **Step 2: Run reuse planner tests and verify they fail**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.graph.RoadGraphReusePlannerTest
```

Expected: compile failure because `RoadReusePlan` and `RoadGraphReusePlanner` do not exist.

- [ ] **Step 3: Add `RoadReusePlan`**

```java
package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;

import java.util.List;

public record RoadReusePlan(List<BlockPos> logicalCenterline,
                            List<BlockPos> displayPath,
                            List<RoadGraphSegmentPlacement> plannedPlacements,
                            List<Range> ownedRanges,
                            List<RoadGraphReuseSpan> reuseSpans,
                            List<Rejection> rejections) {
    public RoadReusePlan {
        logicalCenterline = positions(logicalCenterline);
        displayPath = displayPath == null || displayPath.isEmpty() ? logicalCenterline : positions(displayPath);
        plannedPlacements = plannedPlacements == null ? List.of() : plannedPlacements.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        ownedRanges = ownedRanges == null ? List.of() : ownedRanges.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        reuseSpans = reuseSpans == null ? List.of() : reuseSpans.stream()
                .filter(java.util.Objects::nonNull)
                .filter(RoadGraphReuseSpan::present)
                .toList();
        rejections = rejections == null ? List.of() : rejections.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public static RoadReusePlan noReuse(List<RoadGraphSegmentPlacement> placements, List<BlockPos> centerline) {
        int last = placements == null ? -1 : placements.size() - 1;
        return new RoadReusePlan(centerline, centerline, placements,
                last >= 1 ? List.of(new Range(0, last)) : List.of(),
                List.of(), List.of());
    }

    private static List<BlockPos> positions(List<BlockPos> input) {
        return input == null ? List.of() : input.stream()
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }

    public record Range(int fromIndex, int toIndex) {
        public Range {
            fromIndex = Math.max(0, fromIndex);
            toIndex = Math.max(fromIndex, toIndex);
        }
    }

    public record Rejection(String reason, BlockPos pos) {
        public Rejection {
            reason = reason == null || reason.isBlank() ? "unknown" : reason.trim();
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
        }
    }
}
```

- [ ] **Step 4: Implement `RoadGraphReusePlanner`**

```java
package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reuse run detection adapted from RoadWeaver's RoadSnapService.
 */
public final class RoadGraphReusePlanner {
    private static final int SNAP_THRESHOLD = 12;
    private static final int SPLIT_THRESHOLD = 24;
    private static final int MIN_RUN = 3;
    private static final long SNAP_DIST2 = (long) SNAP_THRESHOLD * SNAP_THRESHOLD;
    private static final long SPLIT_DIST2 = (long) SPLIT_THRESHOLD * SPLIT_THRESHOLD;

    private final RoadGraphRepository repository;

    public RoadGraphReusePlanner(RoadGraphRepository repository) {
        this.repository = repository == null ? new RoadGraphRepository(new RoadNetworkGraphSavedData()) : repository;
    }

    public RoadReusePlan planReuse(String dimensionId,
                                   List<RoadGraphSegmentPlacement> plannedPlacements,
                                   List<BlockPos> logicalCenterline,
                                   CompiledRoadSectionType plannedType) {
        List<RoadGraphSegmentPlacement> placements = plannedPlacements == null ? List.of() : plannedPlacements;
        if (placements.size() < MIN_RUN) {
            return RoadReusePlan.noReuse(placements, logicalCenterline);
        }
        CompiledRoadSectionType safePlannedType = plannedType == null ? CompiledRoadSectionType.ROAD : plannedType;
        MatchResult best = bestMatch(dimensionId, placements, safePlannedType);
        if (best == null || best.runs().isEmpty()) {
            return new RoadReusePlan(logicalCenterline, logicalCenterline, placements,
                    List.of(new RoadReusePlan.Range(0, placements.size() - 1)), List.of(),
                    best == null ? List.of() : best.rejections());
        }
        List<RoadGraphReuseSpan> spans = new ArrayList<>();
        for (int[] run : best.runs()) {
            int plannedFrom = run[0];
            int plannedTo = run[1];
            int sourceFrom = best.targets()[plannedFrom];
            int sourceTo = best.targets()[plannedTo];
            spans.add(new RoadGraphReuseSpan(best.edge().edgeId(), sourceFrom, sourceTo,
                    plannedFrom, plannedTo,
                    placements.get(plannedFrom).middlePos(),
                    placements.get(plannedTo).middlePos(),
                    RoadGraphReuseSpan.Relationship.OWN));
        }
        return new RoadReusePlan(logicalCenterline, logicalCenterline, placements,
                ownedRanges(placements.size(), spans), spans, best.rejections());
    }

    private MatchResult bestMatch(String dimensionId,
                                  List<RoadGraphSegmentPlacement> placements,
                                  CompiledRoadSectionType plannedType) {
        MatchResult best = null;
        ArrayList<RoadReusePlan.Rejection> rejections = new ArrayList<>();
        for (RoadGraphEdgeRecord edge : repository.edgesForDimension(dimensionId)) {
            if (edge == null || !edge.built()) {
                continue;
            }
            if (!compatible(plannedType, edge.sectionType())) {
                if (!placements.isEmpty()) {
                    rejections.add(new RoadReusePlan.Rejection("incompatible_section_type", placements.get(0).middlePos()));
                }
                continue;
            }
            int[] targets = markTargets(placements, edge.placements());
            List<int[]> runs = extractRuns(targets, placements.size());
            if (runs.isEmpty()) {
                continue;
            }
            MatchResult candidate = new MatchResult(edge, targets, runs, List.copyOf(rejections));
            if (best == null || totalRunLength(candidate.runs()) > totalRunLength(best.runs())) {
                best = candidate;
            }
        }
        return best == null && !rejections.isEmpty()
                ? new MatchResult(null, new int[0], List.of(), List.copyOf(rejections))
                : best;
    }

    private int[] markTargets(List<RoadGraphSegmentPlacement> planned,
                              List<RoadGraphSegmentPlacement> source) {
        int[] targets = new int[planned.size()];
        Arrays.fill(targets, -1);
        boolean snapping = false;
        for (int index = 0; index < planned.size(); index++) {
            Nearest nearest = nearest(source, planned.get(index).middlePos());
            if (nearest == null) {
                snapping = false;
                continue;
            }
            if (!snapping) {
                if (nearest.distanceSqr() <= SNAP_DIST2 && directionCompatible(planned, index, source, nearest.index())) {
                    snapping = true;
                    targets[index] = nearest.index();
                }
            } else if (nearest.distanceSqr() <= SPLIT_DIST2 && directionCompatible(planned, index, source, nearest.index())) {
                targets[index] = nearest.index();
            } else {
                snapping = false;
            }
        }
        return targets;
    }

    private static List<int[]> extractRuns(int[] targets, int size) {
        ArrayList<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int index = 0; index < size; index++) {
            if (targets[index] >= 0) {
                if (start < 0) {
                    start = index;
                }
            } else if (start >= 0) {
                if (index - start >= MIN_RUN) {
                    runs.add(new int[] { start, index - 1 });
                }
                start = -1;
            }
        }
        if (start >= 0 && size - start >= MIN_RUN) {
            runs.add(new int[] { start, size - 1 });
        }
        return List.copyOf(runs);
    }

    private static List<RoadReusePlan.Range> ownedRanges(int size, List<RoadGraphReuseSpan> spans) {
        ArrayList<RoadReusePlan.Range> ranges = new ArrayList<>();
        int cursor = 0;
        for (RoadGraphReuseSpan span : spans) {
            if (cursor < span.plannedFromIndex()) {
                ranges.add(new RoadReusePlan.Range(cursor, span.plannedFromIndex() - 1));
            }
            cursor = span.plannedToIndex() + 1;
        }
        if (cursor < size) {
            ranges.add(new RoadReusePlan.Range(cursor, size - 1));
        }
        return List.copyOf(ranges);
    }

    private static boolean compatible(CompiledRoadSectionType plannedType, CompiledRoadSectionType sourceType) {
        if (plannedType == sourceType) {
            return true;
        }
        return plannedType == CompiledRoadSectionType.ROAD && sourceType == CompiledRoadSectionType.ROAD;
    }

    private static boolean directionCompatible(List<RoadGraphSegmentPlacement> planned, int plannedIndex,
                                               List<RoadGraphSegmentPlacement> source, int sourceIndex) {
        double[] plannedDir = direction(planned, plannedIndex);
        double[] sourceDir = direction(source, sourceIndex);
        if (plannedDir == null || sourceDir == null) {
            return true;
        }
        double dot = Math.abs(plannedDir[0] * sourceDir[0] + plannedDir[1] * sourceDir[1]);
        return dot > 0.3D;
    }

    private static double[] direction(List<RoadGraphSegmentPlacement> placements, int index) {
        if (placements == null || placements.size() < 2) {
            return null;
        }
        int previous = Math.max(0, index - 2);
        int next = Math.min(placements.size() - 1, index + 2);
        if (previous == next) {
            return null;
        }
        BlockPos a = placements.get(previous).middlePos();
        BlockPos b = placements.get(next).middlePos();
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double length = Math.hypot(dx, dz);
        return length < 1.0D ? null : new double[] { dx / length, dz / length };
    }

    private static Nearest nearest(List<RoadGraphSegmentPlacement> source, BlockPos target) {
        if (source == null || source.isEmpty() || target == null) {
            return null;
        }
        int bestIndex = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 0; index < source.size(); index++) {
            long distance = dist2XZ(target, source.get(index).middlePos());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex < 0 ? null : new Nearest(bestIndex, bestDistance);
    }

    private static long dist2XZ(BlockPos left, BlockPos right) {
        long dx = (long) left.getX() - right.getX();
        long dz = (long) left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static int totalRunLength(List<int[]> runs) {
        int total = 0;
        for (int[] run : runs) {
            total += run[1] - run[0] + 1;
        }
        return total;
    }

    private record Nearest(int index, long distanceSqr) {
    }

    private record MatchResult(RoadGraphEdgeRecord edge,
                               int[] targets,
                               List<int[]> runs,
                               List<RoadReusePlan.Rejection> rejections) {
    }
}
```

- [ ] **Step 5: Run reuse planner tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.graph.RoadGraphReusePlannerTest
```

Expected: PASS.

- [ ] **Step 6: Commit reuse planner**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadReusePlan.java src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphReusePlanner.java src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphReusePlannerTest.java
git commit -m "Add road graph reuse planning"
```

Expected: one commit for reuse plan model and planner.

---

### Task 4: Build Confirmation Carries Reuse Plan

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java`
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlServiceTest.java`

- [ ] **Step 1: Add failing build-control test for reuse snapshots**

Add this test method to `RoadPlannerBuildControlServiceTest`:

```java
@Test
void previewSnapshotCarriesReusePlanIntoCompletedBuild() {
    java.util.concurrent.atomic.AtomicReference<RoadPlannerBuildControlService.CompletedRoadBuild> completed = new java.util.concurrent.atomic.AtomicReference<>();
    RoadPlannerBuildControlService service = new RoadPlannerBuildControlService((level, road) -> completed.set(road));
    UUID playerId = UUID.randomUUID();
    RoadGraphReuseSpan span = new RoadGraphReuseSpan(UUID.randomUUID(), 0, 3, 0, 3,
            new BlockPos(0, 64, 0), new BlockPos(3, 64, 0), RoadGraphReuseSpan.Relationship.OWN);
    RoadReusePlan reusePlan = new RoadReusePlan(
            List.of(new BlockPos(0, 64, 0), new BlockPos(3, 64, 0), new BlockPos(6, 64, 0)),
            List.of(new BlockPos(0, 64, 0), new BlockPos(6, 64, 0)),
            List.of(),
            List.of(new RoadReusePlan.Range(4, 6)),
            List.of(span),
            List.of());
    UUID previewId = service.startPreview(playerId, "", "",
            List.of(new BlockPos(0, 64, 0), new BlockPos(6, 64, 0)),
            List.of(RoadPlannerSegmentType.ROAD),
            RoadPlannerBuildSettings.DEFAULTS,
            RoadPlannerMergeSelection.none(),
            List.of(new BlockPos(0, 64, 0), new BlockPos(6, 64, 0)),
            List.of(),
            reusePlan);

    service.confirmPreview(playerId, previewId, null);
    service.tick(null);

    assertEquals(List.of(span), completed.get().reusePlan().reuseSpans());
}

@Test
void reusedFootprintPositionsAreRemovedFromBuildSteps() {
    RoadGraphSegmentPlacement reused = new RoadGraphSegmentPlacement(
            new BlockPos(0, 64, 0),
            List.of(new BlockPos(0, 64, 0), new BlockPos(0, 64, 1)));
    RoadGraphSegmentPlacement owned = new RoadGraphSegmentPlacement(
            new BlockPos(4, 64, 0),
            List.of(new BlockPos(4, 64, 0), new BlockPos(4, 64, 1)));
    RoadGraphReuseSpan span = new RoadGraphReuseSpan(UUID.randomUUID(), 0, 1, 0, 1,
            reused.middlePos(), owned.middlePos(), RoadGraphReuseSpan.Relationship.OWN);
    RoadReusePlan reusePlan = new RoadReusePlan(
            List.of(reused.middlePos(), owned.middlePos()),
            List.of(reused.middlePos(), owned.middlePos()),
            List.of(reused, owned),
            List.of(new RoadReusePlan.Range(1, 1)),
            List.of(span),
            List.of());
    List<BuildStep> steps = List.of(
            new BuildStep(0, new BlockPos(0, 64, 0), Blocks.GRASS_BLOCK.defaultBlockState(), BuildPhase.SURFACE),
            new BuildStep(1, new BlockPos(4, 64, 0), Blocks.GRASS_BLOCK.defaultBlockState(), BuildPhase.SURFACE));

    List<BuildStep> filtered = RoadPlannerBuildControlService.filterOwnedBuildStepsForTest(steps, reusePlan);

    assertEquals(List.of(new BlockPos(4, 64, 0)), filtered.stream().map(BuildStep::pos).toList());
}
```

- [ ] **Step 2: Run the build-control test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlServiceTest --tests "*previewSnapshotCarriesReusePlanIntoCompletedBuild"
```

Expected: compile failure because `startPreview`, `CompletedRoadBuild`, and `filterOwnedBuildStepsForTest` do not expose `RoadReusePlan`.

- [ ] **Step 3: Add `RoadReusePlan` to preview snapshot and completed build**

In `RoadPlannerBuildControlService`, import:

```java
import com.monpai.sailboatmod.roadplanner.graph.RoadReusePlan;
```

Add overload:

```java
public UUID startPreview(UUID playerId,
                         String sourceTownName,
                         String targetTownName,
                         List<BlockPos> nodes,
                         List<RoadPlannerSegmentType> segmentTypes,
                         RoadPlannerBuildSettings settings,
                         RoadPlannerMergeSelection mergeSelection,
                         List<BlockPos> logicalNodes,
                         List<RoadPlannerSharedRoadSpan> sharedSpans,
                         RoadReusePlan reusePlan) {
    UUID previewId = UUID.randomUUID();
    activePreviews.put(playerId, previewId);
    previews.put(previewId, new PreviewSnapshot(nodes, segmentTypes, settings, mergeSelection,
            logicalNodes, sharedSpans, sourceTownName, targetTownName, reusePlan));
    return previewId;
}
```

Change the existing deepest `startPreview` overload to call the new overload with:

```java
return startPreview(playerId, sourceTownName, targetTownName, nodes, segmentTypes, settings,
        mergeSelection, logicalNodes, sharedSpans, RoadReusePlan.noReuse(List.of(), logicalNodes));
```

Extend `PreviewSnapshot`:

```java
public record PreviewSnapshot(List<BlockPos> nodes,
                              List<RoadPlannerSegmentType> segmentTypes,
                              RoadPlannerBuildSettings settings,
                              RoadPlannerMergeSelection mergeSelection,
                              List<BlockPos> logicalNodes,
                              List<RoadPlannerSharedRoadSpan> sharedSpans,
                              String sourceTownName,
                              String targetTownName,
                              RoadReusePlan reusePlan) {
    public PreviewSnapshot {
        nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
        segmentTypes = segmentTypes == null ? List.of() : List.copyOf(segmentTypes);
        settings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        mergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
        logicalNodes = logicalNodes == null || logicalNodes.isEmpty()
                ? nodes
                : logicalNodes.stream().map(BlockPos::immutable).toList();
        sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                .filter(java.util.Objects::nonNull)
                .filter(RoadPlannerSharedRoadSpan::present)
                .toList();
        sourceTownName = sourceTownName == null ? "" : sourceTownName.trim();
        targetTownName = targetTownName == null ? "" : targetTownName.trim();
        reusePlan = reusePlan == null ? RoadReusePlan.noReuse(List.of(), logicalNodes) : reusePlan;
    }

    public PreviewSnapshot(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes, RoadPlannerBuildSettings settings) {
        this(nodes, segmentTypes, settings, RoadPlannerMergeSelection.none(), nodes, List.of(), "", "",
                RoadReusePlan.noReuse(List.of(), nodes));
    }

    public PreviewSnapshot(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes, RoadPlannerBuildSettings settings, RoadPlannerMergeSelection mergeSelection) {
        this(nodes, segmentTypes, settings, mergeSelection, nodes, List.of(), "", "",
                RoadReusePlan.noReuse(List.of(), nodes));
    }
}
```

Extend `BuildMetadata` and `CompletedRoadBuild` with a `RoadReusePlan reusePlan` field. In `BuildMetadata.from`, pass:

```java
snapshot == null ? RoadReusePlan.noReuse(List.of(), centerPath) : snapshot.reusePlan()
```

In `completedRoadBuild`, pass `metadata.reusePlan()` into the `CompletedRoadBuild` constructor.

- [ ] **Step 4: Filter compiled build steps to owned reuse ranges**

In `RoadPlannerBuildControlService`, change `buildSteps(PreviewSnapshot snapshot, ServerLevel level)` to:

```java
private static List<BuildStep> buildSteps(PreviewSnapshot snapshot, ServerLevel level) {
    if (snapshot == null) {
        return List.of();
    }
    List<BuildStep> compiled = RoadPlannerBuildStepCompiler.compile(snapshot.nodes(), snapshot.segmentTypes(), snapshot.settings(), level);
    return filterOwnedBuildSteps(compiled, snapshot.reusePlan());
}
```

Add helpers:

```java
static List<BuildStep> filterOwnedBuildStepsForTest(List<BuildStep> buildSteps, RoadReusePlan reusePlan) {
    return filterOwnedBuildSteps(buildSteps, reusePlan);
}

private static List<BuildStep> filterOwnedBuildSteps(List<BuildStep> buildSteps, RoadReusePlan reusePlan) {
    if (buildSteps == null || buildSteps.isEmpty()) {
        return List.of();
    }
    if (reusePlan == null || reusePlan.reuseSpans().isEmpty()) {
        return buildSteps;
    }
    java.util.Set<Long> ownedPositions = ownedPlacementPositions(reusePlan);
    if (ownedPositions.isEmpty()) {
        return List.of();
    }
    return buildSteps.stream()
            .filter(step -> step != null && step.pos() != null && ownedPositions.contains(step.pos().asLong()))
            .toList();
}

private static java.util.Set<Long> ownedPlacementPositions(RoadReusePlan reusePlan) {
    java.util.LinkedHashSet<Long> positions = new java.util.LinkedHashSet<>();
    if (reusePlan == null || reusePlan.plannedPlacements().isEmpty()) {
        return positions;
    }
    for (RoadReusePlan.Range range : reusePlan.ownedRanges()) {
        int from = Math.max(0, range.fromIndex());
        int to = Math.min(reusePlan.plannedPlacements().size() - 1, range.toIndex());
        for (int index = from; index <= to; index++) {
            RoadGraphSegmentPlacement placement = reusePlan.plannedPlacements().get(index);
            if (placement == null) {
                continue;
            }
            positions.add(placement.middlePos().asLong());
            for (BlockPos pos : placement.positions()) {
                positions.add(pos.asLong());
            }
        }
    }
    return positions;
}
```

Add import:

```java
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphSegmentPlacement;
```

- [ ] **Step 5: Compile after record constructor updates**

Run:

```powershell
.\gradlew.bat compileJava
```

Expected: PASS after updating all constructor call sites with `RoadReusePlan.noReuse(List.of(), centerPath)` or `RoadReusePlan.noReuse(List.of(), List.of())`.

- [ ] **Step 6: Run build control tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlServiceTest
```

Expected: PASS.

- [ ] **Step 7: Commit preview reuse metadata**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlServiceTest.java
git commit -m "Carry road reuse plans through builds"
```

Expected: one commit for build-control metadata.

---

### Task 5: Completed Builds Write Graph Roads

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistry.java`
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistryTest.java`

- [ ] **Step 1: Add failing registry test**

Add this test to `RoadPlannerBuiltRoadRegistryTest`:

```java
@Test
void completedManualRoadRegistersGraphEdgeWithReuseSpansAndOwnedBlocks() {
    TestServerLevel level = newPersistentLevel();
    UUID owner = UUID.randomUUID();
    RoadGraphReuseSpan span = new RoadGraphReuseSpan(UUID.randomUUID(), 0, 4, 0, 4,
            new BlockPos(0, 64, 0), new BlockPos(4, 64, 0), RoadGraphReuseSpan.Relationship.OWN);
    RoadReusePlan reusePlan = new RoadReusePlan(
            List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0), new BlockPos(8, 64, 0)),
            List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
            List.of(new RoadGraphSegmentPlacement(new BlockPos(8, 64, 0), List.of(new BlockPos(8, 64, 0)))),
            List.of(new RoadReusePlan.Range(5, 8)),
            List.of(span),
            List.of());
    List<BuildStep> steps = List.of(new BuildStep(0, new BlockPos(8, 64, 0), Blocks.GRASS_BLOCK.defaultBlockState(), BuildPhase.SURFACE));

    RoadPlannerBuiltRoadRegistry.register(level, new RoadPlannerBuildControlService.CompletedRoadBuild(
            "graph-road",
            owner,
            List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
            List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
            steps,
            rollbackEntriesFor(steps),
            Level.OVERWORLD,
            RoadPlannerMergeSelection.none(),
            List.of(),
            "Town A",
            "Town B",
            reusePlan));

    RoadNetworkGraphSavedData graph = RoadNetworkGraphSavedData.get(level);
    RoadGraphEdgeRecord edge = graph.edges().iterator().next();
    assertEquals("graph-road", edge.roadName());
    assertEquals(List.of(span), edge.reuseSpans());
    assertEquals(List.of(new BlockPos(8, 64, 0)), edge.ownedBlockPositions());
}
```

Add this rollback helper to the test file:

```java
private static List<ConstructionQueue.RollbackEntry> rollbackEntriesFor(List<BuildStep> steps) {
    return steps.stream()
            .map(step -> new ConstructionQueue.RollbackEntry(step.pos(), Blocks.AIR.defaultBlockState()))
            .toList();
}
```

- [ ] **Step 2: Run registry test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadRegistryTest --tests "*completedManualRoadRegistersGraphEdgeWithReuseSpansAndOwnedBlocks"
```

Expected: failure because registry still writes only legacy road data.

- [ ] **Step 3: Add graph write to registry**

In `RoadPlannerBuiltRoadRegistry.register`, after `data.putRoadNetwork(road)` and runtime job persistence, add:

```java
registerGraphRoad(level, build, scope, creatorUuid, creatorName, executedSteps);
```

Add helper:

```java
private static void registerGraphRoad(ServerLevel level,
                                      RoadPlannerBuildControlService.CompletedRoadBuild build,
                                      RoadScope scope,
                                      String creatorUuid,
                                      String creatorName,
                                      List<BuildStep> executedSteps) {
    if (level == null || build == null || build.centerPath().size() < 2) {
        return;
    }
    RoadNetworkGraphSavedData graph = RoadNetworkGraphSavedData.get(level);
    long now = System.currentTimeMillis();
    UUID fromNodeId = UUID.randomUUID();
    UUID toNodeId = UUID.randomUUID();
    String dimensionId = level.dimension().location().toString();
    List<BlockPos> centerPath = build.reusePlan() == null || build.reusePlan().logicalCenterline().isEmpty()
            ? build.centerPath()
            : build.reusePlan().logicalCenterline();
    List<BlockPos> displayPath = build.reusePlan() == null || build.reusePlan().displayPath().isEmpty()
            ? build.displayPath()
            : build.reusePlan().displayPath();
    graph.putNode(new RoadGraphNodeRecord(fromNodeId, dimensionId, centerPath.get(0),
            RoadGraphNodeRecord.Kind.NORMAL, scope.nationId(), scope.townId(), "", now, now));
    graph.putNode(new RoadGraphNodeRecord(toNodeId, dimensionId, centerPath.get(centerPath.size() - 1),
            RoadGraphNodeRecord.Kind.NORMAL, scope.nationId(), scope.townId(), "", now, now));
    graph.putEdge(new RoadGraphEdgeRecord(
            UUID.nameUUIDFromBytes(("road-graph-edge:" + build.roadId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            fromNodeId,
            toNodeId,
            dimensionId,
            scope.nationId(),
            scope.townId(),
            creatorUuid,
            creatorName,
            build.sourceTownName(),
            build.targetTownName(),
            build.roadId(),
            3,
            com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType.ROAD,
            RoadGraphEdgeRecord.Status.BUILT,
            centerPath,
            displayPath,
            build.reusePlan() == null ? List.of() : build.reusePlan().plannedPlacements(),
            ownedBlocksAsPositions(executedSteps),
            build.reusePlan() == null ? List.of() : build.reusePlan().reuseSpans(),
            now,
            now));
}

private static List<BlockPos> ownedBlocksAsPositions(List<BuildStep> buildSteps) {
    if (buildSteps == null || buildSteps.isEmpty()) {
        return List.of();
    }
    return buildSteps.stream()
            .filter(step -> step != null && step.pos() != null)
            .map(step -> step.pos().immutable())
            .distinct()
            .toList();
}
```

This implementation writes a single graph edge per completed build. Splitting at junctions is outside this plan and must not be added during these tasks.

- [ ] **Step 4: Run registry tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadRegistryTest
```

Expected: PASS.

- [ ] **Step 5: Commit graph registration**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistry.java src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadRegistryTest.java
git commit -m "Register completed roads in graph"
```

Expected: one commit for graph registration.

---

### Task 6: Graph Overlay Service And Legacy Visual Boundary

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphOverlayService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeService.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphOverlayServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeServiceTest.java`

- [ ] **Step 1: Write failing overlay service test**

```java
package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGraphOverlayServiceTest {
    @Test
    void returnsGraphRoadsAndWeakLegacyRoadsButOnlyGraphCandidates() {
        RoadNetworkGraphSavedData graph = new RoadNetworkGraphSavedData();
        RoadGraphNodeRecord a = node(new BlockPos(0, 64, 0));
        RoadGraphNodeRecord b = node(new BlockPos(10, 64, 0));
        graph.putNode(a);
        graph.putNode(b);
        RoadGraphEdgeRecord edge = edge(a.nodeId(), b.nodeId());
        graph.putEdge(edge);

        NationSavedData legacyData = new NationSavedData();
        legacyData.putRoadNetwork(new RoadNetworkRecord("legacy-road", "alpha", "", "minecraft:overworld",
                "a", "b", List.of(new BlockPos(0, 64, 10), new BlockPos(10, 64, 10)),
                1L, RoadNetworkRecord.SOURCE_TYPE_MANUAL));

        RoadGraphOverlayService service = new RoadGraphOverlayService(new RoadGraphRepository(graph), legacyData);

        List<RoadGraphOverlayService.Overlay> overlays = service.visibleOverlays(
                "minecraft:overworld", new BlockPos(5, 64, 5), 64, 4);
        List<RoadGraphOverlayService.Candidate> candidates = service.candidatesNear(
                "minecraft:overworld", new BlockPos(5, 64, 0), 12);

        assertEquals(2, overlays.size());
        assertTrue(overlays.stream().anyMatch(RoadGraphOverlayService.Overlay::legacy));
        assertTrue(overlays.stream().anyMatch(overlay -> !overlay.legacy()));
        assertEquals(List.of(edge.edgeId()), candidates.stream().map(RoadGraphOverlayService.Candidate::edgeId).toList());
        assertFalse(candidates.stream().anyMatch(candidate -> candidate.displayName().equals("legacy-road")));
    }

    private static RoadGraphNodeRecord node(BlockPos pos) {
        return new RoadGraphNodeRecord(UUID.randomUUID(), "minecraft:overworld", pos,
                RoadGraphNodeRecord.Kind.NORMAL, "alpha", "", "", 1L, 1L);
    }

    private static RoadGraphEdgeRecord edge(UUID from, UUID to) {
        List<BlockPos> path = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 64, 0));
        return new RoadGraphEdgeRecord(UUID.randomUUID(), from, to, "minecraft:overworld", "alpha", "",
                "", "", "Town A", "Town B", "Graph Road", 3, CompiledRoadSectionType.ROAD,
                RoadGraphEdgeRecord.Status.BUILT, path, path,
                path.stream().map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos))).toList(),
                List.of(), List.of(), 1L, 1L);
    }
}
```

- [ ] **Step 2: Run overlay test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.graph.RoadGraphOverlayServiceTest
```

Expected: compile failure because `RoadGraphOverlayService` does not exist.

- [ ] **Step 3: Implement `RoadGraphOverlayService`**

```java
package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

public final class RoadGraphOverlayService {
    private final RoadGraphRepository repository;
    private final NationSavedData legacyData;

    public RoadGraphOverlayService(RoadGraphRepository repository, NationSavedData legacyData) {
        this.repository = repository == null ? new RoadGraphRepository(new RoadNetworkGraphSavedData()) : repository;
        this.legacyData = legacyData == null ? new NationSavedData() : legacyData;
    }

    public List<Overlay> visibleOverlays(String dimensionId, BlockPos center, int regionSize, int lodStepBlocks) {
        if (center == null || regionSize <= 0) {
            return List.of();
        }
        int half = regionSize / 2;
        int minX = center.getX() - half;
        int maxX = center.getX() + half;
        int minZ = center.getZ() - half;
        int maxZ = center.getZ() + half;
        java.util.ArrayList<Overlay> overlays = new java.util.ArrayList<>();
        for (RoadGraphEdgeRecord edge : repository.spatialIndex().queryRect(dimensionId, minX, minZ, maxX, maxZ)) {
            overlays.add(new Overlay(edge.edgeId().toString(), false, edge.roadName(),
                    simplify(edge.displayPath(), Math.max(1, lodStepBlocks)), edge.width(), edge.status().name()));
        }
        String dim = dimensionId == null ? "" : dimensionId.trim().toLowerCase(java.util.Locale.ROOT);
        for (RoadNetworkRecord legacy : legacyData.getRoadNetworks()) {
            if (legacy != null && dim.equals(legacy.dimensionId().toLowerCase(java.util.Locale.ROOT))
                    && intersects(legacy.displayPath(), minX, minZ, maxX, maxZ)) {
                overlays.add(new Overlay(legacy.roadId(), true, legacy.roadId(),
                        simplify(legacy.displayPath(), Math.max(1, lodStepBlocks)), 1, "LEGACY"));
            }
        }
        return List.copyOf(overlays);
    }

    public List<Candidate> candidatesNear(String dimensionId, BlockPos probe, int radiusBlocks) {
        return repository.spatialIndex().near(dimensionId, probe, radiusBlocks).stream()
                .map(hit -> new Candidate(hit.edge().edgeId(), hit.edge().roadName(), hit.pos(), hit.segmentIndex()))
                .toList();
    }

    private static boolean intersects(List<BlockPos> path, int minX, int minZ, int maxX, int maxZ) {
        if (path == null) {
            return false;
        }
        for (BlockPos pos : path) {
            if (pos != null && pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> simplify(List<BlockPos> path, int lodStepBlocks) {
        if (path == null || path.size() <= 2) {
            return path == null ? List.of() : path;
        }
        java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>();
        BlockPos keep = path.get(0);
        out.add(keep);
        for (int index = 1; index < path.size() - 1; index++) {
            BlockPos current = path.get(index);
            if (Math.abs(current.getX() - keep.getX()) + Math.abs(current.getZ() - keep.getZ()) >= lodStepBlocks) {
                out.add(current);
                keep = current;
            }
        }
        BlockPos tail = path.get(path.size() - 1);
        if (!tail.equals(out.get(out.size() - 1))) {
            out.add(tail);
        }
        return List.copyOf(out);
    }

    public record Overlay(String id, boolean legacy, String displayName, List<BlockPos> displayPath, int width, String status) {
    }

    public record Candidate(UUID edgeId, String displayName, BlockPos anchorPos, int segmentIndex) {
    }
}
```

- [ ] **Step 4: Run overlay tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.graph.RoadGraphOverlayServiceTest
```

Expected: PASS.

- [ ] **Step 5: Wire merge service to graph candidates in a narrow adapter**

In `RoadPlannerRoadMergeService`, add a new graph-first helper near `findCandidates`:

```java
private static List<Candidate> graphCandidates(ServerLevel level,
                                               NationSavedData data,
                                               BlockPos probe,
                                               int radius,
                                               RoadPlannerMergeScope scope,
                                               RoadPlannerSegmentType currentSegmentType) {
    if (level == null || data == null || probe == null || scope == null || !scope.enabled() || isBridgeSegment(currentSegmentType)) {
        return List.of();
    }
    RoadGraphOverlayService overlayService = new RoadGraphOverlayService(
            RoadGraphRepository.forLevel(level),
            data);
    return overlayService.candidatesNear(level.dimension().location().toString(), probe, radius).stream()
            .map(candidate -> new Candidate(candidate.edgeId().toString(), candidate.anchorPos(), candidate.segmentIndex(),
                    (int) Math.round(Math.sqrt(candidate.anchorPos().distSqr(probe))),
                    candidate.displayName(), candidate.displayName(), "", RoadPlannerMergeRelationship.OWN))
            .toList();
}
```

Then call it at the start of server-backed `findCandidates(ServerLevel level, ServerPlayer actor, ...)`:

```java
List<Candidate> graph = graphCandidates(level, data, probe, radius, scope, currentSegmentType);
if (!graph.isEmpty()) {
    return graph.size() > MAX_CANDIDATES ? graph.subList(0, MAX_CANDIDATES) : graph;
}
```

This preserves existing legacy test paths for `findCandidatesForTest` while graph-backed server behavior moves forward. Later packet/UI tasks can carry UUID edge ids explicitly.

- [ ] **Step 6: Run merge service tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.RoadPlannerRoadMergeServiceTest --tests com.monpai.sailboatmod.roadplanner.graph.RoadGraphOverlayServiceTest
```

Expected: PASS.

- [ ] **Step 7: Commit overlay service**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphOverlayService.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeService.java src/test/java/com/monpai/sailboatmod/roadplanner/graph/RoadGraphOverlayServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/RoadPlannerRoadMergeServiceTest.java
git commit -m "Add graph road overlays"
```

Expected: one commit for graph overlay and candidate adapter.

---

### Task 7: Routing And Carriage Integration

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/route/RoadGraphRoutingService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/route/CarriageRoutePlanner.java`
- Test: `src/test/java/com/monpai/sailboatmod/route/RoadGraphRoutingServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/route/CarriageRoutePlannerTest.java`

- [ ] **Step 1: Write failing routing service test**

```java
package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGraphRoutingServiceTest {
    @Test
    void resolvesShortestGraphPathAcrossBranch() {
        RoadNetworkGraphSavedData data = new RoadNetworkGraphSavedData();
        RoadGraphNodeRecord a = node(new BlockPos(0, 64, 0));
        RoadGraphNodeRecord b = node(new BlockPos(10, 64, 0));
        RoadGraphNodeRecord c = node(new BlockPos(20, 64, 0));
        data.putNode(a);
        data.putNode(b);
        data.putNode(c);
        data.putEdge(edge(a, b));
        data.putEdge(edge(b, c));
        RoadGraphRoutingService service = new RoadGraphRoutingService(new RoadGraphRepository(data));

        List<BlockPos> path = service.route("minecraft:overworld", a.pos(), c.pos(), 12);

        assertEquals(new BlockPos(0, 64, 0), path.get(0));
        assertEquals(new BlockPos(20, 64, 0), path.get(path.size() - 1));
        assertTrue(path.contains(new BlockPos(10, 64, 0)));
    }

    @Test
    void corridorMembershipUsesGraphFootprint() {
        RoadNetworkGraphSavedData data = new RoadNetworkGraphSavedData();
        RoadGraphNodeRecord a = node(new BlockPos(0, 64, 0));
        RoadGraphNodeRecord b = node(new BlockPos(10, 64, 0));
        data.putNode(a);
        data.putNode(b);
        data.putEdge(edge(a, b));

        RoadGraphRoutingService service = new RoadGraphRoutingService(new RoadGraphRepository(data));

        assertTrue(service.isRoadCorridor("minecraft:overworld", new BlockPos(5, 64, 1), 1));
    }

    private static RoadGraphNodeRecord node(BlockPos pos) {
        return new RoadGraphNodeRecord(UUID.randomUUID(), "minecraft:overworld", pos,
                RoadGraphNodeRecord.Kind.NORMAL, "alpha", "", "", 1L, 1L);
    }

    private static RoadGraphEdgeRecord edge(RoadGraphNodeRecord from, RoadGraphNodeRecord to) {
        List<BlockPos> centerline = interpolate(from.pos(), to.pos());
        return new RoadGraphEdgeRecord(UUID.randomUUID(), from.nodeId(), to.nodeId(), "minecraft:overworld",
                "alpha", "", "", "", "", "", "edge", 3, CompiledRoadSectionType.ROAD,
                RoadGraphEdgeRecord.Status.BUILT, centerline, List.of(from.pos(), to.pos()),
                centerline.stream().map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos, pos.north(), pos.south()))).toList(),
                List.of(), List.of(), 1L, 1L);
    }

    private static List<BlockPos> interpolate(BlockPos from, BlockPos to) {
        java.util.ArrayList<BlockPos> points = new java.util.ArrayList<>();
        int dx = Integer.compare(to.getX() - from.getX(), 0);
        BlockPos cursor = from;
        points.add(cursor);
        while (!cursor.equals(to)) {
            cursor = cursor.offset(dx, 0, 0);
            points.add(cursor);
        }
        return List.copyOf(points);
    }
}
```

- [ ] **Step 2: Run routing tests and verify they fail**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.route.RoadGraphRoutingServiceTest
```

Expected: compile failure because `RoadGraphRoutingService` does not exist.

- [ ] **Step 3: Implement `RoadGraphRoutingService`**

```java
package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNodeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class RoadGraphRoutingService {
    private final RoadGraphRepository repository;

    public RoadGraphRoutingService(RoadGraphRepository repository) {
        this.repository = repository;
    }

    public List<BlockPos> route(String dimensionId, BlockPos start, BlockPos end, int connectorRadius) {
        if (repository == null || start == null || end == null) {
            return List.of();
        }
        Graph graph = buildGraph(dimensionId);
        UUID startNode = nearestNode(start, graph.nodes(), connectorRadius);
        UUID endNode = nearestNode(end, graph.nodes(), connectorRadius);
        if (startNode == null || endNode == null) {
            return List.of();
        }
        List<UUID> nodePath = dijkstra(startNode, endNode, graph.adjacency());
        if (nodePath.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        out.add(start.immutable());
        for (int index = 1; index < nodePath.size(); index++) {
            EdgeStep step = graph.edgeBetween().get(edgeKey(nodePath.get(index - 1), nodePath.get(index)));
            if (step != null) {
                out.addAll(step.forward() ? step.edge().centerline() : reversed(step.edge().centerline()));
            }
        }
        out.add(end.immutable());
        return List.copyOf(out);
    }

    public boolean isRoadCorridor(String dimensionId, BlockPos pos, int radiusBlocks) {
        return repository != null && repository.spatialIndex().isRoadCorridor(dimensionId, pos, radiusBlocks);
    }

    private Graph buildGraph(String dimensionId) {
        Map<UUID, RoadGraphNodeRecord> nodes = new HashMap<>();
        for (RoadGraphNodeRecord node : repository.nodes()) {
            if (node.dimensionId().equalsIgnoreCase(dimensionId)) {
                nodes.put(node.nodeId(), node);
            }
        }
        Map<UUID, Set<RouteNeighbor>> adjacency = new HashMap<>();
        Map<String, EdgeStep> edgeBetween = new HashMap<>();
        for (RoadGraphEdgeRecord edge : repository.edgesForDimension(dimensionId)) {
            if (!edge.built() || !nodes.containsKey(edge.fromNodeId()) || !nodes.containsKey(edge.toNodeId())) {
                continue;
            }
            double length = length(edge.centerline());
            adjacency.computeIfAbsent(edge.fromNodeId(), ignored -> new HashSet<>()).add(new RouteNeighbor(edge.toNodeId(), length));
            adjacency.computeIfAbsent(edge.toNodeId(), ignored -> new HashSet<>()).add(new RouteNeighbor(edge.fromNodeId(), length));
            edgeBetween.put(edgeKey(edge.fromNodeId(), edge.toNodeId()), new EdgeStep(edge, true));
            edgeBetween.put(edgeKey(edge.toNodeId(), edge.fromNodeId()), new EdgeStep(edge, false));
        }
        return new Graph(nodes, adjacency, edgeBetween);
    }

    private static UUID nearestNode(BlockPos origin, Map<UUID, RoadGraphNodeRecord> nodes, int radius) {
        UUID best = null;
        long bestDistance = (long) radius * (long) radius;
        for (RoadGraphNodeRecord node : nodes.values()) {
            long distance = (long) origin.distSqr(node.pos());
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = node.nodeId();
            }
        }
        return best;
    }

    private static List<UUID> dijkstra(UUID start, UUID end, Map<UUID, Set<RouteNeighbor>> adjacency) {
        PriorityQueue<RouteNode> open = new PriorityQueue<>(Comparator.comparingDouble(RouteNode::distance));
        Map<UUID, Double> dist = new HashMap<>();
        Map<UUID, UUID> prev = new HashMap<>();
        open.add(new RouteNode(start, 0.0D));
        dist.put(start, 0.0D);
        while (!open.isEmpty()) {
            RouteNode current = open.poll();
            if (current.nodeId().equals(end)) {
                break;
            }
            if (current.distance() > dist.getOrDefault(current.nodeId(), Double.MAX_VALUE)) {
                continue;
            }
            for (RouteNeighbor neighbor : adjacency.getOrDefault(current.nodeId(), Set.of())) {
                double candidate = current.distance() + neighbor.weight();
                if (candidate < dist.getOrDefault(neighbor.nodeId(), Double.MAX_VALUE)) {
                    dist.put(neighbor.nodeId(), candidate);
                    prev.put(neighbor.nodeId(), current.nodeId());
                    open.add(new RouteNode(neighbor.nodeId(), candidate));
                }
            }
        }
        if (!dist.containsKey(end)) {
            return List.of();
        }
        ArrayList<UUID> path = new ArrayList<>();
        UUID cursor = end;
        path.add(cursor);
        while (!cursor.equals(start)) {
            cursor = prev.get(cursor);
            if (cursor == null) {
                return List.of();
            }
            path.add(cursor);
        }
        java.util.Collections.reverse(path);
        return List.copyOf(path);
    }

    private static double length(List<BlockPos> path) {
        double total = 0.0D;
        for (int index = 1; index < path.size(); index++) {
            total += Math.sqrt(path.get(index - 1).distSqr(path.get(index)));
        }
        return total;
    }

    private static List<BlockPos> reversed(List<BlockPos> path) {
        ArrayList<BlockPos> out = new ArrayList<>(path);
        java.util.Collections.reverse(out);
        return out;
    }

    private static String edgeKey(UUID from, UUID to) {
        return from + "|" + to;
    }

    private record Graph(Map<UUID, RoadGraphNodeRecord> nodes,
                         Map<UUID, Set<RouteNeighbor>> adjacency,
                         Map<String, EdgeStep> edgeBetween) {
    }

    private record RouteNeighbor(UUID nodeId, double weight) {
    }

    private record RouteNode(UUID nodeId, double distance) {
    }

    private record EdgeStep(RoadGraphEdgeRecord edge, boolean forward) {
    }
}
```

- [ ] **Step 4: Run routing service tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.route.RoadGraphRoutingServiceTest
```

Expected: PASS.

- [ ] **Step 5: Switch `CarriageRoutePlanner` corridor classification**

Modify `collectNetworkNodes` and `classify` usage. Replace the path-set classification with a graph service:

```java
static CarriageRoutePlan planFromPath(ServerLevel level, List<BlockPos> path) {
    if (level == null || path == null || path.size() < 2) {
        return CarriageRoutePlan.empty();
    }
    RoadGraphRoutingService graph = new RoadGraphRoutingService(
            com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository.forLevel(level));
    List<CarriageRoutePlan.Segment> segments = splitIntoSegments(level.dimension().location().toString(), path, graph);
    return segments.isEmpty() ? CarriageRoutePlan.empty() : new CarriageRoutePlan(segments);
}
```

Replace `splitIntoSegments(List<BlockPos> path, Set<BlockPos> networkNodes)` with:

```java
private static List<CarriageRoutePlan.Segment> splitIntoSegments(String dimensionId,
                                                                 List<BlockPos> path,
                                                                 RoadGraphRoutingService graph) {
    List<CarriageRoutePlan.Segment> segments = new ArrayList<>();
    if (path == null || path.size() < 2) {
        return segments;
    }
    CarriageRoutePlan.SegmentKind currentKind = classify(dimensionId, path.get(0), graph);
    List<BlockPos> currentPath = new ArrayList<>();
    currentPath.add(path.get(0).immutable());
    for (int i = 1; i < path.size(); i++) {
        BlockPos previous = path.get(i - 1);
        BlockPos current = path.get(i);
        CarriageRoutePlan.SegmentKind nextKind = classify(dimensionId, current, graph);
        if (nextKind != currentKind) {
            currentPath.add(current.immutable());
            addSegmentIfUsable(segments, currentKind, currentPath);
            currentPath = new ArrayList<>();
            currentPath.add(previous.immutable());
            currentPath.add(current.immutable());
            currentKind = nextKind;
            continue;
        }
        currentPath.add(current.immutable());
    }
    addSegmentIfUsable(segments, currentKind, currentPath);
    return List.copyOf(segments);
}

private static CarriageRoutePlan.SegmentKind classify(String dimensionId, BlockPos pos, RoadGraphRoutingService graph) {
    return graph != null && graph.isRoadCorridor(dimensionId, pos, 1)
            ? CarriageRoutePlan.SegmentKind.ROAD_CORRIDOR
            : CarriageRoutePlan.SegmentKind.TERRAIN_CONNECTOR;
}
```

Remove imports for `NationSavedData`, `RoadNetworkRecord`, and `RoadHybridRouteResolver` when unused.

- [ ] **Step 6: Add regression test that legacy roads do not classify as road corridor**

In `CarriageRoutePlannerTest`, keep the existing legacy-road test and change its assertion to show legacy no longer counts:

```java
@Test
void legacyRoadRecordsDoNotCreateCarriageRoadCorridorSegments() {
    TestServerLevel level = newPersistentLevel();
    seedFlatGround(level, 0, 9, -1, 1, 64);
    seedRoad(level, new BlockPos(2, 64, 0), new BlockPos(7, 64, 0));

    CarriageRoutePlan plan = CarriageRoutePlanner.planFromPath(level,
            List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0), new BlockPos(7, 64, 0), new BlockPos(9, 64, 0)));

    assertTrue(plan.found());
    assertEquals(List.of(CarriageRoutePlan.SegmentKind.TERRAIN_CONNECTOR),
            plan.segments().stream().map(CarriageRoutePlan.Segment::kind).distinct().toList());
}
```

Add a new graph-backed positive test using `RoadNetworkGraphSavedData.get(level)` and graph nodes/edges:

```java
@Test
void graphRoadsCreateCarriageRoadCorridorSegments() {
    TestServerLevel level = newPersistentLevel();
    seedFlatGround(level, 0, 9, -1, 1, 64);
    seedGraphRoad(level, new BlockPos(2, 64, 0), new BlockPos(7, 64, 0));

    CarriageRoutePlan plan = CarriageRoutePlanner.planFromPath(level,
            List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0), new BlockPos(7, 64, 0), new BlockPos(9, 64, 0)));

    assertTrue(plan.found());
    assertEquals(List.of(
            CarriageRoutePlan.SegmentKind.TERRAIN_CONNECTOR,
            CarriageRoutePlan.SegmentKind.ROAD_CORRIDOR,
            CarriageRoutePlan.SegmentKind.TERRAIN_CONNECTOR
    ), plan.segments().stream().map(CarriageRoutePlan.Segment::kind).toList());
}
```

Add helper:

```java
private static void seedGraphRoad(TestServerLevel level, BlockPos from, BlockPos to) {
    List<BlockPos> path = buildStraightPath(from, to);
    RoadNetworkGraphSavedData graph = RoadNetworkGraphSavedData.get(level);
    RoadGraphNodeRecord a = new RoadGraphNodeRecord(UUID.randomUUID(), level.dimension().location().toString(), from,
            RoadGraphNodeRecord.Kind.NORMAL, "nation", "town", "", 1L, 1L);
    RoadGraphNodeRecord b = new RoadGraphNodeRecord(UUID.randomUUID(), level.dimension().location().toString(), to,
            RoadGraphNodeRecord.Kind.NORMAL, "nation", "town", "", 1L, 1L);
    graph.putNode(a);
    graph.putNode(b);
    graph.putEdge(new RoadGraphEdgeRecord(UUID.randomUUID(), a.nodeId(), b.nodeId(), level.dimension().location().toString(),
            "nation", "town", "", "", "", "", "graph-road", 3,
            com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType.ROAD,
            RoadGraphEdgeRecord.Status.BUILT, path, List.of(from, to),
            path.stream().map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos, pos.north(), pos.south()))).toList(),
            List.of(), List.of(), 1L, 1L));
}
```

- [ ] **Step 7: Run routing and carriage tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.route.RoadGraphRoutingServiceTest --tests com.monpai.sailboatmod.route.CarriageRoutePlannerTest
```

Expected: PASS.

- [ ] **Step 8: Commit routing integration**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/route/RoadGraphRoutingService.java src/main/java/com/monpai/sailboatmod/route/CarriageRoutePlanner.java src/test/java/com/monpai/sailboatmod/route/RoadGraphRoutingServiceTest.java src/test/java/com/monpai/sailboatmod/route/CarriageRoutePlannerTest.java
git commit -m "Route carriages on road graph"
```

Expected: one commit for graph routing and carriage classification.

---

### Task 8: Road Auto Route Uses Graph

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
- Modify: `src/test/java/com/monpai/sailboatmod/route/CarriageRoutePlannerTest.java`

- [ ] **Step 1: Add failing auto-route regression test**

Add this test method to `CarriageRoutePlannerTest`, using the existing `newPersistentLevel`, `seedFlatGround`, `seedRoad`, and `seedGraphRoad` helpers from Task 7:

```java
@Test
void findRoadRouteUsesGraphRoadsAndIgnoresLegacyRoadRecords() {
    TestServerLevel level = newPersistentLevel();
    seedFlatGround(level, 0, 20, -1, 1, 64);
    seedRoad(level, new BlockPos(2, 64, 0), new BlockPos(7, 64, 0));

    assertTrue(RoadAutoRouteService.findRoadRoute(level, new BlockPos(0, 64, 0), new BlockPos(9, 64, 0)).isEmpty());

    seedGraphRoad(level, new BlockPos(2, 64, 0), new BlockPos(7, 64, 0));

    List<BlockPos> graphRoute = RoadAutoRouteService.findRoadRoute(level, new BlockPos(0, 64, 0), new BlockPos(9, 64, 0));
    assertTrue(graphRoute.contains(new BlockPos(2, 64, 0)));
    assertTrue(graphRoute.contains(new BlockPos(7, 64, 0)));
}
```

- [ ] **Step 2: Run the auto-route regression and verify it fails**

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.route.CarriageRoutePlannerTest --tests "*findRoadRouteUsesGraphRoadsAndIgnoresLegacyRoadRecords"
```

Expected: failure because `RoadAutoRouteService` still reads legacy roads.

- [ ] **Step 3: Replace old graph builder in `RoadAutoRouteService`**

In `findRoadRoute`, replace:

```java
Graph graph = buildGraph(level);
```

with:

```java
RoadGraphRoutingService graphRouting = new RoadGraphRoutingService(
        com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository.forLevel(level));
List<BlockPos> route = graphRouting.route(level.dimension().location().toString(), start, end, (int) STATION_CONNECT_RADIUS);
return route.size() >= 2 ? route : List.of();
```

In `resolveAutoRoute`, replace the road-first branch:

```java
Graph graph = buildGraph(level);
if (!graph.nodes().isEmpty()) {
    return resolveRoadFirstRoute(level, start, end, graph);
}
```

with:

```java
RoadGraphRoutingService graphRouting = new RoadGraphRoutingService(
        com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository.forLevel(level));
List<BlockPos> graphRoute = graphRouting.route(level.dimension().location().toString(), start, end, CARRIAGE_CONNECTOR_MAX_MANHATTAN);
if (graphRoute.size() >= 2) {
    return new RouteResolution(PathSource.ROAD_NETWORK, graphRoute);
}
Graph graph = new Graph(Set.of(), Map.of());
```

Keep terrain fallback intact. Remove unused methods only after compile confirms they are not referenced: `buildGraph`, `nearestRoadNode`, `nearestRoadNodeWithinConnectorBudget`, `resolveRoadFirstRoute`, and internal graph Dijkstra helpers may become unused.

- [ ] **Step 4: Run route tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.route.CarriageRoutePlannerTest
```

Expected: PASS.

- [ ] **Step 5: Compile Java**

Run:

```powershell
.\gradlew.bat compileJava
```

Expected: PASS, with no unused import compile errors.

- [ ] **Step 6: Commit auto-route integration**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java src/test/java/com/monpai/sailboatmod/route/CarriageRoutePlannerTest.java
git commit -m "Use road graph for auto routes"
```

Expected: one commit for auto-route graph use.

---

### Task 9: Built-Road Map Refresh Rejects Unknown Pixels

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileMergeRules.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJob.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileMergeRulesTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJobTest.java`

- [ ] **Step 1: Write failing pure merge-rule tests**

```java
package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerTileMergeRulesTest {
    @Test
    void unknownPixelsDoNotOverwriteExistingPixels() {
        int[] base = { 0xFF112233, 0xFF445566 };
        int[] incoming = { 0xFF000000, 0xFF778899 };
        boolean[] coverage = { true, true };
        boolean[] known = { false, true };

        boolean applied = RoadPlannerTileMergeRules.merge(base, incoming, coverage, known);

        assertTrue(applied);
        assertArrayEquals(new int[] { 0xFF112233, 0xFF778899 }, base);
    }

    @Test
    void coverageWithoutKnownPixelsDoesNotApply() {
        int[] base = { 0xFF112233 };
        int[] incoming = { 0xFF000000 };
        boolean[] coverage = { true };
        boolean[] known = { false };

        boolean applied = RoadPlannerTileMergeRules.merge(base, incoming, coverage, known);

        assertFalse(applied);
        assertArrayEquals(new int[] { 0xFF112233 }, base);
    }

    @Test
    void fullCoverageRequiresKnownPixelsWhenKnownMaskProvided() {
        int[] base = { 0xFF111111, 0xFF222222 };
        int[] incoming = { 0xFF333333, 0xFF444444 };
        boolean[] known = { true, false };

        boolean applied = RoadPlannerTileMergeRules.merge(base, incoming, null, known);

        assertTrue(applied);
        assertArrayEquals(new int[] { 0xFF333333, 0xFF222222 }, base);
    }

    @Test
    void knownMaskRejectsBlackAndLoadingCheckerSamples() {
        assertArrayEquals(new boolean[] { false, false, false, true },
                RoadPlannerTileMergeRules.knownMask(new int[] {
                        0xFF000000,
                        0xFF2A2A2A,
                        0xFF3A3A3A,
                        0xFF778899
                }));
    }
}
```

- [ ] **Step 2: Run merge-rule test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileMergeRulesTest
```

Expected: compile failure because `RoadPlannerTileMergeRules` does not exist.

- [ ] **Step 3: Implement pure merge helper**

```java
package com.monpai.sailboatmod.client.roadplanner;

public final class RoadPlannerTileMergeRules {
    private RoadPlannerTileMergeRules() {
    }

    public static boolean[] knownMask(int[] incomingArgb) {
        if (incomingArgb == null) {
            return new boolean[0];
        }
        boolean[] known = new boolean[incomingArgb.length];
        for (int index = 0; index < incomingArgb.length; index++) {
            known[index] = !isUnsafeSample(incomingArgb[index]);
        }
        return known;
    }

    public static boolean merge(int[] targetArgb, int[] incomingArgb, boolean[] coverageMask, boolean[] knownMask) {
        if (targetArgb == null || incomingArgb == null || targetArgb.length != incomingArgb.length) {
            return false;
        }
        boolean fullCoverage = coverageMask == null || coverageMask.length != incomingArgb.length;
        boolean hasKnownMask = knownMask != null && knownMask.length == incomingArgb.length;
        boolean applied = false;
        for (int index = 0; index < incomingArgb.length; index++) {
            boolean covered = fullCoverage || coverageMask[index];
            boolean known = !hasKnownMask || knownMask[index];
            if (covered && known) {
                targetArgb[index] = incomingArgb[index];
                applied = true;
            }
        }
        return applied;
    }

    private static boolean isUnsafeSample(int argb) {
        int rgb = argb & 0x00FFFFFF;
        return rgb == 0x000000 || rgb == 0x2A2A2A || rgb == 0x3A3A3A;
    }
}
```

- [ ] **Step 4: Use helper inside `RoadPlannerTile.mergePixels`**

Replace the inner loop in `mergePixels` with:

```java
int[] current = new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS];
for (int y = 0; y < RoadMapTileSpec.TILE_PIXELS; y++) {
    for (int x = 0; x < RoadMapTileSpec.TILE_PIXELS; x++) {
        current[y * RoadMapTileSpec.TILE_PIXELS + x] = image.getPixelRGBA(x, y);
    }
}
boolean applied = RoadPlannerTileMergeRules.merge(current, argbPixels, coverageMask,
        RoadPlannerTileMergeRules.knownMask(argbPixels));
if (!applied) {
    return;
}
for (int y = 0; y < RoadMapTileSpec.TILE_PIXELS; y++) {
    for (int x = 0; x < RoadMapTileSpec.TILE_PIXELS; x++) {
        image.setPixelRGBA(x, y, current[y * RoadMapTileSpec.TILE_PIXELS + x]);
    }
}
```

Keep texture upload and dirty logic unchanged.

- [ ] **Step 5: Preserve built refresh full coverage test and add unsafe snapshot test**

In `RoadPlannerMapPreloadJobTest`, add:

```java
@Test
void builtRoadRefreshDoesNotEmitTileWhenSnapshotPixelsAreTooShort() {
    RoadMapRoutePreloadPlan plan = new RoadMapRoutePreloadPlan(
            RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY,
            List.of(new ChunkPos(0, 0)),
            1,
            1);
    RoadPlannerMapPreloadJob job = new RoadPlannerMapPreloadJob(
            UUID.randomUUID(),
            9L,
            RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH,
            "",
            "minecraft:overworld",
            plan);
    List<RoadPlannerMapTileSyncPacket> packets = new ArrayList<>();

    int processed = job.advance(1, key -> new RoadMapSnapshot(
            1L,
            RoadMapRegion.centeredOn(new BlockPos(128, 0, 128), RoadMapTileSpec.TILE_BLOCKS, MapLod.LOD_1),
            List.of(),
            new int[1]), packets::add);

    assertEquals(0, processed);
    assertTrue(packets.isEmpty());
}
```

This test is required regression coverage even when the current length check already satisfies it.

- [ ] **Step 6: Run tile and preload tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileMergeRulesTest --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadJobTest
```

Expected: PASS.

- [ ] **Step 7: Commit tile merge safety**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileMergeRules.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJob.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileMergeRulesTest.java src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJobTest.java
git commit -m "Protect road planner tiles from unsafe refreshes"
```

Expected: one commit for tile refresh safety.

---

### Task 10: Build Refresh Uses Graph Footprints

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadMapRefresh.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java`
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadMapRefreshTest.java`

- [ ] **Step 1: Add failing chunk collection test**

In `RoadPlannerBuiltRoadMapRefreshTest`, add:

```java
@Test
void chunksForGraphPlacementsIncludesFootprintPositions() {
    List<RoadGraphSegmentPlacement> placements = List.of(
            new RoadGraphSegmentPlacement(new BlockPos(0, 64, 0),
                    List.of(new BlockPos(0, 64, 0), new BlockPos(17, 64, 0))));

    Set<ChunkPos> chunks = RoadPlannerBuiltRoadMapRefresh.chunksForGraphPlacements(placements);

    assertEquals(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0)), chunks);
}
```

- [ ] **Step 2: Run refresh test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadMapRefreshTest --tests "*chunksForGraphPlacementsIncludesFootprintPositions"
```

Expected: compile failure because `chunksForGraphPlacements` does not exist.

- [ ] **Step 3: Add graph placement chunk helper**

In `RoadPlannerBuiltRoadMapRefresh`, import `RoadGraphSegmentPlacement` and add:

```java
public static Set<ChunkPos> chunksForGraphPlacements(Collection<RoadGraphSegmentPlacement> placements) {
    if (placements == null || placements.isEmpty()) {
        return Set.of();
    }
    LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
    for (RoadGraphSegmentPlacement placement : placements) {
        if (placement == null) {
            continue;
        }
        addChunk(chunks, placement.middlePos());
        addPositions(chunks, placement.positions());
    }
    return Set.copyOf(chunks);
}

public static void enqueueGraphPlacementRefresh(ServerLevel level, Collection<RoadGraphSegmentPlacement> placements) {
    enqueueChunks(level, chunksForGraphPlacements(placements));
}
```

- [ ] **Step 4: Enqueue graph placement refresh after build completion**

In `RoadPlannerBuildControlService.tick`, after current build-step refresh:

```java
if (metadata != null && metadata.reusePlan() != null) {
    RoadPlannerBuiltRoadMapRefresh.enqueueGraphPlacementRefresh(level, metadata.reusePlan().plannedPlacements());
}
```

Keep the existing `enqueueBuildStepRefresh` call so legacy and current construction steps still refresh.

- [ ] **Step 5: Run refresh tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadMapRefreshTest --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlServiceTest
```

Expected: PASS.

- [ ] **Step 6: Commit graph footprint refresh**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadMapRefresh.java src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuiltRoadMapRefreshTest.java
git commit -m "Refresh map tiles from graph footprints"
```

Expected: one commit for graph footprint map refresh.

---

### Task 11: RoadWeaver Attribution And Full Verification

**Files:**
- Create: `NOTICE`
- Verify: all touched source and test files

- [ ] **Step 1: Check copied source attribution**

Run:

```powershell
rg -n "RoadWeaver|Adapted from RoadWeaver|shiroha" src/main/java/com/monpai/sailboatmod/roadplanner/graph NOTICE
```

Expected: `RoadGraphSegmentPlacement`, `RoadGraphSpatialIndex`, and `RoadGraphReusePlanner` include RoadWeaver adaptation comments.

- [ ] **Step 2: Create `NOTICE` with RoadWeaver attribution**

Create `NOTICE` with this content:

```text
RoadWeaver Portions

This project includes code adapted from RoadWeaver.
RoadWeaver is licensed under the MIT License.
Copyright (c) 2025 shiroha-233.
Source reference in this workspace: F:\Codex\Ref\RoadWeaver-1.20.1-Architectury
```

- [ ] **Step 3: Run targeted graph and route tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.graph.* --tests com.monpai.sailboatmod.route.RoadGraphRoutingServiceTest --tests com.monpai.sailboatmod.route.CarriageRoutePlannerTest
```

Expected: PASS.

- [ ] **Step 4: Run road planner service regression tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlServiceTest --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadRegistryTest --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadMapRefreshTest --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadJobTest
```

Expected: PASS.

- [ ] **Step 5: Compile Java**

Run:

```powershell
.\gradlew.bat compileJava
```

Expected: PASS.

- [ ] **Step 6: Run full build**

Run:

```powershell
.\gradlew.bat build
```

Expected: PASS and jar generation under `build/libs/`.

- [ ] **Step 7: Inspect staged/unstaged changes before final commit**

Run:

```powershell
git status --short
git diff --stat
```

Expected: only intended road graph, route, road planner, test, and `NOTICE` files are changed. Logs and draft files remain unstaged.

- [ ] **Step 8: Commit attribution**

```powershell
git add NOTICE
git commit -m "Credit RoadWeaver adaptations"
```

Expected: no unrelated files staged.

## Self-Review Checklist

- Spec coverage:
  - Durable graph persistence: Tasks 1 and 2.
  - RoadWeaver direct reuse: Tasks 2, 3, and 11.
  - Automatic highlighted reuse model: Tasks 3, 4, and 6.
  - Build skipping for reused spans: Task 4 filters compiled build steps through `RoadReusePlan.ownedRanges`; Task 5 persists the resulting owned blocks.
  - Overlay and legacy visual boundary: Task 6.
  - Logistics and carriage routing: Tasks 7 and 8.
  - Black minimap tile bug: Tasks 9 and 10.
  - Tests and verification: Each task plus Task 11.
- Placeholder scan:
  - No deferred-work markers or vague implementation instructions should remain.
  - Every code-changing step includes concrete code or an exact replacement block.
- Type consistency:
  - `RoadGraphSegmentPlacement`, `RoadGraphReuseSpan`, `RoadGraphNodeRecord`, `RoadGraphEdgeRecord`, and `RoadReusePlan` are defined before dependent tasks reference them.
  - `RoadGraphRepository.forLevel(ServerLevel)` is defined before overlay/routing integrations reference it.
  - `RoadGraphRoutingService.isRoadCorridor(...)` is defined before `CarriageRoutePlanner` uses it.
