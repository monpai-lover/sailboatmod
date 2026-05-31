# Road Planner Auto Merge Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build automatic full-path road merging, preserve manual multi-merge fallback, and move route overlay rendering toward RoadWeaver-style LOD/status layers while guarding map tiles from black refresh regressions.

**Architecture:** Add a server-side graph resolver that returns a proven existing-road route from the current endpoint to the destination. Add dedicated auto-merge packets and client state so `RoadPlannerScreen` can auto-select, manually override, confirm, or fall back. Split overlay rendering into reusable LOD/line-style helpers and strengthen tile replacement rules for built-road refreshes.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, JUnit 5, existing `RoadGraphRepository`, `RoadPlannerScreen`, Forge SimpleChannel packets, `GuiGraphics` rendering.

---

## File Structure

- Create `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerAutoMergeRouteService.java`
  - Server-side graph route resolver and route ranking.
- Create `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlannerAutoMergeRouteServiceTest.java`
  - Pure unit tests with in-memory `RoadNetworkGraphSavedData`.
- Create `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerAutoMergeRouteRequestPacket.java`
  - Client-to-server request for automatic merge route resolution.
- Create `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerAutoMergeRouteSyncPacket.java`
  - Server-to-client result packet.
- Modify `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
  - Register request/sync packets next to merge candidate packets.
- Modify `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
  - Dispatch auto-merge sync results to the open planner screen.
- Modify `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`
  - Add packet round-trip tests.
- Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoMergeState.java`
  - Immutable client-side state for pending/found/failed/manual-fallback auto merge.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
  - Request auto merge, apply result, confirm merge, manual override, and fallback dialog.
- Modify `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`
  - Add screen behavior tests.
- Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerOverlayLod.java`
  - Polyline simplification based on world-to-screen scale.
- Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerOverlayLineRenderer.java`
  - Thick solid/dashed clipped line helpers adapted from RoadWeaver.
- Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoadOverlayRenderModel.java`
  - Render-layer state for base roads, hover, selected reuse path, and key nodes.
- Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerOverlayRenderingTest.java`
  - LOD, state selection, and node suppression tests.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileMergeRules.java`
  - Add a whole-tile safety check for built-road refresh replacement.
- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java`
  - Skip unsafe built-road tile syncs before loading/replacing a base tile.
- Modify `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileMergeRulesTest.java`
  - Add black/empty tile replacement regression tests.

---

### Task 1: Server Auto-Merge Route Resolver

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerAutoMergeRouteService.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlannerAutoMergeRouteServiceTest.java`

- [ ] **Step 1: Write failing resolver tests**

Create `RoadPlannerAutoMergeRouteServiceTest` with these tests. Use in-memory graph data so the tests do not need a server level.

```java
package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNodeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphSegmentPlacement;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraphSavedData;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerAutoMergeRouteServiceTest {
    @Test
    void findsFullExistingRoadRouteToDestination() {
        GraphFixture graph = new GraphFixture();
        RoadGraphNodeRecord a = graph.node(40, 0);
        RoadGraphNodeRecord b = graph.node(80, 0);
        RoadGraphNodeRecord c = graph.node(120, 0);
        RoadGraphEdgeRecord ab = graph.edge("ab", a, b);
        RoadGraphEdgeRecord bc = graph.edge("bc", b, c);

        RoadPlannerAutoMergeRouteService.Result result = RoadPlannerAutoMergeRouteService.resolve(
                new RoadPlannerAutoMergeRouteService.Query(
                        graph.repository(),
                        "minecraft:overworld",
                        List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0)),
                        List.of(RoadPlannerSegmentType.ROAD),
                        new BlockPos(120, 64, 0),
                        RoadPlannerMergeScope.OWN_NATION,
                        RoadPlannerAutoMergeRouteService.PreferredEntry.none(),
                        12,
                        12));

        assertEquals(RoadPlannerAutoMergeRouteService.Status.FOUND, result.status());
        assertEquals(ab.edgeId().toString(), result.mergeSelection().roadId());
        assertEquals(new BlockPos(40, 64, 0), result.mergeSelection().anchorPos());
        assertEquals(List.of(new BlockPos(40, 64, 0), new BlockPos(80, 64, 0), new BlockPos(120, 64, 0)),
                result.displayPath());
        assertEquals(2, result.sharedSpans().size());
        assertEquals(ab.edgeId().toString(), result.sharedSpans().get(0).roadId());
        assertEquals(bc.edgeId().toString(), result.sharedSpans().get(1).roadId());
    }

    @Test
    void rejectsRoadThatCannotReachDestination() {
        GraphFixture graph = new GraphFixture();
        RoadGraphNodeRecord a = graph.node(40, 0);
        RoadGraphNodeRecord b = graph.node(80, 0);
        RoadGraphNodeRecord far = graph.node(200, 0);
        graph.edge("ab", a, b);

        RoadPlannerAutoMergeRouteService.Result result = RoadPlannerAutoMergeRouteService.resolve(
                new RoadPlannerAutoMergeRouteService.Query(
                        graph.repository(),
                        "minecraft:overworld",
                        List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0)),
                        List.of(RoadPlannerSegmentType.ROAD),
                        far.pos(),
                        RoadPlannerMergeScope.OWN_NATION,
                        RoadPlannerAutoMergeRouteService.PreferredEntry.none(),
                        12,
                        12));

        assertEquals(RoadPlannerAutoMergeRouteService.Status.NOT_FOUND, result.status());
        assertFalse(result.mergeSelection().present());
        assertTrue(result.displayPath().isEmpty());
    }

    @Test
    void directionPriorityBeatsShorterButWrongTurnRoute() {
        GraphFixture graph = new GraphFixture();
        RoadGraphNodeRecord straightA = graph.node(40, 0);
        RoadGraphNodeRecord straightB = graph.node(120, 0);
        RoadGraphNodeRecord northA = graph.node(36, -12);
        RoadGraphNodeRecord northB = graph.node(120, 0);
        RoadGraphEdgeRecord straight = graph.edge("straight", straightA, straightB);
        graph.edge("north", northA, northB);

        RoadPlannerAutoMergeRouteService.Result result = RoadPlannerAutoMergeRouteService.resolve(
                new RoadPlannerAutoMergeRouteService.Query(
                        graph.repository(),
                        "minecraft:overworld",
                        List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0)),
                        List.of(RoadPlannerSegmentType.ROAD),
                        new BlockPos(120, 64, 0),
                        RoadPlannerMergeScope.OWN_NATION,
                        RoadPlannerAutoMergeRouteService.PreferredEntry.none(),
                        24,
                        12));

        assertEquals(RoadPlannerAutoMergeRouteService.Status.FOUND, result.status());
        assertEquals(straight.edgeId().toString(), result.mergeSelection().roadId());
    }

    @Test
    void manualPreferredRoadOverridesAutomaticEntryWhenReachable() {
        GraphFixture graph = new GraphFixture();
        RoadGraphNodeRecord lowerA = graph.node(40, 0);
        RoadGraphNodeRecord lowerB = graph.node(120, 0);
        RoadGraphNodeRecord upperA = graph.node(40, 16);
        RoadGraphNodeRecord upperB = graph.node(120, 0);
        graph.edge("lower", lowerA, lowerB);
        RoadGraphEdgeRecord upper = graph.edge("upper", upperA, upperB);

        RoadPlannerAutoMergeRouteService.Result result = RoadPlannerAutoMergeRouteService.resolve(
                new RoadPlannerAutoMergeRouteService.Query(
                        graph.repository(),
                        "minecraft:overworld",
                        List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0)),
                        List.of(RoadPlannerSegmentType.ROAD),
                        new BlockPos(120, 64, 0),
                        RoadPlannerMergeScope.OWN_NATION,
                        new RoadPlannerAutoMergeRouteService.PreferredEntry(upper.edgeId().toString(), -1),
                        24,
                        12));

        assertEquals(RoadPlannerAutoMergeRouteService.Status.FOUND, result.status());
        assertEquals(upper.edgeId().toString(), result.mergeSelection().roadId());
    }

    private static final class GraphFixture {
        private final RoadNetworkGraphSavedData data = new RoadNetworkGraphSavedData();

        RoadGraphRepository repository() {
            return new RoadGraphRepository(data);
        }

        RoadGraphNodeRecord node(int x, int z) {
            RoadGraphNodeRecord node = new RoadGraphNodeRecord(UUID.randomUUID(), "minecraft:overworld",
                    new BlockPos(x, 64, z), RoadGraphNodeRecord.Kind.NORMAL, "alpha", "", "", 1L, 1L);
            data.putNode(node);
            return node;
        }

        RoadGraphEdgeRecord edge(String name, RoadGraphNodeRecord from, RoadGraphNodeRecord to) {
            List<BlockPos> centerline = List.of(from.pos(), to.pos());
            RoadGraphEdgeRecord edge = new RoadGraphEdgeRecord(UUID.randomUUID(), from.nodeId(), to.nodeId(),
                    "minecraft:overworld", "alpha", "", "", "", "Alpha", "Beta", name, 3,
                    CompiledRoadSectionType.ROAD, RoadGraphEdgeRecord.Status.BUILT,
                    centerline, centerline,
                    centerline.stream().map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos))).toList(),
                    List.of(), List.of(), 1L, 1L);
            data.putEdge(edge);
            return edge;
        }
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPlannerAutoMergeRouteServiceTest"
```

Expected: compile failure because `RoadPlannerAutoMergeRouteService` does not exist.

- [ ] **Step 3: Implement resolver**

Create `RoadPlannerAutoMergeRouteService.java` with these public records and behavior.

