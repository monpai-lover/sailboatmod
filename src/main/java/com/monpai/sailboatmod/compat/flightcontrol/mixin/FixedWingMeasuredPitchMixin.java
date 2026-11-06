package com.monpai.sailboatmod.compat.flightcontrol.mixin;

import com.monpai.sailboatmod.compat.flightcontrol.NativeFixedWingPitchBootstrap;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalDoubleRef;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Preserves the native measured compensation and final pitch limits only inside an eligible scope. */
@Pseudo
@Mixin(targets = "ace.flight.navigation.FixedWingGuidance", remap = false)
abstract class FixedWingMeasuredPitchMixin {
    @ModifyVariable(method = "flightPathFeedback(Lace/flight/navigation/FixedWingGuidance$Settings;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Lorg/joml/Vector3d;DDDD)Lorg/joml/Vector3d;",
            at = @At(value = "STORE", ordinal = 0), index = 23, remap = false, require = 1, allow = 1)
    private static double sailboat$captureMeasuredPitch(double measured,
            @Share("sailboat$measuredPitch") LocalDoubleRef captured) {
        captured.set(measured);
        return measured;
    }

    @ModifyVariable(method = "flightPathFeedback(Lace/flight/navigation/FixedWingGuidance$Settings;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Lorg/joml/Vector3d;DDDD)Lorg/joml/Vector3d;",
            at = @At(value = "STORE", ordinal = 1), index = 23, remap = false, require = 1, allow = 1)
    private static double sailboat$blendMeasuredPitch(double nativeValue, @Coerce Object settings,
            Vector3d direction, Vector3d forward, Vector3d velocity,
            double actualForwardSpeed, double descentDegrees, double climbLimit, double minimumCompensation,
            @Share("sailboat$measuredPitch") LocalDoubleRef captured) {
        return NativeFixedWingPitchBootstrap.nativeCompensation(settings, nativeValue, captured.get(),
                actualForwardSpeed, minimumCompensation);
    }
}
