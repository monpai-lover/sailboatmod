package com.monpai.sailboatmod.market.wallet;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWalletSourceContractTest {
    @Test
    void marketPurchaseAndSellerPayoutUseMarketWallet() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));

        assertTrue(source.contains("MarketWalletService.withdraw("),
                "market purchases should spend the buyer's personal market wallet");
        assertTrue(source.contains("MarketWalletService.deposit("),
                "seller payouts should deposit into the seller's personal market wallet");
    }

    @Test
    void buyOrdersReserveAndReleaseMarketWalletFunds() throws Exception {
        String web = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));
        String createPacket = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/network/packet/CreateBuyOrderPacket.java"));
        String cancelPacket = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/network/packet/CancelBuyOrderPacket.java"));

        assertTrue(web.contains("MarketWalletService.reserve("),
                "web buy-order creation should reserve market wallet balance");
        assertTrue(web.contains("MarketWalletService.releaseReserved("),
                "web buy-order cancel should release reserved wallet balance");
        assertTrue(createPacket.contains("MarketWalletService.reserve("),
                "in-game buy-order creation should reserve market wallet balance");
        assertTrue(cancelPacket.contains("MarketWalletService.releaseReserved("),
                "in-game buy-order cancel should release reserved wallet balance");
    }
}
