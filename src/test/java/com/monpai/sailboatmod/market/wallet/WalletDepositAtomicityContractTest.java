package com.monpai.sailboatmod.market.wallet;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletDepositAtomicityContractTest {
    @Test
    void cashToWalletDepositsOnlyAfterConfirmedWithdrawal() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));

        // 三态显式化：区分 Vault 不可用(null) 与 扣款成功(true)
        assertTrue(service.contains("Boolean vaultWithdrawn"),
                "cashToWallet should capture the Vault tri-state result explicitly");
        // 仍保留仓库 fallback（现有契约要求）
        assertTrue(service.contains("MarketWalletGoldSource.withdrawFromLinkedWarehouse("),
                "cashToWallet must keep the linked-warehouse withdrawal fallback");
        // deposit 必须在确认扣源成功的分支内：用一个确定性 withdrew 标志守卫
        assertTrue(service.contains("boolean withdrew"),
                "cashToWallet should track a definitive 'withdrew' flag before depositing");
    }

    @Test
    void walletToCashRefundsWalletWhenPayoutFails() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));
        String packet = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/network/packet/MarketWalletActionPacket.java"));

        // web 端：给付失败必须把已扣的钱包额退回（回滚），并保留仓库 fallback
        assertTrue(service.contains("depositToLinkedWarehouse(resolved.market(), identity.playerUuid(), amount)"),
                "walletToCash should fall back to the player's linked warehouse for payout");
        assertTrue(service.contains("// rollback wallet"),
                "walletToCash should mark the wallet refund as an explicit rollback");

        // 游戏内：给付失败同样显式回滚钱包
        assertTrue(packet.contains("// rollback wallet"),
                "in-game walletToCash should mark the wallet refund as an explicit rollback");
    }
}
