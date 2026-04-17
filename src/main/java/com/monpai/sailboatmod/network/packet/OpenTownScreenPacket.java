package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.TownClientHooks;
import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;
import com.monpai.sailboatmod.nation.menu.NationOverviewMember;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class OpenTownScreenPacket {
    private static final int MAX_MEMBER_COUNT = 1024;
    private static final int MAX_CLAIM_COUNT = 20000;
    private static final int MAX_PREVIEW_LINE_COUNT = 256;
    private static final int MAX_JOINABLE_NATION_TARGET_COUNT = 2048;
    private final TownOverviewData data;

    public OpenTownScreenPacket(TownOverviewData data) {
        this.data = data == null ? TownOverviewData.empty() : data;
    }

    public static void encode(OpenTownScreenPacket packet, FriendlyByteBuf buffer) {
        TownOverviewData data = packet.data;
        buffer.writeBoolean(data.hasTown());
        PacketStringCodec.writeUtfSafe(buffer, data.townId(), 40);
        PacketStringCodec.writeUtfSafe(buffer, data.townName(), 64);
        PacketStringCodec.writeUtfSafe(buffer, data.nationId(), 40);
        PacketStringCodec.writeUtfSafe(buffer, data.nationName(), 64);
        PacketStringCodec.writeUtfSafe(buffer, data.mayorUuid(), 40);
        PacketStringCodec.writeUtfSafe(buffer, data.mayorName(), 64);
        buffer.writeBoolean(data.capitalTown());
        buffer.writeInt(data.primaryColorRgb());
        buffer.writeInt(data.secondaryColorRgb());
        buffer.writeBoolean(data.hasCore());
        PacketStringCodec.writeUtfSafe(buffer, data.coreDimension(), 128);
        buffer.writeLong(data.corePos());
        buffer.writeVarInt(data.totalClaims());
        buffer.writeVarInt(data.residentCount());
        buffer.writeInt(data.currentChunkX());
        buffer.writeInt(data.currentChunkZ());
        buffer.writeInt(data.previewCenterChunkX());
        buffer.writeInt(data.previewCenterChunkZ());
        buffer.writeLong(data.claimMapState().revision());
        buffer.writeVarInt(data.claimMapState().radius());
        buffer.writeInt(data.claimMapState().centerChunkX());
        buffer.writeInt(data.claimMapState().centerChunkZ());
        buffer.writeBoolean(data.claimMapState().loading());
        buffer.writeBoolean(data.claimMapState().ready());
        buffer.writeBoolean(data.currentChunkClaimed());
        buffer.writeBoolean(data.currentChunkOwnedByTown());
        PacketStringCodec.writeUtfSafe(buffer, data.currentChunkOwnerName(), 64);
        PacketStringCodec.writeUtfSafe(buffer, data.breakAccessLevel(), 16);
        PacketStringCodec.writeUtfSafe(buffer, data.placeAccessLevel(), 16);
        PacketStringCodec.writeUtfSafe(buffer, data.useAccessLevel(), 16);
        PacketStringCodec.writeUtfSafe(buffer, data.containerAccessLevel(), 16);
        PacketStringCodec.writeUtfSafe(buffer, data.redstoneAccessLevel(), 16);
        PacketStringCodec.writeUtfSafe(buffer, data.entityUseAccessLevel(), 16);
        PacketStringCodec.writeUtfSafe(buffer, data.entityDamageAccessLevel(), 16);
        PacketStringCodec.writeUtfSafe(buffer, data.flagId(), 128);
        buffer.writeVarInt(data.flagWidth());
        buffer.writeVarInt(data.flagHeight());
        buffer.writeLong(data.flagByteSize());
        PacketStringCodec.writeUtfSafe(buffer, data.flagHash(), 80);
        buffer.writeBoolean(data.flagMirrored());
        buffer.writeBoolean(data.canManageTown());
        buffer.writeBoolean(data.canManageClaims());
        buffer.writeBoolean(data.canUploadFlag());
        buffer.writeBoolean(data.canAssignMayor());
        buffer.writeBoolean(data.isMayor());
        buffer.writeVarInt(data.members().size());
        for (NationOverviewMember member : data.members()) {
            PacketStringCodec.writeUtfSafe(buffer, member.playerUuid(), 40);
            PacketStringCodec.writeUtfSafe(buffer, member.playerName(), 64);
            PacketStringCodec.writeUtfSafe(buffer, member.officeId(), 40);
            PacketStringCodec.writeUtfSafe(buffer, member.officeName(), 64);
            buffer.writeBoolean(member.online());
        }
        buffer.writeVarInt(data.nearbyClaims().size());
        for (NationOverviewClaim claim : data.nearbyClaims()) {
            buffer.writeInt(claim.chunkX());
            buffer.writeInt(claim.chunkZ());
            PacketStringCodec.writeUtfSafe(buffer, claim.nationId(), 40);
            PacketStringCodec.writeUtfSafe(buffer, claim.nationName(), 64);
            buffer.writeInt(claim.primaryColorRgb());
            buffer.writeInt(claim.secondaryColorRgb());
            PacketStringCodec.writeUtfSafe(buffer, claim.townId(), 40);
            PacketStringCodec.writeUtfSafe(buffer, claim.townName(), 64);
            PacketStringCodec.writeUtfSafe(buffer, claim.breakAccessLevel(), 16);
            PacketStringCodec.writeUtfSafe(buffer, claim.placeAccessLevel(), 16);
            PacketStringCodec.writeUtfSafe(buffer, claim.useAccessLevel(), 16);
            PacketStringCodec.writeUtfSafe(buffer, claim.containerAccessLevel(), 16);
            PacketStringCodec.writeUtfSafe(buffer, claim.redstoneAccessLevel(), 16);
            PacketStringCodec.writeUtfSafe(buffer, claim.entityUseAccessLevel(), 16);
            PacketStringCodec.writeUtfSafe(buffer, claim.entityDamageAccessLevel(), 16);
        }
        PacketStringCodec.writeUtfSafe(buffer, data.cultureId(), 32);
        buffer.writeVarInt(data.cultureDistribution().size());
        for (var entry : data.cultureDistribution().entrySet()) {
            PacketStringCodec.writeUtfSafe(buffer, entry.getKey(), 32);
            buffer.writeVarInt(entry.getValue());
        }
        buffer.writeFloat(data.averageLiteracy());
        buffer.writeVarInt(data.educationLevelDistribution().size());
        for (var entry : data.educationLevelDistribution().entrySet()) {
            PacketStringCodec.writeUtfSafe(buffer, entry.getKey(), 32);
            buffer.writeVarInt(entry.getValue());
        }
        buffer.writeFloat(data.employmentRate());
        buffer.writeVarInt(data.stockpileCommodityTypes());
        buffer.writeVarInt(data.stockpileTotalUnits());
        buffer.writeVarInt(data.openDemandCount());
        buffer.writeVarInt(data.openDemandUnits());
        buffer.writeVarInt(data.activeProcurementCount());
        buffer.writeLong(data.totalIncome());
        buffer.writeLong(data.totalExpense());
        buffer.writeLong(data.netBalance());
        writeLines(buffer, data.stockpilePreviewLines(), 80);
        writeLines(buffer, data.demandPreviewLines(), 80);
        writeLines(buffer, data.procurementPreviewLines(), 80);
        writeLines(buffer, data.financePreviewLines(), 80);
        buffer.writeVarInt(data.joinableNationTargets().size());
        for (TownOverviewData.JoinableNationTarget target : data.joinableNationTargets()) {
            PacketStringCodec.writeUtfSafe(buffer, target.nationId(), 40);
            PacketStringCodec.writeUtfSafe(buffer, target.nationName(), 64);
        }
    }

    public static OpenTownScreenPacket decode(FriendlyByteBuf buffer) {
        boolean hasTown = buffer.readBoolean();
        String townId = buffer.readUtf(40);
        String townName = buffer.readUtf(64);
        String nationId = buffer.readUtf(40);
        String nationName = buffer.readUtf(64);
        String mayorUuid = buffer.readUtf(40);
        String mayorName = buffer.readUtf(64);
        boolean capitalTown = buffer.readBoolean();
        int primaryColorRgb = buffer.readInt();
        int secondaryColorRgb = buffer.readInt();
        boolean hasCore = buffer.readBoolean();
        String coreDimension = buffer.readUtf(128);
        long corePos = buffer.readLong();
        int totalClaims = buffer.readVarInt();
        int residentCount = buffer.readVarInt();
        int currentChunkX = buffer.readInt();
        int currentChunkZ = buffer.readInt();
        int previewCenterChunkX = buffer.readInt();
        int previewCenterChunkZ = buffer.readInt();
        long claimMapRevision = buffer.readLong();
        int claimMapRadius = buffer.readVarInt();
        int claimMapCenterChunkX = buffer.readInt();
        int claimMapCenterChunkZ = buffer.readInt();
        boolean claimMapLoading = buffer.readBoolean();
        boolean claimMapReady = buffer.readBoolean();
        boolean currentChunkClaimed = buffer.readBoolean();
        boolean currentChunkOwnedByTown = buffer.readBoolean();
        String currentChunkOwnerName = buffer.readUtf(64);
        String breakAccessLevel = buffer.readUtf(16);
        String placeAccessLevel = buffer.readUtf(16);
        String useAccessLevel = buffer.readUtf(16);
        String containerAccessLevel = buffer.readUtf(16);
        String redstoneAccessLevel = buffer.readUtf(16);
        String entityUseAccessLevel = buffer.readUtf(16);
        String entityDamageAccessLevel = buffer.readUtf(16);
        String flagId = buffer.readUtf(128);
        int flagWidth = buffer.readVarInt();
        int flagHeight = buffer.readVarInt();
        long flagByteSize = buffer.readLong();
        String flagHash = buffer.readUtf(80);
        boolean flagMirrored = buffer.readBoolean();
        boolean canManageTown = buffer.readBoolean();
        boolean canManageClaims = buffer.readBoolean();
        boolean canUploadFlag = buffer.readBoolean();
        boolean canAssignMayor = buffer.readBoolean();
        boolean isMayor = buffer.readBoolean();
        int memberCount = readBoundedCount(buffer, MAX_MEMBER_COUNT, "memberCount");
        List<NationOverviewMember> members = new ArrayList<>(memberCount);
        for (int i = 0; i < memberCount; i++) {
            members.add(new NationOverviewMember(
                    buffer.readUtf(40),
                    buffer.readUtf(64),
                    buffer.readUtf(40),
                    buffer.readUtf(64),
                    buffer.readBoolean()
            ));
        }
        int claimCount = readBoundedCount(buffer, MAX_CLAIM_COUNT, "claimCount");
        List<NationOverviewClaim> nearbyClaims = new ArrayList<>(claimCount);
        for (int i = 0; i < claimCount; i++) {
            nearbyClaims.add(new NationOverviewClaim(
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readUtf(40),
                    buffer.readUtf(64),
                    buffer.readInt(),
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
        String cultureId = buffer.readUtf(32);
        int cultureDistSize = readBoundedCount(buffer, MAX_PREVIEW_LINE_COUNT, "cultureDistributionSize");
        java.util.Map<String, Integer> cultureDistribution = new java.util.HashMap<>();
        for (int i = 0; i < cultureDistSize; i++) {
            cultureDistribution.put(buffer.readUtf(32), buffer.readVarInt());
        }
        float averageLiteracy = buffer.readFloat();
        int eduDistSize = readBoundedCount(buffer, MAX_PREVIEW_LINE_COUNT, "educationDistributionSize");
        java.util.Map<String, Integer> educationLevelDistribution = new java.util.HashMap<>();
        for (int i = 0; i < eduDistSize; i++) {
            educationLevelDistribution.put(buffer.readUtf(32), buffer.readVarInt());
        }
        float employmentRate = buffer.readFloat();
        int stockpileCommodityTypes = buffer.readVarInt();
        int stockpileTotalUnits = buffer.readVarInt();
        int openDemandCount = buffer.readVarInt();
        int openDemandUnits = buffer.readVarInt();
        int activeProcurementCount = buffer.readVarInt();
        long totalIncome = buffer.readLong();
        long totalExpense = buffer.readLong();
        long netBalance = buffer.readLong();
        List<String> stockpilePreviewLines = readLines(buffer, 80);
        List<String> demandPreviewLines = readLines(buffer, 80);
        List<String> procurementPreviewLines = readLines(buffer, 80);
        List<String> financePreviewLines = readLines(buffer, 80);
        int joinableNationTargetCount = readBoundedCount(buffer, MAX_JOINABLE_NATION_TARGET_COUNT, "joinableNationTargetCount");
        List<TownOverviewData.JoinableNationTarget> joinableNationTargets = new ArrayList<>(joinableNationTargetCount);
        for (int i = 0; i < joinableNationTargetCount; i++) {
            joinableNationTargets.add(new TownOverviewData.JoinableNationTarget(
                    buffer.readUtf(40),
                    buffer.readUtf(64)
            ));
        }
        ClaimPreviewMapState claimMapState = new ClaimPreviewMapState(
                claimMapRevision,
                claimMapRadius,
                claimMapCenterChunkX,
                claimMapCenterChunkZ,
                claimMapLoading,
                claimMapReady,
                List.of()
        );
        return new OpenTownScreenPacket(new TownOverviewData(
                hasTown,
                townId,
                townName,
                nationId,
                nationName,
                mayorUuid,
                mayorName,
                capitalTown,
                primaryColorRgb,
                secondaryColorRgb,
                hasCore,
                coreDimension,
                corePos,
                totalClaims,
                residentCount,
                currentChunkX,
                currentChunkZ,
                previewCenterChunkX,
                previewCenterChunkZ,
                currentChunkClaimed,
                currentChunkOwnedByTown,
                currentChunkOwnerName,
                breakAccessLevel,
                placeAccessLevel,
                useAccessLevel,
                containerAccessLevel,
                redstoneAccessLevel,
                entityUseAccessLevel,
                entityDamageAccessLevel,
                flagId,
                flagWidth,
                flagHeight,
                flagByteSize,
                flagHash,
                flagMirrored,
                canManageTown,
                canManageClaims,
                canUploadFlag,
                canAssignMayor,
                isMayor,
                members,
                List.of(),
                nearbyClaims,
                cultureId,
                cultureDistribution,
                averageLiteracy,
                educationLevelDistribution,
                employmentRate,
                stockpileCommodityTypes,
                stockpileTotalUnits,
                openDemandCount,
                openDemandUnits,
                activeProcurementCount,
                totalIncome,
                totalExpense,
                netBalance,
                stockpilePreviewLines,
                demandPreviewLines,
                procurementPreviewLines,
                financePreviewLines,
                joinableNationTargets,
                claimMapState
        ));
    }

    public static void handle(OpenTownScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> TownClientHooks.openOrUpdate(packet.data)));
        context.setPacketHandled(true);
    }

    private static void writeLines(FriendlyByteBuf buffer, List<String> lines, int maxLength) {
        List<String> safeLines = lines == null ? List.of() : lines;
        buffer.writeVarInt(safeLines.size());
        for (String line : safeLines) {
            PacketStringCodec.writeUtfSafe(buffer, line, maxLength);
        }
    }

    private static List<String> readLines(FriendlyByteBuf buffer, int maxLength) {
        int size = readBoundedCount(buffer, MAX_PREVIEW_LINE_COUNT, "lineCount");
        List<String> lines = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            lines.add(buffer.readUtf(maxLength));
        }
        return lines;
    }

    private static int readBoundedCount(FriendlyByteBuf buffer, int maxAllowed, String label) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maxAllowed) {
            throw new IllegalArgumentException(label + " exceeds max allowed count: " + count);
        }
        return count;
    }
}
