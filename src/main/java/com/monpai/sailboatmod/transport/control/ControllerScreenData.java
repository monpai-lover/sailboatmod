package com.monpai.sailboatmod.transport.control;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public record ControllerScreenData(
        BlockPos controllerPos, UUID registrationId, CompoundTag values) {
    public ControllerScreenData {
        controllerPos = controllerPos.immutable();
        values = values.copy();
    }
}
