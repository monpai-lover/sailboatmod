package com.monpai.sailboatmod.client.renderer.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * 旗帜渲染共享逻辑——国旗(Nation)与城镇旗(Town)使用完全相同的几何与飘动动画,只是贴图/数据来源不同。
 * 两个 BlockEntityRenderer 都委托到这里,保证它们永不分叉(历史上分头改造成了不同的墙挂 bug)。
 *
 * <p>站立旗几何是已知正确的基准;墙挂旗 = 站立旗去掉旗杆 + 贴墙偏移(在 scale 之前的世界尺度做,
 * 单位是方块格),所以旗布一定落在方块附近、不会浮空。
 */
public final class FlagRenderHelper {
    private static final ResourceLocation BANNER_BASE_TEXTURE = new ResourceLocation("minecraft", "textures/entity/banner/base.png");
    private static final float BASE_FLAG_WIDTH = 20.0F / 16.0F;
    private static final float BASE_FLAG_HEIGHT = 40.0F / 16.0F;
    private static final float FLAG_FRONT_Z = -1.0F / 16.0F;
    private static final float FLAG_BACK_Z = -2.0F / 16.0F;
    private static final float FLAG_Y_OFFSET = -32.0F / 16.0F;
    private static final float FLAG_AREA = BASE_FLAG_WIDTH * BASE_FLAG_HEIGHT;
    private static final float MIN_FLAG_WIDTH = 14.0F / 16.0F;
    private static final float MAX_FLAG_WIDTH = 44.0F / 16.0F;
    private static final float MIN_FLAG_HEIGHT = 14.0F / 16.0F;
    private static final float MAX_FLAG_HEIGHT = 40.0F / 16.0F;

    // 墙挂旗相对站立旗的偏移(世界尺度,方块格,在 scale 之前应用)。实机可调:
    // DROP 下移补偿无旗杆;TO_WALL 沿 facing 反方向(贴墙侧)平移,把旗根贴到墙面。
    private static final float WALL_FLAG_DROP = 0.30F;
    private static final float WALL_FLAG_TO_WALL = 0.45F;

    private static final int WAVE_SEGMENTS = 5;
    private static final float PRIMARY_WAVE_SPEED = 0.05F;
    private static final float SECONDARY_WAVE_SPEED = 0.13F;
    private static final float PRIMARY_AMPLITUDE = 0.025F;
    private static final float SECONDARY_AMPLITUDE = 0.012F;
    private static final float SEGMENT_PHASE_OFFSET = 0.6F;

    private FlagRenderHelper() {
    }

