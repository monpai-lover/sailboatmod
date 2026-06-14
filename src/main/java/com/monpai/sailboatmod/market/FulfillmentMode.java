package com.monpai.sailboatmod.market;

import java.util.Locale;

/** 运输履约模式：真人自提 / 自动驾驶自提 / 卖家发货。 */
public enum FulfillmentMode {
    REAL_PICKUP,
    AUTO_PICKUP,
    SELLER_SHIP;

    /** 安全解析：未知/空白回退 SELLER_SHIP；大小写不敏感、去空格。 */
    public static FulfillmentMode fromString(String value) {
        if (value == null) {
            return SELLER_SHIP;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (FulfillmentMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return SELLER_SHIP;
    }

    /** ②③ 需调度配车；① 真人自提不进调度（货锁定等玩家亲自来）。 */
    public boolean needsVehicleDispatch() {
        return this != REAL_PICKUP;
    }
}
