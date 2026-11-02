package com.monpai.sailboatmod.route.water.local;

import net.minecraft.world.phys.Vec3;
import java.util.function.BiPredicate;

/** Last horizontal-motion gate, after heading/inertia integration and before Entity.move.
 * A clear pursuit target does not certify motion while the ship is still turning toward it.
 * Does not teleport, alter vertical buoyancy, disable collision, or modify another entity.
 */
public final class BoatMotionGate {
    private BoatMotionGate() { }
    public static Vec3 constrain(Vec3 position, Vec3 requested, double interfaceY,
                                 BiPredicate<Vec3, Vec3> clear) {
        if (!finite(position) || !finite(requested) || !Double.isFinite(interfaceY)) return Vec3.ZERO;
        if (requested.horizontalDistanceSqr() < 1e-16) return requested;
        Vec3 from = new Vec3(position.x, interfaceY, position.z);
        Vec3 to = from.add(requested.x, 0, requested.z);
        return clear.test(from, to) ? requested : new Vec3(0, requested.y, 0);
    }
    private static boolean finite(Vec3 p) {
        return p != null && Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z);
    }
}
