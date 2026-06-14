package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class PostStationGuiActionPacket {
    private final BlockPos stationPos;
    private final Action action;
    private final int value;

    public PostStationGuiActionPacket(BlockPos stationPos, Action action) {
        this(stationPos, action, -1);
    }

    public PostStationGuiActionPacket(BlockPos stationPos, Action action, int value) {
        this.stationPos = stationPos;
        this.action = action;
        this.value = value;
    }

    public static void encode(PostStationGuiActionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.stationPos);
        buffer.writeEnum(packet.action);
        buffer.writeVarInt(packet.value);
    }

    public static PostStationGuiActionPacket decode(FriendlyByteBuf buffer) {
        return new PostStationGuiActionPacket(buffer.readBlockPos(), buffer.readEnum(Action.class), buffer.readVarInt());
    }

    public static void handle(PostStationGuiActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.stationPos) instanceof PostStationBlockEntity station)) {
                return;
            }
            switch (packet.action) {
                case SELECT_DESTINATION_INDEX -> station.selectDestinationIndex(packet.value);
                case SELECT_VEHICLE_INDEX -> station.selectPostStationVehicleIndex(packet.value, player);
                case TOGGLE_AUTO_RETURN -> {
                    if (station.canManageDock(player)) {
                        station.togglePostStationAutoReturn();
                    }
                }
                case DISPATCH_SELECTED -> station.dispatchSelectedDestination(player, packet.value);
                case RECALL_SELECTED -> station.recallSelectedVehicle(player);
                case ADV_LOAD_BOOK_FROM_HAND -> {
                    if (station.canManageDock(player)) {
                        station.loadRouteBookFromPlayer(player);
                    }
                }
                case ADV_LOAD_BOOK_FROM_INVENTORY_SLOT -> {
                    if (station.canManageDock(player)) {
                        station.loadRouteBookFromInventorySlot(player, packet.value);
                    }
                }
                case ADV_IMPORT_BOOK -> {
                    if (station.canManageDock(player)) {
                        station.importRouteBook();
                    }
                }
                case ADV_CLEAR_BOOK -> {
                    if (station.canManageDock(player)) {
                        station.clearRouteBook();
                    }
                }
                case ADV_DELETE_ROUTE -> {
                    if (station.canManageDock(player)) {
                        station.deleteSelectedRoute();
                    }
                }
                case ADV_PREV_ROUTE -> station.selectRouteDelta(-1);
                case ADV_NEXT_ROUTE -> station.selectRouteDelta(1);
                case ADV_SELECT_ROUTE_INDEX -> station.selectRouteIndex(packet.value);
                case ADV_REVERSE_ROUTE -> {
                    if (station.canManageDock(player)) {
                        station.reverseSelectedRoute();
                    }
                }
                case ADV_SELECT_STORAGE_INDEX -> station.selectStorageIndex(packet.value, player);
                case ADV_TAKE_SELECTED_STORAGE -> station.takeSelectedStorage(player);
                case ADV_SELECT_WAYBILL_INDEX -> station.selectWaybillIndex(packet.value);
                case ADV_TAKE_SELECTED_WAYBILL -> station.claimSelectedWaybill(player);
                case REFRESH -> {
                }
            }
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenPostStationScreenPacket(station.buildPostStationScreenData(player))
            );
        });
        context.setPacketHandled(true);
    }

    public enum Action {
        SELECT_DESTINATION_INDEX,
        SELECT_VEHICLE_INDEX,
        TOGGLE_AUTO_RETURN,
        DISPATCH_SELECTED,
        RECALL_SELECTED,
        ADV_LOAD_BOOK_FROM_HAND,
        ADV_LOAD_BOOK_FROM_INVENTORY_SLOT,
        ADV_IMPORT_BOOK,
        ADV_CLEAR_BOOK,
        ADV_DELETE_ROUTE,
        ADV_PREV_ROUTE,
        ADV_NEXT_ROUTE,
        ADV_SELECT_ROUTE_INDEX,
        ADV_REVERSE_ROUTE,
        ADV_SELECT_STORAGE_INDEX,
        ADV_TAKE_SELECTED_STORAGE,
        ADV_SELECT_WAYBILL_INDEX,
        ADV_TAKE_SELECTED_WAYBILL,
        REFRESH
    }
}
