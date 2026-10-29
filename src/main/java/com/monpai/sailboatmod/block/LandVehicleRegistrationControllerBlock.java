package com.monpai.sailboatmod.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.BaseEntityBlock;

public final class LandVehicleRegistrationControllerBlock extends RegisteredVehicleControllerBlock {
    public static final MapCodec<LandVehicleRegistrationControllerBlock> CODEC =
            simpleCodec(LandVehicleRegistrationControllerBlock::new);

    public LandVehicleRegistrationControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
