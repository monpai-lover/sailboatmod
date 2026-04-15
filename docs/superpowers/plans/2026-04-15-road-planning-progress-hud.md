# Road Planning Progress HUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated manual-road-planning HUD that shows truthful stage-based progress and percent while async preview planning runs.

**Architecture:** Keep planning HUD state completely separate from preview-result packets and construction-progress packets. The server emits a dedicated manual-planning progress packet from `ManualRoadPlannerService` using request ids and coarse real pipeline stages; the client stores only the newest request, smooths progress within a stage, renders the HUD in `RoadPlannerPreviewRenderer`, then clears it on success, failure, timeout, or replacement.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1 networking, JUnit 5, Gradle, existing road planner HUD/packet infrastructure

---

## File Structure

### New Files To Create

- `src/main/java/com/monpai/sailboatmod/network/packet/SyncManualRoadPlanningProgressPacket.java`
  - Dedicated clientbound packet for manual road planning HUD updates.
- `src/test/java/com/monpai/sailboatmod/network/packet/SyncManualRoadPlanningProgressPacketTest.java`
  - Packet encode/decode and client application tests.

### Existing Files To Modify

- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
  - Allocate per-request ids, emit planning stage updates, finalize/clear progress on terminal states.
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningTaskService.java`
  - Reuse latest-request suppression helpers if a request-id handoff or cancellation hook is needed.
- `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
  - Add dedicated planning HUD state, stale request suppression, timeout handling, and smoothing helpers.
- `src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java`
  - Draw the planning progress HUD section with stage label and percent.
- `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
  - Register the new clientbound packet.
- `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
  - Verify progress emission, replacement behavior, and terminal handling.
- `src/test/java/com/monpai/sailboatmod/client/RoadPlannerClientHooksTest.java`
  - Verify newest-request wins, stale packets ignored, timeout/hold windows, and smoothing caps.
- `src/test/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRendererTest.java`
  - Verify HUD text/percent rendering helpers.

## Task 1: Add The Dedicated Planning Progress Packet

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/SyncManualRoadPlanningProgressPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/SyncManualRoadPlanningProgressPacketTest.java`

- [ ] **Step 1: Write the failing packet tests**

```java
@Test
void encodeDecodeRoundTripsPlanningProgressPayload() {
    SyncManualRoadPlanningProgressPacket packet = new SyncManualRoadPlanningProgressPacket(
            9L,
            "Alpha",
            "Beta",
            "sampling_terrain",
            "采样地形",
            18,
            45,
            SyncManualRoadPlanningProgressPacket.Status.RUNNING
    );

    FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
    packet.encode(buffer);

    SyncManualRoadPlanningProgressPacket decoded = SyncManualRoadPlanningProgressPacket.decode(buffer);
    assertEquals(packet, decoded);
}

@Test
void handleUpdatesDedicatedPlanningHudState() {
    RoadPlannerClientHooks.resetStateForTest();
    SyncManualRoadPlanningProgressPacket packet = new SyncManualRoadPlanningProgressPacket(
            11L,
            "Alpha",
            "Beta",
            "building_preview",
            "生成预览",
            94,
            70,
            SyncManualRoadPlanningProgressPacket.Status.SUCCESS
    );

    packet.applyForTest();

    RoadPlannerClientHooks.PlanningProgressState state = RoadPlannerClientHooks.activePlanningProgressForTest(2_000L);
    assertNotNull(state);
    assertEquals(11L, state.requestId());
    assertEquals("building_preview", state.stageKey());
    assertEquals(94, state.serverPercent());
}
```

- [ ] **Step 2: Run the packet tests to verify they fail**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacketTest"`

Expected: FAIL because the packet class and dedicated planning state do not exist.

- [ ] **Step 3: Write the minimal packet implementation and registration**

