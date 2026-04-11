# Task 1C Preview And Runtime Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining preview-stability and runtime-polish items with exact-camera rendering, stale-state cleanup, and fresh verification.

**Architecture:** Keep the current renderer/client-hook split. The renderers stay responsible for world-to-camera box math, while `RoadPlannerClientHooks` remains responsible for preview/progress state lifecycle. The work here is hardening and cleanup, not a new overlay system.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Gradle

---

### Task 1: Preserve exact-camera preview-box behavior

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/renderer/ConstructionGhostPreviewRenderer.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRendererTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/renderer/ConstructionGhostPreviewRendererTest.java`

- [ ] **Step 1: Confirm the renderer tests assert exact `Vec3` subtraction rather than integer camera rounding**

```java
RoadPlannerPreviewRenderer.PreviewBox box =
        RoadPlannerPreviewRenderer.previewBoxForTest(new BlockPos(10, 64, 10), new Vec3(9.75D, 63.5D, 9.25D));

assertEquals(0.25D, box.minX(), 1.0E-6D);
assertEquals(0.50D, box.minY(), 1.0E-6D);
assertEquals(0.75D, box.minZ(), 1.0E-6D);
```

- [ ] **Step 2: Run the renderer tests first**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest" --tests "com.monpai.sailboatmod.client.renderer.ConstructionGhostPreviewRendererTest"`

Expected: PASS

- [ ] **Step 3: If any drift remains, keep the renderer math on exact camera `Vec3` paths only**

```java
double minX = pos.getX() - cameraPos.x;
double minY = pos.getY() - cameraPos.y;
double minZ = pos.getZ() - cameraPos.z;
```

- [ ] **Step 4: Re-run the renderer tests**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest" --tests "com.monpai.sailboatmod.client.renderer.ConstructionGhostPreviewRendererTest"`

Expected: PASS

- [ ] **Step 5: Commit the renderer hardening**

```bash
git add src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java src/main/java/com/monpai/sailboatmod/client/renderer/ConstructionGhostPreviewRenderer.java src/test/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRendererTest.java src/test/java/com/monpai/sailboatmod/client/renderer/ConstructionGhostPreviewRendererTest.java
git commit -m "fix: stabilize road preview camera offsets"
```

### Task 2: Eliminate stale preview/progress state after failure and retarget flows

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Create or Modify: `src/test/java/com/monpai/sailboatmod/client/RoadPlannerClientHooksTest.java`

- [ ] **Step 1: Add a small hook-level test for clearing preview/progress state**

```java
@Test
void clearPreviewStateDropsGhostsAndProgress() {
    RoadPlannerClientHooks.setPreviewStateForTest(samplePreview());
    RoadPlannerClientHooks.setActiveProgressForTest(List.of(sampleProgress()));

    RoadPlannerClientHooks.clearPreviewStateForTest();

    assertNull(RoadPlannerClientHooks.previewStateForTest());
    assertTrue(RoadPlannerClientHooks.activeProgressForTest().isEmpty());
}
```

- [ ] **Step 2: Run the new hook-level test**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.client.RoadPlannerClientHooksTest"`

Expected: FAIL until test hooks or clear logic exist.

- [ ] **Step 3: Implement the minimal lifecycle hook needed by the test**

```java
public static void clearPreviewState() {
    previewState = null;
    activeProgress = List.of();
}
```

- [ ] **Step 4: Ensure failed plan / mode-change paths call the clear method**

```java
if (result.failed()) {
    RoadPlannerClientHooks.clearPreviewState();
}
```

- [ ] **Step 5: Re-run the hook test and then the renderer tests together**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.client.RoadPlannerClientHooksTest" --tests "com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest" --tests "com.monpai.sailboatmod.client.renderer.ConstructionGhostPreviewRendererTest"`

Expected: PASS

- [ ] **Step 6: Commit the stale-state cleanup**

```bash
git add src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/client/RoadPlannerClientHooksTest.java
git commit -m "fix: clear stale road preview state"
```

### Task 3: Re-verify task 1C and close the task-file wording

**Files:**
- Modify: `未完成_任务_1.md`

- [ ] **Step 1: Run the full verification command for task 1C**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Update the preview section in the task file to reference exact-camera rendering and stale-state cleanup evidence**

```markdown
- preview stability: closed by `RoadPlannerPreviewRendererTest` and `ConstructionGhostPreviewRendererTest`
- stale preview cleanup: closed by `RoadPlannerClientHooksTest`
```

- [ ] **Step 3: Commit the task-file closure**

```bash
git add 未完成_任务_1.md
git commit -m "docs: close task1c preview and runtime items"
```
