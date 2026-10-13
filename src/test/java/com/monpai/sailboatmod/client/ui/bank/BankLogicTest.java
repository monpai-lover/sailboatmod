package com.monpai.sailboatmod.client.ui.bank;

import com.monpai.sailboatmod.network.packet.BankActionPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankLogicTest {
    @Test
    void amountsMustBePositiveWholeNumbers() {
        assertEquals(250, BankLogic.parseAmount(" 250 "));
        assertEquals(-1, BankLogic.parseAmount("0"));
        assertEquals(-1, BankLogic.parseAmount("-5"));
        assertEquals(-1, BankLogic.parseAmount("1.5"));
        assertEquals(-1, BankLogic.parseAmount(""));
        assertEquals(-1, BankLogic.parseAmount(null));
        assertEquals(-1, BankLogic.parseAmount("12345678901234567"), "longer than the old 16-character box");
    }

    @Test
    void emptyFieldIsNotFlaggedButGarbageIs() {
        assertFalse(BankLogic.isInvalidInput(""));
        assertFalse(BankLogic.isInvalidInput("40"));
        assertTrue(BankLogic.isInvalidInput("abc"));
    }

    @Test
    void buttonsKeepTheirPacketActions() {
        assertEquals(BankActionPacket.Action.DEPOSIT_CURRENCY, BankLogic.CurrencyAction.DEPOSIT.packetAction);
        assertEquals(BankActionPacket.Action.WITHDRAW_CURRENCY, BankLogic.CurrencyAction.WITHDRAW.packetAction);
        assertEquals(BankActionPacket.Action.WITHDRAW_AS_GOLD, BankLogic.CurrencyAction.WITHDRAW_AS_GOLD.packetAction);
        assertEquals(BankActionPacket.Action.BORROW_PERSONAL, BankLogic.loanAction(BankLogic.LoanAccount.PERSONAL, true));
        assertEquals(BankActionPacket.Action.REPAY_PERSONAL, BankLogic.loanAction(BankLogic.LoanAccount.PERSONAL, false));
        assertEquals(BankActionPacket.Action.BORROW_NATION, BankLogic.loanAction(BankLogic.LoanAccount.NATION, true));
        assertEquals(BankActionPacket.Action.REPAY_NATION, BankLogic.loanAction(BankLogic.LoanAccount.NATION, false));
    }
}