```java
public record SyncManualRoadPlanningProgressPacket(
        long requestId,
        String sourceTownName,
        String targetTownName,
        String stageKey,
        String stageLabel,
        int overallPercent,
        int stagePercent,
        Status status) {

    public enum Status {
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED
    }

    public static void handle(SyncManualRoadPlanningProgressPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> RoadPlannerClientHooks.updatePlanningProgress(message));
        context.setPacketHandled(true);
    }
}
```

```java
CHANNEL.registerMessage(
        packetId++,
        SyncManualRoadPlanningProgressPacket.class,
        SyncManualRoadPlanningProgressPacket::encode,
        SyncManualRoadPlanningProgressPacket::decode,
        SyncManualRoadPlanningProgressPacket::handle,
        Optional.of(NetworkDirection.PLAY_TO_CLIENT)
);
```

- [ ] **Step 4: Run the packet tests to verify they pass**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacketTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/network/packet/SyncManualRoadPlanningProgressPacket.java src/main/java/com/monpai/sailboatmod/network/ModNetwork.java src/test/java/com/monpai/sailboatmod/network/packet/SyncManualRoadPlanningProgressPacketTest.java
git commit -m "Add manual road planning progress packet"
```

## Task 2: Add Client Planning HUD State And Smoothing

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/RoadPlannerClientHooksTest.java`

- [ ] **Step 1: Write the failing client-state tests**

```java
@Test
void newerPlanningRequestReplacesOlderRequest() {
    RoadPlannerClientHooks.resetStateForTest();

    RoadPlannerClientHooks.updatePlanningProgress(new SyncManualRoadPlanningProgressPacket(
            4L, "Alpha", "Beta", "sampling_terrain", "采样地形", 18, 30,
            SyncManualRoadPlanningProgressPacket.Status.RUNNING
    ));
    RoadPlannerClientHooks.updatePlanningProgress(new SyncManualRoadPlanningProgressPacket(
            5L, "Alpha", "Gamma", "trying_bridge", "桥路尝试", 80, 60,
            SyncManualRoadPlanningProgressPacket.Status.RUNNING
    ));

    RoadPlannerClientHooks.PlanningProgressState state = RoadPlannerClientHooks.activePlanningProgressForTest(100L);
    assertEquals(5L, state.requestId());
    assertEquals("Gamma", state.targetTownName());
}

@Test
void stalePlanningRequestIsIgnored() {
    RoadPlannerClientHooks.resetStateForTest();

    RoadPlannerClientHooks.updatePlanningProgress(new SyncManualRoadPlanningProgressPacket(
            6L, "Alpha", "Gamma", "trying_bridge", "桥路尝试", 80, 60,
            SyncManualRoadPlanningProgressPacket.Status.RUNNING
    ));
    RoadPlannerClientHooks.updatePlanningProgress(new SyncManualRoadPlanningProgressPacket(
            5L, "Alpha", "Beta", "sampling_terrain", "采样地形", 18, 30,
            SyncManualRoadPlanningProgressPacket.Status.RUNNING
    ));

    RoadPlannerClientHooks.PlanningProgressState state = RoadPlannerClientHooks.activePlanningProgressForTest(100L);
    assertEquals(6L, state.requestId());
    assertEquals("trying_bridge", state.stageKey());
}

@Test
void terminalPlanningStateClearsAfterHoldWindow() {
    RoadPlannerClientHooks.resetStateForTest();
    RoadPlannerClientHooks.updatePlanningProgress(new SyncManualRoadPlanningProgressPacket(
            7L, "Alpha", "Beta", "building_preview", "生成预览", 100, 100,
            SyncManualRoadPlanningProgressPacket.Status.FAILED
    ));

    assertNotNull(RoadPlannerClientHooks.activePlanningProgressForTest(100L));
    assertNull(RoadPlannerClientHooks.activePlanningProgressForTest(3_500L));
}

@Test
void smoothingNeverExceedsLatestAuthoritativeServerPercent() {
    RoadPlannerClientHooks.resetStateForTest();
    RoadPlannerClientHooks.updatePlanningProgress(new SyncManualRoadPlanningProgressPacket(
            8L, "Alpha", "Beta", "sampling_terrain", "采样地形", 20, 40,
            SyncManualRoadPlanningProgressPacket.Status.RUNNING
    ));

    RoadPlannerClientHooks.PlanningProgressState state = RoadPlannerClientHooks.activePlanningProgressForTest(250L);
    assertNotNull(state);
    assertTrue(state.displayPercent() <= 20);
}
```

