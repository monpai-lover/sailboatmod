package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.ModernUiCompat;
import com.monpai.sailboatmod.client.modernui.ModernUiRuntimeBridge;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SyncTreasuryPacket {
    private final long balance;
    private final NonNullList<ItemStack> items;
    private final String[] depositors;

    public SyncTreasuryPacket(NationTreasuryRecord treasury) {
        this.balance = treasury.currencyBalance();
        this.items = treasury.items();
        this.depositors = treasury.itemDepositors();
    }

    private SyncTreasuryPacket(long balance, NonNullList<ItemStack> items, String[] depositors) {
        this.balance = balance;
        this.items = items;
        this.depositors = depositors;
    }

    public static void encode(SyncTreasuryPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.balance);
        int count = 0;
        for (int i = 0; i < msg.items.size(); i++) {
            if (!msg.items.get(i).isEmpty()) count++;
        }
        buf.writeVarInt(count);
        for (int i = 0; i < msg.items.size(); i++) {
            if (!msg.items.get(i).isEmpty()) {
                buf.writeVarInt(i);
                buf.writeItem(msg.items.get(i));
                String dep = (msg.depositors != null && i < msg.depositors.length && msg.depositors[i] != null) ? msg.depositors[i] : "";
                PacketStringCodec.writeUtfSafe(buf, dep, 64);
            }
        }
    }

    public static SyncTreasuryPacket decode(FriendlyByteBuf buf) {
        long balance = buf.readLong();
        NonNullList<ItemStack> items = NonNullList.withSize(NationTreasuryRecord.TREASURY_SLOTS, ItemStack.EMPTY);
        String[] depositors = new String[NationTreasuryRecord.TREASURY_SLOTS];
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++) {
            int slot = buf.readVarInt();
            ItemStack stack = buf.readItem();
            String dep = buf.readUtf(64);
            if (slot >= 0 && slot < NationTreasuryRecord.TREASURY_SLOTS) {
                items.set(slot, stack);
                depositors[slot] = dep;
            }
        }
        return new SyncTreasuryPacket(balance, items, depositors);
    }

    public static void handle(SyncTreasuryPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientTreasuryCache.update(msg.balance, msg.items, msg.depositors);
            if (ModernUiCompat.isAvailable()) {
                ModernUiRuntimeBridge.updateCurrentBank();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public static final class ClientTreasuryCache {
        private static long balance;
        private static NonNullList<ItemStack> items = NonNullList.withSize(NationTreasuryRecord.TREASURY_SLOTS, ItemStack.EMPTY);
        private static String[] depositors = new String[NationTreasuryRecord.TREASURY_SLOTS];

        public static void update(long bal, NonNullList<ItemStack> it, String[] dep) {
            balance = bal;
            items.clear();
            for (int i = 0; i < Math.min(it.size(), items.size()); i++) {
                items.set(i, it.get(i).copy());
            }
            if (dep != null) {
                System.arraycopy(dep, 0, depositors, 0, Math.min(dep.length, depositors.length));
            }
        }

        public static long getBalance() { return balance; }
        public static NonNullList<ItemStack> getItems() { return items; }
        public static String getDepositor(int slot) {
            if (slot < 0 || slot >= depositors.length || depositors[slot] == null) return "";
            return depositors[slot];
        }
        public static void clear() {
            balance = 0;
            items = NonNullList.withSize(NationTreasuryRecord.TREASURY_SLOTS, ItemStack.EMPTY);
            depositors = new String[NationTreasuryRecord.TREASURY_SLOTS];
        }
    }
}
