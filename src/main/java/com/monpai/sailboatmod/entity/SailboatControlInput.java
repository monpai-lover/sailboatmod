package com.monpai.sailboatmod.entity;

public record SailboatControlInput(boolean forward,
                                   boolean back,
                                   boolean left,
                                   boolean right) {
    public static final SailboatControlInput IDLE = new SailboatControlInput(false, false, false, false);

    public static SailboatControlInput fromKeys(boolean controlsEnabled,
                                                boolean forwardDown,
                                                boolean backDown,
                                                boolean leftDown,
                                                boolean rightDown) {
        if (!controlsEnabled) {
            return IDLE;
        }
        return new SailboatControlInput(forwardDown, backDown, leftDown, rightDown);
    }

    public boolean wantsForward() {
        return forward && !back;
    }

    public boolean wantsReverse() {
        return back && !forward;
    }

    public boolean wantsTurn() {
        return left != right;
    }

    public float turnInput() {
        if (left == right) {
            return 0.0F;
        }
        return left ? 1.0F : -1.0F;
    }

    public boolean hasManualInput() {
        return wantsForward() || wantsReverse() || wantsTurn();
    }
}
