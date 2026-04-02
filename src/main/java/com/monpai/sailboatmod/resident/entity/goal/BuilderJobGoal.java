package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.nation.service.StructureConstructionManager;
import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.model.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Builder residents now follow the active structure-construction runtime rather than
 * mutating layer counters directly. The construction site owns placement order and
 * block application; builders only move into range and report active work time.
 */
public class BuilderJobGoal extends Goal {
    private static final int SEARCH_RADIUS = 96;
    private static final int RETARGET_INTERVAL_TICKS = 20;
    private static final double WORK_RANGE_SQR = 25.0D;

    private final ResidentEntity resident;
    private StructureConstructionManager.WorkerSiteAssignment assignment;
    private int retargetCooldown;
    private int workAnimationCooldown;

    public BuilderJobGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(resident.level() instanceof ServerLevel level)) {
            return false;
        }
        if (resident.getProfession() != Profession.BUILDER) {
            return false;
        }
        assignment = StructureConstructionManager.findNearestSite(level, resident.blockPosition(), SEARCH_RADIUS);
        return assignment != null;
    }

    @Override
    public void start() {
        retargetCooldown = 0;
        workAnimationCooldown = 0;
        moveTowardAssignment();
    }

    @Override
    public boolean canContinueToUse() {
        if (!(resident.level() instanceof ServerLevel level) || assignment == null) {
            return false;
        }
        return resident.getProfession() == Profession.BUILDER
                && StructureConstructionManager.hasActiveConstruction(level, assignment.jobId());
    }

    @Override
    public void tick() {
        if (!(resident.level() instanceof ServerLevel level)) {
            return;
        }

        if (retargetCooldown-- <= 0) {
            retargetCooldown = RETARGET_INTERVAL_TICKS;
            StructureConstructionManager.WorkerSiteAssignment refreshed =
                    StructureConstructionManager.getSiteAssignment(
                            level,
                            assignment == null ? "" : assignment.jobId(),
                            resident.blockPosition()
                    );
            if (refreshed == null) {
                refreshed = StructureConstructionManager.findNearestSite(level, resident.blockPosition(), SEARCH_RADIUS);
            }
            assignment = refreshed;
        }

        if (assignment == null) {
            stop();
            return;
        }

        BlockPos approachPos = assignment.approachPos();
        BlockPos focusPos = assignment.focusPos();
        if (resident.blockPosition().distSqr(approachPos) > WORK_RANGE_SQR) {
            resident.getNavigation().moveTo(approachPos.getX() + 0.5D, approachPos.getY(), approachPos.getZ() + 0.5D, 0.9D);
            return;
        }

        resident.getNavigation().stop();
        resident.getLookControl().setLookAt(
                focusPos.getX() + 0.5D,
                focusPos.getY() + 0.5D,
                focusPos.getZ() + 0.5D
        );

        StructureConstructionManager.reportWorkerActivity(
                level,
                assignment.jobId(),
                resident.getResidentId(),
                resident.blockPosition(),
                true
        );

        if (workAnimationCooldown-- <= 0) {
            workAnimationCooldown = 10;
            resident.swing(InteractionHand.MAIN_HAND);
            level.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    focusPos.getX() + 0.5D,
                    focusPos.getY() + 0.8D,
                    focusPos.getZ() + 0.5D,
                    2,
                    0.2D, 0.15D, 0.2D,
                    0.01D
            );
        }
    }

    @Override
    public void stop() {
        resident.getNavigation().stop();
        if (assignment != null) {
            StructureConstructionManager.releaseWorker(assignment.jobId(), resident.getResidentId());
        }
        assignment = null;
        retargetCooldown = 0;
        workAnimationCooldown = 0;
    }

    private void moveTowardAssignment() {
        if (assignment == null) {
            return;
        }
        BlockPos approachPos = assignment.approachPos();
        resident.getNavigation().moveTo(approachPos.getX() + 0.5D, approachPos.getY(), approachPos.getZ() + 0.5D, 0.9D);
    }
}
