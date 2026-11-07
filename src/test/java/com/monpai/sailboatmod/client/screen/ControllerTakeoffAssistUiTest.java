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

/** Actual owner widgets and callbacks; physical assist correctness is tested separately on the server. */
class ControllerTakeoffAssistUiTest {
    private static final String PREFIX = "screen.sailboatmod.controller.";
    private static final BlockPos POS = new BlockPos(8, 81, 10);
    private static final UUID ID = UUID.fromString("5460abc2-5806-4846-9f3c-ab6fae2d3caf");

    @Test void assistActionsAppendAfterAllThirtyOneExistingWireOrdinals() {
        var existing = List.of("REFRESH", "REGISTER", "UNREGISTER", "RENAME", "ADD_CARGO", "REMOVE_CARGO",
                "SET_THROTTLE_TARGET", "SET_STEERING_TARGET", "CLEAR_NATIVE_TARGETS", "SET_PORT", "CONFIRM_PORTS",
                "TOGGLE_INVERTED", "SET_SPEED", "TOGGLE_REVERSE", "BIND_NEAREST_TERMINAL", "IMPORT_ROUTES",
                "SELECT_ROUTE", "START", "PAUSE", "RESUME", "STOP", "TOGGLE_AUTO_RETURN", "TOGGLE_AUTO_UNLOAD",
                "SET_AIRCRAFT_CLASS", "SET_FLIGHT_TERMINAL", "ADD_GROUND_WHEEL", "REMOVE_GROUND_WHEEL",
                "SET_GROUND_CALIBRATION", "CONFIRM_GROUND_CALIBRATION", "CLEAR_GROUND_CALIBRATION", "TOGGLE_PARKING_BRAKE");
        var actual = Arrays.stream(ControllerAction.values()).map(Enum::name).toList();
        assertEquals(existing, actual.subList(0, existing.size()));
        assertEquals(List.of("TOGGLE_TAKEOFF_ASSIST", "SET_TAKEOFF_ASSIST_ACCELERATION"), actual.subList(existing.size(), existing.size()+2));
    }

    @Test void fixedWingNavigationShowsExplicitPhysicalAssistOffWithTwelveDefault() throws Exception {
        var screen = new RegisteredVehicleControllerScreen(data("FIXED_WING", false, "off", null));
        UIElement body = routes(screen);
        assertTrue(translations(body).contains(PREFIX + "takeoff_assist_title"));
        assertTrue(translations(body).contains(PREFIX + "takeoff_assist_hint"));
        assertTrue(translations(body).contains(PREFIX + "takeoff_assist_status.off"));
        assertNotNull(button(body, "takeoff_assist_enable"));
        assertNull(optionalButton(body, "takeoff_assist_disable"));
        assertEquals(1, fields(body).size(), "Navigation needs the actual editable acceleration field");
        assertEquals(12, Double.parseDouble(fields(body).getFirst().getText()));
    }

    @Test void realToggleAndAccelerationSaveUseOnlyTheNewActionsAndExistingPacket() throws Exception {
        var packets = new ArrayList<RegisteredVehicleControllerActionPacket>();
        var screen = new RegisteredVehicleControllerScreen(data("FIXED_WING", false, "off", 12.0), packets::add);
        UIElement body = routes(screen);
        click(button(body, "takeoff_assist_enable"));
        assertEquals(1, packets.size());
        assertPacket(packets.getFirst(), "TOGGLE_TAKEOFF_ASSIST", "");
        assertEquals(1, fields(body).size());
        fields(body).getFirst().setText("7.5", true);
        click(button(body, "takeoff_assist_save_acceleration"));
        assertEquals(2, packets.size());
        assertPacket(packets.getLast(), "SET_TAKEOFF_ASSIST_ACCELERATION", "7.5");
    }

    @Test void emptyOrOutOfRangeDraftDoesNotSubmitTheLastValidAcceleration() throws Exception {
        var packets = new ArrayList<RegisteredVehicleControllerActionPacket>();
        var screen = new RegisteredVehicleControllerScreen(data("FIXED_WING", false, "off", 12.0), packets::add);
        UIElement body = routes(screen);
        assertEquals(1, fields(body).size());
        for (String raw : List.of("", "21")) {
            fields(body).getFirst().setText(raw, true);
            click(button(body, "takeoff_assist_save_acceleration"));
        }
        assertTrue(packets.isEmpty(), "Invalid drafts must not silently save the previous12 value");
        for (String raw : List.of("0", "20")) {
            fields(body).getFirst().setText(raw, true);
            click(button(body, "takeoff_assist_save_acceleration"));
        }
        assertEquals(2, packets.size(), "Both declared range endpoints are valid");
    }

    @Test void asynchronousAssistStateUpdatesKeepTheFocusedDraftWidgetAndToggleCurrent() throws Exception {
        var screen = new RegisteredVehicleControllerScreen(data("FIXED_WING", false, "off", 12.0));
        UIElement body = routes(screen);
        assertEquals(1, fields(body).size());
        TextField edited = fields(body).getFirst();
        edited.setText("7.", true);
        screen.update(data("FIXED_WING", true, "active", 12.0), false);
        assertSame(edited, fields(body).getFirst());
        assertEquals("7.", edited.getText());
        assertTrue(translations(body).contains(PREFIX + "takeoff_assist_status.active"));
        assertNotNull(button(body, "takeoff_assist_disable"));
        assertNull(optionalButton(body, "takeoff_assist_enable"));
    }

