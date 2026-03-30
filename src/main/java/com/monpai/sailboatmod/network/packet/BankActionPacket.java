package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.NationService;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.economy.VaultEconomyBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class BankActionPacket {
    public enum Action {
        DEPOSIT_CURRENCY,
        WITHDRAW_CURRENCY,
        DEPOSIT_ITEM,
        WITHDRAW_ITEM
    }

    private final Action action;
    private final BlockPos pos;
    private final long amount;
    private final int slot;

    public BankActionPacket(Action action, BlockPos pos, long amount, int slot) {
        this.action = action;
        this.pos = pos;
        this.amount = amount;
        this.slot = slot;
    }

    public static void encode(BankActionPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeBlockPos(msg.pos);
        buf.writeLong(msg.amount);
        buf.writeInt(msg.slot);
    }

    public static BankActionPacket decode(FriendlyByteBuf buf) {
        return new BankActionPacket(buf.readEnum(Action.class), buf.readBlockPos(), buf.readLong(), buf.readInt());
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
                    Boolean withdrawn = VaultEconomyBridge.tryWithdraw(player, (int) msg.amount);
                    if (withdrawn == null || !withdrawn) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.insufficient_personal"));
                        return;
                    }
                    data.putTreasury(treasury.withBalance(treasury.currencyBalance() + msg.amount));
                    player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.deposit.success", msg.amount));
                }
                case WITHDRAW_CURRENCY -> {
                    if (msg.amount <= 0) return;
                    if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));
                        return;
                    }
                    if (treasury.currencyBalance() < msg.amount) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.insufficient", treasury.currencyBalance()));
                        return;
                    }
                    VaultEconomyBridge.tryDeposit(player, (int) msg.amount);
                    data.putTreasury(treasury.withBalance(treasury.currencyBalance() - msg.amount));
                    player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.withdraw.success", msg.amount));
                }
                case DEPOSIT_ITEM -> {
                    if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));
                        return;
                    }
                    ItemStack held = player.getMainHandItem();
                    if (held.isEmpty()) return;
                    NonNullList<ItemStack> items = treasury.items();
                    for (int i = 0; i < items.size(); i++) {
                        if (items.get(i).isEmpty()) {
                            items.set(i, held.copy());
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
                    if (msg.slot < 0 || msg.slot >= treasury.items().size()) return;
                    ItemStack item = treasury.items().get(msg.slot);
                    if (item.isEmpty()) return;
                    if (!player.getInventory().add(item.copy())) {
                        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.inventory_full"));
                        return;
                    }
                    treasury.items().set(msg.slot, ItemStack.EMPTY);
                    data.putTreasury(treasury);
                    player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.withdraw_item.success"));
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
