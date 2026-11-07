package com.monpai.sailboatmod.compat.flightcontrol.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.monpai.sailboatmod.compat.flightcontrol.NativeFixedWingPitchBootstrap;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Only the synchronous update of an existing native task may establish a measured-pitch scope. */
@Pseudo
@Mixin(targets = "ace.flight.block.NavigationTerminalBlockEntity", remap = false)
abstract class NativePitchBootstrapScopeMixin {
    @WrapOperation(method = "runRouteTask(Lace/flight/block/NavigationTerminalBlockEntity$ExecutionContext;)V",
            at = @At(value = "INVOKE", target = "Lace/flight/navigation/NavigationRouteFollower;updateFromComputer(Lace/flight/navigation/NavigationVehicleStateSnapshot;Lace/flight/navigation/NavigationComputer;I)Lace/flight/navigation/NavigationRouteFollower$Output;", remap = false),
            remap = false, require = 1, allow = 1)
    @Coerce
    private Object sailboat$scopeMeasuredPitch(@Coerce Object follower, @Coerce Object vehicle,
            @Coerce Object computer, int velocityController, Operation<Object> original) {
        var scope = NativeFixedWingPitchBootstrap.enter((BlockEntity) (Object) this, follower, vehicle);
        try {
            return original.call(follower, vehicle, computer, velocityController);
        } finally {
            scope.close();
        }
    }
}
