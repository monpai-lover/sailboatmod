package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.block.WorkstationBlock;
import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.model.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;

public class FindJobGoal extends Goal {
    private final ResidentEntity resident;
    private BlockPos targetWorkstation;

    public FindJobGoal(ResidentEntity resident) {
        this.resident = resident;
    }

    @Override
    public boolean canUse() {
        return resident.getProfession() == Profession.UNEMPLOYED && targetWorkstation == null;
    }

    @Override
    public void start() {
        BlockPos pos = resident.blockPosition();
        for (int x = -64; x <= 64; x++) {
            for (int y = -16; y <= 16; y++) {
                for (int z = -64; z <= 64; z++) {
                    BlockPos check = pos.offset(x, y, z);
                    BlockState state = resident.level().getBlockState(check);
                    if (state.getBlock() instanceof WorkstationBlock ws) {
                        targetWorkstation = check;
                        resident.getNavigation().moveTo(check.getX(), check.getY(), check.getZ(), 1.0);
                        return;
                    }
                }
            }
        }
    }

    @Override
    public void tick() {
        if (targetWorkstation != null && resident.blockPosition().distSqr(targetWorkstation) < 4) {
            BlockState state = resident.level().getBlockState(targetWorkstation);
            if (state.getBlock() instanceof WorkstationBlock ws) {
                resident.setProfession(ws.getProfession());
            }
            targetWorkstation = null;
        }
    }
}
