package com.example.examplemod.client.renderer.blockentity;

import com.example.examplemod.block.TownFlagBlock;
import com.example.examplemod.block.entity.TownFlagBlockEntity;
import com.example.examplemod.client.texture.NationFlagTextureCache;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public class TownFlagBlockEntityRenderer implements BlockEntityRenderer<TownFlagBlockEntity> {
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

    private final ModelPart pole;
    private final ModelPart bar;

    public TownFlagBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        ModelPart root = context.bakeLayer(ModelLayers.BANNER);
        this.pole = root.getChild("pole");
        this.bar = root.getChild("bar");
    }

    @Override
    public void render(TownFlagBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        int light = blockEntity.getLevel() == null
                ? packedLight
                : LevelRenderer.getLightColor(blockEntity.getLevel(), blockEntity.getBlockPos().above());
        FlagGeometry geometry = FlagGeometry.fromImageSize(blockEntity.getFlagWidth(), blockEntity.getFlagHeight());
        Direction facing = blockEntity.getBlockState().hasProperty(TownFlagBlock.FACING)
                ? blockEntity.getBlockState().getValue(TownFlagBlock.FACING)
                : Direction.NORTH;

        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));

        poseStack.pushPose();
        poseStack.scale(0.6666667F, -0.6666667F, -0.6666667F);

        VertexConsumer baseConsumer = bufferSource.getBuffer(RenderType.entitySolid(BANNER_BASE_TEXTURE));
        this.pole.visible = true;
        this.pole.render(poseStack, baseConsumer, light, packedOverlay);

        poseStack.pushPose();
        poseStack.scale(geometry.barScaleX(), 1.0F, 1.0F);
        this.bar.render(poseStack, baseConsumer, light, packedOverlay);
        poseStack.popPose();

        poseStack.pushPose();
        poseStack.translate(0.0F, FLAG_Y_OFFSET, 0.0F);
        poseStack.mulPose(Axis.XP.rotation(flagRotation(blockEntity, partialTick)));

        ResourceLocation texture = NationFlagTextureCache.resolve(blockEntity.getFlagId(), blockEntity.getPrimaryColor(), blockEntity.getSecondaryColor(), blockEntity.isFlagMirrored());
        VertexConsumer clothConsumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));
        drawFlagSurface(poseStack, clothConsumer, light, packedOverlay, geometry);

        poseStack.popPose();
        poseStack.popPose();
        poseStack.popPose();
    }

    private static float flagRotation(TownFlagBlockEntity blockEntity, float partialTick) {
        long gameTime = blockEntity.getLevel() == null ? 0L : blockEntity.getLevel().getGameTime();
        long waveSeed = blockEntity.getBlockPos().getX() * 7L + blockEntity.getBlockPos().getY() * 9L + blockEntity.getBlockPos().getZ() * 13L + gameTime;
        float waveTime = (Math.floorMod(waveSeed, 100L) + partialTick) / 100.0F;
        return (-0.0125F + 0.01F * Mth.cos((float) (Math.PI * 2) * waveTime)) * (float) Math.PI;
    }

    private static void drawFlagSurface(PoseStack poseStack, VertexConsumer consumer, int packedLight, int packedOverlay, FlagGeometry geometry) {
        drawFace(poseStack, consumer, packedLight, packedOverlay,
                geometry.left(), 0.0F, FLAG_FRONT_Z, 0.0F, 0.0F,
                geometry.right(), 0.0F, FLAG_FRONT_Z, 1.0F, 0.0F,
                geometry.right(), geometry.bottom(), FLAG_FRONT_Z, 1.0F, 1.0F,
                geometry.left(), geometry.bottom(), FLAG_FRONT_Z, 0.0F, 1.0F,
                0.0F, 0.0F, 1.0F);
        drawFace(poseStack, consumer, packedLight, packedOverlay,
                geometry.right(), 0.0F, FLAG_BACK_Z, 0.0F, 0.0F,
                geometry.left(), 0.0F, FLAG_BACK_Z, 1.0F, 0.0F,
                geometry.left(), geometry.bottom(), FLAG_BACK_Z, 1.0F, 1.0F,
                geometry.right(), geometry.bottom(), FLAG_BACK_Z, 0.0F, 1.0F,
                0.0F, 0.0F, -1.0F);
    }

    private static void drawFace(PoseStack poseStack, VertexConsumer consumer, int packedLight, int packedOverlay,
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