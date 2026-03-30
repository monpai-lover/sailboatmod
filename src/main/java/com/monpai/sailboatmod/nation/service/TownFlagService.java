package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationFlagRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;

public final class TownFlagService {
    public static NationResult uploadFlag(ServerPlayer player, String townId, byte[] imageBytes) {
        NationSavedData data = NationSavedData.get(player.level());
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", townId));
        }
        if (!TownService.canManageTown(player, data, town)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.no_permission"));
        }

        try {
            NationFlagRecord existing = town.flagId().isBlank() ? null : data.getFlag(town.flagId());
            NationFlagRecord saved = NationFlagStorage.saveFlag(player.serverLevel(), town.townId(), player.getUUID().toString(), imageBytes);
            NationFlagRecord flag = existing != null && existing.mirrored()
                    ? new NationFlagRecord(saved.flagId(), saved.nationId(), saved.sha256(), saved.width(), saved.height(), saved.uploadedAt(), saved.uploadedBy(), saved.byteSize(), true)
                    : saved;
            data.putFlag(flag);
            data.putTown(new TownRecord(
                    town.townId(),
                    town.nationId(),
                    town.name(),
                    town.mayorUuid(),
                    town.createdAt(),
                    town.coreDimension(),
                    town.corePos(),
                    flag.flagId()
            ));
            if (existing != null && !existing.flagId().equals(flag.flagId())) {
                NationFlagStorage.deleteFlag(player.serverLevel(), data, existing.flagId());
                data.removeFlag(existing.flagId());
            }
            NationFlagSyncService.syncFlagToAll(player, flag.flagId());
            TownFlagBlockTracker.refreshTownFlags(player.getServer(), town.townId());
            return NationResult.success(Component.translatable("command.sailboatmod.nation.flag.import.success", flag.flagId(), flag.width(), flag.height()));
        } catch (IOException e) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.import.failed", e.getMessage()));
        }
    }

    public static NationResult setMirrored(ServerPlayer actor, String townId, Boolean mirroredValue) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", townId));
        }
        if (!TownService.canManageTown(actor, data, town)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.no_permission"));
        }
        NationFlagRecord flag = data.getFlag(town.flagId());
        if (flag == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.none"));
        }

        boolean mirrored = mirroredValue == null ? !flag.mirrored() : mirroredValue.booleanValue();
        if (mirrored == flag.mirrored()) {
            return NationResult.failure(Component.translatable(mirrored ? "command.sailboatmod.nation.flag.mirror.already_on" : "command.sailboatmod.nation.flag.mirror.already_off"));
        }

        data.putFlag(new NationFlagRecord(
                flag.flagId(),
                flag.nationId(),
                flag.sha256(),
                flag.width(),
                flag.height(),
                flag.uploadedAt(),
                flag.uploadedBy(),
                flag.byteSize(),
                mirrored
        ));
        TownFlagBlockTracker.refreshTownFlags(actor.getServer(), town.townId());
        return NationResult.success(Component.translatable(mirrored ? "command.sailboatmod.nation.flag.mirror.on" : "command.sailboatmod.nation.flag.mirror.off"));
    }

    private TownFlagService() {
    }
}