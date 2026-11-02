# Manual Road Planner Dual-Planner Rebuild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild manual road planning so `detour` and `bridge` are real separate planners, the UI no longer jumps back to the option screen, true terrain surfaces ignore canopy noise, short spans stay pierless, long spans become pier bridges, and road demolition uses tolerant targeting.

**Architecture:** Split the work into a thin orchestration layer in `ManualRoadPlannerService`, a dedicated client UI/result state flow, a reusable true-surface sampling helper, separate detour/bridge planner helpers, and a bridge-construction style layer that can distinguish short-span decks from pier bridges. Keep the existing packet/render/service structure, but move planner-specific decisions out of the giant service file wherever a focused helper class makes the behavior testable.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1 networking/screens, Gradle, JUnit 5, existing road planner preview infrastructure

---

## File Structure

### New Files To Create

- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerConfig.java`
  - Shared normalized planner config model for width/material/tunnel values.
- `src/main/java/com/monpai/sailboatmod/network/packet/SyncRoadPlannerResultPacket.java`
  - Dedicated clientbound packet for finished planner candidates and option-selection state.
- `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristics.java`
  - Shared block classification helpers for “true surface” sampling.
- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlanOption.java`
  - Explicit planner option enum for `detour` and `bridge`.
- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlanCandidate.java`
  - Immutable planner output record used by the service and preview pipeline.
- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadDetourPlanner.java`
  - Land-first planner helper with short-span fallback rules.
- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadBridgePlanner.java`
  - Bridge-first planner helper with shoreline anchor and long-span bridge rules.
- `src/main/java/com/monpai/sailboatmod/road/model/BridgeConstructionStyle.java`
  - Distinguishes `SHORT_SPAN` from `PIER_BRIDGE`.
- `src/main/java/com/monpai/sailboatmod/road/model/BridgeSpanProfile.java`
  - Carries a `BridgeSpan` plus the chosen construction style.
- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadDemolitionSelector.java`
  - Scores nearby roads from hit position and view direction to lock one whole road.
- `src/test/java/com/monpai/sailboatmod/network/packet/SyncRoadPlannerResultPacketTest.java`
  - Packet encode/decode and client-result application tests.
- `src/test/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristicsTest.java`
  - Block classification tests for canopy/surface detection.
- `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
  - Planner orchestration, config rebuild, and failure handling tests.
- `src/test/java/com/monpai/sailboatmod/road/construction/bridge/BridgeBuilderTest.java`
  - Short-span vs pier-bridge build step tests.
- `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadDemolitionSelectorTest.java`
  - Tolerant road targeting tests.

### Existing Files To Modify

- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
  - Delegate planning to the new helpers, keep preview caches, rebuild preview from config, and use tolerant demolition selection.
- `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
  - Add UI phase tracking, latest planning result storage, and client-side planner config state.
- `src/main/java/com/monpai/sailboatmod/network/packet/SyncRoadPlannerPreviewPacket.java`
  - Restrict the packet to preview refresh only; stop auto-opening the option-selection screen.
- `src/main/java/com/monpai/sailboatmod/network/packet/ConfigureRoadPlannerPacket.java`
  - Keep packet data stable while routing confirm behavior through preview rebuild.
- `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
  - Register the new result packet.
- `src/main/java/com/monpai/sailboatmod/client/screen/RoadPlannerOptionSelectionScreen.java`
  - Enter config mode before opening the config screen.
- `src/main/java/com/monpai/sailboatmod/client/screen/RoadPlannerConfigScreen.java`
  - Seed fields from current config, reopen the correct previous state on cancel, and send confirm as “rebuild preview”.
- `src/main/java/com/monpai/sailboatmod/client/screen/RoadPlannerTargetSelectionScreen.java`
  - Only consume mouse wheel events while hovering the list.
- `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/FastHeightSampler.java`
  - Delegate surface-noise detection to the new heuristics helper.
- `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/AccurateHeightSampler.java`
  - Stop treating canopy blocks as real terrain surface.
- `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/TerrainSamplingCache.java`
  - Reuse the updated samplers for water checks and terrain height.
- `src/main/java/com/monpai/sailboatmod/road/construction/road/RoadBuilder.java`
  - Build roads from planner-provided bridge style profiles.
- `src/main/java/com/monpai/sailboatmod/road/construction/bridge/BridgeBuilder.java`
  - Branch between pierless short spans and pier bridges.
- `src/main/java/com/monpai/sailboatmod/road/construction/bridge/BridgePierBuilder.java`
  - Only create interior pier nodes for `PIER_BRIDGE`.
- `src/test/java/com/monpai/sailboatmod/client/RoadPlannerClientHooksTest.java`
  - Cover UI phase/result/config state behavior.

The implementation stays within the existing road-planner service and packet ecosystem, but the new helper classes prevent `ManualRoadPlannerService.java` from becoming even more monolithic.

## Task 1: Add A Dedicated Planning Result Packet And UI Phase Gating

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/SyncRoadPlannerResultPacket.java`
- Create: `src/test/java/com/monpai/sailboatmod/network/packet/SyncRoadPlannerResultPacketTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/SyncRoadPlannerPreviewPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/RoadPlannerOptionSelectionScreen.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/RoadPlannerClientHooksTest.java`

- [ ] **Step 1: Write the failing packet and UI-phase tests**

