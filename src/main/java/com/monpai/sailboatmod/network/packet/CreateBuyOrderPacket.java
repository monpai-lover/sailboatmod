package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.market.commodity.CommodityMarketService;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class CreateBuyOrderPacket {
    private final BlockPos marketPos;
    private final String commodityKey;
    private final int quantity;
    private final int minPriceBp;
    private final int maxPriceBp;

    public CreateBuyOrderPacket(BlockPos marketPos, String commodityKey, int quantity, int minPriceBp, int maxPriceBp) {
        this.marketPos = marketPos;
        this.commodityKey = commodityKey;
        this.quantity = quantity;
        this.minPriceBp = minPriceBp;
        this.maxPriceBp = maxPriceBp;
    }

    public static void encode(CreateBuyOrderPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        buffer.writeUtf(packet.commodityKey);
        buffer.writeVarInt(packet.quantity);
        buffer.writeVarInt(packet.minPriceBp);
        buffer.writeVarInt(packet.maxPriceBp);
    }

    public static CreateBuyOrderPacket decode(FriendlyByteBuf buffer) {
        return new CreateBuyOrderPacket(buffer.readBlockPos(), buffer.readUtf(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
    }

    public static void handle(CreateBuyOrderPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            try {
                net.minecraft.world.item.ItemStack itemStack = resolveItemStack(packet.commodityKey);
                if (itemStack.isEmpty()) {
                    return;
                }
                new com.monpai.sailboatmod.market.commodity.CommodityMarketService().createBuyOrder(
                        itemStack,
                        packet.quantity,
                        packet.minPriceBp,
                        packet.maxPriceBp,
                        player.getUUID().toString(),
                        player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName()
                );
            } catch (Exception ignored) {
            }
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }

    private static net.minecraft.world.item.ItemStack resolveItemStack(String commodityKey) {
        net.minecraft.resources.ResourceLocation itemId = new net.minecraft.resources.ResourceLocation(commodityKey);
        net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(itemId);
        return item == null ? net.minecraft.world.item.ItemStack.EMPTY : new net.minecraft.world.item.ItemStack(item);
    }
}
