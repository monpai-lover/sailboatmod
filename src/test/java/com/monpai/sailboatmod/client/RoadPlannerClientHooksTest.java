package com.monpai.sailboatmod.client;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

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
                ""
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
                "bridge"
        ));

        RoadPlannerClientHooks.PreviewState preview = RoadPlannerClientHooks.previewState();
        assertTrue(preview != null && preview.options().size() == 2);
        assertTrue(preview != null && preview.pathNodes().size() == 2);
        assertTrue(preview.options().get(1).bridgeBacked());
        assertTrue("bridge".equals(preview.selectedOptionId()));
    }
}