```java
@Test
void roundTripsPlanningResultPayload() {
    SyncRoadPlannerResultPacket packet = new SyncRoadPlannerResultPacket(
            "alpha",
            "beta",
            List.of(
                    new SyncRoadPlannerResultPacket.OptionEntry("detour", "Detour", 24, false),
                    new SyncRoadPlannerResultPacket.OptionEntry("bridge", "Bridge", 17, true)
            ),
            "bridge"
    );

    FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
    SyncRoadPlannerResultPacket.encode(packet, buffer);
    SyncRoadPlannerResultPacket decoded = SyncRoadPlannerResultPacket.decode(new FriendlyByteBuf(buffer.copy()));

    assertEquals(packet, decoded);
}

@Test
void planningResultMovesClientIntoOptionSelectionPhase() {
    RoadPlannerClientHooks.resetStateForTest();

    SyncRoadPlannerResultPacket.handleClientForTest(new SyncRoadPlannerResultPacket(
            "alpha",
            "beta",
            List.of(
                    new SyncRoadPlannerResultPacket.OptionEntry("detour", "Detour", 24, false),
                    new SyncRoadPlannerResultPacket.OptionEntry("bridge", "Bridge", 17, true)
            ),
            "detour"
    ));

    assertEquals(RoadPlannerClientHooks.UiPhase.OPTION_SELECTION, RoadPlannerClientHooks.uiPhaseForTest());
    assertEquals(2, RoadPlannerClientHooks.latestPlanningResultForTest().options().size());
}

@Test
void previewRefreshDuringConfigurationDoesNotReopenOptionSelection() {
    RoadPlannerClientHooks.resetStateForTest();
    RoadPlannerClientHooks.applyPlanningResultForTest(
            "alpha",
            "beta",
            List.of(
                    new RoadPlannerClientHooks.PreviewOption("detour", "Detour", 24, false),
                    new RoadPlannerClientHooks.PreviewOption("bridge", "Bridge", 17, true)
            ),
            "bridge"
    );
    RoadPlannerClientHooks.enterConfigModeForTest();
    RoadPlannerClientHooks.updatePreview(new RoadPlannerClientHooks.PreviewState(
            "alpha",
            "beta",
            List.of(),
            List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)),
            16,
            null,
            null,
            null,
            true,
            List.of(),
            "bridge",
            List.of()
    ));

    assertEquals(RoadPlannerClientHooks.UiPhase.CONFIGURATION, RoadPlannerClientHooks.uiPhaseForTest());
}
```

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.SyncRoadPlannerResultPacketTest" --tests "com.monpai.sailboatmod.client.RoadPlannerClientHooksTest"`

Expected: FAIL with missing `SyncRoadPlannerResultPacket`, missing `UiPhase`, and missing planning-result helpers in `RoadPlannerClientHooks`.

- [ ] **Step 3: Implement the packet, client phase state, and preview gating**

```java
public record SyncRoadPlannerResultPacket(String sourceTownName,
                                          String targetTownName,
                                          List<OptionEntry> options,
                                          String selectedOptionId) {
    public record OptionEntry(String optionId, String label, int pathNodeCount, boolean bridgeBacked) {
    }

    @OnlyIn(Dist.CLIENT)
    static void handleClientForTest(SyncRoadPlannerResultPacket packet) {
        handleClient(packet);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncRoadPlannerResultPacket packet) {
        RoadPlannerClientHooks.applyPlanningResult(
                packet.sourceTownName(),
                packet.targetTownName(),
                packet.options().stream()
                        .map(option -> new RoadPlannerClientHooks.PreviewOption(
                                option.optionId(),
                                option.label(),
                                option.pathNodeCount(),
                                option.bridgeBacked()
                        ))
                        .toList(),
                packet.selectedOptionId()
        );
    }
}
```

```java
public enum UiPhase {
    NONE,
    OPTION_SELECTION,
    CONFIGURATION,
    PREVIEW_CONFIRMATION
}

public record PlanningResultState(String sourceTownName,
                                  String targetTownName,
                                  List<PreviewOption> options,
                                  String selectedOptionId) {
}

private static UiPhase uiPhase = UiPhase.NONE;
private static PlanningResultState planningResultState;

public static void applyPlanningResultForTest(String sourceTownName,
                                              String targetTownName,
                                              List<PreviewOption> options,
                                              String selectedOptionId) {
    applyPlanningResult(sourceTownName, targetTownName, options, selectedOptionId);
}

public static void applyPlanningResult(String sourceTownName,
                                       String targetTownName,
                                       List<PreviewOption> options,
                                       String selectedOptionId) {
    planningResultState = new PlanningResultState(sourceTownName, targetTownName, options, selectedOptionId);
    uiPhase = UiPhase.OPTION_SELECTION;
}

public static UiPhase uiPhaseForTest() {
    return uiPhase;
}

public static PlanningResultState latestPlanningResultForTest() {
    return planningResultState;
}

public static void enterConfigModeForTest() {
    enterConfigMode();
}

public static void enterConfigMode() {
    uiPhase = UiPhase.CONFIGURATION;
}

public static void updatePreview(PreviewState preview) {
    previewState = preview;
    if (preview != null && uiPhase != UiPhase.CONFIGURATION) {
        uiPhase = UiPhase.PREVIEW_CONFIRMATION;
    }
    if (preview != null) {
        clearPlanningProgress();
    }
}
```

```java
private static void handleClient(SyncRoadPlannerPreviewPacket msg) {
    if (msg.ghostBlocks.isEmpty()) {
        RoadPlannerClientHooks.clearPreview();
        return;
    }
    RoadPlannerClientHooks.updatePreview(new RoadPlannerClientHooks.PreviewState(
            msg.sourceTownName(),
            msg.targetTownName(),
            msg.ghostBlocks().stream()
                    .map(block -> new RoadPlannerClientHooks.PreviewGhostBlock(block.pos(), block.state()))
                    .toList(),
            msg.pathNodes(),
            msg.pathNodeCount(),
            msg.startHighlightPos(),
            msg.endHighlightPos(),
            msg.focusPos(),
            msg.awaitingConfirmation(),
            msg.options().stream()
                    .map(option -> new RoadPlannerClientHooks.PreviewOption(option.optionId(), option.label(), option.pathNodeCount(), option.bridgeBacked()))
                    .toList(),
            msg.selectedOptionId(),
            msg.bridgeRanges().stream()
                    .map(range -> new RoadPlannerClientHooks.BridgeRange(range.startIndex(), range.endIndex()))
                    .toList()
    ));
}
```

