# Road Planner Stability Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 8 confirmed bugs in the road planner: incorrect bridge tagging, broken force render, endpoint persistence, segment type buttons, bridge convergence construction, and preview consistency.

**Architecture:** Each fix is independent and can be committed separately. Fixes 1-2 unblock fix 7 (preview consistency). Fix 3 (small bridge) builds on fix 1. All changes are in the `road-planner-rebuild` worktree.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, vanilla Screen API

---

### Task 1: Fix road segments incorrectly tagged as bridge

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java` — `segmentTypeForConnection()`

- [ ] **Step 1: Fix segmentTypeForConnection**

Replace the method to only check the target point for water, not the previous node:

```java
private RoadPlannerSegmentType segmentTypeForConnection(BlockPos target, RoadPlannerSegmentType fallback) {
    RoadPlannerSegmentType safeFallback = fallback == null ? RoadPlannerSegmentType.ROAD : fallback;
    if (safeFallback == RoadPlannerSegmentType.BRIDGE_MAJOR || safeFallback == RoadPlannerSegmentType.BRIDGE_SMALL || safeFallback == RoadPlannerSegmentType.TUNNEL) {
        return safeFallback;
    }
    if (requiresBridgeTool(target)) {
        return RoadPlannerSegmentType.BRIDGE_MAJOR;
    }
    if (!isClientLand(target.getX(), target.getZ())) {
        return RoadPlannerSegmentType.BRIDGE_MAJOR;
    }
    return safeFallback;
}
```

- [ ] **Step 2: Compile and verify**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java
git commit -m "fix: segmentTypeForConnection only checks target water status"
```

---

### Task 2: Allow bridge as first node

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java` — `addNodeWithWaterSplit()`

- [ ] **Step 1: Fix addNodeWithWaterSplit for first node**

The first node should accept any segment type without restriction:

```java
private void addNodeWithWaterSplit(BlockPos target, RoadPlannerSegmentType segmentType) {
    if (linePlan.nodeCount() == 0) {
        linePlan.addClickNode(target, segmentType);
        return;
    }
    // ... rest unchanged
}
```

Note: remove the `segmentTypeForConnection` call for the first node — the caller already determined the type.

- [ ] **Step 2: Compile and verify**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java
git commit -m "fix: allow bridge/any type as first node"
```

---

### Task 3: Fix force render queue and progress bar

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerForceRenderQueue.java` — `processChunks()`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java` — force render mouseReleased

- [ ] **Step 1: Fix processChunks to count skipped chunks and limit poll rate**

```java
public int processChunks(int maxChunks,
                         java.util.function.Predicate<ChunkPos> skipPredicate,
                         java.util.function.Consumer<ChunkPos> chunkConsumer) {
    int processed = 0;
    int polled = 0;
    int maxPoll = maxChunks * 4;
    while (processed < maxChunks && !pending.isEmpty() && polled < maxPoll) {
        ChunkPos chunk = pending.poll();
        polled++;
        completedChunks++;
        if (skipPredicate != null && skipPredicate.test(chunk)) {
            continue;
        }
        if (chunkConsumer != null) {
            chunkConsumer.accept(chunk);
        }
        processed++;
    }
    return processed;
}
```

- [ ] **Step 2: Clear submitted set when force render selection is released**

In `RoadPlannerScreen.mouseReleased()`, find the force render selection handling and add `tileRenderScheduler.clear()` before enqueueing:

```java
// In the force render selection release block:
tileRenderScheduler.clear();
forceRenderQueue.enqueueSelection(forceRenderSelectionStart, forceRenderSelectionEnd, "选区渲染");
```

- [ ] **Step 3: Compile and verify**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerForceRenderQueue.java \
       src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java
git commit -m "fix: force render progress bar and submitted set clearing"
```

---

### Task 4: Persist endpoint positions in draft

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerDraftStore.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerDraftPersistence.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`

- [ ] **Step 1: Add startPos/endPos to Draft record**

In `RoadPlannerDraftStore.java`, change the `Draft` record:

