package com.example.examplemod.integration.bluemap;

import com.example.examplemod.block.entity.DockBlockEntity;
import com.example.examplemod.dock.DockRegistry;
import com.example.examplemod.entity.SailboatEntity;
import com.example.examplemod.route.RouteDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BlueMapIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SYNC_INTERVAL_TICKS = 40L;
    private static final String DOCKS_SET_ID = "sailboatmod_docks";
    private static final String BOATS_SET_ID = "sailboatmod_boats";
    private static final String ROUTES_SET_ID = "sailboatmod_routes";
    private static final AABB ENTITY_SCAN_BOX = new AABB(-30_000_000D, -2_048D, -30_000_000D, 30_000_000D, 2_048D, 30_000_000D);

    private static volatile ReflectionApi api;
    private static volatile boolean initAttempted;
    private static volatile boolean disabled;
    private static long lastSyncTick = Long.MIN_VALUE;

    public static void onServerStarted(MinecraftServer server) {
        lastSyncTick = Long.MIN_VALUE;
        sync(server);
    }

    public static void onServerTick(MinecraftServer server) {
        if (disabled) {
            return;
        }
        long tick = server.getTickCount();
        if (tick == lastSyncTick || tick % SYNC_INTERVAL_TICKS != 0L) {
            return;
        }
        lastSyncTick = tick;
        sync(server);
    }

    public static void onServerStopped() {
        lastSyncTick = Long.MIN_VALUE;
    }

    private static void sync(MinecraftServer server) {
        ReflectionApi reflection = reflectionApi();
        if (reflection == null) {
            return;
        }
        try {
            for (ServerLevel level : server.getAllLevels()) {
                reflection.syncLevel(level);
            }
        } catch (Throwable t) {
            disabled = true;
            LOGGER.error("Disabling BlueMap integration after sync failure", t);
        }
    }

    private static ReflectionApi reflectionApi() {
        if (disabled) {
            return null;
        }
        if (!initAttempted) {
            synchronized (BlueMapIntegration.class) {
                if (!initAttempted) {
                    initAttempted = true;
                    try {
                        api = new ReflectionApi();
                    } catch (ClassNotFoundException e) {
                        return null;
                    } catch (Throwable t) {
                        disabled = true;
                        LOGGER.error("Failed to bootstrap BlueMap integration", t);
                    }
                }
            }
        }
        return api;
    }

    private static final class ReflectionApi {
        private final Method blueMapGetInstanceMethod;
        private final Method optionalIsPresentMethod;
        private final Method optionalGetMethod;
        private final Method blueMapGetWorldMethod;
        private final Method blueMapWorldGetMapsMethod;
        private final Method blueMapMapGetMarkerSetsMethod;
        private final Constructor<?> markerSetConstructor;
        private final Method markerSetSetToggleableMethod;
        private final Method markerSetSetDefaultHiddenMethod;
        private final Method markerSetSetSortingMethod;
        private final Method markerSetGetMarkersMethod;
        private final Constructor<?> vector3dConstructor;
        private final Constructor<?> lineConstructor;
        private final Constructor<?> colorConstructor;
        private final Constructor<?> poiMarkerConstructor;
        private final Method poiMarkerSetDetailMethod;
        private final Method markerSetListedMethod;
        private final Constructor<?> lineMarkerConstructor;
        private final Method lineMarkerSetDetailMethod;
        private final Method lineMarkerSetLineColorMethod;
        private final Method lineMarkerSetLineWidthMethod;
        private final Method lineMarkerSetDepthTestEnabledMethod;

        private ReflectionApi() throws Exception {
            Class<?> blueMapApiClass = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI");
            Class<?> blueMapWorldClass = Class.forName("de.bluecolored.bluemap.api.BlueMapWorld");
            Class<?> blueMapMapClass = Class.forName("de.bluecolored.bluemap.api.BlueMapMap");
            Class<?> markerSetClass = Class.forName("de.bluecolored.bluemap.api.markers.MarkerSet");
            Class<?> markerClass = Class.forName("de.bluecolored.bluemap.api.markers.Marker");
            Class<?> poiMarkerClass = Class.forName("de.bluecolored.bluemap.api.markers.POIMarker");
            Class<?> lineMarkerClass = Class.forName("de.bluecolored.bluemap.api.markers.LineMarker");
            Class<?> vector3dClass = Class.forName("com.flowpowered.math.vector.Vector3d");
            Class<?> lineClass = Class.forName("de.bluecolored.bluemap.api.math.Line");
            Class<?> colorClass = Class.forName("de.bluecolored.bluemap.api.math.Color");

            this.blueMapGetInstanceMethod = blueMapApiClass.getMethod("getInstance");
            this.optionalIsPresentMethod = Optional.class.getMethod("isPresent");
            this.optionalGetMethod = Optional.class.getMethod("get");
            this.blueMapGetWorldMethod = blueMapApiClass.getMethod("getWorld", Object.class);
            this.blueMapWorldGetMapsMethod = blueMapWorldClass.getMethod("getMaps");
            this.blueMapMapGetMarkerSetsMethod = blueMapMapClass.getMethod("getMarkerSets");
            this.markerSetConstructor = markerSetClass.getConstructor(String.class);
            this.markerSetSetToggleableMethod = markerSetClass.getMethod("setToggleable", boolean.class);
            this.markerSetSetDefaultHiddenMethod = markerSetClass.getMethod("setDefaultHidden", boolean.class);
            this.markerSetSetSortingMethod = markerSetClass.getMethod("setSorting", int.class);
            this.markerSetGetMarkersMethod = markerSetClass.getMethod("getMarkers");
            this.vector3dConstructor = vector3dClass.getConstructor(double.class, double.class, double.class);
            this.lineConstructor = lineClass.getConstructor(Collection.class);
            this.colorConstructor = colorClass.getConstructor(int.class, float.class);
            this.poiMarkerConstructor = poiMarkerClass.getConstructor(String.class, vector3dClass);
            this.poiMarkerSetDetailMethod = poiMarkerClass.getMethod("setDetail", String.class);
            this.markerSetListedMethod = markerClass.getMethod("setListed", boolean.class);
            this.lineMarkerConstructor = lineMarkerClass.getConstructor(String.class, lineClass);
            this.lineMarkerSetDetailMethod = lineMarkerClass.getMethod("setDetail", String.class);
            this.lineMarkerSetLineColorMethod = lineMarkerClass.getMethod("setLineColor", colorClass);
            this.lineMarkerSetLineWidthMethod = lineMarkerClass.getMethod("setLineWidth", int.class);
            this.lineMarkerSetDepthTestEnabledMethod = lineMarkerClass.getMethod("setDepthTestEnabled", boolean.class);
        }

        private void syncLevel(ServerLevel level) throws Exception {
            Object blueMapApi = resolveBlueMapApi();
            if (blueMapApi == null) {
                return;
            }
            Object blueMapWorld = resolveBlueMapWorld(blueMapApi, level);
            if (blueMapWorld == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            Collection<Object> maps = (Collection<Object>) this.blueMapWorldGetMapsMethod.invoke(blueMapWorld);
            for (Object map : maps) {
                @SuppressWarnings("unchecked")
                Map<String, Object> markerSets = (Map<String, Object>) this.blueMapMapGetMarkerSetsMethod.invoke(map);
                Object docksSet = prepareMarkerSet(markerSets, DOCKS_SET_ID, "Sailboat Docks", 10);
                Object boatsSet = prepareMarkerSet(markerSets, BOATS_SET_ID, "Sailboats", 11);
                Object routesSet = prepareMarkerSet(markerSets, ROUTES_SET_ID, "Sailboat Routes", 12);

                clearMarkerSet(docksSet);
                clearMarkerSet(boatsSet);
                clearMarkerSet(routesSet);

                renderDocks(level, docksSet);
                renderBoats(level, boatsSet);
                renderRoutes(level, routesSet);
            }
        }

        private Object resolveBlueMapApi() throws Exception {
            Object optional = this.blueMapGetInstanceMethod.invoke(null);
            if (!(boolean) this.optionalIsPresentMethod.invoke(optional)) {
                return null;
            }
            return this.optionalGetMethod.invoke(optional);
        }

        private Object resolveBlueMapWorld(Object blueMapApi, ServerLevel level) throws Exception {
            Object optional = this.blueMapGetWorldMethod.invoke(blueMapApi, level);
            if (!(boolean) this.optionalIsPresentMethod.invoke(optional)) {
                return null;
            }
            return this.optionalGetMethod.invoke(optional);
        }

        private Object prepareMarkerSet(Map<String, Object> markerSets, String id, String label, int sorting) throws Exception {
            Object markerSet = markerSets.get(id);
            if (markerSet == null) {
                markerSet = this.markerSetConstructor.newInstance(label);
                markerSets.put(id, markerSet);
            }
            this.markerSetSetToggleableMethod.invoke(markerSet, true);
            this.markerSetSetDefaultHiddenMethod.invoke(markerSet, false);
            this.markerSetSetSortingMethod.invoke(markerSet, sorting);
            return markerSet;
        }

        private void clearMarkerSet(Object markerSet) throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> markers = (Map<String, Object>) this.markerSetGetMarkersMethod.invoke(markerSet);
            markers.clear();
        }

        private void renderDocks(ServerLevel level, Object markerSet) throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> markers = (Map<String, Object>) this.markerSetGetMarkersMethod.invoke(markerSet);
            for (BlockPos pos : DockRegistry.get(level)) {
                if (!(level.getBlockEntity(pos) instanceof DockBlockEntity dock)) {
                    continue;
                }
                Object marker = buildPoiMarker(
                        dock.getDockName(),
                        dock.getBlockPos().getX() + 0.5D,
                        dock.getBlockPos().getY() + 1.0D,
                        dock.getBlockPos().getZ() + 0.5D,
                        """
                        <div class="sailboatmod-popup">
                        <strong>%s</strong><br>
                        Owner: %s<br>
                        Routes: %d<br>
                        Coords: %d, %d, %d
                        </div>
                        """.formatted(
                                escapeHtml(dock.getDockName()),
                                escapeHtml(dock.getOwnerName()),
                                dock.getRouteCount(),
                                pos.getX(), pos.getY(), pos.getZ()
                        )
                );
                markers.put("dock-" + pos.asLong(), marker);
            }
        }

        private void renderBoats(ServerLevel level, Object markerSet) throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> markers = (Map<String, Object>) this.markerSetGetMarkersMethod.invoke(markerSet);
            for (SailboatEntity boat : level.getEntitiesOfClass(SailboatEntity.class, ENTITY_SCAN_BOX, SailboatEntity::isAlive)) {
                Object marker = buildPoiMarker(
                        boat.getName().getString(),
                        boat.getX(),
                        boat.getY() + 1.0D,
                        boat.getZ(),
                        """
                        <div class="sailboatmod-popup">
                        <strong>%s</strong><br>
                        Owner: %s<br>
                        State: %s<br>
                        Route: %s<br>
                        Speed: %.2f b/s<br>
                        Cargo: %s<br>
                        Rent: %s<br>
                        Coords: %.1f, %.1f, %.1f
                        </div>
                        """.formatted(
                                escapeHtml(boat.getName().getString()),
                                escapeHtml(boat.getOwnerName()),
                                escapeHtml(autopilotState(boat)),
                                escapeHtml(activeRouteName(boat)),
                                boat.getDeltaMovement().horizontalDistance() * 20.0D,
                                boat.hasCargo() ? "loaded" : "empty",
                                boat.isAvailableForRent() ? Integer.toString(boat.getRentalPrice()) : "off",
                                boat.getX(), boat.getY(), boat.getZ()
                        )
                );
                markers.put("boat-" + boat.getUUID(), marker);
            }
        }

        private void renderRoutes(ServerLevel level, Object markerSet) throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> markers = (Map<String, Object>) this.markerSetGetMarkersMethod.invoke(markerSet);
            Set<String> seen = new HashSet<>();
            int fallbackIndex = 1;
            for (BlockPos pos : DockRegistry.get(level)) {
                if (!(level.getBlockEntity(pos) instanceof DockBlockEntity dock)) {
                    continue;
                }
                for (RouteDefinition route : dock.getRoutesForMap()) {
                    if (route.waypoints().size() < 2) {
                        continue;
                    }
                    String signature = routeSignature(route);
                    if (!seen.add(signature)) {
                        continue;
                    }
                    String routeName = routeDisplayName(route, fallbackIndex++);
                    Object marker = buildLineMarker(
                            routeName,
                            route.waypoints(),
                            """
                            <div class="sailboatmod-popup">
                            <strong>%s</strong><br>
                            Start: %s<br>
                            End: %s<br>
                            Waypoints: %d<br>
                            Length: %.0f m<br>
                            Author: %s
                            </div>
                            """.formatted(
                                    escapeHtml(routeName),
                                    escapeHtml(blankToDash(route.startDockName())),
                                    escapeHtml(blankToDash(route.endDockName())),
                                    route.waypoints().size(),
                                    route.routeLengthMeters(),
                                    escapeHtml(blankToDash(route.authorName()))
                            ),
                            routeColor(signature)
                    );
                    markers.put("route-" + Integer.toHexString(signature.hashCode()), marker);
                }
            }
        }

        private Object buildPoiMarker(String label, double x, double y, double z, String detail) throws Exception {
            Object marker = this.poiMarkerConstructor.newInstance(label, vector3d(x, y, z));
            this.poiMarkerSetDetailMethod.invoke(marker, detail);
            this.markerSetListedMethod.invoke(marker, true);
            return marker;
        }

        private Object buildLineMarker(String label, List<Vec3> waypoints, String detail, int colorRgb) throws Exception {
            List<Object> points = new ArrayList<>(waypoints.size());
            for (Vec3 waypoint : waypoints) {
                points.add(vector3d(waypoint.x, waypoint.y, waypoint.z));
            }
            Object line = this.lineConstructor.newInstance(points);
            Object marker = this.lineMarkerConstructor.newInstance(label, line);
            this.lineMarkerSetDetailMethod.invoke(marker, detail);
            this.lineMarkerSetLineColorMethod.invoke(marker, this.colorConstructor.newInstance(colorRgb, 0.9F));
            this.lineMarkerSetLineWidthMethod.invoke(marker, 3);
            this.lineMarkerSetDepthTestEnabledMethod.invoke(marker, false);
            this.markerSetListedMethod.invoke(marker, true);
            return marker;
        }

        private Object vector3d(double x, double y, double z) throws Exception {
            return this.vector3dConstructor.newInstance(x, y, z);
        }
    }

    private static String activeRouteName(SailboatEntity boat) {
        String routeName = boat.isAutopilotActive() ? boat.getAutopilotRouteName() : boat.getSelectedRouteName();
        return routeName == null || routeName.isBlank() ? "-" : routeName;
    }

    private static String autopilotState(SailboatEntity boat) {
        if (!boat.isAutopilotActive()) {
            return "idle";
        }
        if (boat.isAutopilotPaused()) {
            return "paused";
        }
        return "running";
    }

    private static String routeDisplayName(RouteDefinition route, int fallbackIndex) {
        if (route.name() != null && !route.name().isBlank()) {
            return route.name();
        }
        return "Route-" + fallbackIndex;
    }

    private static String routeSignature(RouteDefinition route) {
        StringBuilder builder = new StringBuilder();
        builder.append(route.name()).append('|')
                .append(route.startDockName()).append('|')
                .append(route.endDockName()).append('|');
        for (Vec3 waypoint : route.waypoints()) {
            builder.append(Math.round(waypoint.x * 100.0D)).append(',')
                    .append(Math.round(waypoint.y * 100.0D)).append(',')
                    .append(Math.round(waypoint.z * 100.0D)).append(';');
        }
        return builder.toString();
    }

    private static int routeColor(String signature) {
        float hue = ((signature.hashCode() & Integer.MAX_VALUE) % 360) / 360.0F;
        return java.awt.Color.HSBtoRGB(hue, 0.7F, 0.95F) & 0x00FFFFFF;
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private BlueMapIntegration() {
    }
}
