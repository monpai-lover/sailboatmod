package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.event.BuildingEvent;
import com.monpai.sailboatmod.nation.model.BuildingUpgradeRequirement;
import com.monpai.sailboatmod.nation.model.PlacedStructureRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;

/**
 * Service for managing building upgrades
 */
public class BuildingUpgradeService {

    public static boolean canUpgrade(ServerLevel level, String structureId) {
        NationSavedData data = NationSavedData.get(level);
        PlacedStructureRecord structure = data.getStructure(structureId);
        return structure != null && structure.canUpgrade();
    }

    public static BuildingUpgradeRequirement getUpgradeRequirement(PlacedStructureRecord structure) {
        if (structure == null) return new BuildingUpgradeRequirement(0, 0, 0);
        return BuildingUpgradeRequirement.forLevel(structure.structureType(), structure.buildingLevel());
    }

    public static boolean upgradeBuilding(ServerLevel level, String structureId) {
        NationSavedData data = NationSavedData.get(level);
        PlacedStructureRecord structure = data.getStructure(structureId);

        if (structure == null || !structure.canUpgrade()) {
            return false;
        }

        // Create upgraded structure
        PlacedStructureRecord upgraded = new PlacedStructureRecord(
            structure.structureId(),
            structure.nationId(),
            structure.townId(),
            structure.structureType(),
            structure.dimensionId(),
            structure.originPos(),
            structure.sizeW(),
            structure.sizeH(),
            structure.sizeD(),
            structure.placedAt(),
            structure.buildingLevel() + 1,
            structure.isBuilt(),
            structure.rotation()
        );

        data.putStructure(upgraded);
        MinecraftForge.EVENT_BUS.post(new BuildingEvent.Upgraded(
            structure.structureId(), structure.townId(), structure.origin(),
            structure.buildingLevel(), structure.buildingLevel() + 1));
        return true;
    }
}