```java
package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNodeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class RoadPlannerAutoMergeRouteService {
    public static final int DEFAULT_ENTRY_RADIUS = 64;
    public static final int DEFAULT_DESTINATION_RADIUS = 96;

    public enum Status {
        FOUND,
        NOT_FOUND,
        INVALID_REQUEST,
        SCOPE_BLOCKED
    }

    public record PreferredEntry(String roadId, int pathIndex) {
        public PreferredEntry {
            roadId = roadId == null ? "" : roadId.trim();
            pathIndex = Math.max(-1, pathIndex);
        }

        public static PreferredEntry none() {
            return new PreferredEntry("", -1);
        }

        public boolean present() {
            return !roadId.isBlank();
        }
    }

    public record Query(RoadGraphRepository repository,
                        String dimensionId,
                        List<BlockPos> routeNodes,
                        List<RoadPlannerSegmentType> routeSegments,
                        BlockPos destinationPos,
                        RoadPlannerMergeScope scope,
                        PreferredEntry preferredEntry,
                        int entryRadius,
                        int destinationRadius) {
        public Query {
            dimensionId = normalizeDimension(dimensionId);
            routeNodes = copyPositions(routeNodes);
            routeSegments = routeSegments == null ? List.of() : List.copyOf(routeSegments);
            destinationPos = destinationPos == null ? null : destinationPos.immutable();
            scope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
            preferredEntry = preferredEntry == null ? PreferredEntry.none() : preferredEntry;
            entryRadius = Math.max(1, entryRadius);
            destinationRadius = Math.max(1, destinationRadius);
        }
    }

    public record Result(Status status,
                         RoadPlannerMergeSelection mergeSelection,
                         List<BlockPos> displayPath,
                         List<RoadPlannerSharedRoadSpan> sharedSpans,
                         List<String> roadIds,
                         String message) {
        public Result {
            status = status == null ? Status.NOT_FOUND : status;
            mergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
            displayPath = copyPositions(displayPath);
            sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(RoadPlannerSharedRoadSpan::present)
                    .toList();
            roadIds = roadIds == null ? List.of() : roadIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            message = message == null ? "" : message;
        }

        public static Result notFound(String message) {
            return new Result(Status.NOT_FOUND, RoadPlannerMergeSelection.none(), List.of(), List.of(), List.of(), message);
        }
    }

    public static Result resolve(Query query) {
        if (query == null || query.repository() == null || query.routeNodes().isEmpty() || query.destinationPos() == null
                || query.dimensionId().isBlank()) {
            return new Result(Status.INVALID_REQUEST, RoadPlannerMergeSelection.none(), List.of(), List.of(), List.of(), "Invalid auto merge request");
        }
        if (!query.scope().enabled()) {
            return new Result(Status.SCOPE_BLOCKED, RoadPlannerMergeSelection.none(), List.of(), List.of(), List.of(), "Merge scope is disabled");
        }
        if (lastSegmentBridgeLike(query.routeSegments())) {
            return Result.notFound("Bridge segments cannot auto merge into existing roads");
        }

        Graph graph = buildGraph(query.repository(), query.dimensionId());
        if (graph.nodes().isEmpty() || graph.edges().isEmpty()) {
            return Result.notFound("No built road graph is available");
        }

        BlockPos currentEndpoint = query.routeNodes().get(query.routeNodes().size() - 1);
        Direction routeDirection = routeDirection(query.routeNodes());
        List<EntryCandidate> entries = entryCandidates(query, graph, currentEndpoint, routeDirection);
        List<UUID> destinationNodes = destinationNodes(query.destinationPos(), query.destinationRadius(), graph);
        if (entries.isEmpty() || destinationNodes.isEmpty()) {
            return Result.notFound("No connected existing road reaches the destination");
        }

        List<RouteCandidate> successful = new ArrayList<>();
        for (EntryCandidate entry : entries) {
            for (UUID destinationNode : destinationNodes) {
                Path path = shortestPath(entry.nodeId(), destinationNode, graph);
                if (!path.nodeIds().isEmpty()) {
                    successful.add(toRouteCandidate(query, graph, entry, path));
                }
            }
        }
        return successful.stream()
                .min(RouteCandidate.ORDER)
                .map(RouteCandidate::result)
                .orElseGet(() -> Result.notFound("No connected existing road reaches the destination"));
    }

    private static Graph buildGraph(RoadGraphRepository repository, String dimensionId) {
        Map<UUID, RoadGraphNodeRecord> nodes = new LinkedHashMap<>();
        for (RoadGraphNodeRecord node : repository.nodes()) {
            if (node != null && normalizeDimension(node.dimensionId()).equals(dimensionId)) {
                nodes.put(node.nodeId(), node);
            }
        }
        Map<UUID, List<EdgeStep>> adjacency = new HashMap<>();
        Map<String, EdgeStep> edgeByDirection = new HashMap<>();
        Map<UUID, RoadGraphEdgeRecord> edges = new LinkedHashMap<>();
        for (RoadGraphEdgeRecord edge : repository.edgesForDimension(dimensionId)) {
            if (edge == null || !edge.built() || !nodes.containsKey(edge.fromNodeId()) || !nodes.containsKey(edge.toNodeId())) {
                continue;
            }
            edges.put(edge.edgeId(), edge);
            EdgeStep forward = new EdgeStep(edge, edge.fromNodeId(), edge.toNodeId(), true, length(edge.centerline()));
            EdgeStep backward = new EdgeStep(edge, edge.toNodeId(), edge.fromNodeId(), false, length(edge.centerline()));
            adjacency.computeIfAbsent(edge.fromNodeId(), ignored -> new ArrayList<>()).add(forward);
            adjacency.computeIfAbsent(edge.toNodeId(), ignored -> new ArrayList<>()).add(backward);
            edgeByDirection.put(edgeKey(edge.fromNodeId(), edge.toNodeId()), forward);
            edgeByDirection.put(edgeKey(edge.toNodeId(), edge.fromNodeId()), backward);
        }
        return new Graph(nodes, edges, adjacency, edgeByDirection);
    }

    private static List<EntryCandidate> entryCandidates(Query query, Graph graph, BlockPos currentEndpoint, Direction routeDirection) {
        long radiusSqr = (long) query.entryRadius() * query.entryRadius();
        List<EntryCandidate> out = new ArrayList<>();
        for (RoadGraphEdgeRecord edge : graph.edges().values()) {
            boolean preferred = query.preferredEntry().present() && query.preferredEntry().roadId().equals(edge.edgeId().toString());
            for (UUID nodeId : List.of(edge.fromNodeId(), edge.toNodeId())) {
                RoadGraphNodeRecord node = graph.nodes().get(nodeId);
                if (node == null) {
                    continue;
                }
                long distanceSqr = distSqrXZ(currentEndpoint, node.pos());
                if (!preferred && distanceSqr > radiusSqr) {
                    continue;
                }
                double angle = firstTurnAngle(routeDirection, currentEndpoint, node.pos());
                out.add(new EntryCandidate(edge, nodeId, node.pos(), distanceSqr, angle, preferred));
            }
        }
        return out.stream()
                .sorted(EntryCandidate.ORDER)
                .toList();
    }

    private static List<UUID> destinationNodes(BlockPos destination, int radius, Graph graph) {
        long radiusSqr = (long) radius * radius;
        return graph.nodes().values().stream()
                .filter(node -> distSqrXZ(destination, node.pos()) <= radiusSqr)
                .sorted(Comparator.comparingLong(node -> distSqrXZ(destination, node.pos())))
                .map(RoadGraphNodeRecord::nodeId)
                .toList();
    }

    private static Path shortestPath(UUID start, UUID end, Graph graph) {
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
            for (EdgeStep step : graph.adjacency().getOrDefault(current.nodeId(), List.of())) {
                double next = current.distance() + step.weight();
                if (next < dist.getOrDefault(step.toNodeId(), Double.MAX_VALUE)) {
                    dist.put(step.toNodeId(), next);
                    prev.put(step.toNodeId(), current.nodeId());
                    open.add(new RouteNode(step.toNodeId(), next));
                }
            }
        }
        if (!dist.containsKey(end)) {
            return Path.empty();
        }
        ArrayList<UUID> nodes = new ArrayList<>();
        UUID cursor = end;
        nodes.add(cursor);
        while (!cursor.equals(start)) {
            cursor = prev.get(cursor);
            if (cursor == null) {
                return Path.empty();
            }
            nodes.add(cursor);
        }
        java.util.Collections.reverse(nodes);
        return new Path(List.copyOf(nodes), dist.get(end));
    }

    private static RouteCandidate toRouteCandidate(Query query, Graph graph, EntryCandidate entry, Path path) {
        LinkedHashSet<BlockPos> displayPath = new LinkedHashSet<>();
        ArrayList<RoadPlannerSharedRoadSpan> spans = new ArrayList<>();
        ArrayList<String> roadIds = new ArrayList<>();
        displayPath.add(entry.anchorPos());
        for (int index = 1; index < path.nodeIds().size(); index++) {
            UUID from = path.nodeIds().get(index - 1);
            UUID to = path.nodeIds().get(index);
            EdgeStep step = graph.edgeByDirection().get(edgeKey(from, to));
            if (step == null) {
                continue;
            }
            List<BlockPos> edgePath = step.forward() ? step.edge().displayPath() : reversed(step.edge().displayPath());
            if (edgePath.isEmpty()) {
                edgePath = step.forward() ? step.edge().centerline() : reversed(step.edge().centerline());
            }
            displayPath.addAll(edgePath);
            roadIds.add(step.edge().edgeId().toString());
            spans.add(spanFor(step.edge(), step.forward(), query.scope()));
        }
        RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection(
                entry.edge().edgeId().toString(),
                nearestPathIndex(entry.edge().centerline(), entry.anchorPos()),
                entry.anchorPos(),
                query.scope());
        Result result = new Result(Status.FOUND, selection, new ArrayList<>(displayPath), spans, roadIds, "Auto merge route found");
        return new RouteCandidate(result, entry.preferred(), entry.angle(), entry.distanceSqr(), path.distance(),
                entry.edge().edgeId().toString());
    }

    private static RoadPlannerSharedRoadSpan spanFor(RoadGraphEdgeRecord edge, boolean forward, RoadPlannerMergeScope scope) {
        List<BlockPos> path = edge.centerline();
        int from = forward ? 0 : Math.max(0, path.size() - 1);
        int to = forward ? Math.max(0, path.size() - 1) : 0;
        return new RoadPlannerSharedRoadSpan(edge.edgeId().toString(), from, to,
                path.isEmpty() ? BlockPos.ZERO : path.get(from),
                path.isEmpty() ? BlockPos.ZERO : path.get(to),
                scope,
                RoadPlannerSharedRoadSpan.Role.END_MERGE);
    }

    private static boolean lastSegmentBridgeLike(List<RoadPlannerSegmentType> segments) {
        if (segments == null || segments.isEmpty()) {
            return false;
        }
        RoadPlannerSegmentType type = segments.get(segments.size() - 1);
        return type == RoadPlannerSegmentType.BRIDGE_MINOR
                || type == RoadPlannerSegmentType.BRIDGE_MAJOR
                || type == RoadPlannerSegmentType.WATER_CROSSING;
    }

    private static Direction routeDirection(List<BlockPos> nodes) {
        if (nodes == null || nodes.size() < 2) {
            return Direction.none();
        }
        BlockPos from = nodes.get(nodes.size() - 2);
        BlockPos to = nodes.get(nodes.size() - 1);
        return new Direction(to.getX() - from.getX(), to.getZ() - from.getZ());
    }

    private static double firstTurnAngle(Direction direction, BlockPos from, BlockPos to) {
        Direction next = new Direction(to.getX() - from.getX(), to.getZ() - from.getZ());
        if (!direction.present() || !next.present()) {
            return 0.0D;
        }
        double dot = direction.dx() * next.dx() + direction.dz() * next.dz();
        double mag = Math.hypot(direction.dx(), direction.dz()) * Math.hypot(next.dx(), next.dz());
        double cos = Math.max(-1.0D, Math.min(1.0D, dot / mag));
        return Math.acos(cos);
    }

    private static int nearestPathIndex(List<BlockPos> path, BlockPos target) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 0; index < path.size(); index++) {
            long distance = distSqrXZ(path.get(index), target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
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

    private static List<BlockPos> copyPositions(List<BlockPos> positions) {
        return positions == null ? List.of() : positions.stream()
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }

    private static long distSqrXZ(BlockPos left, BlockPos right) {
        long dx = (long) left.getX() - right.getX();
        long dz = (long) left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static String normalizeDimension(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String edgeKey(UUID from, UUID to) {
        return from + "|" + to;
    }

    private record Graph(Map<UUID, RoadGraphNodeRecord> nodes,
                         Map<UUID, RoadGraphEdgeRecord> edges,
                         Map<UUID, List<EdgeStep>> adjacency,
                         Map<String, EdgeStep> edgeByDirection) {
    }

    private record EdgeStep(RoadGraphEdgeRecord edge, UUID fromNodeId, UUID toNodeId, boolean forward, double weight) {
    }

    private record RouteNode(UUID nodeId, double distance) {
    }

    private record Path(List<UUID> nodeIds, double distance) {
        static Path empty() {
            return new Path(List.of(), 0.0D);
        }
    }

    private record Direction(double dx, double dz) {
        static Direction none() {
            return new Direction(0.0D, 0.0D);
        }

        boolean present() {
            return Math.abs(dx) > 0.0001D || Math.abs(dz) > 0.0001D;
        }
    }

    private record EntryCandidate(RoadGraphEdgeRecord edge,
                                  UUID nodeId,
                                  BlockPos anchorPos,
                                  long distanceSqr,
                                  double angle,
                                  boolean preferred) {
        static final Comparator<EntryCandidate> ORDER = Comparator
                .comparing(EntryCandidate::preferred, Comparator.reverseOrder())
                .thenComparingDouble(EntryCandidate::angle)
                .thenComparingLong(EntryCandidate::distanceSqr)
                .thenComparing(candidate -> candidate.edge().edgeId().toString());
    }

    private record RouteCandidate(Result result,
                                  boolean preferred,
                                  double angle,
                                  long distanceSqr,
                                  double pathLength,
                                  String firstRoadId) {
        static final Comparator<RouteCandidate> ORDER = Comparator
                .comparing(RouteCandidate::preferred, Comparator.reverseOrder())
                .thenComparingDouble(RouteCandidate::angle)
                .thenComparingLong(RouteCandidate::distanceSqr)
                .thenComparingDouble(RouteCandidate::pathLength)
                .thenComparing(RouteCandidate::firstRoadId);
    }

    private RoadPlannerAutoMergeRouteService() {
    }
}
```

