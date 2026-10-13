package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.monpai.sailboatmod.client.ui.theme.ThemeSubscription;
import com.monpai.sailboatmod.client.ui.theme.UiPreferences;
import com.monpai.sailboatmod.client.ui.theme.UiTheme;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Base for SailboatMod screens that are not containers (dialogs, vehicle panels, nation and town pages).
 *
 * <p>The element tree is built once, on the first {@link #init()}, and kept across resizes so typed text, selections
 * and scroll positions survive. The window is centred and sized by its content, limited to the screen. While a text
 * field has focus every key goes to it (the inventory key does not close the screen) and Escape only leaves the
 * field; otherwise Escape calls {@link #onEscape()}.</p>
 */
public abstract class ThemedScreen extends Screen {
    private final ThemeSubscription themeSubscription = new ThemeSubscription(UiPreferences::get, this::applyTheme);
    protected final Toast toast = new Toast();
    protected Label titleLabel;
    private ModularUI modularUI;
    private UIElement windowElement;
    private long ticks;

    protected ThemedScreen(Component title) {
        super(title);
    }

    /** Builds the window body once. */
    protected abstract UIElement buildBody();

    /** Chips or buttons shown in the header; built before the body so its refresh can update them. */
    protected UIElement[] headerExtras() {
        return new UIElement[0];
    }

    /** Fixed window width, or 0 to size by content. */
    protected float windowWidth() {
        return 0;
    }

    /** Called after every (re)initialisation, once the UI is mounted. */
    protected void afterInit() {
    }

    /** Per-tick hook (toasts are handled here already). */
    protected void onUiTick() {
    }

    /** Escape outside a text field; closes the screen unless a subclass steps back first. */
    protected void onEscape() {
        onClose();
    }

    protected long ticks() {
        return ticks;
    }

    protected ModularUI modularUI() {
        return modularUI;
    }

    @Override
    protected void init() {
        if (modularUI == null) {
            UIElement[] extras = headerExtras();
            WindowFrame frame = WindowFrame.build(title, buildBody(), toast, this::onClose, extras);
            titleLabel = frame.title;
            windowElement = frame.window;
            float fixedWidth = windowWidth();
            if (fixedWidth > 0) {
                windowElement.layout(l -> l.width(fixedWidth));
            }
            modularUI = ThemedUi.createWindow(windowElement, UiPreferences.get().theme());
        }
        themeSubscription.mount();
        float maxHeight = Math.max(120, height - 8);
        float maxWidth = Math.max(160, width - 8);
        windowElement.layout(l -> l.maxHeight(maxHeight).maxWidth(maxWidth));
        float preferredWidth = windowWidth();
        if (preferredWidth > 0) {
            windowElement.layout(l -> l.width(Math.min(preferredWidth, maxWidth)));
        }
        modularUI.setScreenAndInit(this);
        addRenderableWidget(modularUI.getWidget());
        setFocused(modularUI.getWidget());
        afterInit();
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        toast.tick(ticks);
        onUiTick();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (modularUI != null) {
            UIElement focused = modularUI.getFocusedElement();
            if (focused != null && focused.isTextInput()) {
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    modularUI.clearFocus();
                    return true;
                }
                if (onTextFieldKey(focused, keyCode)) {
                    return true;
                }
                modularUI.getWidget().keyPressed(keyCode, scanCode, modifiers);
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onEscape();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Lets a screen handle Enter in its text fields (submit); return true when handled. */
    protected boolean onTextFieldKey(UIElement field, int keyCode) {
        return false;
    }

    protected static boolean isEnter(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
    }

    @Override
    public void removed() {
        themeSubscription.unmount();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void applyTheme(UiTheme theme) {
        if (modularUI != null) {
            ThemedUi.apply(modularUI, theme);
            onThemeChanged(theme);
        }
    }

    /** For screens that draw theme colours themselves (charts, maps). */
    protected void onThemeChanged(UiTheme theme) {
    }
}
