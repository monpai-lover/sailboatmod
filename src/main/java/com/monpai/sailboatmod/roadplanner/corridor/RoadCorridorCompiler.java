package com.monpai.sailboatmod.roadplanner.corridor;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.structure.RoadCenterlinePoint;
import com.monpai.sailboatmod.roadplanner.structure.RoadSpan;
import com.monpai.sailboatmod.roadplanner.structure.RoadSpanType;
import com.monpai.sailboatmod.roadplanner.structure.RoadTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a stretch of {@link RoadAlignment} into blocks.
 *
 * <p>The road is one continuous ribbon. Every column within reach of the centreline is assigned to
 * its nearest point on that line and takes its height from there, so a bend, a ramp and a bridge
 * deck are all the same surface and cannot tear where they meet. What differs along the way is only
 * what holds the ribbon up: fill and dressed verges on the ground, a deck on piers where the ground
 * falls away or there is water.</p>
 *
 * <p>Each column is owned by the position {@code u} of its nearest centreline point. A caller that
 * builds a road in windows passes the half-open {@code u} range it owns; because neighbouring
 * windows compute identical geometry where they overlap, every column is emitted exactly once.</p>
 */
public final class RoadCorridorCompiler {
    /** Free height kept above the running surface. */
    static final int HEADROOM = 5;
    /** Width of the band beside the carriageway that carries parapets. Wide enough to stay edge-connected on diagonals. */
    static final double PARAPET_BAND = 1.42D;
    /** How far beside the carriageway the ground is graded to meet the road. */
    static final double VERGE = 3.5D;
    static final int PIER_SPACING = 16;
    /**
     * Height of the road above the water where boats pass beneath. The deck is two blocks deep and
     * its underside steps down one more toward each pier, leaving five clear blocks over the middle
     * ten of every span and six at its crown.
     */
    static final double CHANNEL_CLEARANCE = 8.0D;
    static final int LIGHT_SPACING = 24;
    /** A deck replaces fill once the road stands this far above the ground. */
    static final double DECK_GAP = 3.0D;
    /** On a leg the planner marked as a bridge, a smaller gap is enough. */
    static final double BRIDGE_DECK_GAP = 1.5D;
    /** Ground this far above the headroom is dug out to the sky; anything deeper is left as a tunnel roof. */
    private static final int OPEN_CUT_LIMIT = 6;
    private static final int MAX_FILL_DEPTH = 8;
    private static final int MAX_VERGE_FILL = 6;
    private static final int MAX_PIER_DEPTH = 96;
    /** Furthest a planned bridge or tunnel is trusted to leave the ground; guards against a bad node height. */
    private static final double MAX_SPAN_HEIGHT = 48.0D;
    private static final double UNKNOWN_WATER_DEPTH = 6.0D;
    /** Stations beyond an owned range that must be computed for the owned part to be exact. */
    public static final int CONTEXT = RoadCorridorProfile.REACH + PIER_SPACING / 2 + 2;

    private RoadCorridorCompiler() {
    }

    public record Result(List<RoadCenterlinePoint> centerline, List<RoadSpan> spans, List<BuildStep> steps) {
    }

    /** Compiles an entire alignment. */
    public static Result compile(RoadAlignment alignment, RoadPlannerBuildSettings settings, RoadTerrainSampler terrain) {
        return compile(alignment, alignment.startDistance(), alignment.endDistance(),
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, settings, terrain);
    }

    /**
     * @param windowFrom first position to compute; should lie {@link #CONTEXT} before {@code ownFrom}
     * @param windowTo   last position to compute; should lie {@link #CONTEXT} after {@code ownTo}
     * @param ownFrom    columns whose position is at or after this are emitted
     * @param ownTo      columns whose position is before this are emitted
     */
    public static Result compile(RoadAlignment alignment, double windowFrom, double windowTo,
                                 double ownFrom, double ownTo,
                                 RoadPlannerBuildSettings settings, RoadTerrainSampler terrain) {
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        Stations stations = Stations.sample(alignment, windowFrom, windowTo, safeSettings.width() / 2.0D, terrain);
        if (stations.count < 2) {
            return new Result(List.of(), List.of(), List.of());
        }
        return new Emitter(stations, safeSettings, terrain, ownFrom, ownTo).emit();
    }

