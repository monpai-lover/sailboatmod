package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.SailboatMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class BlueprintService {

    public record PlacementBounds(BlockPos min, BlockPos max) {
        public int width() {
            return max.getX() - min.getX() + 1;
        }

        public int height() {
            return max.getY() - min.getY() + 1;
        }

        public int depth() {
            return max.getZ() - min.getZ() + 1;
        }

        public BlockPos centerAtY(int y) {
            return new BlockPos((min.getX() + max.getX()) / 2, y, (min.getZ() + max.getZ()) / 2);
        }
    }

    public record BlueprintPlacement(
            String blueprintId,
            StructureTemplate template,
            StructurePlaceSettings settings,
            List<BlueprintBlock> blockData,
            List<StructureTemplate.StructureBlockInfo> blocks,
            PlacementBounds bounds,
            int rotation
    ) {
    }

    private BlueprintService() {
    }

    public record BlueprintBlock(BlockPos relativePos, BlockState state, CompoundTag nbt) {
        public BlueprintBlock rotate(Vec3i size, int rotation) {
            return new BlueprintBlock(rotatePos(relativePos, size, rotation), state.rotate(toRotation(rotation)), copyNbt(nbt));
        }

        public BlueprintBlock move(BlockPos offset) {
            return new BlueprintBlock(offset.offset(relativePos), state, copyNbt(nbt));
        }

        public StructureTemplate.StructureBlockInfo toStructureInfo() {
            return new StructureTemplate.StructureBlockInfo(relativePos, state, copyNbt(nbt));
        }
    }

    private record RawBlueprint(Vec3i size, List<BlueprintBlock> blocks) {
    }

    public static BlueprintPlacement preparePlacement(ServerLevel level, String blueprintId, BlockPos anchor, int rotation) {
        StructureTemplate template = loadTemplate(level, blueprintId);
        return preparePlacement(blueprintId, template, anchor, rotation);
    }

    public static BlueprintPlacement preparePlacement(HolderGetter<Block> blockLookup, String blueprintId, BlockPos anchor, int rotation) {
        StructureTemplate template = loadTemplate(blockLookup, blueprintId);
        return preparePlacement(blueprintId, template, anchor, rotation);
    }

    public static BlueprintPlacement preparePlacement(String blueprintId, StructureTemplate template, BlockPos anchor, int rotation) {
        RawBlueprint blueprint = loadBlueprint(blueprintId);
        if (blueprint == null && template == null) {
            return null;
        }

        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(toRotation(rotation));
        List<BlueprintBlock> blockData = new ArrayList<>();
        List<StructureTemplate.StructureBlockInfo> blocks = new ArrayList<>();
        if (blueprint != null) {
            for (BlueprintBlock block : blueprint.blocks()) {
                BlueprintBlock placedBlock = block.rotate(blueprint.size(), rotation).move(anchor);
                blockData.add(placedBlock);
                blocks.add(placedBlock.toStructureInfo());
            }
        }
        if (blocks.isEmpty()) {
            Vec3i fallbackSize = blueprint != null ? blueprint.size() : template.getSize();
            PlacementBounds fallbackBounds = fallbackBounds(anchor, fallbackSize, rotation);
            return new BlueprintPlacement(blueprintId, template, settings, List.of(), List.of(), fallbackBounds, Math.floorMod(rotation, 4));
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (StructureTemplate.StructureBlockInfo info : blocks) {
            BlockPos pos = info.pos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        PlacementBounds bounds = new PlacementBounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
        return new BlueprintPlacement(blueprintId, template, settings, List.copyOf(blockData), List.copyOf(blocks), bounds, Math.floorMod(rotation, 4));
    }

    public static StructureTemplate loadTemplate(ServerLevel level, String blueprintId) {
        ResourceLocation id = new ResourceLocation(SailboatMod.MODID, blueprintId);
        StructureTemplate template = level.getStructureManager().get(id).orElse(null);
        if (template != null) {
            return template;
        }

        return loadTemplate(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), blueprintId);
    }

    public static StructureTemplate loadTemplate(HolderGetter<Block> blockLookup, String blueprintId) {
        try (InputStream is = openTemplateStream(blueprintId)) {
            if (is == null) {
                return null;
            }

            CompoundTag tag = NbtIo.readCompressed(is);
            StructureTemplate template = new StructureTemplate();
            template.load(blockLookup, tag);
            return template;
        } catch (Exception e) {
            return null;
        }
    }

    public static Rotation toRotation(int rotation) {
        return switch (Math.floorMod(rotation, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static PlacementBounds fallbackBounds(BlockPos anchor, Vec3i size, int rotation) {
        int width = size.getX();
        int height = Math.max(1, size.getY());
        int depth = size.getZ();
        if (Math.floorMod(rotation, 4) % 2 == 1) {
            int tmp = width;
            width = depth;
            depth = tmp;
        }

        BlockPos min = anchor;
        BlockPos max = anchor.offset(Math.max(0, width - 1), Math.max(0, height - 1), Math.max(0, depth - 1));
        return new PlacementBounds(min, max);
    }

    private static InputStream openTemplateStream(String blueprintId) {
        String path = "/data/" + SailboatMod.MODID + "/structures/" + blueprintId + ".nbt";
        InputStream is = BlueprintService.class.getResourceAsStream(path);
        if (is != null) {
            return is;
        }

        return Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("data/" + SailboatMod.MODID + "/structures/" + blueprintId + ".nbt");
    }

    private static RawBlueprint loadBlueprint(String blueprintId) {
        try (InputStream is = openTemplateStream(blueprintId)) {
            if (is == null) {
                return null;
            }

            CompoundTag tag = NbtIo.readCompressed(is);
            ListTag sizeTag = tag.getList("size", 3);
            Vec3i size = sizeTag.size() >= 3
                    ? new Vec3i(sizeTag.getInt(0), Math.max(1, sizeTag.getInt(1)), sizeTag.getInt(2))
                    : new Vec3i(1, 1, 1);
            ListTag paletteList = tag.getList("palette", 10);
            ListTag blocksList = tag.getList("blocks", 10);
            BlockState[] palette = new BlockState[paletteList.size()];
            for (int i = 0; i < paletteList.size(); i++) {
                palette[i] = parseBlockState(paletteList.getCompound(i));
            }

            List<BlueprintBlock> blocks = new ArrayList<>();
            for (int i = 0; i < blocksList.size(); i++) {
                CompoundTag blockTag = blocksList.getCompound(i);
                ListTag posTag = blockTag.getList("pos", 3);
                if (posTag.size() < 3) {
                    continue;
                }
                int stateIndex = blockTag.getInt("state");
                BlockState state = stateIndex >= 0 && stateIndex < palette.length
                        ? palette[stateIndex]
                        : Blocks.AIR.defaultBlockState();
                if (state.isAir() || state.is(Blocks.STRUCTURE_VOID)) {
                    continue;
                }
                CompoundTag blockEntityTag = blockTag.contains("nbt", 10) ? blockTag.getCompound("nbt").copy() : null;
                blocks.add(new BlueprintBlock(
                        new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2)),
                        state,
                        blockEntityTag
                ));
            }
            return new RawBlueprint(size, List.copyOf(blocks));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static BlockState parseBlockState(CompoundTag paletteEntry) {
        ResourceLocation blockId = new ResourceLocation(paletteEntry.getString("Name"));
        Block block = BuiltInRegistries.BLOCK.get(blockId);
        BlockState state = block.defaultBlockState();
        if (!paletteEntry.contains("Properties")) {
            return state;
        }
        CompoundTag properties = paletteEntry.getCompound("Properties");
        for (String key : properties.getAllKeys()) {
            state = applyProperty(state, key, properties.getString(key));
        }
        return state;
    }

    private static BlockState applyProperty(BlockState state, String propertyName, String rawValue) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return setPropertyValue(state, property, rawValue);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state, Property<T> property, String rawValue) {
        return property.getValue(rawValue).map(value -> state.setValue(property, value)).orElse(state);
    }

    private static CompoundTag copyNbt(CompoundTag tag) {
        return tag == null ? null : tag.copy();
    }

    private static BlockPos rotatePos(BlockPos pos, Vec3i size, int rotation) {
        return switch (Math.floorMod(rotation, 4)) {
            case 1 -> new BlockPos(size.getZ() - 1 - pos.getZ(), pos.getY(), pos.getX());
            case 2 -> new BlockPos(size.getX() - 1 - pos.getX(), pos.getY(), size.getZ() - 1 - pos.getZ());
            case 3 -> new BlockPos(pos.getZ(), pos.getY(), size.getX() - 1 - pos.getX());
            default -> pos;
        };
    }
}
