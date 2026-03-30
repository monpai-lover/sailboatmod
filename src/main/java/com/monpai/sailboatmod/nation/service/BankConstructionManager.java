package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BankConstructionManager {
    private static final int TOTAL_BUILD_TICKS = 200;
    private static final ResourceLocation STRUCTURE_ID = new ResourceLocation(SailboatMod.MODID, "victorian_bank");
    private static final List<ConstructionTask> ACTIVE_TASKS = new CopyOnWriteArrayList<>();

    private BankConstructionManager() {
    }

    public static boolean startConstruction(ServerLevel level, BlockPos origin, ServerPlayer player) {
        StructureTemplate template = loadTemplate(level);
        if (template == null) {
            return false;
        }

        List<List<StructureTemplate.StructureBlockInfo>> layers = buildLayers(template);
        if (layers.isEmpty()) {
            return false;
        }

        ACTIVE_TASKS.add(new ConstructionTask(level, origin, layers, 0));
        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank_constructor.started"));
        return true;
    }

    public static void tick(ServerLevel level) {
        Iterator<ConstructionTask> it = ACTIVE_TASKS.iterator();
        while (it.hasNext()) {
            ConstructionTask task = it.next();
            if (!task.level.equals(level)) continue;
            task.ticksElapsed++;
            int totalBlocks = task.totalBlockCount();
            int ticksPerBlock = Math.max(1, TOTAL_BUILD_TICKS / Math.max(1, totalBlocks));
            if (task.ticksElapsed % ticksPerBlock == 0) {
                if (!task.placeNextBlock()) {
                    it.remove();
                }
            }
        }
    }

    public static void clearAll() {
        ACTIVE_TASKS.clear();
    }

    private static StructureTemplate loadTemplate(ServerLevel level) {
        StructureTemplate template = level.getStructureManager().get(STRUCTURE_ID).orElse(null);
        if (template != null) return template;
        try {
            String path = "/data/" + SailboatMod.MODID + "/structures/victorian_bank.nbt";
            InputStream is = BankConstructionManager.class.getResourceAsStream(path);
            if (is == null) return null;
            CompoundTag tag = NbtIo.readCompressed(is);
            is.close();
            template = new StructureTemplate();
            template.load(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag);
            return template;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<List<StructureTemplate.StructureBlockInfo>> buildLayers(StructureTemplate template) {
        List<List<StructureTemplate.StructureBlockInfo>> layers = new ArrayList<>();
        StructurePlaceSettings settings = new StructurePlaceSettings();
        List<StructureTemplate.StructureBlockInfo> allBlocks = template.filterBlocks(
                BlockPos.ZERO, settings, Blocks.STRUCTURE_VOID, false);
        if (allBlocks.isEmpty()) {
            allBlocks = template.filterBlocks(BlockPos.ZERO, settings, Blocks.AIR, false);
        }
        // Get all palettes and collect blocks
        List<StructureTemplate.StructureBlockInfo> blocks = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info : allBlocks) {
            if (!info.state().isAir()) {
                blocks.add(info);
            }
        }
        if (blocks.isEmpty()) return layers;
        int maxY = 0;
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            maxY = Math.max(maxY, info.pos().getY());
        }
        for (int y = 0; y <= maxY; y++) {
            List<StructureTemplate.StructureBlockInfo> layer = new ArrayList<>();
            for (StructureTemplate.StructureBlockInfo info : blocks) {
                if (info.pos().getY() == y) {
                    layer.add(info);
                }
            }
            if (!layer.isEmpty()) layers.add(layer);
        }
        return layers;
    }

    private static class ConstructionTask {
        final ServerLevel level;
        final BlockPos origin;
        final List<List<StructureTemplate.StructureBlockInfo>> layers;
        int currentLayer;
        int currentBlockInLayer;
        int ticksElapsed;

        ConstructionTask(ServerLevel level, BlockPos origin, List<List<StructureTemplate.StructureBlockInfo>> layers, int startTick) {
            this.level = level;
            this.origin = origin;
            this.layers = layers;
            this.currentLayer = 0;
            this.currentBlockInLayer = 0;
            this.ticksElapsed = startTick;
        }

        int totalBlockCount() {
            int count = 0;
            for (List<StructureTemplate.StructureBlockInfo> layer : layers) count += layer.size();
            return count;
        }

        boolean placeNextBlock() {
            if (currentLayer >= layers.size()) return false;
            List<StructureTemplate.StructureBlockInfo> layer = layers.get(currentLayer);
            if (currentBlockInLayer >= layer.size()) {
                currentLayer++;
                currentBlockInLayer = 0;
                return currentLayer < layers.size();
            }
            StructureTemplate.StructureBlockInfo info = layer.get(currentBlockInLayer);
            BlockPos worldPos = origin.offset(info.pos());
            BlockState state = info.state();
            level.setBlock(worldPos, state, Block.UPDATE_ALL);
            if (info.nbt() != null) {
                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(worldPos);
                if (be != null) {
                    be.load(info.nbt());
                    be.setChanged();
                }
            }
            currentBlockInLayer++;
            if (currentBlockInLayer >= layer.size()) {
                currentLayer++;
                currentBlockInLayer = 0;
            }
            return currentLayer < layers.size();
        }
    }
}