- [ ] **Step 2: Run the client-state tests to verify they fail**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.client.RoadPlannerClientHooksTest"`

Expected: FAIL because planning HUD state methods do not exist.

- [ ] **Step 3: Write the minimal client-state implementation**

```java
public record PlanningProgressState(
        long requestId,
        String sourceTownName,
        String targetTownName,
        String stageKey,
        String stageLabel,
        int serverPercent,
        int displayPercent,
        int stagePercent,
        SyncManualRoadPlanningProgressPacket.Status status,
        long updatedAtMs,
        long clearAfterMs) {
}

public static void updatePlanningProgress(SyncManualRoadPlanningProgressPacket packet) {
    if (packet == null || packet.requestId() < 0L) {
        return;
    }
    if (planningProgressState != null && packet.requestId() < planningProgressState.requestId()) {
        return;
    }
    planningProgressState = PlanningProgressState.fromPacket(packet, System.currentTimeMillis());
}

public static PlanningProgressState activePlanningProgress() {
    return activePlanningProgressAt(System.currentTimeMillis());
}
```

- [ ] **Step 4: Run the client-state tests to verify they pass**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.client.RoadPlannerClientHooksTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java src/test/java/com/monpai/sailboatmod/client/RoadPlannerClientHooksTest.java
git commit -m "Add manual road planning HUD client state"
```

## Task 3: Emit Server-Side Planning Progress From Manual Planner Flow

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningTaskService.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`

- [ ] **Step 1: Write the failing service tests**

```java
@Test
void submitPreviewPlanningSendsPreparingStageImmediately() {
    ServerPlayer player = planningPlayer();
    ItemStack planner = plannerStackWithTarget("beta");

    ManualRoadPlannerService.submitPreviewPlanningForTest(taskService(), player, planner);

    SyncManualRoadPlanningProgressPacket packet = lastPlanningProgressPacket();
    assertNotNull(packet);
    assertEquals("preparing", packet.stageKey());
    assertEquals(8, packet.overallPercent());
}

@Test
void successfulPreviewSendsFinalSuccessBeforePreviewPacket() {
    PlannedPreviewState preview = successfulPreviewState();

    ManualRoadPlannerService.applyPlannedPreviewForTest(player(), plannerStackWithTarget("beta"), preview, 12L);

    assertEquals(List.of("progress:SUCCESS", "preview"), sentMessageKinds());
}

@Test
void failedPreviewSendsTerminalFailedState() {
    PlannedPreviewState preview = failedPreviewState("message.sailboatmod.road_planner.path_failed");

    ManualRoadPlannerService.applyPlannedPreviewForTest(player(), plannerStackWithTarget("beta"), preview, 13L);

    SyncManualRoadPlanningProgressPacket packet = lastPlanningProgressPacket();
    assertEquals(SyncManualRoadPlanningProgressPacket.Status.FAILED, packet.status());
}
```

- [ ] **Step 2: Run the service tests to verify they fail**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`

Expected: FAIL because request ids, progress-stage helpers, and dedicated planning packet emission are missing.

- [ ] **Step 3: Write the minimal server implementation**

```java
private static final AtomicLong MANUAL_PLANNING_REQUEST_IDS = new AtomicLong();

private static long nextPlanningRequestId() {
    return MANUAL_PLANNING_REQUEST_IDS.incrementAndGet();
}

