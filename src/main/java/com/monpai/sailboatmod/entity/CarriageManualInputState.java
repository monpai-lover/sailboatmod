package com.monpai.sailboatmod.entity;

final class CarriageManualInputState {
    private static final int INPUT_STALE_TICKS = 5;

    private boolean left;
    private boolean right;
    private boolean forward;
    private boolean back;
    private int lastUpdateTick = Integer.MIN_VALUE;

    void update(boolean left, boolean right, boolean forward, boolean back, int tick) {
        this.left = left;
        this.right = right;
        this.forward = forward;
        this.back = back;
        this.lastUpdateTick = tick;
    }

    void clear() {
        this.left = false;
        this.right = false;
        this.forward = false;
        this.back = false;
        this.lastUpdateTick = Integer.MIN_VALUE;
    }

    SailboatEntity.GroundDriveContext applyTo(SailboatEntity.GroundDriveContext base, int currentTick) {
        SailboatEntity.GroundDriveContext effectiveBase = base == null
                ? new SailboatEntity.GroundDriveContext(false, false, false, false, false, 0.0F, SailboatEntity.EngineGear.STOP)
                : base;
        if (!isFresh(currentTick)) {
            return effectiveBase;
        }

        float manualTurn = 0.0F;
        if (left != right) {
            manualTurn = left ? 1.0F : -1.0F;
        }
        boolean wantsTurn = Math.abs(manualTurn) > 0.0F;
        boolean hasManualInput = effectiveBase.hasManualInput() || forward || back || wantsTurn;
        return new SailboatEntity.GroundDriveContext(
                effectiveBase.autopilotControl(),
                hasManualInput,
                effectiveBase.wantsForward() || forward,
                effectiveBase.wantsReverse() || back,
                effectiveBase.wantsTurn() || wantsTurn,
                wantsTurn ? manualTurn : effectiveBase.turnInput(),
                effectiveBase.gear()
        );
    }

    private boolean isFresh(int currentTick) {
        return lastUpdateTick != Integer.MIN_VALUE && currentTick - lastUpdateTick <= INPUT_STALE_TICKS;
    }
}
