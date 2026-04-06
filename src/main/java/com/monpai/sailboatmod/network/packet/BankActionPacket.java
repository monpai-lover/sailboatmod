package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.BankLoanService;
import com.monpai.sailboatmod.nation.service.NationService;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class BankActionPacket {
    private static final BankLoanService BANK_LOANS = new BankLoanService();

    public enum Action {
        DEPOSIT_CURRENCY,
        WITHDRAW_CURRENCY,
        DEPOSIT_ITEM,
        WITHDRAW_ITEM,
        WITHDRAW_AS_GOLD,
        BORROW_PERSONAL,
        REPAY_PERSONAL,
        BORROW_NATION,
        REPAY_NATION
    }

    private final Action action;
    private final BlockPos pos;
    private final long amount;
    private final int slot;
    private final ItemStack stack;

    public BankActionPacket(Action action, BlockPos pos, long amount, int slot) {
        this(action, pos, amount, slot, ItemStack.EMPTY);
    }

    public BankActionPacket(Action action, BlockPos pos, long amount, int slot, ItemStack stack) {
        this.action = action;
        this.pos = pos;
        this.amount = amount;
        this.slot = slot;
        this.stack = stack == null ? ItemStack.EMPTY : stack.copy();
    }

    public static void encode(BankActionPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeBlockPos(msg.pos);
        buf.writeLong(msg.amount);
        buf.writeInt(msg.slot);
        buf.writeItem(msg.stack);
    }

    public static BankActionPacket decode(FriendlyByteBuf buf) {
        return new BankActionPacket(buf.readEnum(Action.class), buf.readBlockPos(), buf.readLong(), buf.readInt(), buf.readItem());
    }

    public static void handle(BankActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            TownRecord town = TownService.getTownAt(player.level(), msg.pos);
            if (town == null || town.nationId().isBlank()) {
                player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.no_nation"));
                return;
            }

            NationSavedData data = NationSavedData.get(player.level());
            NationMemberRecord member = data.getMember(player.getUUID());
            if (member == null || !member.nationId().equals(town.nationId())) {
                player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.not_member"));
                return;
            }

            NationTreasuryRecord treasury = data.getOrCreateTreasury(town.nationId());

            switch (msg.action) {
                case DEPOSIT_CURRENCY -> {
                    if (msg.amount <= 0) return;
                    if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));
                        return;
                    }
                    Boolean withdrawn = GoldStandardEconomy.tryWithdraw(player, msg.amount);
                    if (withdrawn == null || !withdrawn) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.insufficient_personal"));
                        return;
                    }
                    data.putTreasury(treasury.withBalance(treasury.currencyBalance() + msg.amount));
                    player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.deposit.success", GoldStandardEconomy.formatBalance(msg.amount)));
                }
                case WITHDRAW_CURRENCY -> {
                    if (msg.amount <= 0) return;
                    if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));
                        return;
                    }
                    if (treasury.currencyBalance() < msg.amount) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.insufficient", GoldStandardEconomy.formatBalance(treasury.currencyBalance())));
                        return;
                    }
                    GoldStandardEconomy.tryDeposit(player, msg.amount);
                    data.putTreasury(treasury.withBalance(treasury.currencyBalance() - msg.amount));
                    player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.withdraw.success", GoldStandardEconomy.formatBalance(msg.amount)));
                }
                case DEPOSIT_ITEM -> {
                    if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));
                        return;
                    }
                    ItemStack held = player.getMainHandItem();
                    if (held.isEmpty()) return;
                    // Gold items: convert to currency at market price
                    long goldValue = GoldStandardEconomy.goldItemMarketValue(held);
                    if (goldValue > 0) {
                        player.getMainHandItem().setCount(0);
                        data.putTreasury(treasury.withBalance(treasury.currencyBalance() + goldValue));
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.deposit.success", GoldStandardEconomy.formatBalance(goldValue)));
                        break;
                    }
                    NonNullList<ItemStack> items = treasury.items();
                    String depositorName = player.getGameProfile() != null ? player.getGameProfile().getName() : player.getName().getString();
                    for (int i = 0; i < items.size(); i++) {
                        if (items.get(i).isEmpty()) {
                            items.set(i, held.copy());
                            treasury.setDepositor(i, depositorName);
                            player.getMainHandItem().setCount(0);
                            data.putTreasury(treasury);
                            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.deposit_item.success"));
                            return;
                        }
                    }
                    player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.treasury_full"));
                }
                case WITHDRAW_ITEM -> {
                    if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));
                        return;
                    }
                    if (msg.amount <= 0 || msg.stack.isEmpty()) {
                        return;
                    }
                    java.util.List<ItemStack> extracted = treasury.removeMatching(msg.stack, (int) Math.min(Integer.MAX_VALUE, msg.amount));
                    if (extracted.isEmpty()) {
                        return;
                    }
                    java.util.List<ItemStack> added = new java.util.ArrayList<>();
                    for (ItemStack item : extracted) {
                        if (player.getInventory().add(item.copy())) {
                            added.add(item);
                            continue;
                        }
                        for (ItemStack rollback : added) {
                            treasury.addItem(rollback, player.getName().getString());
                        }
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.inventory_full"));
                        return;
                    }
                    data.putTreasury(treasury);
                    player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.withdraw_item.success"));
                }
                case WITHDRAW_AS_GOLD -> {
                    if (msg.amount <= 0) return;
                    if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));
                        return;
                    }
                    if (treasury.currencyBalance() < msg.amount) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.insufficient", GoldStandardEconomy.formatBalance(treasury.currencyBalance())));
                        return;
                    }
                    GoldStandardEconomy.tryDeposit(player, msg.amount);
                    data.putTreasury(treasury.withBalance(treasury.currencyBalance() - msg.amount));
                    player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.withdraw.success", GoldStandardEconomy.formatBalance(msg.amount)));
                }
                case BORROW_PERSONAL -> {
                    if (!BANK_LOANS.borrowPersonal(player, msg.amount)) {
                        player.sendSystemMessage(Component.translatable("screen.sailboatmod.bank.loan_action_failed"));
                        return;
                    }
                    player.sendSystemMessage(Component.translatable("screen.sailboatmod.bank.borrow_personal_success", GoldStandardEconomy.formatBalance(msg.amount)));
                }
                case REPAY_PERSONAL -> {
                    if (!BANK_LOANS.repayPersonal(player, msg.amount)) {
                        player.sendSystemMessage(Component.translatable("screen.sailboatmod.bank.loan_action_failed"));
                        return;
                    }
                    player.sendSystemMessage(Component.translatable("screen.sailboatmod.bank.repay_personal_success", GoldStandardEconomy.formatBalance(msg.amount)));
                }
                case BORROW_NATION -> {
                    if (!BANK_LOANS.borrowNation(player, town.nationId(), msg.amount)) {
                        player.sendSystemMessage(Component.translatable("screen.sailboatmod.bank.loan_action_failed"));
                        return;
                    }
                    player.sendSystemMessage(Component.translatable("screen.sailboatmod.bank.borrow_nation_success", GoldStandardEconomy.formatBalance(msg.amount)));
                }
                case REPAY_NATION -> {
                    if (!BANK_LOANS.repayNation(player, town.nationId(), msg.amount)) {
                        player.sendSystemMessage(Component.translatable("screen.sailboatmod.bank.loan_action_failed"));
                        return;
                    }
                    player.sendSystemMessage(Component.translatable("screen.sailboatmod.bank.repay_nation_success", GoldStandardEconomy.formatBalance(msg.amount)));
                }
            }
            NationTreasuryRecord updatedTreasury = data.getOrCreateTreasury(town.nationId());
            com.monpai.sailboatmod.network.ModNetwork.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new SyncTreasuryPacket(
                            updatedTreasury,
                            BANK_LOANS.buildPersonalView(player),
                            BANK_LOANS.buildNationView(player.level(), town.nationId())
                    ));
        });
        ctx.get().setPacketHandled(true);
    }
}
