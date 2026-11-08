package com.monpai.sailboatmod.transport;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GuidedSurfacePathTest {
    @Test void roundsADenseRightAngleAndKeepsBothTerminals() {
        List<Vec3> authored = new ArrayList<>();
        for (int i = 0; i <= 12; i++) authored.add(new Vec3(0.5, 128.05, 12.5 - i));
        for (int i = 1; i <= 12; i++) authored.add(new Vec3(0.5 - i, 128.05, 0.5));
        GuidedSurfacePath path = GuidedSurfacePath.compile(authored, 2.5);
        assertEquals(authored.getFirst(), path.sample(0).position());
        assertEquals(authored.getLast(), path.sample(path.length()).position());
        assertTrue(path.length() < 24);
        assertTrue(path.length() > 23);
        boolean rounded = false;
        for (double d = 0.05; d < path.length() - 0.05; d += 0.05) {
            var previous = path.sample(d - 0.05);
            var next = path.sample(d);
            assertTrue(previous.tangent().dot(next.tangent()) > 0.99);
            if (next.tangent().x < -0.3 && next.tangent().z < -0.3) {
                assertTrue(next.position().x < 0.5);
                assertTrue(next.position().z > 0.5);
                rounded = true;
            }
        }
        assertTrue(rounded);
    }

    @Test void reducesAHighCruiseSpeedBeforeTheRightAngle() {
        var route = GuidedSurfacePath.compile(List.of(
                new Vec3(0, 128.05, 40), new Vec3(0, 128.05, 0), new Vec3(-40, 128.05, 0)), 2.5);
        var before = route.sample(37.6);
        var unbraked = route.sample(37.6 + 12.0 / 20.0);
        assertTrue(before.tangent().dot(unbraked.tangent()) < Math.cos(Math.toRadians(8)));
        assertTrue(route.speedLimit(25) > route.speedLimit(37.6));
        for (double d = 36; d <= 43; d += 0.04) {
            double speed = Math.min(12, route.speedLimit(d));
            assertTrue(speed > 1);
            var ahead = route.sample(d + speed / 20.0);
            assertTrue(route.sample(d).tangent().dot(ahead.tangent()) > Math.cos(Math.toRadians(4.5)),
                    "Excess yaw at distance " + d + " and speed " + speed);
        }
    }

    @Test void projectionIsBoundedToTheSavedPortionAndRetainsGrade() {
        var route = GuidedSurfacePath.compile(List.of(new Vec3(0, 60, 0), new Vec3(10, 64, 0),
                new Vec3(10, 64, 1), new Vec3(0, 60, 1)), 0.2);
        assertEquals(62, route.sample(5).position().y, 1e-8);
        double firstPass = route.nearestDistance(new Vec3(4, 63, 1), 0, 10);
        assertEquals(4, firstPass, 1e-8);
        assertTrue(route.nearestDistance(new Vec3(4, 63, 1), 11, route.length()) > 15);
    }
}
