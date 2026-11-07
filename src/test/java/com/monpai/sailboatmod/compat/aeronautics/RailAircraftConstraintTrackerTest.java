package com.monpai.sailboatmod.compat.aeronautics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailAircraftConstraintTrackerTest {
    static final class Handle { boolean valid = true; }
    final Object pipeline = new Object(), root = new Object(), foreign = new Object();
    final RailAircraftConstraintTracker tracker = new RailAircraftConstraintTracker(2, 2);

    @Test void foreignBodyAndWorldAttachmentsAreRejectedButUnrelatedBodiesAreAllowed() throws Exception {
        Handle h = new Handle();
        tracker.add(pipeline, root, foreign, h);
        assertEquals("air_rail_multi_body_unsupported", tracker.reason(pipeline, root, x -> ((Handle) x).valid));
        assertEquals("", tracker.reason(pipeline, new Object(), x -> true));
        tracker.removed(h);
        tracker.add(pipeline, null, root, new Handle());
        assertEquals("air_rail_world_constraint_unsupported", tracker.reason(pipeline, root, x -> true));
    }

    @Test void RemovedAndNativeInvalidHandlesArePrunedAndNewHandlesAreChecked() throws Exception {
        Handle removed = new Handle();
        tracker.add(pipeline, root, foreign, removed);
        tracker.removed(removed);
        assertEquals("", tracker.reason(pipeline, root, x -> true));
        Handle invalid = new Handle();
        tracker.add(pipeline, root, foreign, invalid);
        invalid.valid = false;
        assertEquals("", tracker.reason(pipeline, root, x -> ((Handle) x).valid));
        tracker.add(pipeline, root, foreign, new Handle());
        assertEquals("air_rail_multi_body_unsupported", tracker.reason(pipeline, root, x -> true));
    }

    @Test void overflowIsStickyAndConservativeUntilTheNativePipelineIsDisposed() throws Exception {
        tracker.add(pipeline, new Object(), new Object(), new Handle());
        tracker.add(pipeline, new Object(), new Object(), new Handle());
        tracker.add(pipeline, new Object(), new Object(), new Handle());
        assertEquals("air_rail_constraint_tracker_overflow", tracker.reason(pipeline, root, x -> false));
        tracker.disposed(pipeline);
        assertEquals("", tracker.reason(pipeline, root, x -> true));
    }

    @Test void failedValidityReadRejectsOnlyTheAffectedBodyAndOtherPipelinesAreIndependent() throws Exception {
        tracker.add(pipeline, root, foreign, new Handle());
        assertEquals("air_rail_constraint_state_unavailable", tracker.reason(pipeline, root, x -> { throw new ReflectiveOperationException(); }));
        assertEquals("", tracker.reason(pipeline, new Object(), x -> { throw new ReflectiveOperationException(); }));
        assertEquals("", tracker.reason(new Object(), root, x -> true));
    }

    @Test void duplicateHandleDoesNotOverflowAndFailedCreationCannotRegisterAConnection() throws Exception {
        Handle handle = new Handle();
        tracker.add(pipeline, root, foreign, handle);
        tracker.add(pipeline, root, foreign, handle);
        tracker.add(pipeline, root, foreign, null);
        tracker.removed(handle);
        assertEquals("", tracker.reason(pipeline, root, x -> true));
    }

    @Test void pipelineDisposalAndServerClearRemoveTheirLifecycleState() {
        Object temporaryPipeline = new Object();
        tracker.add(temporaryPipeline, root, foreign, new Handle());
        assertEquals(1, tracker.pipelineCount());
        tracker.disposed(temporaryPipeline);
        assertEquals(0, tracker.pipelineCount());
        tracker.add(pipeline, root, foreign, new Handle());
        tracker.clear();
        assertEquals(0, tracker.pipelineCount());
    }

    @Test void pipelineCapacityFailureRejectsUnknownCoverageUntilLifecycleClear() throws Exception {
        Object second = new Object(), third = new Object();
        tracker.add(pipeline, root, foreign, new Handle());
        tracker.add(second, root, foreign, new Handle());
        tracker.add(third, root, foreign, new Handle());
        assertEquals("air_rail_constraint_tracker_overflow", tracker.reason(pipeline, root, x -> true));
        tracker.clear();
        assertEquals("", tracker.reason(pipeline, root, x -> true));
    }

    @Test void bodyRemovalClearsItsInvalidNativeEdgesWithoutTouchingAnotherAircraft() throws Exception {
        Object otherRoot = new Object(), otherForeign = new Object();
        tracker.add(pipeline, root, foreign, new Handle());
        tracker.add(pipeline, otherRoot, otherForeign, new Handle());
        tracker.bodyRemoved(pipeline, root);
        assertEquals("", tracker.reason(pipeline, root, x -> true));
        assertEquals("air_rail_multi_body_unsupported", tracker.reason(pipeline, otherRoot, x -> true));
    }
}
