# Road Node Structure Expander Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a unified RoadPlanner structure expander so manual roads, auto-complete roads, preview, and confirmed construction share the same road/bridge generation path.

**Architecture:** Add a focused `com.monpai.sailboatmod.roadplanner.structure` package that normalizes route nodes, builds centerline/span/height profiles, and emits legacy `BuildStep` objects plus preview blocks. Keep the existing construction queue unchanged by turning `RoadPlannerBuildStepCompiler` into an adapter over the new expander. Update preview packet generation to consume the expander result so `RAMP`, `PIER`, and `RAILING` appear in ghost preview.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, JUnit 5, existing `BuildStep`/`BuildPhase`, existing `RoadPlannerBuildSettings`, existing `WeaverSegmentPaver` footprint helper.

---

## File Structure

Create focused structure files:

- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadStructureMode.java` — `PREVIEW`/`BUILD` mode marker.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSpanType.java` — normalized span categories.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadStructureIssue.java` — non-crashing expander diagnostics.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPreviewBlock.java` — preview block plus source phase.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadCenterlinePoint.java` — centerline sample with terrain and target height.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSpan.java` — inclusive centerline span.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadTerrainSampler.java` — testable terrain sampling seam and `ServerLevel` adapter.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadRouteSection.java` — contiguous route section after null/duplicate cleanup.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadRouteSectionNormalizer.java` — canonical node/segment preparation.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeExpansionResult.java` — expander output.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadCenterlineBuilder.java` — block-by-block centerline interpolation.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSpanClassifier.java` — merge segment types into road/bridge spans.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadHeightProfileSmoother.java` — RoadWeaver-style slope clamp for normal roads.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSurfaceStepEmitter.java` — normal road foundation/surface/ramp/headroom steps.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeTemplateProvider.java` — bridge template seam.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java` — programmatic bridge deck/ramp/pier/railing steps.
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpander.java` — public orchestration entrypoint.

Modify existing integration files:

- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompiler.java` — adapter to `RoadNodeStructureExpander`.
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java` — expose preview expansion and use shared build steps.
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacket.java` — use expander preview blocks and remove the old build-step preview filter.

Tests:

- Create: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompilerTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacketTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteExpanderTest.java` — keep auto-complete compatibility assertions aligned with unified build steps.

Do not reset or clean unrelated current edits in RoadPlanner minimap or Town/Nation UI files.

---

### Task 1: Add expander result model and route normalization

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadStructureMode.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSpanType.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadStructureIssue.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPreviewBlock.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadCenterlinePoint.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSpan.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadTerrainSampler.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadRouteSection.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadRouteSectionNormalizer.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeExpansionResult.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpander.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`

- [ ] **Step 1: Write failing normalization tests**

Create `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java` with this initial content:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadNodeStructureExpanderTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void normalizesMissingSegmentsAndSkipsDuplicateSegmentTypes() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(0, 64, 0),
                        new BlockPos(8, 64, 0),
                        new BlockPos(16, 64, 0)
                ),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.PREVIEW
        );

        assertFalse(result.hasErrors());
        assertEquals(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(16, 64, 0)
        ), result.canonicalNodes());
        assertEquals(List.of(
                RoadPlannerSegmentType.ROAD,
                RoadPlannerSegmentType.ROAD
        ), result.canonicalSegmentTypes());
    }

    @Test
    void blockedBridgeMarkerNormalizesToMajorBridge() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.PREVIEW
        );

        assertFalse(result.hasErrors());
        assertEquals(List.of(RoadPlannerSegmentType.BRIDGE_MAJOR), result.canonicalSegmentTypes());
    }

    @Test
    void nullNodeBreaksRouteInsteadOfConnectingAcrossGap() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                Arrays.asList(
                        new BlockPos(0, 64, 0),
                        new BlockPos(4, 64, 0),
                        null,
                        new BlockPos(40, 64, 0),
                        new BlockPos(44, 64, 0)
                ),
                List.of(
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.ROAD
                ),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.PREVIEW
        );

        assertFalse(result.hasErrors());
        assertEquals(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(40, 64, 0),
                new BlockPos(44, 64, 0)
        ), result.canonicalNodes());
        assertEquals(List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD), result.canonicalSegmentTypes());
    }

    @Test
    void singleNodeProducesIssueAndNoSteps() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0)),
                List.of(),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.PREVIEW
        );

        assertTrue(result.hasErrors());
        assertEquals(0, result.buildSteps().size());
        assertEquals(0, result.previewBlocks().size());
    }
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected: FAIL at Java compilation with missing `RoadNodeStructureExpander` or related structure types.

- [ ] **Step 3: Create structure model files**

Create the structure model files with these complete sources.

`RoadStructureMode.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

public enum RoadStructureMode {
    PREVIEW,
    BUILD
}
```

`RoadSpanType.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

public enum RoadSpanType {
    ROAD,
    BRIDGE,
    TUNNEL
}
```

`RoadStructureIssue.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

public record RoadStructureIssue(Severity severity, String message) {
    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public RoadStructureIssue {
        severity = severity == null ? Severity.INFO : severity;
        message = message == null ? "" : message;
    }

    public static RoadStructureIssue error(String message) {
        return new RoadStructureIssue(Severity.ERROR, message);
    }

    public static RoadStructureIssue warning(String message) {
        return new RoadStructureIssue(Severity.WARNING, message);
    }

    public static RoadStructureIssue info(String message) {
        return new RoadStructureIssue(Severity.INFO, message);
    }
}
```

`RoadPreviewBlock.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.road.model.BuildPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public record RoadPreviewBlock(BlockPos pos, BlockState state, BuildPhase phase) {
    public RoadPreviewBlock {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        state = Objects.requireNonNull(state, "state");
        phase = Objects.requireNonNull(phase, "phase");
    }
}
```

`RoadCenterlinePoint.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;

import java.util.Objects;

public record RoadCenterlinePoint(BlockPos pos,
                                  int segmentIndex,
                                  RoadPlannerSegmentType segmentType,
                                  int terrainY,
                                  int targetY,
                                  double distanceFromStart) {
    public RoadCenterlinePoint {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        segmentType = segmentType == null ? RoadPlannerSegmentType.ROAD : segmentType;
        distanceFromStart = Math.max(0.0D, distanceFromStart);
    }

    public RoadCenterlinePoint withTargetY(int newTargetY) {
        return new RoadCenterlinePoint(pos.atY(newTargetY), segmentIndex, segmentType, terrainY, newTargetY, distanceFromStart);
    }
}
```

