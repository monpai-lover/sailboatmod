package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerDraftPersistenceTest {
    @TempDir Path tempDir;

    @Test
    void savesAndLoadsDraftBySession() {
        UUID sessionId = UUID.randomUUID();
        RoadPlannerDraftPersistence persistence = new RoadPlannerDraftPersistence(tempDir.toFile());
        RoadPlannerDraftStore.Draft draft = new RoadPlannerDraftStore.Draft(
                List.of(new BlockPos(1, 64, 2), new BlockPos(9, 70, 12)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR)
        );

        persistence.save(sessionId, draft);

        RoadPlannerDraftStore.Draft loaded = persistence.load(sessionId).orElseThrow();
        assertEquals(draft.nodes(), loaded.nodes());
        assertEquals(draft.segmentTypes(), loaded.segmentTypes());
    }
}
