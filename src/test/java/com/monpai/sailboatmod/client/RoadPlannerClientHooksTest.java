package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.nation.service.ManualRoadPlannerConfig;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerClientHooksTest {
    @Test
    void clearPreviewRemovesCurrentPreviewState() {
        RoadPlannerClientHooks.resetStateForTest();
        RoadPlannerClientHooks.updatePreview(new RoadPlannerClientHooks.PreviewState(
                "alpha",
                "beta",
                List.of(),
                List.of(),
                0,
                null,
                null,
                null,
                false,
                List.of(),
                "",
                List.of()
        ));

        RoadPlannerClientHooks.clearPreview();

        assertNull(RoadPlannerClientHooks.previewState());
    }

    @Test
    void staleProgressEntriesExpireAfterTimeout() {
        RoadPlannerClientHooks.resetStateForTest();
        RoadPlannerClientHooks.updateProgress(List.of(
                new RoadPlannerClientHooks.ProgressState("manual|town:a|town:b", "alpha", "beta", BlockPos.ZERO, 40, 2)
        ));
        RoadPlannerClientHooks.setLastProgressSyncAtMsForTest(System.currentTimeMillis() - 5000L);

        assertTrue(RoadPlannerClientHooks.activeProgress().isEmpty());
    }

    @Test
    void previewStateKeepsSelectableOptions() {
        RoadPlannerClientHooks.resetStateForTest();
        RoadPlannerClientHooks.updatePreview(new RoadPlannerClientHooks.PreviewState(
                "alpha",
                "beta",
                List.of(),
                List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1)),
                12,
                null,
                null,
                null,
                true,
                List.of(
                        new RoadPlannerClientHooks.PreviewOption("detour", "Detour", 28, false),
                        new RoadPlannerClientHooks.PreviewOption("bridge", "Bridge", 17, true)
                ),
                "bridge",
                List.of()
        ));

        RoadPlannerClientHooks.PreviewState preview = RoadPlannerClientHooks.previewState();
        assertTrue(preview != null && preview.options().size() == 2);
        assertTrue(preview != null && preview.pathNodes().size() == 2);
        assertTrue(preview.options().get(1).bridgeBacked());
        assertTrue("bridge".equals(preview.selectedOptionId()));
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

    @Test
    void plannerConfigMemoryNormalizesAndPersistsSelections() {
        RoadPlannerClientHooks.resetStateForTest();

        RoadPlannerClientHooks.rememberPlannerConfig(ManualRoadPlannerConfig.normalized(9, "sandstone", true));

        ManualRoadPlannerConfig config = RoadPlannerClientHooks.currentPlannerConfig();
        assertEquals(7, config.width());
        assertEquals("sandstone", config.materialPreset());
        assertTrue(config.tunnelEnabled());
    }

    @Test
    void newerPlanningRequestReplacesOlderRequest() {
        RoadPlannerClientHooks.resetStateForTest();
        RoadPlannerClientHooks.updatePlanningProgressForTest(
                new com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket(
                        4L,
                        "alpha",
                        "beta",
                        "sampling_terrain",
                        "采样地形",
                        18,
                        30,
                        com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket.Status.RUNNING
                ),
                1_000L
        );
        RoadPlannerClientHooks.updatePlanningProgressForTest(
                new com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket(
                        5L,
                        "alpha",
                        "gamma",
                        "trying_bridge",
                        "桥路尝试",
                        80,
                        60,
                        com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket.Status.RUNNING
                ),
                1_050L
        );

        RoadPlannerClientHooks.PlanningProgressState state = RoadPlannerClientHooks.activePlanningProgressForTest(1_200L);
        assertEquals(5L, state.requestId());
        assertEquals("gamma", state.targetTownName());
    }

    @Test
    void stalePlanningRequestIsIgnored() {
        RoadPlannerClientHooks.resetStateForTest();
        RoadPlannerClientHooks.updatePlanningProgressForTest(
                new com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket(
                        6L,
                        "alpha",
                        "gamma",
                        "trying_bridge",
                        "桥路尝试",
                        80,
                        60,
                        com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket.Status.RUNNING
                ),
                1_000L
        );
        RoadPlannerClientHooks.updatePlanningProgressForTest(
                new com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket(
                        5L,
                        "alpha",
                        "beta",
                        "sampling_terrain",
                        "采样地形",
                        18,
                        30,
                        com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket.Status.RUNNING
                ),
                1_050L
        );

        RoadPlannerClientHooks.PlanningProgressState state = RoadPlannerClientHooks.activePlanningProgressForTest(1_200L);
        assertEquals(6L, state.requestId());
        assertEquals("trying_bridge", state.stageKey());
    }

    @Test
    void terminalPlanningStateClearsAfterHoldWindow() {
        RoadPlannerClientHooks.resetStateForTest();
        RoadPlannerClientHooks.updatePlanningProgressForTest(
                new com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket(
                        7L,
                        "alpha",
                        "beta",
                        "building_preview",
                        "生成预览",
                        100,
                        100,
                        com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket.Status.FAILED
                ),
                2_000L
        );

        assertTrue(RoadPlannerClientHooks.activePlanningProgressForTest(2_500L) != null);
        assertNull(RoadPlannerClientHooks.activePlanningProgressForTest(5_500L));
    }

    @Test
    void smoothingNeverExceedsLatestAuthoritativeServerPercent() {
        RoadPlannerClientHooks.resetStateForTest();
        RoadPlannerClientHooks.updatePlanningProgressForTest(
                new com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket(
                        8L,
                        "alpha",
                        "beta",
                        "sampling_terrain",
                        "采样地形",
                        20,
                        40,
                        com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket.Status.RUNNING
                ),
                3_000L
        );

        RoadPlannerClientHooks.PlanningProgressState state = RoadPlannerClientHooks.activePlanningProgressForTest(3_100L);
        assertTrue(state != null && state.displayPercent() <= 20);
    }
}
