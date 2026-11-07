package com.monpai.sailboatmod.compat.flightcontrol.mixin;

import com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutral;
import com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutralHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Source-only optional compatibility candidate; no hard native class is referenced in the handler ABI. */
@Pseudo
@Mixin(targets = "ace.flight.block.FlightControlComputerBlockEntity", remap = false)
abstract class FlightControlGroundNeutralMixin {
    @Shadow private void stopControlOutput(boolean sync) { throw new AssertionError("shadow"); }
    @Shadow private void clearLinkedSwivelMountLocks(double dt) { throw new AssertionError("shadow"); }
    @Shadow private void commandLinkedFlapBearingsNeutral(boolean active) { throw new AssertionError("shadow"); }

    @Inject(method = "cfc$physicsTickAllocation(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V",
            at = @At(value = "INVOKE", target = "Lace/flight/block/FlightControlComputerBlockEntity;computeCompositeBodyState(Ljava/lang/Object;)Lace/flight/physics/FlightPhysicsController$BodyStateOverride;", remap = false),
            cancellable = true, remap = false, require = 1)
    private void sailboat$groundNeutral(@Coerce Object body, @Coerce Object handle, double dt, CallbackInfo ci) {
        if (NativeGroundNeutralHook.neutralize(this, body, dt, new NativeGroundNeutral.NativeActions() {
            public void stopControlOutput(boolean sync) { FlightControlGroundNeutralMixin.this.stopControlOutput(sync); }
            public void clearLinkedSwivelMountLocks(double step) { FlightControlGroundNeutralMixin.this.clearLinkedSwivelMountLocks(step); }
            public void commandLinkedFlapBearingsNeutral(boolean active) { FlightControlGroundNeutralMixin.this.commandLinkedFlapBearingsNeutral(active); }
        })) ci.cancel();
    }
}
