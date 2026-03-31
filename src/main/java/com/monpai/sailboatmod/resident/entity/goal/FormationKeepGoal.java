package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.resident.army.*;
import com.monpai.sailboatmod.resident.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Keep formation position when idle or defending.
 */
public class FormationKeepGoal extends Goal {
    private final SoldierEntity soldier;
    private final double speed;
    private int recalcCooldown;

    public FormationKeepGoal(SoldierEntity soldier, double speed) {
        this.soldier = soldier;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        ArmyRecord army = soldier.getArmy();
        if (army == null) return false;
        return army.state() == ArmyState.IDLE
                || army.state() == ArmyState.DEFENDING;
    }

    @Override
    public void tick() {
        if (--recalcCooldown > 0) return;
        recalcCooldown = 20;

        ArmyRecord army = soldier.getArmy();
        if (army == null) return;
        int idx = ArmyCommandManager.getSoldierIndex(army, soldier.getResidentId());
        if (idx < 0) return;

        BlockPos slot = ArmyCommandManager.getSoldierTargetPos(army, idx);
        double dist = soldier.distanceToSqr(slot.getX() + 0.5, slot.getY(), slot.getZ() + 0.5);
        if (dist > 4.0) { // more than 2 blocks away
            soldier.getNavigation().moveTo(slot.getX() + 0.5, slot.getY(), slot.getZ() + 0.5, speed);
        }
    }

    @Override
    public boolean canContinueToUse() { return canUse(); }
}
