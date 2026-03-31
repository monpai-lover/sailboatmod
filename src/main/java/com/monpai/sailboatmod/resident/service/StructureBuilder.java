package com.monpai.sailboatmod.resident.service;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.InputStream;
import java.util.*;

public class StructureBuilder {
    private static final Map<String, List<BlockPlacement>> structureCache = new HashMap<>();

    public static void placeLayer(ServerLevel level, BlockPos origin, String structureType, int layer) {
        List<BlockPlacement> blocks = loadStructure(structureType);
        if (blocks == null) return;

        for (BlockPlacement placement : blocks) {
            if (placement.y == layer) {
                BlockPos pos = origin.offset(placement.x, placement.y, placement.z);
                level.setBlock(pos, placement.state, 3);
            }
        }
    }

    public static int getTotalLayers(String structureType) {
        List<BlockPlacement> blocks = loadStructure(structureType);
        if (blocks == null || blocks.isEmpty()) return 0;
        return blocks.stream().mapToInt(b -> b.y).max().orElse(0) + 1;
    }

    private static List<BlockPlacement> loadStructure(String structureType) {
        if (structureCache.containsKey(structureType)) {
            return structureCache.get(structureType);
        }

        try {
            InputStream stream = StructureBuilder.class.getResourceAsStream("/data/sailboatmod/structures/" + structureType + ".nbt");
            if (stream == null) return null;

            CompoundTag nbt = NbtIo.readCompressed(stream);
            ListTag paletteList = nbt.getList("palette", 10);
            ListTag blocksList = nbt.getList("blocks", 10);

            // Parse palette
            BlockState[] palette = new BlockState[paletteList.size()];
            for (int i = 0; i < paletteList.size(); i++) {
                palette[i] = parseBlockState(paletteList.getCompound(i));
            }

            // Parse blocks
            List<BlockPlacement> placements = new ArrayList<>();
            for (int i = 0; i < blocksList.size(); i++) {
                CompoundTag blockTag = blocksList.getCompound(i);
                ListTag pos = blockTag.getList("pos", 3);
                int stateIdx = blockTag.getInt("state");

                BlockState state = stateIdx < palette.length ? palette[stateIdx] : Blocks.AIR.defaultBlockState();
                placements.add(new BlockPlacement(pos.getInt(0), pos.getInt(1), pos.getInt(2), state));
            }

            structureCache.put(structureType, placements);
            return placements;
        } catch (Exception e) {
            return null;
        }
    }

    private static BlockState parseBlockState(CompoundTag paletteEntry) {
        String name = paletteEntry.getString("Name");
        ResourceLocation loc = new ResourceLocation(name);
        Block block = BuiltInRegistries.BLOCK.get(loc);
        BlockState state = block.defaultBlockState();

        if (paletteEntry.contains("Properties")) {
            CompoundTag props = paletteEntry.getCompound("Properties");
            for (String key : props.getAllKeys()) {
                String value = props.getString(key);
                state = applyProperty(state, key, value);
            }
        }
        return state;
    }

    private static BlockState applyProperty(BlockState state, String name, String value) {
        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equals(name)) {
                return setProperty(state, prop, value);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> prop, String value) {
        return prop.getValue(value).map(v -> state.setValue(prop, v)).orElse(state);
    }

    private record BlockPlacement(int x, int y, int z, BlockState state) {}
}
