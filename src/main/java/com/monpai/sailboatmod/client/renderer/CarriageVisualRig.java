package com.monpai.sailboatmod.client.renderer;

import net.minecraft.world.phys.Vec3;

public final class CarriageVisualRig {
    private static final double MODEL_UNIT = 1.0D / 16.0D;
    // 车离地高度偏移:0.55→0.12→0.02;2026-06 用户「还有一点点浮空」再降 0.05 → -0.03 让车轮压地。车悬空改小、轮埋地改大。
    private static final double CARRIAGE_MODEL_Y_OFFSET = -0.03D;
    // 2026-06 车放大:2.2 太大→1.8。游戏内觉得大/小改此值,记得连同 CARRIAGE_MODEL_Y_OFFSET 调高度。
    private static final float CARRIAGE_MODEL_SCALE = 1.8F;
    // 2026-06 新模型车头朝向校正:先 90° 把长边(X)转到 Z,实测车头前后反了再 +180 = 270°。让车头(辕杆)与马同向。
    // 这是渲染层视觉旋转,不影响实体碰撞/移动方向。若再反改回 90,左右偏改 ±90。
    private static final float CARRIAGE_MODEL_YAW_OFFSET = 270.0F;
    private static final double SHAFT_TIP_Z_UNITS = 25.0D;
    // 马离辕杆尖的前向间隙(格):增大=马更靠车头前方,减小=往车身后靠。2026-06 用户要再往后约1格→0.5减到 -0.5。
    private static final double HORSE_FORWARD_CLEARANCE = -0.5D;
    // 马离地高度(相对车中心的世界 Y 偏移):2026-06 改走 dispatcher.render 后马脚=实体原点,高度语义变,
    // 原 3.0 让马飞天→降到 0.0 附近(马脚约落车底地面)。悬空改小、埋地改大,游戏内对马脚落地微调。
    private static final double HORSE_MODEL_Y = 0.0D;
    // 马模型缩放:改走 dispatcher.render 后内部已按实体尺寸归一化(vanilla 马已是正常大小),
    // 原 2.2 是手画路径补偿值,现在 2.2 放大过头→降到 1.3(比真马稍大配大车,不夸张)。游戏内觉得大/小改此值。
    private static final float HORSE_MODEL_SCALE = 1.3F;

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

    public static float horseModelScale() {
        return HORSE_MODEL_SCALE;
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
