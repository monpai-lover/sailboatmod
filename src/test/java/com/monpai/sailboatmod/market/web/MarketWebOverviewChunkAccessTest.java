package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebOverviewChunkAccessTest {
    @Test
    void webOverviewUsesLoadedOnlyWarehouseAndReceivingLabels() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
        assertTrue(source.contains("public MarketOverviewData buildWebOverviewForIdentity("));
        assertTrue(source.contains("allowChunkLoads ? getLinkedWarehouse() : getLoadedLinkedWarehouse()"));
        int start = source.indexOf("public TownWarehouseBlockEntity getLoadedLinkedWarehouse()");
        assertTrue(start >= 0, "web warehouse lookup must have a loaded-only entry point");
        String lookup = source.substring(start, source.indexOf("private BlockEntity blockEntityAt(", start));
        assertTrue(lookup.contains("blockEntityAt(linkedDockPos, false)"));
        assertFalse(lookup.contains("level.getBlockEntity("));
        int helperStart = source.indexOf("private BlockEntity blockEntityAt(", start);
        String helper = source.substring(helperStart, source.indexOf("@Override", helperStart));
        assertTrue(helper.contains("getChunkNow("));
        assertTrue(helper.contains("if (allowChunkLoads) return level.getBlockEntity(position);"));
        assertTrue(source.contains("receivingWarehouseOptionsForViewer(safePlayerUuid, allowChunkLoads)"));
    }

    @Test
    void webReadEndpointsUseSafeOverviewAndGuardColonyReads() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));
        assertFalse(source.contains(".buildOverviewForIdentity("));
        assertTrue(source.contains(".buildWebOverviewForIdentity("));
        assertTrue(source.contains("!overview.canManage() || !overview.linkedDock()"));
    }

    @Test
    void webDispatchPreviewsCarryLoadedOnlyPolicyThroughWarehouseAndTerminalLookups() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
        assertTrue(source.contains("buildAvailableDispatchOptions(warehouse, onlinePlayer, allowChunkLoads)"),
                "available previews must retain the web overview's loaded-only policy");
        assertTrue(source.contains("buildDispatchOptionsForOrder(warehouse, order, onlinePlayer, allowChunkLoads)"));
        assertTrue(source.contains("blockEntityAt(targetWarehousePos, allowChunkLoads)"),
                "an open order cannot force-load its destination warehouse while rendering a web view");
        assertTrue(source.contains("terminalsForTown(sourceWarehouse.getTownId(), terminalKind, allowChunkLoads)"));
        assertTrue(source.contains("terminalsForTown(targetWarehouse.getTownId(), terminalKind, allowChunkLoads)"));
    }

    @Test
    void receivingLabelsRequireACompletedChunkInsteadOfATicketHolder() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
        int start = source.indexOf("private java.util.List<MarketOverviewData.WarehouseOption> receivingWarehouseOptionsForViewer(");
        String receiving = source.substring(start, source.indexOf("private String posLabel", start));
        assertFalse(receiving.contains("hasChunkAt("), "a FULL ticket does not establish that a chunk has completed loading");
        assertTrue(receiving.contains("townNameForWarehouse(nations, pos, allowChunkLoads)"));
        assertTrue(receiving.contains("warehouseDisplayNameFor(pos, linked, allowChunkLoads)"));
    }
}