```java
public record Draft(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes,
                    BlockPos startPos, BlockPos endPos) {
    public Draft {
        nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
        segmentTypes = segmentTypes == null ? List.of() : List.copyOf(segmentTypes);
        startPos = startPos == null ? BlockPos.ZERO : startPos.immutable();
        endPos = endPos == null ? BlockPos.ZERO : endPos.immutable();
    }
}
```

Update `save()` to accept startPos/endPos:

```java
public static void save(UUID sessionId, List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes,
                        BlockPos startPos, BlockPos endPos) {
    if (sessionId == null || nodes == null || nodes.isEmpty()) {
        return;
    }
    DRAFTS.put(sessionId, new Draft(nodes, segmentTypes, startPos, endPos));
}
```

- [ ] **Step 2: Update DraftPersistence save/load**

In `RoadPlannerDraftPersistence.java`, add P and E lines for start/end positions:

```java
// In save():
if (draft.startPos() != null && !draft.startPos().equals(BlockPos.ZERO)) {
    lines.add("P," + draft.startPos().getX() + "," + draft.startPos().getY() + "," + draft.startPos().getZ());
}
if (draft.endPos() != null && !draft.endPos().equals(BlockPos.ZERO)) {
    lines.add("E," + draft.endPos().getX() + "," + draft.endPos().getY() + "," + draft.endPos().getZ());
}

// In load():
BlockPos startPos = BlockPos.ZERO;
BlockPos endPos = BlockPos.ZERO;
// ... in the line parsing loop:
} else if (parts.length == 4 && "P".equals(parts[0])) {
    startPos = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
} else if (parts.length == 4 && "E".equals(parts[0])) {
    endPos = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
}
// Return:
return Optional.of(new RoadPlannerDraftStore.Draft(nodes, segments, startPos, endPos));
```

- [ ] **Step 3: Update RoadPlannerScreen saveDraft and applyTownRoute**

In `saveDraft()`, pass startTownPos/destinationTownPos:

```java
private void saveDraft() {
    RoadPlannerDraftStore.Draft draft = new RoadPlannerDraftStore.Draft(
            linePlan.nodes(), linePlan.segments(), startTownPos, destinationTownPos);
    RoadPlannerDraftStore.save(state.sessionId(), draft.nodes(), draft.segmentTypes(),
            draft.startPos(), draft.endPos());
    draftPersistence.save(state.sessionId(), draft);
    // ... routeDraftId save similarly
}
```

In `applyTownRoute()`, after loading draft, restore endpoint positions:

```java
if (draft != null && !draft.nodes().isEmpty()) {
    linePlan.replaceWith(draft.nodes(), draft.segmentTypes());
    if (!draft.startPos().equals(BlockPos.ZERO)) {
        this.startTownPos = draft.startPos();
    }
    if (!draft.endPos().equals(BlockPos.ZERO)) {
        this.destinationTownPos = draft.endPos();
    }
}
```

- [ ] **Step 4: Fix all callers of DraftStore.save**

Search for all calls to `RoadPlannerDraftStore.save` and update them to pass the new parameters. There may be calls in `saveDraft()` and test code.

- [ ] **Step 5: Compile and verify**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerDraftStore.java \
       src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerDraftPersistence.java \
       src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java
git commit -m "fix: persist endpoint positions in draft store"
```

---

### Task 5: Fix segment type buttons (set as bridge/road/tunnel)

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java` — `setSelectedEdgeType()`

- [ ] **Step 1: Rewrite setSelectedEdgeType to operate on linePlan**

```java
private void setSelectedEdgeType(CompiledRoadSectionType type) {
    if (selectedNode == null) {
        statusLine = "请先用选择工具选中一个节点";
        return;
    }
    int segIndex = selectedNode.nodeIndex();
    if (segIndex >= linePlan.segmentCount()) {
        segIndex = Math.max(0, segIndex - 1);
    }
    if (segIndex < 0 || segIndex >= linePlan.segmentCount()) {
        statusLine = "该节点没有可修改的段";
        return;
    }
    RoadPlannerSegmentType segType = switch (type) {
        case BRIDGE -> RoadPlannerSegmentType.BRIDGE_MAJOR;
        case TUNNEL -> RoadPlannerSegmentType.TUNNEL;
        default -> RoadPlannerSegmentType.ROAD;
    };
    linePlan.setSegmentTypeFromNode(segIndex, segType);
    saveDraft();
    statusLine = "已将该段设为: " + editableTypeLabel(type);
}
```

