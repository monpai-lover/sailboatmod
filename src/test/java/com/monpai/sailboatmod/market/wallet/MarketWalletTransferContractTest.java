package com.monpai.sailboatmod.market.wallet;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWalletTransferContractTest {
    @Test
    void inGameWalletTransferPacketUsesCashWalletTreasuryAndPermissions() throws Exception {
        String packet = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/network/packet/MarketWalletActionPacket.java"));
        String network = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/network/ModNetwork.java"));

        assertTrue(packet.contains("CASH_TO_WALLET"));
        assertTrue(packet.contains("WALLET_TO_CASH"));
        assertTrue(packet.contains("WALLET_TO_TREASURY"));
        assertTrue(packet.contains("TREASURY_TO_WALLET"));
        assertTrue(packet.contains("CLAIM_CREDITS_TO_WALLET"));
        assertTrue(packet.contains("GoldStandardEconomy.tryWithdraw("));
        assertTrue(packet.contains("MarketWalletGoldSource.withdrawFromLinkedWarehouse("),
                "in-game cash-to-wallet should fall back to the linked private warehouse gold storage");
        assertTrue(packet.contains("GoldStandardEconomy.tryDeposit("));
        assertTrue(packet.contains("MarketWalletService.deposit("));
        assertTrue(packet.contains("MarketWalletService.withdraw("));
        assertTrue(packet.contains("getOrCreateTreasury("));
        assertTrue(packet.contains("putTreasury("));
        assertTrue(packet.contains("NationPermission.MANAGE_TREASURY"));
        assertTrue(packet.contains("NationService.hasPermission("));
        assertTrue(network.contains("MarketWalletActionPacket.class"));
    }

    @Test
    void webWalletTransferEndpointUsesSameLedgerSources() throws Exception {
        String service = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));
        String server = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java"));
        String app = Files.readString(Path.of("src/main/resources/marketweb/app.js"));

        assertTrue(service.contains("transferWallet("));
        assertTrue(service.contains("GoldStandardEconomy.tryWithdrawByIdentity("));
        assertTrue(service.contains("MarketWalletGoldSource.withdrawFromLinkedWarehouse("),
                "web cash-to-wallet should fall back to the linked private warehouse gold storage");
        assertTrue(service.contains("GoldStandardEconomy.tryDepositByIdentity("));
        assertTrue(!service.contains("? GoldStandardEconomy.tryDeposit(identity.onlinePlayer(), amount)"),
                "web wallet withdrawals should not bypass the warehouse fallback by paying physical gold directly to an online player");
        assertTrue(service.contains("MarketWalletGoldSource.depositToLinkedWarehouse("),
                "web wallet withdrawals should fall back to the linked private warehouse when no economy plugin can deposit offline");
        assertTrue(service.contains("MarketWalletGoldSource.depositToLinkedWarehouse(resolved.market(), identity.playerUuid(), amount)"),
                "offline physical-gold withdrawal fallback should target the player's private warehouse storage");
        assertTrue(service.contains("MarketWalletService.deposit("));
        assertTrue(service.contains("MarketWalletService.withdraw("));
        assertTrue(service.contains("NationPermission.MANAGE_TREASURY"));
        assertTrue(server.contains("\"wallet\".equals(path.get(3))"));
        assertTrue(server.contains("service.transferWallet("));
        assertTrue(app.contains("wallet/transfer"));
        assertTrue(app.contains("现金/仓库金 -> 钱包"),
                "wallet UI should tell players that cash-to-wallet can use linked warehouse gold");
    }

    @Test
    void linkedWarehouseGoldSourceSupportsDepositAndWithdrawal() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/wallet/MarketWalletGoldSource.java"));

        assertTrue(source.contains("withdrawFromLinkedWarehouse("),
                "wallet gold source should expose a linked-warehouse withdrawal path");
        assertTrue(source.contains("depositToLinkedWarehouse("),
                "wallet gold source should expose a linked-warehouse deposit path");
        assertTrue(source.contains("extractMatchingStock(ownerId"),
                "linked-warehouse withdrawal should remove gold from the player's private warehouse storage");
        assertTrue(source.contains("insertCargo(ownerId"),
                "linked-warehouse deposit and change should go to the player's private warehouse storage");
        assertTrue(source.contains("Items.GOLD_BLOCK")
                        && source.contains("Items.GOLD_INGOT")
                        && source.contains("Items.GOLD_NUGGET")
                        && source.contains("ModItems.HALF_NUGGET_ITEM"),
                "warehouse wallet deposits should handle all gold-standard denominations");
    }
}
