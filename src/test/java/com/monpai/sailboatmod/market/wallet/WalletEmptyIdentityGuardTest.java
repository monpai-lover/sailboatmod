package com.monpai.sailboatmod.market.wallet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WalletEmptyIdentityGuardTest {
    @Test
    void blankIdentityHasZeroBalanceAndCannotHoldFunds() {
        // 空 uuid 的账户即便被 deposit 也不应累积余额——杜绝所有访客落到同一 "" 账户后互相影响
        MarketWalletAccount blank = MarketWalletAccount.empty("", "", 100L);
        MarketWalletAccount afterDeposit = MarketWalletService.deposit(blank, 500L, 110L);
        assertEquals(0L, afterDeposit.availableBalance(),
                "deposits into a blank-identity account must not accumulate balance");
    }

    @Test
    void blankIdentityWithdrawAlwaysFails() {
        MarketWalletAccount blank = MarketWalletAccount.empty("", "", 100L);
        MarketWalletService.AccountResult result = MarketWalletService.withdraw(blank, 1L, 110L);
        assertFalse(result.success(),
                "withdrawals from a blank-identity account must always fail");
    }

    @Test
    void normalIdentityStillWorks() {
        // 回归：正常身份不受影响
        MarketWalletAccount acc = MarketWalletAccount.empty("player-1", "GoatDie", 100L);
        MarketWalletAccount afterDeposit = MarketWalletService.deposit(acc, 240L, 110L);
        assertEquals(240L, afterDeposit.availableBalance());
    }
}
