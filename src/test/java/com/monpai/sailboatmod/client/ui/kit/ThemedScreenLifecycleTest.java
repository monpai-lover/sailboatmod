package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThemedScreenLifecycleTest {
    @Test
    void firstInitializationBuildsHeaderBeforeTheDependentBody() {
        HeaderDependentScreen screen = new HeaderDependentScreen();

        // Stop after the body callback, before LDLib2 needs a running graphical client.
        assertThrows(BodyBuilt.class, screen::init);

        assertEquals(List.of("header", "body"), screen.buildOrder,
                "home-screen body refreshes need the header labels to exist already");
    }

    private static final class HeaderDependentScreen extends ThemedScreen {
        private final List<String> buildOrder = new ArrayList<>();

        private HeaderDependentScreen() {
            super(Component.literal("Header-dependent screen"));
        }

        @Override
        protected UIElement[] headerExtras() {
            buildOrder.add("header");
            return new UIElement[0];
        }

        @Override
        protected UIElement buildBody() {
            buildOrder.add("body");
            throw new BodyBuilt();
        }
    }

    private static final class BodyBuilt extends RuntimeException {
    }
}
