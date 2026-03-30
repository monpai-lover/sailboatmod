package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.entity.SailboatEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.Locale;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SailboatSpeedHud {
    private static final double BLOCKS_PER_TICK_TO_KNOTS = 38.87689D;
    private static final double SPEED_SMOOTH_ALPHA = 0.14D;
    private static final double DISPLAY_STEP_KNOTS = 0.5D;
    private static final double DISPLAY_FREEZE_DELTA = 0.24D;
    private static double smoothedKnots = 0.0D;
    private static double displayedKnots = 0.0D;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !(player.getVehicle() instanceof SailboatEntity sailboat)) {
            smoothedKnots = 0.0D;
            displayedKnots = 0.0D;
            return;
        }

        Vec3 velocity = sailboat.getDeltaMovement();
        double yawRad = sailboat.getYRot() * (Math.PI / 180.0D);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);
        double signedForward = velocity.x * dirX + velocity.z * dirZ;
        double rawKnots = signedForward * BLOCKS_PER_TICK_TO_KNOTS;
        smoothedKnots += (rawKnots - smoothedKnots) * SPEED_SMOOTH_ALPHA;

        double quantized = Math.round(smoothedKnots / DISPLAY_STEP_KNOTS) * DISPLAY_STEP_KNOTS;
        if (Math.abs(quantized - displayedKnots) >= DISPLAY_FREEZE_DELTA) {
            displayedKnots = quantized;
        }
        if (Math.abs(displayedKnots) < 0.05D) {
            displayedKnots = 0.0D;
        }
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !(player.getVehicle() instanceof SailboatEntity sailboat)) {
            return;
        }
        Component speedText = Component.literal(String.format(Locale.ROOT, "Speed: %.1f kn", displayedKnots));
        Component gearText = Component.literal("Gear: " + sailboat.getEngineGear().displayName);

        int x = 10;
        int y = event.getWindow().getGuiScaledHeight() - 40;
        event.getGuiGraphics().drawString(minecraft.font, speedText, x, y, 0xFFFFFF, true);
        event.getGuiGraphics().drawString(minecraft.font, gearText, x, y + 12, 0xFFD27F, true);
    }

    private SailboatSpeedHud() {
    }
}
