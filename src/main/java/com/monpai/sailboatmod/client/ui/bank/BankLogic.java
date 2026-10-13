package com.monpai.sailboatmod.client.ui.bank;

import com.monpai.sailboatmod.network.packet.BankActionPacket;

/**
 * Framework-free rules of the bank screen: which page, which loan account, which packet action a button maps to,
 * and how typed amounts are read. The server still checks membership, permission and funds for every action.
 */
public final class BankLogic {
    public enum Page { TREASURY, STORAGE, LOANS }

    public enum LoanAccount { PERSONAL, NATION }

    public enum CurrencyAction {
        DEPOSIT(BankActionPacket.Action.DEPOSIT_CURRENCY),
        WITHDRAW(BankActionPacket.Action.WITHDRAW_CURRENCY),
        WITHDRAW_AS_GOLD(BankActionPacket.Action.WITHDRAW_AS_GOLD);

        public final BankActionPacket.Action packetAction;

        CurrencyAction(BankActionPacket.Action packetAction) {
            this.packetAction = packetAction;
        }
    }

    /** Longest amount the old input accepted (16 characters). */
    public static final int MAX_AMOUNT_LENGTH = 16;

    private BankLogic() {
    }

    /** Typed amount, or -1 when it is empty, not a whole number, or not positive (the old screen sent nothing then too). */
    public static long parseAmount(String text) {
        if (text == null) {
            return -1;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_AMOUNT_LENGTH) {
            return -1;
        }
        try {
            long value = Long.parseLong(trimmed);
            return value > 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** True when the field holds something that is not a valid amount (an empty field is not an error yet). */
    public static boolean isInvalidInput(String text) {
        return text != null && !text.isBlank() && parseAmount(text) < 0;
    }

    public static BankActionPacket.Action loanAction(LoanAccount account, boolean borrow) {
        if (account == LoanAccount.PERSONAL) {
            return borrow ? BankActionPacket.Action.BORROW_PERSONAL : BankActionPacket.Action.REPAY_PERSONAL;
        }
        return borrow ? BankActionPacket.Action.BORROW_NATION : BankActionPacket.Action.REPAY_NATION;
    }

    /** Hint shown under the loan figures, as the old status line did. */
    public static String loanHintKey(LoanAccount account) {
        return account == LoanAccount.PERSONAL
                ? "screen.sailboatmod.bank.loan_personal_hint"
                : "screen.sailboatmod.bank.loan_nation_hint";
    }
}
