package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.style.StyleEngine;
import com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.ui.theme.UiTheme;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Creates full-screen ModularUIs styled by a SailboatMod theme and switches themes on an open UI.
 *
 * <p>Stylesheets stack: LDLib2's built-in Minecraft sheet styles every internal element (scrollbars, dropdowns,
 * text cursors), then the theme sheet ({@code assets/sailboatmod/lss/<theme>.lss}) overrides colours and textures.
 * Switching swaps the sheets on the live UI, so no element is rebuilt and nothing the player typed is lost.</p>
 */
public final class ThemedUi {
    private ThemedUi() {
    }

    public static List<Stylesheet> stylesheets(UiTheme theme) {
        StylesheetManager manager = StylesheetManager.INSTANCE;
        return List.of(
                manager.getStylesheetSafe(StylesheetManager.MC),
                manager.getStylesheetSafe(ResourceLocation.fromNamespaceAndPath(SailboatMod.MODID, theme.stylesheetPath())));
    }

    /** A UI that always fills the screen, whatever the window size and GUI scale. */
    public static ModularUI create(UIElement root, UiTheme theme) {
        return ModularUI.of(UI.of(root, stylesheets(theme), screenSize -> screenSize));
    }

    /**
     * A UI sized by its own content and centred on the screen, like a vanilla container window. Used for container
     * screens so mods that place panels beside the window (recipe viewers) keep their space.
     */
    public static ModularUI createWindow(UIElement window, UiTheme theme) {
        return ModularUI.of(UI.of(window, stylesheets(theme)));
    }

    public static void apply(ModularUI modularUI, UiTheme theme) {
        StyleEngine engine = modularUI.getStyleEngine();
        engine.clearAllStylesheets();
        stylesheets(theme).forEach(engine::addStylesheet);
    }
}