- [ ] **Step 2: Compile and verify**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java
git commit -m "fix: segment type buttons operate on linePlan not graph"
```

---

### Task 6: Rewrite bridge construction with two-end convergence

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java` — `nodeAnchoredBridgeSteps()`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitter.java` — bridge type selection
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoCompleteService.java` — small bridge threshold

- [ ] **Step 1: Rewrite nodeAnchoredBridgeSteps with convergence logic**

Both ends climb UP toward the middle. The bridge is arch-shaped:

```java
private static List<BuildStep> nodeAnchoredBridgeSteps(List<BlockPos> bridgeNodes, int width, ServerLevel level) {
    if (bridgeNodes == null || bridgeNodes.size() < 2) {
        return List.of();
    }
    List<BlockPos> centerline = RoadPlannerPathCompiler.interpolateCenters(bridgeNodes);
    if (centerline.size() < 2) {
        return List.of();
    }
    BlockPos entryNode = bridgeNodes.get(0);
    BlockPos exitNode = bridgeNodes.get(bridgeNodes.size() - 1);
    int entryY = entryNode.getY();
    int exitY = exitNode.getY();
    int deckY = Math.max(entryY, exitY) + 5;
    int totalLen = centerline.size();

    // Ramp lengths: 2 horizontal per 1 vertical, capped at 1/4 total
    int entryRampLen = Math.min((deckY - entryY) * 2, totalLen / 4);
    int exitRampLen = Math.min((deckY - exitY) * 2, totalLen / 4);

    List<BuildStep> steps = new java.util.ArrayList<>();
    int order = 0;
    int halfWidth = width / 2;
    BlockState deckState = net.minecraft.world.level.block.Blocks.SPRUCE_PLANKS.defaultBlockState();
    BlockState pierState = net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState();
    BlockState railState = net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState();

    for (int i = 0; i < totalLen; i++) {
        BlockPos center = centerline.get(i);
        int y;
        if (i < entryRampLen && entryRampLen > 0) {
            // Entry ramp: climb from entryY to deckY
            double t = i / (double) entryRampLen;
            y = (int) Math.round(entryY + (deckY - entryY) * t);
        } else if (i >= totalLen - exitRampLen && exitRampLen > 0) {
            // Exit ramp: climb from exitY to deckY (converging from exit end)
            double t = (totalLen - 1 - i) / (double) exitRampLen;
            y = (int) Math.round(exitY + (deckY - exitY) * t);
        } else {
            // Flat deck in the middle
            y = deckY;
        }

        BlockPos deckPos = new BlockPos(center.getX(), y, center.getZ());

        // Direction for perpendicular width
        int dx = 0, dz = 0;
        if (i + 1 < totalLen) {
            dx = Integer.compare(centerline.get(i + 1).getX() - center.getX(), 0);
            dz = Integer.compare(centerline.get(i + 1).getZ() - center.getZ(), 0);
        } else if (i > 0) {
            dx = Integer.compare(center.getX() - centerline.get(i - 1).getX(), 0);
            dz = Integer.compare(center.getZ() - centerline.get(i - 1).getZ(), 0);
        }
        int perpX = -dz;
        int perpZ = dx;

        // Deck surface
        for (int offset = -halfWidth; offset <= halfWidth; offset++) {
            steps.add(new BuildStep(order++, deckPos.offset(perpX * offset, 0, perpZ * offset), deckState, BuildPhase.DECK));
        }

        // Railings
        steps.add(new BuildStep(order++, deckPos.offset(perpX * (halfWidth + 1), 1, perpZ * (halfWidth + 1)), railState, BuildPhase.DECK));
        steps.add(new BuildStep(order++, deckPos.offset(perpX * -(halfWidth + 1), 1, perpZ * -(halfWidth + 1)), railState, BuildPhase.DECK));

        // Piers every 4 blocks in flat deck section only
        if (i >= entryRampLen && i < totalLen - exitRampLen && i % 4 == 0) {
            for (int py = y - 1; py >= y - 12 && py >= 0; py--) {
                steps.add(new BuildStep(order++, new BlockPos(center.getX(), py, center.getZ()), pierState, BuildPhase.DECK));
            }
        }
    }
    return List.copyOf(steps);
}
```