`RoadSpan.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;

public record RoadSpan(RoadSpanType type,
                       int startIndex,
                       int endIndex,
                       RoadPlannerSegmentType sourceSegmentType) {
    public RoadSpan {
        type = type == null ? RoadSpanType.ROAD : type;
        startIndex = Math.max(0, startIndex);
        endIndex = Math.max(startIndex, endIndex);
        sourceSegmentType = sourceSegmentType == null ? RoadPlannerSegmentType.ROAD : sourceSegmentType;
    }

    public boolean contains(int index) {
        return index >= startIndex && index <= endIndex;
    }
}
```

`RoadTerrainSampler.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

@FunctionalInterface
public interface RoadTerrainSampler {
    int terrainY(int x, int z);

    default int oceanFloorY(int x, int z) {
        return terrainY(x, z) - 1;
    }

    default boolean isWater(int x, int y, int z) {
        return false;
    }

    static RoadTerrainSampler flat(int y) {
        return (x, z) -> y;
    }

    static RoadTerrainSampler fromLevel(ServerLevel level) {
        if (level == null) {
            return null;
        }
        return new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
            }

            @Override
            public int oceanFloorY(int x, int z) {
                return level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return !level.getFluidState(new net.minecraft.core.BlockPos(x, y, z)).isEmpty();
            }
        };
    }
}
```

`RoadRouteSection.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;

import java.util.List;

public record RoadRouteSection(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes) {
    public RoadRouteSection {
        nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
        segmentTypes = segmentTypes == null ? List.of() : List.copyOf(segmentTypes);
        if (nodes.size() >= 2 && segmentTypes.size() != nodes.size() - 1) {
            throw new IllegalArgumentException("segmentTypes must be nodes.size() - 1 for a route section");
        }
    }
}
```

`RoadRouteSectionNormalizer.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadRouteSectionNormalizer {
    private RoadRouteSectionNormalizer() {
    }

    public static List<RoadRouteSection> sections(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes) {
        if (nodes == null || nodes.size() < 2) {
            return List.of();
        }
        List<RoadRouteSection> sections = new ArrayList<>();
        List<BlockPos> sectionNodes = new ArrayList<>();
        List<RoadPlannerSegmentType> sectionTypes = new ArrayList<>();
        for (int index = 0; index < nodes.size() - 1; index++) {
            BlockPos from = nodes.get(index);
            BlockPos to = nodes.get(index + 1);
            if (from == null || to == null) {
                addSection(sections, sectionNodes, sectionTypes);
                sectionNodes = new ArrayList<>();
                sectionTypes = new ArrayList<>();
                continue;
            }
            if (from.equals(to)) {
                continue;
            }
            BlockPos immutableFrom = from.immutable();
            BlockPos immutableTo = to.immutable();
            if (sectionNodes.isEmpty()) {
                sectionNodes.add(immutableFrom);
            } else if (!sectionNodes.get(sectionNodes.size() - 1).equals(immutableFrom)) {
                addSection(sections, sectionNodes, sectionTypes);
                sectionNodes = new ArrayList<>();
                sectionTypes = new ArrayList<>();
                sectionNodes.add(immutableFrom);
            }
            sectionNodes.add(immutableTo);
            sectionTypes.add(segmentTypeAt(segmentTypes, index));
        }
        addSection(sections, sectionNodes, sectionTypes);
        return List.copyOf(sections);
    }

    public static List<BlockPos> flattenNodes(List<RoadRouteSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        List<BlockPos> result = new ArrayList<>();
        for (RoadRouteSection section : sections) {
            for (BlockPos node : section.nodes()) {
                if (result.isEmpty() || !result.get(result.size() - 1).equals(node)) {
                    result.add(node.immutable());
                }
            }
        }
        return List.copyOf(result);
    }

    public static List<RoadPlannerSegmentType> flattenSegmentTypes(List<RoadRouteSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        List<RoadPlannerSegmentType> result = new ArrayList<>();
        for (RoadRouteSection section : sections) {
            result.addAll(section.segmentTypes());
        }
        return List.copyOf(result);
    }

    private static void addSection(List<RoadRouteSection> sections,
                                   List<BlockPos> nodes,
                                   List<RoadPlannerSegmentType> segmentTypes) {
        if (nodes.size() >= 2 && segmentTypes.size() == nodes.size() - 1) {
            sections.add(new RoadRouteSection(List.copyOf(nodes), List.copyOf(segmentTypes)));
        }
    }

    private static RoadPlannerSegmentType segmentTypeAt(List<RoadPlannerSegmentType> segmentTypes, int index) {
        RoadPlannerSegmentType type = RoadPlannerSegmentType.ROAD;
        if (segmentTypes != null && index >= 0 && index < segmentTypes.size() && segmentTypes.get(index) != null) {
            type = segmentTypes.get(index);
        }
        return type == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE ? RoadPlannerSegmentType.BRIDGE_MAJOR : type;
    }
}
```

`RoadNodeExpansionResult.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;

import java.util.List;

public record RoadNodeExpansionResult(List<BlockPos> canonicalNodes,
                                      List<RoadPlannerSegmentType> canonicalSegmentTypes,
                                      List<RoadCenterlinePoint> centerline,
                                      List<RoadSpan> spans,
                                      List<RoadPreviewBlock> previewBlocks,
                                      List<BuildStep> buildSteps,
                                      List<RoadStructureIssue> issues) {
    public RoadNodeExpansionResult {
        canonicalNodes = canonicalNodes == null ? List.of() : canonicalNodes.stream().map(BlockPos::immutable).toList();
        canonicalSegmentTypes = canonicalSegmentTypes == null ? List.of() : List.copyOf(canonicalSegmentTypes);
        centerline = centerline == null ? List.of() : List.copyOf(centerline);
        spans = spans == null ? List.of() : List.copyOf(spans);
        previewBlocks = previewBlocks == null ? List.of() : List.copyOf(previewBlocks);
        buildSteps = buildSteps == null ? List.of() : List.copyOf(buildSteps);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.severity() == RoadStructureIssue.Severity.ERROR);
    }
}
```

