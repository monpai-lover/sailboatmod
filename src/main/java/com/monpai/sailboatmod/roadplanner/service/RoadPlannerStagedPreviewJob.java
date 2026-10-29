package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Exactly one prediction window is queued/running; only the server tick publishes its result. */
public final class RoadPlannerStagedPreviewJob {
    private final RoadPlannerStagedPlan plan;
    private final Function<RoadPlannerStagedPlan.Stage, Supplier<SyncRoadPlannerPreviewPacket>> prepare;
    private final Executor worker;
    private FutureTask<SyncRoadPlannerPreviewPacket> pending;
    private int completedStages;
    private boolean cancelled;

    public RoadPlannerStagedPreviewJob(RoadPlannerStagedPlan plan,
                                      Function<RoadPlannerStagedPlan.Stage, SyncRoadPlannerPreviewPacket> compile,
                                      Executor worker) {
        this(plan, stage -> () -> compile.apply(stage), worker, true);
    }

    private RoadPlannerStagedPreviewJob(RoadPlannerStagedPlan plan,
                                       Function<RoadPlannerStagedPlan.Stage, Supplier<SyncRoadPlannerPreviewPacket>> prepare,
                                       Executor worker, boolean prepared) {
        this.plan = java.util.Objects.requireNonNull(plan);
        this.prepare = java.util.Objects.requireNonNull(prepare);
        this.worker = java.util.Objects.requireNonNull(worker);
    }

    public static RoadPlannerStagedPreviewJob prepared(RoadPlannerStagedPlan plan,
            Function<RoadPlannerStagedPlan.Stage, Supplier<SyncRoadPlannerPreviewPacket>> prepare, Executor worker) {
        return new RoadPlannerStagedPreviewJob(plan, prepare, worker, true);
    }

    public void tick(Consumer<SyncRoadPlannerPreviewPacket> publish) {
        if (cancelled || completedStages >= plan.totalStages()) return;
        if (pending != null) {
            if (!pending.isDone()) return;
            try {
                SyncRoadPlannerPreviewPacket result = pending.get();
                pending = null;
                completedStages++;
                publish.accept(result);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancel();
                return;
            } catch (ExecutionException failed) {
                cancel();
                throw new IllegalStateException("Road terrain window unavailable", failed.getCause());
            } catch (CancellationException stopped) {
                cancel();
                return;
            }
        }
        if (!cancelled && completedStages < plan.totalStages()) {
            RoadPlannerStagedPlan.Stage stage = plan.stage(completedStages);
            Supplier<SyncRoadPlannerPreviewPacket> owned = prepare.apply(stage);
            pending = new FutureTask<>(owned::get);
            try { worker.execute(pending); }
            catch (RuntimeException busy) { cancel(); throw busy; }
        }
    }

    public void cancel() {
        cancelled = true;
        if (pending != null) { pending.cancel(true); pending = null; }
    }

    public int completedStages() { return completedStages; }
    public boolean isDone() { return !cancelled && completedStages >= plan.totalStages(); }
    public boolean isCancelled() { return cancelled; }
}
