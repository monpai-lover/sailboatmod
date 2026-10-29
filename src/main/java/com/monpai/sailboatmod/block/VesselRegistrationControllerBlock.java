package com.monpai.sailboatmod.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.BaseEntityBlock;

public final class VesselRegistrationControllerBlock extends RegisteredVehicleControllerBlock {
    public static final MapCodec<VesselRegistrationControllerBlock> CODEC =
            simpleCodec(VesselRegistrationControllerBlock::new);

    public VesselRegistrationControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
