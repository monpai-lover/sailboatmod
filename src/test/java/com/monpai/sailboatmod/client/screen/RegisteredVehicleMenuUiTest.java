package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
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
    @Test void rMenuOpensTheExistingRoutesPageAndShowsServerDestinationNames() {
        var screen = new RegisteredVehicleControllerScreen(data(false, false));
        UIElement body = screen.buildBody();
        assertNotNull(button(body, "Ocean B"), "R must show the destination immediately, rather than the status page");
        assertNotNull(button(body, "Ocean C"));
        assertNull(button(body, "internal-route-one"));
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
    private static void click(Button button) throws Exception {
        var callback = Button.class.getDeclaredField("onClick"); callback.setAccessible(true);
        ((UIEventListener) callback.get(button)).handleEvent(null);
    }
    private static void walk(UIElement element, Consumer<UIElement> visit) {
        visit.accept(element); element.getChildren().forEach(child -> walk(child, visit));
    }
}
