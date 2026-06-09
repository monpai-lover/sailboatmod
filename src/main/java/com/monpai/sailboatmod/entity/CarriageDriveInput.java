package com.monpai.sailboatmod.entity;

import net.minecraft.util.Mth;

public record CarriageDriveInput(AccelerationDirection acceleration,
                                 TurnDirection turn,
                                 float targetTurnAngle,
                                 float power) {
    public CarriageDriveInput {
        acceleration = acceleration == null ? AccelerationDirection.NONE : acceleration;
        turn = turn == null ? TurnDirection.FORWARD : turn;
        targetTurnAngle = Mth.clamp(targetTurnAngle, -CarriageLandDriveModel.MAX_TURN_ANGLE, CarriageLandDriveModel.MAX_TURN_ANGLE);
        power = Mth.clamp(power, 0.0F, 1.0F);
    }

    public static CarriageDriveInput idle() {
        return new CarriageDriveInput(AccelerationDirection.NONE, TurnDirection.FORWARD, 0.0F, 0.0F);
    }

    public boolean hasThrottle() {
        return acceleration == AccelerationDirection.FORWARD
                || acceleration == AccelerationDirection.REVERSE
                || acceleration == AccelerationDirection.CHARGING;
    }

    public enum AccelerationDirection {
        FORWARD,
        NONE,
        REVERSE,
        CHARGING
    }

    public enum TurnDirection {
        LEFT(1),
        FORWARD(0),
        RIGHT(-1);

        private final int direction;

        TurnDirection(int direction) {
            this.direction = direction;
        }

        public int direction() {
            return direction;
        }
    }
}