```java
private void choose() {
    if (selectedIndex < 0 || selectedIndex >= options.size()) {
        return;
    }
    ModNetwork.CHANNEL.sendToServer(new SelectRoadPlannerPreviewOptionPacket(options.get(selectedIndex).optionId()));
    RoadPlannerClientHooks.enterConfigMode();
    net.minecraft.client.Minecraft.getInstance().setScreen(new RoadPlannerConfigScreen());
}
```

- [ ] **Step 4: Run the focused tests again to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.SyncRoadPlannerResultPacketTest" --tests "com.monpai.sailboatmod.client.RoadPlannerClientHooksTest"`

Expected: PASS with the new packet registered and preview refresh no longer reopening option selection.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/network/packet/SyncRoadPlannerResultPacket.java src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java src/main/java/com/monpai/sailboatmod/network/packet/SyncRoadPlannerPreviewPacket.java src/main/java/com/monpai/sailboatmod/network/ModNetwork.java src/main/java/com/monpai/sailboatmod/client/screen/RoadPlannerOptionSelectionScreen.java src/test/java/com/monpai/sailboatmod/network/packet/SyncRoadPlannerResultPacketTest.java src/test/java/com/monpai/sailboatmod/client/RoadPlannerClientHooksTest.java
git commit -m "Split road planner result flow from preview refresh"
```

## Task 2: Make Config Confirmation Rebuild The Selected Preview

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerConfig.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlanOption.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlanCandidate.java`
- Create: `src/main/java/com/monpai/sailboatmod/road/model/BridgeConstructionStyle.java`
- Create: `src/main/java/com/monpai/sailboatmod/road/model/BridgeSpanProfile.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/RoadPlannerConfigScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/ConfigureRoadPlannerPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`

- [ ] **Step 1: Write the failing config-rebuild tests**

```java
@Test
void plannerConfigNormalizesUnsupportedWidths() {
    ManualRoadPlannerConfig config = ManualRoadPlannerConfig.normalized(9, "sandstone", true);

    assertEquals(7, config.width());
    assertEquals("sandstone", config.materialPreset());
    assertTrue(config.tunnelEnabled());
}

@Test
void applyRoadConfigRebuildsSelectedPreviewUsingCurrentOption() {
    ManualRoadPlanCandidate detour = ManualRoadPlannerService.planCandidateForTest(
            ManualRoadPlanOption.DETOUR,
            List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)),
            List.of(),
            false
    );
    ManualRoadPlanCandidate bridge = ManualRoadPlannerService.planCandidateForTest(
            ManualRoadPlanOption.BRIDGE,
            List.of(new BlockPos(0, 64, 0), new BlockPos(0, 64, 1)),
            List.of(),
            true
    );

    ManualRoadPlanCandidate rebuilt = ManualRoadPlannerService.rebuildSelectedCandidateForTest(
            List.of(detour, bridge),
            "bridge",
            ManualRoadPlannerConfig.normalized(7, "sandstone", true)
    );

    assertEquals(ManualRoadPlanOption.BRIDGE, rebuilt.option());
    assertEquals(7, rebuilt.width());
    assertEquals("sandstone", rebuilt.materialPreset());
    assertTrue(rebuilt.tunnelEnabled());
}
```

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`

Expected: FAIL because `ManualRoadPlannerConfig`, `planCandidateForTest`, and `rebuildSelectedCandidateForTest(...)` do not exist.

- [ ] **Step 3: Implement normalized config state and rebuild-on-confirm**

```java
public enum ManualRoadPlanOption {
    DETOUR("detour", "Detour"),
    BRIDGE("bridge", "Bridge");

    private final String optionId;
    private final String label;

    ManualRoadPlanOption(String optionId, String label) {
        this.optionId = optionId;
        this.label = label;
    }

    public String optionId() {
        return optionId;
    }

    public String label() {
        return label;
    }
}
```

```java
public enum BridgeConstructionStyle {
    SHORT_SPAN,
    PIER_BRIDGE
}
```

```java
public record BridgeSpanProfile(BridgeSpan span, BridgeConstructionStyle style) {
    public BridgeSpanProfile {
        span = Objects.requireNonNull(span, "span");
        style = style == null ? BridgeConstructionStyle.PIER_BRIDGE : style;
    }
}
```

```java
public record ManualRoadPlanCandidate(ManualRoadPlanOption option,
                                      List<BlockPos> centerPath,
                                      List<BridgeSpanProfile> bridgeProfiles,
                                      int width,
                                      String materialPreset,
                                      boolean tunnelEnabled,
                                      boolean bridgeBacked,
                                      String failureReason) {
    public static ManualRoadPlanCandidate success(ManualRoadPlanOption option,
                                                  List<BlockPos> centerPath,
                                                  List<BridgeSpanProfile> bridgeProfiles,
                                                  int width,
                                                  String materialPreset,
                                                  boolean tunnelEnabled,
                                                  boolean bridgeBacked) {
        return new ManualRoadPlanCandidate(
                option,
                List.copyOf(centerPath),
                List.copyOf(bridgeProfiles),
                width,
                materialPreset,
                tunnelEnabled,
                bridgeBacked,
                ""
        );
    }

    public static ManualRoadPlanCandidate failure(ManualRoadPlanOption option, String failureReason) {
        return new ManualRoadPlanCandidate(option, List.of(), List.of(), 3, "auto", false, false, failureReason);
    }

    public boolean success() {
        return centerPath.size() >= 2 && failureReason.isBlank();
    }
}
```

```java
public record ManualRoadPlannerConfig(int width, String materialPreset, boolean tunnelEnabled) {
    public static ManualRoadPlannerConfig normalized(int width, String materialPreset, boolean tunnelEnabled) {
        int normalizedWidth = width <= 3 ? 3 : (width <= 5 ? 5 : 7);
        String normalizedMaterial = materialPreset == null || materialPreset.isBlank() ? "auto" : materialPreset;
        return new ManualRoadPlannerConfig(normalizedWidth, normalizedMaterial, tunnelEnabled);
    }
}
```

```java
private static final Map<UUID, ManualRoadPlannerConfig> PLAYER_ROAD_CONFIGS = new ConcurrentHashMap<>();

