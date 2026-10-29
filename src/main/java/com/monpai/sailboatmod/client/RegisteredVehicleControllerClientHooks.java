package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.RegisteredVehicleControllerScreen;
import com.monpai.sailboatmod.transport.control.ControllerScreenData;

import net.minecraft.client.Minecraft;

public final class RegisteredVehicleControllerClientHooks {
    private RegisteredVehicleControllerClientHooks() {}

    public static void openOrUpdate(ControllerScreenData data, boolean repaint) {
        var client = Minecraft.getInstance();
        if (client.screen instanceof RegisteredVehicleControllerScreen screen
                && screen.registrationId().equals(data.registrationId()))
            screen.update(data, repaint);
        else if (repaint) client.setScreen(new RegisteredVehicleControllerScreen(data));
    }
}
