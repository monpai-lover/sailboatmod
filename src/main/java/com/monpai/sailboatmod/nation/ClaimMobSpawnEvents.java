package com.monpai.sailboatmod.nation;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.service.ClaimMobSpawnPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.concurrent.ConcurrentHashMap;

/** Checks the destination chunk, not the spawner's chunk. No entity-join/despawn hook: saved mobs are never erased. */
@EventBusSubscriber(modid = SailboatMod.MODID)
public final class ClaimMobSpawnEvents {
    private static final ConcurrentHashMap<MinecraftServer, NationSavedData> DATA = new ConcurrentHashMap<>();
    private ClaimMobSpawnEvents() { }
    @SubscribeEvent public static void starting(ServerStartingEvent event) {
        DATA.put(event.getServer(), NationSavedData.get(event.getServer().overworld()));
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { DATA.remove(event.getServer()); }
    private static boolean denied(ServerLevelAccessor world, BlockPos pos, MobCategory category, MobSpawnType type) {
        if (!ClaimMobSpawnPolicy.automaticMonster(category, type)) return false;
        var level = world.getLevel();
        var server = level.getServer();
        NationSavedData data = DATA.get(server);
        // Lazy initialization may ONLY touch SavedData from the server thread. Worldgen workers read the index.
        if (data == null && server.isSameThread()) {
            data = NationSavedData.get(level);
            DATA.put(server, data);
        }
        return data != null && !data.allowsMonsterSpawning(level.dimension().location().toString(),
                Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void placement(MobSpawnEvent.SpawnPlacementCheck event) {
        if (denied(event.getLevel(), event.getPos(), event.getEntityType().getCategory(), event.getSpawnType()))
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void position(MobSpawnEvent.PositionCheck event) {
        if (denied(event.getLevel(), BlockPos.containing(event.getX(), event.getY(), event.getZ()),
                event.getEntity().getType().getCategory(), event.getSpawnType()))
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void finalizeSpawn(FinalizeSpawnEvent event) {
        if (denied(event.getLevel(), BlockPos.containing(event.getX(), event.getY(), event.getZ()),
                event.getEntity().getType().getCategory(), event.getSpawnType()))
            event.setSpawnCancelled(true); // cancelling initialization alone does NOT cancel spawning
    }
}
