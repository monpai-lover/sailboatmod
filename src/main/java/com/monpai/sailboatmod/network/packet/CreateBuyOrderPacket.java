package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.market.commodity.CommodityMarketService;
import com.monpai.sailboatmod.market.wallet.MarketWalletService;
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
                CommodityMarketService service = new CommodityMarketService();
                String playerUuid = player.getUUID().toString();
                String playerName = player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
                int safeQuantity = Math.max(1, packet.quantity);
                int reserveBp = Math.max(packet.minPriceBp, packet.maxPriceBp);
                // pricing: buy-order unit price uses market reference price (trade-avg -> lowest ask -> basePrice)
                int lowestAsk = com.monpai.sailboatmod.market.MarketSavedData.get(player.level())
                        .lowestActiveAsk(com.monpai.sailboatmod.market.commodity.CommodityKeyResolver.resolve(itemStack));
                int referenceUnitPrice = service.referencePrice(itemStack, lowestAsk);
                long reservedBalance = player.getAbilities().instabuild
                        ? 0L
                        : CommodityMarketService.reservedBalanceForBuyOrder(
                        referenceUnitPrice,
                        safeQuantity,
                        reserveBp
                );
                if (reservedBalance > 0L
                        && !MarketWalletService.reserve(player.level(), playerUuid, playerName, reservedBalance).success()) {
                    return;
                }
                try {
                    service.createReservedBuyOrder(
                        itemStack,
                        safeQuantity,
                        packet.minPriceBp,
                        packet.maxPriceBp,
                        playerUuid,
                        playerName,
                        reservedBalance
                    );
                } catch (Exception createException) {
                    if (reservedBalance > 0L) {
                        MarketWalletService.releaseReserved(player.level(), playerUuid, playerName, reservedBalance);
                    }
                }
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
        if (item == null) {
            item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        }
        return item == null ? net.minecraft.world.item.ItemStack.EMPTY : new net.minecraft.world.item.ItemStack(item);
    }
}
