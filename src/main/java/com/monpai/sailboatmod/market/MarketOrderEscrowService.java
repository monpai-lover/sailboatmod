package com.monpai.sailboatmod.market;

import com.monpai.sailboatmod.market.wallet.MarketWalletService;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.Map;

final class MarketOrderEscrowService {
    private MarketOrderEscrowService() {
    }

    static boolean settle(ServerLevel level, MarketSavedData market, MarketOrderEscrow escrow) {
        if (level == null || escrow == null || !escrow.hasBalancedAmounts()) {
            return false;
        }

        Map<String, Long> taxes = new LinkedHashMap<>();
        addTax(taxes, escrow.salesTaxNationId(), escrow.salesTax());
        addTax(taxes, escrow.importTariffNationId(), escrow.importTariff());
        NationSavedData nations = NationSavedData.get(level);
        Map<String, NationTreasuryRecord> updatedTreasuries = new LinkedHashMap<>();
        long buyerTaxRefund = 0L;
        for (Map.Entry<String, Long> tax : taxes.entrySet()) {
            if (nations.getNation(tax.getKey()) == null) {
                buyerTaxRefund += tax.getValue();
                continue;
            }
            NationTreasuryRecord treasury = nations.getTreasury(tax.getKey());
            if (treasury == null) {
                treasury = NationTreasuryRecord.empty(tax.getKey());
            }
            if (treasury.currencyBalance() > Long.MAX_VALUE - tax.getValue()) {
                return false;
            }
            updatedTreasuries.put(tax.getKey(), treasury.withBalance(treasury.currencyBalance() + tax.getValue()));
        }

        Map<String, Long> walletCredits = new LinkedHashMap<>();
        Map<String, String> walletNames = new LinkedHashMap<>();
        if (!addWalletCredit(walletCredits, walletNames, escrow.sellerUuid(), escrow.sellerName(), escrow.sellerPayout())) {
            return false;
        }
        if (buyerTaxRefund > 0L) {
            PurchaseOrder order = market.getPurchaseOrder(escrow.orderId());
            if (order == null || !addWalletCredit(walletCredits, walletNames,
                    order.buyerUuid(), order.buyerName(), buyerTaxRefund)) {
                return false;
            }
        }
        for (Map.Entry<String, Long> credit : walletCredits.entrySet()) {
            long balance = MarketWalletService.availableBalance(level, credit.getKey(), walletNames.get(credit.getKey()));
            if (balance > Long.MAX_VALUE - credit.getValue()) {
                return false;
            }
        }

        // All recipients are checked before any money is moved on the server thread.
        for (Map.Entry<String, Long> credit : walletCredits.entrySet()) {
            MarketWalletService.deposit(level, credit.getKey(), walletNames.get(credit.getKey()), credit.getValue());
        }
        for (NationTreasuryRecord treasury : updatedTreasuries.values()) {
            nations.putTreasury(treasury);
        }
        market.removeEscrow(escrow.orderId());
        return true;
    }

    private static void addTax(Map<String, Long> taxes, String nationId, int amount) {
        if (amount > 0) {
            taxes.merge(nationId, (long) amount, Long::sum);
        }
    }

    private static boolean addWalletCredit(Map<String, Long> credits, Map<String, String> names,
                                           String playerUuid, String playerName, long amount) {
        if (amount <= 0L) {
            return true;
        }
        if (playerUuid == null || playerUuid.isBlank()) {
            return false;
        }
        credits.merge(playerUuid, amount, Long::sum);
        names.putIfAbsent(playerUuid, playerName);
        return true;
    }
}
