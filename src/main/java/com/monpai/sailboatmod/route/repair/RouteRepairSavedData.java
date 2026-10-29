package com.monpai.sailboatmod.route.repair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 码头区块未加载时暂存的航线修正;码头 onLoad 时取出套用。每码头最多 32 笔,超过丢最旧。 */
public class RouteRepairSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_route_repairs";
    public static final int MAX_PATCHES_PER_DOCK = 32;

    private final Map<String, Deque<RoutePatch>> pending = new LinkedHashMap<>();

    public static RouteRepairSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new RouteRepairSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RouteRepairSavedData::new, (tag, provider) -> load(tag)), DATA_NAME);
    }

    public void enqueue(String dimension, BlockPos dockPos, RoutePatch patch) {
        Deque<RoutePatch> queue = pending.computeIfAbsent(key(dimension, dockPos), k -> new ArrayDeque<>());
        queue.addLast(patch);
        while (queue.size() > MAX_PATCHES_PER_DOCK) {
            queue.removeFirst();
        }
        setDirty();
    }

    public List<RoutePatch> drain(String dimension, BlockPos dockPos) {
        Deque<RoutePatch> queue = pending.remove(key(dimension, dockPos));
        if (queue == null) {
            return List.of();
        }
        setDirty();
        return new ArrayList<>(queue);
    }

    public static RouteRepairSavedData load(CompoundTag tag) {
        RouteRepairSavedData data = new RouteRepairSavedData();
        ListTag docks = tag.getList("Docks", Tag.TAG_COMPOUND);
        for (int i = 0; i < docks.size(); i++) {
            CompoundTag dock = docks.getCompound(i);
            Deque<RoutePatch> queue = new ArrayDeque<>();
            ListTag patches = dock.getList("Patches", Tag.TAG_COMPOUND);
            for (int j = 0; j < patches.size(); j++) {
                CompoundTag patch = patches.getCompound(j);
                queue.addLast(new RoutePatch(readPoints(patch.getList("Before", Tag.TAG_DOUBLE)),
                        readPoints(patch.getList("After", Tag.TAG_DOUBLE))));
            }
            if (!queue.isEmpty()) {
                data.pending.put(dock.getString("Key"), queue);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        return save(tag);
    }

    public CompoundTag save(CompoundTag tag) {
        ListTag docks = new ListTag();
        for (Map.Entry<String, Deque<RoutePatch>> entry : pending.entrySet()) {
            CompoundTag dock = new CompoundTag();
            dock.putString("Key", entry.getKey());
            ListTag patches = new ListTag();
            for (RoutePatch patch : entry.getValue()) {
                CompoundTag p = new CompoundTag();
                p.put("Before", writePoints(patch.before()));
                p.put("After", writePoints(patch.after()));
                patches.add(p);
            }
            dock.put("Patches", patches);
            docks.add(dock);
        }
        tag.put("Docks", docks);
        return tag;
    }

    private static String key(String dimension, BlockPos pos) {
        return dimension + "|" + pos.asLong();
    }

    private static ListTag writePoints(List<Vec3> points) {
        ListTag list = new ListTag();
        for (Vec3 p : points) {
            list.add(DoubleTag.valueOf(p.x));
            list.add(DoubleTag.valueOf(p.y));
            list.add(DoubleTag.valueOf(p.z));
        }
        return list;
    }

    private static List<Vec3> readPoints(ListTag list) {
        List<Vec3> points = new ArrayList<>(list.size() / 3);
        for (int i = 0; i + 2 < list.size(); i += 3) {
            points.add(new Vec3(list.getDouble(i), list.getDouble(i + 1), list.getDouble(i + 2)));
        }
        return points;
    }
}
