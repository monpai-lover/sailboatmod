package com.monpai.sailboatmod.nation.event;

import net.minecraft.core.BlockPos;
import net.minecraftforge.eventbus.api.Event;

/**
 * Building lifecycle events (inspired by MineColonies IBuildingEventsModule)
 */
public abstract class BuildingEvent extends Event {
    private final String structureId;
    private final String townId;
    private final BlockPos position;

    protected BuildingEvent(String structureId, String townId, BlockPos position) {
        this.structureId = structureId;
        this.townId = townId;
        this.position = position;
    }

    public String getStructureId() { return structureId; }
    public String getTownId() { return townId; }
    public BlockPos getPosition() { return position; }

    /** Fired when a building is placed */
    public static class Placed extends BuildingEvent {
        private final String structureType;
        public Placed(String structureId, String townId, BlockPos position, String structureType) {
            super(structureId, townId, position);
            this.structureType = structureType;
        }
        public String getStructureType() { return structureType; }
    }

    /** Fired when a building construction completes */
    public static class Built extends BuildingEvent {
        public Built(String structureId, String townId, BlockPos position) {
            super(structureId, townId, position);
        }
    }

    /** Fired when a building is upgraded */
    public static class Upgraded extends BuildingEvent {
        private final int oldLevel;
        private final int newLevel;
        public Upgraded(String structureId, String townId, BlockPos position, int oldLevel, int newLevel) {
            super(structureId, townId, position);
            this.oldLevel = oldLevel;
            this.newLevel = newLevel;
        }
        public int getOldLevel() { return oldLevel; }
        public int getNewLevel() { return newLevel; }
    }

    /** Fired when a building is demolished */
    public static class Demolished extends BuildingEvent {
        public Demolished(String structureId, String townId, BlockPos position) {
            super(structureId, townId, position);
        }
    }
}
