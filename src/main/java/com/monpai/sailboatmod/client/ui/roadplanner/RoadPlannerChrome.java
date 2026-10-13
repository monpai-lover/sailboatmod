package com.monpai.sailboatmod.client.ui.roadplanner;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapLayout;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTopToolbar;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerVanillaContextMenu;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerVanillaLayout;
import com.monpai.sailboatmod.client.ui.kit.MeterBar;
import com.monpai.sailboatmod.client.ui.kit.ThemedUi;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.theme.ThemeSubscription;
import com.monpai.sailboatmod.client.ui.theme.UiPreferences;
import com.monpai.sailboatmod.client.ui.theme.UiTheme;
import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Themed LDLib2 chrome of the road planner: the map area, the top toolbar with its drop-down groups, the status bar
 * and the right-click menu.
 *
 * <p>The planner's own layout models ({@link RoadPlannerMapLayout}, {@link RoadPlannerTopToolbar},
 * {@link RoadPlannerVanillaContextMenu}) stay the source of truth: every element here is placed at the rectangle
 * those models compute, and clicks keep going through the planner's tested hit-testing. This class only draws, so
 * the toolbar actions, tool switching and menu actions behave exactly as before.</p>
 */
public final class RoadPlannerChrome {
    /** What the chrome reads from the planner each frame. */
    public interface Host {
        void paintMap(GuiGraphics graphics, int mouseX, int mouseY);

        RoadPlannerMapLayout mapLayout();

        RoadPlannerTopToolbar toolbar();

        RoadPlannerTopToolbar.Group expandedGroup();

        RoadToolType activeTool();

        Component statusText();

        int progressPercent();

        RoadPlannerVanillaContextMenu contextMenu();

        void close();
    }

    /** Display names for the planner's internal toolbar labels (the labels themselves stay the action keys). */
    private static final Map<String, String> LABEL_KEYS = Map.ofEntries(
            Map.entry(RoadPlannerTopToolbar.GROUP_TOOLS, "screen.sailboatmod.ui.road.toolbar.tools"),
            Map.entry(RoadPlannerTopToolbar.GROUP_EDIT, "screen.sailboatmod.ui.road.toolbar.edit"),
            Map.entry(RoadPlannerTopToolbar.GROUP_ROUTE, "screen.sailboatmod.ui.road.toolbar.route"),
            Map.entry(RoadPlannerTopToolbar.ACTION_UNDO, "screen.sailboatmod.ui.road.toolbar.undo"),
            Map.entry(RoadPlannerTopToolbar.ACTION_CLEAR, "screen.sailboatmod.ui.road.toolbar.clear"),
            Map.entry(RoadPlannerTopToolbar.ACTION_AUTO_COMPLETE, "screen.sailboatmod.ui.road.toolbar.auto_complete"),
            Map.entry(RoadPlannerTopToolbar.ACTION_PATHFIND_MODE, "screen.sailboatmod.ui.road.toolbar.pathfind_mode"),
            Map.entry(RoadPlannerTopToolbar.ACTION_MERGE_SCOPE, "screen.sailboatmod.ui.road.toolbar.merge_scope"),
            Map.entry(RoadPlannerTopToolbar.ACTION_CONFIRM_MERGE, "screen.sailboatmod.ui.road.toolbar.confirm_merge"),
            Map.entry(RoadPlannerTopToolbar.ACTION_CONFIRM_BUILD, "screen.sailboatmod.ui.road.toolbar.confirm_build"),
            Map.entry(RoadPlannerTopToolbar.ACTION_CANCEL, "screen.sailboatmod.ui.road.toolbar.cancel"),
            Map.entry("选择", "screen.sailboatmod.ui.road.tool.select"),
            Map.entry("端点", "screen.sailboatmod.ui.road.tool.endpoint"),
            Map.entry("道路", "screen.sailboatmod.ui.road.tool.road"),
            Map.entry("桥梁", "screen.sailboatmod.ui.road.tool.bridge"),
            Map.entry("隧道", "screen.sailboatmod.ui.road.tool.tunnel"),
            Map.entry("擦除", "screen.sailboatmod.ui.road.tool.erase"),
            Map.entry("跨水", "screen.sailboatmod.ui.road.tool.water_crossing"),
            Map.entry("贝塞尔", "screen.sailboatmod.ui.road.tool.bezier"),
            Map.entry("强制渲染", "screen.sailboatmod.ui.road.tool.force_render"),
            Map.entry("并入", "screen.sailboatmod.ui.road.tool.merge"));

    private static final int OWN_BUTTON = 18;

