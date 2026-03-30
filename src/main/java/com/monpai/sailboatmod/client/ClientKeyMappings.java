package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.SailboatMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientKeyMappings {
    public static final KeyMapping OPEN_SAILBOAT_INFO = new KeyMapping(
            "key.sailboatmod.open_info",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.gameplay"
    );

    public static final KeyMapping OPEN_NATION_MENU = new KeyMapping(
            "key.sailboatmod.open_nation_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "key.categories.gameplay"
    );

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SAILBOAT_INFO);
        event.register(OPEN_NATION_MENU);
    }

    private ClientKeyMappings() {
    }
}
