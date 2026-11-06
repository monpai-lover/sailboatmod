package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.util.Set;
import java.util.HashSet;
import com.monpai.sailboatmod.client.airport.AirportPlannerModel;
import com.monpai.sailboatmod.transport.air.airport.AirportGroundLayout;
import com.monpai.sailboatmod.transport.air.airport.AirportScreenData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AirportInspectorHoldTest {
    @Test void inboundInspectorDoesNotOfferAnUnsupportedWaitingPointEditor() throws Exception {
        var inspector = inspector(AirportPlannerModel.Tool.INBOUND);
        assertFalse(inspector.fields().containsKey("hold"));
        assertFalse(inspector.translations().contains("screen.sailboatmod.airport.set_hold"));
        assertFalse(inspector.translations().contains("screen.sailboatmod.airport.clear_hold"));
        assertTrue(inspector.translations().contains("screen.sailboatmod.airport.inbound_no_hold"));
    }
    @Test void outboundInspectorRetainsItsWaitingPointEditor() throws Exception {
        var inspector = inspector(AirportPlannerModel.Tool.OUTBOUND);
        assertTrue(inspector.fields().containsKey("hold"));
        assertTrue(inspector.translations().contains("screen.sailboatmod.airport.set_hold"));
        assertTrue(inspector.translations().contains("screen.sailboatmod.airport.clear_hold"));
        assertFalse(inspector.translations().contains("screen.sailboatmod.airport.inbound_no_hold"));
    }

    private record Inspector(Map<?, ?> fields, Set<String> translations) {}
    private static Inspector inspector(AirportPlannerModel.Tool tool) throws Exception {
        var data = new AirportScreenData(Level.OVERWORLD.location(), BlockPos.ZERO, "Airport", "Town", "Owner", true,
                0, List.of(), AirportGroundLayout.empty(), List.of(), "");
        var screen = new AirportScreen(data, packet -> {});
        assertTrue(screen.planner().startNew(tool));
        Field inspector = AirportScreen.class.getDeclaredField("inspector"); inspector.setAccessible(true);
        var content = new ScrollerView(); inspector.set(screen, content);
        var synchronize = AirportScreen.class.getDeclaredMethod("synchronizePrecision"); synchronize.setAccessible(true); synchronize.invoke(screen);
        var properties = AirportScreen.class.getDeclaredMethod("properties", AirportPlannerModel.Draft.class); properties.setAccessible(true);
        properties.invoke(screen, screen.planner().draft().orElseThrow());
        Field fields = AirportScreen.class.getDeclaredField("precisionFields"); fields.setAccessible(true);
        var translations = new HashSet<String>(); collectTranslations(content, translations);
        return new Inspector((Map<?, ?>) fields.get(screen), translations);
    }
    private static void collectTranslations(UIElement element, Set<String> keys) {
        if (element instanceof Label label && label.getValue().getContents() instanceof TranslatableContents translated)
            keys.add(translated.getKey());
        if (element instanceof TextElement text && text.getText().getContents() instanceof TranslatableContents translated)
            keys.add(translated.getKey());
        element.getChildren().forEach(child -> collectTranslations(child, keys));
    }
}
