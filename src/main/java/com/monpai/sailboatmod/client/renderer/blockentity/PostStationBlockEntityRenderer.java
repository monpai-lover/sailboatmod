package com.monpai.sailboatmod.client.renderer.blockentity;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;

public class PostStationBlockEntityRenderer extends DockBlockEntityRenderer {
    public PostStationBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    protected String facilityLabel() {
        return Component.translatable("block.sailboatmod.post_station").getString();
    }

    @Override
    protected int facilityColor() {
        return 0xD5B86A;
    }
}