`RoadNodeStructureExpander.java` initial implementation:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public final class RoadNodeStructureExpander {
    private RoadNodeStructureExpander() {
    }

    public static RoadNodeExpansionResult expand(List<BlockPos> nodes,
                                                 List<RoadPlannerSegmentType> segmentTypes,
                                                 RoadPlannerBuildSettings settings,
                                                 ServerLevel level,
                                                 RoadStructureMode mode) {
        return expand(nodes, segmentTypes, settings, RoadTerrainSampler.fromLevel(level), mode);
    }

    public static RoadNodeExpansionResult expand(List<BlockPos> nodes,
                                                 List<RoadPlannerSegmentType> segmentTypes,
                                                 RoadPlannerBuildSettings settings,
                                                 RoadTerrainSampler terrainSampler,
                                                 RoadStructureMode mode) {
        List<RoadRouteSection> sections = RoadRouteSectionNormalizer.sections(nodes, segmentTypes);
        if (sections.isEmpty()) {
            return new RoadNodeExpansionResult(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(RoadStructureIssue.error("路径节点不足，至少需要两个有效节点。"))
            );
        }
        return new RoadNodeExpansionResult(
                RoadRouteSectionNormalizer.flattenNodes(sections),
                RoadRouteSectionNormalizer.flattenSegmentTypes(sections),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
```

- [ ] **Step 4: Run normalization tests and verify they pass**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected: PASS for the four initial tests.

- [ ] **Step 5: Commit Task 1**

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java
git commit -m "feat: add road node structure expansion model"
```

---

### Task 2: Build centerline, spans, and smoothed target heights

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadCenterlineBuilder.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSpanClassifier.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadHeightProfileSmoother.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpander.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`

- [ ] **Step 1: Add failing centerline/span/smoothing tests**

Append these tests inside `RoadNodeStructureExpanderTest`:

```java
    @Test
    void buildsContinuousCenterlineAndBridgeSpan() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.PREVIEW
        );

        assertFalse(result.centerline().isEmpty());
        assertEquals(new BlockPos(0, 64, 0), result.centerline().get(0).pos());
        assertEquals(new BlockPos(8, 64, 0), result.centerline().get(result.centerline().size() - 1).pos());
        assertTrue(result.spans().stream().anyMatch(span -> span.type() == RoadSpanType.ROAD));
        assertTrue(result.spans().stream().anyMatch(span -> span.type() == RoadSpanType.BRIDGE));
    }

    @Test
    void steepRoadTerrainIsSmoothedForCartTravel() {
        RoadTerrainSampler steep = (x, z) -> 64 + x * 2;
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 80, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                steep,
                RoadStructureMode.PREVIEW
        );

        int maxDelta = 0;
        for (int i = 1; i < result.centerline().size(); i++) {
            maxDelta = Math.max(maxDelta, Math.abs(result.centerline().get(i).targetY() - result.centerline().get(i - 1).targetY()));
        }
        assertTrue(maxDelta <= 1, "integer targetY must not jump more than one block between samples");
        assertTrue(result.centerline().stream().anyMatch(point -> point.targetY() < point.terrainY()));
    }
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected: FAIL because `centerline()` and `spans()` are still empty.

- [ ] **Step 3: Create centerline/span/height classes**

`RoadCenterlineBuilder.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadCenterlineBuilder {
    private RoadCenterlineBuilder() {
    }

    public static List<RoadCenterlinePoint> build(RoadRouteSection section, RoadTerrainSampler terrainSampler) {
        if (section == null || section.nodes().size() < 2) {
            return List.of();
        }
        List<RoadCenterlinePoint> points = new ArrayList<>();
        double distance = 0.0D;
        for (int segmentIndex = 0; segmentIndex < section.nodes().size() - 1; segmentIndex++) {
            List<BlockPos> segment = interpolate(section.nodes().get(segmentIndex), section.nodes().get(segmentIndex + 1));
            RoadPlannerSegmentType segmentType = section.segmentTypes().get(segmentIndex);
            for (BlockPos raw : segment) {
                if (!points.isEmpty() && points.get(points.size() - 1).pos().getX() == raw.getX() && points.get(points.size() - 1).pos().getZ() == raw.getZ()) {
                    continue;
                }
                if (!points.isEmpty()) {
                    BlockPos prev = points.get(points.size() - 1).pos();
                    distance += Math.sqrt(Math.pow(raw.getX() - prev.getX(), 2) + Math.pow(raw.getZ() - prev.getZ(), 2));
                }
                int terrainY = terrainSampler == null ? raw.getY() : terrainSampler.terrainY(raw.getX(), raw.getZ());
                BlockPos pos = new BlockPos(raw.getX(), terrainY, raw.getZ());
                points.add(new RoadCenterlinePoint(pos, segmentIndex, segmentType, terrainY, terrainY, distance));
            }
        }
        return List.copyOf(points);
    }

    private static List<BlockPos> interpolate(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz)));
        List<BlockPos> points = new ArrayList<>(steps + 1);
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            points.add(new BlockPos(
                    (int) Math.round(from.getX() + dx * t),
                    (int) Math.round(from.getY() + dy * t),
                    (int) Math.round(from.getZ() + dz * t)
            ));
        }
        return List.copyOf(points);
    }
}
```

`RoadSpanClassifier.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;

import java.util.ArrayList;
import java.util.List;

public final class RoadSpanClassifier {
    private RoadSpanClassifier() {
    }

    public static List<RoadSpan> classify(List<RoadCenterlinePoint> centerline) {
        if (centerline == null || centerline.isEmpty()) {
            return List.of();
        }
        List<RoadSpan> spans = new ArrayList<>();
        int start = 0;
        RoadSpanType currentType = typeFor(centerline.get(0).segmentType());
        RoadPlannerSegmentType sourceType = centerline.get(0).segmentType();
        for (int index = 1; index < centerline.size(); index++) {
            RoadSpanType nextType = typeFor(centerline.get(index).segmentType());
            if (nextType != currentType) {
                spans.add(new RoadSpan(currentType, start, index - 1, sourceType));
                start = index;
                currentType = nextType;
                sourceType = centerline.get(index).segmentType();
            }
        }
        spans.add(new RoadSpan(currentType, start, centerline.size() - 1, sourceType));
        return List.copyOf(spans);
    }

    public static boolean isBridge(RoadPlannerSegmentType type) {
        return type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL || type == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE;
    }

    private static RoadSpanType typeFor(RoadPlannerSegmentType type) {
        if (isBridge(type)) {
            return RoadSpanType.BRIDGE;
        }
        if (type == RoadPlannerSegmentType.TUNNEL) {
            return RoadSpanType.TUNNEL;
        }
        return RoadSpanType.ROAD;
    }
}
```

