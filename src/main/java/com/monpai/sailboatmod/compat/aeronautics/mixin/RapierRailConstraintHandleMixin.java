package com.monpai.sailboatmod.compat.aeronautics.mixin;

import com.monpai.sailboatmod.compat.aeronautics.RailAircraftMotionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.constraint.RapierConstraintHandle", remap = false)
public abstract class RapierRailConstraintHandleMixin {
    @Inject(method = "remove()V", at = @At("RETURN"), require = 1, remap = false)
    private void sailboat$railConstraintRemoved(CallbackInfo ci) {
        RailAircraftMotionBridge.nativeConstraintRemoved(this);
    }
}
