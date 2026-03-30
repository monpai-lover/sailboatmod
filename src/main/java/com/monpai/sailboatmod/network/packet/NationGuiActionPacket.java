package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import com.monpai.sailboatmod.nation.service.NationClaimService;
import com.monpai.sailboatmod.nation.service.NationDiplomacyService;
import com.monpai.sailboatmod.nation.service.NationFlagService;
import com.monpai.sailboatmod.nation.service.NationOverviewService;
import com.monpai.sailboatmod.nation.service.NationResult;
import com.monpai.sailboatmod.nation.service.NationService;
import com.monpai.sailboatmod.nation.service.NationWarService;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

public class NationGuiActionPacket {
    private final Action action;
    private final int chunkX;
    private final int chunkZ;
    private final String memberUuid;
    private final String text;

    public NationGuiActionPacket(Action action) {
        this(action, 0, 0, "", "");
    }

    public NationGuiActionPacket(Action action, int chunkX, int chunkZ) {
        this(action, chunkX, chunkZ, "", "");
    }

    public NationGuiActionPacket(Action action, String memberUuid) {
        this(action, 0, 0, memberUuid, "");
    }

    public NationGuiActionPacket(Action action, String memberUuid, String text) {
        this(action, 0, 0, memberUuid, text);
    }

    public NationGuiActionPacket(Action action, String text, boolean textOnly) {
        this(action, 0, 0, "", text);
    }

    private NationGuiActionPacket(Action action, int chunkX, int chunkZ, String memberUuid, String text) {
        this.action = action == null ? Action.REFRESH : action;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.memberUuid = memberUuid == null ? "" : memberUuid.trim();
        this.text = text == null ? "" : text.trim();
    }

    public static void encode(NationGuiActionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.action);
        buffer.writeInt(packet.chunkX);
        buffer.writeInt(packet.chunkZ);
        PacketStringCodec.writeUtfSafe(buffer, packet.memberUuid, 40);
        PacketStringCodec.writeUtfSafe(buffer, packet.text, 64);
    }

    public static NationGuiActionPacket decode(FriendlyByteBuf buffer) {
        return new NationGuiActionPacket(
                buffer.readEnum(Action.class),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readUtf(40),
                buffer.readUtf(64)
        );
    }

    public static void handle(NationGuiActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            NationResult result = switch (packet.action) {
                case REFRESH -> NationResult.success(Component.empty());
                case CLAIM_CHUNK -> NationClaimService.claimChunk(player, new ChunkPos(packet.chunkX, packet.chunkZ));
                case UNCLAIM_CHUNK -> NationClaimService.unclaimChunk(player, new ChunkPos(packet.chunkX, packet.chunkZ));
                case APPOINT_OFFICER -> {
                    UUID targetUuid = parseUuid(packet.memberUuid);
                    yield targetUuid == null
                            ? NationResult.failure(Component.translatable("command.sailboatmod.nation.member.invalid"))
                            : NationService.assignOfficer(player, targetUuid);
                }
                case REMOVE_OFFICER -> {
                    UUID targetUuid = parseUuid(packet.memberUuid);
                    yield targetUuid == null
                            ? NationResult.failure(Component.translatable("command.sailboatmod.nation.member.invalid"))
                            : NationService.removeOfficer(player, targetUuid);
                }
                case APPOINT_MAYOR -> {
                    UUID targetUuid = parseUuid(packet.memberUuid);
                    yield targetUuid == null
                            ? NationResult.failure(Component.translatable("command.sailboatmod.nation.member.invalid"))
                            : TownService.assignMayorById(player, packet.text, targetUuid);
                }
                case RENAME_OFFICER_TITLE -> NationService.renameOfficerTitle(player, packet.text);
                case CREATE_NATION -> NationService.createNation(player, packet.text);
                case RENAME_NATION -> NationService.renameNation(player, packet.text);
                case SET_SHORT_NAME -> NationService.setShortName(player, packet.text);
                case JOIN_NATION -> NationService.joinNation(player, packet.text);
                case LEAVE_NATION -> NationService.leaveNation(player);
                case KICK_MEMBER -> {
                    UUID targetUuid = parseUuid(packet.memberUuid);
                    yield targetUuid == null
                            ? NationResult.failure(Component.translatable("command.sailboatmod.nation.member.invalid"))
                            : NationService.kickMemberByUuid(player, targetUuid);
                }
                case SET_COLOR_PRIMARY -> NationService.setPrimaryColor(player, packet.text);
                case SET_COLOR_SECONDARY -> NationService.setSecondaryColor(player, packet.text);
                case DECLARE_WAR -> NationWarService.declareWar(player, packet.text);
                case DIPLOMACY_ALLY -> NationDiplomacyService.requestAlliance(player, packet.text);
                case DIPLOMACY_TRADE -> NationDiplomacyService.setTrade(player, packet.text);
                case DIPLOMACY_NEUTRAL -> NationDiplomacyService.setNeutral(player, packet.text);
                case DIPLOMACY_ACCEPT -> NationDiplomacyService.acceptAlliance(player, packet.text);
                case DIPLOMACY_REJECT -> NationDiplomacyService.rejectAlliance(player, packet.text);
                case TOGGLE_FLAG_MIRROR -> NationFlagService.setMirrored(player, null);
            };

            if (!result.message().getString().isBlank()) {
                player.sendSystemMessage(result.message());
                if (!result.success()) {
                    NationToastPacket.send(player, Component.translatable("toast.sailboatmod.nation.title"), result.message());
                }
            }

            NationOverviewData data = NationOverviewService.buildFor(player);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenNationScreenPacket(data));
        });
        context.setPacketHandled(true);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public enum Action {
        REFRESH,
        CLAIM_CHUNK,
        UNCLAIM_CHUNK,
        APPOINT_OFFICER,
        REMOVE_OFFICER,
        APPOINT_MAYOR,
        RENAME_OFFICER_TITLE,
        CREATE_NATION,
        RENAME_NATION,
        SET_SHORT_NAME,
        JOIN_NATION,
        LEAVE_NATION,
        KICK_MEMBER,
        SET_COLOR_PRIMARY,
        SET_COLOR_SECONDARY,
        DECLARE_WAR,
        DIPLOMACY_ALLY,
        DIPLOMACY_TRADE,
        DIPLOMACY_NEUTRAL,
        DIPLOMACY_ACCEPT,
        DIPLOMACY_REJECT,
        TOGGLE_FLAG_MIRROR
    }
}