- [ ] **Step 4: Run resolver tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPlannerAutoMergeRouteServiceTest"
```

Expected: PASS.

- [ ] **Step 5: Commit resolver**

```powershell
git add src/main/java/com/monpai/sailboatmod/nation/service/RoadPlannerAutoMergeRouteService.java src/test/java/com/monpai/sailboatmod/nation/service/RoadPlannerAutoMergeRouteServiceTest.java
git commit -m "Add road planner auto merge resolver"
```

---

### Task 2: Auto-Merge Network Packets

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerAutoMergeRouteRequestPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerAutoMergeRouteSyncPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`

- [ ] **Step 1: Write failing packet round-trip test**

Add this test method to `RoadPlannerPacketRoundTripTest`.

```java
@Test
void autoMergeRoutePacketsRoundTrip() {
    UUID sessionId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    RoadPlannerAutoMergeRouteRequestPacket request = new RoadPlannerAutoMergeRouteRequestPacket(
            sessionId,
            requestId,
            "minecraft:overworld",
            List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0)),
            List.of(RoadPlannerSegmentType.ROAD),
            new BlockPos(120, 64, 0),
            RoadPlannerMergeScope.OWN_NATION,
            "preferred-road",
            2);
    RoadPlannerSharedRoadSpan span = new RoadPlannerSharedRoadSpan(
            "road-a",
            0,
            1,
            new BlockPos(40, 64, 0),
            new BlockPos(80, 64, 0),
            RoadPlannerMergeScope.OWN_NATION,
            RoadPlannerSharedRoadSpan.Role.END_MERGE);
    RoadPlannerAutoMergeRouteSyncPacket sync = new RoadPlannerAutoMergeRouteSyncPacket(
            sessionId,
            requestId,
            RoadPlannerAutoMergeRouteSyncPacket.Status.FOUND,
            new RoadPlannerMergeSelection("road-a", 0, new BlockPos(40, 64, 0), RoadPlannerMergeScope.OWN_NATION),
            List.of(new BlockPos(40, 64, 0), new BlockPos(80, 64, 0), new BlockPos(120, 64, 0)),
            List.of(span),
            List.of("road-a", "road-b"),
            "Auto merge route found");

    assertEquals(request, roundTrip(request, RoadPlannerAutoMergeRouteRequestPacket::encode, RoadPlannerAutoMergeRouteRequestPacket::decode));
    assertEquals(sync, roundTrip(sync, RoadPlannerAutoMergeRouteSyncPacket::encode, RoadPlannerAutoMergeRouteSyncPacket::decode));
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest.autoMergeRoutePacketsRoundTrip"
```

Expected: compile failure because packet classes do not exist.

- [ ] **Step 3: Implement request packet**

Create `RoadPlannerAutoMergeRouteRequestPacket.java`.

```java
package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.nation.service.RoadPlannerAutoMergeRouteService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerAutoMergeRouteRequestPacket(UUID sessionId,
                                                     UUID requestId,
                                                     String dimensionId,
                                                     List<BlockPos> routeNodes,
                                                     List<RoadPlannerSegmentType> routeSegments,
                                                     BlockPos destinationPos,
                                                     RoadPlannerMergeScope scope,
                                                     String preferredRoadId,
                                                     int preferredPathIndex) {
    private static final int MAX_ROUTE_NODES = 512;
    private static final int MAX_ROUTE_SEGMENTS = 511;

    public RoadPlannerAutoMergeRouteRequestPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        requestId = requestId == null ? UUID.randomUUID() : requestId;
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        routeNodes = routeNodes == null ? List.of() : routeNodes.stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_ROUTE_NODES)
                .map(BlockPos::immutable)
                .toList();
        routeSegments = routeSegments == null ? List.of() : routeSegments.stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_ROUTE_SEGMENTS)
                .toList();
        destinationPos = destinationPos == null ? BlockPos.ZERO : destinationPos.immutable();
        scope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
        preferredRoadId = preferredRoadId == null ? "" : preferredRoadId.trim();
        preferredPathIndex = Math.max(-1, preferredPathIndex);
    }

    public static void encode(RoadPlannerAutoMergeRouteRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeUuid(buffer, packet.requestId());
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId(), 128);
        buffer.writeVarInt(packet.routeNodes().size());
        for (BlockPos node : packet.routeNodes()) {
            buffer.writeBlockPos(node);
        }
        buffer.writeVarInt(packet.routeSegments().size());
        for (RoadPlannerSegmentType segment : packet.routeSegments()) {
            buffer.writeEnum(segment);
        }
        buffer.writeBlockPos(packet.destinationPos());
        buffer.writeEnum(packet.scope());
        RoadPlannerPacketCodec.writeString(buffer, packet.preferredRoadId(), 128);
        buffer.writeVarInt(packet.preferredPathIndex());
    }

    public static RoadPlannerAutoMergeRouteRequestPacket decode(FriendlyByteBuf buffer) {
        UUID sessionId = RoadPlannerPacketCodec.readUuid(buffer);
        UUID requestId = RoadPlannerPacketCodec.readUuid(buffer);
        String dimensionId = buffer.readUtf(128);
        int nodeCount = buffer.readVarInt();
        if (nodeCount < 0 || nodeCount > MAX_ROUTE_NODES) {
            throw new IllegalArgumentException("Auto merge route node count out of bounds: " + nodeCount);
        }
        ArrayList<BlockPos> nodes = new ArrayList<>(nodeCount);
        for (int index = 0; index < nodeCount; index++) {
            nodes.add(buffer.readBlockPos());
        }
        int segmentCount = buffer.readVarInt();
        if (segmentCount < 0 || segmentCount > MAX_ROUTE_SEGMENTS) {
            throw new IllegalArgumentException("Auto merge route segment count out of bounds: " + segmentCount);
        }
        ArrayList<RoadPlannerSegmentType> segments = new ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            segments.add(buffer.readEnum(RoadPlannerSegmentType.class));
        }
        return new RoadPlannerAutoMergeRouteRequestPacket(
                sessionId,
                requestId,
                dimensionId,
                nodes,
                segments,
                buffer.readBlockPos(),
                buffer.readEnum(RoadPlannerMergeScope.class),
                buffer.readUtf(128),
                buffer.readVarInt());
    }

    public static void handle(RoadPlannerAutoMergeRouteRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> handleOnServer(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void handleOnServer(RoadPlannerAutoMergeRouteRequestPacket packet, ServerPlayer sender) {
        if (sender == null || !(sender.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        RoadPlannerAutoMergeRouteService.Result result = RoadPlannerAutoMergeRouteService.resolve(
                new RoadPlannerAutoMergeRouteService.Query(
                        RoadGraphRepository.forLevel(level),
                        packet.dimensionId(),
                        packet.routeNodes(),
                        packet.routeSegments(),
                        packet.destinationPos(),
                        packet.scope(),
                        new RoadPlannerAutoMergeRouteService.PreferredEntry(packet.preferredRoadId(), packet.preferredPathIndex()),
                        RoadPlannerAutoMergeRouteService.DEFAULT_ENTRY_RADIUS,
                        RoadPlannerAutoMergeRouteService.DEFAULT_DESTINATION_RADIUS));
        ModNetwork.CHANNEL.sendTo(RoadPlannerAutoMergeRouteSyncPacket.fromResult(packet.sessionId(), packet.requestId(), result),
                sender.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT);
    }
}
```

- [ ] **Step 4: Implement sync packet**

Create `RoadPlannerAutoMergeRouteSyncPacket.java`.

