package com.monpai.sailboatmod.compat.flightcontrol.mixin;

import com.monpai.sailboatmod.compat.flightcontrol.NativeTakeoffAssist;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the current native suspension calculation immediately before its real impulse. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity", remap = false)
abstract class AircraftWheelContactMixin {
    @Shadow private double extension;
    @Shadow @Final private Vector3d queuedForce;

    @Inject(method = "sable$physicsTick(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/api/physics/force/ForceTotal;applyImpulseAtPoint(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)V", remap = false),
            cancellable = true, remap = false, require = 1)
    private void sailboat$unloadedWheel(@Coerce Object body, @Coerce Object handle, double dt, CallbackInfo ci) {
        if (NativeTakeoffAssist.ignoreUnloadedWheelForce((BlockEntity)(Object)this, body,
                extension, queuedForce.y, dt)) ci.cancel();
    }
}
