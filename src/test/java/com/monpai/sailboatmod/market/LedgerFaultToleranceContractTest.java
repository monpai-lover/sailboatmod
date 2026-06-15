package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerFaultToleranceContractTest {
    @Test
    void ledgerRecordingWrappedInTryCatch() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
        int put = src.indexOf("market.putPurchaseOrder(createdOrder);");
        assertTrue(put >= 0, "purchaseListingResolved should put the order");
        int tryIdx = src.indexOf("try {", put);
        int recordExpense = src.indexOf("TownFinanceLedgerService.recordExpense(", put);
        int recordIncome = src.indexOf("TownFinanceLedgerService.recordIncome(", put);
        int createProc = src.indexOf("ProcurementService.createProcurement(", put);
        int catchIdx = src.indexOf("} catch (Exception e) {", put);
        // 记账段在 try-catch 内
        assertTrue(tryIdx >= 0 && tryIdx < createProc, "ledger block should start with try after order is persisted");
        assertTrue(createProc < catchIdx && recordExpense < catchIdx && recordIncome < catchIdx,
                "createProcurement + recordExpense + recordIncome must all be inside the try block");
        // catch 用 error 级别日志，不向上抛
        int errLog = src.indexOf("MARKET_LOGGER.error", catchIdx);
        assertTrue(errLog >= 0 && errLog - catchIdx < 200,
                "catch should log at error level (transaction complete, ledger may be missing)");
    }
}
