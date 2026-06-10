package com.monpai.sailboatmod.entity;

import java.util.UUID;

final class SailboatManualInputState {
    private static final int INPUT_STALE_TICKS = 5;

    private UUID captainId;
    private SailboatControlInput input = SailboatControlInput.IDLE;
    private int lastUpdateTick = Integer.MIN_VALUE;

    void update(UUID captainId, SailboatControlInput input, int tick) {
        this.captainId = captainId;
        this.input = input == null ? SailboatControlInput.IDLE : input;
        this.lastUpdateTick = tick;
    }

    void clear() {
        this.captainId = null;
        this.input = SailboatControlInput.IDLE;
        this.lastUpdateTick = Integer.MIN_VALUE;
    }

    SailboatControlInput currentInput(UUID captainId, int currentTick) {
        if (captainId == null || !captainId.equals(this.captainId) || !isFresh(currentTick)) {
            return SailboatControlInput.IDLE;
        }
        return input;
    }

    private boolean isFresh(int currentTick) {
        return lastUpdateTick != Integer.MIN_VALUE && currentTick - lastUpdateTick <= INPUT_STALE_TICKS;
    }
}