`RoadHeightProfileSmoother.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.roadplanner.weaver.highway.WeaverHighwayHeightSmoother;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadHeightProfileSmoother {
    private static final int SLOPE_RUN_BLOCKS = 3;
    private static final int SLOPE_RISE_BLOCKS = 1;

    private RoadHeightProfileSmoother() {
    }

    public static List<RoadCenterlinePoint> smooth(List<RoadCenterlinePoint> centerline, List<RoadSpan> spans) {
        if (centerline == null || centerline.isEmpty()) {
            return List.of();
        }
        int[] baseY = new int[centerline.size()];
        boolean[] bridgeMask = new boolean[centerline.size()];
        List<BlockPos> centers = new ArrayList<>(centerline.size());
        for (int index = 0; index < centerline.size(); index++) {
            RoadCenterlinePoint point = centerline.get(index);
            baseY[index] = point.terrainY();
            centers.add(point.pos());
            bridgeMask[index] = spans != null && spans.stream().anyMatch(span -> span.type() == RoadSpanType.BRIDGE && span.contains(index));
        }
        int[] targetY = WeaverHighwayHeightSmoother.smooth(baseY, centers, bridgeMask, SLOPE_RUN_BLOCKS, SLOPE_RISE_BLOCKS);
        List<RoadCenterlinePoint> result = new ArrayList<>(centerline.size());
        for (int index = 0; index < centerline.size(); index++) {
            result.add(centerline.get(index).withTargetY(targetY[index]));
        }
        return List.copyOf(result);
    }
}
```

- [ ] **Step 4: Update expander to populate centerline and spans**

Replace the successful-return block in `RoadNodeStructureExpander.expand(... RoadTerrainSampler ...)` with this logic:

```java
        List<BlockPos> canonicalNodes = RoadRouteSectionNormalizer.flattenNodes(sections);
        List<RoadPlannerSegmentType> canonicalSegmentTypes = RoadRouteSectionNormalizer.flattenSegmentTypes(sections);
        java.util.List<RoadCenterlinePoint> allCenterline = new java.util.ArrayList<>();
        java.util.List<RoadSpan> allSpans = new java.util.ArrayList<>();
        int centerlineOffset = 0;
        for (RoadRouteSection section : sections) {
            List<RoadCenterlinePoint> rawCenterline = RoadCenterlineBuilder.build(section, terrainSampler);
            List<RoadSpan> rawSpans = RoadSpanClassifier.classify(rawCenterline);
            List<RoadCenterlinePoint> smoothedCenterline = RoadHeightProfileSmoother.smooth(rawCenterline, rawSpans);
            for (RoadSpan span : rawSpans) {
                allSpans.add(new RoadSpan(span.type(), span.startIndex() + centerlineOffset, span.endIndex() + centerlineOffset, span.sourceSegmentType()));
            }
            allCenterline.addAll(smoothedCenterline);
            centerlineOffset += smoothedCenterline.size();
        }
        return new RoadNodeExpansionResult(
                canonicalNodes,
                canonicalSegmentTypes,
                allCenterline,
                allSpans,
                List.of(),
                List.of(),
                List.of()
        );
```

Keep the insufficient-node error branch unchanged.

- [ ] **Step 5: Run centerline/span/smoothing tests**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected: PASS for normalization, centerline/span, and smoothing tests.

- [ ] **Step 6: Commit Task 2**

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java
git commit -m "feat: build road centerline spans and height profile"
```

---

### Task 3: Emit normal road foundation, surface, ramps, and headroom clearing

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSurfaceStepEmitter.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpander.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`

- [ ] **Step 1: Add failing normal-road structure tests**

Append these tests inside `RoadNodeStructureExpanderTest`:

