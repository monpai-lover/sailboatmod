package com.monpai.sailboatmod.entity;

final class AutopilotPassengerInputPolicy {
    private AutopilotPassengerInputPolicy() {
    }

    static boolean shouldUseAutopilotCommand(boolean autopilotControl, boolean hasPassengerInput) {
        return autopilotControl;
    }

    static boolean shouldCancelAutopilotForPassengerInput(boolean autopilotActive, CarriageDriveInput input) {
        // Route stop/pause is an explicit UI action; rider input packets must not implicitly cancel it.
        return false;
    }
}
