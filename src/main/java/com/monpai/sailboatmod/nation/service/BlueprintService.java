package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.SailboatMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.InputStream;
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
            List<StructureTemplate.StructureBlockInfo> blocks,
            PlacementBounds bounds,
            int rotation
    ) {
    }

    private BlueprintService() {
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
        if (template == null) {
            return null;
        }

        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(toRotation(rotation));
        List<StructureTemplate.StructureBlockInfo> blocks = template.filterBlocks(anchor, settings, Blocks.AIR, true);
        blocks.removeIf(info -> info.state().isAir() || info.state().is(Blocks.STRUCTURE_VOID));
        if (blocks.isEmpty()) {
            PlacementBounds fallbackBounds = fallbackBounds(anchor, template.getSize(), rotation);
            return new BlueprintPlacement(blueprintId, template, settings, List.of(), fallbackBounds, Math.floorMod(rotation, 4));
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
        return new BlueprintPlacement(blueprintId, template, settings, List.copyOf(blocks), bounds, Math.floorMod(rotation, 4));
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
}
