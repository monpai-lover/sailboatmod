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
}
