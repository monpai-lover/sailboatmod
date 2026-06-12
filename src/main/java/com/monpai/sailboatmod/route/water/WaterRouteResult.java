package com.monpai.sailboatmod.route.water;

public record WaterRouteResult<T>(boolean successful, T value, WaterRouteFailureReason reason) {
    public static <T> WaterRouteResult<T> success(T value) {
        return new WaterRouteResult<>(true, value, WaterRouteFailureReason.NONE);
    }

    public static <T> WaterRouteResult<T> failure(WaterRouteFailureReason reason) {
        return new WaterRouteResult<>(false, null, reason == null ? WaterRouteFailureReason.NO_WATER_PATH : reason);
    }
}