    /** Geometry and design heights at one-block intervals along the alignment. */
    private static final class Stations {
        final int count;
        final double[] u;
        final double[] x;
        final double[] z;
        final double[] tangentX;
        final double[] tangentZ;
        final RoadPlannerSegmentType[] type;
        final int[] leg;
        final double[] ground;
        final boolean[] wet;
        final double[] bed;
        final double[] height;
        final boolean[] elevated;
        final boolean[] pier;
        final boolean routeStart;
        final boolean routeEnd;

        private Stations(int count, boolean routeStart, boolean routeEnd) {
            this.count = count;
            this.routeStart = routeStart;
            this.routeEnd = routeEnd;
            u = new double[count];
            x = new double[count];
            z = new double[count];
            tangentX = new double[count];
            tangentZ = new double[count];
            type = new RoadPlannerSegmentType[count];
            leg = new int[count];
            ground = new double[count];
            wet = new boolean[count];
            bed = new double[count];
            height = new double[count];
            elevated = new boolean[count];
            pier = new boolean[count];
        }

        static Stations sample(RoadAlignment alignment, double windowFrom, double windowTo,
                               double halfWidth, RoadTerrainSampler terrain) {
            double start = alignment.startDistance();
            double end = alignment.endDistance();
            long first = (long) Math.ceil(Math.max(windowFrom, start) - 1.0E-9D);
            long last = (long) Math.floor(Math.min(windowTo, end) + 1.0E-9D);
            boolean reachesEnd = alignment.endsRoute() && windowTo >= end;
            boolean tail = reachesEnd && end - last > 1.0E-6D;
            int whole = (int) Math.max(0, last - first + 1);
            int count = whole + (tail ? 1 : 0);
            Stations s = new Stations(count, alignment.startsRoute() && first <= start + 1.0E-9D, reachesEnd);
            double[] point = new double[4];
            double[] virtual = new double[count];
            double[] minimum = new double[count];
            double[] water = new double[count];
            boolean[] marked = new boolean[count];
            double side = Math.max(1.0D, halfWidth * 0.7D);
            for (int i = 0; i < count; i++) {
                double at = i < whole ? first + i : end;
                alignment.evaluate(Math.max(start, Math.min(end, at)), point);
                s.u[i] = at;
                s.x[i] = point[0];
                s.z[i] = point[1];
                s.tangentX[i] = point[2];
                s.tangentZ[i] = point[3];
                s.type[i] = alignment.typeAt(at);
                s.leg[i] = alignment.routeLegAt(at);
                minimum[i] = Double.NEGATIVE_INFINITY;
                boolean bridge = isBridge(s.type[i]);
                marked[i] = s.type[i] == RoadPlannerSegmentType.BRIDGE_MAJOR
                        || s.type[i] == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE;
                double nodeHeight = alignment.nodeHeightAt(at);
                if (terrain == null) {
                    // With no terrain to read, the plan is the only evidence: the ground is taken to
                    // lie at the node heights, and a leg planned as a bridge is taken to cross water.
                    s.ground[i] = nodeHeight;
                    s.wet[i] = bridge;
                    s.bed[i] = bridge ? nodeHeight - UNKNOWN_WATER_DEPTH : nodeHeight;
                    minimum[i] = bridge ? nodeHeight + 1.0D : Double.NEGATIVE_INFINITY;
                    virtual[i] = bridge ? nodeHeight + waterClearance(s.type[i]) : nodeHeight;
                    water[i] = nodeHeight;
                    continue;
                }
                int cx = (int) Math.round(point[0]);
                int cz = (int) Math.round(point[1]);
                int lx = (int) Math.round(point[0] - point[3] * side);
                int lz = (int) Math.round(point[1] + point[2] * side);
                int rx = (int) Math.round(point[0] + point[3] * side);
                int rz = (int) Math.round(point[1] - point[2] * side);
                int centre = terrain.terrainY(cx, cz);
                int left = terrain.terrainY(lx, lz);
                int right = terrain.terrainY(rx, rz);
                s.wet[i] = terrain.isWater(cx, centre - 1, cz);
                if (s.wet[i]) {
                    s.ground[i] = centre;
                    water[i] = centre;
                    s.bed[i] = Math.min(centre, terrain.oceanFloorY(cx, cz));
                    minimum[i] = centre + 1.0D;
                } else {
                    s.ground[i] = median(centre, left, right);
                    s.bed[i] = s.ground[i];
                    if (terrain.isWater(lx, left - 1, lz)) {
                        minimum[i] = left + 0.5D;
                    }
                    if (terrain.isWater(rx, right - 1, rz)) {
                        minimum[i] = Math.max(minimum[i], right + 0.5D);
                    }
                }
                double natural = s.wet[i] ? centre + waterClearance(s.type[i]) : s.ground[i];
                if (s.type[i] == RoadPlannerSegmentType.TUNNEL) {
                    // A tunnel holds the line between its portals whatever the hill above does. A node
                    // height far below the ground is not a portal but a missing height, and is ignored.
                    virtual[i] = nodeHeight >= s.ground[i] - MAX_SPAN_HEIGHT ? Math.min(nodeHeight, s.ground[i]) : s.ground[i];
                } else if (bridge) {
                    // A planned bridge flies straight between its end nodes, so a valley too wide
                    // to be spanned automatically is still crossed level rather than descended into.
                    virtual[i] = Math.max(natural, Math.min(nodeHeight, s.ground[i] + MAX_SPAN_HEIGHT));
                } else {
                    virtual[i] = natural;
                }
            }
            double[] spacing = new double[Math.max(0, count - 1)];
            for (int i = 0; i < count - 1; i++) {
                spacing[i] = Math.hypot(s.x[i + 1] - s.x[i], s.z[i + 1] - s.z[i]);
            }
            boolean[] channel = RoadCorridorProfile.channel(s.wet, marked);
            double[] cleared = new double[count];
            for (int i = 0; i < count; i++) {
                cleared[i] = channel[i] ? water[i] + CHANNEL_CLEARANCE : Double.NEGATIVE_INFINITY;
            }
            double[] designed = RoadCorridorProfile.design(virtual, spacing, minimum, cleared);
            boolean[] raised = new boolean[count];
            for (int i = 0; i < count; i++) {
                s.height[i] = designed[i];
                double gap = isBridge(s.type[i]) ? BRIDGE_DECK_GAP : DECK_GAP;
                raised[i] = s.type[i] != RoadPlannerSegmentType.TUNNEL && designed[i] - s.ground[i] >= gap;
            }
            boolean[] elevated = RoadCorridorProfile.elevated(raised, s.wet);
            for (int i = 0; i < count; i++) {
                s.elevated[i] = elevated[i];
                s.pier[i] = elevated[i] && i < whole && Math.floorMod((long) s.u[i], PIER_SPACING) == 0
                        && designed[i] - s.bed[i] >= 4.0D;
            }
            return s;
        }

