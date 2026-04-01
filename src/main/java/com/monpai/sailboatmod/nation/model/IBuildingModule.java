package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

/**
 * Interface for building behavior modules (inspired by MineColonies IBuildingModule)
 */
public interface IBuildingModule {
    /** Unique module ID */
    String getId();

    /** Called when the building is placed */
    default void onPlaced(PlacedStructureRecord building) {}

    /** Called when the building is upgraded */
    default void onUpgraded(PlacedStructureRecord building, int newLevel) {}

    /** Called when the building is demolished */
    default void onDemolished(PlacedStructureRecord building) {}

    /** Called every server tick for this building */
    default void onTick(PlacedStructureRecord building) {}

    /** Serialize module data */
    default CompoundTag save() { return new CompoundTag(); }

    /** Deserialize module data */
    default void load(CompoundTag tag) {}
}
