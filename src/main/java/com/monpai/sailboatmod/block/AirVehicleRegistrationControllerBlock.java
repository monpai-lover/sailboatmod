package com.monpai.sailboatmod.block;

import com.mojang.serialization.MapCodec;

/** Registers a physical aircraft; the installed Flight Control terminal owns its actuators. */
public final class AirVehicleRegistrationControllerBlock extends RegisteredVehicleControllerBlock {
    public static final MapCodec<AirVehicleRegistrationControllerBlock> CODEC =
            simpleCodec(AirVehicleRegistrationControllerBlock::new);

    public AirVehicleRegistrationControllerBlock(Properties properties) { super(properties); }

    @Override
    protected MapCodec<? extends net.minecraft.world.level.block.BaseEntityBlock> codec() { return CODEC; }
}