- [ ] **Step 2: Update WaterCrossingSplitter to choose BRIDGE_SMALL vs BRIDGE_MAJOR**

In `RoadPlannerWaterCrossingSplitter.buildSplitFromSpans()`, calculate water span width and choose type:

```java
int spanWidth = span.endSampleIndex() - span.startSampleIndex();
int spanBlocks = spanWidth * SAMPLE_SPACING;
RoadPlannerSegmentType bridgeType = spanBlocks <= 24
        ? RoadPlannerSegmentType.BRIDGE_SMALL
        : RoadPlannerSegmentType.BRIDGE_MAJOR;
```

Use `bridgeType` instead of hardcoded `BRIDGE_MAJOR` for bridge nodes.

- [ ] **Step 3: Update AutoCompleteService classifySegment for small bridge**

In `RoadPlannerAutoCompleteService.classifySegment()`, the existing water detection already returns BRIDGE_MAJOR. Add BRIDGE_SMALL threshold:

```java
if (!landProbe.isLand(from.getX(), from.getZ()) || !landProbe.isLand(to.getX(), to.getZ())) {
    int horizontal = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
    return horizontal <= 24 ? RoadPlannerSegmentType.BRIDGE_SMALL : RoadPlannerSegmentType.BRIDGE_MAJOR;
}
```

- [ ] **Step 4: Handle BRIDGE_SMALL in buildStepsWithBridgeBackend**

In `RoadPlannerBuildControlService.buildStepsWithBridgeBackend()`, add BRIDGE_SMALL handling alongside BRIDGE_MAJOR. BRIDGE_SMALL uses the same `nodeAnchoredBridgeSteps` but with a lower deckY (entryY/exitY + 3 instead of +5):

```java
if (type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL) {
    int bridgeStart = segmentIndex;
    RoadPlannerSegmentType bridgeType = type;
    while (segmentIndex < nodes.size() - 1 && (segmentTypeAt(segmentTypes, segmentIndex) == RoadPlannerSegmentType.BRIDGE_MAJOR || segmentTypeAt(segmentTypes, segmentIndex) == RoadPlannerSegmentType.BRIDGE_SMALL)) {
        segmentIndex++;
    }
    int heightBonus = bridgeType == RoadPlannerSegmentType.BRIDGE_SMALL ? 3 : 5;
    List<BuildStep> bridgeSteps = nodeAnchoredBridgeSteps(
            nodes.subList(bridgeStart, segmentIndex + 1), snapshot.settings().width(), level, heightBonus);
    // ...
}
```

Add `heightBonus` parameter to `nodeAnchoredBridgeSteps`:

```java
private static List<BuildStep> nodeAnchoredBridgeSteps(List<BlockPos> bridgeNodes, int width, ServerLevel level, int heightBonus) {
    // ... same as Step 1 but use heightBonus instead of hardcoded 5:
    int deckY = Math.max(entryY, exitY) + heightBonus;
    // ...
}
```

- [ ] **Step 5: Compile and verify**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerBuildControlService.java \
       src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerWaterCrossingSplitter.java \
       src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoCompleteService.java
git commit -m "feat: bridge convergence construction + BRIDGE_SMALL type"
```

---

### Task 7: Build jar and push

- [ ] **Step 1: Full build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Push**

```bash
git push
```

---

## Spec Coverage Check

| Spec Item | Task |
|-----------|------|
| Fix 1: Road tagged as bridge | Task 1 |
| Fix 2: Bridge as first node | Task 2 |
| Fix 3: Small bridge type | Task 6 (steps 2-4) |
| Fix 4: Force render broken | Task 3 |
| Fix 5: Endpoint persistence | Task 4 |
| Fix 6: Segment type buttons | Task 5 |
| Fix 7: Preview consistency | Resolved by Tasks 1+6 |
| Fix 8: Auto-complete water | Task 6 step 3 |
