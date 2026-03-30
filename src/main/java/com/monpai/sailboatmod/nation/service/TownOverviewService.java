package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;
import com.monpai.sailboatmod.nation.menu.NationOverviewMember;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationFlagRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class TownOverviewService {
    private static final int CLAIM_PREVIEW_RADIUS = 20;
    private static final int DEFAULT_PRIMARY_COLOR = 0x4FA89B;
    private static final int DEFAULT_SECONDARY_COLOR = 0xD8B35A;

    public static TownOverviewData buildFor(ServerPlayer player, String townId) {
        if (player == null || townId == null || townId.isBlank()) {
            return TownOverviewData.empty();
        }

        NationSavedData data = NationSavedData.get(player.level());
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return TownOverviewData.empty();
        }

        NationRecord nation = town.hasNation() ? data.getNation(town.nationId()) : null;
        ChunkPos playerChunk = player.chunkPosition();
        NationClaimRecord currentClaim = data.getClaim(player.level(), playerChunk);
        TownRecord currentTown = currentClaim == null || currentClaim.townId().isBlank() ? null : data.getTown(currentClaim.townId());
        NationRecord currentNation = currentClaim == null || currentClaim.nationId().isBlank() ? null : data.getNation(currentClaim.nationId());
        NationFlagRecord flag = town.flagId().isBlank() ? null : data.getFlag(town.flagId());

        List<NationOverviewMember> members = new ArrayList<>();
        if (nation != null) {
            List<NationMemberRecord> memberRecords = new ArrayList<>(data.getMembersForNation(nation.nationId()));
            memberRecords.sort(
                    Comparator.comparingInt((NationMemberRecord member) -> officePriority(data, nation.nationId(), member.officeId()))
                            .thenComparing(member -> playerName(data, player, member.playerUuid()).toLowerCase(Locale.ROOT))
            );
            for (NationMemberRecord member : memberRecords) {
                members.add(new NationOverviewMember(
                        member.playerUuid().toString(),
                        playerName(data, player, member.playerUuid()),
                        member.officeId(),
                        officeName(data, nation.nationId(), member.officeId()),
                        player.getServer() != null && player.getServer().getPlayerList().getPlayer(member.playerUuid()) != null
                ));
            }
        }

        List<NationOverviewClaim> nearbyClaims = new ArrayList<>();
        for (NationClaimRecord claim : data.getClaimsInArea(
                player.level().dimension().location().toString(),
                playerChunk.x - CLAIM_PREVIEW_RADIUS,
                playerChunk.x + CLAIM_PREVIEW_RADIUS,
                playerChunk.z - CLAIM_PREVIEW_RADIUS,
                playerChunk.z + CLAIM_PREVIEW_RADIUS)) {
            TownRecord claimTown = claim.townId().isBlank() ? null : data.getTown(claim.townId());
            NationRecord claimNation = claim.nationId().isBlank() ? null : data.getNation(claim.nationId());
            nearbyClaims.add(new NationOverviewClaim(
                    claim.chunkX(),
                    claim.chunkZ(),
                    displayClaimTownId(claim, town, nation),
                    displayClaimName(claimTown, claimNation, town, nation, claim),
                    claimColor(claimTown, claimNation),
                    claim.townId(),
                    claimTown == null ? "" : claimTown.name(),
                    claim.breakAccessLevel(),
                    claim.placeAccessLevel(),
                    claim.useAccessLevel(),
                    claim.containerAccessLevel(),
                    claim.redstoneAccessLevel(),
                    claim.entityUseAccessLevel(),
                    claim.entityDamageAccessLevel()));
        }
        nearbyClaims.sort(Comparator.comparingInt(NationOverviewClaim::chunkZ).thenComparingInt(NationOverviewClaim::chunkX));
        List<Integer> nearbyTerrainColors = ClaimPreviewTerrainService.sample(player.serverLevel(), playerChunk, CLAIM_PREVIEW_RADIUS);

        boolean canManageTown = TownService.canManageTown(player, data, town);
        boolean canAssignMayor = nation != null && player.getUUID().equals(nation.leaderUuid());
        boolean isMayor = player.getUUID().equals(town.mayorUuid());
        int primaryColor = nation == null ? DEFAULT_PRIMARY_COLOR : nation.primaryColorRgb();
        int secondaryColor = nation == null ? DEFAULT_SECONDARY_COLOR : nation.secondaryColorRgb();
        String ownerName = currentTown != null
                ? currentTown.name()
                : displayClaimName(null, currentNation, town, nation, currentClaim);

        return new TownOverviewData(
                true,
                town.townId(),
                town.name(),
                town.nationId(),
                nation == null ? "" : nation.name(),
                town.mayorUuid() == null ? "" : town.mayorUuid().toString(),
                playerName(data, player, town.mayorUuid()),
                nation != null && town.townId().equals(nation.capitalTownId()),
                primaryColor,
                secondaryColor,
                town.hasCore(),
                town.coreDimension(),
                town.corePos(),
                countClaimsManagedByTown(data, town, nation),
                playerChunk.x,
                playerChunk.z,
                currentClaim != null,
                isClaimManagedByTown(currentClaim, town, nation),
                ownerName,
                currentClaim == null ? "" : currentClaim.breakAccessLevel(),
                currentClaim == null ? "" : currentClaim.placeAccessLevel(),
                currentClaim == null ? "" : currentClaim.useAccessLevel(),
                currentClaim == null ? "" : currentClaim.containerAccessLevel(),
                currentClaim == null ? "" : currentClaim.redstoneAccessLevel(),
                currentClaim == null ? "" : currentClaim.entityUseAccessLevel(),
                currentClaim == null ? "" : currentClaim.entityDamageAccessLevel(),
                town.flagId(),
                flag == null ? 0 : flag.width(),
                flag == null ? 0 : flag.height(),
                flag == null ? 0L : flag.byteSize(),
                flag == null ? "" : flag.sha256(),
                flag != null && flag.mirrored(),
                canManageTown,
                canManageTown,
                canManageTown,
                canAssignMayor,
                isMayor,
                members,
                nearbyTerrainColors,
                nearbyClaims);
    }

    private static int countClaimsManagedByTown(NationSavedData data, TownRecord town, NationRecord nation) {
        if (data == null || town == null) {
            return 0;
        }
        int count = 0;
        if (nation == null || !town.townId().equals(nation.capitalTownId())) {
            return data.getClaimsForTown(town.townId()).size();
        }
        for (NationClaimRecord claim : data.getClaimsForNation(nation.nationId())) {
            if (isClaimManagedByTown(claim, town, nation)) {
                count++;
            }
        }
        return count;
    }

    private static String displayClaimTownId(NationClaimRecord claim, TownRecord town, NationRecord nation) {
        if (claim == null) {
            return "";
        }
        if (!claim.townId().isBlank()) {
            return claim.townId();
        }
        return isClaimManagedByTown(claim, town, nation) ? town.townId() : "";
    }

    private static String displayClaimName(TownRecord claimTown, NationRecord claimNation, TownRecord town, NationRecord nation, NationClaimRecord claim) {
        if (claimTown != null) {
            return claimTown.name();
        }
        if (isClaimManagedByTown(claim, town, nation)) {
            return town.name();
        }
        return nameOrFallback(claimNation, claim == null ? "" : claim.nationId());
    }

    private static boolean isClaimManagedByTown(NationClaimRecord claim, TownRecord town, NationRecord nation) {
        if (claim == null || town == null) {
            return false;
        }
        if (town.townId().equals(claim.townId())) {
            return true;
        }
        return claim.townId().isBlank()
                && nation != null
                && town.townId().equals(nation.capitalTownId())
                && nation.nationId().equals(claim.nationId());
    }

    private static int claimColor(TownRecord town, NationRecord nation) {
        if (nation != null) {
            return nation.primaryColorRgb();
        }
        return DEFAULT_PRIMARY_COLOR;
    }

    private static String officeName(NationSavedData data, String nationId, String officeId) {
        NationOfficeRecord office = data.getOffice(nationId, officeId);
        if (office != null && !office.name().isBlank()) {
            return office.name();
        }
        return officeId == null || officeId.isBlank() ? "-" : officeId;
    }

    private static int officePriority(NationSavedData data, String nationId, String officeId) {
        NationOfficeRecord office = data.getOffice(nationId, officeId);
        return office == null ? Integer.MAX_VALUE : office.priority();
    }

    private static String playerName(NationSavedData data, ServerPlayer viewer, UUID playerUuid) {
        if (playerUuid == null) {
            return "-";
        }
        if (viewer.getServer() != null) {
            ServerPlayer onlinePlayer = viewer.getServer().getPlayerList().getPlayer(playerUuid);
            if (onlinePlayer != null) {
                return onlinePlayer.getGameProfile().getName();
            }
        }
        NationMemberRecord member = data.getMember(playerUuid);
        if (member != null && !member.lastKnownName().isBlank()) {
            return member.lastKnownName();
        }
        return playerUuid.toString();
    }

    private static String nameOrFallback(NationRecord nation, String fallbackId) {
        if (nation != null && !nation.name().isBlank()) {
            return nation.name();
        }
        return fallbackId == null || fallbackId.isBlank() ? "-" : fallbackId;
    }

    private TownOverviewService() {
    }
}