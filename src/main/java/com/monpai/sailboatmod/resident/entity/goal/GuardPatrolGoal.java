package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.model.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Monster;

import java.util.EnumSet;
import java.util.List;

/**
 * Guard patrol and combat AI (inspired by MineColonies IGuardBuilding)
 */
public class GuardPatrolGoal extends Goal {
    private final ResidentEntity resident;
    private LivingEntity target;
    private int patrolCooldown;
    private BlockPos patrolCenter;

    private static final double PATROL_RANGE = 32.0;
    private static final double ATTACK_RANGE = 2.5;
    private static final int ATTACK_COOLDOWN = 20;
    private int attackTimer;

    public GuardPatrolGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Profession prof = resident.getProfession();
        return prof == Profession.GUARD || prof == Profession.SOLDIER;
    }

    @Override
    public void start() {
        patrolCenter = resident.getAssignedBed();
        if (patrolCenter == null || patrolCenter.equals(BlockPos.ZERO)) {
            patrolCenter = resident.blockPosition();
        }
        patrolCooldown = 0;
        target = null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        // Scan for threats
        if (target == null || !target.isAlive()) {
            target = findNearestThreat();
        }

        if (target != null && target.isAlive()) {
            // Combat mode
            double dist = resident.distanceToSqr(target);
            resident.getLookControl().setLookAt(target);

            if (dist > ATTACK_RANGE * ATTACK_RANGE) {
                resident.getNavigation().moveTo(target, 1.0);
            } else {
                resident.getNavigation().stop();
                attackTimer--;
                if (attackTimer <= 0) {
                    resident.doHurtTarget(target);
                    attackTimer = ATTACK_COOLDOWN;
                }
            }
        } else {
            // Patrol mode
            patrolCooldown--;
            if (patrolCooldown <= 0) {
                BlockPos patrolTarget = patrolCenter.offset(
                    resident.getRandom().nextInt(16) - 8, 0,
                    resident.getRandom().nextInt(16) - 8
                );
                resident.getNavigation().moveTo(patrolTarget.getX(), patrolTarget.getY(), patrolTarget.getZ(), 0.6);
                patrolCooldown = 200 + resident.getRandom().nextInt(200);
            }
        }
    }

    private LivingEntity findNearestThreat() {
        List<Monster> monsters = resident.level().getEntitiesOfClass(Monster.class,
            resident.getBoundingBox().inflate(PATROL_RANGE));
        Monster nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Monster m : monsters) {
            double d = resident.distanceToSqr(m);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = m;
            }
        }
        return nearest;
    }

    @Override
    public void stop() {
        target = null;
    }
}
