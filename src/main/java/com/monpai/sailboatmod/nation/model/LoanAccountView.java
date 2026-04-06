package com.monpai.sailboatmod.nation.model;

public record LoanAccountView(
        long principal,
        long accruedInterest,
        long outstanding,
        long lifetimeInterestPaid,
        long totalBorrowed,
        long maxBorrowable,
        long nextInterestCharge,
        long nextDueAt,
        boolean delinquent,
        boolean enabled
) {
    public static LoanAccountView disabled() {
        return new LoanAccountView(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, false, false);
    }
}
