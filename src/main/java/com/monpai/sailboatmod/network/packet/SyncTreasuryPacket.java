package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.model.LoanAccountView;
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
    private final LoanAccountView personalLoan;
    private final LoanAccountView nationLoan;

    public SyncTreasuryPacket(NationTreasuryRecord treasury, LoanAccountView personalLoan, LoanAccountView nationLoan) {
        this.balance = treasury.currencyBalance();
        this.items = treasury.items();
        this.depositors = treasury.itemDepositors();
        this.personalLoan = personalLoan == null ? LoanAccountView.disabled() : personalLoan;
        this.nationLoan = nationLoan == null ? LoanAccountView.disabled() : nationLoan;
    }

    private SyncTreasuryPacket(long balance, NonNullList<ItemStack> items, String[] depositors, LoanAccountView personalLoan, LoanAccountView nationLoan) {
        this.balance = balance;
        this.items = items;
        this.depositors = depositors;
        this.personalLoan = personalLoan == null ? LoanAccountView.disabled() : personalLoan;
        this.nationLoan = nationLoan == null ? LoanAccountView.disabled() : nationLoan;
    }

    public static void encode(SyncTreasuryPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.balance);
        buf.writeVarInt(msg.items.size());
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
        writeLoanView(buf, msg.personalLoan);
        writeLoanView(buf, msg.nationLoan);
    }

    public static SyncTreasuryPacket decode(FriendlyByteBuf buf) {
        long balance = buf.readLong();
        int slotCount = Math.max(NationTreasuryRecord.TREASURY_SLOTS, buf.readVarInt());
        NonNullList<ItemStack> items = NonNullList.withSize(slotCount, ItemStack.EMPTY);
        String[] depositors = new String[Math.max(4096, slotCount)];
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++) {
            int slot = buf.readVarInt();
            ItemStack stack = buf.readItem();
            String dep = buf.readUtf(64);
            if (slot >= 0 && slot < items.size()) {
                items.set(slot, stack);
                depositors[slot] = dep;
            }
        }
        return new SyncTreasuryPacket(balance, items, depositors, readLoanView(buf), readLoanView(buf));
    }

    public static void handle(SyncTreasuryPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientTreasuryCache.update(msg.balance, msg.items, msg.depositors, msg.personalLoan, msg.nationLoan);
        });
        ctx.get().setPacketHandled(true);
    }

    public static final class ClientTreasuryCache {
        private static long balance;
        private static NonNullList<ItemStack> items = NonNullList.withSize(NationTreasuryRecord.TREASURY_SLOTS, ItemStack.EMPTY);
        private static String[] depositors = new String[4096];
        private static LoanAccountView personalLoan = LoanAccountView.disabled();
        private static LoanAccountView nationLoan = LoanAccountView.disabled();

        public static void update(long bal, NonNullList<ItemStack> it, String[] dep, LoanAccountView personal, LoanAccountView nation) {
            balance = bal;
            items = NonNullList.withSize(Math.max(NationTreasuryRecord.TREASURY_SLOTS, it.size()), ItemStack.EMPTY);
            for (int i = 0; i < Math.min(it.size(), items.size()); i++) {
                items.set(i, it.get(i).copy());
            }
            depositors = new String[Math.max(4096, items.size())];
            if (dep != null) {
                System.arraycopy(dep, 0, depositors, 0, Math.min(dep.length, depositors.length));
            }
            personalLoan = personal == null ? LoanAccountView.disabled() : personal;
            nationLoan = nation == null ? LoanAccountView.disabled() : nation;
        }

        public static long getBalance() { return balance; }
        public static NonNullList<ItemStack> getItems() { return items; }
        public static LoanAccountView getPersonalLoan() { return personalLoan; }
        public static LoanAccountView getNationLoan() { return nationLoan; }
        public static String getDepositor(int slot) {
            if (slot < 0 || slot >= depositors.length || depositors[slot] == null) return "";
            return depositors[slot];
        }
        public static void clear() {
            balance = 0;
            items = NonNullList.withSize(NationTreasuryRecord.TREASURY_SLOTS, ItemStack.EMPTY);
            depositors = new String[4096];
            personalLoan = LoanAccountView.disabled();
            nationLoan = LoanAccountView.disabled();
        }
    }

    private static void writeLoanView(FriendlyByteBuf buf, LoanAccountView view) {
        buf.writeLong(view.principal());
        buf.writeLong(view.accruedInterest());
        buf.writeLong(view.outstanding());
        buf.writeLong(view.lifetimeInterestPaid());
        buf.writeLong(view.totalBorrowed());
        buf.writeLong(view.maxBorrowable());
        buf.writeLong(view.nextInterestCharge());
        buf.writeLong(view.nextDueAt());
        buf.writeBoolean(view.delinquent());
        buf.writeBoolean(view.enabled());
    }

    private static LoanAccountView readLoanView(FriendlyByteBuf buf) {
        return new LoanAccountView(
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readBoolean(),
                buf.readBoolean()
        );
    }
}
