package com.monpai.sailboatmod.compat.flightcontrol.mixin;

import com.monpai.sailboatmod.compat.flightcontrol.FlightTaskRecoveryHooks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Observes only the native automatic reconciliation, never the public requestAction entry. */
@Pseudo
@Mixin(targets = "ace.flight.block.NavigationTerminalBlockEntity", remap = false)
abstract class NavigationTerminalRecoveryMixin {
    @Shadow(remap = false) private boolean requestResume() { throw new AssertionError("Mixin shadow"); }
    @Redirect(method = "reconcileAutomaticEnableState()V", at = @At(value = "INVOKE",
            target = "Lace/flight/block/NavigationTerminalBlockEntity;requestResume()Z", remap = false),
            remap = false, require = 1)
    private boolean sailboat$observeAutomaticResume(@Coerce Object receiver) {
        var terminal = (BlockEntity)(Object)this;
        var attempt = FlightTaskRecoveryHooks.before(terminal);
        boolean accepted = requestResume();
        if (accepted) FlightTaskRecoveryHooks.after(terminal, attempt);
        return accepted;
    }
}
