package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import com.monpai.sailboatmod.nation.service.NationResult;
import com.monpai.sailboatmod.nation.service.NationTradeService;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class TradeScreenActionPacket {
    private final Action action;
    private final String targetNationId;
    private final long offerCurrency;
    private final long requestCurrency;
    private final List<ItemStack> offerItems;
    private final List<ItemStack> requestItems;

    public TradeScreenActionPacket(Action action, String targetNationId) {
        this(action, targetNationId, 0L, 0L, List.of(), List.of());
    }

    public TradeScreenActionPacket(Action action, String targetNationId,
                                   long offerCurrency, long requestCurrency,
                                   List<ItemStack> offerItems, List<ItemStack> requestItems) {
        this.action = action == null ? Action.REFRESH : action;
        this.targetNationId = targetNationId == null ? "" : targetNationId.trim();
        this.offerCurrency = Math.max(0L, offerCurrency);
        this.requestCurrency = Math.max(0L, requestCurrency);
        this.offerItems = offerItems == null ? List.of() : offerItems;
        this.requestItems = requestItems == null ? List.of() : requestItems;
    }
    public static void encode(TradeScreenActionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.action);
        PacketStringCodec.writeUtfSafe(buffer, packet.targetNationId, 40);
        buffer.writeLong(packet.offerCurrency);
        buffer.writeLong(packet.requestCurrency);
        writeItems(buffer, packet.offerItems);
        writeItems(buffer, packet.requestItems);
    }

    public static TradeScreenActionPacket decode(FriendlyByteBuf buffer) {
        Action action = buffer.readEnum(Action.class);
        String targetNationId = buffer.readUtf(40);
        long offerCurrency = buffer.readLong();
        long requestCurrency = buffer.readLong();
        List<ItemStack> offerItems = readItems(buffer);
        List<ItemStack> requestItems = readItems(buffer);
        return new TradeScreenActionPacket(action, targetNationId, offerCurrency, requestCurrency, offerItems, requestItems);
    }

    public static void handle(TradeScreenActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;

            NationResult result = switch (packet.action) {
                case PROPOSE -> NationTradeService.proposeTrade(player, packet.targetNationId,
                        packet.offerCurrency, packet.offerItems, packet.requestCurrency, packet.requestItems);
                case ACCEPT -> NationTradeService.acceptTrade(player, packet.targetNationId);
                case REJECT -> NationTradeService.rejectTrade(player, packet.targetNationId);
                case COUNTER_OFFER -> {
                    NationTradeService.rejectTrade(player, packet.targetNationId);
                    yield NationTradeService.proposeTrade(player, packet.targetNationId,
                            packet.offerCurrency, packet.offerItems, packet.requestCurrency, packet.requestItems);
                }
                case CANCEL -> NationTradeService.cancelTrade(player);
                case REFRESH -> NationResult.success(Component.empty());
            };

            if (!result.message().getString().isBlank()) {
                player.sendSystemMessage(result.message());
                if (!result.success()) {
                    NationToastPacket.send(player, Component.translatable("toast.sailboatmod.nation.trade.title"), result.message());
                }
            }

            TradeScreenData data = NationTradeService.buildTradeScreenData(player, packet.targetNationId);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenTradeScreenPacket(data));
        });
        context.setPacketHandled(true);
    }

    private static void writeItems(FriendlyByteBuf buffer, List<ItemStack> items) {
        int count = 0;
        for (ItemStack item : items) { if (!item.isEmpty()) count++; }
        buffer.writeVarInt(count);
        for (ItemStack item : items) {
            if (!item.isEmpty()) buffer.writeItem(item);
        }
    }

    private static List<ItemStack> readItems(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<ItemStack> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) items.add(buffer.readItem());
        return items;
    }

    public enum Action {
        PROPOSE,
        ACCEPT,
        REJECT,
        COUNTER_OFFER,
        CANCEL,
        REFRESH
    }
}
