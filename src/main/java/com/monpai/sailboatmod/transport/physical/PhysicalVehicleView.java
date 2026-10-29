package com.monpai.sailboatmod.transport.physical;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Immutable server snapshot in world coordinates. Velocity is blocks/second; mass is in native Sable units.
 * Physical identity is separate from the controller's business registration. Unavailable views cannot be dispatched.
 */
public record PhysicalVehicleView(UUID physicalId, String dimensionId, Vec3 worldPosition,
                                  Vec3 worldForward, Vec3 linearVelocity, AABB worldBounds,
                                  double mass, boolean available) {
}