    private final Host host;
    private final ThemeSubscription themeSubscription = new ThemeSubscription(UiPreferences::get, this::applyTheme);
    private final UIElement root;
    private final ModularUI modularUI;
    private final UIElement mapElement;
    private final UIElement toolbarPanel;
    private final UIElement toolbarItems;
    private final UIElement statusPanel;
    private final Label statusLabel;
    private final MeterBar progressBar;
    private final UIElement menuPanel;
    private final Button themeButton;
    private final Button closeButton;
    private final List<Button> itemButtons = new ArrayList<>();
    private List<RoadPlannerTopToolbar.Item> shownItems = List.of();
    private RoadPlannerVanillaContextMenu shownMenu;
    private String shownMenuState = "";
    private int screenWidth;
    private final Map<UIElement, RoadPlannerMapLayout.Rect> placed = new java.util.HashMap<>();

    public RoadPlannerChrome(Host host) {
        this.host = host;
        root = new UIElement().addClass("sm-controls");
        root.layout(l -> l.widthPercent(100).heightPercent(100));

        mapElement = new UIElement() {
            @Override
            public void drawContents(GUIContext context) {
                super.drawContents(context);
                host.paintMap(context.graphics, context.mouseX, context.mouseY);
            }
        };
        mapElement.addClass("sm-minimap");

        statusPanel = Ui.row("sm-header");
        statusPanel.layout(l -> l.paddingHorizontal(Ui.GAP_L).gapAll(Ui.GAP_M));
        statusLabel = Ui.text("");
        statusLabel.layout(l -> l.flexGrow(1));
        progressBar = new MeterBar();
        progressBar.layout(l -> l.width(80));
        statusPanel.addChildren(statusLabel, progressBar);

        toolbarPanel = Ui.row("sm-header");
        toolbarItems = new UIElement();
        toolbarItems.layout(l -> l.widthPercent(100).heightPercent(100));
        themeButton = Ui.iconButton(new ItemStack(Items.MAP), Component.translatable("screen.sailboatmod.ui.theme.toggle"), () -> { });
        closeButton = Ui.iconButton(new ItemStack(Items.BARRIER), Component.translatable("screen.sailboatmod.ui.close"), () -> { });

        menuPanel = Ui.column("sm-dialog");
        menuPanel.style(s -> s.zIndex(80));
        menuPanel.setDisplay(false);

        root.addChildren(mapElement, statusPanel, toolbarPanel, toolbarItems, themeButton, closeButton, menuPanel);
        modularUI = ThemedUi.create(root, UiPreferences.get().theme());
    }

    /** Restores theme updates each time the retained planner returns from a child screen. */
    public void mount() {
        themeSubscription.mount();
    }

    public ModularUI modularUI() {
        return modularUI;
    }

    /** Places every element at the planner's layout rectangles; call before each render. */
    public void sync(int width, int height) {
        RoadPlannerMapLayout layout = host.mapLayout();
        if (layout == null) {
            return;
        }
        place(mapElement, layout.map());
        place(statusPanel, layout.statusBar());
        place(toolbarPanel, layout.toolbar());
        place(closeButton, new RoadPlannerMapLayout.Rect(width - OWN_BUTTON - 8, 8, OWN_BUTTON, OWN_BUTTON - 2));
        place(themeButton, new RoadPlannerMapLayout.Rect(width - OWN_BUTTON * 2 - 12, 8, OWN_BUTTON, OWN_BUTTON - 2));
        syncToolbar(width);
        statusLabel.setValue(host.statusText());
        progressBar.set(host.progressPercent() / 100f, 0xFF3CCF7E);
        syncMenu(width, height);
    }

