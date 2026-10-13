package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.theme.UiPreferences;
import com.monpai.sailboatmod.client.ui.theme.UiTheme;
import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager.StructureType;
import com.monpai.sailboatmod.registry.ModBlocks;
import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Structure catalog for the constructor item: pick a building on the left, read its size and role on the right,
 * "Done" (or Enter) stores the choice on the held item exactly as before. Arrow keys and Page Up/Down still move the
 * selection.
 */
public class StructureSelectionScreen extends ThemedScreen {
    private static final int PAGE = 6;

    private final ItemStack constructorStack;
    private int selectedIndex;
    private final List<UIElement> rows = new ArrayList<>();
    private ScrollerView list;
    private final UIElement detailSlot = Ui.row();
    private final Label detailName = Ui.text("", "sm-title");
    private final Label detailRole = Ui.text("", "sm-muted");
    private final Label width = Ui.text("", "sm-price");
    private final Label height = Ui.text("", "sm-price");
    private final Label depth = Ui.text("", "sm-price");
    private final Label description = Ui.paragraph(Component.empty());
    private final Footprint footprint = new Footprint();

    public StructureSelectionScreen(ItemStack constructorStack) {
        super(Component.translatable("screen.sailboatmod.structure_selection"));
        this.constructorStack = constructorStack;
        this.selectedIndex = BankConstructorItem.getSelectedIndex(constructorStack);
    }

    @Override
    protected float windowWidth() {
        return 392;
    }

    @Override
    protected UIElement buildBody() {
        clampSelection();
        UIElement body = Ui.column();
        body.addChild(Ui.text(Component.translatable("screen.sailboatmod.structure_selection.subtitle"), "sm-muted"));

        UIElement main = Ui.row();
        main.layout(l -> l.gapAll(Ui.GAP_M).alignItems(AlignItems.STRETCH).height(168));

        UIElement catalog = Ui.column();
        catalog.layout(l -> l.width(160).flexShrink(0).gapAll(Ui.GAP_XS));
        list = Ui.scroller();
        for (int i = 0; i < StructureType.ALL.size(); i++) {
            int index = i;
            StructureType type = StructureType.ALL.get(i);
            UIElement row = Ui.row("sm-row");
            row.layout(l -> l.widthPercent(100).height(24).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).flexShrink(0));
            UIElement names = Ui.column();
            names.layout(l -> l.flexGrow(1).minWidth(0));
            names.addChildren(Ui.text(Component.translatable(type.translationKey())),
                    Ui.text(type.w() + "×" + type.h() + "×" + type.d(), "sm-muted"));
            row.addChildren(Ui.slot(iconFor(type), Ui.SLOT), names, Ui.text(sizeLabel(type), "sm-warn"));
            Ui.clickable(row, () -> select(index));
            rows.add(row);
            list.addScrollViewChild(row);
        }
        catalog.addChildren(Ui.text(Component.translatable("screen.sailboatmod.structure_selection.catalog"), "sm-muted"), list);

