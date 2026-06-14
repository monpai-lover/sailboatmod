package com.monpai.sailboatmod.market.wallet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWalletServiceTest {
    @Test
    void accountDepositsWithdrawsAndReservesFunds() {
        MarketWalletAccount account = MarketWalletAccount.empty("player-1", "GoatDie", 100L);

        account = MarketWalletService.deposit(account, 240L, 110L);
        assertEquals(240L, account.availableBalance());
        assertEquals(0L, account.reservedBalance());
        assertEquals(110L, account.updatedAtMillis());

        MarketWalletService.AccountResult reserved = MarketWalletService.reserve(account, 90L, 120L);
        assertTrue(reserved.success());
        assertEquals(150L, reserved.account().availableBalance());
        assertEquals(90L, reserved.account().reservedBalance());

        MarketWalletService.AccountResult released = MarketWalletService.releaseReserved(reserved.account(), 40L, 130L);
        assertTrue(released.success());
        assertEquals(190L, released.account().availableBalance());
        assertEquals(50L, released.account().reservedBalance());

        MarketWalletService.AccountResult withdrawn = MarketWalletService.withdraw(released.account(), 191L, 140L);
        assertFalse(withdrawn.success());
        assertEquals(190L, withdrawn.account().availableBalance());
    }

    @Test
    void spendingReservedConsumesOnlyFrozenFunds() {
        MarketWalletAccount account = MarketWalletAccount.empty("player-2", "Buyer", 100L);
        account = MarketWalletService.deposit(account, 200L, 101L);
        account = MarketWalletService.reserve(account, 75L, 102L).account();

        MarketWalletService.AccountResult spent = MarketWalletService.spendReserved(account, 50L, 103L);
        assertTrue(spent.success());
        assertEquals(125L, spent.account().availableBalance());
        assertEquals(25L, spent.account().reservedBalance());

        MarketWalletService.AccountResult tooMuch = MarketWalletService.spendReserved(spent.account(), 26L, 104L);
        assertFalse(tooMuch.success());
        assertEquals(125L, tooMuch.account().availableBalance());
        assertEquals(25L, tooMuch.account().reservedBalance());
    }
}
