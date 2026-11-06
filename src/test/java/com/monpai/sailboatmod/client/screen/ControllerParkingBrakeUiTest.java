package com.monpai.sailboatmod.client.screen;

import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventListener;
import com.monpai.sailboatmod.network.packet.RegisteredVehicleControllerActionPacket;
import com.monpai.sailboatmod.transport.control.ControllerAction;
import com.monpai.sailboatmod.transport.control.ControllerScreenData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ControllerParkingBrakeUiTest {
    private static final String PREFIX = "screen.sailboatmod.controller.";
    private static final BlockPos POS = new BlockPos(8, 81, 10);
    private static final UUID ID = UUID.fromString("78b8d492-727a-43df-9d6a-7d08158fdc60");

    @Test void parkingActionAppendsAfterAllThirtyExistingWireOrdinals() {
        var existing = List.of("REFRESH", "REGISTER", "UNREGISTER", "RENAME", "ADD_CARGO", "REMOVE_CARGO",
                "SET_THROTTLE_TARGET", "SET_STEERING_TARGET", "CLEAR_NATIVE_TARGETS", "SET_PORT", "CONFIRM_PORTS",
                "TOGGLE_INVERTED", "SET_SPEED", "TOGGLE_REVERSE", "BIND_NEAREST_TERMINAL", "IMPORT_ROUTES",
                "SELECT_ROUTE", "START", "PAUSE", "RESUME", "STOP", "TOGGLE_AUTO_RETURN", "TOGGLE_AUTO_UNLOAD",
                "SET_AIRCRAFT_CLASS", "SET_FLIGHT_TERMINAL", "ADD_GROUND_WHEEL", "REMOVE_GROUND_WHEEL",
                "SET_GROUND_CALIBRATION", "CONFIRM_GROUND_CALIBRATION", "CLEAR_GROUND_CALIBRATION");
        var actual = Arrays.stream(ControllerAction.values()).map(Enum::name).toList();
        assertEquals(existing, actual.subList(0, existing.size()));
        assertEquals("TOGGLE_PARKING_BRAKE", actual.get(existing.size()));
    }

    @Test void statusPageShowsActualHoldingStateAndTheOwnerReleaseAction() {
        var screen = new RegisteredVehicleControllerScreen(data("HOLDING", false));
        UIElement body = screen.buildBody();
        assertTrue(translations(body).contains(PREFIX + "parking_brake"));
        assertTrue(translations(body).contains(PREFIX + "parking_brake.holding"));
        assertNotNull(button(body, "parking_brake_release"));
        assertNull(optionalButton(body, "parking_brake_engage"));
    }

    @Test void realReleaseAndReengageButtonCallbacksSendOnlyTheAppendedAction() throws Exception {
        var packets = new ArrayList<RegisteredVehicleControllerActionPacket>();
        var screen = screen(data("HOLDING", false), packets::add);
        UIElement body = screen.buildBody();
        click(button(body, "parking_brake_release"));
        assertEquals(1, packets.size());
        assertParkingPacket(packets.getFirst());

        screen.update(data("RELEASED", true), false);
        assertTrue(translations(body).contains(PREFIX + "parking_brake.released"));
        assertNull(optionalButton(body, "parking_brake_release"));
        click(button(body, "parking_brake_engage"));
        assertEquals(2, packets.size());
        assertParkingPacket(packets.getLast());
    }

    @Test void asynchronousParkingStateChangeRefreshesStatusWithoutLosingEditedName() {
        var screen = new RegisteredVehicleControllerScreen(data("HOLDING", false));
        UIElement body = screen.buildBody();
        fields(body).getFirst().setText("Unsaved aircraft name", true);

        screen.update(data("WAITING", false), false);

        assertTrue(translations(body).contains(PREFIX + "parking_brake.waiting"));
        assertFalse(translations(body).contains(PREFIX + "parking_brake.holding"));
        assertEquals("Unsaved aircraft name", fields(body).getFirst().getText());
    }

    @Test void releaseFlagAloneRefreshesTheActualButtonLabel() {
        var screen = new RegisteredVehicleControllerScreen(data("WAITING", false));
        UIElement body = screen.buildBody();
        assertNotNull(button(body, "parking_brake_release"));

        screen.update(data("WAITING", true), false);

        assertNotNull(button(body, "parking_brake_engage"));
        assertNull(optionalButton(body, "parking_brake_release"));
    }

    @Test void periodicUnchangedParkingStatusPreservesTheActualFocusedTextWidget() {
        var screen = new RegisteredVehicleControllerScreen(data("HOLDING", false));
        UIElement body = screen.buildBody();
        TextField field = fields(body).getFirst();
        field.setText("", true);
        screen.update(data("HOLDING", false), false);
        assertSame(field, fields(body).getFirst());
        assertEquals("", field.getText());
    }

    @Test void parkingRefreshOnPowerPageKeepsAllActualDraftWidgetsAndRawInput() throws Exception {
        var screen = new RegisteredVehicleControllerScreen(data("HOLDING", false));
        UIElement body = screen.buildBody();
        var page = RegisteredVehicleControllerScreen.class.getDeclaredField("page");
        page.setAccessible(true);
        page.set(screen, "power");
        var render = RegisteredVehicleControllerScreen.class.getDeclaredMethod("renderPage");
        render.setAccessible(true);
        render.invoke(screen);
        var before = fields(body);
        assertTrue(before.size() >= 5);
        before.getLast().setText("unfinished calibration", true);

        screen.update(data("RELEASED", true), false);

        assertEquals(before, fields(body), "Parking-only status must not replace unrelated editing controls");
        assertEquals("unfinished calibration", fields(body).getLast().getText());
    }

    @Test void nonFixedWingControllersDoNotAdvertiseAnAircraftParkingAction() {
        var values = data("HOLDING", false).values();
        values.putString("Kind", "LAND");
        var screen = new RegisteredVehicleControllerScreen(new ControllerScreenData(POS, ID, values));
        UIElement body = screen.buildBody();
        assertNull(optionalButton(body, "parking_brake_release"));
        assertNull(optionalButton(body, "parking_brake_engage"));
        assertFalse(translations(body).contains(PREFIX + "parking_brake"));
    }

    @Test void everyParkingStatusAndActionHasEnglishAndChineseText() throws Exception {
        Path lang = Path.of("src/main/resources/assets/sailboatmod/lang");
        var en = JsonParser.parseString(Files.readString(lang.resolve("en_us.json"))).getAsJsonObject();
        var zh = JsonParser.parseString(Files.readString(lang.resolve("zh_cn.json"))).getAsJsonObject();
        for (String key : List.of("parking_brake", "parking_brake.holding", "parking_brake.released",
                "parking_brake.waiting", "parking_brake_release", "parking_brake_engage")) {
            assertTrue(en.has(PREFIX + key), "Missing English: " + key);
            assertTrue(zh.has(PREFIX + key), "Missing Chinese: " + key);
            assertFalse(en.get(PREFIX + key).getAsString().isBlank());
            assertFalse(zh.get(PREFIX + key).getAsString().isBlank());
        }
    }

    private static ControllerScreenData data(String parking, boolean released) {
        var tag = new CompoundTag();
        tag.putString("Name", "Aircraft"); tag.putString("Status", "IDLE");
        tag.putString("Kind", "AIR"); tag.putString("AircraftClass", "FIXED_WING");
        tag.putString("ParkingBrake", parking); tag.putBoolean("ParkingBrakeReleased", released);
        tag.putDouble("MaxSpeed", 49); tag.putString("ThrottleTarget", "3 -1 -2");
        tag.putString("GroundCalibration", "0.75 32 30 15 0 1");
        return new ControllerScreenData(POS, ID, tag);
    }

    private static RegisteredVehicleControllerScreen screen(ControllerScreenData data,
            Consumer<RegisteredVehicleControllerActionPacket> sender) {
        var constructor = assertDoesNotThrow(() -> RegisteredVehicleControllerScreen.class
                .getDeclaredConstructor(ControllerScreenData.class, Consumer.class));
        constructor.setAccessible(true);
        return assertDoesNotThrow(() -> constructor.newInstance(data, sender));
    }

    private static void assertParkingPacket(RegisteredVehicleControllerActionPacket packet) {
        assertEquals(POS, packet.controllerPos()); assertEquals(ID, packet.registrationId());
        assertEquals("TOGGLE_PARKING_BRAKE", packet.action().name());
        assertEquals("", packet.value()); assertEquals(0, packet.index());
    }

    private static void click(Button button) throws Exception {
        var callback = Button.class.getDeclaredField("onClick");
        callback.setAccessible(true);
        ((UIEventListener) callback.get(button)).handleEvent(null);
    }

    private static Button button(UIElement root, String key) {
        Button button = optionalButton(root, key);
        assertNotNull(button, "Actual widget missing: " + key);
        return button;
    }

    private static Button optionalButton(UIElement root, String key) {
        var result = new ArrayList<Button>();
        walk(root, element -> {
            if (element instanceof Button b && b.text.getText().getContents() instanceof TranslatableContents t
                    && t.getKey().equals(PREFIX + key)) result.add(b);
        });
        return result.isEmpty() ? null : result.getFirst();
    }

    private static List<TextField> fields(UIElement root) {
        var fields = new ArrayList<TextField>();
        walk(root, element -> { if (element instanceof TextField field) fields.add(field); });
        return fields;
    }

    private static Set<String> translations(UIElement root) {
        var result = new HashSet<String>();
        walk(root, element -> {
            if (element instanceof Label label) translations(label.getValue(), result);
            if (element instanceof TextElement text) translations(text.getText(), result);
        });
        return result;
    }

    private static void translations(Component component, Set<String> keys) {
        if (component.getContents() instanceof TranslatableContents t) keys.add(t.getKey());
        component.getSiblings().forEach(sibling -> translations(sibling, keys));
    }

    private static void walk(UIElement element, Consumer<UIElement> visitor) {
        visitor.accept(element);
        element.getChildren().forEach(child -> walk(child, visitor));
    }
}
