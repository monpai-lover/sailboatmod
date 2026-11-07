package com.monpai.sailboatmod.client.airport;

import com.monpai.sailboatmod.network.packet.airport.AirportActionPacket;
import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AirportPlannerModelTest {
    @Test void mapEditorRetainsVerticalFacilityTypesWhenSavingAndReloading() {
        for (var type : List.of(AirportFacility.Type.HELIPAD, AirportFacility.Type.MOORING)) {
            var tool = AirportPlannerModel.Tool.valueOf(type.name());
            assertFalse(new AirportPlannerModel(snapshot(5)).startNew(tool));
            var source = new AirportScreenData(Level.OVERWORLD.location(), AIRPORT, "Airport", "Town", "Owner", true,
                    5, List.of(), AirportGroundLayout.empty().withRevision(5),
                    List.of("feature|route_publication|1"), "");
            var model = new AirportPlannerModel(source);
            assertTrue(model.startNew(tool));
            model.click(new Vec3(20.5, 81, 30.5));
            model.click(new Vec3(32.5, 81, 30.5));
            var packet = model.savePacket();
            var facility = AirportFacility.load(packet.toActionTag().getCompound("Facility"));
            assertEquals(type, facility.type());
            model.markSubmitted(packet);
            model.applySnapshot(new AirportScreenData(Level.OVERWORLD.location(), AIRPORT, "Airport", "Town", "Owner", true,
                    6, List.of(facility), AirportGroundLayout.empty().withRevision(6), source.statuses(), "ok"));
            assertFalse(model.dirty());
            assertTrue(model.selectFacility(facility.id()));
            assertEquals(tool, model.draft().orElseThrow().kind());
        }
    }

    @Test void plannerUsesTheSame2048RunwayLengthLimitAsServerGeometry() {
        var model = new AirportPlannerModel(snapshot(5));
        model.startNew(AirportPlannerModel.Tool.RUNWAY);
        model.click(new Vec3(0.5, 81, 0.5));
        model.click(new Vec3(2048.5, 81, 0.5));
        assertTrue(model.canSave());
        assertEquals(2048, AirportFacility.load(model.savePacket().toActionTag().getCompound("Facility")).length());
        model.movePoint(1, new Vec3(2049.5, 81, 0.5));
        assertFalse(model.canSave());
    }

    private static final BlockPos AIRPORT = new BlockPos(20, 81, 30);
    private static final AirportFacility RUNWAY = new AirportFacility("r", AirportFacility.Type.RUNWAY,
            new BlockPos(-32,81,5), new BlockPos(96,81,5), 12, 16);
    private static final AirportParkingStand STAND = new AirportParkingStand("s", new Vec3(-60.25,81,5.75),90,24,20,12,true);
    private static AirportScreenData snapshot(long revision, String result, List<AirportFacility> runways,
                                               List<AirportParkingStand> stands, List<AirportTaxiRoute> routes) {
        return new AirportScreenData(Level.OVERWORLD.location(),AIRPORT,"Airport","Town","Owner",true,revision,
                runways,new AirportGroundLayout(revision,stands,routes),List.of(),result);
    }
    private static AirportScreenData snapshot(long revision) { return snapshot(revision,"",List.of(RUNWAY),List.of(STAND),List.of()); }
    private static AirportPlannerModel runwayDraft() {
        var model = new AirportPlannerModel(snapshot(5));
        assertTrue(model.startNew(AirportPlannerModel.Tool.RUNWAY));
        model.click(new Vec3(-10.9,0,2.9)); model.click(new Vec3(100.9,0,2.2));
        return model;
    }
    @Test void twoMapClicksCreateCenteredRunwayButPacketKeepsLowerBlockCorners() {
        var model = new AirportPlannerModel(snapshot(5)); model.startNew(AirportPlannerModel.Tool.RUNWAY);
        assertFalse(model.canSave()); model.click(new Vec3(-10.9,13,2.9)); assertFalse(model.canSave());
        model.click(new Vec3(100.9,13,2.2)); assertTrue(model.canSave());
        assertEquals(List.of(new Vec3(-10.5,81,2.5),new Vec3(100.5,81,2.5)),model.draft().orElseThrow().points());
        var packet=model.savePacket(); assertEquals(List.of(new Vec3(-11,81,2),new Vec3(100,81,2)),packet.points());
        assertEquals(Level.OVERWORLD.location(),packet.dimension()); assertEquals(AIRPORT,packet.airportPos()); assertEquals(5,packet.revision());
        assertEquals(new BlockPos(-11,81,2),AirportFacility.load(packet.toActionTag().getCompound("Facility")).start());
        model.click(new Vec3(200,81,2)); assertEquals(2,model.draft().orElseThrow().points().size());
    }
    @Test void selectingExistingRunwayRoundTripsWithoutHalfBlockDrift() {
        var model=new AirportPlannerModel(snapshot(5)); assertTrue(model.selectRunway("r"));
        for(int i=0;i<3;i++) {
            assertEquals(new Vec3(-31.5,81,5.5),model.draft().orElseThrow().points().getFirst());
            assertEquals(RUNWAY,AirportFacility.load(model.savePacket().toActionTag().getCompound("Facility")));
            model.discard();
        }
    }
    @Test void runwayReverseAndEndpointDragAreUndoable() {
        var model=new AirportPlannerModel(snapshot(5)); model.selectRunway("r");
        model.reverseRunway(); assertEquals(new Vec3(96,81,5),model.savePacket().points().getFirst());
        assertTrue(model.undo()); assertFalse(model.dirty());
        model.movePoint(0,new Vec3(-50.9,99,5.9)); assertEquals(new Vec3(-51,81,5),model.savePacket().points().getFirst());
    }
    @Test void taxiPolylinePreservesExactCoordinatesAndMovesHoldWithInsertionAndDeletion() {
        var model=new AirportPlannerModel(snapshot(5)); model.startNew(AirportPlannerModel.Tool.OUTBOUND);
        model.click(STAND.position()); model.click(new Vec3(-40.25,81,5.75)); model.click(new Vec3(-31.5,81,5.5));
        model.setHoldPoint(1); model.insertPoint(1,new Vec3(-50.75,81,5.65));
        assertEquals(2,model.draft().orElseThrow().holdPoint());
        model.movePoint(1,new Vec3(-51.125,0,5.875));
        assertEquals(new Vec3(-51.125,81,5.875),model.draft().orElseThrow().points().get(1));
        model.deletePoint(0); assertEquals(1,model.draft().orElseThrow().holdPoint());
        model.deletePoint(1); assertEquals(-1,model.draft().orElseThrow().holdPoint()); assertFalse(model.canSave());
        model.setHoldPoint(0); assertTrue(model.canSave());
        var packet=model.savePacket(); assertFalse(packet.flag()); assertEquals("r",packet.runwayId());assertEquals("s",packet.standId());
        assertEquals(model.draft().orElseThrow().points(),packet.points());
    }
    @Test void incompleteTaxiAndInboundHoldCannotBeSaved() {
        var model=new AirportPlannerModel(snapshot(5));model.startNew(AirportPlannerModel.Tool.INBOUND);
        model.click(new Vec3(-31.5,81,5.5)); assertFalse(model.canSave());
        model.click(STAND.position());assertTrue(model.canSave());assertTrue(model.savePacket().flag());
        assertThrows(IllegalArgumentException.class,()->model.setHoldPoint(0));
        assertEquals(-1,model.draft().orElseThrow().holdPoint());
    }
    @Test void taxiCannotGrowBeyondWirePointLimit() {
        var model=new AirportPlannerModel(snapshot(5));model.startNew(AirportPlannerModel.Tool.INBOUND);
        for(int i=0;i<64;i++) model.click(new Vec3(i,81,0));
        assertThrows(IllegalArgumentException.class,()->model.click(new Vec3(64,81,0)));
        assertEquals(64,model.draft().orElseThrow().points().size());
    }
    @Test void standClickDragAndHeadingUseRealWorldCoordinatesAndMinecraftYaw() {
        var model=new AirportPlannerModel(snapshot(5));model.startNew(AirportPlannerModel.Tool.STAND);
        model.click(new Vec3(20.125,0,-6.875));assertTrue(model.canSave());
        model.headingToward(new Vec3(10.125,81,-6.875));assertEquals(90,model.draft().orElseThrow().yaw(),1e-9);
        model.movePoint(0,new Vec3(21.25,0,-7.75));
        var stand=AirportParkingStand.load(model.savePacket().toActionTag().getCompound("Stand"));
        assertEquals(new Vec3(21.25,81,-7.75),stand.position());assertEquals(90,stand.yawDegrees());
        assertEquals(1,model.draft().orElseThrow().points().size());
    }
    @Test void dirtyDrawingCannotBeSilentlyReplacedByToolOrSavedSelection() {
        var model=runwayDraft();var draft=model.draft().orElseThrow();
        assertFalse(model.startNew(AirportPlannerModel.Tool.STAND));assertFalse(model.setTool(AirportPlannerModel.Tool.INBOUND));
        assertFalse(model.selectRunway("r"));assertFalse(model.selectStand("s"));
        assertTrue(model.setTool(AirportPlannerModel.Tool.SELECT));assertEquals(draft,model.draft().orElseThrow());
        model.discard();assertTrue(model.selectStand("s"));assertFalse(model.dirty());
    }
    @Test void selectedDirtySavedObjectCanBeReselectedWithoutDiscardingIt() {
        var model=new AirportPlannerModel(snapshot(5));model.selectStand("s");model.setHeading(45);
        assertTrue(model.selectStand("s"));assertEquals(45,model.draft().orElseThrow().yaw());assertTrue(model.dirty());
    }
    @Test void dragHasOneUndoCheckpointAndCancelRestoresOriginalGeometry() {
        var model=new AirportPlannerModel(snapshot(5));model.selectStand("s");
        model.beginEdit();for(int i=0;i<100;i++) model.movePoint(0,new Vec3(i+.125,81,2.875));model.endEdit();
        assertTrue(model.undo());assertEquals(STAND.position(),model.draft().orElseThrow().points().getFirst());assertFalse(model.undo());
        model.beginEdit();model.movePoint(0,new Vec3(2,81,3));model.cancelEdit();
        assertEquals(STAND.position(),model.draft().orElseThrow().points().getFirst());assertFalse(model.dirty());assertFalse(model.undo());
    }
    @Test void historyIsBoundedAndDraftListsAreImmutable() {
        var model=new AirportPlannerModel(snapshot(5));model.selectStand("s");
        for(int i=0;i<200;i++)model.setHeading(i);
        int count=0;while(model.undo())count++;assertEquals(AirportPlannerModel.MAX_UNDO,count);assertTrue(count<=64);
        assertThrows(UnsupportedOperationException.class,()->model.draft().orElseThrow().points().add(Vec3.ZERO));
    }
    @Test void newerSnapshotAndServerRejectionPreserveDraftUntilExplicitRevisionAcceptance() {
        var model=runwayDraft();var draft=model.draft().orElseThrow();model.applySnapshot(snapshot(6,"foreign_town",List.of(RUNWAY),List.of(STAND),List.of()));
        assertEquals(draft,model.draft().orElseThrow());assertEquals(5,model.savePacket().revision());assertTrue(model.conflict());
        assertEquals("foreign_town",model.lastResult());model.acceptCurrentRevision();
        assertFalse(model.conflict());assertTrue(model.dirty());assertEquals(6,model.savePacket().revision());
    }
    @Test void staleOutOfOrderSnapshotAndOtherAirportCannotRollBackAuthoritativeState() {
        var model=runwayDraft();model.applySnapshot(snapshot(6));model.applySnapshot(snapshot(4));assertEquals(6,model.snapshot().revision());
        var other=new AirportScreenData(Level.NETHER.location(),AIRPORT,"Other","Town","Owner",true,9,List.of(),
                new AirportGroundLayout(9,List.of(),List.of()),List.of(),"");
        model.applySnapshot(other);assertEquals(Level.OVERWORLD.location(),model.snapshot().dimension());assertEquals(6,model.snapshot().revision());
    }
    @Test void exactSaveAcknowledgementClearsDirtyButUnrelatedOkCannot() {
        var model=runwayDraft();var packet=model.savePacket();model.markSubmitted(packet);assertTrue(model.awaitingReply());
        model.applySnapshot(snapshot(6,"ok",List.of(RUNWAY),List.of(STAND),List.of()));
        assertTrue(model.dirty());assertTrue(model.conflict());assertFalse(model.awaitingReply());
        model.acceptCurrentRevision();packet=model.savePacket();model.markSubmitted(packet);
        var saved=AirportFacility.load(packet.toActionTag().getCompound("Facility"));
        model.applySnapshot(snapshot(7,"ok",List.of(RUNWAY,saved),List.of(STAND),List.of()));
        assertFalse(model.dirty());assertFalse(model.conflict());assertEquals(7,model.draftRevision());assertFalse(model.undo());
    }
    @Test void pendingCandidateIsLockedUntilItsExactAcknowledgement() {
        var model=runwayDraft();var sent=model.savePacket();model.markSubmitted(sent);
        var submitted=model.draft().orElseThrow();
        assertThrows(IllegalStateException.class,()->model.movePoint(1,new Vec3(130,81,2)));
        assertEquals(submitted,model.draft().orElseThrow());
        var saved=AirportFacility.load(sent.toActionTag().getCompound("Facility"));
        model.applySnapshot(snapshot(6,"ok",List.of(RUNWAY,saved),List.of(STAND),List.of()));
        assertFalse(model.dirty());assertFalse(model.conflict());assertEquals(6,model.savePacket().revision());
    }
    @Test void pendingMutationCannotBeReplacedOrEditedAndFailurePreservesTheDraft() {
        var model=runwayDraft();var sent=model.savePacket();model.markSubmitted(sent);
        assertFalse(model.canSave());assertThrows(IllegalStateException.class,model::savePacket);
        assertThrows(IllegalStateException.class,()->model.markSubmitted(sent));
        assertThrows(IllegalStateException.class,()->model.movePoint(1,new Vec3(130,81,2)));
        assertTrue(model.dirty());assertTrue(model.awaitingReply());
        model.applySnapshot(snapshot(5,"occupied",List.of(RUNWAY),List.of(STAND),List.of()));
        assertFalse(model.awaitingReply());assertTrue(model.canSave());
    }
    @Test void deletingExistingFacilityDoesNotRequireItsIncompleteDirtyGeometryToBeSavable() {
        var model=new AirportPlannerModel(snapshot(5));model.selectRunway("r");model.deletePoint(1);assertFalse(model.canSave());
        var removal=model.removePacket();assertEquals(AirportActionPacket.Action.REMOVE_RUNWAY,removal.action());model.markSubmitted(removal);
        assertThrows(IllegalStateException.class,model::removePacket);
        model.applySnapshot(snapshot(6,"ok",List.of(),List.of(STAND),List.of()));
        assertTrue(model.draft().isEmpty());assertFalse(model.dirty());assertEquals(AirportPlannerModel.Tool.SELECT,model.tool());
    }
    @Test void sameRevisionRefreshDoesNotAlterIncompleteDraftOrHistory() {
        var model=new AirportPlannerModel(snapshot(5));model.startNew(AirportPlannerModel.Tool.RUNWAY);model.click(new Vec3(2,81,3));
        var draft=model.draft().orElseThrow();model.applySnapshot(snapshot(5));assertEquals(draft,model.draft().orElseThrow());assertTrue(model.undo());
    }
    @Test void refreshBetweenPointerPressAndFirstMovementPreservesSingleDragUndo() {
        var model=new AirportPlannerModel(snapshot(5));model.selectStand("s");model.beginEdit();model.applySnapshot(snapshot(5));
        for(int i=0;i<40;i++)model.movePoint(0,new Vec3(i+.25,81,5.75));model.endEdit();
        assertTrue(model.undo());assertEquals(STAND.position(),model.draft().orElseThrow().points().getFirst());assertFalse(model.undo());
    }
    @Test void olderPendingReplyClearsWaitingWithoutRollingBackNewerSnapshotOrRebasingDraft() {
        var model=runwayDraft();var sent=model.savePacket();model.markSubmitted(sent);model.applySnapshot(snapshot(7));
        var saved=AirportFacility.load(sent.toActionTag().getCompound("Facility"));
        model.applySnapshot(snapshot(6,"ok",List.of(RUNWAY,saved),List.of(STAND),List.of()));
        assertFalse(model.awaitingReply());assertEquals(7,model.snapshot().revision());assertTrue(model.dirty());assertTrue(model.conflict());assertEquals(5,model.draftRevision());
    }
    @Test void incompletePrecisionTextRetainsCapturedRevisionUntilExplicitReviewOrDiscard() {
        var model=new AirportPlannerModel(snapshot(5));model.selectStand("s");model.retainUnsavedInput();model.applySnapshot(snapshot(6));
        assertTrue(model.dirty());assertTrue(model.conflict());assertEquals(5,model.draftRevision());assertFalse(model.canSave());
        model.clearUnsavedInput();assertFalse(model.dirty());assertEquals(5,model.draftRevision());
        model.acceptCurrentRevision();assertEquals(6,model.savePacket().revision());
    }
    @Test void rawPrecisionCannotAlterThePendingCandidate() {
        var model=runwayDraft();var sent=model.savePacket();model.markSubmitted(sent);
        assertThrows(IllegalStateException.class,model::retainUnsavedInput);
        var saved=AirportFacility.load(sent.toActionTag().getCompound("Facility"));
        model.applySnapshot(snapshot(6,"ok",List.of(RUNWAY,saved),List.of(STAND),List.of()));
        assertFalse(model.dirty());assertFalse(model.conflict());assertEquals(6,model.draftRevision());assertTrue(model.canSave());
    }
    @Test void progressSnapshotKeepsPendingUntilTheFinalAcknowledgement() {
        var model=runwayDraft();var sent=model.savePacket();model.markSubmitted(sent);
        model.applySnapshot(snapshot(5,"pending",List.of(RUNWAY),List.of(STAND),List.of()));
        assertTrue(model.awaitingReply());assertFalse(model.canSave());assertTrue(model.dirty());
        model.applySnapshot(snapshot(5,"foreign_town",List.of(RUNWAY),List.of(STAND),List.of()));
        assertFalse(model.awaitingReply());assertTrue(model.dirty());assertTrue(model.canSave());
    }
    @Test void readOnlySnapshotCannotStartOrMutateAnEditor() {
        var original=snapshot(5);var readonly=new AirportScreenData(original.dimension(),AIRPORT,"A","T","O",false,5,
                original.facilities(),original.groundLayout(),List.of(),"");
        var model=new AirportPlannerModel(readonly);assertFalse(model.startNew(AirportPlannerModel.Tool.RUNWAY));
        assertTrue(model.selectStand("s"));assertThrows(IllegalStateException.class,()->model.setHeading(90));assertFalse(model.canSave());
    }
    @Test void previewReplacesSelectedGeometryButKeepsOtherSavedObjects() {
        var model=new AirportPlannerModel(snapshot(5));model.selectStand("s");model.movePoint(0,new Vec3(-61.125,81,5.875));
        assertEquals(1,model.previewStands().size());assertEquals(new Vec3(-61.125,81,5.875),model.previewStands().getFirst().position());
        assertEquals(List.of(RUNWAY),model.previewRunways());assertEquals(STAND,model.snapshot().groundLayout().stands().getFirst());
        model.discard();assertEquals(List.of(STAND),model.previewStands());
    }
}
