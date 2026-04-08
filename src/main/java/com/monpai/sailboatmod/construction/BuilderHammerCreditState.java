package com.monpai.sailboatmod.construction;

public record BuilderHammerCreditState(int queuedCredits, int maxQueuedCredits, boolean accepted) {
    public static BuilderHammerCreditState of(int queuedCredits, int maxQueuedCredits) {
        return new BuilderHammerCreditState(
                Math.max(0, queuedCredits),
                Math.max(0, maxQueuedCredits),
                false
        );
    }

    public BuilderHammerCreditState enqueue() {
        if (queuedCredits >= maxQueuedCredits) {
            return new BuilderHammerCreditState(queuedCredits, maxQueuedCredits, false);
        }
        return new BuilderHammerCreditState(queuedCredits + 1, maxQueuedCredits, true);
    }
}