    private void syncToolbar(int width) {
        RoadPlannerTopToolbar toolbar = host.toolbar();
        if (!toolbar.items().equals(shownItems) || width != screenWidth) {
            shownItems = toolbar.items();
            screenWidth = width;
            for (UIElement child : toolbarItems.getChildren()) {
                placed.remove(child);
            }
            toolbarItems.clearAllChildren();
            itemButtons.clear();
            boolean dropdown = host.expandedGroup() != RoadPlannerTopToolbar.Group.NONE;
            if (dropdown) {
                // Panel behind the open group's items.
                int minX = Integer.MAX_VALUE;
                int minY = Integer.MAX_VALUE;
                int maxX = 0;
                int maxY = 0;
                for (RoadPlannerTopToolbar.Item item : toolbar.items()) {
                    if (item.kind() == RoadPlannerTopToolbar.Kind.GROUP) {
                        continue;
                    }
                    minX = Math.min(minX, item.bounds().x());
                    minY = Math.min(minY, item.bounds().y());
                    maxX = Math.max(maxX, item.bounds().right());
                    maxY = Math.max(maxY, item.bounds().bottom());
                }
                if (minX != Integer.MAX_VALUE) {
                    UIElement panel = Ui.column("sm-dialog");
                    place(panel, new RoadPlannerMapLayout.Rect(minX - 2, minY - 2, maxX - minX + 4, maxY - minY + 4));
                    toolbarItems.addChild(panel);
                }
            }
            for (RoadPlannerTopToolbar.Item item : toolbar.items()) {
                Button button = new Button();
                String key = LABEL_KEYS.get(item.label());
                button.setText(key == null ? Component.literal(item.label()) : Component.translatable(key));
                if (item.kind() == RoadPlannerTopToolbar.Kind.GROUP) {
                    button.addClass("sm-nav-tab");
                }
                place(button, item.bounds());
                itemButtons.add(button);
                toolbarItems.addChild(button);
            }
        }
        for (int i = 0; i < shownItems.size() && i < itemButtons.size(); i++) {
            RoadPlannerTopToolbar.Item item = shownItems.get(i);
            Button button = itemButtons.get(i);
            boolean active = item.kind() == RoadPlannerTopToolbar.Kind.TOOL && item.toolType() == host.activeTool();
            Ui.setClass(button, item.kind() == RoadPlannerTopToolbar.Kind.GROUP ? "active" : "sm-primary",
                    item.kind() == RoadPlannerTopToolbar.Kind.GROUP ? item.group() == host.expandedGroup() : active);
        }
    }

    private void syncMenu(int width, int height) {
        RoadPlannerVanillaContextMenu menu = host.contextMenu();
        boolean open = menu != null && menu.isOpen();
        menuPanel.setDisplay(open);
        if (!open) {
            shownMenu = null;
            return;
        }
        menu.layout(width, height, text -> net.minecraft.client.Minecraft.getInstance().font.width(text));
        StringBuilder state = new StringBuilder();
        List<RoadPlannerVanillaContextMenu.Entry> entries = menu.entries();
        for (RoadPlannerVanillaContextMenu.Entry entry : entries) {
            state.append(entry.label()).append(entry.enabled()).append(entry.hovered()).append(';');
        }
        RoadPlannerVanillaLayout.Rect bounds = menu.bounds();
        place(menuPanel, new RoadPlannerMapLayout.Rect(bounds.x(), bounds.y(), bounds.width(), bounds.height()));
        if (menu == shownMenu && state.toString().equals(shownMenuState)) {
            return;
        }
        shownMenu = menu;
        shownMenuState = state.toString();
        menuPanel.clearAllChildren();
        menuPanel.layout(l -> l.paddingVertical(RoadPlannerVanillaContextMenu.PADDING));
        for (RoadPlannerVanillaContextMenu.Entry entry : entries) {
            if (entry.divider()) {
                UIElement divider = Ui.divider();
                divider.layout(l -> l.marginVertical((RoadPlannerVanillaContextMenu.SEPARATOR_HEIGHT - 1) / 2f).marginHorizontal(4));
                menuPanel.addChild(divider);
                continue;
            }
            UIElement row = Ui.row();
            row.layout(l -> l.height(RoadPlannerVanillaContextMenu.ITEM_HEIGHT).paddingHorizontal(RoadPlannerVanillaContextMenu.PADDING).flexShrink(0));
            if (entry.hovered() && entry.enabled()) {
                row.addClasses("sm-row", "selected");
            }
            row.addChild(entry.enabled() ? Ui.text(entry.label()) : Ui.text(entry.label(), "sm-muted"));
            menuPanel.addChild(row);
        }
    }

    /** The chrome's own buttons (theme, close); returns true when the click was one of them. */
    public boolean handleClick(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (contains(themeButton, mouseX, mouseY)) {
            UiPreferences.get().toggleTheme();
            return true;
        }
        if (contains(closeButton, mouseX, mouseY)) {
            host.close();
            return true;
        }
        return false;
    }

    public void removed() {
        themeSubscription.unmount();
    }

    private boolean contains(UIElement element, double x, double y) {
        RoadPlannerMapLayout.Rect rect = placed.get(element);
        return rect != null && x >= rect.x() && x < rect.right() && y >= rect.y() && y < rect.bottom();
    }

    /** Absolute placement; only touches the layout when the rectangle changed, so frames stay cheap. */
    private void place(UIElement element, RoadPlannerMapLayout.Rect rect) {
        if (rect.equals(placed.get(element))) {
            return;
        }
        placed.put(element, rect);
        element.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(rect.x()).top(rect.y()).width(rect.width()).height(rect.height()));
    }

    private void applyTheme(UiTheme theme) {
        ThemedUi.apply(modularUI, theme);
    }
}
