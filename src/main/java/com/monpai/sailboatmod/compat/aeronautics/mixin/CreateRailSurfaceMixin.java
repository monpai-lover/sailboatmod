package com.monpai.sailboatmod.compat.aeronautics.mixin;

import com.monpai.sailboatmod.compat.aeronautics.RailAircraftSurfaceAccess;
import com.monpai.sailboatmod.compat.aeronautics.RailAircraftSurfaceBridge;
import com.monpai.sailboatmod.compat.aeronautics.RailAircraftSurfaceServo;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Leaves ordinary bearing ticks and attached contraption transforms intact. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity", remap = false)
public abstract class CreateRailSurfaceMixin implements RailAircraftSurfaceAccess {
    @Shadow protected float angle;
    @Unique private final RailAircraftSurfaceServo sailboat$railSurface = new RailAircraftSurfaceServo();
    @Override public RailAircraftSurfaceServo sailboat$surfaceServo() { return sailboat$railSurface; }
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void sailboat$surfaceTick(CallbackInfo ci) {
        RailAircraftSurfaceBridge.onNativeTick((BlockEntity) (Object) this, sailboat$railSurface, angle);
    }
    @Inject(method = "getAngularSpeed()F", at = @At("RETURN"), cancellable = true)
    private void sailboat$finiteSurfaceStep(CallbackInfoReturnable<Float> result) {
        if (sailboat$railSurface.view(angle).active()) result.setReturnValue((float) sailboat$railSurface.step(angle));
    }
    @Inject(method = "write(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V", at = @At("TAIL"))
    private void sailboat$writeSurface(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket, CallbackInfo ci) {
        sailboat$railSurface.write(tag);
    }
    @Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V", at = @At("TAIL"))
    private void sailboat$readSurface(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        sailboat$railSurface.read(tag, clientPacket || self.getLevel() != null && self.getLevel().isClientSide, angle);
    }
}
