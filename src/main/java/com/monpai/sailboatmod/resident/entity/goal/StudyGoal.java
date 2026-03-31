package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.block.SchoolBlock;
import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;

public class StudyGoal extends Goal {
    private final ResidentEntity resident;
    private BlockPos targetSchool;
    private int studyDays = 0;
    private static final int DAYS_TO_GRADUATE = 7;

    public StudyGoal(ResidentEntity resident) {
        this.resident = resident;
    }

    @Override
    public boolean canUse() {
        return !resident.isEducated() && targetSchool == null;
    }

    @Override
    public void start() {
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
        if (targetSchool != null && resident.blockPosition().distSqr(targetSchool) < 4) {
            studyDays++;
            if (studyDays >= DAYS_TO_GRADUATE) {
                resident.setEducated(true);
                targetSchool = null;
            }
        }
    }
}
