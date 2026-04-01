package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.pathfinding.MaplePathfinder;
import com.monpai.sailboatmod.resident.pathfinding.goal.NearGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/**
 * Goal for wandering around home area
 */
public class WanderAroundHomeGoal extends Goal {
    private final ResidentEntity resident;
    private List<BlockPos> path;
    private int pathIndex;
    private int cooldown;

    public WanderAroundHomeGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        return resident.getAssignedBed() != null && !resident.getAssignedBed().equals(BlockPos.ZERO);
    }

    @Override
    public void start() {
        BlockPos home = resident.getAssignedBed();
        if (home == null) return;

        // Random position near home
        BlockPos target = home.offset(
            resident.getRandom().nextInt(32) - 16,
            0,
            resident.getRandom().nextInt(32) - 16
        );

        path = MaplePathfinder.findPath(resident.level(), resident.blockPosition(), new NearGoal(target, 2), 1000, 500);
        pathIndex = 0;
    }

    @Override
    public boolean canContinueToUse() {
        return path != null && pathIndex < path.size();
    }

    @Override
    public void tick() {
        if (path == null || pathIndex >= path.size()) return;

        BlockPos next = path.get(pathIndex);
        if (resident.blockPosition().distSqr(next) < 4) {
            pathIndex++;
        } else {
            resident.getNavigation().moveTo(next.getX(), next.getY(), next.getZ(), 0.6);
        }
    }

    @Override
    public void stop() {
        path = null;
        cooldown = 100 + resident.getRandom().nextInt(100);
    }
}
