package com.monpai.sailboatmod.compat.flightcontrol.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.monpai.sailboatmod.compat.flightcontrol.NativeFixedWingTrimEnvironment;
import com.monpai.sailboatmod.compat.flightcontrol.TrimEnvironmentCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = TrimEnvironmentCompatibility.COMPUTER, remap = false)
abstract class TrimEnvironmentScopeMixin {
    @WrapOperation(method = TrimEnvironmentCompatibility.SCOPE_METHOD,
            at = @At(value = "INVOKE", target = TrimEnvironmentCompatibility.SCOPE_TARGET, remap = false),
            remap = false, require = 1, allow = 1)
    private void sailboat$trimEnvironmentScope(@Coerce Object computer, @Coerce Object body,
            @Coerce Object handle, double dt, Operation<Void> original) {
        var scope = NativeFixedWingTrimEnvironment.enter(computer, body, handle, dt);
        try { original.call(computer, body, handle, dt); }
        finally { scope.close(); }
    }
}