```java
package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.nation.service.RoadPlannerAutoMergeRouteService;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerAutoMergeRouteSyncPacket(UUID sessionId,
                                                  UUID requestId,
                                                  Status status,
                                                  RoadPlannerMergeSelection mergeSelection,
                                                  List<BlockPos> displayPath,
                                                  List<RoadPlannerSharedRoadSpan> sharedSpans,
                                                  List<String> roadIds,
                                                  String message) {
    private static final int MAX_PATH_POINTS = 4096;
    private static final int MAX_SHARED_SPANS = 64;
    private static final int MAX_ROAD_IDS = 128;

    public enum Status {
        FOUND,
        NOT_FOUND,
        INVALID_REQUEST,
        SCOPE_BLOCKED
    }

    public RoadPlannerAutoMergeRouteSyncPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        requestId = requestId == null ? new UUID(0L, 0L) : requestId;
        status = status == null ? Status.NOT_FOUND : status;
        mergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
        displayPath = displayPath == null ? List.of() : displayPath.stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_PATH_POINTS)
                .map(BlockPos::immutable)
                .toList();
        sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                .filter(java.util.Objects::nonNull)
                .filter(RoadPlannerSharedRoadSpan::present)
                .limit(MAX_SHARED_SPANS)
                .toList();
        roadIds = roadIds == null ? List.of() : roadIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .limit(MAX_ROAD_IDS)
                .toList();
        message = message == null ? "" : message;
    }

    public static RoadPlannerAutoMergeRouteSyncPacket fromResult(UUID sessionId, UUID requestId, RoadPlannerAutoMergeRouteService.Result result) {
        RoadPlannerAutoMergeRouteService.Result safe = result == null ? RoadPlannerAutoMergeRouteService.Result.notFound("No auto merge route found") : result;
        return new RoadPlannerAutoMergeRouteSyncPacket(
                sessionId,
                requestId,
                Status.valueOf(safe.status().name()),
                safe.mergeSelection(),
                safe.displayPath(),
                safe.sharedSpans(),
                safe.roadIds(),
                safe.message());
    }

    public boolean found() {
        return status == Status.FOUND && mergeSelection.present() && !displayPath.isEmpty();
    }

    public static void encode(RoadPlannerAutoMergeRouteSyncPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeUuid(buffer, packet.requestId());
        buffer.writeEnum(packet.status());
        writeMergeSelection(buffer, packet.mergeSelection());
        buffer.writeVarInt(packet.displayPath().size());
        for (BlockPos pos : packet.displayPath()) {
            buffer.writeBlockPos(pos);
        }
        writeSharedSpans(buffer, packet.sharedSpans());
        buffer.writeVarInt(packet.roadIds().size());
        for (String roadId : packet.roadIds()) {
            RoadPlannerPacketCodec.writeString(buffer, roadId, 128);
        }
        RoadPlannerPacketCodec.writeString(buffer, packet.message(), 256);
    }

    public static RoadPlannerAutoMergeRouteSyncPacket decode(FriendlyByteBuf buffer) {
        UUID sessionId = RoadPlannerPacketCodec.readUuid(buffer);
        UUID requestId = RoadPlannerPacketCodec.readUuid(buffer);
        Status status = buffer.readEnum(Status.class);
        RoadPlannerMergeSelection selection = readMergeSelection(buffer);
        int pointCount = buffer.readVarInt();
        if (pointCount < 0 || pointCount > MAX_PATH_POINTS) {
            throw new IllegalArgumentException("Auto merge display path count out of bounds: " + pointCount);
        }
        ArrayList<BlockPos> path = new ArrayList<>(pointCount);
        for (int index = 0; index < pointCount; index++) {
            path.add(buffer.readBlockPos());
        }
        List<RoadPlannerSharedRoadSpan> spans = readSharedSpans(buffer);
        int roadIdCount = buffer.readVarInt();
        if (roadIdCount < 0 || roadIdCount > MAX_ROAD_IDS) {
            throw new IllegalArgumentException("Auto merge road id count out of bounds: " + roadIdCount);
        }
        ArrayList<String> roadIds = new ArrayList<>(roadIdCount);
        for (int index = 0; index < roadIdCount; index++) {
            roadIds.add(buffer.readUtf(128));
        }
        return new RoadPlannerAutoMergeRouteSyncPacket(sessionId, requestId, status, selection, path, spans, roadIds, buffer.readUtf(256));
    }

    public static void handle(RoadPlannerAutoMergeRouteSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RoadPlannerClientHooks.applyAutoMergeRoute(packet)));
        contextSupplier.get().setPacketHandled(true);
    }

    private static void writeMergeSelection(FriendlyByteBuf buffer, RoadPlannerMergeSelection selection) {
        buffer.writeBoolean(selection != null && selection.present());
        if (selection != null && selection.present()) {
            RoadPlannerPacketCodec.writeString(buffer, selection.roadId(), 128);
            buffer.writeVarInt(selection.pathIndex());
            buffer.writeBlockPos(selection.anchorPos());
            buffer.writeEnum(selection.scope());
        }
    }

    private static RoadPlannerMergeSelection readMergeSelection(FriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) {
            return RoadPlannerMergeSelection.none();
        }
        return new RoadPlannerMergeSelection(buffer.readUtf(128), buffer.readVarInt(), buffer.readBlockPos(),
                buffer.readEnum(RoadPlannerMergeScope.class));
    }

    private static void writeSharedSpans(FriendlyByteBuf buffer, List<RoadPlannerSharedRoadSpan> spans) {
        List<RoadPlannerSharedRoadSpan> safe = spans == null ? List.of() : spans.stream()
                .filter(java.util.Objects::nonNull)
                .filter(RoadPlannerSharedRoadSpan::present)
                .limit(MAX_SHARED_SPANS)
                .toList();
        buffer.writeVarInt(safe.size());
        for (RoadPlannerSharedRoadSpan span : safe) {
            RoadPlannerPacketCodec.writeString(buffer, span.roadId(), 128);
            buffer.writeVarInt(span.fromPathIndex());
            buffer.writeVarInt(span.toPathIndex());
            buffer.writeBlockPos(span.fromPos());
            buffer.writeBlockPos(span.toPos());
            buffer.writeEnum(span.scope());
            buffer.writeEnum(span.role());
        }
    }

    private static List<RoadPlannerSharedRoadSpan> readSharedSpans(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_SHARED_SPANS) {
            throw new IllegalArgumentException("Auto merge shared span count out of bounds: " + count);
        }
        ArrayList<RoadPlannerSharedRoadSpan> spans = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            spans.add(new RoadPlannerSharedRoadSpan(
                    buffer.readUtf(128),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBlockPos(),
                    buffer.readBlockPos(),
                    buffer.readEnum(RoadPlannerMergeScope.class),
                    buffer.readEnum(RoadPlannerSharedRoadSpan.Role.class)));
        }
        return List.copyOf(spans);
    }
}
```

- [ ] **Step 5: Register packets and client hook**

Modify `ModNetwork` near existing merge packet registrations:

```java
CHANNEL.registerMessage(
        packetId++,
        RoadPlannerAutoMergeRouteRequestPacket.class,
        RoadPlannerAutoMergeRouteRequestPacket::encode,
        RoadPlannerAutoMergeRouteRequestPacket::decode,
        RoadPlannerAutoMergeRouteRequestPacket::handle,
        Optional.of(NetworkDirection.PLAY_TO_SERVER)
);
CHANNEL.registerMessage(
        packetId++,
        RoadPlannerAutoMergeRouteSyncPacket.class,
        RoadPlannerAutoMergeRouteSyncPacket::encode,
        RoadPlannerAutoMergeRouteSyncPacket::decode,
        RoadPlannerAutoMergeRouteSyncPacket::handle,
        Optional.of(NetworkDirection.PLAY_TO_CLIENT)
);
```

Add imports for both packet classes if the wildcard imports are not already present.

Modify `RoadPlannerClientHooks` imports:

```java
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoMergeRouteSyncPacket;
```

Add this method next to `applyRoadMergeCandidates`:

```java
public static void applyAutoMergeRoute(RoadPlannerAutoMergeRouteSyncPacket packet) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.screen instanceof RoadPlannerScreen screen) {
        screen.applyAutoMergeRoute(packet);
    }
}
```

- [ ] **Step 6: Run packet tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest.autoMergeRoutePacketsRoundTrip"
```

Expected: PASS.

- [ ] **Step 7: Commit packets**

```powershell
git add src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerAutoMergeRouteRequestPacket.java src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerAutoMergeRouteSyncPacket.java src/main/java/com/monpai/sailboatmod/network/ModNetwork.java src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java
git commit -m "Add road planner auto merge packets"
```

---

### Task 3: Client Auto-Merge State and Request Flow

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoMergeState.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`

- [ ] **Step 1: Write failing screen request/state tests**

Add these tests to `RoadPlannerScreenBehaviorTest`.

```java
@Test
void selectingMergeToolRequestsAutoMergeRoute() {
    UUID sessionId = UUID.randomUUID();
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(sessionId, 1280, 720,
            new BlockPos(0, 64, 0), new BlockPos(120, 64, 0));
    RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
    clickToolbarTool(screen, RoadToolType.ROAD);
    screen.mouseClicked(screenXFromWorld(map, 0), screenZFromWorld(map, 0), 0);
    screen.mouseClicked(screenXFromWorld(map, 36), screenZFromWorld(map, 0), 0);

    clickToolbarTool(screen, RoadToolType.MERGE);

    RoadPlannerAutoMergeRouteRequestPacket request = screen.lastAutoMergeRouteRequestForTest();
    assertEquals(sessionId, request.sessionId());
    assertEquals(new BlockPos(120, 64, 0), request.destinationPos());
    assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0)), request.routeNodes());
    assertEquals("", request.preferredRoadId());
}

@Test
void staleAutoMergeResponseIsIgnored() {
    UUID sessionId = UUID.randomUUID();
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(sessionId, 1280, 720,
            new BlockPos(0, 64, 0), new BlockPos(120, 64, 0));
    RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
    clickToolbarTool(screen, RoadToolType.ROAD);
    screen.mouseClicked(screenXFromWorld(map, 0), screenZFromWorld(map, 0), 0);
    screen.mouseClicked(screenXFromWorld(map, 36), screenZFromWorld(map, 0), 0);
    clickToolbarTool(screen, RoadToolType.MERGE);
    UUID liveRequestId = screen.lastAutoMergeRouteRequestForTest().requestId();

    screen.applyAutoMergeRoute(new RoadPlannerAutoMergeRouteSyncPacket(
            sessionId,
            UUID.randomUUID(),
            RoadPlannerAutoMergeRouteSyncPacket.Status.FOUND,
            new RoadPlannerMergeSelection("road-a", 0, new BlockPos(40, 64, 0), RoadPlannerMergeScope.OWN_NATION),
            List.of(new BlockPos(40, 64, 0), new BlockPos(120, 64, 0)),
            List.of(),
            List.of("road-a"),
            "stale"));

    assertEquals(liveRequestId, screen.lastAutoMergeRouteRequestForTest().requestId());
    assertFalse(screen.autoMergeStateForTest().found());
}

@Test
void autoMergeSuccessSelectsReturnedMergeRoute() {
    UUID sessionId = UUID.randomUUID();
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(sessionId, 1280, 720,
            new BlockPos(0, 64, 0), new BlockPos(120, 64, 0));
    RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
    clickToolbarTool(screen, RoadToolType.ROAD);
    screen.mouseClicked(screenXFromWorld(map, 0), screenZFromWorld(map, 0), 0);
    screen.mouseClicked(screenXFromWorld(map, 36), screenZFromWorld(map, 0), 0);
    clickToolbarTool(screen, RoadToolType.MERGE);
    UUID requestId = screen.lastAutoMergeRouteRequestForTest().requestId();

    screen.applyAutoMergeRoute(new RoadPlannerAutoMergeRouteSyncPacket(
            sessionId,
            requestId,
            RoadPlannerAutoMergeRouteSyncPacket.Status.FOUND,
            new RoadPlannerMergeSelection("road-a", 0, new BlockPos(40, 64, 0), RoadPlannerMergeScope.OWN_NATION),
            List.of(new BlockPos(40, 64, 0), new BlockPos(80, 64, 0), new BlockPos(120, 64, 0)),
            List.of(new RoadPlannerSharedRoadSpan("road-a", 0, 2,
                    new BlockPos(40, 64, 0), new BlockPos(120, 64, 0),
                    RoadPlannerMergeScope.OWN_NATION, RoadPlannerSharedRoadSpan.Role.END_MERGE)),
            List.of("road-a"),
            "found"));

    assertTrue(screen.autoMergeStateForTest().found());
    assertEquals(new RoadPlannerMergeSelection("road-a", 0, new BlockPos(40, 64, 0), RoadPlannerMergeScope.OWN_NATION),
            screen.selectedMergeSelectionForTest());
}
```

