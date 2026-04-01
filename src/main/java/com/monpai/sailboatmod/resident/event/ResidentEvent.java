package com.monpai.sailboatmod.resident.event;

import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.core.BlockPos;
import net.minecraftforge.eventbus.api.Event;

/**
 * Base class for all resident lifecycle events (inspired by MineColonies)
 */
public abstract class ResidentEvent extends Event {
    private final String residentId;
    private final String townId;

    protected ResidentEvent(String residentId, String townId) {
        this.residentId = residentId;
        this.townId = townId;
    }

    public String getResidentId() { return residentId; }
    public String getTownId() { return townId; }

    /** Fired when a new resident is born/created */
    public static class Born extends ResidentEvent {
        private final ResidentRecord record;
        public Born(ResidentRecord record) {
            super(record.residentId(), record.townId());
            this.record = record;
        }
        public ResidentRecord getRecord() { return record; }
    }

    /** Fired when a resident dies */
    public static class Died extends ResidentEvent {
        private final BlockPos deathPos;
        private final String cause;
        public Died(String residentId, String townId, BlockPos deathPos, String cause) {
            super(residentId, townId);
            this.deathPos = deathPos;
            this.cause = cause;
        }
        public BlockPos getDeathPos() { return deathPos; }
        public String getCause() { return cause; }
    }

    /** Fired when a resident gets a new job */
    public static class JobChanged extends ResidentEvent {
        private final String oldJob;
        private final String newJob;
        public JobChanged(String residentId, String townId, String oldJob, String newJob) {
            super(residentId, townId);
            this.oldJob = oldJob;
            this.newJob = newJob;
        }
        public String getOldJob() { return oldJob; }
        public String getNewJob() { return newJob; }
    }

    /** Fired when a resident levels up */
    public static class LevelUp extends ResidentEvent {
        private final int newLevel;
        public LevelUp(String residentId, String townId, int newLevel) {
            super(residentId, townId);
            this.newLevel = newLevel;
        }
        public int getNewLevel() { return newLevel; }
    }
}
