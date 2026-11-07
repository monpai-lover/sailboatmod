package com.monpai.sailboatmod.transport.rail;

import com.monpai.sailboatmod.transport.CargoLoadReceipt;

/** Couples the staged physical cargo with the native one-way schedule for this dispatch. */
public interface RailDispatchReceipt extends CargoLoadReceipt {
    boolean start();
}
