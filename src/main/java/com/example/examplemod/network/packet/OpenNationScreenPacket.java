package com.example.examplemod.network.packet;

import com.example.examplemod.client.NationClientHooks;
import com.example.examplemod.nation.menu.NationOverviewClaim;
import com.example.examplemod.nation.menu.NationOverviewData;
import com.example.examplemod.nation.menu.NationOverviewDiplomacyEntry;
import com.example.examplemod.nation.menu.NationOverviewDiplomacyRequest;
import com.example.examplemod.nation.menu.NationOverviewMember;
import com.example.examplemod.nation.menu.NationOverviewTown;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class OpenNationScreenPacket {
    private final NationOverviewData data;

    public OpenNationScreenPacket(NationOverviewData data) {
        this.data = data;
    }

    public static void encode(OpenNationScreenPacket packet, FriendlyByteBuf buffer) {
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
            writeUtfSafe(buffer, claim.breakAccessLevel(), 16);
            writeUtfSafe(buffer, claim.placeAccessLevel(), 16);
            writeUtfSafe(buffer, claim.useAccessLevel(), 16);
        }
    }

    public static OpenNationScreenPacket decode(FriendlyByteBuf buffer) {
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
                    buffer.readUtf(16),
                    buffer.readUtf(16),
                    buffer.readUtf(16)
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
                officerTitle,
                diplomacyRelations,
                incomingDiplomacyRequests,
                members,
                towns,
                nearbyTerrainColors,
                nearbyClaims
        ));
    }

    public static void handle(OpenNationScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> NationClientHooks.openOrUpdate(packet.data)));
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