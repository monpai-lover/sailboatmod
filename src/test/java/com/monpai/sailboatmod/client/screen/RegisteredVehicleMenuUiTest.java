package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventListener;
import com.monpai.sailboatmod.network.packet.RegisteredVehicleControllerActionPacket;
import com.monpai.sailboatmod.transport.control.ControllerAction;
import com.monpai.sailboatmod.transport.control.ControllerScreenData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredVehicleMenuUiTest {
    private static final BlockPos AIRPORT_ORIGIN = new BlockPos(-213, 81, 19);
    private static final BlockPos AIRPORT_EAST = new BlockPos(240, 81, 126);
    private static final BlockPos AIRPORT_WEST = new BlockPos(-460, 75, 290);

    @Test void rMenuOpensTheExistingRoutesPageAndShowsServerDestinationNames() {
        var screen = new RegisteredVehicleControllerScreen(data(false, false));
        UIElement body = screen.buildBody();
        assertNotNull(button(body, "Ocean B"), "R must show the destination immediately, rather than the status page");
        assertNotNull(button(body, "Ocean C"));
        assertNull(button(body, "internal-route-one"));
    }

    @Test void overviewOffersTheNextActionsBeforeOptionalVehicleDetails() throws Exception {
        var snapshot = overviewData(true, 0);
        var screen = new RegisteredVehicleControllerScreen(snapshot);
        var body = screen.buildBody();

        assertTrue(hasTranslatedButton(body, "view_destinations"));
        assertTrue(hasTranslatedButton(body, "manage_cargo"));
        assertTrue(hasTranslatedButton(body, "cargo_auto_configure"));
        assertFalse(hasTranslatedButton(body, "unregister"));
        assertFalse(hasTranslatedButton(body, "rename"));
        click(assertNotNullButton(body, "screen.sailboatmod.controller.show_details"));
        assertTrue(hasTranslatedButton(body, "unregister"));
        assertTrue(hasTranslatedButton(body, "rename"));

        click(assertNotNullButton(body, "screen.sailboatmod.controller.view_destinations"));
        assertNotNullButton(body, "Ocean C");
    }

    @Test void cargoOffersOneClickDetectionAndMakesManualOffsetsOptIn() throws Exception {
        var sent = new ArrayList<RegisteredVehicleControllerActionPacket>();
        var screen = new RegisteredVehicleControllerScreen(overviewData(true, 0), sent::add);
        var body = screen.buildBody();
        click(assertNotNullButton(body, "screen.sailboatmod.controller.manage_cargo"));

        assertFalse(hasTranslatedButton(body, "add_cargo"));
        var scan = assertNotNullButton(body, "screen.sailboatmod.controller.cargo_auto_configure");
        click(scan);
        assertEquals(List.of(ControllerAction.AUTO_CONFIGURE), sent.stream().map(RegisteredVehicleControllerActionPacket::action).toList());

        screen.update(overviewData(false, 1), false);
        click(scan);
        assertEquals(1, sent.size(), "An old button cannot send a scan when the refreshed snapshot disallows it");
        click(assertNotNullButton(body, "screen.sailboatmod.controller.show_manual_cargo"));
        click(assertNotNullButton(body, "screen.sailboatmod.controller.add_cargo"));
        assertEquals(ControllerAction.ADD_CARGO, sent.getLast().action());
        assertEquals("1 0 0", sent.getLast().value());
        click(assertNotNullButton(body, "screen.sailboatmod.controller.remove"));
        assertEquals(ControllerAction.REMOVE_CARGO, sent.getLast().action());
        assertEquals("2, 0, 0", sent.getLast().value());
    }

    @Test void destinationClickUsesTheOriginalSelectionActionAndServerIndex() throws Exception {
        var sent = new ArrayList<RegisteredVehicleControllerActionPacket>();
        var screen = new RegisteredVehicleControllerScreen(data(false, false), sent::add);
        UIElement body = screen.buildBody();
        click(assertNotNullButton(body, "Ocean C"));
        assertEquals(1, sent.size());
        assertEquals(ControllerAction.SELECT_ROUTE, sent.getFirst().action());
        assertEquals(1, sent.getFirst().index());
        assertEquals(data(false, false).registrationId(), sent.getFirst().registrationId());
    }

    @Test void rMenuCannotSelectAnotherDestinationWhileAJobIsActiveOrStillCarriesAnOrder() throws Exception {
        for (boolean active : List.of(false, true)) {
            var sent = new ArrayList<RegisteredVehicleControllerActionPacket>();
            var screen = new RegisteredVehicleControllerScreen(data(active, !active), sent::add);
            UIElement body = screen.buildBody();
            click(assertNotNullButton(body, "Ocean C"));
            assertTrue(sent.isEmpty(), "Opening the R menu must not reroute an existing transport order");
        }
    }

    @Test void emptyDestinationMenuExplainsTheNormalSetupInsteadOfOfferingAFakeDestination() {
        CompoundTag tag = data(false, false).values();
        tag.put("Routes", new ListTag()); tag.put("Destinations", new ListTag());
        UIElement body = new RegisteredVehicleControllerScreen(new ControllerScreenData(BlockPos.ZERO, new UUID(3, 4), tag)).buildBody();
        var translations = new ArrayList<String>();
        walk(body, e -> {
            var component = e instanceof Label label ? label.getValue() : e instanceof TextElement text ? text.getText() : null;
            if (component != null && component.getContents() instanceof TranslatableContents t) translations.add(t.getKey());
        });
        assertTrue(translations.contains("screen.sailboatmod.controller.no_destinations"));
    }

    @Test void aircraftDestinationClickSendsOneStartWithTheAuthoritativeOriginAndTarget() throws Exception {
        var sent = new ArrayList<RegisteredVehicleControllerActionPacket>();
        var snapshot = aircraftData(false, false, false);
        var screen = new RegisteredVehicleControllerScreen(snapshot, sent::add);
        UIElement body = screen.buildBody();

        assertNotNullButton(body, "East Airport");
        assertNull(button(body, "Ocean B"), "Aircraft departure must replace previously imported route buttons");
        assertFalse(hasTranslatedButton(body, "start"));
        assertFalse(hasTranslatedButton(body, "fuel_check_start"));
        click(assertNotNullButton(body, "West Airport"));

        assertEquals(1, sent.size());
        var packet = sent.getFirst();
        assertEquals("START_AIRPORT_DESTINATION", packet.action().name());
        assertEquals(AIRPORT_ORIGIN.asLong() + ":" + AIRPORT_WEST.asLong(), packet.value());
        assertEquals(0, packet.index());
        assertEquals(snapshot.registrationId(), packet.registrationId());
        assertEquals(snapshot.controllerPos(), packet.controllerPos());
        screen.update(aircraftData(false, false, false), false);
        click(assertNotNullButton(body, "West Airport"));
        assertEquals(1, sent.size(), "An earlier periodic refresh is not the start acknowledgment");
        screen.update(aircraftData(false, false, false), true);
        click(assertNotNullButton(body, "West Airport"));
        assertEquals(2, sent.size(), "After a server rejection, the same destination can be retried");
    }

    @Test void aircraftDestinationCannotStartAfterAnActivePausedOrCargoSnapshot() throws Exception {
        for (var snapshot : List.of(aircraftData(true, false, false), aircraftData(false, true, false),
                aircraftData(false, false, true))) {
            var sent = new ArrayList<RegisteredVehicleControllerActionPacket>();
            var screen = new RegisteredVehicleControllerScreen(aircraftData(false, false, false), sent::add);
            UIElement body = screen.buildBody();
            var previousButton = assertNotNullButton(body, "West Airport");
            screen.update(snapshot, false);

            click(previousButton);
            var currentButton = button(body, "West Airport");
            if (currentButton != null) click(currentButton);
            assertTrue(sent.isEmpty(), "Even an earlier button callback must respect the current aircraft state");
        }
    }

    @Test void everyRegisteredModeUsesTheSameKnownDestinationAction() throws Exception {
        for (String kind : List.of("SEA", "LAND", "AIR")) {
            var base = aircraftData(false, false, false);
            var values = base.values();
            values.putString("Kind", kind);
            values.putBoolean("AirRail", "AIR".equals(kind));
            values.put("KnownDeparture", values.getCompound("AirportDeparture"));
            values.remove("AirportDeparture");
            var sent = new ArrayList<RegisteredVehicleControllerActionPacket>();
            var screen = new RegisteredVehicleControllerScreen(
                    new ControllerScreenData(base.controllerPos(), base.registrationId(), values), sent::add);
            UIElement body = screen.buildBody();
            assertNull(button(body, "Ocean B"), kind);
            click(assertNotNullButton(body, "West Airport"));
            assertEquals(1, sent.size(), kind);
            assertEquals("START_KNOWN_DESTINATION", sent.getFirst().action().name(), kind);
            assertEquals(AIRPORT_ORIGIN.asLong() + ":" + AIRPORT_WEST.asLong(), sent.getFirst().value(), kind);
        }
    }

    @Test void unifiedRegistrationOffersVehicleTypesBeforeShowingAdvancedControls() throws Exception {
        var base = data(false, false);
        var values = base.values();
        values.putBoolean("ChooseRegistrationMode", true);
        values.putBoolean("RegistrationStructureReady", true);
        var sent = new ArrayList<RegisteredVehicleControllerActionPacket>();
        UIElement body = new RegisteredVehicleControllerScreen(
                new ControllerScreenData(base.controllerPos(), base.registrationId(), values), sent::add).buildBody();
        assertNull(button(body, "Ocean B"));
        assertFalse(hasTranslatedButton(body, "register"));
        for (String mode : List.of("water", "land", "fixed_wing", "rotorcraft", "airship")) {
            click(assertNotNullButton(body, "screen.sailboatmod.controller.registration_mode." + mode));
        }
        assertEquals(List.of("WATER", "LAND", "FIXED_WING", "ROTORCRAFT", "AIRSHIP"),
                sent.stream().map(RegisteredVehicleControllerActionPacket::value).toList());
        assertTrue(sent.stream().allMatch(p -> p.action() == ControllerAction.REGISTER));
    }

    @Test void firstRegistrationSendsNameAndVehicleTypeTogether() throws Exception {
        var base = data(false, false);
        var values = base.values();
        values.putBoolean("ChooseRegistrationMode", true);
        values.putBoolean("NamedRegistration", true);
        values.putBoolean("RegistrationStructureReady", true);
        var sent = new ArrayList<RegisteredVehicleControllerActionPacket>();
        UIElement body = new RegisteredVehicleControllerScreen(
                new ControllerScreenData(base.controllerPos(), base.registrationId(), values), sent::add).buildBody();
        var register = assertNotNullButton(body, "screen.sailboatmod.controller.registration_mode.fixed_wing");
        click(register);
        assertTrue(sent.isEmpty());
        var fields = new ArrayList<TextField>();
        walk(body, element -> { if (element instanceof TextField field) fields.add(field); });
        assertEquals(1, fields.size());
        fields.getFirst().setText("Test:Airliner", true);
        click(register);
        assertEquals(1, sent.size());
        assertEquals(ControllerAction.REGISTER, sent.getFirst().action());
        assertEquals("FIXED_WING:Test:Airliner", sent.getFirst().value());
    }

    @Test void emptyAircraftDepartureDoesNotOfferImportedRoutesOrStandaloneStart() {
        var snapshot = aircraftData(false, false, false);
        var departure = snapshot.values().getCompound("AirportDeparture");
        departure.put("Options", new ListTag());
        departure.putString("Reason", "airport_stand_required");
        UIElement body = new RegisteredVehicleControllerScreen(snapshot).buildBody();
        assertNull(button(body, "East Airport"));
        assertNull(button(body, "Ocean B"));
        assertFalse(hasTranslatedButton(body, "start"));
        assertFalse(hasTranslatedButton(body, "fuel_check_start"));
    }

    private static ControllerScreenData overviewData(boolean canScan, int containers) {
        var base = data(false, false);
        var values = base.values();
        values.remove("InitialPage");
        values.putBoolean("Registered", true);
        values.putBoolean("NamedRegistration", true);
        values.putBoolean("CanAutoConfigure", canScan);
        var cargo = new ListTag();
        if (containers > 0) cargo.add(StringTag.valueOf("2, 0, 0"));
        values.put("Cargo", cargo);
        return new ControllerScreenData(base.controllerPos(), base.registrationId(), values);
    }

    private static ControllerScreenData aircraftData(boolean active, boolean paused, boolean manifest) {
        var base = data(active, manifest);
        var tag = base.values();
        tag.putString("Kind", "AIR");
        tag.putString("AircraftClass", "FIXED_WING");
        tag.putBoolean("AirRail", true);
        tag.putBoolean("Paused", paused);
        var departure = new CompoundTag();
        departure.putLong("Origin", AIRPORT_ORIGIN.asLong());
        departure.putString("OriginName", "Origin Airport");
        var options = new ListTag();
        options.add(airportOption(AIRPORT_EAST, "East Airport"));
        options.add(airportOption(AIRPORT_WEST, "West Airport"));
        departure.put("Options", options);
        tag.put("AirportDeparture", departure);
        return new ControllerScreenData(base.controllerPos(), base.registrationId(), tag);
    }

    private static CompoundTag airportOption(BlockPos position, String name) {
        var option = new CompoundTag();
        option.putLong("Pos", position.asLong());
        option.putString("Name", name);
        return option;
    }

    private static ControllerScreenData data(boolean active, boolean manifest) {
        var tag = new CompoundTag();
        tag.putString("InitialPage", "routes"); tag.putString("Name", "Registered vehicle");
        tag.putString("Status", "IDLE"); tag.putString("Kind", "LAND"); tag.putDouble("MaxSpeed", 3);
        tag.putBoolean("Active", active); tag.putInt("ManifestCount", manifest ? 1 : 0);
        var routes = new ListTag(); routes.add(StringTag.valueOf("internal-route-one")); routes.add(StringTag.valueOf("internal-route-two"));
        var destinations = new ListTag(); destinations.add(StringTag.valueOf("Ocean B")); destinations.add(StringTag.valueOf("Ocean C"));
        tag.put("Routes", routes); tag.put("Destinations", destinations);
        return new ControllerScreenData(new BlockPos(20, 64, 30), new UUID(1, 2), tag);
    }
    private static Button assertNotNullButton(UIElement root, String name) {
        Button found = button(root, name); assertNotNull(found, "Missing real destination button: " + name); return found;
    }
    private static Button button(UIElement root, String name) {
        var found = new ArrayList<Button>();
        walk(root, element -> { if (element instanceof Button b && b.text.getText().getString().contains(name)) found.add(b); });
        return found.isEmpty() ? null : found.getFirst();
    }
    private static boolean hasTranslatedButton(UIElement root, String suffix) {
        var found = new ArrayList<Button>();
        walk(root, element -> {
            if (element instanceof Button b && b.text.getText().getContents() instanceof TranslatableContents text
                    && text.getKey().equals("screen.sailboatmod.controller." + suffix)) found.add(b);
        });
        return !found.isEmpty();
    }
    private static void click(Button button) throws Exception {
        var callback = Button.class.getDeclaredField("onClick"); callback.setAccessible(true);
        ((UIEventListener) callback.get(button)).handleEvent(null);
    }
    private static void walk(UIElement element, Consumer<UIElement> visit) {
        visit.accept(element); element.getChildren().forEach(child -> walk(child, visit));
    }
}
