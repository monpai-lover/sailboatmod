package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class NationTreasuryService {
    private NationTreasuryService() {
    }

    public static NationResult depositCurrency(ServerPlayer player, long amount) {
        if (amount <= 0) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.treasury.invalid_amount"));
        }

        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));
        }

        Boolean withdrawn = GoldStandardEconomy.tryWithdraw(player, amount);
        if (!Boolean.TRUE.equals(withdrawn)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.bank.insufficient_personal"));
        }

        NationTreasuryRecord treasury = data.getOrCreateTreasury(member.nationId());
        data.putTreasury(treasury.withBalance(treasury.currencyBalance() + amount));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.treasury.deposit.success", GoldStandardEconomy.formatBalance(amount)));
    }

    public static NationResult withdrawCurrency(ServerPlayer player, long amount) {
        if (amount <= 0) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.treasury.invalid_amount"));
        }

        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));
        }

        NationTreasuryRecord treasury = data.getOrCreateTreasury(member.nationId());
        if (treasury.currencyBalance() < amount) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.treasury.insufficient", GoldStandardEconomy.formatBalance(treasury.currencyBalance())));
        }
        GoldStandardEconomy.tryDeposit(player, amount);
        data.putTreasury(treasury.withBalance(treasury.currencyBalance() - amount));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.treasury.withdraw.success", GoldStandardEconomy.formatBalance(amount)));
    }

    public static NationResult setSalesTax(ServerPlayer player, int basisPoints) {
        if (basisPoints < 0 || basisPoints > NationTreasuryRecord.MAX_SALES_TAX_BP) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.treasury.tax.invalid_range", 0, NationTreasuryRecord.MAX_SALES_TAX_BP / 100));
        }
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));
        }
        NationTreasuryRecord treasury = data.getOrCreateTreasury(member.nationId());
        data.putTreasury(treasury.withSalesTax(basisPoints));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.treasury.tax.sales_set", String.format("%.1f%%", basisPoints / 100.0)));
    }

    public static NationResult setImportTariff(ServerPlayer player, int basisPoints) {
        if (basisPoints < 0 || basisPoints > NationTreasuryRecord.MAX_IMPORT_TARIFF_BP) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.treasury.tax.invalid_range", 0, NationTreasuryRecord.MAX_IMPORT_TARIFF_BP / 100));
        }
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));
        }
        NationTreasuryRecord treasury = data.getOrCreateTreasury(member.nationId());
        data.putTreasury(treasury.withImportTariff(basisPoints));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.treasury.tax.tariff_set", String.format("%.1f%%", basisPoints / 100.0)));
    }

    public static NationResult status(ServerPlayer player) {
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }

        NationRecord nation = data.getNation(member.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }

        NationTreasuryRecord treasury = data.getOrCreateTreasury(member.nationId());
        return NationResult.success(Component.translatable(
                "command.sailboatmod.nation.treasury.status",
                nation.name(),
                GoldStandardEconomy.formatBalance(treasury.currencyBalance())
        ));
    }
}
