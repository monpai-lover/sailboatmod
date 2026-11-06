package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.monpai.sailboatmod.client.airport.AirportPlannerModel;
import com.monpai.sailboatmod.transport.air.airport.AirportFacility;
import com.monpai.sailboatmod.transport.air.airport.AirportGroundLayout;
import com.monpai.sailboatmod.transport.air.airport.AirportScreenData;
import com.monpai.sailboatmod.transport.air.airport.AirportParkingStand;
import com.monpai.sailboatmod.transport.air.airport.AirportTaxiRoute;
import com.monpai.sailboatmod.network.packet.airport.AirportActionPacket;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class AirportVisualScreenTest {
    @Test void asynchronousDeleteReplyRebuildsTheActualInspectorAndObjectList() throws Exception {
        var outgoing = outbound();
        var incoming = inbound();
        var packets = new ArrayList<AirportActionPacket>();
        var screen = new AirportScreen(airport(6, List.of(stand()), List.of(outgoing, incoming), List.of(), ""), packets::add);
        assertTrue(screen.planner().selectTaxi(incoming.id()));
        UIElement body = buildSelected(screen);
        assertEquals("taxi-in", precisionFields(screen).get("id").getText());
        assertTrue(texts(body).stream().anyMatch(text -> text.startsWith("13: ")));

        invoke(screen, "deleteSelected");
        assertEquals(AirportActionPacket.Action.REMOVE_TAXI_ROUTE, packets.getFirst().action());
        screen.update(airport(6, List.of(stand()), List.of(outgoing, incoming), List.of(), "pending"), true);
        assertTrue(screen.planner().awaitingReply());
        screen.update(airport(7, List.of(stand()), List.of(outgoing), List.of(), "ok"), false);

        assertTrue(screen.planner().draft().isEmpty(), "The authoritative deletion already clears the model");
        assertFalse(screen.planner().awaitingReply());
        assertTrue(precisionFields(screen).isEmpty(), "Deleted selection must not leave its old editable fields visible");
        assertFalse(texts(body).contains("← taxi-in"));
        assertFalse(texts(body).stream().anyMatch(text -> text.startsWith("13: ")));
        assertTrue(texts(body).contains("→ taxi-out"), "The remaining saved object must stay selectable");
    }

    @Test void asynchronousSaveReplyAddsTheNewObjectToTheActualWidgetList() throws Exception {
        var packets = new ArrayList<AirportActionPacket>();
        var screen = new AirportScreen(airport(6, List.of(), List.of(), List.of(), ""), packets::add);
        assertTrue(screen.planner().startNew(AirportPlannerModel.Tool.STAND));
        screen.planner().click(new Vec3(.5, 81, 16.5));
        UIElement body = buildSelected(screen);
        invoke(screen, "saveSelected");
        var saved = AirportParkingStand.load(packets.getFirst().toActionTag().getCompound("Stand"));
        assertFalse(texts(body).contains("P " + saved.id()));
        screen.update(airport(6, List.of(), List.of(), List.of(), "pending"), true);

        screen.update(airport(7, List.of(saved), List.of(), List.of(), "ok"), false);

        assertFalse(screen.planner().awaitingReply());
        assertTrue(texts(body).contains("P " + saved.id()), "A completed asynchronous save must refresh the actual object buttons");
    }

    @Test void sameRevisionValidationCompletionRefreshesOverviewWithoutAPlannerSubmission() {
        var screen = new AirportScreen(airport(6, List.of(), List.of(), List.of("runway|r|unvalidated"), ""), packet -> {});
        UIElement body = screen.buildBody();
        screen.update(airport(6, List.of(), List.of(), List.of("runway|r|unvalidated"), "pending"), true);
        assertFalse(screen.planner().awaitingReply(), "Validation uses the pending result rather than a geometry submission");
        assertTrue(translations(body).contains("screen.sailboatmod.airport.state.unvalidated"));

        screen.update(airport(6, List.of(), List.of(), List.of("runway|r|valid"), "valid"), false);

        assertTrue(translations(body).contains("screen.sailboatmod.airport.state.valid"));
        assertFalse(translations(body).contains("screen.sailboatmod.airport.state.unvalidated"));
    }

    @Test void pendingStatusKeepsTheActualRawTextWidgetAndFailureRetainsItsInvalidInput() throws Exception {
        var screen = new AirportScreen(airport(6, List.of(stand()), List.of(outbound(), inbound()), List.of(), ""), packet -> {});
        assertTrue(screen.planner().selectTaxi("taxi-in"));
        buildSelected(screen);
        TextField width = precisionFields(screen).get("width");
        width.setText("", true);
        var draft = screen.planner().draft().orElseThrow();
        screen.update(airport(6, List.of(stand()), List.of(outbound(), inbound()), List.of(), "pending"), false);
        screen.update(airport(6, List.of(stand()), List.of(outbound(), inbound()), List.of(), "pending"), false);

        assertSame(width, precisionFields(screen).get("width"), "Periodic progress must not replace a focused text widget");
        assertEquals("", width.getText());
        screen.update(airport(6, List.of(stand()), List.of(outbound(), inbound()), List.of(), "invalid_geometry"), false);
        assertEquals("", precisionFields(screen).get("width").getText(), "Completion must not silently restore the last valid number");
        assertEquals(draft, screen.planner().draft().orElseThrow());
        assertEquals(6, screen.planner().draftRevision());
        assertFalse(screen.planner().canSave());
    }

    @Test void snapshotRefreshRetainsVisualDraftAndItsCapturedRevision() {
        AirportScreen screen = new AirportScreen(snapshot(0));
        assertTrue(screen.planner().startNew(AirportPlannerModel.Tool.RUNWAY));
        screen.planner().click(new Vec3(10.5, 81, 20.5));
        screen.planner().click(new Vec3(60.5, 81, 20.5));
        var before = screen.planner().draft().orElseThrow();

        screen.update(snapshot(1), true);

        assertEquals(before, screen.planner().draft().orElseThrow());
        assertEquals(0, screen.planner().draftRevision());
        assertTrue(screen.planner().conflict());
        assertEquals(1, screen.data().revision());
    }

    @Test void foreignDimensionSnapshotCannotReplaceTheAirportOrDraft() {
        AirportScreen screen = new AirportScreen(snapshot(0));
        var other = new AirportScreenData(Level.NETHER.location(), BlockPos.ZERO, "Other", "Town", "Owner", true,
                0, List.of(), AirportGroundLayout.empty(), List.of(), "");
        screen.update(other, true);
        assertEquals(Level.OVERWORLD.location(), screen.data().dimension());
        assertEquals("Airport", screen.data().name());
    }

    @Test void shortWindowKeepsMapAndInspectorUsableWithoutGrowingBeyondItsFrame() {
        assertTrue(AirportScreen.plannerBodyHeight(240) <= 240 - 32);
        assertTrue(AirportScreen.plannerBodyHeight(480) <= 480 - 32);
        assertTrue(AirportScreen.inspectorWidth(427) <= 160);
        assertTrue(AirportScreen.inspectorWidth(854) <= 224);
        assertTrue(AirportScreen.inspectorWidth(427) >= 130);
    }

    @Test void deletionIsTrackedUntilAuthoritativeReplyAndClearsTheSelectedPreview() throws Exception {
        var stand = new AirportParkingStand("stand", new Vec3(10.123456789, 81, 20.987654321), 90, 24, 24, 16, true);
        var data = new AirportScreenData(Level.OVERWORLD.location(), BlockPos.ZERO, "Airport", "Town", "Owner", true,
                0, List.of(), new AirportGroundLayout(0, List.of(stand), List.of()), List.of(), "");
        var packets = new ArrayList<AirportActionPacket>();
        var screen = new AirportScreen(data, packets::add);
        assertTrue(screen.planner().selectStand("stand"));
        var remove = AirportScreen.class.getDeclaredMethod("deleteSelected"); remove.setAccessible(true);
        remove.invoke(screen);
        assertEquals(1, packets.size());
        assertEquals(AirportActionPacket.Action.REMOVE_STAND, packets.getFirst().action());
        assertTrue(screen.planner().awaitingReply());
        var ack = new AirportScreenData(Level.OVERWORLD.location(), BlockPos.ZERO, "Airport", "Town", "Owner", true,
                1, List.of(), new AirportGroundLayout(1, List.of(), List.of()), List.of(), "ok");
        screen.update(ack, true);
        assertFalse(screen.planner().awaitingReply());
        assertTrue(screen.planner().draft().isEmpty());
        assertTrue(screen.previewStands().isEmpty());
    }

    @Test void aRenameCannotStealTheReplyForAnInFlightGeometrySave() throws Exception {
        var screen = new AirportScreen(snapshot(0), packet -> {});
        assertTrue(screen.planner().startNew(AirportPlannerModel.Tool.RUNWAY));
        screen.planner().click(new Vec3(10.5, 81, 20.5)); screen.planner().click(new Vec3(60.5, 81, 20.5));
        var save = AirportScreen.class.getDeclaredMethod("saveSelected"); save.setAccessible(true); save.invoke(screen);
        assertTrue(screen.planner().awaitingReply());
        var rename = AirportScreen.class.getDeclaredMethod("send", AirportActionPacket.Action.class, String.class); rename.setAccessible(true);
        assertDoesNotThrow(() -> rename.invoke(screen, AirportActionPacket.Action.RENAME, "New name"));
        assertTrue(screen.planner().awaitingReply());
        assertEquals("Airport", screen.data().name());
    }

    @Test void validPrecisionChangesGeometryImmediatelyAndInvalidTextSurvivesNewerSnapshots() throws Exception {
        var stand = new AirportParkingStand("stand", new Vec3(10.123456789, 81, 20.987654321), 90, 24, 24, 16, true);
        var data = new AirportScreenData(Level.OVERWORLD.location(), BlockPos.ZERO, "Airport", "Town", "Owner", true,
                0, List.of(), new AirportGroundLayout(0, List.of(stand), List.of()), List.of(), "");
        var screen = new AirportScreen(data, packet -> {}); screen.planner().selectStand("stand");
        var synchronize = AirportScreen.class.getDeclaredMethod("synchronizePrecision"); synchronize.setAccessible(true); synchronize.invoke(screen);
        var apply = AirportScreen.class.getDeclaredMethod("applyPrecision", boolean.class); apply.setAccessible(true);
        assertEquals(true, apply.invoke(screen, false));
        assertEquals(stand.position(), screen.planner().draft().orElseThrow().points().getFirst(), "Opening precision controls must not round coordinates");
        var raw = AirportScreen.class.getDeclaredField("precision"); raw.setAccessible(true);
        @SuppressWarnings("unchecked") var text = (java.util.Map<String, String>) raw.get(screen);
        text.put("width", "38.125"); assertEquals(true, apply.invoke(screen, false));
        assertEquals(38.125, screen.planner().draft().orElseThrow().width());
        text.put("width", ""); assertEquals(false, apply.invoke(screen, false));
        var newer = new AirportScreenData(Level.OVERWORLD.location(), BlockPos.ZERO, "Airport", "Town", "Owner", true,
                1, List.of(), new AirportGroundLayout(1, List.of(stand), List.of()), List.of(), "");
        screen.update(newer, true);
        assertEquals("", text.get("width")); assertEquals(0, screen.planner().draftRevision());
        assertTrue(screen.planner().conflict()); assertFalse(screen.planner().canSave());
    }

    @Test void finiteButInvalidFacilityShapeHasImmediateLocalFeedback() {
        var screen = new AirportScreen(snapshot(0), packet -> {});
        screen.planner().startNew(AirportPlannerModel.Tool.RUNWAY);
        assertEquals("draft_incomplete", AirportScreen.draftStatus(screen.planner(), false));
        screen.planner().click(new Vec3(10.5, 81, 20.5)); screen.planner().click(new Vec3(60.5, 81, 20.5));
        screen.planner().updateProperties(screen.planner().draft().orElseThrow().withDimensions(24, -1, 16));
        assertEquals("draft_invalid", AirportScreen.draftStatus(screen.planner(), false));
        assertFalse(screen.planner().canSave());
    }

    @Test void aWorldPickUsesTheRealSupportHeightAndUndoRestoresOneWholeEdit() {
        var screen = new AirportScreen(snapshot(0), packet -> {});
        screen.planner().startNew(AirportPlannerModel.Tool.RUNWAY);
        screen.planner().setGroundY(81);
        screen.planner().click(new Vec3(10.5, 81, 20.5)); screen.planner().click(new Vec3(60.5, 81, 20.5));
        var before = screen.planner().draft().orElseThrow();
        screen.movePickedPoint(new Vec3(12.5, 90, 23.5));
        assertEquals(90, screen.planner().groundY());
        assertTrue(screen.planner().draft().orElseThrow().points().stream().allMatch(point -> point.y == 90));
        assertEquals(new Vec3(12.5, 90, 23.5), screen.planner().draft().orElseThrow().points().getFirst());
        assertTrue(screen.planner().undo()); assertEquals(before, screen.planner().draft().orElseThrow());
        assertEquals(81, screen.planner().groundY());
    }

    private static AirportScreenData snapshot(long revision) {
        return new AirportScreenData(Level.OVERWORLD.location(), BlockPos.ZERO, "Airport", "Town", "Owner", true,
                revision, List.of(), new AirportGroundLayout(revision, List.of(), List.of()), List.of(), "");
    }

    private static AirportScreenData airport(long revision, List<AirportParkingStand> stands, List<AirportTaxiRoute> routes,
            List<String> statuses, String result) {
        var runway = new AirportFacility("r", AirportFacility.Type.RUNWAY, new BlockPos(0, 81, 0), new BlockPos(64, 81, 0), 16, 8);
        return new AirportScreenData(Level.OVERWORLD.location(), BlockPos.ZERO, "Airport", "Town", "Owner", true,
                revision, List.of(runway), new AirportGroundLayout(revision, stands, routes), statuses, result);
    }

    private static AirportParkingStand stand() {
        return new AirportParkingStand("stand", new Vec3(.5, 81, 16.5), 0, 24, 24, 8, true);
    }

    private static AirportTaxiRoute outbound() {
        return new AirportTaxiRoute("taxi-out", AirportTaxiRoute.Direction.OUTBOUND, "r", "stand",
                List.of(stand().position(), new Vec3(.5, 81, 8.5), new Vec3(.5, 81, .5)), 12, 8, 2, 1);
    }

    private static AirportTaxiRoute inbound() {
        var points = new ArrayList<Vec3>();
        for (int i = 0; i <= 12; i++) points.add(new Vec3(64.5 - i * 64.0 / 12, 81, .5 + i * 16.0 / 12));
        return new AirportTaxiRoute("taxi-in", AirportTaxiRoute.Direction.INBOUND, "r", "stand", points, 12, 8, 2, -1);
    }

    private static UIElement buildSelected(AirportScreen screen) throws Exception {
        var overview = AirportScreen.class.getDeclaredField("overview");
        overview.setAccessible(true);
        overview.setBoolean(screen, false);
        return screen.buildBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, TextField> precisionFields(AirportScreen screen) throws Exception {
        var fields = AirportScreen.class.getDeclaredField("precisionFields");
        fields.setAccessible(true);
        return (Map<String, TextField>) fields.get(screen);
    }

    private static void invoke(AirportScreen screen, String method) throws Exception {
        var action = AirportScreen.class.getDeclaredMethod(method);
        action.setAccessible(true);
        action.invoke(screen);
    }

    private static List<String> texts(UIElement root) {
        var result = new ArrayList<String>();
        walk(root, element -> {
            if (element instanceof Label label) result.add(label.getValue().getString());
            if (element instanceof TextElement text) result.add(text.getText().getString());
        });
        return result;
    }

    private static Set<String> translations(UIElement root) {
        var result = new HashSet<String>();
        walk(root, element -> {
            if (element instanceof Label label) translationKeys(label.getValue(), result);
            if (element instanceof TextElement text) translationKeys(text.getText(), result);
        });
        return result;
    }

    private static void translationKeys(Component component, Set<String> keys) {
        if (component.getContents() instanceof TranslatableContents translated) keys.add(translated.getKey());
        component.getSiblings().forEach(sibling -> translationKeys(sibling, keys));
    }

    private static void walk(UIElement element, java.util.function.Consumer<UIElement> visitor) {
        visitor.accept(element);
        element.getChildren().forEach(child -> walk(child, visitor));
    }
}
