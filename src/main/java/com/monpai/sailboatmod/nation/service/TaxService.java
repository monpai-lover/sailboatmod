package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public final class TaxService {
    private static final int TRADE_COUNT_THRESHOLD = 50;
    private static final int FLOAT_RANGE_BP = 300;
    private static final long DAY_MILLIS = 86_400_000L;

    public record TaxResult(int sellerReceives, int taxAmount) {}

    public static int effectiveSalesTaxBp(Level level, String nationId) {
        if (level == null || nationId == null || nationId.isBlank()) return 0;
        NationSavedData data = NationSavedData.get(level);
        NationTreasuryRecord treasury = data.getOrCreateTreasury(nationId);
        return applyFloat(treasury.salesTaxBasisPoints(), treasury.recentTradeCount(), treasury.lastTaxAdjustTime(), NationTreasuryRecord.MAX_SALES_TAX_BP);
    }

    public static int effectiveImportTariffBp(Level level, String nationId) {
        if (level == null || nationId == null || nationId.isBlank()) return 0;
        NationSavedData data = NationSavedData.get(level);
        NationTreasuryRecord treasury = data.getOrCreateTreasury(nationId);
        return applyFloat(treasury.importTariffBasisPoints(), treasury.recentTradeCount(), treasury.lastTaxAdjustTime(), NationTreasuryRecord.MAX_IMPORT_TARIFF_BP);
    }

    public static TaxResult applySalesTax(Level level, int totalPrice, BlockPos marketPos) {
        if (level == null || totalPrice <= 0 || marketPos == null) return new TaxResult(totalPrice, 0);
        String nationId = getNationIdAt(level, marketPos);
        if (nationId == null) return new TaxResult(totalPrice, 0);
        int bp = effectiveSalesTaxBp(level, nationId);
        int tax = Math.max(0, (int) Math.floor((long) totalPrice * bp / 10000.0));
        if (tax > 0) depositTax(level, nationId, tax);
        return new TaxResult(totalPrice - tax, tax);
    }

    public static TaxResult applyImportTariff(Level level, int totalPrice, BlockPos sourceDockPos, BlockPos targetDockPos) {
        if (level == null || totalPrice <= 0 || sourceDockPos == null || targetDockPos == null) return new TaxResult(totalPrice, 0);
        String exportNationId = getNationIdAt(level, sourceDockPos);
        String importNationId = getNationIdAt(level, targetDockPos);
        if (importNationId == null || importNationId.equals(exportNationId)) return new TaxResult(totalPrice, 0);
        int bp = effectiveImportTariffBp(level, importNationId);
        int tax = Math.max(0, (int) Math.floor((long) totalPrice * bp / 10000.0));
        if (tax > 0) depositTax(level, importNationId, tax);
        return new TaxResult(totalPrice - tax, tax);
    }

    public static void recordTrade(Level level, BlockPos marketPos) {
        if (level == null || marketPos == null) return;
        String nationId = getNationIdAt(level, marketPos);
        if (nationId == null) return;
        NationSavedData data = NationSavedData.get(level);
        NationTreasuryRecord treasury = data.getOrCreateTreasury(nationId);
        data.putTreasury(treasury.withTradeRecorded());
    }

    public static void depositTax(Level level, String nationId, int amount) {
        if (level == null || nationId == null || nationId.isBlank() || amount <= 0) return;
        NationSavedData data = NationSavedData.get(level);
        NationTreasuryRecord treasury = data.getOrCreateTreasury(nationId);
        data.putTreasury(treasury.withBalance(treasury.currencyBalance() + amount));
    }

    public static String getNationIdAt(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        NationSavedData data = NationSavedData.get(level);
        NationClaimRecord claim = data.getClaim(level, new ChunkPos(pos));
        return claim == null || claim.nationId().isBlank() ? null : claim.nationId();
    }

    private static int applyFloat(int baseBp, int recentTradeCount, long lastAdjustTime, int maxBp) {
        long elapsed = System.currentTimeMillis() - lastAdjustTime;
        int trades = elapsed > DAY_MILLIS ? 0 : recentTradeCount;
        double ratio = Math.min(1.0, (double) trades / TRADE_COUNT_THRESHOLD);
        int adjustment = (int) Math.round(FLOAT_RANGE_BP * (1.0 - ratio));
        return Math.max(0, Math.min(maxBp, baseBp + adjustment));
    }

    private TaxService() {}
}
