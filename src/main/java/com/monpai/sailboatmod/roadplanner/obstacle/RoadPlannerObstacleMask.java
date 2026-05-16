package com.monpai.sailboatmod.roadplanner.obstacle;

import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.PlacedStructureRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RoadPlannerObstacleMask {
    private static final RoadPlannerObstacleMask EMPTY = new RoadPlannerObstacleMask(Set.of());

    private final Set<Long> blockedColumns;

    private RoadPlannerObstacleMask(Set<Long> blockedColumns) {
        this.blockedColumns = blockedColumns == null || blockedColumns.isEmpty()
                ? Set.of()
                : Set.copyOf(blockedColumns);
    }

    public static RoadPlannerObstacleMask empty() {
        return EMPTY;
    }

    public static RoadPlannerObstacleMask fromColumns(Collection<Long> columns) {
        if (columns == null || columns.isEmpty()) {
            return empty();
        }
        LinkedHashSet<Long> blocked = new LinkedHashSet<>();
        for (Long column : columns) {
            if (column != null) {
                blocked.add(column);
            }
        }
        return blocked.isEmpty() ? empty() : new RoadPlannerObstacleMask(blocked);
    }

    public Set<Long> blockedColumns() {
        return blockedColumns;
    }

    public static RoadPlannerObstacleMask fromNationData(ServerLevel level, NationSavedData data) {
        if (level == null || data == null) {
            return empty();
        }
        String dimensionId = level.dimension().location().toString();
        Set<Long> blocked = new HashSet<>(structureColumns(data.getPlacedStructures(), dimensionId));
        List<BlockPos> townCores = data.getTowns().stream()
                .filter(town -> town != null
                        && town.hasCore()
                        && dimensionId.equalsIgnoreCase(town.coreDimension()))
                .map(town -> BlockPos.of(town.corePos()))
                .toList();
        List<BlockPos> nationCores = data.getNations().stream()
                .filter(nation -> nation != null
                        && nation.hasCore()
                        && dimensionId.equalsIgnoreCase(nation.coreDimension()))
                .map(nation -> BlockPos.of(nation.corePos()))
                .toList();
        blocked.addAll(coreColumns(townCores, nationCores));
        return fromColumns(blocked);
    }

    public static Set<Long> coreColumns(Collection<BlockPos> townCores, Collection<BlockPos> nationCores) {
        HashSet<BlockPos> cores = new HashSet<>();
        if (townCores != null) {
            for (BlockPos core : townCores) {
                if (core != null) {
                    cores.add(core);
                }
            }
        }
        if (nationCores != null) {
            for (BlockPos core : nationCores) {
                if (core != null) {
                    cores.add(core);
                }
            }
        }
        return RoadCoreExclusion.collectExcludedColumns(cores, RoadCoreExclusion.DEFAULT_RADIUS);
    }

    public static Set<Long> structureColumns(Collection<PlacedStructureRecord> structures, String dimensionId) {
        if (structures == null || structures.isEmpty() || dimensionId == null || dimensionId.isBlank()) {
            return Set.of();
        }
        HashSet<Long> blocked = new HashSet<>();
        for (PlacedStructureRecord structure : structures) {
            if (structure == null || !dimensionId.equalsIgnoreCase(structure.dimensionId())) {
                continue;
            }
            BlockPos origin = structure.origin();
            int minX = origin.getX() - 1;
            int minZ = origin.getZ() - 1;
            int maxX = origin.getX() + structure.sizeW();
            int maxZ = origin.getZ() + structure.sizeD();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    blocked.add(RoadCoreExclusion.columnKey(x, z));
                }
            }
        }
        return blocked.isEmpty() ? Set.of() : Set.copyOf(blocked);
    }

    public boolean isBlocked(int x, int z) {
        return blockedColumns.contains(RoadCoreExclusion.columnKey(x, z));
    }

    public boolean isBlocked(BlockPos pos) {
        return pos != null && isBlocked(pos.getX(), pos.getZ());
    }

    public RoadPlannerObstacleMask withoutEndpoints(BlockPos... endpoints) {
        if (blockedColumns.isEmpty() || endpoints == null || endpoints.length == 0) {
            return this;
        }
        LinkedHashSet<Long> unblocked = new LinkedHashSet<>(blockedColumns);
        for (BlockPos endpoint : endpoints) {
            if (endpoint != null) {
                unblocked.remove(RoadCoreExclusion.columnKey(endpoint.getX(), endpoint.getZ()));
            }
        }
        return fromColumns(unblocked);
    }

    public boolean pathTouchesBlockedColumn(List<BlockPos> path) {
        if (path == null || path.isEmpty() || blockedColumns.isEmpty()) {
            return false;
        }
        BlockPos previous = null;
        for (BlockPos current : path) {
            if (current == null) {
                continue;
            }
            if (isBlocked(current)) {
                return true;
            }
            if (previous != null && segmentTouchesBlockedColumn(previous, current)) {
                return true;
            }
            previous = current;
        }
        return false;
    }

    private boolean segmentTouchesBlockedColumn(BlockPos from, BlockPos to) {
        int x = from.getX();
        int z = from.getZ();
        int dx = Math.abs(to.getX() - x);
        int dz = Math.abs(to.getZ() - z);
        int stepX = Integer.compare(to.getX(), x);
        int stepZ = Integer.compare(to.getZ(), z);
        int ix = 0;
        int iz = 0;
        while (ix < dx || iz < dz) {
            long decision = (1L + 2L * ix) * dz - (1L + 2L * iz) * dx;
            if (decision == 0L) {
                if (ix < dx && isBlocked(x + stepX, z)) {
                    return true;
                }
                if (iz < dz && isBlocked(x, z + stepZ)) {
                    return true;
                }
                if (ix < dx) {
                    x += stepX;
                    ix++;
                }
                if (iz < dz) {
                    z += stepZ;
                    iz++;
                }
            } else if (decision < 0L) {
                x += stepX;
                ix++;
            } else {
                z += stepZ;
                iz++;
            }
            if (isBlocked(x, z)) {
                return true;
            }
        }
        return false;
    }
}