        private static double median(int a, int b, int c) {
            return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
        }

        /** Index of the station at or before {@code at}, never the last one. */
        int floorIndex(double at) {
            int index = (int) Math.floor(at - u[0]);
            return Math.max(0, Math.min(count - 2, index));
        }
    }

    /**
     * How far above the water a crossing is set before any shipping channel is considered. Narrow
     * water is crossed low; height for boats is added separately, and only where the water is wide.
     */
    private static double waterClearance(RoadPlannerSegmentType type) {
        return type == RoadPlannerSegmentType.ROAD || type == RoadPlannerSegmentType.TUNNEL ? 2.0D : 3.0D;
    }

    private static boolean isBridge(RoadPlannerSegmentType type) {
        return type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL
                || type == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE;
    }

    /** One terrain column and where it sits relative to the centreline. */
    private record Column(int x, int z, double at, double distance) {
    }

    private static final class Emitter {
        private final Stations stations;
        private final RoadCorridorMaterials materials;
        private final RoadTerrainSampler terrain;
        private final double ownFrom;
        private final double ownTo;
        private final double halfWidth;
        private final boolean lights;
        private final List<BuildStep> steps = new ArrayList<>();

        Emitter(Stations stations, RoadPlannerBuildSettings settings, RoadTerrainSampler terrain,
                double ownFrom, double ownTo) {
            this.stations = stations;
            this.materials = RoadCorridorMaterials.of(settings);
            this.terrain = terrain;
            this.ownFrom = ownFrom;
            this.ownTo = ownTo;
            this.halfWidth = settings.width() / 2.0D;
            this.lights = settings.streetlightsEnabled();
        }