Add imports:

```java
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoMergeRouteRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoMergeRouteSyncPacket;
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.selectingMergeToolRequestsAutoMergeRoute" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.staleAutoMergeResponseIsIgnored" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.autoMergeSuccessSelectsReturnedMergeRoute"
```

Expected: compile failure because `RoadPlannerAutoMergeState` and screen methods do not exist.

- [ ] **Step 3: Add `RoadPlannerAutoMergeState`**

Create `RoadPlannerAutoMergeState.java`.

```java
package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoMergeRouteSyncPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

public record RoadPlannerAutoMergeState(UUID requestId,
                                        Source source,
                                        Status status,
                                        RoadPlannerMergeSelection selection,
                                        List<BlockPos> displayPath,
                                        List<RoadPlannerSharedRoadSpan> sharedSpans,
                                        List<String> roadIds,
                                        String message) {
    public enum Source {
        NONE,
        AUTOMATIC,
        MANUAL_OVERRIDE
    }

    public enum Status {
        IDLE,
        PENDING,
        FOUND,
        FAILED,
        MANUAL_FALLBACK
    }

    public RoadPlannerAutoMergeState {
        requestId = requestId == null ? new UUID(0L, 0L) : requestId;
        source = source == null ? Source.NONE : source;
        status = status == null ? Status.IDLE : status;
        selection = selection == null ? RoadPlannerMergeSelection.none() : selection;
        displayPath = copyPositions(displayPath);
        sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                .filter(java.util.Objects::nonNull)
                .filter(RoadPlannerSharedRoadSpan::present)
                .toList();
        roadIds = roadIds == null ? List.of() : List.copyOf(roadIds);
        message = message == null ? "" : message;
    }

    public static RoadPlannerAutoMergeState idle() {
        return new RoadPlannerAutoMergeState(new UUID(0L, 0L), Source.NONE, Status.IDLE,
                RoadPlannerMergeSelection.none(), List.of(), List.of(), List.of(), "");
    }

    public static RoadPlannerAutoMergeState pending(UUID requestId, Source source) {
        return new RoadPlannerAutoMergeState(requestId, source, Status.PENDING,
                RoadPlannerMergeSelection.none(), List.of(), List.of(), List.of(), "");
    }

    public static RoadPlannerAutoMergeState fromSync(RoadPlannerAutoMergeRouteSyncPacket packet, Source source) {
        if (packet != null && packet.found()) {
            return new RoadPlannerAutoMergeState(packet.requestId(), source, Status.FOUND,
                    packet.mergeSelection(), packet.displayPath(), packet.sharedSpans(), packet.roadIds(), packet.message());
        }
        return new RoadPlannerAutoMergeState(packet == null ? null : packet.requestId(), source, Status.FAILED,
                RoadPlannerMergeSelection.none(), List.of(), List.of(), List.of(), packet == null ? "" : packet.message());
    }

    public RoadPlannerAutoMergeState manualFallback() {
        return new RoadPlannerAutoMergeState(requestId, source, Status.MANUAL_FALLBACK,
                RoadPlannerMergeSelection.none(), List.of(), List.of(), List.of(), message);
    }

    public boolean found() {
        return status == Status.FOUND && selection.present() && !displayPath.isEmpty();
    }

    public boolean pending() {
        return status == Status.PENDING;
    }

    public boolean failed() {
        return status == Status.FAILED;
    }

    public boolean manualFallbackEnabled() {
        return status == Status.MANUAL_FALLBACK;
    }

    private static List<BlockPos> copyPositions(List<BlockPos> positions) {
        return positions == null ? List.of() : positions.stream()
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }
}
```

- [ ] **Step 4: Wire screen request and response state**

Modify `RoadPlannerScreen`:

Add imports:

```java
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoMergeRouteRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoMergeRouteSyncPacket;
```

Add fields near merge state:

```java
private RoadPlannerAutoMergeRouteRequestPacket lastAutoMergeRouteRequest;
private RoadPlannerAutoMergeState autoMergeState = RoadPlannerAutoMergeState.idle();
```

Add test accessors near existing `lastRoadOverlayRequestForTest()`:

```java
public RoadPlannerAutoMergeRouteRequestPacket lastAutoMergeRouteRequestForTest() {
    return lastAutoMergeRouteRequest;
}

public RoadPlannerAutoMergeState autoMergeStateForTest() {
    return autoMergeState;
}
```

Add request method:

```java
private void requestAutoMergeRoute(RoadPlannerAutoMergeState.Source source, String preferredRoadId, int preferredPathIndex) {
    if (!mergeScope.enabled() || destinationTownPos == null || linePlan.nodeCount() == 0) {
        autoMergeState = RoadPlannerAutoMergeState.idle();
        return;
    }
    if (isBridgeLikeSegment(lastSegmentType())) {
        autoMergeState = RoadPlannerAutoMergeState.idle();
        statusLine = "桥梁段禁止并入现有道路";
        return;
    }
    RoadPlannerAutoMergeRouteRequestPacket request = new RoadPlannerAutoMergeRouteRequestPacket(
            state.sessionId(),
            UUID.randomUUID(),
            minecraft != null && minecraft.level != null ? minecraft.level.dimension().location().toString() : "minecraft:overworld",
            linePlan.nodes(),
            linePlan.segments(),
            destinationTownPos,
            mergeScope,
            preferredRoadId,
            preferredPathIndex);
    lastAutoMergeRouteRequest = request;
    autoMergeState = RoadPlannerAutoMergeState.pending(request.requestId(), source);
    if (!testMode && minecraft != null && minecraft.getConnection() != null) {
        ModNetwork.CHANNEL.sendToServer(request);
    }
}
```

Add response method:

```java
public void applyAutoMergeRoute(RoadPlannerAutoMergeRouteSyncPacket packet) {
    if (packet == null || !state.sessionId().equals(packet.sessionId()) || lastAutoMergeRouteRequest == null
            || !lastAutoMergeRouteRequest.requestId().equals(packet.requestId())) {
        return;
    }
    RoadPlannerAutoMergeState.Source source = autoMergeState.source() == RoadPlannerAutoMergeState.Source.MANUAL_OVERRIDE
            ? RoadPlannerAutoMergeState.Source.MANUAL_OVERRIDE
            : RoadPlannerAutoMergeState.Source.AUTOMATIC;
    autoMergeState = RoadPlannerAutoMergeState.fromSync(packet, source);
    if (autoMergeState.found()) {
        OpenRoadMergeCandidatesPacket.Entry candidate = new OpenRoadMergeCandidatesPacket.Entry(
                packet.mergeSelection().roadId(),
                packet.mergeSelection().anchorPos(),
                packet.mergeSelection().pathIndex(),
                (int) Math.round(Math.sqrt(horizontalDistanceSqr(lastNode(), packet.mergeSelection().anchorPos()))),
                "Auto",
                "Destination",
                "",
                RoadPlannerMergeRelationship.OWN);
        mergeCandidates = List.of(candidate);
        selectedMergeCandidateIndex = 0;
        selectedMergeOverlay = roadOverlays.stream()
                .filter(overlay -> overlay != null && overlay.roadId().equals(packet.mergeSelection().roadId()))
                .findFirst()
                .orElse(null);
        statusLine = packet.message().isBlank() ? "已自动选择可达并入道路" : packet.message();
    } else {
        clearMergeCandidateState();
        statusLine = packet.message().isBlank() ? "未找到可直达目的地的现有道路" : packet.message();
    }
}
```

Trigger automatic request when selecting merge tool. In the existing click/toolbar handler where `state.activeTool()` changes to `RoadToolType.MERGE`, call:

```java
requestAutoMergeRoute(RoadPlannerAutoMergeState.Source.AUTOMATIC, "", -1);
```

Reset auto state inside `clearMergeCandidateState()`:

```java
autoMergeState = RoadPlannerAutoMergeState.idle();
lastAutoMergeRouteRequest = null;
```

When clearing as part of response handling, avoid calling `clearMergeCandidateState()` after setting failed state. Use direct candidate clearing there.

- [ ] **Step 5: Run screen request/state tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.selectingMergeToolRequestsAutoMergeRoute" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.staleAutoMergeResponseIsIgnored" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.autoMergeSuccessSelectsReturnedMergeRoute"
```

Expected: PASS.

- [ ] **Step 6: Commit client state**

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoMergeState.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java
git commit -m "Wire road planner auto merge state"
```

---

