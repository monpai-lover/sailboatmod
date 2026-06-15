package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceivingWarehouseContractTest {
    private static String overviewData() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java"));
    }

    private static String marketBlockEntity() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
    }

    @Test
    void overviewHasWarehouseOptionRecord() throws Exception {
        String src = overviewData();
        assertTrue(src.contains("public record WarehouseOption(BlockPos pos, String displayName, String townName)"),
                "MarketOverviewData should declare a WarehouseOption record");
    }

    @Test
    void optionsMethodScopedToCurrentTown() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("receivingWarehouseOptionsForViewer("),
                "should expose receivingWarehouseOptionsForViewer producing WarehouseOption list");
        assertTrue(src.contains("List<MarketOverviewData.WarehouseOption>"),
                "options method should return WarehouseOption list");
        // 收窄到玩家自己所属的 town（镇民体系），而非整个 nation 的所有 town
        assertTrue(src.contains("getTownsForPlayer("),
                "candidates should resolve the viewer's own towns, not all nation towns");
        assertTrue(!src.contains("getTownsForNation(member.nationId())"),
                "candidates must no longer iterate all nation towns");
    }

    @Test
    void targetResolveNoLongerFallsBackToLinkedDock() throws Exception {
        String src = marketBlockEntity();
        int idx = src.indexOf("private BlockPos resolveBuyerTargetWarehouse(");
        assertTrue(idx >= 0, "resolveBuyerTargetWarehouse should exist");
        int end = src.indexOf("\n    private ", idx + 1);
        if (end < 0) {
            end = src.length();
        }
        String body = src.substring(idx, end);
        assertTrue(!body.contains("linkedDockPos"),
                "resolveBuyerTargetWarehouse must not fall back to linkedDockPos");
        assertTrue(body.contains("defaultReceivingWarehouseFor"),
                "no-warehouse case should resolve via defaultReceivingWarehouseFor (null when none), not the seller dock");
    }

    @Test
    void overviewCarriesReceivingFields() throws Exception {
        String src = overviewData();
        assertTrue(src.contains("List<WarehouseOption> receivingWarehouseOptions"),
                "overview record should carry receivingWarehouseOptions");
        assertTrue(src.contains("boolean canChooseReceiving"),
                "overview record should carry canChooseReceiving");
    }

    @Test
    void shippedModesRejectWhenNoReceivingWarehouse() throws Exception {
        String src = marketBlockEntity();
        int idx = src.indexOf("private boolean purchaseListingResolved(");
        assertTrue(idx >= 0, "purchaseListingResolved should exist");
        int end = src.indexOf("\n    private ", idx + 1);
        if (end < 0) {
            end = src.length();
        }
        String body = src.substring(idx, end);
        // ②③ 模式无收货仓时，必须在扣款前拒单（兜底），不能让钱货已动后才发现 null
        assertTrue(body.contains("receivingWarehouse == null"),
                "purchaseListingResolved should reject shipped modes when receiving warehouse is null");
        assertTrue(body.indexOf("receivingWarehouse == null") < body.indexOf("chargePlayer("),
                "the null receiving-warehouse rejection must happen before chargePlayer");
    }
}