private static void sendPlanningProgress(ServerPlayer player,
                                         long requestId,
                                         String sourceTownName,
                                         String targetTownName,
                                         PlanningStage stage,
                                         int stagePercent,
                                         SyncManualRoadPlanningProgressPacket.Status status) {
    ModNetwork.CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            stage.packet(requestId, sourceTownName, targetTownName, stagePercent, status)
    );
}
```

```java
sendPlanningProgress(player, requestId, sourceName, targetName, PlanningStage.PREPARING, 100, Status.RUNNING);
taskService.submitLatest(
        new TaskKey("manual-preview", player.getUUID().toString()),
        () -> buildPreviewWithProgress(player, planningStack, requestId),
        preview -> applyPlannedPreview(player, stack, preview, requestId)
);
```

- [ ] **Step 4: Run the service tests to verify they pass**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningTaskService.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java
git commit -m "Emit manual road planning progress stages"
```

## Task 4: Render The Planning HUD Section

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRendererTest.java`

- [ ] **Step 1: Write the failing renderer tests**

```java
@Test
void planningHudLabelIncludesStageAndPercent() {
    RoadPlannerClientHooks.PlanningProgressState state = new RoadPlannerClientHooks.PlanningProgressState(
            21L, "Alpha", "Beta", "sampling_terrain", "采样地形", 18, 18, 45,
            SyncManualRoadPlanningProgressPacket.Status.RUNNING, 0L, Long.MAX_VALUE
    );

    assertEquals(
            "道路规划中: 采样地形 18%",
            RoadPlannerPreviewRenderer.planningHeadlineForTest(state).getString()
    );
}

@Test
void planningHudUsesTerminalColorForFailedState() {
    assertEquals(
            0xFFF08A8A,
            RoadPlannerPreviewRenderer.planningStatusColorForTest(
                    SyncManualRoadPlanningProgressPacket.Status.FAILED
            )
    );
}
```

- [ ] **Step 2: Run the renderer tests to verify they fail**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest"`

Expected: FAIL because planning HUD formatting helpers do not exist.

- [ ] **Step 3: Write the minimal HUD rendering implementation**

```java
RoadPlannerClientHooks.PlanningProgressState planning = RoadPlannerClientHooks.activePlanningProgress();
if (planning != null) {
    guiGraphics.drawString(minecraft.font, planningHeadline(planning), x + 10, textY, planningStatusColor(planning.status()), false);
    textY += 12;
    drawProgressBar(guiGraphics, x + 10, textY, width - 20, 8, planning.displayPercent());
    textY += 14;
}
```

```java
static Component planningHeadlineForTest(RoadPlannerClientHooks.PlanningProgressState state) {
    return Component.literal("道路规划中: " + state.stageLabel() + " " + state.displayPercent() + "%");
}
```

- [ ] **Step 4: Run the renderer tests to verify they pass**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java src/test/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRendererTest.java
git commit -m "Render manual road planning HUD progress"
```

## Task 5: Regression Verification And Push

**Files:**
- Verify only

- [ ] **Step 1: Run the targeted planning HUD suites**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacketTest" --tests "com.monpai.sailboatmod.client.RoadPlannerClientHooksTest" --tests "com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest" --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`

Expected: PASS

- [ ] **Step 2: Run compile verification**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" compileJava`

Expected: PASS

- [ ] **Step 3: Run full build**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" build`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Push branch**

```bash
git push origin road-bridge-construction-stability
```

Expected: remote branch updates successfully

## Spec Coverage Check

1. Dedicated planning packet and isolated client state: Tasks 1-2
2. Request id replacement and stale suppression: Tasks 2-3
3. Real stage-based progress with approved stage bands: Task 3
4. HUD percent + stage text + smoothing: Tasks 2 and 4
5. Success/failure/cancel clear behavior separate from preview geometry: Tasks 2-4
6. Verification and push: Task 5

## Placeholder Scan

1. No `TODO`, `TBD`, or deferred implementation markers remain.
2. Every task includes explicit files, tests, commands, and expected outcomes.

## Type Consistency Check

1. `SyncManualRoadPlanningProgressPacket` is the only network payload for planning HUD updates.
2. `RoadPlannerClientHooks.PlanningProgressState` is distinct from construction `ProgressState`.
3. `ManualRoadPlannerService` remains the owner of manual preview request ids and terminal update ordering.
