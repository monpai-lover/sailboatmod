package com.monpai.sailboatmod.compat.flightcontrol.mixin;

import com.monpai.sailboatmod.compat.flightcontrol.NativeCruiseTrimSearch;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only the offline cruise probe; roll authority and live actuator allocation retain native behavior. */
@Pseudo
@Mixin(targets = "ace.flight.physics.FixedWingCruise", remap = false)
abstract class FixedWingCruiseTrimMixin {
    @Redirect(method = "lambda$solve$0(Lace/flight/physics/FixedWingLevelTrim;D)Lace/flight/physics/FixedWingCruise$Point;",
            at = @At(value = "INVOKE", target = "Lace/flight/physics/FixedWingLevelTrim;controls(DLorg/joml/Vector3d;)[D", remap = false),
            remap = false, require = 1)
    private static double[] sailboat$refineCruiseTrim(@Coerce Object trim, double speed, Vector3d torque) {
        return NativeCruiseTrimSearch.controls(trim, speed, torque);
    }
}