static ManualRoadPlanCandidate planCandidateForTest(ManualRoadPlanOption option,
                                                    List<BlockPos> centerPath,
                                                    List<BridgeSpanProfile> bridgeProfiles,
                                                    boolean bridgeBacked) {
    return ManualRoadPlanCandidate.success(option, centerPath, bridgeProfiles, 3, "auto", false, bridgeBacked);
}

static ManualRoadPlanCandidate rebuildSelectedCandidateForTest(List<ManualRoadPlanCandidate> candidates,
                                                               String selectedOptionId,
                                                               ManualRoadPlannerConfig config) {
    return rebuildSelectedCandidate(candidates, selectedOptionId, config);
}

private static ManualRoadPlanCandidate rebuildSelectedCandidate(List<ManualRoadPlanCandidate> candidates,
                                                                String selectedOptionId,
                                                                ManualRoadPlannerConfig config) {
    if (candidates == null || candidates.isEmpty()) {
        return null;
    }
    ManualRoadPlannerConfig normalized = config == null
            ? ManualRoadPlannerConfig.normalized(3, "auto", false)
            : ManualRoadPlannerConfig.normalized(config.width(), config.materialPreset(), config.tunnelEnabled());
    for (ManualRoadPlanCandidate candidate : candidates) {
        if (candidate.option().optionId().equalsIgnoreCase(selectedOptionId)) {
            return ManualRoadPlanCandidate.success(
                    candidate.option(),
                    candidate.centerPath(),
                    candidate.bridgeProfiles(),
                    normalized.width(),
                    normalized.materialPreset(),
                    normalized.tunnelEnabled(),
                    candidate.bridgeBacked()
            );
        }
    }
    ManualRoadPlanCandidate first = candidates.get(0);
    return ManualRoadPlanCandidate.success(
            first.option(),
            first.centerPath(),
            first.bridgeProfiles(),
            normalized.width(),
            normalized.materialPreset(),
            normalized.tunnelEnabled(),
            first.bridgeBacked()
    );
}

public static void applyRoadConfig(ServerPlayer player, ConfigureRoadPlannerPacket packet) {
    ManualRoadPlannerConfig config = ManualRoadPlannerConfig.normalized(
            packet.width(),
            packet.materialPreset(),
            packet.tunnelEnabled()
    );
    PLAYER_ROAD_CONFIGS.put(player.getUUID(), config);
    ItemStack stack = player.getMainHandItem();
    PlannedPreviewState preview = readyPreviewState(player, stack);
    if (preview == null || preview.candidates().isEmpty()) {
        return;
    }

    ManualRoadPlanCandidate rebuilt = rebuildSelectedCandidate(preview.candidates(), stack.getOrCreateTag().getString(TAG_PREVIEW_OPTION_ID), config);
    if (rebuilt == null) {
        return;
    }
    cachePreviewState(stack.getOrCreateTag(), rebuilt, previewHash(rebuilt.plan()), System.currentTimeMillis());
    sendPreview(player, rebuilt, preview.candidates(), true);
}
```

```java
private static ManualRoadPlannerConfig plannerConfig = ManualRoadPlannerConfig.normalized(3, "auto", false);

public static ManualRoadPlannerConfig currentPlannerConfig() {
    return plannerConfig;
}

public static void rememberPlannerConfig(ManualRoadPlannerConfig config) {
    plannerConfig = config == null
            ? ManualRoadPlannerConfig.normalized(3, "auto", false)
            : ManualRoadPlannerConfig.normalized(config.width(), config.materialPreset(), config.tunnelEnabled());
}

public RoadPlannerConfigScreen() {
    this(RoadPlannerClientHooks.currentPlannerConfig());
}

public RoadPlannerConfigScreen(ManualRoadPlannerConfig initialConfig) {
    super(Component.translatable("screen.sailboatmod.road_planner.config.title"));
    ManualRoadPlannerConfig normalized = initialConfig == null
            ? ManualRoadPlannerConfig.normalized(3, "auto", false)
            : ManualRoadPlannerConfig.normalized(initialConfig.width(), initialConfig.materialPreset(), initialConfig.tunnelEnabled());
    this.selectedWidth = normalized.width();
    this.selectedMaterialIndex = materialIndexFor(normalized.materialPreset());
    this.tunnelEnabled = normalized.tunnelEnabled();
}

private void confirm() {
    ManualRoadPlannerConfig config = ManualRoadPlannerConfig.normalized(
            selectedWidth,
            MATERIAL_OPTIONS[selectedMaterialIndex],
            tunnelEnabled
    );
    RoadPlannerClientHooks.rememberPlannerConfig(config);
    ModNetwork.CHANNEL.sendToServer(new ConfigureRoadPlannerPacket(
            config.width(),
            "default",
            config.materialPreset(),
            config.tunnelEnabled()
    ));
    onClose();
}
```

- [ ] **Step 4: Run the focused tests again to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest" --tests "com.monpai.sailboatmod.client.RoadPlannerClientHooksTest"`