```java
    @Test
    void flatRoadEmitsFoundationAndSurfaceSteps() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.BUILD
        );

        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.FOUNDATION));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.SURFACE));
        assertTrue(result.previewBlocks().stream().anyMatch(block -> block.phase() == com.monpai.sailboatmod.road.model.BuildPhase.SURFACE));
    }

    @Test
    void smoothedSteepRoadEmitsRampSteps() {
        RoadTerrainSampler steep = (x, z) -> 64 + x * 2;
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(12, 88, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                steep,
                RoadStructureMode.BUILD
        );

        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.state().isAir()));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.FOUNDATION && !step.state().isAir()));
    }
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected: FAIL because `buildSteps()` and `previewBlocks()` are empty.

- [ ] **Step 3: Create `RoadSurfaceStepEmitter`**

Create `RoadSurfaceStepEmitter.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverSegmentPaver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class RoadSurfaceStepEmitter {
    private RoadSurfaceStepEmitter() {
    }

    public static List<BuildStep> emit(List<RoadCenterlinePoint> centerline,
                                       List<RoadSpan> spans,
                                       RoadPlannerBuildSettings settings,
                                       int startOrder) {
        if (centerline == null || centerline.isEmpty()) {
            return List.of();
        }
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        for (int index = 0; index < centerline.size(); index++) {
            if (!isRoadIndex(spans, index)) {
                continue;
            }
            RoadCenterlinePoint point = centerline.get(index);
            BlockPos center = new BlockPos(point.pos().getX(), point.targetY(), point.pos().getZ());
            boolean ramp = isRamp(centerline, spans, index);
            BlockState surfaceState = ramp ? rampState(safeSettings, index) : safeSettings.surfaceState();
            BuildPhase surfacePhase = ramp ? BuildPhase.RAMP : BuildPhase.SURFACE;
            List<WeaverBuildCandidate> footprint = WeaverSegmentPaver.paveCenterline(List.of(center), safeSettings.width(), surfaceState);
            for (WeaverBuildCandidate candidate : footprint) {
                for (int dy = 1; dy <= 4; dy++) {
                    steps.add(new BuildStep(order++, candidate.pos().above(dy), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
                }
                int bottomY = Math.min(point.terrainY(), point.targetY()) - 3;
                for (int y = candidate.pos().getY() - 1; y >= bottomY; y--) {
                    BlockState foundation = y == bottomY ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                    steps.add(new BuildStep(order++, new BlockPos(candidate.pos().getX(), y, candidate.pos().getZ()), foundation, BuildPhase.FOUNDATION));
                }
                steps.add(new BuildStep(order++, candidate.pos(), candidate.state(), surfacePhase));
            }
        }
        return List.copyOf(steps);
    }

    private static boolean isRoadIndex(List<RoadSpan> spans, int index) {
        return spans == null || spans.stream().anyMatch(span -> span.type() == RoadSpanType.ROAD && span.contains(index));
    }

    private static boolean isRamp(List<RoadCenterlinePoint> centerline, List<RoadSpan> spans, int index) {
        if (!isRoadIndex(spans, index)) {
            return false;
        }
        int y = centerline.get(index).targetY();
        int prevY = index > 0 ? centerline.get(index - 1).targetY() : y;
        int nextY = index + 1 < centerline.size() ? centerline.get(index + 1).targetY() : y;
        return y != prevY || y != nextY;
    }

    private static BlockState rampState(RoadPlannerBuildSettings settings, int index) {
        return (index & 1) == 0 ? settings.slabBottomState() : settings.slabTopState();
    }
}
```

- [ ] **Step 4: Wire road emitter into expander**

In `RoadNodeStructureExpander.expand(... RoadTerrainSampler ...)`, after `allCenterline` and `allSpans` are built, add step aggregation before returning:

```java
        java.util.List<com.monpai.sailboatmod.road.model.BuildStep> steps = new java.util.ArrayList<>();
        steps.addAll(RoadSurfaceStepEmitter.emit(allCenterline, allSpans, settings, steps.size()));
        java.util.List<RoadPreviewBlock> previewBlocks = previewBlocksFromSteps(steps);
```

Then change the result return arguments from empty preview/build lists to:

```java
                previewBlocks,
                dedupeAndReorder(steps),
```

Add these private helpers to `RoadNodeStructureExpander`:

```java
    private static java.util.List<RoadPreviewBlock> previewBlocksFromSteps(java.util.List<com.monpai.sailboatmod.road.model.BuildStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<RoadPreviewBlock> preview = new java.util.ArrayList<>();
        for (com.monpai.sailboatmod.road.model.BuildStep step : steps) {
            if (isVisiblePreviewStep(step)) {
                preview.add(new RoadPreviewBlock(step.pos(), step.state(), step.phase()));
            }
        }
        return java.util.List.copyOf(preview);
    }

    private static boolean isVisiblePreviewStep(com.monpai.sailboatmod.road.model.BuildStep step) {
        if (step == null || step.pos() == null || step.state() == null || step.phase() == null || step.state().isAir()) {
            return false;
        }
        return switch (step.phase()) {
            case SURFACE, RAMP, DECK, PIER, RAILING, STREETLIGHT -> true;
            case FOUNDATION -> false;
        };
    }

    private static java.util.List<com.monpai.sailboatmod.road.model.BuildStep> dedupeAndReorder(java.util.List<com.monpai.sailboatmod.road.model.BuildStep> steps) {
        java.util.Set<StepKey> seen = new java.util.LinkedHashSet<>();
        java.util.List<com.monpai.sailboatmod.road.model.BuildStep> deduped = new java.util.ArrayList<>();
        if (steps != null) {
            for (com.monpai.sailboatmod.road.model.BuildStep step : steps) {
                if (step == null || step.pos() == null || step.state() == null || step.phase() == null) {
                    continue;
                }
                StepKey key = new StepKey(step.pos().immutable(), step.phase(), step.state());
                if (seen.add(key)) {
                    deduped.add(new com.monpai.sailboatmod.road.model.BuildStep(deduped.size(), step.pos(), step.state(), step.phase()));
                }
            }
        }
        return java.util.List.copyOf(deduped);
    }

    private record StepKey(net.minecraft.core.BlockPos pos, com.monpai.sailboatmod.road.model.BuildPhase phase, net.minecraft.world.level.block.state.BlockState state) {
    }
```

Compute `previewBlocks` after `dedupeAndReorder(steps)` so preview and build queues share the same deduped step set:

```java
        java.util.List<com.monpai.sailboatmod.road.model.BuildStep> dedupedSteps = dedupeAndReorder(steps);
        java.util.List<RoadPreviewBlock> previewBlocks = previewBlocksFromSteps(dedupedSteps);
```

Use `dedupedSteps` in the result.

- [ ] **Step 5: Run structure tests**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected: PASS including normal-road build step tests.

- [ ] **Step 6: Commit Task 3**

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java
git commit -m "feat: emit smoothed road construction steps"
```

---

### Task 4: Emit bridge deck, ramps, piers, and railings with separate phases

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeTemplateProvider.java`
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpander.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`

- [ ] **Step 1: Add failing bridge structure tests**

Append these tests inside `RoadNodeStructureExpanderTest`:

```java
    @Test
    void bridgeSpanEmitsRampDeckPierAndRailingPhases() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(60),
                RoadStructureMode.BUILD
        );

        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.PIER));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAILING));
        assertTrue(result.previewBlocks().stream().anyMatch(block -> block.phase() == com.monpai.sailboatmod.road.model.BuildPhase.PIER));
        assertTrue(result.previewBlocks().stream().anyMatch(block -> block.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAILING));
    }

    @Test
    void blockedBridgeMarkerBuildsAsMajorBridge() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                List.of(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(60),
                RoadStructureMode.BUILD
        );

        assertTrue(result.canonicalSegmentTypes().contains(RoadPlannerSegmentType.BRIDGE_MAJOR));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK));
        assertFalse(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.SURFACE));
    }
```

- [ ] **Step 2: Run tests and verify bridge tests fail**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected: FAIL because bridge spans do not yet emit bridge-specific steps.

- [ ] **Step 3: Create bridge emitter seam and programmatic emitter**

`BridgeTemplateProvider.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.road.model.BuildStep;

import java.util.List;

@FunctionalInterface
public interface BridgeTemplateProvider {
    List<BuildStep> buildFromTemplate(List<RoadCenterlinePoint> bridgeCenterline,
                                      RoadPlannerBuildSettings settings,
                                      int startOrder);

    static BridgeTemplateProvider empty() {
        return (bridgeCenterline, settings, startOrder) -> List.of();
    }
}
```

`BridgeStructureEmitter.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverSegmentPaver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class BridgeStructureEmitter {
    private static final int MAJOR_HEIGHT_BONUS = 5;
    private static final int SMALL_HEIGHT_BONUS = 3;
    private static final int PIER_INTERVAL = 4;

    private BridgeStructureEmitter() {
    }

    public static List<BuildStep> emit(List<RoadCenterlinePoint> centerline,
                                       List<RoadSpan> spans,
                                       RoadPlannerBuildSettings settings,
                                       BridgeTemplateProvider templateProvider,
                                       int startOrder) {
        if (centerline == null || centerline.isEmpty() || spans == null || spans.isEmpty()) {
            return List.of();
        }
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        BridgeTemplateProvider safeProvider = templateProvider == null ? BridgeTemplateProvider.empty() : templateProvider;
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        for (RoadSpan span : spans) {
            if (span.type() != RoadSpanType.BRIDGE) {
                continue;
            }
            List<RoadCenterlinePoint> bridgePoints = centerline.subList(span.startIndex(), span.endIndex() + 1);
            List<BuildStep> templateSteps = safeProvider.buildFromTemplate(bridgePoints, safeSettings, order);
            if (!templateSteps.isEmpty()) {
                steps.addAll(templateSteps);
                order += templateSteps.size();
                continue;
            }
            List<BuildStep> programmatic = emitProgrammaticBridge(bridgePoints, span, safeSettings, order);
            steps.addAll(programmatic);
            order += programmatic.size();
        }
        return List.copyOf(steps);
    }

    private static List<BuildStep> emitProgrammaticBridge(List<RoadCenterlinePoint> points,
                                                          RoadSpan span,
                                                          RoadPlannerBuildSettings settings,
                                                          int startOrder) {
        if (points.size() < 2) {
            return List.of();
        }
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        int heightBonus = span.sourceSegmentType() == com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType.BRIDGE_SMALL ? SMALL_HEIGHT_BONUS : MAJOR_HEIGHT_BONUS;
        int entryY = points.get(0).targetY();
        int exitY = points.get(points.size() - 1).targetY();
        int terrainFloor = points.stream().mapToInt(RoadCenterlinePoint::terrainY).min().orElse(Math.min(entryY, exitY));
        int deckY = Math.max(Math.max(entryY, exitY) + heightBonus, terrainFloor + 6);
        int entryRampLen = Math.min(Math.max(2, (deckY - entryY) * 2), Math.max(1, points.size() / 4));
        int exitRampLen = Math.min(Math.max(2, (deckY - exitY) * 2), Math.max(1, points.size() / 4));
        int deckStart = Math.min(points.size() - 1, entryRampLen);
        int deckEndExclusive = Math.max(deckStart + 1, points.size() - exitRampLen);

        for (int index = 0; index < points.size(); index++) {
            RoadCenterlinePoint point = points.get(index);
            int y = bridgeY(index, points.size(), entryY, exitY, deckY, entryRampLen, exitRampLen);
            boolean ramp = index < deckStart || index >= deckEndExclusive;
            BlockState state = ramp ? rampState(settings, index) : settings.surfaceState();
            BuildPhase phase = ramp ? BuildPhase.RAMP : BuildPhase.DECK;
            BlockPos center = new BlockPos(point.pos().getX(), y, point.pos().getZ());
            for (WeaverBuildCandidate candidate : WeaverSegmentPaver.paveCenterline(List.of(center), settings.width(), state)) {
                steps.add(new BuildStep(order++, candidate.pos(), candidate.state(), phase));
            }
            steps.addAll(railings(points, index, center, settings, order));
            order = startOrder + steps.size();
            if (!ramp && index % PIER_INTERVAL == 0) {
                int bottomY = Math.min(point.terrainY(), y - 1);
                for (int pierY = y - 1; pierY >= bottomY; pierY--) {
                    steps.add(new BuildStep(order++, new BlockPos(center.getX(), pierY, center.getZ()), Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.PIER));
                }
            }
        }
        return List.copyOf(steps);
    }

    private static int bridgeY(int index, int total, int entryY, int exitY, int deckY, int entryRampLen, int exitRampLen) {
        if (index < entryRampLen && entryRampLen > 0) {
            return (int) Math.round(entryY + (deckY - entryY) * (index / (double) entryRampLen));
        }
        if (index >= total - exitRampLen && exitRampLen > 0) {
            int remaining = total - 1 - index;
            return (int) Math.round(exitY + (deckY - exitY) * (remaining / (double) exitRampLen));
        }
        return deckY;
    }

    private static BlockState rampState(RoadPlannerBuildSettings settings, int index) {
        return (index & 1) == 0 ? settings.slabBottomState() : settings.slabTopState();
    }

    private static List<BuildStep> railings(List<RoadCenterlinePoint> points,
                                            int index,
                                            BlockPos center,
                                            RoadPlannerBuildSettings settings,
                                            int startOrder) {
        int dx = 0;
        int dz = 0;
        if (index + 1 < points.size()) {
            dx = Integer.compare(points.get(index + 1).pos().getX() - center.getX(), 0);
            dz = Integer.compare(points.get(index + 1).pos().getZ() - center.getZ(), 0);
        } else if (index > 0) {
            dx = Integer.compare(center.getX() - points.get(index - 1).pos().getX(), 0);
            dz = Integer.compare(center.getZ() - points.get(index - 1).pos().getZ(), 0);
        }
        if (dx == 0 && dz == 0) {
            dx = 1;
        }
        int halfWidth = settings.width() / 2;
        int perpX = -dz;
        int perpZ = dx;
        BlockState rail = Blocks.OAK_FENCE.defaultBlockState();
        return List.of(
                new BuildStep(startOrder, center.offset(perpX * (halfWidth + 1), 1, perpZ * (halfWidth + 1)), rail, BuildPhase.RAILING),
                new BuildStep(startOrder + 1, center.offset(perpX * -(halfWidth + 1), 1, perpZ * -(halfWidth + 1)), rail, BuildPhase.RAILING)
        );
    }
}
```

- [ ] **Step 4: Wire bridge emitter into expander before preview conversion**

In `RoadNodeStructureExpander.expand(... RoadTerrainSampler ...)`, after road steps are added, add bridge steps:

```java
        steps.addAll(BridgeStructureEmitter.emit(allCenterline, allSpans, settings, BridgeTemplateProvider.empty(), steps.size()));
        java.util.List<com.monpai.sailboatmod.road.model.BuildStep> dedupedSteps = dedupeAndReorder(steps);
        java.util.List<RoadPreviewBlock> previewBlocks = previewBlocksFromSteps(dedupedSteps);
```

Make sure the result uses `dedupedSteps` and `previewBlocks`.

- [ ] **Step 5: Run bridge structure tests**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest
```

Expected: PASS including bridge phase tests.

- [ ] **Step 6: Commit Task 4**

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java
git commit -m "feat: emit bridge ramps piers and railings"
```

---

### Task 5: Replace build-step compiler internals with the unified expander

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompiler.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompilerTest.java`

- [ ] **Step 1: Strengthen compiler tests for unified phases**

In `RoadPlannerBuildStepCompilerTest`, update `majorBridgeCreatesDeckStepsWithoutWaterSurfaceRoad` to require bridge support phases:

```java
    @Test
    void majorBridgeCreatesDeckRampPierAndRailingStepsWithoutSurfaceRoad() {
        List<BuildStep> steps = RoadPlannerBuildStepCompiler.compileForTest(
                List.of(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS
        );

        assertFalse(steps.isEmpty());
        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.DECK));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.RAMP));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.PIER));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.RAILING));
        assertFalse(steps.stream().anyMatch(step -> step.phase() == BuildPhase.SURFACE));
    }
