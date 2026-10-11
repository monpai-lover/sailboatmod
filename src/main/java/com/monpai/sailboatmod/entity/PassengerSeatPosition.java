package com.monpai.sailboatmod.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/** Preserves the 1.20.1 rider positions while exposing a 1.21.1 attachment anchor. */
final class PassengerSeatPosition {
    private PassengerSeatPosition() {}

    static Vec3 anchor(Entity passenger, Entity vehicle, Vec3 legacySeatPosition, double vehicleRidingOffset) {
        return anchor(legacySeatPosition, passenger.getVehicleAttachmentPoint(vehicle),
                legacyRidingOffset(passenger, vehicle, vehicleRidingOffset));
    }

    static Vec3 anchor(Vec3 legacySeatPosition, Vec3 attachment, double legacyRidingOffset) {
        return legacySeatPosition.add(attachment).add(0.0D, legacyRidingOffset, 0.0D);
    }

    // These are the vanilla 1.20.1 getMyRidingOffset implementations. Subclass checks
    // precede their parents so baby piglins and the boat-specific shulker offset survive.
    private static double legacyRidingOffset(Entity passenger, Entity vehicle, double vehicleRidingOffset) {
        if (passenger instanceof Player) return -0.35D;
        if (passenger instanceof Shulker) return vehicle instanceof Boat ? 0.1875D - vehicleRidingOffset : 0.0D;
        if (passenger instanceof ZombifiedPiglin piglin) return piglin.isBaby() ? -0.05D : -0.45D;
        if (passenger instanceof AbstractPiglin piglin) return piglin.isBaby() ? -0.05D : -0.45D;
        if (passenger instanceof Zombie zombie) return zombie.isBaby() ? 0.0D : -0.45D;
        if (passenger instanceof AbstractSkeleton) return -0.6D;
        if (passenger instanceof PatrollingMonster) return -0.45D;
        if (passenger instanceof ArmorStand stand) return stand.isMarker() ? 0.0D : (double) 0.1F;
        if (passenger instanceof Animal) return 0.14D;
        if (passenger instanceof Allay || passenger instanceof Vex) return 0.4D;
        if (passenger instanceof Endermite || passenger instanceof Silverfish) return 0.1D;
        return 0.0D;
    }
}
