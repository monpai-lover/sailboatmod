package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.ModConfig;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.LoanAccountRecord;
import com.monpai.sailboatmod.nation.model.LoanAccountView;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class BankLoanService {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final double PERSONAL_CASH_WEIGHT = 1.00D;
    private static final double PERSONAL_INVENTORY_WEIGHT = 0.20D;
    private static final double NATION_CASH_WEIGHT = 1.00D;
    private static final double NATION_TREASURY_ITEMS_WEIGHT = 0.35D;
    private static final long NATION_TOWN_CREDIT = 800L;
    private static final long NATION_CLAIM_CREDIT = 90L;
    private static final long NATION_TRADE_CREDIT = 120L;

    public void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        NationSavedData data = NationSavedData.get(server.overworld());
        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            accruePersonal(data, player, now);
        }
        for (LoanAccountRecord record : data.getNationLoans()) {
            accrueNation(data, record.accountId(), now);
        }
    }

    public LoanAccountView buildPersonalView(Player player) {
        if (player == null) {
            return LoanAccountView.disabled();
        }
        NationSavedData data = NationSavedData.get(player.level());
        LoanAccountRecord record = accruePersonal(data, player, System.currentTimeMillis());
        long maxBorrowable = maxPersonalBorrowable(player, record);
        long nextInterest = nextInterest(record.outstanding());
        return new LoanAccountView(
                record.principal(),
                record.accruedInterest(),
                record.outstanding(),
                record.lifetimeInterestPaid(),
                record.totalBorrowed(),
                maxBorrowable,
                nextInterest,
                record.nextDueAt(),
                record.delinquent(),
                ModConfig.bankPersonalLoansEnabled()
        );
    }

    public LoanAccountView buildNationView(Level level, String nationId) {
        if (level == null || nationId == null || nationId.isBlank()) {
            return LoanAccountView.disabled();
        }
        NationSavedData data = NationSavedData.get(level);
        LoanAccountRecord record = accrueNation(data, nationId, System.currentTimeMillis());
        long maxBorrowable = maxNationBorrowable(level, nationId, record);
        long nextInterest = nextInterest(record.outstanding());
        return new LoanAccountView(
                record.principal(),
                record.accruedInterest(),
                record.outstanding(),
                record.lifetimeInterestPaid(),
                record.totalBorrowed(),
                maxBorrowable,
                nextInterest,
                record.nextDueAt(),
                record.delinquent(),
                ModConfig.bankNationLoansEnabled()
        );
    }

    public boolean borrowPersonal(ServerPlayer player, long amount) {
        if (player == null || !ModConfig.bankPersonalLoansEnabled() || amount < ModConfig.bankLoanMin()) {
            return false;
        }
        NationSavedData data = NationSavedData.get(player.level());
        LoanAccountRecord record = accruePersonal(data, player, System.currentTimeMillis());
        long maxBorrowable = maxPersonalBorrowable(player, record);
        long safeAmount = Math.max(0L, Math.min(maxBorrowable, amount));
        if (safeAmount < ModConfig.bankLoanMin()) {
            return false;
        }
        LoanAccountRecord updated = record.withBorrow(
                record.principal() + safeAmount,
                record.totalBorrowed() + safeAmount,
                System.currentTimeMillis(),
                System.currentTimeMillis() + DAY_MS
        );
        data.putPersonalLoan(updated);
        GoldStandardEconomy.tryDeposit(player, safeAmount);
        return true;
    }

    public boolean repayPersonal(ServerPlayer player, long amount) {
        if (player == null || amount < ModConfig.bankLoanMin()) {
            return false;
        }
        NationSavedData data = NationSavedData.get(player.level());
        LoanAccountRecord record = accruePersonal(data, player, System.currentTimeMillis());
        long safeAmount = Math.max(0L, Math.min(record.outstanding(), amount));
        if (safeAmount < ModConfig.bankLoanMin()) {
            return false;
        }
        Boolean withdrawn = GoldStandardEconomy.tryWithdraw(player, safeAmount);
        if (withdrawn == null || !withdrawn) {
            return false;
        }
        data.putPersonalLoan(applyPayment(record, safeAmount));
        return true;
    }

    public boolean borrowNation(ServerPlayer player, String nationId, long amount) {
        if (player == null || nationId == null || nationId.isBlank() || !ModConfig.bankNationLoansEnabled()) {
            return false;
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
            return false;
        }
        NationSavedData data = NationSavedData.get(player.level());
        LoanAccountRecord record = accrueNation(data, nationId, System.currentTimeMillis());
        long maxBorrowable = maxNationBorrowable(player.level(), nationId, record);
        long safeAmount = Math.max(0L, Math.min(maxBorrowable, amount));
        if (safeAmount < ModConfig.bankLoanMin()) {
            return false;
        }
        data.putNationLoan(record.withBorrow(
                record.principal() + safeAmount,
                record.totalBorrowed() + safeAmount,
                System.currentTimeMillis(),
                System.currentTimeMillis() + DAY_MS
        ));
        NationTreasuryRecord treasury = data.getOrCreateTreasury(nationId);
        data.putTreasury(treasury.withBalance(treasury.currencyBalance() + safeAmount));
        return true;
    }

    public boolean repayNation(ServerPlayer player, String nationId, long amount) {
        if (player == null || nationId == null || nationId.isBlank()) {
            return false;
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
            return false;
        }
        NationSavedData data = NationSavedData.get(player.level());
        LoanAccountRecord record = accrueNation(data, nationId, System.currentTimeMillis());
        NationTreasuryRecord treasury = data.getOrCreateTreasury(nationId);
        long safeAmount = Math.max(0L, Math.min(Math.min(record.outstanding(), treasury.currencyBalance()), amount));
        if (safeAmount < ModConfig.bankLoanMin()) {
            return false;
        }
        data.putTreasury(treasury.withBalance(treasury.currencyBalance() - safeAmount));
        data.putNationLoan(applyPayment(record, safeAmount));
        return true;
    }

    public LoanAccountRecord accruePersonal(NationSavedData data, Player player, long now) {
        if (data == null || player == null) {
            return LoanAccountRecord.empty("");
        }
        LoanAccountRecord record = data.getPersonalLoan(player.getUUID());
        record = accrueCommon(record, now, amount -> {
            Boolean withdrawn = GoldStandardEconomy.tryWithdraw(player, amount);
            return withdrawn != null && withdrawn;
        });
        data.putPersonalLoan(record);
        return record;
    }

    public LoanAccountRecord accrueNation(NationSavedData data, String nationId, long now) {
        if (data == null || nationId == null || nationId.isBlank()) {
            return LoanAccountRecord.empty("");
        }
        LoanAccountRecord record = data.getNationLoan(nationId);
        record = accrueCommon(record, now, amount -> {
            NationTreasuryRecord treasury = data.getOrCreateTreasury(nationId);
            if (treasury.currencyBalance() < amount) {
                return false;
            }
            data.putTreasury(treasury.withBalance(treasury.currencyBalance() - amount));
            return true;
        });
        data.putNationLoan(record);
        return record;
    }

    public static long maxPersonalBorrowable(Player player, @Nullable LoanAccountRecord record) {
        if (player == null || !ModConfig.bankPersonalLoansEnabled()) {
            return 0L;
        }
        long collateral = estimatePlayerLoanCollateral(player);
        long cap = Math.min(
                ModConfig.bankPersonalLoanMax(),
                Math.round(collateral * ModConfig.bankPersonalCollateralRatio())
        );
        long outstanding = record == null ? 0L : record.outstanding();
        return Math.max(0L, cap - outstanding);
    }

    public static long maxNationBorrowable(Level level, String nationId, @Nullable LoanAccountRecord record) {
        if (level == null || nationId == null || nationId.isBlank() || !ModConfig.bankNationLoansEnabled()) {
            return 0L;
        }
        NationSavedData data = NationSavedData.get(level);
        NationTreasuryRecord treasury = data.getOrCreateTreasury(nationId);
        long treasuryInventoryValue = estimateTreasuryInventoryValue(treasury);
        long organizationalCredit = estimateNationOrganizationalCredit(data, treasury, nationId);
        long collateral = Math.round(treasury.currencyBalance() * NATION_CASH_WEIGHT)
                + Math.round(treasuryInventoryValue * NATION_TREASURY_ITEMS_WEIGHT)
                + organizationalCredit;
        long cap = Math.min(
                ModConfig.bankNationLoanMax(),
                Math.round(collateral * ModConfig.bankNationCollateralRatio())
        );
        long outstanding = record == null ? 0L : record.outstanding();
        return Math.max(0L, cap - outstanding);
    }

    private static LoanAccountRecord accrueCommon(LoanAccountRecord record, long now, InterestCollector collector) {
        LoanAccountRecord safeRecord = record == null ? LoanAccountRecord.empty("") : record;
        if (safeRecord.outstanding() <= 0L) {
            return safeRecord.withSchedule(now, now + DAY_MS, false).withAccruedInterest(0L).withPrincipal(0L);
        }
        long lastAccruedAt = safeRecord.lastAccruedAt() > 0L ? safeRecord.lastAccruedAt() : now;
        long elapsedDays = Math.max(0L, (now - lastAccruedAt) / DAY_MS);
        if (elapsedDays <= 0L) {
            if (safeRecord.nextDueAt() <= 0L) {
                return safeRecord.withSchedule(lastAccruedAt, lastAccruedAt + DAY_MS, safeRecord.delinquent());
            }
            return safeRecord;
        }
        LoanAccountRecord current = safeRecord;
        long cursor = lastAccruedAt;
        boolean delinquent = current.delinquent();
        for (long i = 0; i < elapsedDays; i++) {
            long interest = nextInterest(current.outstanding());
            if (interest > 0L && collector.collect(interest)) {
                current = new LoanAccountRecord(
                        current.accountId(),
                        current.principal(),
                        current.accruedInterest(),
                        current.lifetimeInterestPaid() + interest,
                        current.totalBorrowed(),
                        current.lastAccruedAt(),
                        current.nextDueAt(),
                        false
                );
                delinquent = false;
            } else {
                current = current.withAccruedInterest(current.accruedInterest() + interest);
                delinquent = true;
            }
            cursor += DAY_MS;
        }
        return current.withSchedule(cursor, cursor + DAY_MS, delinquent);
    }

    private static LoanAccountRecord applyPayment(LoanAccountRecord record, long payment) {
        long remaining = Math.max(0L, payment);
        long interestPaid = Math.min(record.accruedInterest(), remaining);
        remaining -= interestPaid;
        long principalPaid = Math.min(record.principal(), remaining);
        long newInterest = Math.max(0L, record.accruedInterest() - interestPaid);
        long newPrincipal = Math.max(0L, record.principal() - principalPaid);
        return new LoanAccountRecord(
                record.accountId(),
                newPrincipal,
                newInterest,
                record.lifetimeInterestPaid() + interestPaid,
                record.totalBorrowed(),
                record.lastAccruedAt(),
                record.nextDueAt(),
                newInterest > 0L && record.delinquent()
        );
    }

    private static long nextInterest(long outstanding) {
        if (outstanding <= 0L) {
            return 0L;
        }
        return Math.max(
                ModConfig.bankLoanMinDailyInterest(),
                Math.round(outstanding * ModConfig.bankLoanDailyInterestRate())
        );
    }

    private static long estimatePlayerLoanCollateral(Player player) {
        long liquidBalance = Math.max(0L, GoldStandardEconomy.getBalance(player));
        long inventoryValue = 0L;
        for (ItemStack stack : player.getInventory().items) {
            inventoryValue += estimateStackValue(stack);
        }
        return Math.round(liquidBalance * PERSONAL_CASH_WEIGHT)
                + Math.round(inventoryValue * PERSONAL_INVENTORY_WEIGHT);
    }

    private static long estimateTreasuryInventoryValue(NationTreasuryRecord treasury) {
        if (treasury == null) {
            return 0L;
        }
        long total = 0L;
        for (ItemStack stack : treasury.items()) {
            total += estimateStackValue(stack);
        }
        return total;
    }

    private static long estimateNationOrganizationalCredit(NationSavedData data, NationTreasuryRecord treasury, String nationId) {
        if (data == null || nationId == null || nationId.isBlank()) {
            return 0L;
        }
        long townCredit = (long) data.getTownsForNation(nationId).size() * NATION_TOWN_CREDIT;
        long claimCredit = (long) data.getClaimsForNation(nationId).size() * NATION_CLAIM_CREDIT;
        long tradeCredit = (long) Math.max(0, treasury == null ? 0 : treasury.recentTradeCount()) * NATION_TRADE_CREDIT;
        return townCredit + claimCredit + tradeCredit;
    }

    private static long estimateStackValue(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0L;
        }
        long goldValue = GoldStandardEconomy.goldItemMarketValue(stack);
        if (goldValue > 0L) {
            return goldValue;
        }
        try {
            int unitPrice = new com.monpai.sailboatmod.market.commodity.CommodityMarketService()
                    .ensureCommodity(stack)
                    .state()
                    .basePrice();
            return (long) unitPrice * stack.getCount();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    @FunctionalInterface
    private interface InterestCollector {
        boolean collect(long amount);
    }
}
