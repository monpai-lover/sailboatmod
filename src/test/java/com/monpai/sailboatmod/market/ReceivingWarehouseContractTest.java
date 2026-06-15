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
}
