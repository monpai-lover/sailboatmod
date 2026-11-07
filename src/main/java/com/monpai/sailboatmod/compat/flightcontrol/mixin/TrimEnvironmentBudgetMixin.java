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
abstract class TrimEnvironmentBudgetMixin {
    @WrapOperation(method = TrimEnvironmentCompatibility.ALLOCATION,
            at = @At(value = "INVOKE", target = TrimEnvironmentCompatibility.BUDGET_TARGET, remap = false),
            remap = false, require = 1, allow = 1)
    @Coerce
    private Object sailboat$captureAcceptedBudget(@Coerce Object coupler, Operation<Object> original) {
        Object budget = original.call(coupler);
        NativeFixedWingTrimEnvironment.captureBudget(this, budget);
        return budget;
    }
}