        UIElement detail = Ui.column("sm-pane");
        detail.layout(l -> l.flexGrow(1).minWidth(0).paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        UIElement head = Ui.row();
        head.layout(l -> l.gapAll(Ui.GAP_M));
        UIElement names = Ui.column();
        names.layout(l -> l.flexGrow(1).minWidth(0));
        names.addChildren(detailName, detailRole);
        head.addChildren(detailSlot, names);

        UIElement metrics = Ui.row();
        metrics.layout(l -> l.gapAll(Ui.GAP_S));
        metrics.addChildren(metric("width", width), metric("height", height), metric("depth", depth));

        UIElement lower = Ui.row();
        lower.layout(l -> l.gapAll(Ui.GAP_M).alignItems(AlignItems.STRETCH).flexGrow(1).minHeight(0));
        footprint.layout(l -> l.width(96).flexShrink(0));
        description.layout(l -> l.flexGrow(1).minWidth(0));
        lower.addChildren(footprint, description);

        UIElement placement = Ui.row();
        placement.layout(l -> l.gapAll(Ui.GAP_S));
        placement.addChildren(Ui.text(Component.translatable("screen.sailboatmod.structure_selection.placement"), "sm-warn"),
                Ui.text(Component.translatable("screen.sailboatmod.structure_selection.placement_hint"), "sm-muted"));
        detail.addChildren(head, metrics, lower, placement);
        main.addChildren(catalog, detail);

        UIElement footer = Ui.row();
        footer.layout(l -> l.gapAll(Ui.GAP_S));
        UIElement hints = Ui.column();
        hints.layout(l -> l.flexGrow(1).minWidth(0));
        hints.addChildren(Ui.text(Component.translatable("screen.sailboatmod.structure_selection.controls_hint"), "sm-muted"),
                Ui.text(Component.translatable("screen.sailboatmod.structure_selection.controls_hint_2"), "sm-muted"));
        footer.addChildren(hints,
                Ui.button(Component.translatable("gui.cancel"), Ui.Tone.SECONDARY, this::onClose),
                Ui.button(Component.translatable("gui.done"), Ui.Tone.PRIMARY, this::saveAndClose));
        body.addChildren(main, footer);
        refresh();
        return body;
    }

    private static UIElement metric(String key, Label value) {
        UIElement chip = Ui.column("sm-chip");
        chip.layout(l -> l.height(26).flexGrow(1).flexBasis(0).alignItems(AlignItems.CENTER).paddingVertical(Ui.GAP_XS));
        chip.addChildren(Ui.text(Component.translatable("screen.sailboatmod.structure_selection.metric." + key), "sm-muted"), value);
        return chip;
    }

    private void select(int index) {
        selectedIndex = index;
        refresh();
    }

