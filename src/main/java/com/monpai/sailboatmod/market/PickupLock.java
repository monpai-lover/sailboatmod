package com.monpai.sailboatmod.market;

/** 买家自提（真人/自动）的锁定与装货纯逻辑。 */
public final class PickupLock {
    /** 自提锁定状态常量。 */
    public static final String STATUS_LOCKED = "PICKUP_LOCKED";

    private PickupLock() {
    }

    /** 实际可装量 = 锁定量与载具剩余空间取小（均非负）。 */
    public static int loadableAmount(int lockedQuantity, int vehicleSpace) {
        int locked = Math.max(0, lockedQuantity);
        int space = Math.max(0, vehicleSpace);
        return Math.min(locked, space);
    }

    /** 是否为待自提的锁定订单：状态 PICKUP_LOCKED 且 fulfillment 是自提（真人/自动）。 */
    public static boolean isPickupOrder(String status, String fulfillment) {
        if (status == null || !STATUS_LOCKED.equals(status.trim())) {
            return false;
        }
        FulfillmentMode mode = FulfillmentMode.fromString(fulfillment);
        return mode == FulfillmentMode.REAL_PICKUP || mode == FulfillmentMode.AUTO_PICKUP;
    }

    /** 载具是否属于该买家（按 uuid 匹配，任一为空则否）。 */
    public static boolean vehicleBelongsToBuyer(String vehicleOwnerUuid, String buyerUuid) {
        return vehicleOwnerUuid != null && buyerUuid != null
                && !vehicleOwnerUuid.isBlank()
                && vehicleOwnerUuid.equals(buyerUuid);
    }
}
