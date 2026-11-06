package com.monpai.sailboatmod.compat.flightcontrol.mixin;

import com.monpai.sailboatmod.compat.flightcontrol.NativeCruiseBudgetContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Carries the actual physics solver budget into this one asynchronous cruise calculation. */
@Pseudo
@Mixin(targets = "ace.flight.block.FlightControlComputerBlockEntity", remap = false)
abstract class FlightControlCruiseBudgetMixin {
    @Redirect(method = "startCruiseEstimate(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;DLace/flight/block/FlightControlComputerBlockEntity$FixedWingValidation;Ljava/util/List;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", remap = false),
            remap = false, require = 1)
    private <T> CompletableFuture<T> sailboat$inheritCruiseBudget(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(NativeCruiseBudgetContext.wrap(this, supplier));
    }
}
