package com.monpai.sailboatmod.road.persistence;

import com.monpai.sailboatmod.road.model.RoadData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;

public class RoadNetworkStorage {
    private final Map<String, RoadData> roads = new LinkedHashMap<>();

    public void save(RoadData road) {
        roads.put(road.roadId(), road);
    }

    public Optional<RoadData> get(String roadId) {
        return Optional.ofNullable(roads.get(roadId));
    }

    public void remove(String roadId) {
        roads.remove(roadId);
    }

    public Collection<RoadData> getAll() {
        return Collections.unmodifiableCollection(roads.values());
    }

    public CompoundTag serialize() {
        CompoundTag tag = new CompoundTag();
        ListTag roadList = new ListTag();
        for (RoadData road : roads.values()) {
            CompoundTag roadTag = new CompoundTag();
            roadTag.putString("roadId", road.roadId());
            roadTag.putInt("width", road.width());
            ListTag pathTag = new ListTag();
            for (BlockPos pos : road.centerPath()) {
                CompoundTag posTag = new CompoundTag();
                posTag.putInt("x", pos.getX());
                posTag.putInt("y", pos.getY());
                posTag.putInt("z", pos.getZ());
                pathTag.add(posTag);
            }
            roadTag.put("centerPath", pathTag);
            roadList.add(roadTag);
        }
        tag.put("roads", roadList);
        return tag;
    }

    public void deserialize(CompoundTag tag) {
        roads.clear();
        ListTag roadList = tag.getList("roads", Tag.TAG_COMPOUND);
        for (int i = 0; i < roadList.size(); i++) {
            CompoundTag roadTag = roadList.getCompound(i);
            String roadId = roadTag.getString("roadId");
            int width = roadTag.getInt("width");
            ListTag pathTag = roadTag.getList("centerPath", Tag.TAG_COMPOUND);
            List<BlockPos> path = new ArrayList<>();
            for (int j = 0; j < pathTag.size(); j++) {
                CompoundTag posTag = pathTag.getCompound(j);
                path.add(new BlockPos(posTag.getInt("x"), posTag.getInt("y"), posTag.getInt("z")));
            }
            roads.put(roadId, new RoadData(roadId, width, List.of(), List.of(), null, List.of(), path));
        }
    }
}