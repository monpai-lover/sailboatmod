package com.monpai.sailboatmod.transport;

/** Owns only the cargo inserted by one dispatch attempt. Both operations must be idempotent. */
public interface CargoLoadReceipt {
    void commit();

    void rollback();

    static CargoLoadReceipt loadEmptyCarrier(
            LogisticsCarrier carrier, java.util.List<net.minecraft.world.item.ItemStack> cargo) {
        if (carrier == null
                || carrier.hasCargo()
                || !carrier.canLoadCargo(cargo)
                || !carrier.loadCargo(cargo)) return null;
        return new CargoLoadReceipt() {
            private boolean closed;

            public synchronized void commit() {
                closed = true;
            }

            public synchronized void rollback() {
                if (!closed) {
                    closed = true;
                    carrier.unloadAllCargo();
                }
            }
        };
    }
}
