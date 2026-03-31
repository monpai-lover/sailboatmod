package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.NationClientHooks;
import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;
import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import com.monpai.sailboatmod.nation.menu.NationOverviewDiplomacyEntry;
import com.monpai.sailboatmod.nation.menu.NationOverviewDiplomacyRequest;
import com.monpai.sailboatmod.nation.menu.NationOverviewMember;
import com.monpai.sailboatmod.nation.menu.NationOverviewNationEntry;
import com.monpai.sailboatmod.nation.menu.NationOverviewTown;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class OpenNationScreenPacket {
    private final NationOverviewData data;
    private final boolean warSyncOnly;

    public OpenNationScreenPacket(NationOverviewData data) {
        this(data, false);
    }

    public OpenNationScreenPacket(NationOverviewData data, boolean warSyncOnly) {
        this.data = data;
        this.warSyncOnly = warSyncOnly;
    }

    public static void encode(OpenNationScreenPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.warSyncOnly);
        NationOverviewData data = packet.data;
        buffer.writeBoolean(data.hasNation());
        writeUtfSafe(buffer, data.nationId(), 40);
        writeUtfSafe(buffer, data.nationName(), 64);
        writeUtfSafe(buffer, data.shortName(), 16);
        buffer.writeInt(data.primaryColorRgb());
        buffer.writeInt(data.secondaryColorRgb());
        writeUtfSafe(buffer, data.leaderName(), 64);
        writeUtfSafe(buffer, data.officeName(), 64);
        writeUtfSafe(buffer, data.capitalTownId(), 40);
        writeUtfSafe(buffer, data.capitalTownName(), 64);
        buffer.writeVarInt(data.memberCount());
        buffer.writeBoolean(data.hasCore());
        writeUtfSafe(buffer, data.coreDimension(), 128);
        buffer.writeLong(data.corePos());
        buffer.writeVarInt(data.totalClaims());
        buffer.writeInt(data.currentChunkX());
        buffer.writeInt(data.currentChunkZ());
        buffer.writeBoolean(data.currentChunkClaimed());
        buffer.writeBoolean(data.currentChunkOwnedByNation());
        writeUtfSafe(buffer, data.currentChunkOwnerName(), 64);
        writeUtfSafe(buffer, data.breakAccessLevel(), 16);
        writeUtfSafe(buffer, data.placeAccessLevel(), 16);
        writeUtfSafe(buffer, data.useAccessLevel(), 16);
        writeUtfSafe(buffer, data.containerAccessLevel(), 16);
        writeUtfSafe(buffer, data.redstoneAccessLevel(), 16);
        writeUtfSafe(buffer, data.entityUseAccessLevel(), 16);
        writeUtfSafe(buffer, data.entityDamageAccessLevel(), 16);
        buffer.writeBoolean(data.hasActiveWar());
        writeUtfSafe(buffer, data.warOpponentName(), 64);
        buffer.writeVarInt(data.warScoreSelf());
        buffer.writeVarInt(data.warScoreOpponent());
        buffer.writeVarInt(data.warCaptureProgress());
        buffer.writeVarInt(data.warScoreLimit());
        writeUtfSafe(buffer, data.warStatus(), 24);
        buffer.writeVarInt(data.warTimeRemainingSeconds());
        buffer.writeVarInt(data.warCooldownRemainingSeconds());
        writeUtfSafe(buffer, data.flagId(), 128);
        buffer.writeVarInt(data.flagWidth());
        buffer.writeVarInt(data.flagHeight());
        buffer.writeLong(data.flagByteSize());
        writeUtfSafe(buffer, data.flagHash(), 80);
        buffer.writeBoolean(data.flagMirrored());
        buffer.writeBoolean(data.isLeader());
        buffer.writeBoolean(data.canManageInfo());
        buffer.writeBoolean(data.canManageOffices());
        buffer.writeBoolean(data.canManageClaims());
        buffer.writeBoolean(data.canUploadFlag());
        buffer.writeBoolean(data.canDeclareWar());
        buffer.writeBoolean(data.canManageTreasury());
        buffer.writeLong(data.treasuryBalance());
        buffer.writeVarInt(data.salesTaxBasisPoints());
        buffer.writeVarInt(data.importTariffBasisPoints());
        buffer.writeVarInt(data.recentTradeCount());
        writeUtfSafe(buffer, data.officerTitle(), 64);
        buffer.writeVarInt(data.diplomacyRelations().size());
        for (NationOverviewDiplomacyEntry relation : data.diplomacyRelations()) {
            writeUtfSafe(buffer, relation.nationId(), 40);
            writeUtfSafe(buffer, relation.nationName(), 64);
            writeUtfSafe(buffer, relation.statusId(), 24);
        }
        buffer.writeVarInt(data.incomingDiplomacyRequests().size());
        for (NationOverviewDiplomacyRequest request : data.incomingDiplomacyRequests()) {
            writeUtfSafe(buffer, request.nationId(), 40);
            writeUtfSafe(buffer, request.nationName(), 64);
            writeUtfSafe(buffer, request.statusId(), 24);
        }
        buffer.writeVarInt(data.members().size());
        for (NationOverviewMember member : data.members()) {
            writeUtfSafe(buffer, member.playerUuid(), 40);
            writeUtfSafe(buffer, member.playerName(), 64);
            writeUtfSafe(buffer, member.officeId(), 32);
            writeUtfSafe(buffer, member.officeName(), 64);
            buffer.writeBoolean(member.online());
        }
        buffer.writeVarInt(data.towns().size());
        for (NationOverviewTown town : data.towns()) {
            writeUtfSafe(buffer, town.townId(), 40);
            writeUtfSafe(buffer, town.townName(), 64);
            writeUtfSafe(buffer, town.mayorName(), 64);
            buffer.writeVarInt(town.claimCount());
            buffer.writeBoolean(town.capital());
        }
        buffer.writeVarInt(data.nearbyTerrainColors().size());
        for (Integer color : data.nearbyTerrainColors()) {
            buffer.writeInt(color == null ? 0xFF33414A : color);
        }
        buffer.writeVarInt(data.nearbyClaims().size());
        for (NationOverviewClaim claim : data.nearbyClaims()) {
            buffer.writeInt(claim.chunkX());
            buffer.writeInt(claim.chunkZ());
            writeUtfSafe(buffer, claim.nationId(), 40);
            writeUtfSafe(buffer, claim.nationName(), 64);
            buffer.writeInt(claim.primaryColorRgb());
            writeUtfSafe(buffer, claim.townId(), 40);
            writeUtfSafe(buffer, claim.townName(), 64);
            writeUtfSafe(buffer, claim.breakAccessLevel(), 16);
            writeUtfSafe(buffer, claim.placeAccessLevel(), 16);
            writeUtfSafe(buffer, claim.useAccessLevel(), 16);
            writeUtfSafe(buffer, claim.containerAccessLevel(), 16);
            writeUtfSafe(buffer, claim.redstoneAccessLevel(), 16);
            writeUtfSafe(buffer, claim.entityUseAccessLevel(), 16);
            writeUtfSafe(buffer, claim.entityDamageAccessLevel(), 16);
        }
        buffer.writeVarInt(data.allNations().size());
        for (NationOverviewNationEntry entry : data.allNations()) {
            writeUtfSafe(buffer, entry.nationId(), 40);
            writeUtfSafe(buffer, entry.nationName(), 64);
            writeUtfSafe(buffer, entry.shortName(), 16);
            buffer.writeInt(entry.primaryColorRgb());
            buffer.writeInt(entry.secondaryColorRgb());
            writeUtfSafe(buffer, entry.flagId(), 128);
            buffer.writeBoolean(entry.flagMirrored());
            buffer.writeVarInt(entry.memberCount());
            writeUtfSafe(buffer, entry.diplomacyStatusId(), 24);
        }
    }

    public static OpenNationScreenPacket decode(FriendlyByteBuf buffer) {
        boolean warSyncOnly = buffer.readBoolean();
        boolean hasNation = buffer.readBoolean();
        String nationId = buffer.readUtf(40);
        String nationName = buffer.readUtf(64);
        String shortName = buffer.readUtf(16);
        int primaryColorRgb = buffer.readInt();
        int secondaryColorRgb = buffer.readInt();
        String leaderName = buffer.readUtf(64);
        String officeName = buffer.readUtf(64);
        String capitalTownId = buffer.readUtf(40);
        String capitalTownName = buffer.readUtf(64);
        int memberCount = buffer.readVarInt();
        boolean hasCore = buffer.readBoolean();
        String coreDimension = buffer.readUtf(128);
        long corePos = buffer.readLong();
        int totalClaims = buffer.readVarInt();
        int currentChunkX = buffer.readInt();
        int currentChunkZ = buffer.readInt();
        boolean currentChunkClaimed = buffer.readBoolean();
        boolean currentChunkOwnedByNation = buffer.readBoolean();
        String currentChunkOwnerName = buffer.readUtf(64);
        String breakAccessLevel = buffer.readUtf(16);
        String placeAccessLevel = buffer.readUtf(16);
        String useAccessLevel = buffer.readUtf(16);
        String containerAccessLevel = buffer.readUtf(16);
        String redstoneAccessLevel = buffer.readUtf(16);
        String entityUseAccessLevel = buffer.readUtf(16);
        String entityDamageAccessLevel = buffer.readUtf(16);
        boolean hasActiveWar = buffer.readBoolean();
        String warOpponentName = buffer.readUtf(64);
        int warScoreSelf = buffer.readVarInt();
        int warScoreOpponent = buffer.readVarInt();
        int warCaptureProgress = buffer.readVarInt();
        int warScoreLimit = buffer.readVarInt();
        String warStatus = buffer.readUtf(24);
        int warTimeRemainingSeconds = buffer.readVarInt();
        int warCooldownRemainingSeconds = buffer.readVarInt();
        String flagId = buffer.readUtf(128);
        int flagWidth = buffer.readVarInt();
        int flagHeight = buffer.readVarInt();
        long flagByteSize = buffer.readLong();
        String flagHash = buffer.readUtf(80);
        boolean flagMirrored = buffer.readBoolean();
        boolean isLeader = buffer.readBoolean();
        boolean canManageInfo = buffer.readBoolean();
        boolean canManageOffices = buffer.readBoolean();
        boolean canManageClaims = buffer.readBoolean();
        boolean canUploadFlag = buffer.readBoolean();
        boolean canDeclareWar = buffer.readBoolean();
        boolean canManageTreasury = buffer.readBoolean();
        long treasuryBalance = buffer.readLong();
        int salesTaxBasisPoints = buffer.readVarInt();
        int importTariffBasisPoints = buffer.readVarInt();
        int recentTradeCount = buffer.readVarInt();
        String officerTitle = buffer.readUtf(64);
        int diplomacyRelationSize = buffer.readVarInt();
        List<NationOverviewDiplomacyEntry> diplomacyRelations = new ArrayList<>(diplomacyRelationSize);
        for (int i = 0; i < diplomacyRelationSize; i++) {
            diplomacyRelations.add(new NationOverviewDiplomacyEntry(
                    buffer.readUtf(40),
                    buffer.readUtf(64),
                    buffer.readUtf(24)
            ));
        }
        int diplomacyRequestSize = buffer.readVarInt();
        List<NationOverviewDiplomacyRequest> incomingDiplomacyRequests = new ArrayList<>(diplomacyRequestSize);
        for (int i = 0; i < diplomacyRequestSize; i++) {
            incomingDiplomacyRequests.add(new NationOverviewDiplomacyRequest(
                    buffer.readUtf(40),
                    buffer.readUtf(64),
                    buffer.readUtf(24)
            ));
        }
        int memberListSize = buffer.readVarInt();
        List<NationOverviewMember> members = new ArrayList<>(memberListSize);
        for (int i = 0; i < memberListSize; i++) {
            members.add(new NationOverviewMember(
                    buffer.readUtf(40),
                    buffer.readUtf(64),
                    buffer.readUtf(32),
                    buffer.readUtf(64),
                    buffer.readBoolean()
            ));
        }
        int townListSize = buffer.readVarInt();
        List<NationOverviewTown> towns = new ArrayList<>(townListSize);
        for (int i = 0; i < townListSize; i++) {
            towns.add(new NationOverviewTown(
                    buffer.readUtf(40),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readVarInt(),
                    buffer.readBoolean()
            ));
        }
        int terrainColorCount = buffer.readVarInt();
        List<Integer> nearbyTerrainColors = new ArrayList<>(terrainColorCount);
        for (int i = 0; i < terrainColorCount; i++) {
            nearbyTerrainColors.add(buffer.readInt());
        }
        int claimListSize = buffer.readVarInt();
        List<NationOverviewClaim> nearbyClaims = new ArrayList<>(claimListSize);
        for (int i = 0; i < claimListSize; i++) {
            nearbyClaims.add(new NationOverviewClaim(
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readUtf(40),
                    buffer.readUtf(64),
                    buffer.readInt(),
                    buffer.readUtf(40),
                    buffer.readUtf(64),
                    buffer.readUtf(16),
                    buffer.readUtf(16),
                    buffer.readUtf(16),
                    buffer.readUtf(16),
                    buffer.readUtf(16),
                    buffer.readUtf(16),
                    buffer.readUtf(16)
            ));
        }
        int nationListSize = buffer.readVarInt();
        List<NationOverviewNationEntry> allNations = new ArrayList<>(nationListSize);
        for (int i = 0; i < nationListSize; i++) {
            allNations.add(new NationOverviewNationEntry(
                    buffer.readUtf(40),
                    buffer.readUtf(64),
                    buffer.readUtf(16),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readUtf(128),
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readUtf(24)
            ));
        }
        return new OpenNationScreenPacket(new NationOverviewData(
                hasNation,
                nationId,
                nationName,
                shortName,
                primaryColorRgb,
                secondaryColorRgb,
                leaderName,
                officeName,
                capitalTownId,
                capitalTownName,
                memberCount,
                hasCore,
                coreDimension,
                corePos,
                totalClaims,
                currentChunkX,
                currentChunkZ,
                currentChunkClaimed,
                currentChunkOwnedByNation,
                currentChunkOwnerName,
                breakAccessLevel,
                placeAccessLevel,
                useAccessLevel,
                containerAccessLevel,
                redstoneAccessLevel,
                entityUseAccessLevel,
                entityDamageAccessLevel,
                hasActiveWar,
                warOpponentName,
                warScoreSelf,
                warScoreOpponent,
                warCaptureProgress,
                warScoreLimit,
                warStatus,
                warTimeRemainingSeconds,
                warCooldownRemainingSeconds,
                flagId,
                flagWidth,
                flagHeight,
                flagByteSize,
                flagHash,
                flagMirrored,
                isLeader,
                canManageInfo,
                canManageOffices,
                canManageClaims,
                canUploadFlag,
                canDeclareWar,
                canManageTreasury,
                treasuryBalance,
                salesTaxBasisPoints,
                importTariffBasisPoints,
                recentTradeCount,
                officerTitle,
                diplomacyRelations,
                incomingDiplomacyRequests,
                members,
                towns,
                nearbyTerrainColors,
                nearbyClaims,
                allNations
        ), warSyncOnly);
    }

    public static void handle(OpenNationScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (packet.warSyncOnly) {
                NationClientHooks.updateIfOpen(packet.data);
            } else {
                NationClientHooks.openOrUpdate(packet.data);
            }
        }));
        context.setPacketHandled(true);
    }

    private static void writeUtfSafe(FriendlyByteBuf buffer, String value, int maxLength) {
        buffer.writeUtf(trimToLength(value, maxLength), maxLength);
    }

    private static String trimToLength(String value, int maxLength) {
        if (value == null || value.isEmpty() || maxLength <= 0) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}