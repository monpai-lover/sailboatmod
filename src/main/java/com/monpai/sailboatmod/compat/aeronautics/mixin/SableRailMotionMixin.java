package com.monpai.sailboatmod.compat.aeronautics.mixin;

import com.monpai.sailboatmod.compat.aeronautics.RailAircraftMotionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps normal block ticks and queue reset while the selected body has one route-motion owner. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.ServerSubLevel", remap = false)
public abstract class SableRailMotionMixin {
    @Inject(method = "prePhysicsTick(Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sailboat$railActors(@Coerce Object system, @Coerce Object handle, double dt, CallbackInfo ci) {
        if (RailAircraftMotionBridge.controlsNativeBody(this)) ci.cancel();
    }
    @Inject(method = "applyQueuedForces(Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sailboat$railQueuedForces(@Coerce Object system, @Coerce Object handle, double dt, CallbackInfo ci) {
        if (RailAircraftMotionBridge.controlsNativeBody(this)) ci.cancel();
    }
}
