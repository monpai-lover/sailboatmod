package com.monpai.sailboatmod.compat.aeronautics.mixin;

import com.monpai.sailboatmod.compat.aeronautics.RailAircraftMotionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes ordinary native topology; it never changes constraint results or foreign-body motion. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class RapierRailConstraintMixin {
    @Inject(method = "addConstraint(Ldev/ryanhcode/sable/api/physics/PhysicsPipelineBody;Ldev/ryanhcode/sable/api/physics/PhysicsPipelineBody;Ldev/ryanhcode/sable/api/physics/constraint/PhysicsConstraintConfiguration;)Ldev/ryanhcode/sable/api/physics/constraint/PhysicsConstraintHandle;",
            at = @At("RETURN"), require = 1, remap = false)
    private void sailboat$railConstraintAdded(@Coerce Object first, @Coerce Object second,
                                             @Coerce Object configuration, CallbackInfoReturnable<Object> ci) {
        RailAircraftMotionBridge.nativeConstraintAdded(this, first, second, ci.getReturnValue());
    }
    @Inject(method = "remove(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V",
            at = @At("RETURN"), require = 1, remap = false)
    private void sailboat$railBodyRemoved(@Coerce Object body, CallbackInfo ci) {
        RailAircraftMotionBridge.nativeBodyRemoved(this, body);
    }
    @Inject(method = "dispose()V", at = @At("RETURN"), require = 1, remap = false)
    private void sailboat$railPipelineDisposed(CallbackInfo ci) {
        RailAircraftMotionBridge.nativePipelineDisposed(this);
    }
}
