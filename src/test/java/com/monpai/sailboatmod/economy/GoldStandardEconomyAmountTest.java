package com.monpai.sailboatmod.economy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoldStandardEconomyAmountTest {
    @ParameterizedTest
    @ValueSource(longs = {2147483648L, Long.MAX_VALUE})
    void unsupportedAmountIsRejectedBeforeAnyPayment(long amount) {
        assertEquals(Boolean.FALSE, GoldStandardEconomy.tryWithdraw(null, amount));
        assertEquals(Boolean.FALSE, GoldStandardEconomy.tryDeposit(null, amount));
        assertEquals(Boolean.FALSE, GoldStandardEconomy.tryWithdrawByIdentity(null, "", amount));
        assertEquals(Boolean.FALSE, GoldStandardEconomy.tryDepositByIdentity(null, "", amount));
    }
}
