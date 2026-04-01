package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.model.BuildingConstructionRecord;
import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.pathfinding.MaplePathfinder;
import com.monpai.sailboatmod.resident.pathfinding.goal.NearGoal;
import com.monpai.sailboatmod.resident.service.BuildingConstructionService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/**
 * Goal for builders to work on construction sites (inspired by MineColonies)
 */
public class BuilderJobGoal extends Goal {
    private final ResidentEntity resident;
    private BuildingConstructionRecord construction;
    private List<BlockPos> path;
    private int pathIndex;
    private int workTimer;
    private BuildState state;

    private enum BuildState {
        FIND_WORK, PATHFIND, BUILDING, COMPLETE
    }

    public BuilderJobGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE));
        this.state = BuildState.FIND_WORK;
    }

    @Override
    public boolean canUse() {
        if (!(resident.level() instanceof ServerLevel level)) return false;
        if (!Profession.BUILDER.id().equals(resident.getProfession())) return false;

        // Find construction assigned to this resident
        for (BuildingConstructionRecord c : BuildingConstructionService.getAllConstructions(level)) {
            if (c.assignedBuilders().contains(resident.getResidentId()) && !c.isComplete()) {
                construction = c;
                return true;
            }
        }
        return false;
    }

    @Override
    public void start() {
        if (construction == null) return;
        path = MaplePathfinder.findPath(resident.level(), resident.blockPosition(),
            new NearGoal(construction.position(), 5), 3000, 1000);
        pathIndex = 0;
        workTimer = 0;
    }

    @Override
    public boolean canContinueToUse() {
        return construction != null && !construction.isComplete();
    }

    @Override
    public void tick() {
        if (!(resident.level() instanceof ServerLevel level)) return;

        switch (state) {
            case FIND_WORK -> {
                if (findConstruction(level)) {
                    state = BuildState.PATHFIND;
                }
            }
            case PATHFIND -> {
                if (path != null && pathIndex < path.size()) {
                    BlockPos next = path.get(pathIndex);
                    if (resident.blockPosition().distSqr(next) < 4) {
                        pathIndex++;
                    } else {
                        resident.getNavigation().moveTo(next.getX(), next.getY(), next.getZ(), 0.7);
                    }
                } else {
                    state = BuildState.BUILDING;
                }
            }
            case BUILDING -> {
                if (construction != null && resident.blockPosition().distSqr(construction.position()) < 64) {
                    workTimer++;

                    // Show particles
                    if (workTimer % 10 == 0) {
                        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                            construction.position().getX() + 0.5,
                            construction.position().getY() + 1,
                            construction.position().getZ() + 0.5,
                            3, 0.5, 0.5, 0.5, 0.1);
                    }

                    if (workTimer >= 100) {
                        BuildingConstructionService.updateProgress(level, construction.buildingId(),
                            construction.currentLayer() + 1);
                        workTimer = 0;

                        if (construction.isComplete()) {
                            state = BuildState.COMPLETE;
                        }
                    }
                }
            }
            case COMPLETE -> {
                construction = null;
                state = BuildState.FIND_WORK;
            }
        }
    }

    private boolean findConstruction(ServerLevel level) {
        for (BuildingConstructionRecord c : BuildingConstructionService.getAllConstructions(level)) {
            if (c.assignedBuilders().contains(resident.getResidentId()) && !c.isComplete()) {
                construction = c;
                path = MaplePathfinder.findPath(level, resident.blockPosition(),
                    new NearGoal(c.position(), 5), 3000, 1000);
                pathIndex = 0;
                return true;
            }
        }
        return false;
    }

    @Override
    public void stop() {
        construction = null;
        path = null;
        workTimer = 0;
    }
}
