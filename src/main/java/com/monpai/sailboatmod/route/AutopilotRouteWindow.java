package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.route.water.lookahead.RouteSplicer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Complete persistent route with a bounded overlapping window for near-field validation and repair. */
public final class AutopilotRouteWindow {
    public static final int WINDOW_POINTS = 256;
    public record State(List<Vec3> route, int targetIndex) {
        public State { route = List.copyOf(route); }
    }
    public record Window(int offset, List<Vec3> points, int targetIndex) {
        public Window { points = List.copyOf(points); }
    }
    private AutopilotRouteWindow() { }
    public static List<Vec3> complete(List<Vec3> points) { return points == null ? List.of() : List.copyOf(points); }

    public static Window around(List<Vec3> route, int targetIndex) {
        if (route == null || route.isEmpty()) return new Window(0, List.of(), 0);
        int target = clampTarget(targetIndex, route.size());
        int stride = WINDOW_POINTS / 2;
        int offset = Math.max(0, target - 1) / stride * stride;
        int end = Math.min(route.size(), offset + WINDOW_POINTS);
        return new Window(offset, route.subList(offset, end), target - offset);
    }

    public static State apply(List<Vec3> route, Window window, RouteSplicer.Spliced local) {
        int end = window.offset() + window.points().size();
        if (window.offset() < 0 || end > route.size() || window.points().isEmpty()
                || !route.subList(window.offset(), end).equals(window.points())) throw new IllegalStateException("Stale autopilot route window");
        if (local == null || local.route().size() < 2 || local.route().size() > 512
                || local.targetIndex() < 0 || local.targetIndex() >= local.route().size()
                || !local.route().getFirst().equals(window.points().getFirst())
                || !local.route().getLast().equals(window.points().getLast())) throw new IllegalStateException("Invalid local autopilot route patch");
        List<Vec3> full = new ArrayList<>(route.size() - window.points().size() + local.route().size());
        full.addAll(route.subList(0, window.offset()));
        full.addAll(local.route());
        full.addAll(route.subList(end, route.size()));
        return new State(full, window.offset() + local.targetIndex());
    }

    public static void save(CompoundTag tag, List<Vec3> route, int targetIndex) {
        ListTag points = new ListTag();
        for (Vec3 point : route) {
            CompoundTag entry = new CompoundTag();
            entry.putDouble("X", point.x); entry.putDouble("Y", point.y); entry.putDouble("Z", point.z);
            points.add(entry);
        }
        tag.put("AutopilotRoute", points);
        tag.putInt("AutopilotTargetIndex", clampTarget(targetIndex, route.size()));
    }

    public static State load(CompoundTag tag) {
        List<Vec3> points = new ArrayList<>();
        for (Tag entry : tag.getList("AutopilotRoute", Tag.TAG_COMPOUND)) {
            if (entry instanceof CompoundTag point) points.add(new Vec3(point.getDouble("X"), point.getDouble("Y"), point.getDouble("Z")));
        }
        return new State(points, clampTarget(tag.getInt("AutopilotTargetIndex"), points.size()));
    }

    private static int clampTarget(int target, int points) { return Math.max(0, Math.min(target, Math.max(0, points - 1))); }
}
