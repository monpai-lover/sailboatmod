package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record LoanAccountRecord(
        String accountId,
        long principal,
        long accruedInterest,
        long lifetimeInterestPaid,
        long totalBorrowed,
        long lastAccruedAt,
        long nextDueAt,
        boolean delinquent
) {
    public LoanAccountRecord {
        accountId = accountId == null ? "" : accountId.trim().toLowerCase(Locale.ROOT);
        principal = Math.max(0L, principal);
        accruedInterest = Math.max(0L, accruedInterest);
        lifetimeInterestPaid = Math.max(0L, lifetimeInterestPaid);
        totalBorrowed = Math.max(0L, totalBorrowed);
        lastAccruedAt = Math.max(0L, lastAccruedAt);
        nextDueAt = Math.max(0L, nextDueAt);
    }

    public static LoanAccountRecord empty(String accountId) {
        return new LoanAccountRecord(accountId, 0L, 0L, 0L, 0L, 0L, 0L, false);
    }

    public long outstanding() {
        return principal + accruedInterest;
    }

    public boolean active() {
        return outstanding() > 0L;
    }

    public LoanAccountRecord withPrincipal(long value) {
        return new LoanAccountRecord(accountId, value, accruedInterest, lifetimeInterestPaid, totalBorrowed, lastAccruedAt, nextDueAt, delinquent);
    }

    public LoanAccountRecord withAccruedInterest(long value) {
        return new LoanAccountRecord(accountId, principal, value, lifetimeInterestPaid, totalBorrowed, lastAccruedAt, nextDueAt, delinquent);
    }

    public LoanAccountRecord withSchedule(long lastAccruedAt, long nextDueAt, boolean delinquent) {
        return new LoanAccountRecord(accountId, principal, accruedInterest, lifetimeInterestPaid, totalBorrowed, lastAccruedAt, nextDueAt, delinquent);
    }

    public LoanAccountRecord withPayment(long principal, long accruedInterest, long lifetimeInterestPaid, boolean delinquent) {
        return new LoanAccountRecord(accountId, principal, accruedInterest, lifetimeInterestPaid, totalBorrowed, lastAccruedAt, nextDueAt, delinquent);
    }

    public LoanAccountRecord withBorrow(long principal, long totalBorrowed, long lastAccruedAt, long nextDueAt) {
        return new LoanAccountRecord(accountId, principal, accruedInterest, lifetimeInterestPaid, totalBorrowed, lastAccruedAt, nextDueAt, false);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("AccountId", accountId);
        tag.putLong("Principal", principal);
        tag.putLong("AccruedInterest", accruedInterest);
        tag.putLong("LifetimeInterestPaid", lifetimeInterestPaid);
        tag.putLong("TotalBorrowed", totalBorrowed);
        tag.putLong("LastAccruedAt", lastAccruedAt);
        tag.putLong("NextDueAt", nextDueAt);
        tag.putBoolean("Delinquent", delinquent);
        return tag;
    }

    public static LoanAccountRecord load(CompoundTag tag) {
        return new LoanAccountRecord(
                tag.getString("AccountId"),
                tag.getLong("Principal"),
                tag.getLong("AccruedInterest"),
                tag.getLong("LifetimeInterestPaid"),
                tag.getLong("TotalBorrowed"),
                tag.getLong("LastAccruedAt"),
                tag.getLong("NextDueAt"),
                tag.getBoolean("Delinquent")
        );
    }
}
