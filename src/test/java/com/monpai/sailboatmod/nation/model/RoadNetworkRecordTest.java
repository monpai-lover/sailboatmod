package com.monpai.sailboatmod.nation.model;

import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadNetworkRecordTest {
    @Test
    void saveLoadPreservesDensePathDisplayPathAndSharedSpans() {
        List<BlockPos> densePath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0));
        List<BlockPos> displayPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(3, 64, 0));
        RoadPlannerSharedRoadSpan sharedSpan = new RoadPlannerSharedRoadSpan(
                "existing-road",
                1,
                3,
                densePath.get(1),
                densePath.get(3),
                RoadPlannerMergeScope.OWN_NATION,
                RoadPlannerSharedRoadSpan.Role.END_MERGE);

        RoadNetworkRecord road = new RoadNetworkRecord(
                "road-a",
                "nation-a",
                "town-a",
                "minecraft:overworld",
                "planner:start:0,64,0",
                "roadnode:existing-road:3",
                densePath,
                displayPath,
                List.of(sharedSpan),
                200L,
                100L,
                "uuid-a",
                "Builder",
                RoadNetworkRecord.SOURCE_TYPE_MANUAL,
                "Alpha",
                "Beta");

        RoadNetworkRecord loaded = RoadNetworkRecord.load(road.save());

        assertEquals(densePath, loaded.path());
        assertEquals(displayPath, loaded.displayPath());
        assertEquals(List.of(sharedSpan), loaded.sharedSpans());
    }

    @Test
    void loadingOldDensePathWithoutDisplayPathBuildsSparseDisplayFallback() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", "legacy-road");
        tag.putString("NationId", "nation-a");
        tag.putString("TownId", "");
        tag.putString("Dim", "minecraft:overworld");
        tag.putString("A", "planner:start:0,64,0");
        tag.putString("B", "planner:end:4,64,2");
        tag.putLong("UpdatedAt", 100L);
        ListTag pathTag = new ListTag();
        for (BlockPos pos : List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 1),
                new BlockPos(4, 64, 2))) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", pos.asLong());
            pathTag.add(entry);
        }
        tag.put("Path", pathTag);

        RoadNetworkRecord loaded = RoadNetworkRecord.load(tag);

        assertEquals(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(4, 64, 2)), loaded.displayPath());
    }

    @Test
    void saveLoadPreservesRouteTownIds() {
        RoadNetworkRecord road = new RoadNetworkRecord(
                "road-town-link",
                "nation-a",
                "source-town",
                "minecraft:overworld",
                "town:source-town",
                "town:target-town",
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                null,
                List.of(),
                200L,
                100L,
                "creator-uuid",
                "Builder",
                RoadNetworkRecord.SOURCE_TYPE_MANUAL,
                "Alpha",
                "Beta",
                "source-town",
                "target-town");

        RoadNetworkRecord loaded = RoadNetworkRecord.load(road.save());

        assertEquals("source-town", loaded.routeSourceTownId());
        assertEquals("target-town", loaded.routeTargetTownId());
    }
}
