package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.resident.army.*;
import com.monpai.sailboatmod.resident.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Highest priority: execute commander orders (rally, march, retreat).
 * Mount & Blade style: soldiers move to their assigned formation slot.
 */
public class FollowCommandGoal extends Goal {
    private final SoldierEntity soldier;
    private BlockPos targetPos;
    private int recalcCooldown;

    public FollowCommandGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        ArmyRecord army = soldier.getArmy();
        if (army == null) return false;
        if (army.state() == ArmyState.ATTACKING && soldier.getTarget() != null && soldier.getTarget().isAlive()) return false;
        return army.state() == ArmyState.RALLYING
                || army.state() == ArmyState.MARCHING
                || army.state() == ArmyState.ATTACKING
                || army.state() == ArmyState.RETREATING;
    }

    @Override
    public void start() {
        recalcCooldown = 0;
    }

    @Override
    public void tick() {
        if (--recalcCooldown > 0) return;
        recalcCooldown = 10; // recalc every 10 ticks

        ArmyRecord army = soldier.getArmy();
        if (army == null) return;

        int idx = ArmyCommandManager.getSoldierIndex(army, soldier.getResidentId());
        if (idx < 0) return;

        targetPos = ArmyCommandManager.getSoldierTargetPos(army, idx);
        double dist = soldier.distanceToSqr(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D);
        if (dist > 2.25D) {
            soldier.getNavigation().moveTo(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D, 1.0D);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        soldier.getNavigation().stop();
    }
}
