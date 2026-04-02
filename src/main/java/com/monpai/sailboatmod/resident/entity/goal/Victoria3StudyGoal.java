package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.block.SchoolBlock;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.model.EducationLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Victoria 3-inspired education system
 * - Requires town/nation funding
 * - Gradual literacy improvement
 * - Progressive education levels
 */
public class Victoria3StudyGoal extends Goal {
    private final ResidentEntity resident;
    private BlockPos targetSchool;
    private int studyTicks = 0;

    // Education costs per day (in game currency)
    private static final double PRIMARY_COST = 0.5;
    private static final double SECONDARY_COST = 1.0;
    private static final double UNIVERSITY_COST = 2.0;

    // Literacy thresholds for education levels
    private static final float PRIMARY_THRESHOLD = 0.3f;
    private static final float SECONDARY_THRESHOLD = 0.6f;
    private static final float UNIVERSITY_THRESHOLD = 0.9f;

    // Ticks per day (20 ticks/sec * 60 sec/min * 20 min/day = 24000)
    private static final int TICKS_PER_DAY = 24000;
    private static final float LITERACY_GAIN_PER_DAY = 0.05f;

    public Victoria3StudyGoal(ResidentEntity resident) {
        this.resident = resident;
    }

    @Override
    public boolean canUse() {
        // Can study if not at max education level
        if (resident.getEducationLevel() == EducationLevel.UNIVERSITY && resident.getLiteracy() >= 1.0f) {
            return false;
        }
        // High learning residents can self-study without school
        return resident.getLearning() >= 15 || targetSchool != null;
    }

    @Override
    public void start() {
        if (targetSchool == null) {
            findNearestSchool();
        }
    }

    private void findNearestSchool() {
        BlockPos pos = resident.blockPosition();
        for (int x = -64; x <= 64; x++) {
            for (int y = -16; y <= 16; y++) {
                for (int z = -64; z <= 64; z++) {
                    BlockPos check = pos.offset(x, y, z);
                    BlockState state = resident.level().getBlockState(check);
                    if (state.getBlock() instanceof SchoolBlock) {
                        targetSchool = check;
                        resident.getNavigation().moveTo(check.getX(), check.getY(), check.getZ(), 1.0);
                        return;
                    }
                }
            }
        }
    }

    @Override
    public void tick() {
        if (!(resident.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        // High learning residents can self-study without school
        boolean selfStudy = resident.getLearning() >= 15 && targetSchool == null;

        if (!selfStudy) {
            if (targetSchool == null) return;
            // Check if resident is near school
            if (resident.blockPosition().distSqr(targetSchool) > 16) {
                return;
            }
        }

        studyTicks++;

        // Process education once per day (self-study is slower)
        int ticksRequired = selfStudy ? TICKS_PER_DAY * 2 : TICKS_PER_DAY;
        if (studyTicks >= ticksRequired) {
            studyTicks = 0;
            processEducation(serverLevel);
        }
    }

    private void processEducation(ServerLevel level) {
        String townId = resident.getTownId();
        if (townId == null || townId.isBlank()) {
            return;
        }

        NationSavedData nationData = NationSavedData.get(level);
        TownRecord town = nationData.getTown(townId);
        if (town == null) {
            return;
        }

        // Determine education cost based on current level
        double cost = getEducationCost();

        // Check if town/nation has enough funds
        // TODO: Implement proper economy check with town/nation treasury
        // For now, assume education is always available
        boolean canAfford = true; // nationData.canAffordEducation(town, cost);

        if (!canAfford) {
            // No education if no funding
            return;
        }

        // Deduct cost
        // TODO: Implement proper cost deduction
        // nationData.deductEducationCost(town, cost);

        // Increase literacy
        float currentLiteracy = resident.getLiteracy();
        float newLiteracy = Math.min(1.0f, currentLiteracy + LITERACY_GAIN_PER_DAY);
        resident.setLiteracy(newLiteracy);

        // Update education level based on literacy
        updateEducationLevel(newLiteracy);
    }

    private double getEducationCost() {
        return switch (resident.getEducationLevel()) {
            case ILLITERATE, PRIMARY -> PRIMARY_COST;
            case SECONDARY -> SECONDARY_COST;
            case UNIVERSITY -> UNIVERSITY_COST;
        };
    }

    private void updateEducationLevel(float literacy) {
        EducationLevel current = resident.getEducationLevel();

        if (literacy >= UNIVERSITY_THRESHOLD && current.tier() < EducationLevel.UNIVERSITY.tier()) {
            resident.setEducationLevel(EducationLevel.UNIVERSITY);
        } else if (literacy >= SECONDARY_THRESHOLD && current.tier() < EducationLevel.SECONDARY.tier()) {
            resident.setEducationLevel(EducationLevel.SECONDARY);
        } else if (literacy >= PRIMARY_THRESHOLD && current.tier() < EducationLevel.PRIMARY.tier()) {
            resident.setEducationLevel(EducationLevel.PRIMARY);
        }

        // Update legacy educated flag for compatibility
        resident.setEducated(literacy >= PRIMARY_THRESHOLD);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse() && targetSchool != null;
    }

    @Override
    public void stop() {
        targetSchool = null;
        studyTicks = 0;
    }
}
