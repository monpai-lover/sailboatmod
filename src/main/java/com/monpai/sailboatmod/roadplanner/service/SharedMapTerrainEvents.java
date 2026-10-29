package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.SailboatMod;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Capture events only enqueue coordinates; all actual reads and network updates happen in the budgeted server tick. */
@EventBusSubscriber(modid = SailboatMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SharedMapTerrainEvents {
    private SharedMapTerrainEvents() { }

    @SubscribeEvent public static void loaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var pos = event.getChunk().getPos();
            SharedMapTerrainService.global().changedChunk(level, pos.x, pos.z);
        }
    }

    @SubscribeEvent public static void placed(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SharedMapTerrainService.global().changedChunk(level, event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
        }
    }

    @SubscribeEvent public static void broken(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SharedMapTerrainService.global().changedChunk(level, event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
        }
    }
}
