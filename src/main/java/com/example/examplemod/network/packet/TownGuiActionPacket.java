package com.example.examplemod.network.packet;

import com.example.examplemod.nation.menu.TownOverviewData;
import com.example.examplemod.nation.service.NationResult;
import com.example.examplemod.nation.service.TownClaimService;
import com.example.examplemod.nation.service.TownFlagService;
import com.example.examplemod.nation.service.TownOverviewService;
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

public class TownGuiActionPacket {
    private final Action action;
    private final String townId;
    private final int chunkX;
    private final int chunkZ;
    private final String text;

    public TownGuiActionPacket(Action action, String townId) {
        this(action, townId, 0, 0, "");
    }

    public TownGuiActionPacket(Action action, String townId, int chunkX, int chunkZ) {
        this(action, townId, chunkX, chunkZ, "");
    }

    public TownGuiActionPacket(Action action, String townId, String text) {
        this(action, townId, 0, 0, text);
    }

    private TownGuiActionPacket(Action action, String townId, int chunkX, int chunkZ, String text) {
        this.action = action == null ? Action.REFRESH : action;
        this.townId = townId == null ? "" : townId.trim();
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.text = text == null ? "" : text.trim();
    }

    public static void encode(TownGuiActionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.action);
        PacketStringCodec.writeUtfSafe(buffer, packet.townId, 40);
        buffer.writeInt(packet.chunkX);
        buffer.writeInt(packet.chunkZ);
        PacketStringCodec.writeUtfSafe(buffer, packet.text, 64);
    }

    public static TownGuiActionPacket decode(FriendlyByteBuf buffer) {
        return new TownGuiActionPacket(
                buffer.readEnum(Action.class),
                buffer.readUtf(40),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readUtf(64)
        );
    }

    public static void handle(TownGuiActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            NationResult result = switch (packet.action) {
                case REFRESH -> NationResult.success(Component.empty());
                case CLAIM_CHUNK -> TownClaimService.claimChunk(player, packet.townId, new ChunkPos(packet.chunkX, packet.chunkZ));
                case UNCLAIM_CHUNK -> TownClaimService.unclaimChunk(player, packet.townId, new ChunkPos(packet.chunkX, packet.chunkZ));
                case RENAME_TOWN -> TownService.renameTownById(player, packet.townId, packet.text);
                case TOGGLE_FLAG_MIRROR -> TownFlagService.setMirrored(player, packet.townId, null);
                case APPOINT_MAYOR -> {
                    UUID targetUuid = parseUuid(packet.text);
                    yield targetUuid == null
                            ? NationResult.failure(Component.translatable("command.sailboatmod.nation.member.invalid"))
                            : TownService.assignMayorById(player, packet.townId, targetUuid);
                }
            };

            if (!result.message().getString().isBlank()) {
                player.sendSystemMessage(result.message());
                if (!result.success()) {
                    NationToastPacket.send(player, Component.translatable("toast.sailboatmod.nation.title"), result.message());
                }
            }

            TownOverviewData data = TownOverviewService.buildFor(player, packet.townId);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenTownScreenPacket(data));
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
        RENAME_TOWN,
        TOGGLE_FLAG_MIRROR,
        APPOINT_MAYOR
    }
}