### Task 4: Auto-Merge Confirmation, Manual Override, and Fallback

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`

- [ ] **Step 1: Write failing behavior tests**

Add these tests to `RoadPlannerScreenBehaviorTest`.

```java
@Test
void confirmingAutoMergeBuildsConnectorOnlyAndKeepsReusePathLogical() throws Exception {
    UUID sessionId = UUID.randomUUID();
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(sessionId, 1280, 720,
            new BlockPos(0, 64, 0), new BlockPos(120, 64, 0));
    RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
    clickToolbarTool(screen, RoadToolType.ROAD);
    screen.mouseClicked(screenXFromWorld(map, 0), screenZFromWorld(map, 0), 0);
    screen.mouseClicked(screenXFromWorld(map, 36), screenZFromWorld(map, 0), 0);
    clickToolbarTool(screen, RoadToolType.MERGE);
    UUID requestId = screen.lastAutoMergeRouteRequestForTest().requestId();
    RoadPlannerSharedRoadSpan span = new RoadPlannerSharedRoadSpan("road-a", 0, 2,
            new BlockPos(40, 64, 0), new BlockPos(120, 64, 0),
            RoadPlannerMergeScope.OWN_NATION, RoadPlannerSharedRoadSpan.Role.END_MERGE);
    screen.applyAutoMergeRoute(new RoadPlannerAutoMergeRouteSyncPacket(
            sessionId,
            requestId,
            RoadPlannerAutoMergeRouteSyncPacket.Status.FOUND,
            new RoadPlannerMergeSelection("road-a", 0, new BlockPos(40, 64, 0), RoadPlannerMergeScope.OWN_NATION),
            List.of(new BlockPos(40, 64, 0), new BlockPos(80, 64, 0), new BlockPos(120, 64, 0)),
            List.of(span),
            List.of("road-a"),
            "found"));

    clickToolbarAction(screen, RoadPlannerTopToolbar.Group.ROUTE, RoadPlannerTopToolbar.ACTION_NEXT_MERGE);
    invokeSubmitPreview(screen);

    RoadPlannerPreviewRequestPacket packet = RoadPlannerGhostPreviewBridge.lastPreviewRequestForTest();
    assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0), new BlockPos(40, 64, 0)), packet.nodes());
    assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0), new BlockPos(40, 64, 0),
            new BlockPos(80, 64, 0), new BlockPos(120, 64, 0)), packet.logicalNodes());
    assertEquals(List.of(span), packet.sharedSpans());
}

@Test
void failedAutoMergeCanEnterManualFallback() {
    UUID sessionId = UUID.randomUUID();
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(sessionId, 1280, 720,
            new BlockPos(0, 64, 0), new BlockPos(120, 64, 0));
    RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
    clickToolbarTool(screen, RoadToolType.ROAD);
    screen.mouseClicked(screenXFromWorld(map, 0), screenZFromWorld(map, 0), 0);
    screen.mouseClicked(screenXFromWorld(map, 36), screenZFromWorld(map, 0), 0);
    clickToolbarTool(screen, RoadToolType.MERGE);
    UUID requestId = screen.lastAutoMergeRouteRequestForTest().requestId();

    screen.applyAutoMergeRoute(new RoadPlannerAutoMergeRouteSyncPacket(
            sessionId,
            requestId,
            RoadPlannerAutoMergeRouteSyncPacket.Status.NOT_FOUND,
            RoadPlannerMergeSelection.none(),
            List.of(),
            List.of(),
            List.of(),
            "not found"));

    assertTrue(screen.autoMergeStateForTest().failed());
    assertTrue(screen.autoMergeFallbackDialogOpenForTest());
    screen.confirmAutoMergeManualFallbackForTest();
    assertTrue(screen.autoMergeStateForTest().manualFallbackEnabled());
}