Expected: PASS with normalized config values and the selected option rebuilding instead of silently discarding config.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerConfig.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlanOption.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlanCandidate.java src/main/java/com/monpai/sailboatmod/client/RoadPlannerClientHooks.java src/main/java/com/monpai/sailboatmod/client/screen/RoadPlannerConfigScreen.java src/main/java/com/monpai/sailboatmod/network/packet/ConfigureRoadPlannerPacket.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java
git commit -m "Rebuild selected road preview from planner config"
```

## Task 3: Replace Canopy-Based Anchors With True-Surface Sampling

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristics.java`
- Create: `src/test/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristicsTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/FastHeightSampler.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/AccurateHeightSampler.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/TerrainSamplingCache.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`

- [ ] **Step 1: Write the failing true-surface tests**

```java
@Test
void leavesAndLogsAreIgnoredAsSurfaceNoise() {
    assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.OAK_LEAVES.defaultBlockState()));
    assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.OAK_LOG.defaultBlockState()));
}

@Test
void solidGroundRemainsRoadBearingSurface() {
    assertTrue(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.GRASS_BLOCK.defaultBlockState()));
    assertTrue(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.STONE.defaultBlockState()));
    assertFalse(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.WATER.defaultBlockState()));
}
```

- [ ] **Step 2: Run the surface tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.road.pathfinding.cache.RoadSurfaceHeuristicsTest"`

Expected: FAIL because `RoadSurfaceHeuristics` does not exist.

- [ ] **Step 3: Implement the shared heuristics and move `surfaceAt(...)` onto the cache**

```java
public final class RoadSurfaceHeuristics {
    private RoadSurfaceHeuristics() {
    }

    public static boolean isIgnoredSurfaceNoise(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.TALL_FLOWERS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.VINE)
                || state.is(Blocks.SNOW)
                || state.is(Blocks.BAMBOO)
                || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.MUSHROOM_STEM)
                || state.is(Blocks.RED_MUSHROOM_BLOCK)
                || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || state.is(BlockTags.REPLACEABLE);
    }

    public static boolean isRoadBearingSurface(BlockState state) {
        return state != null
                && !state.isAir()
                && state.getFluidState().isEmpty()
                && !isIgnoredSurfaceNoise(state);
    }
}
```

```java
public int surfaceHeight(int x, int z) {
    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
    while (y > level.getMinBuildHeight()) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        if (!RoadSurfaceHeuristics.isIgnoredSurfaceNoise(state)) {
            return y;
        }
        y--;
    }
    return level.getMinBuildHeight();
}
```

```java
private static BlockPos surfaceAt(ServerLevel level, BlockPos pos) {
    if (level == null || pos == null) {
        return null;
    }
    TerrainSamplingCache cache = new TerrainSamplingCache(level, PathfindingConfig.SamplingPrecision.HIGH);
    return new BlockPos(pos.getX(), cache.getHeight(pos.getX(), pos.getZ()), pos.getZ());
}
```

- [ ] **Step 4: Run the focused tests again to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.road.pathfinding.cache.RoadSurfaceHeuristicsTest"`

Expected: PASS with canopy blocks ignored and solid terrain preserved as road-bearing surface.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristics.java src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/FastHeightSampler.java src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/AccurateHeightSampler.java src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/TerrainSamplingCache.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristicsTest.java
git commit -m "Use true terrain surface for manual road anchors"
```

## Task 4: Split `detour` And `bridge` Into Real Planner Helpers

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadDetourPlanner.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadBridgePlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`

- [ ] **Step 1: Write the failing dual-planner tests**

```java
@Test
void detourRejectsLongCrossingThatExceedsShortSpanThreshold() {
    assertFalse(ManualRoadDetourPlanner.allowsCrossingForTest(9));
    assertTrue(ManualRoadDetourPlanner.allowsCrossingForTest(8));
}

@Test
void bridgePlannerPromotesLongCrossingToPierBridge() {
    assertEquals(BridgeConstructionStyle.SHORT_SPAN, ManualRoadBridgePlanner.styleForCrossingForTest(8));
    assertEquals(BridgeConstructionStyle.PIER_BRIDGE, ManualRoadBridgePlanner.styleForCrossingForTest(12));
}

@Test
void bothPlannerReturnsOnlySuccessfulCandidates() {
    List<ManualRoadPlanCandidate> candidates = ManualRoadPlannerService.mergePlannerResultsForTest(
            ManualRoadPlanCandidate.success(ManualRoadPlanOption.DETOUR, List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)), List.of(), 3, "auto", false, false),
            ManualRoadPlanCandidate.failure(ManualRoadPlanOption.BRIDGE, "no_shoreline_anchor")
    );

    assertEquals(1, candidates.size());
    assertEquals(ManualRoadPlanOption.DETOUR, candidates.get(0).option());
}
```

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`

Expected: FAIL because the planner helper classes and merge helpers do not exist.

- [ ] **Step 3: Implement separate planner helpers and service orchestration**

```java
public final class ManualRoadDetourPlanner {
    private static final int SHORT_SPAN_LIMIT = 8;

    public static boolean allowsCrossingForTest(int crossingLength) {
        return crossingLength <= SHORT_SPAN_LIMIT;
    }