```

Keep the existing duplicate and null-gap tests because they protect old behavior that users still rely on.

- [ ] **Step 2: Run compiler tests and verify old compiler fails the new bridge assertions**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildStepCompilerTest
```

Expected: FAIL on missing `RAMP`, `PIER`, or `RAILING` from the old compiler path.

- [ ] **Step 3: Replace `RoadPlannerBuildStepCompiler` with expander adapter**

Replace the body of `RoadPlannerBuildStepCompiler.java` with this slimmer adapter:

```java
package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpander;
import com.monpai.sailboatmod.roadplanner.structure.RoadStructureMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public final class RoadPlannerBuildStepCompiler {
    private RoadPlannerBuildStepCompiler() {
    }

    public static List<BuildStep> compile(List<BlockPos> nodes,
                                          List<RoadPlannerSegmentType> segmentTypes,
                                          RoadPlannerBuildSettings settings,
                                          ServerLevel level) {
        return RoadNodeStructureExpander.expand(
                nodes,
                segmentTypes,
                settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings,
                level,
                RoadStructureMode.BUILD
        ).buildSteps();
    }

    public static List<BuildStep> compileForTest(List<BlockPos> nodes,
                                                 List<RoadPlannerSegmentType> segmentTypes,
                                                 RoadPlannerBuildSettings settings) {
        return compile(nodes, segmentTypes, settings, null);
    }
}
```

