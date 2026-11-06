package com.monpai.sailboatmod.compat.flightcontrol.mixin;

import com.monpai.sailboatmod.compat.flightcontrol.NativeFixedWingTrimEnvironment;
import com.monpai.sailboatmod.compat.flightcontrol.TrimEnvironmentCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Pseudo
@Mixin(targets = TrimEnvironmentCompatibility.COMPUTER, remap = false)
abstract class TrimEnvironmentSampleMixin {
    @ModifyArgs(method = TrimEnvironmentCompatibility.ALLOCATION,
            at = @At(value = "INVOKE", target = TrimEnvironmentCompatibility.SAMPLE_TARGET, remap = false),
            remap = false, require = 1, allow = 1)
    private void sailboat$supplyObservedEnvironment(Args args) {
        args.set(3, NativeFixedWingTrimEnvironment.supplement(this, args.get(0),
                args.<Long>get(1), args.<Long>get(2), args.get(3), args.<Long>get(4)));
    }
}
