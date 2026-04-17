package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class TerrainCacheInvalidator {

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BlockPos pos = event.getPos();
            ClaimPreviewTerrainService service = ClaimPreviewTerrainService.get(level.getServer());
            if (service != null) {
                service.invalidateChunkNow(level, pos.getX() >> 4, pos.getZ() >> 4);
            }
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BlockPos pos = event.getPos();
            ClaimPreviewTerrainService service = ClaimPreviewTerrainService.get(level.getServer());
            if (service != null) {
                service.invalidateChunkNow(level, pos.getX() >> 4, pos.getZ() >> 4);
            }
        }
    }

    private TerrainCacheInvalidator() {
    }
}
