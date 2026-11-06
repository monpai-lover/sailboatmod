package com.monpai.sailboatmod.compat.flightcontrol;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual optional-mod route factory without requiring Flight Control at test runtime. */
class FlightControlRouteConstructionTest {
    @TempDir static Path fixture;
    static ClassLoader loader;

    @BeforeAll static void optionalProfileApi() throws Exception {
        Path api = fixture.resolve("ace/flight/navigation");
        Path minecraft = fixture.resolve("net/minecraft/resources");
        Files.createDirectories(api);
        Files.createDirectories(minecraft);
        // Only a compilation signature; the parent loader supplies the real Minecraft class at runtime.
        Path location = minecraft.resolve("ResourceLocation.java");
        Files.writeString(location, "package net.minecraft.resources; public class ResourceLocation {}");
        Path definition = api.resolve("NavigationProfileDefinition.java");
        Files.writeString(definition, """
                package ace.flight.navigation;
                public class NavigationProfileDefinition {
                    public interface FlightPreset {}
                    public enum DirectFlightPreset implements FlightPreset { INSTANCE }
                    public record SmoothDirectFlightPreset() implements FlightPreset {}
                    public enum AttitudeControl { CUSTOM, FOLLOW_FLIGHT_TERMINAL }
                }
                """);
        Path draft = api.resolve("NavigationProfileDraft.java");
        Files.writeString(draft, """
                package ace.flight.navigation;
                import java.util.*;
                import net.minecraft.resources.ResourceLocation;
                import ace.flight.navigation.NavigationProfileDefinition.*;
                public record NavigationProfileDraft(String name, List<PathDraft> paths, EndBehavior endBehavior) {
                    public enum EndBehavior { RELEASE_CONTROL, HOLD_LAST_TARGET, HOVER, APPROACH_LANDING }
                    public record AxisDraft(String mode, double value) {
                        public static AxisDraft fixedAngle(double v) { return new AxisDraft("fixed_angle", v); }
                        public static AxisDraft velocityDirection() { return new AxisDraft("velocity_direction", 0); }
                    }
                    public record PathDraft(double x, double y, double z, AxisDraft pitch, AxisDraft roll,
                            AxisDraft yaw, int linearVelocityThrottle, int angularVelocityThrottle,
                            Optional<?> xReference, Optional<?> yReference, Optional<?> zReference,
                            FlightPreset flightPreset, Optional<?> offsetFrame, AttitudeControl attitudeControl) {}
                    public record WaypointRoute(String name, List<PathDraft> paths, EndBehavior endBehavior,
                            String dimension) {}
                    public WaypointRoute toWaypointRoute(ResourceLocation dimension) {
                        return new WaypointRoute(name, List.copyOf(paths), endBehavior, dimension.toString());
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", fixture.toString(),
                location.toString(), definition.toString(), draft.toString()));
        loader = new URLClassLoader(new java.net.URL[]{fixture.toUri().toURL()},
                FlightControlRouteConstructionTest.class.getClassLoader());
    }

    @Test void fixedWingCruiseUsesNativeSupportedDirectPathsAndFlightTerminalAttitude() throws Exception {
        verify(Airframe.FIXED_WING, EndBehavior.RELEASE_CONTROL, 3, "FOLLOW_FLIGHT_TERMINAL");
    }

    @Test void fixedWingLandingUsesDirectForApproachAsWellAsTouchdownAndStop() throws Exception {
        verify(Airframe.FIXED_WING, EndBehavior.APPROACH_LANDING, 4, "FOLLOW_FLIGHT_TERMINAL");
    }

    @Test void minimalTwoPointLandingRetainsNativeEndBehavior() throws Exception {
        verify(Airframe.FIXED_WING, EndBehavior.APPROACH_LANDING, 2, "FOLLOW_FLIGHT_TERMINAL");
    }

    @Test void rotorcraftKeepsCustomAttitudeAndDirectHoverRoute() throws Exception {
        verify(Airframe.ROTORCRAFT, EndBehavior.HOVER, 3, "CUSTOM");
    }

    @Test void airshipKeepsCustomAttitudeAndDirectReleaseRoute() throws Exception {
        verify(Airframe.AIRSHIP, EndBehavior.RELEASE_CONTROL, 3, "CUSTOM");
    }

    private void verify(Airframe airframe, EndBehavior end, int count, String attitude) throws Exception {
        List<Waypoint> points = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new Waypoint(10 + i * 31, 90 - i * 3, -7 - i * 17)).toList();
        RoutePlan plan = new RoutePlan("native-route", points, airframe, end, 11, 7);
        Object route = FlightControlReflection.buildRoute(plan, ResourceLocation.parse("minecraft:overworld"), loader);
        assertEquals(plan.name(), read(route, "name"));
        assertEquals(end.name(), String.valueOf(read(route, "endBehavior")));
        assertEquals("minecraft:overworld", read(route, "dimension"));
        List<?> paths = (List<?>) read(route, "paths");
        assertEquals(count, paths.size());
        for (int i = 0; i < paths.size(); i++) {
            Object path = paths.get(i);
            assertEquals("DirectFlightPreset", read(path, "flightPreset").getClass().getSimpleName(),
                    "Flight Control 0.8.3 rejects SmoothDirect when fixed-wing guidance is enabled: path " + i);
            assertEquals(attitude, String.valueOf(read(path, "attitudeControl")));
            assertEquals(points.get(i).x(), read(path, "x"));
            assertEquals(points.get(i).y(), read(path, "y"));
            assertEquals(points.get(i).z(), read(path, "z"));
            assertEquals(11, read(path, "linearVelocityThrottle"));
            assertEquals(7, read(path, "angularVelocityThrottle"));
            assertEquals("fixed_angle", read(read(path, "pitch"), "mode"));
            assertEquals("fixed_angle", read(read(path, "roll"), "mode"));
            assertEquals("velocity_direction", read(read(path, "yaw"), "mode"));
        }
    }

    private static Object read(Object value, String method) throws Exception {
        return value.getClass().getMethod(method).invoke(value);
    }
}
