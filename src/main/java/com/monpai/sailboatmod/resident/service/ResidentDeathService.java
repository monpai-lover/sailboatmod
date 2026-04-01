package com.monpai.sailboatmod.resident.service;

import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.event.ResidentEvent;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;

import java.util.List;

/**
 * Handles resident death, mourning, and respawn (inspired by MineColonies)
 */
public class ResidentDeathService {

    public static void onResidentDeath(ServerLevel level, String residentId, BlockPos deathPos, String cause) {
        ResidentSavedData data = ResidentSavedData.get(level);
        ResidentRecord record = data.getResident(residentId);
        if (record == null) return;

        // Fire death event
        MinecraftForge.EVENT_BUS.post(new ResidentEvent.Died(residentId, record.townId(), deathPos, cause));

        // Notify town members
        notifyTownMembers(level, record, cause);

        // Remove from saved data
        data.removeResident(residentId);
    }

    private static void notifyTownMembers(ServerLevel level, ResidentRecord deceased, String cause) {
        String msg = deceased.name() + " has died" + (cause.isEmpty() ? "." : " (" + cause + ").");
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal("[Town] " + msg));
        }
    }

    /**
     * Trigger mourning for nearby residents
     */
    public static void triggerMourning(ServerLevel level, String townId, BlockPos deathPos) {
        ResidentSavedData data = ResidentSavedData.get(level);
        for (ResidentRecord r : data.getResidentsForTown(townId)) {
            if (r.happiness() > 10) {
                data.putResident(r.withHappiness(r.happiness() - 10));
            }
        }
    }
}
