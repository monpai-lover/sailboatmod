package com.monpai.sailboatmod.client.renderer;

import com.monpai.sailboatmod.client.model.CarriageItemModel;
import com.monpai.sailboatmod.item.CarriageItem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class CarriageItemRenderer extends GeoItemRenderer<CarriageItem> {
    private static final ThreadLocal<ItemStack> CURRENT_STACK = ThreadLocal.withInitial(() -> ItemStack.EMPTY);

    public CarriageItemRenderer() {
        super(new CarriageItemModel());
    }

    public static ItemStack currentItemStack() {
        return CURRENT_STACK.get();
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        CURRENT_STACK.set(stack);
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        if (displayContext == ItemDisplayContext.GUI) {
            poseStack.scale(0.3F, 0.3F, 0.3F);
            poseStack.translate(0.0F, -0.18F, 0.0F);
        } else if (displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
            poseStack.scale(0.18F, 0.18F, 0.18F);
            poseStack.translate(0.0F, -0.05F, 0.0F);
        } else {
            poseStack.scale(0.22F, 0.22F, 0.22F);
        }
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        super.renderByItem(stack, displayContext, poseStack, bufferSource, packedLight, packedOverlay);
        poseStack.popPose();
        CURRENT_STACK.remove();
    }
}
