package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.layout.LayoutProperties;
import com.monpai.sailboatmod.client.ui.theme.ThemeSubscription;
import com.monpai.sailboatmod.client.ui.theme.UiPreferences;
import com.monpai.sailboatmod.client.ui.theme.UiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Base for container screens (warehouse, bank, dock, post station) drawn with LDLib2 over the existing menu.
 *
 * <p>The menu is unchanged: slot indexes, {@code quickMoveStack}, {@code mayPlace}/{@code mayPickup},
 * {@code stillValid} and close handling stay with vanilla. The UI is attached to the menu on the client
 * ({@link IModularUIHolderMenu}, mixed into every menu by LDLib2); LDLib2 then mounts it when the screen initialises
 * and lets {@link ItemSlot} elements bound to the real {@link Slot}s draw and position them. Clicks, drags and
 * shift-clicks still travel through vanilla's container protocol.</p>
 *
 * <p>The window is sized by its content and centred like a vanilla container, and {@code imageWidth/imageHeight}
 * match it, so recipe viewers still find free space beside it. Every slot of the menu must be bound to an
 * {@link ItemSlot} ({@link #bindAllSlots} checks this), otherwise vanilla would draw it at its old place.</p>
 */
public abstract class ThemedContainerScreen<M extends AbstractContainerMenu> extends AbstractContainerScreen<M> {
    private final ThemeSubscription themeSubscription = new ThemeSubscription(UiPreferences::get, this::applyTheme);
    /** Bottom-of-window message; added by {@link #window}. */
    protected final Toast toast = new Toast();
    /** Title in the window header; screens whose title follows server data (renamed docks) update it. */
    protected Label titleLabel;
    private ModularUI modularUI;
    private UIElement windowElement;
    private float preferredWindowWidth;
    private float preferredWindowHeight;
    private long ticks;

    protected ThemedContainerScreen(M menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    /**
     * Subclasses call this at the end of their constructor with the window built by {@link #window}. It must run
     * before the screen initialises, because LDLib2 mounts the menu's UI during {@code ScreenEvent.Init.Pre}.
     */
    protected final void install(UIElement window) {
        this.windowElement = window;
        var requestedWidth = window.getStyleBag().computeCandidate(LayoutProperties.WIDTH);
        preferredWindowWidth = requestedWidth == null || requestedWidth.isAuto() || requestedWidth.isPercent()
                ? 0 : requestedWidth.getValue();
        var requestedHeight = window.getStyleBag().computeCandidate(LayoutProperties.HEIGHT);
        preferredWindowHeight = requestedHeight == null || requestedHeight.isAuto() || requestedHeight.isPercent()
                ? 0 : requestedHeight.getValue();
        modularUI = ThemedUi.createWindow(window, UiPreferences.get().theme());
        ((IModularUIHolderMenu) (Object) menu).setModularUI(modularUI);
    }

    protected ModularUI modularUI() {
        return modularUI;
    }

    /** Standard window: themed panel with a header (title, theme switch, close) above the given body. */
    protected UIElement window(Component heading, UIElement body, UIElement... headerExtras) {
        WindowFrame frame = WindowFrame.build(heading, body, toast, this::onClose, headerExtras);
        titleLabel = frame.title;
        return frame.window;
    }

    /**
     * A grid of item slots bound to the given menu slots, in menu order. LDLib2 only records slots it adds to the menu
     * itself, so the menu's existing slots are registered here; from then on LDLib2 positions and draws them.
     */
    protected UIElement slotGrid(List<Slot> slots, int columns) {
        IModularUIHolderMenu holder = (IModularUIHolderMenu) (Object) menu;
        UIElement grid = Ui.column();
        grid.layout(l -> l.gapAll(0).flexShrink(0));
        UIElement row = null;
        for (int i = 0; i < slots.size(); i++) {
            if (i % columns == 0) {
                row = Ui.row();
                row.layout(l -> l.gapAll(0));
                grid.addChild(row);
            }
            ItemSlot slot = new ItemSlot();
            slot.bind(slots.get(i));
            holder.ldlib2$addSlot(slot);
            row.addChild(slot);
        }
        return grid;
    }

    /** The player's 27 storage slots above the 9 hotbar slots, given the menu's first player-inventory slot index. */
    protected UIElement playerInventory(int firstIndex) {
        UIElement inventory = Ui.column();
        inventory.layout(l -> l.gapAll(Ui.GAP_S).flexShrink(0));
        inventory.addChild(Ui.text(playerInventoryTitle, "sm-muted"));
        inventory.addChild(slotGrid(menu.slots.subList(firstIndex, firstIndex + 27), 9));
        inventory.addChild(slotGrid(menu.slots.subList(firstIndex + 27, firstIndex + 36), 9));
        return inventory;
    }

    /**
     * Throws if a menu slot was left unbound; called by subclasses after building their UI so a forgotten slot fails
     * loudly in development instead of being drawn twice.
     */
    protected void bindAllSlots() {
        IModularUIHolderMenu holder = (IModularUIHolderMenu) (Object) menu;
        for (Slot slot : menu.slots) {
            if (holder.getItemSlot(slot) == null) {
                throw new IllegalStateException(getClass().getSimpleName() + " left menu slot " + slot.index + " unbound");
            }
        }
    }

    @Override
    protected void init() {
        if (modularUI != null && windowElement != null) {
            themeSubscription.mount();
            // Keep the window on screen at small GUI scales; lists inside shrink and scroll.
            float maxHeight = Math.max(120, height - 8);
            float maxWidth = Math.max(160, width - 8);
            windowElement.layout(l -> l.maxHeight(maxHeight).maxWidth(maxWidth));
            // LDLib2 centres a literal root width before applying max-width; clamp the literal too.
            if (preferredWindowWidth > 0) {
                windowElement.layout(l -> l.width(Math.min(preferredWindowWidth, maxWidth)));
            }
            if (preferredWindowHeight > 0) {
                windowElement.layout(l -> l.height(Math.min(preferredWindowHeight, maxHeight)));
            }
            modularUI.init(width, height);
            imageWidth = Math.round(modularUI.getWidth());
            imageHeight = Math.round(modularUI.getHeight());
        }
        super.init();
        if (modularUI != null) {
            // Same origin as the UI, so vanilla slot coordinates and LDLib2 element coordinates agree.
            leftPos = Math.round(modularUI.getLeftPos());
            topPos = Math.round(modularUI.getTopPos());
            setFocused(modularUI.getWidget());
        }
    }

    /** Client ticks since the screen opened; drives toasts, confirmations and reply timeouts. */
    protected long ticks() {
        return ticks;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        ticks++;
        toast.tick(ticks);
        onUiTick();
    }

    /** Per-tick hook for subclasses (polling client caches, request timeouts). */
    protected void onUiTick() {
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
                // While typing, keys go to the field only: the inventory key must not close the screen.
                modularUI.getWidget().keyPressed(keyCode, scanCode, modifiers);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        // LDLib2 can notify slots before it finalises the centred origin. Resync after layout/render so vanilla
        // hit testing uses the same positions as the visible items, including after resizing or switching pages.
        IModularUIHolderMenu holder = (IModularUIHolderMenu) (Object) menu;
        for (Slot slot : menu.slots) {
            ItemSlot item = holder.getItemSlot(slot);
            if (item != null && item.getSizeWidth() > 0 && item.getSizeHeight() > 0 && isSlotVisible(item)) {
                item.updateSlotPosition();
            }
        }
        renderTooltip(graphics, mouseX, mouseY);
    }

    private static boolean isSlotVisible(UIElement element) {
        for (UIElement current = element; current != null; current = current.getParent()) {
            if (!current.isDisplayed() || !current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // The LDLib2 UI draws the whole window.
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Titles are part of the LDLib2 UI.
    }

    @Override
    public void removed() {
        themeSubscription.unmount();
        super.removed();
    }

    private void applyTheme(UiTheme theme) {
        if (modularUI != null) {
            ThemedUi.apply(modularUI, theme);
        }
    }
}
