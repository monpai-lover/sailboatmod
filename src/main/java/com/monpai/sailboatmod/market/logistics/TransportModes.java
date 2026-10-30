package com.monpai.sailboatmod.market.logistics;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 物流运输方式。轨迹里的 transportMode 是自由字串(历史资料有 PORT/LAND/POST_STATION 等),这里统一推出载具种类,
 * 给网页地图选图示与特效。{@link #RAIL} 预留给之后接入 Create 的列车物流:以 RAIL 调用
 * {@link ShippingTraceService#createOrUpdateManualTrace} 即可在网页地图显示为列车。
 */
public final class TransportModes {
    public static final String PORT = "PORT";
    public static final String LAND = "LAND";
    public static final String POST_STATION = "POST_STATION";
    public static final String RAIL = "RAIL";
    public static final String AIR = "AIR";
    public static final String AIRSHIP = "AIRSHIP";
    public static final String HELICOPTER = "HELICOPTER";
    public static final String AIRPLANE = "AIRPLANE";

    public static final String KIND_SAILBOAT = "sailboat";
    public static final String KIND_CARRIAGE = "carriage";
    public static final String KIND_TRAIN = "train";
    public static final String KIND_AIRSHIP = "airship";
    public static final String KIND_HELICOPTER = "helicopter";
    public static final String KIND_AIRPLANE = "airplane";

    private static final Set<String> TRAIN_WORDS = Set.of("RAIL", "RAILWAY", "TRAIN");
    private static final Set<String> WATER_WORDS = Set.of("PORT", "WATER", "SEA", "SAIL", "SAILBOAT", "BOAT", "SHIP");

    private TransportModes() {
    }

    /**
     * 按整词比对(以非字母数字切分),如 CREATE_RAIL 是列车;只是含有片段的自订名称(BOATYARD_EXPRESS)不算船。
     * 未知方式沿用网页地图旧行为(画成马车)。网页 logistics-render.js 的 kindFromMode 用同一套规则。
     */
    public static String vehicleKind(String transportMode) {
        List<String> words = transportMode == null ? List.of()
                : Arrays.asList(transportMode.trim().toUpperCase(Locale.ROOT).split("[^A-Z0-9]+"));
        if (words.contains("AIRSHIP") || words.contains("ZEPPELIN")) return KIND_AIRSHIP;
        if (words.contains("HELICOPTER") || words.contains("ROTORCRAFT")) return KIND_HELICOPTER;
        if (words.stream().anyMatch(Set.of("AIR", "AIRPORT", "AIRPLANE", "AIRCRAFT", "PLANE", "FIXEDWING")::contains)
                || (words.contains("FIXED") && words.contains("WING"))) return KIND_AIRPLANE;
        if (words.stream().anyMatch(TRAIN_WORDS::contains)) {
            return KIND_TRAIN;
        }
        if (words.stream().anyMatch(WATER_WORDS::contains)) {
            return KIND_SAILBOAT;
        }
        return KIND_CARRIAGE;
    }

    public static String defaultVehicleName(String transportMode) {
        return switch (vehicleKind(transportMode)) {
            case KIND_SAILBOAT -> "帆船";
            case KIND_TRAIN -> "列车";
            case KIND_AIRSHIP -> "飞艇";
            case KIND_HELICOPTER -> "旋翼机";
            case KIND_AIRPLANE -> "固定翼飞机";
            default -> "马车";
        };
    }
}
