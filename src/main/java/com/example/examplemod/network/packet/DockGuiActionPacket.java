package com.example.examplemod.network.packet;

import com.example.examplemod.block.entity.DockBlockEntity;
import com.example.examplemod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class DockGuiActionPacket {
    private final BlockPos dockPos;
    private final Action action;
    private final int value;

    public DockGuiActionPacket(BlockPos dockPos, Action action) {
        this(dockPos, action, -1);
    }

    public DockGuiActionPacket(BlockPos dockPos, Action action, int value) {
        this.dockPos = dockPos;
        this.action = action;
        this.value = value;
    }

    public static void encode(DockGuiActionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.dockPos);
        buffer.writeEnum(packet.action);
        buffer.writeVarInt(packet.value);
    }

    public static DockGuiActionPacket decode(FriendlyByteBuf buffer) {
        return new DockGuiActionPacket(buffer.readBlockPos(), buffer.readEnum(Action.class), buffer.readVarInt());
    }

    public static void handle(DockGuiActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.dockPos) instanceof DockBlockEntity dock)) {
                return;
            }

            switch (packet.action) {
                case LOAD_BOOK_FROM_HAND -> dock.loadRouteBookFromPlayer(player);
                case LOAD_BOOK_FROM_INVENTORY_SLOT -> dock.loadRouteBookFromInventorySlot(player, packet.value);
                case IMPORT_BOOK -> dock.importRouteBook();
                case CLEAR_BOOK -> dock.clearRouteBook();
                case DELETE_ROUTE -> dock.deleteSelectedRoute();
                case PREV_ROUTE -> dock.selectRouteDelta(-1);
                case NEXT_ROUTE -> dock.selectRouteDelta(1);
                case SELECT_ROUTE_INDEX -> dock.selectRouteIndex(packet.value);
                case REVERSE_ROUTE -> dock.reverseSelectedRoute();
                case PREV_BOAT -> dock.selectBoatDelta(-1, player);
                case NEXT_BOAT -> dock.selectBoatDelta(1, player);
                case SELECT_BOAT_INDEX -> dock.selectBoatIndex(packet.value, player);
                case ASSIGN_SELECTED -> dock.assignSelectedBoat(player, true);
                case SELECT_WAYBILL_INDEX -> dock.selectWaybillIndex(packet.value);
                case TAKE_SELECTED_WAYBILL -> dock.claimSelectedWaybill(player);
                case REFRESH -> { }
            }

            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenDockScreenPacket(dock.buildScreenData(player))
            );
        });
        context.setPacketHandled(true);
    }

    public enum Action {
        LOAD_BOOK_FROM_HAND,
        LOAD_BOOK_FROM_INVENTORY_SLOT,
        IMPORT_BOOK,
        CLEAR_BOOK,
        DELETE_ROUTE,
        PREV_ROUTE,
        NEXT_ROUTE,
        SELECT_ROUTE_INDEX,
        REVERSE_ROUTE,
        PREV_BOAT,
        NEXT_BOAT,
        SELECT_BOAT_INDEX,
        ASSIGN_SELECTED,
        SELECT_WAYBILL_INDEX,
        TAKE_SELECTED_WAYBILL,
        REFRESH
    }
}
