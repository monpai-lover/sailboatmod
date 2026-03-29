package com.example.examplemod.nation.service;

import com.example.examplemod.nation.data.NationSavedData;
import com.example.examplemod.nation.model.NationClaimAccessLevel;
import com.example.examplemod.nation.model.NationClaimRecord;
import com.example.examplemod.nation.model.NationFlagRecord;
import com.example.examplemod.nation.model.NationRecord;
import com.example.examplemod.nation.model.NationWarRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class NationAdminService {
    public static NationResult disbandNation(ServerLevel level, String rawNationName) {
        NationSavedData data = NationSavedData.get(level);
        NationRecord nation = data.findNationByName(rawNationName);
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nationadmin.nation_not_found", rawNationName));
        }

        List<NationFlagRecord> flags = new ArrayList<>(data.getFlagsForNation(nation.nationId()));
        for (NationFlagRecord flag : flags) {
            deleteFlagFile(level, data, flag.flagId());
        }

        data.removeNation(nation.nationId());
        NationFlagBlockTracker.refreshNationFlags(level.getServer(), nation.nationId());
        TownFlagBlockTracker.refreshNationFlags(level.getServer(), nation.nationId());
        return NationResult.success(Component.translatable("command.sailboatmod.nationadmin.disband.success", nation.name()));
    }

    public static NationResult clearFlag(ServerLevel level, String rawNationName) {
        NationSavedData data = NationSavedData.get(level);
        NationRecord nation = data.findNationByName(rawNationName);
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nationadmin.nation_not_found", rawNationName));
        }
        if (nation.flagId().isBlank()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nationadmin.clearflag.none", nation.name()));
        }

        deleteFlagFile(level, data, nation.flagId());
        data.removeFlag(nation.flagId());
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
                ""
        ));
        NationFlagBlockTracker.refreshNationFlags(level.getServer(), nation.nationId());
        TownFlagBlockTracker.refreshNationFlags(level.getServer(), nation.nationId());
        return NationResult.success(Component.translatable("command.sailboatmod.nationadmin.clearflag.success", nation.name()));
    }

    public static NationResult endWar(ServerLevel level, String warId) {
        return NationWarService.adminEndWar(level, warId);
    }

    public static NationResult setClaim(ServerLevel level, String rawNationName, int chunkX, int chunkZ) {
        NationSavedData data = NationSavedData.get(level);
        NationRecord nation = data.findNationByName(rawNationName);
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nationadmin.nation_not_found", rawNationName));
        }

        NationClaimRecord existing = data.getClaim(level.dimension().location().toString(), chunkX, chunkZ);
        if (existing != null) {
            data.removeClaim(level.dimension().location().toString(), chunkX, chunkZ);
        }
        data.putClaim(new NationClaimRecord(
                level.dimension().location().toString(),
                chunkX,
                chunkZ,
                nation.nationId(),
                "",
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                System.currentTimeMillis()
        ));
        return NationResult.success(Component.translatable("command.sailboatmod.nationadmin.setclaim.success", chunkX, chunkZ, nation.name()));
    }

    public static List<Component> debugDump(ServerLevel level, String rawNationName) {
        NationSavedData data = NationSavedData.get(level);
        if (rawNationName != null && !rawNationName.isBlank()) {
            NationRecord nation = data.findNationByName(rawNationName);
            if (nation == null) {
                return List.of(Component.translatable("command.sailboatmod.nationadmin.nation_not_found", rawNationName));
            }
            NationWarRecord activeWar = NationWarService.getActiveWarForNation(data, nation.nationId());
            return List.of(
                    Component.translatable("command.sailboatmod.nationadmin.dump.nation", nation.name(), nation.nationId()),
                    Component.translatable("command.sailboatmod.nationadmin.dump.members", data.getMembersForNation(nation.nationId()).size()),
                    Component.translatable("command.sailboatmod.nationadmin.dump.claims", data.getClaimsForNation(nation.nationId()).size()),
                    Component.translatable("command.sailboatmod.nationadmin.dump.flags", data.getFlagsForNation(nation.nationId()).size()),
                    Component.translatable("command.sailboatmod.nationadmin.dump.war", activeWar == null ? "-" : activeWar.warId(), activeWar == null ? "none" : activeWar.state())
            );
        }

        return List.of(
                Component.translatable("command.sailboatmod.nationadmin.dump.summary"),
                Component.translatable("command.sailboatmod.nationadmin.dump.nations", data.getNations().size()),
                Component.translatable("command.sailboatmod.nationadmin.dump.members", totalMembers(data)),
                Component.translatable("command.sailboatmod.nationadmin.dump.claims", totalClaims(data)),
                Component.translatable("command.sailboatmod.nationadmin.dump.flags", data.getFlags().size()),
                Component.translatable("command.sailboatmod.nationadmin.dump.wars_total", data.getWars().size())
        );
    }

    private static int totalMembers(NationSavedData data) {
        int count = 0;
        for (NationRecord nation : data.getNations()) {
            count += data.getMembersForNation(nation.nationId()).size();
        }
        return count;
    }

    private static int totalClaims(NationSavedData data) {
        int count = 0;
        for (NationRecord nation : data.getNations()) {
            count += data.getClaimsForNation(nation.nationId()).size();
        }
        return count;
    }

    private static void deleteFlagFile(ServerLevel level, NationSavedData data, String flagId) {
        try {
            NationFlagStorage.deleteFlag(level, data, flagId);
        } catch (IOException ignored) {
        }
    }

    private NationAdminService() {
    }
}