- [ ] **Step 4: Run compiler tests**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildStepCompilerTest
```

Expected: PASS, including `nullNodeGapKeepsSeparatedRoadSpansAsSurfaceOnly`; that passing result confirms `RoadRouteSectionNormalizer.sections(...)` flushes at null gaps and does not carry bridge segment types across the gap.

- [ ] **Step 5: Run route expander tests for auto-complete compatibility**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerRouteExpanderTest
```

Expected: PASS; auto-complete still produces nodes/segment types and build preview steps now come from the unified expander.

- [ ] **Step 6: Commit Task 5**

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompiler.java src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildStepCompilerTest.java src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerRouteExpanderTest.java
git commit -m "refactor: route road planner builds through structure expander"
```

---

### Task 6: Use expander preview blocks in preview packet and expose bridge support structures

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacketTest.java`

- [ ] **Step 1: Update preview packet tests for ramp/pier/railing ghosts**

Replace the old bridge-deck ghost test in `RoadPlannerPreviewRequestPacketTest` with:

```java
    @Test
    void previewUsesUnifiedBridgeExpansionForRampPierAndRailingGhosts() {
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                List.of(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0)),
                List.of(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE),
                new RoadPlannerBuildSettings(5, "stone_bricks", false)
        );

        List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocks = packet.toPreviewPacketForTest().ghostBlocks();

        assertTrue(ghostBlocks.stream().anyMatch(block -> block.state().getBlock() == Blocks.STONE_BRICKS));
        assertTrue(ghostBlocks.stream().anyMatch(block -> block.state().getBlock() == Blocks.OAK_FENCE));
        assertFalse(ghostBlocks.stream().anyMatch(block -> block.state().getBlock() == Blocks.DIRT));
        assertFalse(ghostBlocks.stream().anyMatch(block -> block.state().getBlock() == Blocks.COBBLESTONE));
    }
```

- [ ] **Step 2: Run preview packet test and verify it fails until packet uses preview blocks**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacketTest
```

Expected: FAIL before Step 4 because the old ghost preview path does not expose all unified bridge preview blocks.

- [ ] **Step 3: Add `previewExpansion` to build control service**

In `RoadPlannerBuildControlService.java`, add imports:

```java
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeExpansionResult;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpander;
import com.monpai.sailboatmod.roadplanner.structure.RoadStructureMode;
```

Replace `previewBuildSteps(...)` with:

```java
    public static RoadNodeExpansionResult previewExpansion(List<BlockPos> nodes,
                                                           List<RoadPlannerSegmentType> segmentTypes,
                                                           RoadPlannerBuildSettings settings,
                                                           ServerLevel level) {
        return RoadNodeStructureExpander.expand(nodes, segmentTypes, settings, level, RoadStructureMode.PREVIEW);
    }

    public static List<BuildStep> previewBuildSteps(List<BlockPos> nodes,
                                                    List<RoadPlannerSegmentType> segmentTypes,
                                                    RoadPlannerBuildSettings settings,
                                                    ServerLevel level) {
        return previewExpansion(nodes, segmentTypes, settings, level).buildSteps();
    }
