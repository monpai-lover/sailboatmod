package com.monpai.sailboatmod.client.renderer;

import net.minecraft.world.phys.Vec3;

public final class CarriageVisualRig {
    private static final double MODEL_UNIT = 1.0D / 16.0D;
    private static final double CARRIAGE_MODEL_Y_OFFSET = 0.0D;
    // 2026-06 车+马等比放大到正常比例(俯视图原比例偏小)。车 1.04→1.35;马 HORSE_MODEL_SCALE 同比例 1.0→1.3。
    // 两者一起调才保持协调。游戏内觉得大/小同比改这俩。
    private static final float CARRIAGE_MODEL_SCALE = 1.35F;
    // 2026-06 新模型车头朝向校正:先 90° 把长边(X)转到 Z,实测车头前后反了再 +180 = 270°。让车头(辕杆)与马同向。
    // 这是渲染层视觉旋转,不影响实体碰撞/移动方向。若再反改回 90,左右偏改 ±90。
    private static final float CARRIAGE_MODEL_YAW_OFFSET = 270.0F;
    private static final double SHAFT_TIP_Z_UNITS = 25.0D;
    // 马离辕杆尖的前向间隙(格):增大=马更靠车头前方。2026-06 马放大到约2.5需更往前→增大。
    private static final double HORSE_FORWARD_CLEARANCE = 1.2D;
    // 马离地高度:放大后马变高,Y 相应抬高否则腿插地里。约2.5倍 → 3.0。游戏内悬空改小、埋地改大。
    private static final double HORSE_MODEL_Y = 3.0D;
    // 马模型缩放:1.0=vanilla 原比例。2026-06 用户"再放大1倍",1.8→2.5(再大改 3.6=精确×2,先 2.5 防过大)。
    private static final float HORSE_MODEL_SCALE = 2.5F;

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
        return -entityYaw + CARRIAGE_MODEL_YAW_OFFSET;
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
