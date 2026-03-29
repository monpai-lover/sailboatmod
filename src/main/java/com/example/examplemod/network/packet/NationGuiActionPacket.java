package com.example.examplemod.network.packet;

import com.example.examplemod.nation.menu.NationOverviewData;
import com.example.examplemod.nation.service.NationClaimService;
import com.example.examplemod.nation.service.NationOverviewService;
import com.example.examplemod.nation.service.NationResult;
import com.example.examplemod.nation.service.NationService;
import com.example.examplemod.nation.service.TownService;
import com.example.examplemod.network.ModNetwork;
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
        RENAME_OFFICER_TITLE
    }
}