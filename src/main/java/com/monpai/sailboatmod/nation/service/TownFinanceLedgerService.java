package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.TownFinanceSavedData;
import com.monpai.sailboatmod.nation.model.TownFinanceEntryRecord;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TownFinanceLedgerService {
    public record TownBalanceSnapshot(String townId, long totalIncome, long totalExpense, long netBalance, int entryCount) {
    }

    private TownFinanceLedgerService() {
    }

    public static TownFinanceEntryRecord recordExpense(Level level, String townId, String type, long amount, String currency,
                                                       String commodityKey, int quantity, String sourceRef) {
        return record(level, townId, "EXPENSE", type, amount, currency, commodityKey, quantity, sourceRef);
    }

    public static TownFinanceEntryRecord recordIncome(Level level, String townId, String type, long amount, String currency,
                                                      String commodityKey, int quantity, String sourceRef) {
        return record(level, townId, "INCOME", type, amount, currency, commodityKey, quantity, sourceRef);
    }

    public static TownBalanceSnapshot getTownBalanceSnapshot(Level level, String townId) {
        long totalIncome = 0L;
        long totalExpense = 0L;
        int entryCount = 0;
        if (level != null && townId != null && !townId.isBlank()) {
            for (TownFinanceEntryRecord record : TownFinanceSavedData.get(level).getEntries().values()) {
                if (!townId.equals(record.townId())) {
                    continue;
                }
                entryCount++;
                if ("INCOME".equals(record.entryKind())) {
                    totalIncome += record.amount();
                } else if ("EXPENSE".equals(record.entryKind())) {
                    totalExpense += record.amount();
                }
            }
        }
        return new TownBalanceSnapshot(townId == null ? "" : townId.trim(), totalIncome, totalExpense, totalIncome - totalExpense, entryCount);
    }

    public static List<TownFinanceEntryRecord> getRecentEntries(Level level, String townId) {
        List<TownFinanceEntryRecord> out = new ArrayList<>();
        if (level == null || townId == null || townId.isBlank()) {
            return out;
        }
        for (TownFinanceEntryRecord record : TownFinanceSavedData.get(level).getEntries().values()) {
            if (townId.equals(record.townId())) {
                out.add(record);
            }
        }
        out.sort(Comparator.comparing(TownFinanceEntryRecord::timestamp).reversed());
        return out;
    }

    private static TownFinanceEntryRecord record(Level level, String townId, String entryKind, String type, long amount, String currency,
                                                 String commodityKey, int quantity, String sourceRef) {
        if (level == null || townId == null || townId.isBlank() || amount <= 0L) {
            return null;
        }
        TownFinanceEntryRecord record = TownFinanceEntryRecord.create(
                townId,
                entryKind,
                type,
                amount,
                currency,
                commodityKey,
                quantity,
                sourceRef,
                level.getGameTime()
        );
        TownFinanceSavedData.get(level).putEntry(record);
        return record;
    }
}