    public ManualRoadPlanCandidate plan(List<BlockPos> candidatePath,
                                        int longestCrossingLength,
                                        ManualRoadPlannerConfig config,
                                        List<BridgeSpanProfile> bridgeProfiles) {
        if (candidatePath == null || candidatePath.size() < 2) {
            return ManualRoadPlanCandidate.failure(ManualRoadPlanOption.DETOUR, "land_unreachable");
        }
        if (longestCrossingLength > SHORT_SPAN_LIMIT) {
            return ManualRoadPlanCandidate.failure(ManualRoadPlanOption.DETOUR, "crossing_too_long_for_detour");
        }
        ManualRoadPlannerConfig normalized = config == null
                ? ManualRoadPlannerConfig.normalized(3, "auto", false)
                : ManualRoadPlannerConfig.normalized(config.width(), config.materialPreset(), config.tunnelEnabled());
        return ManualRoadPlanCandidate.success(
                ManualRoadPlanOption.DETOUR,
                candidatePath,
                bridgeProfiles == null ? List.of() : bridgeProfiles,
                normalized.width(),
                normalized.materialPreset(),
                normalized.tunnelEnabled(),
                bridgeProfiles != null && !bridgeProfiles.isEmpty()
        );
    }
}
```

```java
public final class ManualRoadBridgePlanner {
    private static final int SHORT_SPAN_LIMIT = 8;

    public static BridgeConstructionStyle styleForCrossingForTest(int crossingLength) {
        return crossingLength <= SHORT_SPAN_LIMIT
                ? BridgeConstructionStyle.SHORT_SPAN
                : BridgeConstructionStyle.PIER_BRIDGE;
    }

    public ManualRoadPlanCandidate plan(List<BlockPos> candidatePath,
                                        int longestCrossingLength,
                                        ManualRoadPlannerConfig config,
                                        boolean shorelineAnchorsFound) {
        if (!shorelineAnchorsFound) {
            return ManualRoadPlanCandidate.failure(ManualRoadPlanOption.BRIDGE, "no_shoreline_anchor");
        }
        if (candidatePath == null || candidatePath.size() < 2) {
            return ManualRoadPlanCandidate.failure(ManualRoadPlanOption.BRIDGE, "no_bridge_corridor");
        }
        BridgeConstructionStyle style = styleForCrossingForTest(longestCrossingLength);
        BridgeSpanProfile profile = new BridgeSpanProfile(
                new BridgeSpan(0, candidatePath.size() - 1, candidatePath.get(0).getY(), candidatePath.get(0).getY() - 8),
                style
        );
        ManualRoadPlannerConfig normalized = config == null
                ? ManualRoadPlannerConfig.normalized(3, "auto", false)
                : ManualRoadPlannerConfig.normalized(config.width(), config.materialPreset(), config.tunnelEnabled());
        return ManualRoadPlanCandidate.success(
                ManualRoadPlanOption.BRIDGE,
                candidatePath,
                List.of(profile),
                normalized.width(),
                normalized.materialPreset(),
                normalized.tunnelEnabled(),
                true
        );
    }
}
```

```java
private static List<ManualRoadPlanCandidate> mergePlannerResults(ManualRoadPlanCandidate detour,
                                                                 ManualRoadPlanCandidate bridge) {
    List<ManualRoadPlanCandidate> merged = new ArrayList<>();
    if (detour != null && detour.success()) {
        merged.add(detour);
    }
    if (bridge != null && bridge.success()) {
        merged.add(bridge);
    }
    return List.copyOf(merged);
}

static List<ManualRoadPlanCandidate> mergePlannerResultsForTest(ManualRoadPlanCandidate detour,
                                                                ManualRoadPlanCandidate bridge) {
    return mergePlannerResults(detour, bridge);
}
```

- [ ] **Step 4: Run the focused tests again to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`

Expected: PASS with separate `detour` and `bridge` planner helpers wired into the service.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadDetourPlanner.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadBridgePlanner.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java
git commit -m "Split manual road planning into detour and bridge helpers"
```

## Task 5: Teach Bridge Construction About Short Spans And Pier Bridges

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/road/RoadBuilder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/bridge/BridgeBuilder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/road/construction/bridge/BridgePierBuilder.java`
- Create: `src/test/java/com/monpai/sailboatmod/road/construction/bridge/BridgeBuilderTest.java`

- [ ] **Step 1: Write the failing bridge-style tests**

```java
@Test
void shortSpanBuildSkipsPierPhaseSteps() {
    BridgeBuilder builder = new BridgeBuilder(new BridgeConfig());
    BridgeSpanProfile profile = new BridgeSpanProfile(
            new BridgeSpan(1, 3, 62, 55),
            BridgeConstructionStyle.SHORT_SPAN
    );

    List<BuildStep> steps = builder.build(
            profile,
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 64, 0),
                    new BlockPos(2, 64, 0),
                    new BlockPos(3, 64, 0),
                    new BlockPos(4, 64, 0)
            ),
            3,
            new RoadMaterial(Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.OAK_FENCE, Blocks.OAK_FENCE_GATE),
            0
    );

    assertTrue(steps.stream().noneMatch(step -> step.phase() == BuildPhase.PIER));
}

@Test
void pierBridgeBuildCreatesInteriorPierSteps() {
    BridgeBuilder builder = new BridgeBuilder(new BridgeConfig());
    BridgeSpanProfile profile = new BridgeSpanProfile(
            new BridgeSpan(1, 8, 62, 40),
            BridgeConstructionStyle.PIER_BRIDGE
    );

    List<BuildStep> steps = builder.build(
            profile,
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 64, 0),
                    new BlockPos(2, 64, 0),
                    new BlockPos(3, 64, 0),
                    new BlockPos(4, 64, 0),
                    new BlockPos(5, 64, 0),
                    new BlockPos(6, 64, 0),
                    new BlockPos(7, 64, 0),
                    new BlockPos(8, 64, 0),
                    new BlockPos(9, 64, 0)
            ),
            3,
            new RoadMaterial(Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.OAK_FENCE, Blocks.OAK_FENCE_GATE),
            0
    );

    assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.PIER));
}
```