@Test
void clickingRoadWhileMergeToolActiveRequestsManualOverrideAutoRoute() {
    UUID sessionId = UUID.randomUUID();
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(sessionId, 1280, 720,
            new BlockPos(0, 64, 0), new BlockPos(120, 64, 0));
    RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
    clickToolbarTool(screen, RoadToolType.ROAD);
    screen.mouseClicked(screenXFromWorld(map, 0), screenZFromWorld(map, 0), 0);
    screen.mouseClicked(screenXFromWorld(map, 36), screenZFromWorld(map, 0), 0);
    screen.applyRoadOverlays(sessionId, List.of(roadOverlay("road-preferred", RoadPlannerMergeRelationship.OWN,
            new BlockPos(40, 64, 0), new BlockPos(120, 64, 0))));

    clickToolbarTool(screen, RoadToolType.MERGE);
    screen.mouseClicked(screenXFromWorld(map, 40), screenZFromWorld(map, 0), 0);

    assertEquals("road-preferred", screen.lastAutoMergeRouteRequestForTest().preferredRoadId());
    assertEquals(RoadPlannerAutoMergeState.Source.MANUAL_OVERRIDE, screen.autoMergeStateForTest().source());
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.confirmingAutoMergeBuildsConnectorOnlyAndKeepsReusePathLogical" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.failedAutoMergeCanEnterManualFallback" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.clickingRoadWhileMergeToolActiveRequestsManualOverrideAutoRoute"
```

Expected: compile failure for missing fallback test methods, then behavior failure until implementation is done.

- [ ] **Step 3: Implement auto confirm path**

Modify `confirmSelectedMergeAnchor()` at its start:

```java
if (autoMergeState.found()) {
    confirmAutoMergeRoute();
    return;
}
```

Add this method to `RoadPlannerScreen`:

```java
private void confirmAutoMergeRoute() {
    RoadPlannerMergeSelection selection = autoMergeState.selection();
    if (!selection.present()) {
        statusLine = "暂无可确认并入道路";
        return;
    }
    BlockPos anchor = selection.anchorPos();
    if (lastNode() != null && !anchor.equals(lastNode())) {
        linePlan.addClickNode(anchor, RoadPlannerSegmentType.ROAD);
    }
    for (BlockPos node : autoMergeState.displayPath()) {
        if (!node.equals(lastNode())) {
            linePlan.addClickNode(node, RoadPlannerSegmentType.ROAD);
        }
    }
    selectedNode = null;
    saveDraft();
    requestRoutePreload(linePlan.nodes());
    statusLine = "已确认自动并入现有道路";
}
```

Modify `sharedSpansForSubmission(RoadPlannerMergeSelection selection, RoadPlannerSharedRoadSpan startSpan)`:

```java
if (autoMergeState.found()) {
    List<RoadPlannerSharedRoadSpan> spans = new ArrayList<>();
    if (startSpan != null && startSpan.present()) {
        spans.add(startSpan);
    }
    spans.addAll(autoMergeState.sharedSpans());
    return List.copyOf(spans);
}
```

Modify `previewSubmission()` build end calculation:

```java
RoadPlannerMergeSelection selection = autoMergeState.found() ? autoMergeState.selection() : selectedMergeSelection();
```

- [ ] **Step 4: Implement manual override request and fallback dialog state**

In `selectMergeAnchorAt`, before creating local `mergeCandidates`, request manual override auto route:

```java
if (!autoMergeState.manualFallbackEnabled()) {
    requestAutoMergeRoute(RoadPlannerAutoMergeState.Source.MANUAL_OVERRIDE, hit.entry().roadId(), hit.segmentIndex());
    statusLine = "正在检查所选道路是否可直达目的地";
    return true;
}
```

Add fallback dialog fields:

```java
private boolean autoMergeFallbackDialogOpen;
```

When `applyAutoMergeRoute` receives failed state:

```java
autoMergeFallbackDialogOpen = true;
```

Render a simple confirmation in `render` after context menu rendering:

```java
private void renderAutoMergeFallbackDialog(GuiGraphics graphics, int mouseX, int mouseY) {
    if (!autoMergeFallbackDialogOpen) {
        return;
    }
    int w = 260;
    int h = 92;
    int x = (width - w) / 2;
    int y = (height - h) / 2;
    graphics.fill(x, y, x + w, y + h, 0xEE101418);
    graphics.fill(x, y, x + w, y + 1, RoadPlannerMapTheme.FLOATING_PANEL_BORDER);
    graphics.drawString(font, "没有可直达目的地的现有道路", x + 12, y + 14, RoadPlannerMapTheme.TEXT, false);
    graphics.drawString(font, "是否继续使用手动多次并入？", x + 12, y + 30, RoadPlannerMapTheme.MUTED_TEXT, false);
    graphics.fill(x + 12, y + 58, x + 118, y + 78, 0xFF2D6CDF);
    graphics.drawCenteredString(font, "继续手动", x + 65, y + 64, 0xFFFFFFFF);
    graphics.fill(x + 142, y + 58, x + 248, y + 78, 0xFF333941);
    graphics.drawCenteredString(font, "取消", x + 195, y + 64, 0xFFFFFFFF);
}
```

Call it from `render`:

```java
renderAutoMergeFallbackDialog(graphics, mouseX, mouseY);
```

Handle click before map handling:

```java
private boolean clickAutoMergeFallbackDialog(double mouseX, double mouseY) {
    if (!autoMergeFallbackDialogOpen) {
        return false;
    }
    int w = 260;
    int h = 92;
    int x = (width - w) / 2;
    int y = (height - h) / 2;
    if (mouseX >= x + 12 && mouseX <= x + 118 && mouseY >= y + 58 && mouseY <= y + 78) {
        confirmAutoMergeManualFallback();
        return true;
    }
    if (mouseX >= x + 142 && mouseX <= x + 248 && mouseY >= y + 58 && mouseY <= y + 78) {
        autoMergeFallbackDialogOpen = false;
        autoMergeState = RoadPlannerAutoMergeState.idle();
        return true;
    }
    return true;
}
```

Add test methods:

```java
public boolean autoMergeFallbackDialogOpenForTest() {
    return autoMergeFallbackDialogOpen;
}

public void confirmAutoMergeManualFallbackForTest() {
    confirmAutoMergeManualFallback();
}

private void confirmAutoMergeManualFallback() {
    autoMergeFallbackDialogOpen = false;
    autoMergeState = autoMergeState.manualFallback();
    requestMergeCandidates(lastNode(), lastSegmentType());
    statusLine = "已切换到手动并入";
}
```

- [ ] **Step 5: Run behavior tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.confirmingAutoMergeBuildsConnectorOnlyAndKeepsReusePathLogical" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.failedAutoMergeCanEnterManualFallback" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.clickingRoadWhileMergeToolActiveRequestsManualOverrideAutoRoute"
```

Expected: PASS.

- [ ] **Step 6: Commit confirmation/fallback**

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java
git commit -m "Add auto merge confirmation fallback"
```

---

### Task 5: RoadWeaver-Style Overlay Rendering Helpers

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerOverlayLod.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerOverlayLineRenderer.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoadOverlayRenderModel.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerOverlayRenderingTest.java`

- [ ] **Step 1: Write failing rendering helper tests**

Create `RoadPlannerOverlayRenderingTest.java`.

```java
package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlaySyncPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerOverlayRenderingTest {
    @Test
    void lodKeepsEndpointsAndDropsCloseInteriorPoints() {
        List<BlockPos> simplified = RoadPlannerOverlayLod.simplify(
                List.of(point(0), point(2), point(4), point(20)), 8);

        assertEquals(List.of(point(0), point(20)), simplified);
    }

    @Test
    void renderModelMarksOnlySelectedReuseRoadAsSelected() {
        RoadPlannerRoadOverlaySyncPacket.Entry selected = overlay("road-a", point(0), point(40), point(80));
        RoadPlannerRoadOverlaySyncPacket.Entry normal = overlay("road-b", point(0), point(0, 40));

        List<RoadPlannerRoadOverlayRenderModel.RoadLayer> layers = RoadPlannerRoadOverlayRenderModel.layers(
                List.of(selected, normal),
                RoadPlannerMergeSelection.none(),
                Set.of("road-a"),
                null,
                1);

        RoadPlannerRoadOverlayRenderModel.RoadLayer selectedLayer = layers.stream()
                .filter(layer -> layer.roadId().equals("road-a"))
                .findFirst()
                .orElseThrow();
        RoadPlannerRoadOverlayRenderModel.RoadLayer normalLayer = layers.stream()
                .filter(layer -> layer.roadId().equals("road-b"))
                .findFirst()
                .orElseThrow();
        assertEquals(RoadPlannerRoadOverlayRenderModel.Kind.SELECTED_REUSE, selectedLayer.kind());
        assertEquals(RoadPlannerRoadOverlayRenderModel.Kind.BASE, normalLayer.kind());
    }

    @Test
    void keyNodesDoNotIncludeEveryRoadNodeAtNormalZoom() {
        RoadPlannerRoadOverlaySyncPacket.Entry road = overlay("road-a", point(0), point(40), point(80));

        List<BlockPos> nodes = RoadPlannerRoadOverlayRenderModel.keyNodes(
                road,
                RoadPlannerMergeSelection.none(),
                Set.of(),
                null,
                false);

        assertTrue(nodes.isEmpty());
    }

    @Test
    void selectedAnchorIsAKeyNode() {
        RoadPlannerRoadOverlaySyncPacket.Entry road = overlay("road-a", point(0), point(40), point(80));
        RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection("road-a", 1, point(40), RoadPlannerMergeScope.OWN_NATION);

        List<BlockPos> nodes = RoadPlannerRoadOverlayRenderModel.keyNodes(road, selection, Set.of(), null, false);

        assertEquals(List.of(point(40)), nodes);
    }

    private static RoadPlannerRoadOverlaySyncPacket.Entry overlay(String roadId, BlockPos... path) {
        return new RoadPlannerRoadOverlaySyncPacket.Entry(roadId, RoadPlannerMergeRelationship.OWN, List.of(path));
    }

    private static BlockPos point(int x) {
        return new BlockPos(x, 64, 0);
    }

    private static BlockPos point(int x, int z) {
        return new BlockPos(x, 64, z);
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerOverlayRenderingTest"
```

Expected: compile failure because rendering helper classes do not exist.

- [ ] **Step 3: Implement LOD helper**

Create `RoadPlannerOverlayLod.java`.

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlannerOverlayLod {
    public static List<BlockPos> simplify(List<BlockPos> path, int lodStepBlocks) {
        if (path == null || path.size() <= 2) {
            return path == null ? List.of() : List.copyOf(path);
        }
        int step = Math.max(1, lodStepBlocks);
        ArrayList<BlockPos> out = new ArrayList<>();
        BlockPos keep = path.get(0);
        out.add(keep);
        for (int index = 1; index < path.size() - 1; index++) {
            BlockPos current = path.get(index);
            if (Math.abs(current.getX() - keep.getX()) + Math.abs(current.getZ() - keep.getZ()) >= step) {
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

    public static int stepForPixelsPerBlock(double pixelsPerBlock) {
        if (pixelsPerBlock >= 0.5D) {
            return 1;
        }
        if (pixelsPerBlock >= 0.25D) {
            return 4;
        }
        if (pixelsPerBlock >= 0.125D) {
            return 16;
        }
        return 32;
    }

    private RoadPlannerOverlayLod() {
    }
}
```

- [ ] **Step 4: Implement render model**

Create `RoadPlannerRoadOverlayRenderModel.java`.

```java
package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlaySyncPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RoadPlannerRoadOverlayRenderModel {
    public enum Kind {
        BASE,
        HOVERED,
        SELECTED_REUSE
    }

    public record RoadLayer(String roadId,
                            Kind kind,
                            RoadPlannerMergeRelationship relationship,
                            List<BlockPos> path,
                            int thickness,
                            boolean dashed) {
        public RoadLayer {
            roadId = roadId == null ? "" : roadId;
            kind = kind == null ? Kind.BASE : kind;
            relationship = relationship == null ? RoadPlannerMergeRelationship.OWN : relationship;
            path = path == null ? List.of() : path.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(BlockPos::immutable)
                    .toList();
            thickness = Math.max(1, thickness);
        }
    }

    public static List<RoadLayer> layers(List<RoadPlannerRoadOverlaySyncPacket.Entry> overlays,
                                         RoadPlannerMergeSelection selectedMerge,
                                         Set<String> selectedReuseRoadIds,
                                         String hoveredRoadId,
                                         int lodStepBlocks) {
        if (overlays == null || overlays.isEmpty()) {
            return List.of();
        }
        ArrayList<RoadLayer> layers = new ArrayList<>();
        for (RoadPlannerRoadOverlaySyncPacket.Entry overlay : overlays) {
            if (overlay == null || overlay.displayPath().size() < 2) {
                continue;
            }
            String roadId = overlay.roadId();
            Kind kind = Kind.BASE;
            int thickness = 3;
            boolean dashed = false;
            if (selectedReuseRoadIds != null && selectedReuseRoadIds.contains(roadId)) {
                kind = Kind.SELECTED_REUSE;
                thickness = 6;
            } else if (hoveredRoadId != null && hoveredRoadId.equals(roadId)) {
                kind = Kind.HOVERED;
                thickness = 5;
            }
            layers.add(new RoadLayer(roadId, kind, overlay.relationship(),
                    RoadPlannerOverlayLod.simplify(overlay.displayPath(), lodStepBlocks), thickness, dashed));
        }
        return List.copyOf(layers);
    }

    public static List<BlockPos> keyNodes(RoadPlannerRoadOverlaySyncPacket.Entry overlay,
                                          RoadPlannerMergeSelection selectedMerge,
                                          Set<String> selectedReuseRoadIds,
                                          BlockPos hoveredNode,
                                          boolean showAllNodes) {
        if (overlay == null) {
            return List.of();
        }
        ArrayList<BlockPos> nodes = new ArrayList<>();
        if (showAllNodes) {
            nodes.addAll(overlay.displayPath());
        }
        if (selectedMerge != null && selectedMerge.present() && overlay.roadId().equals(selectedMerge.roadId())) {
            nodes.add(selectedMerge.anchorPos());
        }
        if (hoveredNode != null) {
            nodes.add(hoveredNode);
        }
        return nodes.stream().distinct().map(BlockPos::immutable).toList();
    }

    private RoadPlannerRoadOverlayRenderModel() {
    }
}
```

- [ ] **Step 5: Implement line renderer**

Create `RoadPlannerOverlayLineRenderer.java`.

```java
package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.client.gui.GuiGraphics;

public final class RoadPlannerOverlayLineRenderer {
    public static void drawThickLine(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                     int color, int thickness,
                                     int left, int top, int right, int bottom) {
        thickness = Math.max(1, thickness);
        int half = thickness / 2;
        for (int ox = -half; ox <= half; ox++) {
            for (int oy = -half; oy <= half; oy++) {
                drawLine(graphics, x1 + ox, y1 + oy, x2 + ox, y2 + oy, color, left, top, right, bottom);
            }
        }
    }

    public static void drawThickDashedLine(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                           int color, int thickness, int dash, int gap,
                                           int left, int top, int right, int bottom) {
        thickness = Math.max(1, thickness);
        int half = thickness / 2;
        for (int ox = -half; ox <= half; ox++) {
            for (int oy = -half; oy <= half; oy++) {
                drawDashedLine(graphics, x1 + ox, y1 + oy, x2 + ox, y2 + oy, color, dash, gap, left, top, right, bottom);
            }
        }
    }

    private static void drawLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color,
                                 int left, int top, int right, int bottom) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) {
            drawPoint(graphics, x1, y1, color, left, top, right, bottom);
            return;
        }
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            drawPoint(graphics, (int) Math.round(x1 + dx * t), (int) Math.round(y1 + dy * t), color, left, top, right, bottom);
        }
    }

    private static void drawDashedLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color,
                                       int dash, int gap, int left, int top, int right, int bottom) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        int pattern = Math.max(1, dash) + Math.max(1, gap);
        for (int step = 0; step <= steps; step++) {
            if (step % pattern >= dash) {
                continue;
            }
            double t = steps == 0 ? 0.0D : step / (double) steps;
            drawPoint(graphics, (int) Math.round(x1 + dx * t), (int) Math.round(y1 + dy * t), color, left, top, right, bottom);
        }
    }

    private static void drawPoint(GuiGraphics graphics, int x, int y, int color, int left, int top, int right, int bottom) {
        if (x >= left && x <= right && y >= top && y <= bottom) {
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private RoadPlannerOverlayLineRenderer() {
    }
}
```

- [ ] **Step 6: Run rendering helper tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerOverlayRenderingTest"
```

Expected: PASS.

- [ ] **Step 7: Commit rendering helpers**

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerOverlayLod.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerOverlayLineRenderer.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRoadOverlayRenderModel.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerOverlayRenderingTest.java
git commit -m "Add RoadWeaver style overlay helpers"
```

---

### Task 6: Integrate Overlay Renderer Into Planner Screen

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`

- [ ] **Step 1: Write failing render-state behavior test**

Add this test to `RoadPlannerScreenBehaviorTest`.

```java
@Test
void autoMergeRenderStateHighlightsOnlyExistingReuseRoad() {
    UUID sessionId = UUID.randomUUID();
    RoadPlannerScreen screen = RoadPlannerScreen.forTest(sessionId, 1280, 720,
            new BlockPos(0, 64, 0), new BlockPos(120, 64, 0));
    RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
    screen.applyRoadOverlays(sessionId, List.of(
            roadOverlay("road-a", RoadPlannerMergeRelationship.OWN, new BlockPos(40, 64, 0), new BlockPos(120, 64, 0)),
            roadOverlay("road-b", RoadPlannerMergeRelationship.OWN, new BlockPos(0, 64, 40), new BlockPos(120, 64, 40))));
    clickToolbarTool(screen, RoadToolType.ROAD);
    screen.mouseClicked(screenXFromWorld(map, 0), screenZFromWorld(map, 0), 0);
    screen.mouseClicked(screenXFromWorld(map, 36), screenZFromWorld(map, 0), 0);
    clickToolbarTool(screen, RoadToolType.MERGE);
    UUID requestId = screen.lastAutoMergeRouteRequestForTest().requestId();
    screen.applyAutoMergeRoute(new RoadPlannerAutoMergeRouteSyncPacket(
            sessionId,
            requestId,
            RoadPlannerAutoMergeRouteSyncPacket.Status.FOUND,
            new RoadPlannerMergeSelection("road-a", 0, new BlockPos(40, 64, 0), RoadPlannerMergeScope.OWN_NATION),
            List.of(new BlockPos(40, 64, 0), new BlockPos(120, 64, 0)),
            List.of(new RoadPlannerSharedRoadSpan("road-a", 0, 1,
                    new BlockPos(40, 64, 0), new BlockPos(120, 64, 0),
                    RoadPlannerMergeScope.OWN_NATION, RoadPlannerSharedRoadSpan.Role.END_MERGE)),
            List.of("road-a"),
            "found"));

    List<RoadPlannerScreen.RoadOverlayRenderStateForTest> states = screen.roadOverlayRenderStateForTest();

    assertTrue(states.stream().anyMatch(state -> state.roadId().equals("road-a") && state.selectedSharedSpanNodeCount() > 0));
    assertTrue(states.stream().anyMatch(state -> state.roadId().equals("road-b") && state.selectedSharedSpanNodeCount() == 0));
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.autoMergeRenderStateHighlightsOnlyExistingReuseRoad"
```

Expected: FAIL because render state does not use auto merge shared spans yet.

- [ ] **Step 3: Use render model and line renderer in `RoadPlannerScreen`**

Modify `renderSyncedRoadOverlays`:

```java
RoadPlannerMergeSelection selectedMerge = autoMergeState.found() ? autoMergeState.selection() : selectedMergeSelection();
List<RoadPlannerSharedRoadSpan> activeSharedSpans = sharedSpansForSubmission(selectedMerge);
java.util.Set<String> selectedReuseRoadIds = autoMergeState.found()
        ? new java.util.HashSet<>(autoMergeState.roadIds())
        : activeSharedSpans.stream().map(RoadPlannerSharedRoadSpan::roadId).collect(java.util.stream.Collectors.toSet());
String hoveredRoadId = hoveredRoad != null && hoveredRoad.hit() ? hoveredRoad.entry().roadId() : "";
int lodStep = RoadPlannerOverlayLod.stepForPixelsPerBlock(map.width() / Math.max(1.0D, mapView.visibleBlockWidthForTest(map)));
for (RoadPlannerRoadOverlayRenderModel.RoadLayer layer : RoadPlannerRoadOverlayRenderModel.layers(
        roadOverlays, selectedMerge, selectedReuseRoadIds, hoveredRoadId, lodStep)) {
    drawRoadOverlayPath(graphics, map, layer.path(), roadOverlayColor(layer.relationship()), layer.thickness(), layer.dashed());
}
for (RoadPlannerRoadOverlaySyncPacket.Entry overlay : roadOverlays) {
    if (isSelectedMergeAnchor(overlay, selectedMerge)) {
        drawSelectedMergeAnchor(graphics, map, selectedMerge.anchorPos());
    }
    drawSharedSpansForOverlay(graphics, map, overlay, activeSharedSpans);
    for (BlockPos node : RoadPlannerRoadOverlayRenderModel.keyNodes(overlay, selectedMerge, selectedReuseRoadIds, null, false)) {
        drawRoadOverlayNode(graphics, map, node, roadOverlayColor(overlay.relationship()));
    }
}
```

If `RoadPlannerMapView` does not expose visible block width, add:

```java
public double visibleBlockWidthForTest(RoadPlannerMapLayout.Rect map) {
    return Math.max(1.0D, screenToWorldX(map.right(), map) - screenToWorldX(map.x(), map));
}
```

Replace the old `drawRoadOverlayPath` overload with:

```java
private void drawRoadOverlayPath(GuiGraphics graphics,
                                 RoadPlannerMapLayout.Rect map,
                                 List<BlockPos> path,
                                 int color,
                                 int thickness,
                                 boolean dashed) {
    if (path == null || path.size() < 2) {
        return;
    }
    for (int index = 1; index < path.size(); index++) {
        BlockPos previous = path.get(index - 1);
        BlockPos current = path.get(index);
        int x1 = mapView.worldToScreenX(previous.getX(), map);
        int y1 = mapView.worldToScreenZ(previous.getZ(), map);
        int x2 = mapView.worldToScreenX(current.getX(), map);
        int y2 = mapView.worldToScreenZ(current.getZ(), map);
        if (!lineIntersectsRect(x1, y1, x2, y2, map)) {
            continue;
        }
        if (dashed) {
            RoadPlannerOverlayLineRenderer.drawThickDashedLine(graphics, x1, y1, x2, y2, color, thickness, 8, 6,
                    map.x(), map.y(), map.right(), map.bottom());
        } else {
            RoadPlannerOverlayLineRenderer.drawThickLine(graphics, x1, y1, x2, y2, color, thickness,
                    map.x(), map.y(), map.right(), map.bottom());
        }
    }
}
```

Add single-node helper:

```java
private void drawRoadOverlayNode(GuiGraphics graphics, RoadPlannerMapLayout.Rect map, BlockPos node, int color) {
    int x = mapView.worldToScreenX(node.getX(), map);
    int y = mapView.worldToScreenZ(node.getZ(), map);
    if (!map.contains(x, y)) {
        return;
    }
    graphics.fill(x - 2, y - 2, x + 3, y + 3, color);
    graphics.fill(x - 1, y - 1, x + 2, y + 2, 0xEE101418);
}
```

- [ ] **Step 4: Include auto spans in render-state test accessor**

Modify `roadOverlayRenderStateForTest()`:

```java
RoadPlannerMergeSelection selectedMerge = autoMergeState.found() ? autoMergeState.selection() : selectedMergeSelection();
List<RoadPlannerSharedRoadSpan> activeSharedSpans = sharedSpansForSubmission(selectedMerge);
```

- [ ] **Step 5: Run render-state behavior test**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest.autoMergeRenderStateHighlightsOnlyExistingReuseRoad"
```

Expected: PASS.

- [ ] **Step 6: Commit screen renderer integration**

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMapView.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java
git commit -m "Use layered road overlay rendering"
```

---

### Task 7: Built-Road Refresh Black Tile Guard

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileMergeRules.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileMergeRulesTest.java`

- [ ] **Step 1: Write failing tile safety tests**

Add these tests to `RoadPlannerTileMergeRulesTest`.

```java
@Test
void builtRoadRefreshRejectsFullBlackReplacementTile() {
    int[] pixels = new int[256 * 256];
    java.util.Arrays.fill(pixels, 0xFF000000);

    assertFalse(RoadPlannerTileMergeRules.safeFullTileReplacement(pixels, RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH));
}

@Test
void routePreloadCanStillAcceptFullBlackIfServerExplicitlySendsIt() {
    int[] pixels = new int[256 * 256];
    java.util.Arrays.fill(pixels, 0xFF000000);

    assertTrue(RoadPlannerTileMergeRules.safeFullTileReplacement(pixels, RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD));
}

@Test
void builtRoadRefreshAcceptsTerrainLikeReplacementTile() {
    int[] pixels = new int[256 * 256];
    java.util.Arrays.fill(pixels, 0xFF3F8F37);

    assertTrue(RoadPlannerTileMergeRules.safeFullTileReplacement(pixels, RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH));
}
```

Add import:

```java
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileMergeRulesTest"
```

Expected: compile failure because `safeFullTileReplacement` does not exist.

- [ ] **Step 3: Add full-tile safety check**

Modify `RoadPlannerTileMergeRules`:

```java
public static boolean safeFullTileReplacement(int[] incomingArgb,
                                              com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
    if (purpose != com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH) {
        return true;
    }
    if (incomingArgb == null || incomingArgb.length == 0) {
        return false;
    }
    int known = 0;
    boolean[] mask = knownMask(incomingArgb);
    for (boolean value : mask) {
        if (value) {
            known++;
        }
    }
    int minimumKnown = Math.max(64, incomingArgb.length / 100);
    return known >= minimumKnown;
}
```

- [ ] **Step 4: Apply safety check in tile manager**

Modify `RoadPlannerTileManager.applyTileSync` before loading/creating tile:

```java
if (!RoadPlannerTileMergeRules.safeFullTileReplacement(packet.argbPixels(), packet.purpose())) {
    return 0;
}
```

Keep the existing partial coverage guard. The new full-tile guard prevents an unsafe built-road refresh from replacing a valid tile when the packet has full coverage but all samples are black/loading/unknown.

- [ ] **Step 5: Run tile tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileMergeRulesTest"
```

Expected: PASS.

- [ ] **Step 6: Commit tile guard**

```powershell
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileMergeRules.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileMergeRulesTest.java
git commit -m "Guard road planner tiles from black refreshes"
```

---

### Task 8: Full Verification and Regression Pass

**Files:**
- Modify only files touched by previous tasks if verification finds issues.

- [ ] **Step 1: Run focused test suite**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPlannerAutoMergeRouteServiceTest" --tests "com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerOverlayRenderingTest" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileMergeRulesTest"
```

Expected: PASS.

- [ ] **Step 2: Run compile validation**

Run:

```powershell
.\gradlew.bat compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Inspect git diff for accidental unrelated edits**

Run:

```powershell
git status --short
git diff --stat
```

Expected: only intended source/test files are modified after the last task commit, or the tree is clean except pre-existing unrelated logs/generated files.

- [ ] **Step 4: Manual in-game checks**

Run client:

```powershell
.\gradlew.bat runClient
```

Manual checklist:

- Open road planner between two towns.
- Build or load an existing connected graph route to the destination.
- Switch to merge tool.
- Confirm auto-selected route highlights only the existing-road reuse segment.
- Confirm merge and preview: connector is new-build, existing route is reused.
- Create a case with no complete existing route; confirm fallback dialog appears.
- Choose continue manual merge and verify old multi-node merge workflow still works.
- Build a road and re-open the planner map; route chunks refresh without non-road terrain turning black.

- [ ] **Step 5: Final commit if verification fixes were needed**

If verification required fixes:

```powershell
git add src/main/java src/test/java
git commit -m "Fix road planner auto merge verification issues"
```

If no fixes were needed, do not create an empty commit.

---

## Self-Review Checklist

- Spec coverage:
  - Full existing-road reachability: Task 1.
  - Direction-first ranking: Task 1.
  - Auto request and manual override: Tasks 2-4.
  - Confirm highlights/reuses existing path only: Tasks 4 and 6.
  - Manual fallback dialog: Task 4.
  - RoadWeaver-style LOD/status rendering: Tasks 5-6.
  - Black tile refresh guard: Task 7.
  - Compile and manual verification: Task 8.
- Placeholder scan:
  - No task uses placeholder markers or unspecified "handle edge cases" language.
  - Each task includes concrete tests, implementation snippets, commands, and expected outcomes.
- Type consistency:
  - `RoadPlannerAutoMergeRouteService.Result` maps to `RoadPlannerAutoMergeRouteSyncPacket`.
  - `RoadPlannerAutoMergeState` stores sync packet fields and is consumed by `RoadPlannerScreen`.
  - Shared spans stay as existing `RoadPlannerSharedRoadSpan`.
