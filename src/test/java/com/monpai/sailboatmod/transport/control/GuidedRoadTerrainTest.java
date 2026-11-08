package com.monpai.sailboatmod.transport.control;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GuidedRoadTerrainTest {
    private static final AABB BODY_SWEEP = new AABB(0.1, 127.7, 0.1, 3.9, 131.0, 2.9);
    private static final AABB STONE = new AABB(0, 0, 0, 1, 1, 1);
    private static final Vec3 CENTERLINE = new Vec3(2, 128.05, 1.5);

    @Test void keepsGroundContactButRejectsMissingSweptSupport() {
        assertEquals("", check(p -> p.getY() == 127));
        assertEquals("guided_road_unsupported", check(p -> p.getY() == 127 && p.getX() != 3));
    }

    @Test void stopsForBlocksAboveThePermittedRoadDeck() {
        assertEquals("obstacle", check(p -> p.getY() == 127 || p.equals(new BlockPos(2, 128, 1))));
    }

    @Test void actualTiresMayBeSupportedWhileTheBodyEnvelopeOverhangsTheCorner() {
        AABB corner = new AABB(135323.3254, 128.9776, -28745.3534, 135332.1598, 131.9883, -28736.5190);
        Vec3 point = new Vec3(135325.2778, 128.05, -28739.5489);
        var tires = List.of(tire(135325.0084, -28738.5859), tire(135326.3553, -28743.4010),
                tire(135330.2074, -28742.3235), tire(135328.8605, -28737.5083));
        Predicate<BlockPos> originalDeck = p -> p.getY() == 127 && !(p.getX() == 135332 && p.getZ() <= -28743);
        assertEquals("guided_road_unsupported", corner(corner, point, List.of(), originalDeck));
        assertEquals("", corner(corner, point, tires, originalDeck));

        Predicate<BlockPos> wheelDrop = p -> originalDeck.test(p) && !(p.getX() == 135326 && p.getZ() == -28744);
        assertEquals("guided_road_unsupported", corner(corner, point, tires, wheelDrop));
        Predicate<BlockPos> overhangObstacle = p -> originalDeck.test(p) || p.equals(new BlockPos(135332, 129, -28744));
        assertEquals("obstacle", corner(corner, point, tires, overhangObstacle));
    }

    private static AABB tire(double x, double z) {
        return new AABB(x - 0.22, 129.48, z - 0.22, x + 0.22, 129.48, z + 0.22);
    }

    private static String corner(AABB sweep, Vec3 path, List<AABB> tires, Predicate<BlockPos> solid) {
        return new GuidedRoadTerrain().scan(sweep, path, new Vec3(1, 0, 0), tires,
                p -> true, p -> solid.test(p) ? List.of(STONE) : List.of(), solid);
    }

    private static String check(Predicate<BlockPos> solid) {
        return new GuidedRoadTerrain().scan(BODY_SWEEP, CENTERLINE, new Vec3(1, 0, 0),
                p -> true, p -> solid.test(p) ? List.of(STONE) : List.of(), solid);
    }
}
