package com.monpai.sailboatmod.compat.aeronautics;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Read-only topology coverage. It never creates, removes or modifies a native constraint. */
final class RailAircraftConstraintTracker {
    @FunctionalInterface interface Validity { boolean valid(Object handle) throws ReflectiveOperationException; }
    private record Link(WeakReference<Object> first, WeakReference<Object> second,
                        boolean firstWorld, boolean secondWorld, Object handle) {
        boolean involves(Object body) { return first.get() == body || second.get() == body; }
    }
    private static final class Partition {
        final WeakReference<Object> pipeline;
        final List<Link> links = new ArrayList<>();
        boolean overflow;
        Partition(Object pipeline) { this.pipeline = new WeakReference<>(pipeline); }
    }
    private final List<Partition> pipelines = new ArrayList<>();
    private final int maxPipelines, maxLinks;
    private boolean overflow;

    RailAircraftConstraintTracker() { this(32, 8192); }
    RailAircraftConstraintTracker(int maxPipelines, int maxLinks) {
        if (maxPipelines < 1 || maxLinks < 1) throw new IllegalArgumentException("invalid tracker capacity");
        this.maxPipelines = maxPipelines; this.maxLinks = maxLinks;
    }

    synchronized void add(Object pipeline, Object first, Object second, Object handle) {
        if (pipeline == null || handle == null) return; // A failed native creation has no connection.
        Partition p = find(pipeline);
        if (p == null) {
            if (pipelines.size() >= maxPipelines) { overflow = true; return; }
            p = new Partition(pipeline); pipelines.add(p);
        }
        for (Link link : p.links) if (link.handle == handle) return;
        if (p.links.size() >= maxLinks) { p.overflow = true; return; }
        // Inspected Rapier handles contain native numeric IDs/cache only; they do not retain a world/pipeline.
        p.links.add(new Link(new WeakReference<>(first), new WeakReference<>(second), first == null, second == null, handle));
    }

    synchronized String reason(Object pipeline, Object body, Validity validity) {
        if (overflow) return "air_rail_constraint_tracker_overflow";
        Partition p = find(pipeline);
        if (p == null) return "";
        if (p.overflow) return "air_rail_constraint_tracker_overflow";
        String rejected = "";
        for (var iterator = p.links.iterator(); iterator.hasNext();) {
            Link link = iterator.next();
            boolean relevant = body != null && link.involves(body);
            try {
                if (!validity.valid(link.handle)) { iterator.remove(); continue; }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                if (relevant) rejected = "air_rail_constraint_state_unavailable";
                continue;
            }
            if (relevant && rejected.isEmpty()) rejected = link.firstWorld || link.secondWorld
                    ? "air_rail_world_constraint_unsupported" : "air_rail_multi_body_unsupported";
        }
        return rejected;
    }

    synchronized void removed(Object handle) {
        for (Partition p : pipelines) p.links.removeIf(link -> link.handle == handle);
    }
    synchronized void bodyRemoved(Object pipeline, Object body) {
        if (body == null) return;
        Partition p = find(pipeline);
        if (p != null) p.links.removeIf(link -> link.involves(body));
    }
    synchronized void disposed(Object pipeline) {
        pipelines.removeIf(p -> p.pipeline.get() == null || p.pipeline.get() == pipeline);
    }
    synchronized void clear() { pipelines.clear(); overflow = false; }
    synchronized int pipelineCount() { find(null); return pipelines.size(); }
    private Partition find(Object pipeline) {
        pipelines.removeIf(p -> p.pipeline.get() == null);
        for (Partition p : pipelines) if (p.pipeline.get() == pipeline) return p;
        return null;
    }
}
