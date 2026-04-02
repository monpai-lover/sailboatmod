package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.nation.model.DockTownBindingRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class DockTownResolver {
    private DockTownResolver() {
    }

    public static String dockId(Level level, BlockPos dockPos) {
        if (level == null || dockPos == null) {
            return "";
        }
        return level.dimension().location() + "|" + dockPos.asLong();
    }

    public static String getOwningTown(Level level, String dockId) {
        DockTownBindingRecord binding = resolve(level, dockId);
        return binding == null ? "" : binding.townId();
    }

    public static DockTownBindingRecord resolve(Level level, String dockId) {
        if (level == null || dockId == null || dockId.isBlank()) {
            return null;
        }
        int separator = dockId.lastIndexOf('|');
        if (separator <= 0 || separator >= dockId.length() - 1) {
            return null;
        }
        String dimensionId = dockId.substring(0, separator);
        long packedPos;
        try {
            packedPos = Long.parseLong(dockId.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return null;
        }
        Level targetLevel = resolveLevel(level, dimensionId);
        return targetLevel == null ? null : resolve(targetLevel, BlockPos.of(packedPos));
    }

    public static DockTownBindingRecord resolve(Level level, BlockPos dockPos) {
        if (level == null || dockPos == null) {
            return null;
        }
        DockBlockEntity dock = level.getBlockEntity(dockPos) instanceof DockBlockEntity be ? be : null;
        String dockName = dock == null ? "" : dock.getDockName();
        String townId = dock == null ? "" : dock.getTownId();
        String nationId = dock == null ? "" : dock.getNationId();
        if (townId.isBlank()) {
            TownRecord town = TownService.getTownAt(level, dockPos);
            if (town != null) {
                townId = town.townId();
                nationId = nationId.isBlank() ? town.nationId() : nationId;
                if (dock != null) {
                    if (!townId.equals(dock.getTownId())) {
                        dock.setTownId(townId);
                    }
                    if (!nationId.equals(dock.getNationId())) {
                        dock.setNationId(nationId);
                    }
                }
            }
        }
        return new DockTownBindingRecord(
                dockId(level, dockPos),
                level.dimension().location().toString(),
                dockPos,
                dockName,
                townId,
                nationId
        );
    }

    public static DockTownBindingRecord resolve(Level level, DockBlockEntity dock) {
        return dock == null ? null : resolve(level == null ? dock.getLevel() : level, dock.getBlockPos());
    }

    public static String resolveTownForArrival(Level level, BlockPos dockPos) {
        DockTownBindingRecord binding = resolve(level, dockPos);
        return binding == null ? "" : binding.townId();
    }

    public static String resolveTownForArrival(Level level, BlockPos dockPos, String declaredTownId) {
        if (declaredTownId != null && !declaredTownId.isBlank()) {
            return declaredTownId.trim();
        }
        return resolveTownForArrival(level, dockPos);
    }

    public static String resolveTownForSource(Level level, BlockPos dockPos, String declaredTownId) {
        return resolveTownForArrival(level, dockPos, declaredTownId);
    }

    private static Level resolveLevel(Level currentLevel, String dimensionId) {
        if (currentLevel == null || dimensionId == null || dimensionId.isBlank()) {
            return null;
        }
        if (dimensionId.equals(currentLevel.dimension().location().toString())) {
            return currentLevel;
        }
        if (currentLevel instanceof ServerLevel serverLevel && serverLevel.getServer() != null) {
            for (ServerLevel candidate : serverLevel.getServer().getAllLevels()) {
                if (dimensionId.equals(candidate.dimension().location().toString())) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
