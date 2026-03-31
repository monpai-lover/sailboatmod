package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.resident.army.ArmyRecord;
import com.monpai.sailboatmod.resident.army.ArmyState;
import com.monpai.sailboatmod.resident.army.Stance;
import com.monpai.sailboatmod.resident.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;

import java.util.function.Predicate;

/**
 * Scans for threats based on army stance.
 * AGGRESSIVE: target any hostile mob or enemy nation soldier within range.
 * DEFENSIVE: only target entities that attacked us.
 * HOLD: no scanning.
 */
public class ThreatScanGoal extends NearestAttackableTargetGoal<LivingEntity> {
    private final SoldierEntity soldier;

    public ThreatScanGoal(SoldierEntity soldier) {
        super(soldier, LivingEntity.class, 10, true, false, e -> isValidTarget(soldier, e));
        this.soldier = soldier;
    }

    @Override
    public boolean canUse() {
        ArmyRecord army = soldier.getArmy();
        if (army == null) return false;
        if (army.stance() == Stance.HOLD) return false;
        if (army.state() == ArmyState.RETREATING) return false;

        // Defensive: only react if we were hurt
        if (army.stance() == Stance.DEFENSIVE) {
            return soldier.getLastHurtByMob() != null && soldier.getLastHurtByMob().isAlive();
        }
        // Aggressive or attacking state: scan for targets
        return army.stance() == Stance.AGGRESSIVE || army.state() == ArmyState.ATTACKING;
    }

    @Override
    public void start() {
        ArmyRecord army = soldier.getArmy();
        if (army != null && army.stance() == Stance.DEFENSIVE && soldier.getLastHurtByMob() != null) {
            soldier.setTarget(soldier.getLastHurtByMob());
            return;
        }
        super.start();
    }

    private static boolean isValidTarget(SoldierEntity soldier, LivingEntity target) {
        if (target == null || !target.isAlive()) return false;
        if (target instanceof Monster) return true; // always target hostile mobs

        // Target enemy soldiers (different nation)
        if (target instanceof SoldierEntity other) {
            String ourNation = soldier.getTownId(); // simplified; should check nation
            String theirNation = other.getTownId();
            return !ourNation.isBlank() && !theirNation.isBlank() && !ourNation.equals(theirNation);
        }
        return false;
    }
}
