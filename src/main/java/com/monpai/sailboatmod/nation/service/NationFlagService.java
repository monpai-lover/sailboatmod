package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationFlagRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NationFlagService {
    public static NationResult importFlag(ServerPlayer player, String rawPath) {
        String normalizedPath = rawPath == null ? "" : rawPath.trim();
        if (normalizedPath.isBlank()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.path_missing"));
        }

        try {
            Path path = Path.of(normalizedPath);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.path_invalid", normalizedPath));
            }
            return uploadFlag(player, Files.readAllBytes(path));
        } catch (IOException e) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.import.failed", e.getMessage()));
        }
    }

    public static NationResult uploadFlag(ServerPlayer player, byte[] imageBytes) {
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.UPLOAD_FLAG)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.no_permission"));
        }

        NationRecord nation = data.getNation(member.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }

        try {
            NationFlagRecord existing = data.getFlag(nation.flagId());
            NationFlagRecord saved = NationFlagStorage.saveFlag(player.serverLevel(), nation.nationId(), player.getUUID().toString(), imageBytes);
            NationFlagRecord flag = existing != null && existing.mirrored()
                    ? new NationFlagRecord(saved.flagId(), saved.nationId(), saved.sha256(), saved.width(), saved.height(), saved.uploadedAt(), saved.uploadedBy(), saved.byteSize(), true)
                    : saved;
            data.putFlag(flag);
            data.putNation(new NationRecord(
                    nation.nationId(),
                    nation.name(),
                    nation.shortName(),
                    nation.primaryColorRgb(),
                    nation.secondaryColorRgb(),
                    nation.leaderUuid(),
                    nation.createdAt(),
                    nation.capitalTownId(),
                    nation.coreDimension(),
                    nation.corePos(),
                    flag.flagId()
            ));
            NationFlagSyncService.syncFlagToAll(player, flag.flagId());
            NationFlagBlockTracker.refreshNationFlags(player.getServer(), nation.nationId());
            TownFlagBlockTracker.refreshNationFlags(player.getServer(), nation.nationId());
            return NationResult.success(Component.translatable("command.sailboatmod.nation.flag.import.success", flag.flagId(), flag.width(), flag.height()));
        } catch (IOException e) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.import.failed", e.getMessage()));
        }
    }

    public static NationResult setMirrored(ServerPlayer actor, Boolean mirroredValue) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationMemberRecord member = data.getMember(actor.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!NationService.hasPermission(actor.level(), actor.getUUID(), NationPermission.UPLOAD_FLAG)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.no_permission"));
        }

        NationRecord nation = data.getNation(member.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        NationFlagRecord flag = data.getFlag(nation.flagId());
        if (flag == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.none"));
        }

        boolean mirrored = mirroredValue == null ? !flag.mirrored() : mirroredValue.booleanValue();
        if (mirrored == flag.mirrored()) {
            return NationResult.failure(Component.translatable(mirrored ? "command.sailboatmod.nation.flag.mirror.already_on" : "command.sailboatmod.nation.flag.mirror.already_off"));
        }

        NationFlagRecord updated = new NationFlagRecord(
                flag.flagId(),
                flag.nationId(),
                flag.sha256(),
                flag.width(),
                flag.height(),
                flag.uploadedAt(),
                flag.uploadedBy(),
                flag.byteSize(),
                mirrored
        );
        data.putFlag(updated);
        NationFlagBlockTracker.refreshNationFlags(actor.getServer(), nation.nationId());
        TownFlagBlockTracker.refreshNationFlags(actor.getServer(), nation.nationId());
        return NationResult.success(Component.translatable(mirrored ? "command.sailboatmod.nation.flag.mirror.on" : "command.sailboatmod.nation.flag.mirror.off"));
    }

    public static List<Component> describeFlag(Level level, String nationId) {
        NationSavedData data = NationSavedData.get(level);
        NationRecord nation = data.getNation(nationId);
        if (nation == null || nation.flagId().isBlank()) {
            return List.of(Component.translatable("command.sailboatmod.nation.flag.none"));
        }
        NationFlagRecord flag = data.getFlag(nation.flagId());
        if (flag == null) {
            return List.of(Component.translatable("command.sailboatmod.nation.flag.missing_meta", nation.flagId()));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.nation.flag.info.header", nation.name(), flag.flagId()));
        lines.add(Component.translatable("command.sailboatmod.nation.flag.info.size", flag.width(), flag.height(), flag.byteSize()));
        lines.add(Component.translatable("command.sailboatmod.nation.flag.info.hash", flag.sha256()));
        lines.add(Component.translatable(flag.mirrored() ? "command.sailboatmod.nation.flag.mirror.on" : "command.sailboatmod.nation.flag.mirror.off"));
        return lines;
    }

    private NationFlagService() {
    }
}