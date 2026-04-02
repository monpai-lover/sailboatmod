package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.model.BuildingConstructionRecord;
import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.service.BuildingConstructionService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

public class BuildGoal extends Goal {
    private final ResidentEntity resident;
    private BuildingConstructionRecord targetConstruction;
    private int buildTicks = 0;
    private static final int TICKS_PER_LAYER = 200;
    private static final int SEARCH_RADIUS = 64;

    public BuildGoal(ResidentEntity resident) {
        this.resident = resident;
    }

    @Override
    public boolean canUse() {
        boolean isBuilder = resident.getProfession() == Profession.BUILDER;

        // Find nearest construction
        BuildingConstructionRecord nearest = null;
        double minDist = Double.MAX_VALUE;

        for (BuildingConstructionRecord construction : BuildingConstructionService.getAllConstructions(resident.level())) {
            if (construction.isComplete()) continue;
            boolean requiresBuilder = com.monpai.sailboatmod.resident.service.BuildingComplexity.requiresBuilder(construction.structureType());
            if (requiresBuilder && !isBuilder) continue;

            double dist = resident.blockPosition().distSqr(construction.position());
            if (dist < minDist) {
                minDist = dist;
                nearest = construction;
            }
        }

        targetConstruction = nearest;
        return targetConstruction != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (targetConstruction == null) return false;
        boolean requiresBuilder = com.monpai.sailboatmod.resident.service.BuildingComplexity.requiresBuilder(targetConstruction.structureType());
        boolean isBuilder = resident.getProfession() == Profession.BUILDER;
        return !requiresBuilder || isBuilder;
    }

    @Override
    public void start() {
        if (targetConstruction != null) {
            BuildingConstructionService.assignBuilder(resident.level(), targetConstruction.buildingId(), resident.getUUID().toString());
        }
    }

    @Override
    public void tick() {
        if (targetConstruction == null) {
            stop();
            return;
        }

        // Refresh construction data (record is immutable, need to fetch latest)
        targetConstruction = BuildingConstructionService.getConstruction(resident.level(), targetConstruction.buildingId());
        if (targetConstruction == null || targetConstruction.isComplete()) {
            stop();
            return;
        }

        // Navigate to construction site
        BlockPos buildPos = targetConstruction.position();
        if (resident.blockPosition().distSqr(buildPos) > 16) {
            resident.getNavigation().moveTo(buildPos.getX(), buildPos.getY(), buildPos.getZ(), 1.0);
            return;
        }

        // Build layer
        buildTicks++;
        int workerCount = targetConstruction.assignedBuilders().size();
        int speedMultiplier = Math.max(1, workerCount * (resident.getProfession() == Profession.BUILDER ?
            com.monpai.sailboatmod.resident.service.BuildingComplexity.getBuilderSpeedBonus() : 1));
        if (buildTicks >= TICKS_PER_LAYER / speedMultiplier) {
            buildTicks = 0;
            int nextLayer = targetConstruction.currentLayer() + 1;

            // Place blocks for this layer
            if (resident.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                com.monpai.sailboatmod.resident.service.StructureBuilder.placeLayer(
                    serverLevel, targetConstruction.position(), targetConstruction.structureType(), nextLayer
                );
            }

            BuildingConstructionService.updateProgress(
                resident.level(), targetConstruction.buildingId(), nextLayer
            );
        }
    }

    @Override
    public void stop() {
        targetConstruction = null;
        buildTicks = 0;
    }
}