    @Test void nonFixedWingControllersDoNotAdvertiseTakeoffPushSettings() throws Exception {
        for (String type : List.of("AIRSHIP", "ROTORCRAFT", "LAND")) {
            var screen = new RegisteredVehicleControllerScreen(data(type, false, "off", 12.0));
            UIElement body = routes(screen);
            assertFalse(translations(body).contains(PREFIX + "takeoff_assist_title"));
            assertNull(optionalButton(body, "takeoff_assist_enable"));
        }
    }

    @Test void unknownServerStatusRemainsVisibleInsteadOfClaimingAssistActive() throws Exception {
        var screen = new RegisteredVehicleControllerScreen(data("FIXED_WING", true, "new_native_reason", 12.0));
        UIElement body = routes(screen);
        var visible = new ArrayList<String>();
        walk(body, element -> { if (element instanceof Label label) visible.add(label.getValue().getString()); });
        assertTrue(visible.contains("new_native_reason"));
        assertFalse(translations(body).contains(PREFIX + "takeoff_assist_status.active"));
    }

    @Test void everyAssistSettingAndRuntimeStateHasHonestEnglishAndChineseText() throws Exception {
        Path lang = Path.of("src/main/resources/assets/sailboatmod/lang");
        var en = JsonParser.parseString(Files.readString(lang.resolve("en_us.json"))).getAsJsonObject();
        var zh = JsonParser.parseString(Files.readString(lang.resolve("zh_cn.json"))).getAsJsonObject();
        for (String suffix : List.of("takeoff_assist_title", "takeoff_assist_hint", "takeoff_assist_status", "takeoff_assist_enable",
                "takeoff_assist_disable", "takeoff_assist_acceleration", "takeoff_assist_save_acceleration",
                "takeoff_assist_status.off", "takeoff_assist_status.armed", "takeoff_assist_status.active",
                "takeoff_assist_status.airborne", "takeoff_assist_status.waiting", "takeoff_assist_status.blocked")) {
            assertTrue(en.has(PREFIX + suffix), "Missing English " + suffix);
            assertTrue(zh.has(PREFIX + suffix), "Missing Chinese " + suffix);
            assertFalse(en.get(PREFIX + suffix).getAsString().isBlank());
            assertFalse(zh.get(PREFIX + suffix).getAsString().isBlank());
        }
        String zhHint = zh.get(PREFIX + "takeoff_assist_hint").getAsString();
        assertTrue(zhHint.contains("额外物理推力"));
        assertTrue(zhHint.contains("离地后"));
        assertTrue(zhHint.contains("默认关闭"));
    }

    private static ControllerScreenData data(String airframe, boolean enabled, String status, Double acceleration) {
        var tag = new CompoundTag();
        tag.putString("Name", "Aircraft"); tag.putString("Status", "IDLE");
        tag.putString("Kind", airframe.equals("LAND") ? "LAND" : "AIR");
        tag.putString("AircraftClass", airframe); tag.putDouble("MaxSpeed", 49);
        tag.putBoolean("TakeoffAssistEnabled", enabled); tag.putString("TakeoffAssistStatus", status);
        if (acceleration != null) tag.putDouble("TakeoffAssistAcceleration", acceleration);
        return new ControllerScreenData(POS, ID, tag);
    }

    private static UIElement routes(RegisteredVehicleControllerScreen screen) throws Exception {
        UIElement body = screen.buildBody();
        var page = RegisteredVehicleControllerScreen.class.getDeclaredField("page"); page.setAccessible(true); page.set(screen, "routes");
        var render = RegisteredVehicleControllerScreen.class.getDeclaredMethod("renderPage"); render.setAccessible(true); render.invoke(screen);
        return body;
    }

    private static void assertPacket(RegisteredVehicleControllerActionPacket packet, String action, String value) {
        assertEquals(POS, packet.controllerPos()); assertEquals(ID, packet.registrationId());
        assertEquals(action, packet.action().name()); assertEquals(value, packet.value()); assertEquals(0, packet.index());
    }

    private static void click(Button button) throws Exception {
        var callback = Button.class.getDeclaredField("onClick"); callback.setAccessible(true);
        ((UIEventListener) callback.get(button)).handleEvent(null);
    }

    private static Button button(UIElement root, String key) { Button b=optionalButton(root,key); assertNotNull(b,"Actual widget missing: "+key); return b; }
    private static Button optionalButton(UIElement root, String key) {
        var result = new ArrayList<Button>();
        walk(root, element -> { if(element instanceof Button b && b.text.getText().getContents() instanceof TranslatableContents t && t.getKey().equals(PREFIX+key))result.add(b); });
        return result.isEmpty() ? null : result.getFirst();
    }
    private static List<TextField> fields(UIElement root) { var out=new ArrayList<TextField>();walk(root,e->{if(e instanceof TextField f)out.add(f);});return out; }
    private static Set<String> translations(UIElement root) { var out=new HashSet<String>();walk(root,e->{if(e instanceof Label l)translations(l.getValue(),out);if(e instanceof TextElement t)translations(t.getText(),out);});return out; }
    private static void translations(Component c, Set<String> out) { if(c.getContents() instanceof TranslatableContents t)out.add(t.getKey());c.getSiblings().forEach(s->translations(s,out)); }
    private static void walk(UIElement e, Consumer<UIElement> visitor) { visitor.accept(e);e.getChildren().forEach(c->walk(c,visitor)); }
}