- [ ] **Step 2: Run the bridge-style tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.road.construction.bridge.BridgeBuilderTest"`

Expected: FAIL because the new `BridgeBuilder.build(...)` overload and style-aware pier planning do not exist yet.

- [ ] **Step 3: Implement bridge style profiles and branch the builder**

public List<BuildStep> build(BridgeSpanProfile profile,
                             List<BlockPos> centerPath,
                             int width,
                             RoadMaterial material,
                             int startOrder) {
    if (profile.style() == BridgeConstructionStyle.SHORT_SPAN) {
        return buildShortSpan(profile.span(), centerPath, width, material, startOrder);
    }
    return buildPierBridge(profile.span(), centerPath, width, material, startOrder);
}

private List<BuildStep> buildShortSpan(BridgeSpan span,
                                       List<BlockPos> centerPath,
                                       int width,
                                       RoadMaterial material,
                                       int startOrder) {
    int deckY = span.waterSurfaceY() + config.getDeckHeight();
    List<BlockPos> bridgePath = centerPath.subList(span.startIndex(), span.endIndex() + 1);
    return deckPlacer.placeDeck(bridgePath, deckY, width, material, BridgeDeckPlacer.getDirection(centerPath, span.startIndex()), startOrder);
}
```

```java
public List<PierNode> planPierNodes(List<BlockPos> bridgePath,
                                    int deckY,
                                    int oceanFloorY,
                                    BridgeConstructionStyle style) {
    if (style == BridgeConstructionStyle.SHORT_SPAN) {
        return List.of();
    }
    List<PierNode> nodes = new ArrayList<>();
    int interval = Math.max(5, config.getPierInterval());
    for (int i = 0; i < bridgePath.size(); i += interval) {
        BlockPos pos = bridgePath.get(i);
        nodes.add(new PierNode(new BlockPos(pos.getX(), oceanFloorY, pos.getZ()), pos.getY(), deckY));
    }
    return List.copyOf(nodes);
}
```

- [ ] **Step 4: Run the bridge-style tests again to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.road.construction.bridge.BridgeBuilderTest"`

Expected: PASS with pier steps absent for short spans and present for pier bridges.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/road/model/BridgeConstructionStyle.java src/main/java/com/monpai/sailboatmod/road/model/BridgeSpanProfile.java src/main/java/com/monpai/sailboatmod/road/construction/road/RoadBuilder.java src/main/java/com/monpai/sailboatmod/road/construction/bridge/BridgeBuilder.java src/main/java/com/monpai/sailboatmod/road/construction/bridge/BridgePierBuilder.java src/test/java/com/monpai/sailboatmod/road/construction/bridge/BridgeBuilderTest.java
git commit -m "Add short-span and pier-bridge construction styles"
```

## Task 6: Replace Exact-Match Demolition With A Tolerant Selector

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadDemolitionSelector.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadDemolitionSelectorTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`

- [ ] **Step 1: Write the failing demolition-selector tests**

```java
@Test
void picksNearestRoadWhenHitPointFallsBesideTheCenterLine() {
    RoadNetworkRecord eastWest = ManualRoadDemolitionSelector.roadForTest(
            "manual|a|b",
            List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0))
    );

    RoadNetworkRecord selected = ManualRoadDemolitionSelector.selectRoadForTest(
            new Vec3(1.2D, 64.0D, 0.9D),
            new Vec3(1.0D, 0.0D, 0.0D),
            List.of(eastWest),
            2.0D
    );

    assertEquals("manual|a|b", selected.roadId());
}

@Test
void prefersRoadAlignedWithViewDirectionWhenTwoRoadsOverlapSelectionRadius() {
    RoadNetworkRecord eastWest = ManualRoadDemolitionSelector.roadForTest(
            "manual|a|b",
            List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0))
    );
    RoadNetworkRecord northSouth = ManualRoadDemolitionSelector.roadForTest(
            "manual|c|d",
            List.of(new BlockPos(1, 64, -1), new BlockPos(1, 64, 0), new BlockPos(1, 64, 1))
    );

    RoadNetworkRecord selected = ManualRoadDemolitionSelector.selectRoadForTest(
            new Vec3(1.0D, 64.0D, 0.4D),
            new Vec3(0.0D, 0.0D, 1.0D),
            List.of(eastWest, northSouth),
            2.0D
    );

    assertEquals("manual|c|d", selected.roadId());
}
```

- [ ] **Step 2: Run the demolition-selector tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadDemolitionSelectorTest"`

Expected: FAIL because `ManualRoadDemolitionSelector` does not exist.

- [ ] **Step 3: Implement the selector and route service targeting through it**

