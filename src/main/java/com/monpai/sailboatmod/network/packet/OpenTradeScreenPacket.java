package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.TradeClientHooks;
import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class OpenTradeScreenPacket {
    private final TradeScreenData data;

    public OpenTradeScreenPacket(TradeScreenData data) {
        this.data = data;
    }

    public static void encode(OpenTradeScreenPacket packet, FriendlyByteBuf buffer) {
        TradeScreenData d = packet.data;
        writeUtfSafe(buffer, d.ourNationId(), 40);
        writeUtfSafe(buffer, d.ourNationName(), 64);
        buffer.writeInt(d.ourPrimaryColor());
        buffer.writeLong(d.ourTreasuryBalance());
        writeItemList(buffer, d.ourTreasuryItems());
        buffer.writeBoolean(d.canManageTreasury());
        writeUtfSafe(buffer, d.targetNationId(), 40);
        writeUtfSafe(buffer, d.targetNationName(), 64);
        buffer.writeInt(d.targetPrimaryColor());
        buffer.writeLong(d.targetTreasuryBalance());
        writeItemList(buffer, d.targetTreasuryItems());
        buffer.writeBoolean(d.hasExistingProposal());
        writeUtfSafe(buffer, d.proposalId(), 16);
        buffer.writeBoolean(d.weAreProposer());
        buffer.writeLong(d.offerCurrency());
        writeItemList(buffer, d.offerItems());
        buffer.writeLong(d.requestCurrency());
        writeItemList(buffer, d.requestItems());
        buffer.writeVarInt(d.proposalRemainingSeconds());
        writeUtfSafe(buffer, d.diplomacyStatus(), 24);
    }
    public static OpenTradeScreenPacket decode(FriendlyByteBuf buffer) {
        String ourNationId = buffer.readUtf(40);
        String ourNationName = buffer.readUtf(64);
        int ourPrimaryColor = buffer.readInt();
        long ourTreasuryBalance = buffer.readLong();
        List<ItemStack> ourTreasuryItems = readItemList(buffer);
        boolean canManageTreasury = buffer.readBoolean();
        String targetNationId = buffer.readUtf(40);
        String targetNationName = buffer.readUtf(64);
        int targetPrimaryColor = buffer.readInt();
        long targetTreasuryBalance = buffer.readLong();
        List<ItemStack> targetTreasuryItems = readItemList(buffer);
        boolean hasExistingProposal = buffer.readBoolean();
        String proposalId = buffer.readUtf(16);
        boolean weAreProposer = buffer.readBoolean();
        long offerCurrency = buffer.readLong();
        List<ItemStack> offerItems = readItemList(buffer);
        long requestCurrency = buffer.readLong();
        List<ItemStack> requestItems = readItemList(buffer);
        int proposalRemainingSeconds = buffer.readVarInt();
        String diplomacyStatus = buffer.readUtf(24);
        return new OpenTradeScreenPacket(new TradeScreenData(
                ourNationId, ourNationName, ourPrimaryColor, ourTreasuryBalance, ourTreasuryItems, canManageTreasury,
                targetNationId, targetNationName, targetPrimaryColor, targetTreasuryBalance, targetTreasuryItems,
                hasExistingProposal, proposalId, weAreProposer,
                offerCurrency, offerItems, requestCurrency, requestItems,
                proposalRemainingSeconds, diplomacyStatus
        ));
    }

    public static void handle(OpenTradeScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            TradeClientHooks.openOrUpdate(packet.data);
        }));
        context.setPacketHandled(true);
    }

    private static void writeItemList(FriendlyByteBuf buffer, List<ItemStack> items) {
        int nonEmpty = 0;
        for (ItemStack item : items) { if (!item.isEmpty()) nonEmpty++; }
        buffer.writeVarInt(nonEmpty);
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (!item.isEmpty()) {
                buffer.writeVarInt(i);
                buffer.writeItem(item);
            }
        }
    }

    private static List<ItemStack> readItemList(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int slot = buffer.readVarInt();
            ItemStack item = buffer.readItem();
            // Pad with empty stacks up to slot index
            while (items.size() < slot) items.add(ItemStack.EMPTY);
            items.add(item);
        }
        return items;
    }

    private static void writeUtfSafe(FriendlyByteBuf buffer, String value, int maxLength) {
        String safe = value == null || value.isEmpty() ? "" : (value.length() <= maxLength ? value : value.substring(0, maxLength));
        buffer.writeUtf(safe, maxLength);
    }
}
