package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.monpai.sailboatmod.client.MarketClientHooks;
import com.monpai.sailboatmod.client.MarketOverviewConsumer;
import com.monpai.sailboatmod.client.ui.kit.ThemedUi;
import com.monpai.sailboatmod.client.ui.market.MarketController;
import com.monpai.sailboatmod.client.ui.market.MarketText;
import com.monpai.sailboatmod.client.ui.market.MarketView;
import com.monpai.sailboatmod.client.ui.market.MarketViewModel;
import com.monpai.sailboatmod.client.ui.theme.UiPreferences;
import com.monpai.sailboatmod.client.ui.theme.UiTheme;
import com.monpai.sailboatmod.client.ui.theme.ThemeSubscription;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.menu.MarketMenu;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Market terminal screen, hosting the LDLib2 {@link MarketView}.
 *
 * <p>It stays a vanilla {@link Screen} implementing {@link MenuAccess} so the server-side {@link MarketMenu} keeps its
 * identity, distance check ({@code stillValid}) and close handling; LDLib2's container screen would require a
 * different menu type. The screen only does lifecycle work: opening and closing the container, forwarding server
 * data and notices from {@link MarketClientHooks}, the Escape ladder, ticking timeouts and switching themes.</p>
 */
public class MarketScreen extends Screen implements MenuAccess<MarketMenu>, MarketOverviewConsumer {
    private final MarketMenu menu;
    private final MarketViewModel model;
    private final MarketController controller;
    private final ThemeSubscription themeSubscription = new ThemeSubscription(UiPreferences::get, this::applyTheme);

    private MarketView view;
    private ModularUI modularUI;
    private long ticks;
    private boolean initialRefreshSent;
    private boolean closingContainer;
    private Component pendingNotice;
    private boolean pendingNoticePositive;

    public MarketScreen(MarketMenu menu, Inventory inventory, Component title) {
        super(title);
        this.menu = menu;
        MarketOverviewData initial = MarketClientHooks.consumeFor(menu.getMarketPos());
        this.model = new MarketViewModel(initial != null ? initial : MarketViewModel.placeholder(menu.getMarketPos()));
        this.controller = new MarketController(model, ModNetwork.CHANNEL::sendToServer, () -> ticks);
        MarketClientHooks.MarketNotice notice = MarketClientHooks.consumeNoticeFor(menu.getMarketPos());
        if (notice != null) {
            pendingNotice = Component.literal(notice.message());
            pendingNoticePositive = notice.positive();
        }
    }

    @Override
    protected void init() {
        if (modularUI == null) {
            view = new MarketView(controller, new MarketView.Host() {
                @Override
                public void close() {
                    onClose();
                }

                @Override
                public void toggleTheme() {
                    UiPreferences.get().toggleTheme();
                }

                @Override
                public UiTheme theme() {
                    return UiPreferences.get().theme();
                }

                @Override
                public long tick() {
                    return ticks;
                }
            });
            modularUI = ThemedUi.create(view.root(), UiPreferences.get().theme());
        }
        themeSubscription.mount();
        // Re-initialising on resize keeps the same element tree, so selections, typed text and scroll survive.
        modularUI.setScreenAndInit(this);
        addRenderableWidget(modularUI.getWidget());
        setFocused(modularUI.getWidget());
        if (pendingNotice != null) {
            view.showToast(pendingNotice, pendingNoticePositive);
            pendingNotice = null;
        }
        if (!initialRefreshSent) {
            controller.refresh();
            initialRefreshSent = true;
        }
    }

    // ---------------------------------------------------------------- MarketClientHooks entry points

    @Override
    public MarketMenu getMenu() {
        return menu;
    }

    @Override
    public boolean isForMarket(BlockPos pos) {
        return model.marketPos().equals(pos);
    }

    @Override
    public void updateData(MarketOverviewData updated) {
        controller.onDataUpdated(updated);
        MarketClientHooks.MarketNotice notice = MarketClientHooks.consumeNoticeFor(menu.getMarketPos());
        if (view != null) {
            if (notice != null) {
                view.showToast(Component.literal(notice.message()), notice.positive());
            }
            view.refresh();
        }
    }

    /** Server notice for this market (success or refusal); also answers the action that caused it. */
    public void showNotice(String message, boolean positive) {
        controller.onNotice();
        if (view != null) {
            view.showToast(Component.literal(message == null ? "" : message), positive);
            view.refresh();
        } else {
            pendingNotice = Component.literal(message == null ? "" : message);
            pendingNoticePositive = positive;
        }
    }

    /** Delivery-mode reachability for the open purchase dialog; replies for other listings are ignored. */
    public void onProbeResult(String listingId, boolean sellerShip, boolean autoPickup, boolean realPickup) {
        if (model.applyProbeResult(listingId, sellerShip, autoPickup, realPickup) && view != null) {
            view.refresh();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        if (!menu.stillValid(minecraft.player)) {
            onClose();
            return;
        }
        controller.pollTimedOut().ifPresent(key -> {
            view.showToast(MarketText.ui("no_reply"), false);
            view.refresh();
        });
        view.tick();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && modularUI != null) {
            // Escape steps back: leave a text field, close a dropdown, close the dialog, go up one view, then close.
            UIElement focused = modularUI.getFocusedElement();
            if (focused != null && focused.isTextInput()) {
                modularUI.clearFocus();
                return true;
            }
            if (view.closeOpenSelector()) {
                return true;
            }
            if (model.escape() == MarketViewModel.EscapeResult.HANDLED) {
                view.refresh();
                return true;
            }
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (!closingContainer && minecraft != null && minecraft.player != null && minecraft.player.containerMenu == menu) {
            closingContainer = true;
            try {
                minecraft.player.closeContainer();
            } finally {
                closingContainer = false;
            }
            return;
        }
        super.onClose();
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
            view.refresh();
        }
    }

    // ---------------------------------------------------------------- test hooks kept from the previous screen

    static List<MarketOverviewData.DispatchOption> dispatchOptionsForPanelForTest(MarketOverviewData.OrderEntry order,
                                                                                  List<MarketOverviewData.DispatchOption> availableDispatchOptions) {
        return MarketViewModel.dispatchOptionsFor(order, availableDispatchOptions);
    }

    static String dispatchCandidateSubtitleForTest(String carrierName, String routeName, String fallback) {
        return MarketText.dispatchCandidateSubtitle(carrierName, routeName, fallback);
    }
}
