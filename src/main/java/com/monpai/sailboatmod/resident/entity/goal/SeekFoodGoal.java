package com.monpai.sailboatmod.resident.entity.goal;

import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Goal for seeking food when hungry
 */
public class SeekFoodGoal extends Goal {
    private final ResidentEntity resident;
    private int eatTimer;

    public SeekFoodGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(resident.level() instanceof ServerLevel level)) return false;

        ResidentSavedData data = ResidentSavedData.get(level);
        ResidentRecord record = data.getResident(resident.getResidentId());

        return record != null && record.isHungry();
    }

    @Override
    public void start() {
        eatTimer = 100;
    }

    @Override
    public boolean canContinueToUse() {
        return eatTimer > 0;
    }

    @Override
    public void tick() {
        eatTimer--;

        if (eatTimer <= 0 && resident.level() instanceof ServerLevel level) {
            ResidentSavedData data = ResidentSavedData.get(level);
            ResidentRecord record = data.getResident(resident.getResidentId());

            if (record != null) {
                ResidentRecord fed = record.withHunger(ResidentRecord.MAX_HUNGER);
                data.putResident(fed);
            }
        }
    }

    @Override
    public void stop() {
        eatTimer = 0;
    }
}
