package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.TownClientHooks;
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
        buffer.writeInt(data.currentChunkX());
        buffer.writeInt(data.currentChunkZ());
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
        buffer.writeVarInt(data.nearbyTerrainColors().size());
        for (Integer color : data.nearbyTerrainColors()) {
            buffer.writeInt(color == null ? 0xFF33414A : color);
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
        int currentChunkX = buffer.readInt();
        int currentChunkZ = buffer.readInt();
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
        int memberCount = buffer.readVarInt();
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
        int terrainColorCount = buffer.readVarInt();
        List<Integer> nearbyTerrainColors = new ArrayList<>(terrainColorCount);
        for (int i = 0; i < terrainColorCount; i++) {
            nearbyTerrainColors.add(buffer.readInt());
        }
        int claimCount = buffer.readVarInt();
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
        int cultureDistSize = buffer.readVarInt();
        java.util.Map<String, Integer> cultureDistribution = new java.util.HashMap<>();
        for (int i = 0; i < cultureDistSize; i++) {
            cultureDistribution.put(buffer.readUtf(32), buffer.readVarInt());
        }
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
                currentChunkX,
                currentChunkZ,
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
                nearbyTerrainColors,
                nearbyClaims,
                cultureId,
                cultureDistribution
        ));
    }

    public static void handle(OpenTownScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> TownClientHooks.openOrUpdate(packet.data)));
        context.setPacketHandled(true);
    }
}