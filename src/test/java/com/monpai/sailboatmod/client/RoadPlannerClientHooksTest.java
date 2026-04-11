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
                null,
                null,
                null,
                false
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
}