```

Keep `confirmPreview(...)` using `buildSteps(snapshot, level)`, and keep `buildSteps(...)` delegating through `RoadPlannerBuildStepCompiler.compile(...)`.

- [ ] **Step 4: Update preview packet to map `RoadPreviewBlock`**

In `RoadPlannerPreviewRequestPacket.java`, add imports:

```java
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeExpansionResult;
import com.monpai.sailboatmod.roadplanner.structure.RoadPreviewBlock;
```

Replace `ghostBlocksFromBuildSteps(ServerLevel level)` with:

```java
    private List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocksFromBuildSteps(ServerLevel level) {
        RoadNodeExpansionResult expansion = RoadPlannerBuildControlService.previewExpansion(nodes, segmentTypes, settings, level);
        List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocks = new ArrayList<>();
        for (RoadPreviewBlock block : expansion.previewBlocks()) {
            ghostBlocks.add(new SyncRoadPlannerPreviewPacket.GhostBlock(block.pos(), block.state()));
        }
        return List.copyOf(ghostBlocks);
    }
```

Delete the private `isVisiblePreviewStep(BuildStep step)` method and remove the now-unused `BuildStep` import from the packet.

- [ ] **Step 5: Run preview packet tests**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacketTest
```

Expected: PASS.

- [ ] **Step 6: Run packet round-trip smoke test**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest
```

Expected: PASS; preview request still produces ghost blocks for world preview.

- [ ] **Step 7: Commit Task 6**

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacket.java src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacketTest.java src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java
git commit -m "feat: preview unified road bridge structures"
```

---

### Task 7: Verify confirmed builds, progress service, and existing route behavior

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlServiceTest.java`
- Test-only run across affected test classes.

- [ ] **Step 1: Add confirmed bridge queue test**

Append this test inside `RoadPlannerBuildControlServiceTest`:

```java
    @Test
    void confirmedBridgePreviewQueuesRampPierAndRailingSteps() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID playerId = UUID.randomUUID();
        UUID previewId = service.startPreview(
                playerId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0)),
                List.of(com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS
        );

        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();

        var queue = service.buildQueueForTest(jobId).orElseThrow();
        assertTrue(queue.getSteps().stream().anyMatch(step -> step.phase() == BuildPhase.RAMP));
        assertTrue(queue.getSteps().stream().anyMatch(step -> step.phase() == BuildPhase.PIER));
        assertTrue(queue.getSteps().stream().anyMatch(step -> step.phase() == BuildPhase.RAILING));
        assertTrue(queue.getSteps().stream().anyMatch(step -> step.phase() == BuildPhase.DECK));
    }
```

- [ ] **Step 2: Run build control service tests**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlServiceTest
```

Expected: PASS; confirmed preview queues use the same expander-backed build steps.

- [ ] **Step 3: Run all RoadPlanner affected tests**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests 'com.monpai.sailboatmod.roadplanner.structure.*' --tests 'com.monpai.sailboatmod.roadplanner.service.*' --tests 'com.monpai.sailboatmod.network.packet.roadplanner.*' --tests 'com.monpai.sailboatmod.client.roadplanner.*'
```

Expected: PASS for all RoadPlanner affected tests. The implementation scope remains limited to RoadPlanner structure/integration files; unrelated minimap/Town/Nation UI files are not edited in this task.

- [ ] **Step 4: Commit Task 7**

```bash
git add src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlServiceTest.java
git commit -m "test: cover confirmed unified bridge builds"
```

---

### Task 8: Compile, run full tests, and build the all jar

**Files:**
- No source edits expected.
- Build artifact: `build/libs/sailboatmod-*-all.jar`

- [ ] **Step 1: Compile Java**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run full test suite**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false test
```

Expected: BUILD SUCCESSFUL. Any failure must be triaged to either this expander integration or pre-existing unrelated workspace edits before changing files; only expander-caused failures are fixed in this plan.

- [ ] **Step 3: Build the JarJar all jar**

Run:

```bash
./gradlew -Dnet.minecraftforge.gradle.check.certs=false jarJar
```

Expected: BUILD SUCCESSFUL and an `-all.jar` under `build/libs/`, such as `build/libs/sailboatmod-1.3.7-all.jar`.

- [ ] **Step 4: Record artifact path and git status**

Run:

```bash
ls -lh build/libs/*-all.jar
git status --short
```

Expected: the all jar exists; `git status --short` only shows intentional commits plus unrelated pre-existing workspace changes.

- [ ] **Step 5: Report final verification**

Report the exact `compileJava`, `test`, and `jarJar` results, plus the all-jar path printed by `ls -lh build/libs/*-all.jar`. Source and test fixes belong in the earlier task that introduced the failing behavior, so this step does not create a new commit.

---

## Final Verification Checklist

- [ ] `RoadNodeStructureExpanderTest` passes.
- [ ] `RoadPlannerBuildStepCompilerTest` passes.
- [ ] `RoadPlannerBuildControlServiceTest` passes.
- [ ] `RoadPlannerPreviewRequestPacketTest` passes.
- [ ] `RoadPlannerPacketRoundTripTest` passes.
- [ ] `RoadPlannerRouteExpanderTest` passes.
- [ ] `./gradlew -Dnet.minecraftforge.gradle.check.certs=false compileJava` succeeds.
- [ ] `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test` succeeds or any unrelated failure is documented with exact class and reason.
- [ ] `./gradlew -Dnet.minecraftforge.gradle.check.certs=false jarJar` produces an `-all.jar`.
- [ ] Manual game check: bridge preview shows deck, ramps, piers, and railings.
- [ ] Manual game check: confirmed bridge build queues deck, ramps, piers, and railings.
- [ ] Manual game check: steep normal road becomes smoother and remains carriage-friendly.
