package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.pathfinding.MaplePathfinder;
import com.monpai.sailboatmod.resident.pathfinding.goal.BlockGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/**
 * Goal for sleeping at home during night
 */
public class SleepAtHomeGoal extends Goal {
    private final ResidentEntity resident;
    private List<BlockPos> path;
    private int pathIndex;

    public SleepAtHomeGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        long time = resident.level().getDayTime() % 24000;
        boolean isNight = time >= 13000 && time < 23000;
        return isNight && resident.getAssignedBed() != null && !resident.getAssignedBed().equals(BlockPos.ZERO);
    }

    @Override
    public void start() {
        BlockPos bed = resident.getAssignedBed();
        if (bed == null) return;

        path = MaplePathfinder.findPath(resident.level(), resident.blockPosition(), new BlockGoal(bed), 2000, 1000);
        pathIndex = 0;
    }

    @Override
    public boolean canContinueToUse() {
        long time = resident.level().getDayTime() % 24000;
        boolean isNight = time >= 13000 && time < 23000;
        return isNight && path != null && pathIndex < path.size();
    }

    @Override
    public void tick() {
        if (path == null || pathIndex >= path.size()) return;

        BlockPos next = path.get(pathIndex);
        if (resident.blockPosition().distSqr(next) < 4) {
            pathIndex++;
        } else {
            resident.getNavigation().moveTo(next.getX(), next.getY(), next.getZ(), 0.8);
        }
    }

    @Override
    public void stop() {
        path = null;
    }
}
