package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.entity.TransportEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
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
    private static final double METERS_PER_SECOND_TO_KNOTS = 1.9438445D;
    private static final double METERS_PER_SECOND_TO_KMH = 3.6D; // 马车用陆地单位 km/h(节是航海单位,陆地载具不合理)
    private static final double SPEED_SMOOTH_ALPHA = 0.14D;
    private static final double DISPLAY_STEP_KNOTS = 0.5D;
    private static final double DISPLAY_FREEZE_DELTA = 0.24D;
    private static double smoothedKnots = 0.0D;   // 平滑后的显示值(单位随载具:帆船=kn,马车=km/h)
    private static double displayedKnots = 0.0D;  // 量化冻结后的显示值
    private static String speedUnit = "kn";       // 当前显示单位(帆船 kn / 马车 km/h)

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !(player.getVehicle() instanceof TransportEntity transport)) {
            smoothedKnots = 0.0D;
            displayedKnots = 0.0D;
            return;
        }

        double rawKnots;
        if (transport instanceof CarriageEntity carriage) {
            // 马车用陆地单位 km/h(独立于帆船的 kn)。getCurrentSpeedForHud() 是 m/s,×3.6 得 km/h。
            rawKnots = carriage.getCurrentSpeedForHud() * METERS_PER_SECOND_TO_KMH;
            speedUnit = "km/h";
        } else if (transport instanceof SailboatEntity sailboat) {
            // 帆船服务端权威，客户端 deltaMovement 恒=ZERO，必须读服务端同步的前向速度(格/tick)，
            // 否则 HUD 恒显示 0.0 kn。
            rawKnots = sailboat.getCurrentSpeedForHud() * BLOCKS_PER_TICK_TO_KNOTS;
            speedUnit = "kn";
        } else {
            Entity vehicle = transport.asEntity();
            Vec3 velocity = vehicle.getDeltaMovement();
            double yawRad = vehicle.getYRot() * (Math.PI / 180.0D);
            double dirX = -Math.sin(yawRad);
            double dirZ = Math.cos(yawRad);
            double signedForward = velocity.x * dirX + velocity.z * dirZ;
            rawKnots = signedForward * BLOCKS_PER_TICK_TO_KNOTS;
            speedUnit = "kn";
        }
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
        if (player == null || !(player.getVehicle() instanceof TransportEntity transport)) {
            return;
        }
        Component speedText = Component.literal(String.format(Locale.ROOT, "Speed: %.1f %s", displayedKnots, speedUnit));
        Component gearText = transport instanceof CarriageEntity carriage
                ? Component.literal("Drive: " + carriageDriveLabel(player, carriage))
                : transport instanceof SailboatEntity sailboat
                ? Component.translatable("hud.sailboatmod.gear",
                        Component.translatable(sailboat.getEngineGear().translationKey))
                : Component.empty();

        int x = 10;
        int y = event.getWindow().getGuiScaledHeight() - 40;
        event.getGuiGraphics().drawString(minecraft.font, speedText, x, y, 0xFFFFFF, true);
        event.getGuiGraphics().drawString(minecraft.font, gearText, x, y + 12, 0xFFD27F, true);
    }

    private SailboatSpeedHud() {
    }

    private static String carriageDriveLabel(LocalPlayer player, CarriageEntity carriage) {
        if (carriage.isAutopilotActive()) {
            return carriage.isAutopilotPaused() ? "Auto Paused" : "Auto";
        }
        if (player.zza > 0.15F) {
            return "Forward";
        }
        if (player.zza < -0.15F) {
            return displayedKnots > 0.5D ? "Brake" : "Reverse";
        }
        if (Math.abs(displayedKnots) < 0.2D) {
            return "Stopped";
        }
        return "Coast";
    }
}
