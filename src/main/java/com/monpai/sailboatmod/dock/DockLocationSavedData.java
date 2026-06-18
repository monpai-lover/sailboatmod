package com.monpai.sailboatmod.dock;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 码头坐标的轻量持久化清单(仅维度+坐标),供「创建自动航线」开 UI 时枚举候选码头——
 * 不依赖区块加载,远处码头也能被列出。筛选(权限/泊位/连通)仍在 packet 里对每个候选实时进行。
 *
 * <p>登记:DockBlockEntity.onLoad(PORT-only,惰性覆盖老存档)。
 * 移除:仅在 DockBlock.onRemove 真正破坏时(区块卸载不触发 Block#onRemove,故不会误删远处卸载的码头)。
 * 故意不持久化码头→国家归属:那部分开 UI 时临时从方块实体读、用完即丢。
 */
public class DockLocationSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_dock_locations";

    // key = dimensionId + "|" + packedPos,全局一个码头一条
    private final Map<String, DockLocation> docks = new LinkedHashMap<>();

    public static DockLocationSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new DockLocationSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(DockLocationSavedData::load, DockLocationSavedData::new, DATA_NAME);
    }

    public static DockLocationSavedData load(CompoundTag tag) {
        DockLocationSavedData data = new DockLocationSavedData();
        ListTag list = tag.getList("Docks", Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            if (raw instanceof CompoundTag compound) {
                DockLocation dock = DockLocation.load(compound);
                if (!dock.dimensionId().isBlank()) {
                    data.docks.put(key(dock.dimensionId(), dock.packedPos()), dock);
                }
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (DockLocation dock : docks.values()) {
            list.add(dock.save());
        }
        tag.put("Docks", list);
        return tag;
    }

    public void register(String dimensionId, long packedPos) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return;
        }
        String key = key(dimensionId, packedPos);
        if (docks.containsKey(key)) {
            return; // 幂等:区块反复加载不反复写盘
        }
        docks.put(key, new DockLocation(dimensionId, packedPos));
        setDirty();
    }

    public void unregister(String dimensionId, long packedPos) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return;
        }
        if (docks.remove(key(dimensionId, packedPos)) != null) {
            setDirty();
        }
    }

    public List<BlockPos> positionsIn(String dimensionId) {
        List<BlockPos> out = new ArrayList<>();
        if (dimensionId == null || dimensionId.isBlank()) {
            return out;
        }
        for (DockLocation dock : docks.values()) {
            if (dimensionId.equals(dock.dimensionId())) {
                out.add(dock.pos());
            }
        }
        return out;
    }

    private static String key(String dimensionId, long packedPos) {
        return dimensionId + "|" + packedPos;
    }

    public record DockLocation(String dimensionId, long packedPos) {
        public DockLocation {
            dimensionId = dimensionId == null ? "" : dimensionId.trim();
        }

        public BlockPos pos() {
            return BlockPos.of(packedPos);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("DimensionId", dimensionId);
            tag.putLong("PackedPos", packedPos);
            return tag;
        }

        public static DockLocation load(CompoundTag tag) {
            return new DockLocation(
                    tag.contains("DimensionId") ? tag.getString("DimensionId") : "",
                    tag.contains("PackedPos") ? tag.getLong("PackedPos") : 0L);
        }
    }
}