        Result emit() {
            Map<Long, Column> nearest = nearestColumns();
            Map<Long, Integer> lamps = lights ? lampColumns(nearest) : Map.of();
            List<Column> owned = new ArrayList<>();
            for (Column column : nearest.values()) {
                if (owns(column.at)) {
                    owned.add(column);
                }
            }
            owned.sort((a, b) -> {
                int byPosition = Double.compare(a.at, b.at);
                if (byPosition != 0) {
                    return byPosition;
                }
                return a.x != b.x ? Integer.compare(a.x, b.x) : Integer.compare(a.z, b.z);
            });
            for (Column column : owned) {
                Integer lamp = lamps.get(key(column.x, column.z));
                if (column.distance <= halfWidth + 1.0E-6D) {
                    carriageway(column);
                } else if (lamp == null || !lamp(column)) {
                    roadside(column);
                }
            }
            List<RoadCenterlinePoint> centerline = centerline();
            return new Result(centerline, spans(centerline), List.copyOf(steps));
        }

        private boolean owns(double at) {
            return at >= ownFrom && at < ownTo;
        }

        /** Assigns every column near the road to its closest point on the centreline. */
        private Map<Long, Column> nearestColumns() {
            Map<Long, Column> nearest = new HashMap<>();
            double reach = halfWidth + VERGE;
            int last = stations.count - 1;
            for (int i = 0; i < last; i++) {
                double ax = stations.x[i];
                double az = stations.z[i];
                double dx = stations.x[i + 1] - ax;
                double dz = stations.z[i + 1] - az;
                double lengthSq = dx * dx + dz * dz;
                int minX = (int) Math.floor(Math.min(ax, ax + dx) - reach);
                int maxX = (int) Math.ceil(Math.max(ax, ax + dx) + reach);
                int minZ = (int) Math.floor(Math.min(az, az + dz) - reach);
                int maxZ = (int) Math.ceil(Math.max(az, az + dz) + reach);
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (beyondRouteEnd(x, z, 0, -1.0D) || beyondRouteEnd(x, z, last, 1.0D)) {
                            continue;
                        }
                        double raw = lengthSq < 1.0E-12D ? 0.0D : ((x - ax) * dx + (z - az) * dz) / lengthSq;
                        double t = Math.max(0.0D, Math.min(1.0D, raw));
                        double ex = x - (ax + dx * t);
                        double ez = z - (az + dz * t);
                        double distance = Math.sqrt(ex * ex + ez * ez);
                        if (distance > reach) {
                            continue;
                        }
                        long key = key(x, z);
                        Column existing = nearest.get(key);
                        if (existing == null || distance < existing.distance - 1.0E-9D) {
                            double at = stations.u[i] + (stations.u[i + 1] - stations.u[i]) * t;
                            nearest.put(key, new Column(x, z, at, distance));
                        }
                    }
                }
            }
            return nearest;
        }

        /** A road ends square: nothing is laid behind its first point or past its last. */
        private boolean beyondRouteEnd(int x, int z, int station, double outward) {
            if (station == 0 ? !stations.routeStart : !stations.routeEnd) {
                return false;
            }
            double dx = x - stations.x[station];
            double dz = z - stations.z[station];
            double reach = halfWidth + VERGE + 1.0D;
            if (dx * dx + dz * dz > reach * reach) {
                return false;
            }
            return (dx * stations.tangentX[station] + dz * stations.tangentZ[station]) * outward > 1.0E-9D;
        }

        /**
         * Lamp posts stand beside the road at fixed positions along it, alternating sides, with one
         * more at each end of the route so that even a short road is lit.
         */
        private Map<Long, Integer> lampColumns(Map<Long, Column> nearest) {
            Map<Long, Integer> lamps = new HashMap<>();
            int last = stations.count - 1;
            for (int i = 0; i <= last; i++) {
                if (stations.type[i] == RoadPlannerSegmentType.TUNNEL) {
                    continue;
                }
                long at = (long) Math.floor(stations.u[i]);
                boolean routeEnd = (i == 0 && stations.routeStart) || (i == last && stations.routeEnd);
                boolean regular = at == stations.u[i] && Math.floorMod(at, LIGHT_SPACING) == LIGHT_SPACING / 2;
                if (!routeEnd && !regular) {
                    continue;
                }
                int side = Math.floorMod(Math.floorDiv(at, LIGHT_SPACING), 2) == 0 ? 1 : -1;
                for (double offset = halfWidth + 1.0D; offset <= halfWidth + 2.5D; offset += 0.5D) {
                    int x = (int) Math.round(stations.x[i] - stations.tangentZ[i] * side * offset);
                    int z = (int) Math.round(stations.z[i] + stations.tangentX[i] * side * offset);
                    Column column = nearest.get(key(x, z));
                    if (column != null && column.distance > halfWidth + 0.5D) {
                        lamps.putIfAbsent(key(x, z), i);
                        break;
                    }
                }
            }
            return lamps;
        }

        /** Surface height at a column, in half blocks. */
        private int surfaceHalves(Column column) {
            int index = stations.floorIndex(column.at);
            double span = stations.u[index + 1] - stations.u[index];
            double t = span <= 0 ? 0.0D : Math.max(0.0D, Math.min(1.0D, (column.at - stations.u[index]) / span));
            double height = stations.height[index] + (stations.height[index + 1] - stations.height[index]) * t;
            return (int) Math.round(height * 2.0D);
        }

        private int stationOf(Column column) {
            int index = stations.floorIndex(column.at);
            double middle = (stations.u[index] + stations.u[index + 1]) / 2.0D;
            return column.at < middle ? index : index + 1;
        }

        /** The block that holds the running surface: a full block, or a bottom slab for a half step. */
        private static int surfaceCell(int halves) {
            return Math.floorDiv(halves - 1, 2);
        }

        private int groundTop(Column column, int station) {
            return terrain == null ? (int) Math.round(stations.ground[station]) : terrain.terrainY(column.x, column.z);
        }

        private boolean isWet(Column column, int groundTop) {
            return terrain != null && terrain.isWater(column.x, groundTop - 1, column.z);
        }

        private void carriageway(Column column) {
            int halves = surfaceHalves(column);
            boolean half = (halves & 1) != 0;
            int surface = surfaceCell(halves);
            int station = stationOf(column);
            int groundTop = groundTop(column, station);
            boolean wet = isWet(column, groundTop);
            int bed = wet ? Math.min(groundTop, terrain.oceanFloorY(column.x, column.z)) : groundTop;
            clear(column, surface + 1, clearanceTop(surface, groundTop, station));
            if (stations.elevated[station]) {
                support(column, surface - 2, bed);
                place(column, surface - 1, materials.structure(), BuildPhase.PIER);
                place(column, surface, half ? materials.slab() : materials.surface(), BuildPhase.DECK);
                return;
            }
            int lowest = Math.max(bed, surface - MAX_FILL_DEPTH);
            for (int y = lowest; y < surface; y++) {
                boolean underSlab = half && y == surface - 1;
                place(column, y, underSlab ? materials.surface() : (wet ? materials.wetFill() : materials.fill()),
                        BuildPhase.FOUNDATION);
            }
            place(column, surface, half ? materials.slab() : materials.surface(),
                    half ? BuildPhase.RAMP : BuildPhase.SURFACE);
        }

        /** Highest block to remove above a surface: the headroom, or up to the sky where the cover is thin. */
        private int clearanceTop(int surface, int groundTop, int station) {
            int headroom = surface + HEADROOM;
            boolean thinCover = groundTop - 1 > headroom && groundTop - 1 - headroom <= OPEN_CUT_LIMIT;
            return thinCover && stations.type[station] != RoadPlannerSegmentType.TUNNEL ? groundTop - 1 : headroom;
        }

        /**
         * Piers stand at fixed positions along the road. Beside each one the underside of the deck
         * steps down toward it, so a row of piers reads as a run of arches from any direction.
         */
        private void support(Column column, int top, int bed) {
            if (column.distance > Math.max(0.5D, halfWidth - 1.0D) + 1.0E-6D) {
                return;
            }
            long nearestPier = Math.round(column.at / PIER_SPACING) * PIER_SPACING;
            int index = (int) (nearestPier - (long) stations.u[0]);
            if (index < 0 || index >= stations.count || stations.u[index] != nearestPier || !stations.pier[index]) {
                return;
            }
            double along = Math.abs(column.at - nearestPier);
            int depth = along <= 1.0D ? MAX_PIER_DEPTH : along <= 2.0D ? 3 : along <= 3.0D ? 2 : along <= 4.5D ? 1 : 0;
            int lowest = Math.max(bed, top - depth + 1);
            for (int y = lowest; y <= top; y++) {
                place(column, y, materials.pier(), BuildPhase.PIER);
            }
        }

        private void roadside(Column column) {
            int halves = surfaceHalves(column);
            int surface = surfaceCell(halves);
            int station = stationOf(column);
            double beyond = column.distance - halfWidth;
            if (stations.elevated[station]) {
                if (beyond <= PARAPET_BAND) {
                    place(column, surface - 1, materials.structure(), BuildPhase.RAILING);
                    place(column, surface, materials.structure(), BuildPhase.RAILING);
                    place(column, surface + 1, materials.railing(), BuildPhase.RAILING);
                    clear(column, surface + 2, surface + HEADROOM);
                }
                return;
            }
            if (stations.type[station] == RoadPlannerSegmentType.TUNNEL) {
                return;
            }
            int groundTop = groundTop(column, station);
            if (isWet(column, groundTop)) {
                return;
            }
            double roadTop = halves / 2.0D;
            double run = Math.max(0.0D, beyond - 0.5D);
            int bank = (int) Math.floor(roadTop - run + 1.0E-9D);
            int cut = (int) Math.ceil(roadTop + run - 1.0E-9D);
            BlockState cover = cover(column);
            if (bank > groundTop && bank - groundTop <= MAX_VERGE_FILL) {
                for (int y = groundTop; y < bank; y++) {
                    place(column, y, y == bank - 1 ? cover : materials.fill(), BuildPhase.FOUNDATION);
                }
                groundTop = bank;
            } else if (groundTop > cut) {
                clear(column, cut, Math.min(groundTop - 1, clearanceTop(surface, groundTop, station)));
                place(column, cut - 1, cover, BuildPhase.FOUNDATION);
                groundTop = cut;
            }
            if (beyond <= 1.0D) {
                clear(column, groundTop, surface + HEADROOM);
            }
        }

        private BlockState cover(Column column) {
            BlockState state = terrain == null ? null : terrain.groundCover(column.x, column.z);
            return RoadCorridorMaterials.isNaturalGround(state) ? state : Blocks.GRASS_BLOCK.defaultBlockState();
        }

        /** Returns false when a lamp cannot stand here, so the column is finished as ordinary roadside. */
        private boolean lamp(Column column) {
            int halves = surfaceHalves(column);
            int surface = surfaceCell(halves);
            int station = stationOf(column);
            if (stations.elevated[station]) {
                place(column, surface - 1, materials.structure(), BuildPhase.RAILING);
            } else {
                int groundTop = groundTop(column, station);
                if (isWet(column, groundTop) || surface - groundTop > MAX_VERGE_FILL) {
                    return false;
                }
                for (int y = groundTop; y < surface; y++) {
                    place(column, y, materials.fill(), BuildPhase.FOUNDATION);
                }
            }
            place(column, surface, materials.structure(), BuildPhase.STREETLIGHT);
            for (int y = surface + 1; y <= surface + 3; y++) {
                place(column, y, materials.railing(), BuildPhase.STREETLIGHT);
            }
            place(column, surface + 4, Blocks.LANTERN.defaultBlockState(), BuildPhase.STREETLIGHT);
            clear(column, surface + 5, surface + HEADROOM);
            return true;
        }

        private void clear(Column column, int fromY, int toY) {
            for (int y = toY; y >= fromY; y--) {
                place(column, y, Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION);
            }
        }

        private void place(Column column, int y, BlockState state, BuildPhase phase) {
            steps.add(new BuildStep(steps.size(), new BlockPos(column.x, y, column.z), state, phase));
        }

        private List<RoadCenterlinePoint> centerline() {
            List<RoadCenterlinePoint> points = new ArrayList<>();
            for (int i = 0; i < stations.count; i++) {
                if (!owns(stations.u[i])) {
                    continue;
                }
                int x = (int) Math.round(stations.x[i]);
                int z = (int) Math.round(stations.z[i]);
                if (!points.isEmpty()) {
                    BlockPos previous = points.get(points.size() - 1).pos();
                    if (previous.getX() == x && previous.getZ() == z) {
                        continue;
                    }
                }
                int top = Math.floorDiv((int) Math.round(stations.height[i] * 2.0D) + 1, 2);
                RoadPlannerSegmentType type = stations.elevated[i] && !isBridge(stations.type[i])
                        ? RoadPlannerSegmentType.BRIDGE_SMALL : stations.type[i];
                if (!stations.elevated[i] && isBridge(type)) {
                    type = RoadPlannerSegmentType.ROAD;
                }
                points.add(new RoadCenterlinePoint(new BlockPos(x, top, z), stations.leg[i], type,
                        (int) Math.round(stations.ground[i]), top, Math.max(0.0D, stations.u[i])));
            }
            return List.copyOf(points);
        }

        private static List<RoadSpan> spans(List<RoadCenterlinePoint> centerline) {
            List<RoadSpan> spans = new ArrayList<>();
            int start = 0;
            for (int i = 1; i <= centerline.size(); i++) {
                if (i == centerline.size() || spanType(centerline.get(i)) != spanType(centerline.get(start))) {
                    spans.add(new RoadSpan(spanType(centerline.get(start)), start, i - 1, centerline.get(start).segmentType()));
                    start = i;
                }
            }
            return List.copyOf(spans);
        }

        private static RoadSpanType spanType(RoadCenterlinePoint point) {
            if (isBridge(point.segmentType())) {
                return RoadSpanType.BRIDGE;
            }
            return point.segmentType() == RoadPlannerSegmentType.TUNNEL ? RoadSpanType.TUNNEL : RoadSpanType.ROAD;
        }

        private static long key(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
    }
}
