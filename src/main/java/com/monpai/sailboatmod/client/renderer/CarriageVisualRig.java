package com.monpai.sailboatmod.client.renderer;

import net.minecraft.world.phys.Vec3;

public final class CarriageVisualRig {
    private static final double MODEL_UNIT = 1.0D / 16.0D;
    private static final double CARRIAGE_MODEL_Y_OFFSET = 0.0D;
    private static final float CARRIAGE_MODEL_SCALE = 1.04F;
    private static final double SHAFT_TIP_Z_UNITS = 25.0D;
    private static final double HORSE_FORWARD_CLEARANCE = 0.18D;
    private static final double HORSE_MODEL_Y = 1.42D;
    private static final float HORSE_MODEL_SCALE = 0.96F;

    private CarriageVisualRig() {
    }

    public record HorseAttachmentPose(Vec3 localOffset, float scale) {
    }

    public static HorseAttachmentPose horseAttachmentPose(float entityYaw, double bob) {
        return new HorseAttachmentPose(
                new Vec3(0.0D, HORSE_MODEL_Y + bob, -(scaledShaftTipZ() + HORSE_FORWARD_CLEARANCE)),
                HORSE_MODEL_SCALE
        );
    }

    public static double carriageModelYOffset() {
        return CARRIAGE_MODEL_Y_OFFSET;
    }

    public static float carriageModelScale() {
        return CARRIAGE_MODEL_SCALE;
    }

    public static double scaledShaftTipZ() {
        return SHAFT_TIP_Z_UNITS * MODEL_UNIT * CARRIAGE_MODEL_SCALE;
    }

    public static double scaledShaftCenterY() {
        return 6.0D * MODEL_UNIT * CARRIAGE_MODEL_SCALE;
    }

    public static Vec3 rotateLocalOffset(Vec3 localOffset, float entityYaw) {
        Vec3 offset = localOffset == null ? Vec3.ZERO : localOffset;
        double yawRad = -entityYaw * (Math.PI / 180.0D);
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        return new Vec3(
                offset.x * cos + offset.z * sin,
                offset.y,
                -offset.x * sin + offset.z * cos
        );
    }

    public static float localYawRotation(float entityYaw) {
        return -entityYaw;
    }

    public static float horseRootYawRotation(float entityYaw) {
        return 180.0F - entityYaw;
    }

    public static Vec3 renderedHorseWorldOffset(float entityYaw, double bob) {
        HorseAttachmentPose pose = horseAttachmentPose(entityYaw, bob);
        return rotateLocalOffsetByDegrees(pose.localOffset(), horseRootYawRotation(entityYaw));
    }

    private static Vec3 rotateLocalOffsetByDegrees(Vec3 localOffset, float rotationDegrees) {
        Vec3 offset = localOffset == null ? Vec3.ZERO : localOffset;
        double yawRad = rotationDegrees * (Math.PI / 180.0D);
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        return new Vec3(
                offset.x * cos + offset.z * sin,
                offset.y,
                -offset.x * sin + offset.z * cos
        );
    }
}