    private void refresh() {
        clampSelection();
        for (int i = 0; i < rows.size(); i++) {
            Ui.setClass(rows.get(i), "selected", i == selectedIndex);
        }
        StructureType type = StructureType.ALL.get(selectedIndex);
        detailSlot.clearAllChildren();
        detailSlot.addChild(Ui.slot(iconFor(type), 36));
        detailName.setValue(Component.translatable(type.translationKey()));
        detailRole.setValue(roleLabel(type));
        width.setValue(Component.literal(Integer.toString(type.w())));
        height.setValue(Component.literal(Integer.toString(type.h())));
        depth.setValue(Component.literal(Integer.toString(type.d())));
        description.setValue(description(type));
        footprint.type = type;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> cycleSelection(-1);
            case GLFW.GLFW_KEY_DOWN -> cycleSelection(1);
            case GLFW.GLFW_KEY_PAGE_UP -> cycleSelection(-PAGE);
            case GLFW.GLFW_KEY_PAGE_DOWN -> cycleSelection(PAGE);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> saveAndClose();
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    private void cycleSelection(int delta) {
        int size = StructureType.ALL.size();
        if (size <= 0) {
            return;
        }
        selectedIndex = Math.floorMod(selectedIndex + delta, size);
        refresh();
        list.scrollToChild(rows.get(selectedIndex));
    }

    private void saveAndClose() {
        BankConstructorItem.setSelectedIndex(this.constructorStack, this.selectedIndex);
        com.monpai.sailboatmod.client.ConstructorClientHooks.syncHeldSettings(this.constructorStack);
        onClose();
    }

    private void clampSelection() {
        int size = StructureType.ALL.size();
        this.selectedIndex = size <= 0 ? 0 : Mth.clamp(this.selectedIndex, 0, size - 1);
    }

    /** Top-down footprint with the front edge marked, beside a height bar. */
    private static final class Footprint extends UIElement {
        private StructureType type;

        Footprint() {
            addClass("sm-chart");
        }

        @Override
        public void drawContents(GUIContext context) {
            super.drawContents(context);
            if (type == null) {
                return;
            }
            boolean ledger = UiPreferences.get().theme() == UiTheme.LEDGER;
            int fill = ledger ? 0xAA8B7146 : 0xAA4E87A0;
            int edge = ledger ? 0xFF9B2C24 : 0xFF34D399;
            int text = ledger ? 0xFF2B1D10 : 0xFFF6F8FB;
            GuiGraphics g = context.graphics;
            int x = Math.round(getContentX()) + 4;
            int y = Math.round(getContentY()) + 4;
            int w = Math.round(getContentWidth()) - 8;
            int h = Math.round(getContentHeight()) - 8;
            int barW = 12;
            int areaW = w - barW - 6;
            if (areaW < 16 || h < 16) {
                return;
            }
            float scale = Math.min(areaW / (float) type.w(), h / (float) type.d());
            int rectW = Math.max(14, Mth.floor(type.w() * scale));
            int rectH = Math.max(14, Mth.floor(type.d() * scale));
            int rx = x + (areaW - rectW) / 2;
            int ry = y + (h - rectH) / 2;
            g.fill(rx, ry, rx + rectW, ry + rectH, fill);
            g.fill(rx, ry + rectH - 2, rx + rectW, ry + rectH, edge);
            var font = Minecraft.getInstance().font;
            String size = type.w() + "×" + type.d();
            g.drawString(font, size, rx + (rectW - font.width(size)) / 2, ry + rectH / 2 - 4, text, false);

            int barX = x + areaW + 6;
            int barHeight = Math.max(8, Mth.floor((h - 10) * Math.min(1f, type.h() / 14f)));
            g.fill(barX, y + h - barHeight, barX + barW, y + h, fill);
            g.fill(barX, y + h - barHeight, barX + barW, y + h - barHeight + 2, edge);
        }
    }

    private ItemStack iconFor(StructureType type) {
        return switch (type) {
            case VICTORIAN_BANK -> new ItemStack(ModBlocks.BANK_BLOCK.get());
            case VICTORIAN_TOWN_HALL -> new ItemStack(ModBlocks.TOWN_CORE_BLOCK.get());
            case NATION_CAPITOL -> new ItemStack(ModBlocks.NATION_CORE_BLOCK.get());
            case OPEN_AIR_MARKETPLACE -> new ItemStack(ModBlocks.MARKET_BLOCK.get());
            case WATERFRONT_DOCK -> new ItemStack(ModBlocks.DOCK_BLOCK.get());
            case COTTAGE -> new ItemStack(ModBlocks.COTTAGE_BLOCK.get());
            case TAVERN -> new ItemStack(ModBlocks.BAR_BLOCK.get());
            case SCHOOL -> new ItemStack(ModBlocks.SCHOOL_BLOCK.get());
        };
    }

    private Component sizeLabel(StructureType type) {
        int volume = type.w() * type.h() * type.d();
        if (volume >= 7000) {
            return Component.translatable("screen.sailboatmod.structure_selection.size.grand");
        }
        if (volume >= 2500) {
            return Component.translatable("screen.sailboatmod.structure_selection.size.major");
        }
        if (volume >= 1000) {
            return Component.translatable("screen.sailboatmod.structure_selection.size.medium");
        }
        return Component.translatable("screen.sailboatmod.structure_selection.size.small");
    }

    private Component roleLabel(StructureType type) {
        return Component.translatable("screen.sailboatmod.structure_selection.role." + typeKey(type));
    }

    private Component description(StructureType type) {
        return Component.translatable("screen.sailboatmod.structure_selection.description." + typeKey(type));
    }

    private static String typeKey(StructureType type) {
        return switch (type) {
            case VICTORIAN_BANK -> "victorian_bank";
            case VICTORIAN_TOWN_HALL -> "victorian_town_hall";
            case NATION_CAPITOL -> "nation_capitol";
            case OPEN_AIR_MARKETPLACE -> "open_air_marketplace";
            case WATERFRONT_DOCK -> "waterfront_dock";
            case COTTAGE -> "cottage";
            case TAVERN -> "tavern";
            case SCHOOL -> "school";
        };
    }
}