    /** 渲染一面旗(站立或墙挂)。pole/bar 是调用方各自 bake 的 BANNER ModelPart。 */
    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, ModelPart pole, ModelPart bar,
                              int light, int packedOverlay, boolean isWall, Direction facing,
                              int flagWidth, int flagHeight, ResourceLocation texture,
                              BlockPos blockPos, long gameTime, float partialTick) {
        FlagGeometry geometry = FlagGeometry.fromImageSize(flagWidth, flagHeight);
        poseStack.pushPose();
        if (isWall) {
            renderWallFlag(poseStack, bufferSource, pole, bar, light, packedOverlay, geometry, facing, texture, blockPos, gameTime, partialTick);
        } else {
            renderStandingFlag(poseStack, bufferSource, pole, bar, light, packedOverlay, geometry, facing, texture, blockPos, gameTime, partialTick);
        }
        poseStack.popPose();
    }

    private static void renderStandingFlag(PoseStack poseStack, MultiBufferSource bufferSource, ModelPart pole, ModelPart bar,
                                           int light, int packedOverlay, FlagGeometry geometry, Direction facing,
                                           ResourceLocation texture, BlockPos blockPos, long gameTime, float partialTick) {
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));

        poseStack.pushPose();
        poseStack.scale(0.6666667F, -0.6666667F, -0.6666667F);

        VertexConsumer baseConsumer = bufferSource.getBuffer(RenderType.entitySolid(BANNER_BASE_TEXTURE));
        pole.visible = true;
        pole.render(poseStack, baseConsumer, light, packedOverlay);

        poseStack.pushPose();
        poseStack.scale(geometry.barScaleX(), 1.0F, 1.0F);
        bar.render(poseStack, baseConsumer, light, packedOverlay);
        poseStack.popPose();

        poseStack.pushPose();
        poseStack.translate(0.0F, FLAG_Y_OFFSET, 0.0F);
        VertexConsumer clothConsumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));
        drawWavingFlagSurface(poseStack, clothConsumer, light, packedOverlay, geometry, blockPos, gameTime, partialTick);
        poseStack.popPose();

        poseStack.popPose();
    }

    private static void renderWallFlag(PoseStack poseStack, MultiBufferSource bufferSource, ModelPart pole, ModelPart bar,
                                       int light, int packedOverlay, FlagGeometry geometry, Direction facing,
                                       ResourceLocation texture, BlockPos blockPos, long gameTime, float partialTick) {
        // 以站立旗为基准:相同 translate(0.5,0.5,0.5)+旋转,但在 scale 之前(世界尺度)下移并往墙推,
        // 然后照站立旗那样 scale + 画 bar + 画布(只是不画旗杆)。这样旗布一定贴在方块附近。
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        // 站立旗旋转后:本地 -Z 指向 facing(墙外),+Z 指向贴墙侧。往 +Z 推即贴墙;Y 向下为正方向是世界 -Y。
        poseStack.translate(0.0F, -WALL_FLAG_DROP, WALL_FLAG_TO_WALL);

        poseStack.pushPose();
        poseStack.scale(0.6666667F, -0.6666667F, -0.6666667F);

        // 墙挂不画旗杆(只画横杆 + 旗布)
        VertexConsumer baseConsumer = bufferSource.getBuffer(RenderType.entitySolid(BANNER_BASE_TEXTURE));
        pole.visible = false;

        poseStack.pushPose();
        poseStack.scale(geometry.barScaleX(), 1.0F, 1.0F);
        bar.render(poseStack, baseConsumer, light, packedOverlay);
        poseStack.popPose();

        poseStack.pushPose();
        poseStack.translate(0.0F, FLAG_Y_OFFSET, 0.0F);
        VertexConsumer clothConsumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));
        drawWavingFlagSurface(poseStack, clothConsumer, light, packedOverlay, geometry, blockPos, gameTime, partialTick);
        poseStack.popPose();

        poseStack.popPose();
    }

    private static void drawWavingFlagSurface(PoseStack poseStack, VertexConsumer consumer, int packedLight, int packedOverlay,
                                              FlagGeometry geometry, BlockPos blockPos, long gameTime, float partialTick) {
        float time = gameTime + partialTick;
        long posSeed = blockPos.getX() * 7L + blockPos.getY() * 9L + blockPos.getZ() * 13L;
        float seedPhase = (posSeed % 360) * Mth.DEG_TO_RAD;

        float segHeight = geometry.bottom() / WAVE_SEGMENTS;

        for (int i = 0; i < WAVE_SEGMENTS; i++) {
            float topY = segHeight * i;
            float botY = segHeight * (i + 1);
            float vTop = (float) i / WAVE_SEGMENTS;
            float vBot = (float) (i + 1) / WAVE_SEGMENTS;

            float segFactor = (float) (i + 1) / WAVE_SEGMENTS;
            float prevSegFactor = (float) i / WAVE_SEGMENTS;

            float topWaveZ = computeWaveOffset(time, seedPhase, prevSegFactor, i);
            float botWaveZ = computeWaveOffset(time, seedPhase, segFactor, i + 1);

            drawSegmentFace(poseStack, consumer, packedLight, packedOverlay,
                    geometry.left(), topY, FLAG_FRONT_Z + topWaveZ, 0.0F, vTop,
                    geometry.right(), topY, FLAG_FRONT_Z + topWaveZ, 1.0F, vTop,
                    geometry.right(), botY, FLAG_FRONT_Z + botWaveZ, 1.0F, vBot,
                    geometry.left(), botY, FLAG_FRONT_Z + botWaveZ, 0.0F, vBot,
                    0.0F, 0.0F, 1.0F);

            drawSegmentFace(poseStack, consumer, packedLight, packedOverlay,
                    geometry.right(), topY, FLAG_BACK_Z + topWaveZ, 0.0F, vTop,
                    geometry.left(), topY, FLAG_BACK_Z + topWaveZ, 1.0F, vTop,
                    geometry.left(), botY, FLAG_BACK_Z + botWaveZ, 1.0F, vBot,
                    geometry.right(), botY, FLAG_BACK_Z + botWaveZ, 0.0F, vBot,
                    0.0F, 0.0F, -1.0F);
        }
    }

    private static float computeWaveOffset(float time, float seedPhase, float segFactor, int segIndex) {
        float primaryWave = Mth.sin(time * PRIMARY_WAVE_SPEED + seedPhase + segIndex * SEGMENT_PHASE_OFFSET) * PRIMARY_AMPLITUDE;
        float secondaryWave = Mth.sin(time * SECONDARY_WAVE_SPEED + seedPhase * 1.7F + segIndex * SEGMENT_PHASE_OFFSET * 0.8F) * SECONDARY_AMPLITUDE;
        return (primaryWave + secondaryWave) * segFactor;
    }

    private static void drawSegmentFace(PoseStack poseStack, VertexConsumer consumer, int packedLight, int packedOverlay,
                                        float x1, float y1, float z1, float u1, float v1,
                                        float x2, float y2, float z2, float u2, float v2,
                                        float x3, float y3, float z3, float u3, float v3,
                                        float x4, float y4, float z4, float u4, float v4,
                                        float nx, float ny, float nz) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f poseMatrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();
        vertex(consumer, poseMatrix, normalMatrix, x1, y1, z1, u1, v1, nx, ny, nz, packedLight, packedOverlay);
        vertex(consumer, poseMatrix, normalMatrix, x2, y2, z2, u2, v2, nx, ny, nz, packedLight, packedOverlay);
        vertex(consumer, poseMatrix, normalMatrix, x3, y3, z3, u3, v3, nx, ny, nz, packedLight, packedOverlay);
        vertex(consumer, poseMatrix, normalMatrix, x4, y4, z4, u4, v4, nx, ny, nz, packedLight, packedOverlay);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f poseMatrix, Matrix3f normalMatrix,
                               float x, float y, float z, float u, float v,
                               float nx, float ny, float nz, int packedLight, int packedOverlay) {
        consumer.vertex(poseMatrix, x, y, z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(packedOverlay)
                .uv2(packedLight)
                .normal(normalMatrix, nx, ny, nz)
                .endVertex();
    }

    private record FlagGeometry(float left, float right, float bottom, float barScaleX) {
        private static FlagGeometry fromImageSize(int width, int height) {
            float safeWidth = Math.max(1.0F, width);
            float safeHeight = Math.max(1.0F, height);
            float aspect = safeWidth / safeHeight;

            float computedWidth = (float) Math.sqrt(FLAG_AREA * aspect);
            float computedHeight = FLAG_AREA / computedWidth;

            if (computedWidth < MIN_FLAG_WIDTH) {
                computedWidth = MIN_FLAG_WIDTH;
                computedHeight = FLAG_AREA / computedWidth;
            } else if (computedWidth > MAX_FLAG_WIDTH) {
                computedWidth = MAX_FLAG_WIDTH;
                computedHeight = FLAG_AREA / computedWidth;
            }

            if (computedHeight < MIN_FLAG_HEIGHT) {
                computedHeight = MIN_FLAG_HEIGHT;
                computedWidth = FLAG_AREA / computedHeight;
            } else if (computedHeight > MAX_FLAG_HEIGHT) {
                computedHeight = MAX_FLAG_HEIGHT;
                computedWidth = FLAG_AREA / computedHeight;
            }

            float halfWidth = computedWidth * 0.5F;
            return new FlagGeometry(-halfWidth, halfWidth, computedHeight, computedWidth / BASE_FLAG_WIDTH);
        }
    }
}
