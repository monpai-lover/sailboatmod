package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.SailboatMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.InputStream;

public final class BankConstructionManager {
    private static final ResourceLocation STRUCTURE_ID = new ResourceLocation(SailboatMod.MODID, "victorian_bank");

    private BankConstructionManager() {
    }

    public static boolean startConstruction(ServerLevel level, BlockPos origin, ServerPlayer player) {
        StructureTemplate template = loadTemplate(level);
        if (template == null) {
            return false;
        }
        StructurePlaceSettings settings = new StructurePlaceSettings();
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_ALL);
        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank_constructor.started"));
        return true;
    }

    public static void tick(ServerLevel level) {
    }

    public static void clearAll() {
    }

    private static StructureTemplate loadTemplate(ServerLevel level) {
        StructureTemplate template = level.getStructureManager().get(STRUCTURE_ID).orElse(null);
        if (template != null) return template;
        try {
            String path = "/data/" + SailboatMod.MODID + "/structures/victorian_bank.nbt";
            InputStream is = BankConstructionManager.class.getResourceAsStream(path);
            if (is == null) {
                is = Thread.currentThread().getContextClassLoader().getResourceAsStream("data/" + SailboatMod.MODID + "/structures/victorian_bank.nbt");
            }
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
}
