package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.resident.army.ArmyRecord;
import com.monpai.sailboatmod.resident.army.ArmyState;
import com.monpai.sailboatmod.resident.army.Stance;
import com.monpai.sailboatmod.resident.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * Melee attack goal that respects army stance.
 */
public class SoldierAttackGoal extends MeleeAttackGoal {
    private final SoldierEntity soldier;

    public SoldierAttackGoal(SoldierEntity soldier, double speed) {
        super(soldier, speed, true);
        this.soldier = soldier;
    }

    @Override
    public boolean canUse() {
        ArmyRecord army = soldier.getArmy();
        if (army != null && army.stance() == Stance.HOLD) return false;
        if (army != null && army.state() == ArmyState.RETREATING) return false;
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        ArmyRecord army = soldier.getArmy();
        if (army != null && army.state() == ArmyState.RETREATING) return false;
        return super.canContinueToUse();
    }
}