```java
public final class ManualRoadDemolitionSelector {
    private ManualRoadDemolitionSelector() {
    }

    public static RoadNetworkRecord roadForTest(String roadId, List<BlockPos> path) {
        return new RoadNetworkRecord(
                roadId,
                "nation:test",
                "town:test",
                "minecraft:overworld",
                "town:a",
                "town:b",
                path,
                0L,
                RoadNetworkRecord.SOURCE_TYPE_MANUAL
        );
    }

    public static RoadNetworkRecord selectRoadForTest(Vec3 hitPos,
                                                      Vec3 viewDir,
                                                      List<RoadNetworkRecord> roads,
                                                      double radius) {
        return selectRoad(hitPos, viewDir, roads, radius);
    }

    public static RoadNetworkRecord selectRoad(Vec3 hitPos,
                                               Vec3 viewDir,
                                               List<RoadNetworkRecord> roads,
                                               double radius) {
        RoadNetworkRecord best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (RoadNetworkRecord road : roads) {
            double score = scoreRoad(hitPos, viewDir, road, radius);
            if (score > bestScore) {
                bestScore = score;
                best = road;
            }
        }
        return best;
    }

    private static double scoreRoad(Vec3 hitPos, Vec3 viewDir, RoadNetworkRecord road, double radius) {
        double bestDistance = Double.MAX_VALUE;
        double bestAlignment = -1.0D;
        for (int i = 1; i < road.path().size(); i++) {
            BlockPos from = road.path().get(i - 1);
            BlockPos to = road.path().get(i);
            Vec3 midpoint = new Vec3((from.getX() + to.getX()) / 2.0D, from.getY(), (from.getZ() + to.getZ()) / 2.0D);
            double distance = midpoint.distanceTo(hitPos);
            if (distance <= radius && distance < bestDistance) {
                bestDistance = distance;
                Vec3 segmentDir = new Vec3(to.getX() - from.getX(), 0.0D, to.getZ() - from.getZ()).normalize();
                bestAlignment = Math.abs(segmentDir.dot(viewDir.normalize()));
            }
        }
        if (bestDistance == Double.MAX_VALUE) {
            return Double.NEGATIVE_INFINITY;
        }
        return (radius - bestDistance) * 10.0D + bestAlignment;
    }
}
```

```java
private static TargetedRoadPreview resolveLookedAtRoad(ServerPlayer player) {
    HitResult hitResult = player.pick(5.0D, 0.0F, false);
    if (hitResult.getType() != HitResult.Type.BLOCK) {
        return null;
    }
    BlockPos hitPos = ((BlockHitResult) hitResult).getBlockPos();
    ServerLevel level = player.serverLevel();
    NationSavedData data = NationSavedData.get(level);
    Vec3 hitCenter = Vec3.atCenterOf(hitPos);
    Vec3 look = player.getLookAngle();
    RoadNetworkRecord selected = ManualRoadDemolitionSelector.selectRoad(
            hitCenter,
            look,
            data.getRoadNetworks().stream()
                    .filter(road -> level.dimension().location().toString().equalsIgnoreCase(road.dimensionId()))
                    .toList(),
            2.5D
    );
    if (selected == null) {
        return null;
    }
    String remoteTownId = resolveRemoteTownId(selected);
    TownRecord remoteTown = data.getTown(remoteTownId);
    TownRecord localTown = data.getTown(selected.townId());
    return new TargetedRoadPreview(selected.roadId(), displayTownName(localTown), displayTownName(remoteTown), null);
}
```

- [ ] **Step 4: Run the demolition-selector tests again to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadDemolitionSelectorTest"`

Expected: PASS with nearby roads lockable even when the hit point is not exactly on the center line.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadDemolitionSelector.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadDemolitionSelectorTest.java
git commit -m "Add tolerant manual road demolition targeting"
```

## Task 7: Run Regression Verification And Manual Checks

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/client/RoadPlannerClientHooksTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/road/construction/bridge/BridgeBuilderTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadDemolitionSelectorTest.java`

- [ ] **Step 1: Add the final regression assertions that match the spec**

```java
@Test
void bothModeKeepsBridgeCandidateWhenDetourFailsForIslandRoute() {
    List<ManualRoadPlanCandidate> candidates = ManualRoadPlannerService.mergePlannerResultsForTest(
            ManualRoadPlanCandidate.failure(ManualRoadPlanOption.DETOUR, "land_unreachable"),
            ManualRoadPlanCandidate.success(ManualRoadPlanOption.BRIDGE, List.of(new BlockPos(0, 64, 0), new BlockPos(0, 64, 1)), List.of(), 5, "auto", false, true)
    );

    assertEquals(1, candidates.size());
    assertEquals(ManualRoadPlanOption.BRIDGE, candidates.get(0).option());
}

@Test
void shortSpanBridgeProfilesRemainPierless() {
    assertEquals(BridgeConstructionStyle.SHORT_SPAN, ManualRoadBridgePlanner.styleForCrossingForTest(4));
}

@Test
void longSpanDetourNeverUpgradesToPierBridge() {
    assertFalse(ManualRoadDetourPlanner.allowsCrossingForTest(12));
}
```

- [ ] **Step 2: Run the targeted automated regression suite**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.client.RoadPlannerClientHooksTest" --tests "com.monpai.sailboatmod.network.packet.SyncRoadPlannerResultPacketTest" --tests "com.monpai.sailboatmod.road.pathfinding.cache.RoadSurfaceHeuristicsTest" --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest" --tests "com.monpai.sailboatmod.road.construction.bridge.BridgeBuilderTest" --tests "com.monpai.sailboatmod.nation.service.ManualRoadDemolitionSelectorTest"`

Expected: PASS for all targeted tests.

- [ ] **Step 3: Run the compile gate**

Run: `.\gradlew.bat compileJava`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run the manual gameplay verification checklist**

Run:

```text
1. In a forest town edge, open manual road planner and confirm anchors/preview sit on ground, not canopy.
2. Plan a <=8 block stream crossing in detour mode and confirm the preview shows a pierless short span.
3. Plan a long water crossing in bridge mode and confirm the preview shows a pier bridge.
4. Plan island-to-mainland using bridge mode and confirm bridge candidate appears even if detour fails.
5. Enter demolish mode, look near an existing road edge or bridge deck, and confirm the whole road locks for removal.
6. Reopen config screen after changing width/material/tunnel and confirm the previous values are preselected.
```

Expected: Each scenario behaves exactly as described in the spec.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/monpai/sailboatmod/client/RoadPlannerClientHooksTest.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java src/test/java/com/monpai/sailboatmod/road/construction/bridge/BridgeBuilderTest.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadDemolitionSelectorTest.java
git commit -m "Verify rebuilt manual road planner regressions"
```
