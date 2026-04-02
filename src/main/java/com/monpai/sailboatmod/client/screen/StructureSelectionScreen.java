package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager.StructureType;
import com.monpai.sailboatmod.registry.ModBlocks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class StructureSelectionScreen extends Screen {
    private static final int SCREEN_W = 392;
    private static final int SCREEN_H = 252;
    private static final int HEADER_H = 32;
    private static final int LIST_X = 14;
    private static final int LIST_Y = 44;
    private static final int LIST_W = 156;
    private static final int LIST_H = 156;
    private static final int ROW_H = 24;
    private static final int VISIBLE_ROWS = 6;
    private static final int DETAIL_X = 180;
    private static final int DETAIL_Y = 44;
    private static final int DETAIL_W = 198;
    private static final int DETAIL_H = 156;
    private static final int FOOTER_Y = 208;

    private final ItemStack constructorStack;
    private int selectedIndex;
    private int scrollOffset;

    public StructureSelectionScreen(ItemStack constructorStack) {
        super(Component.translatable("screen.sailboatmod.structure_selection"));
        this.constructorStack = constructorStack;
        this.selectedIndex = BankConstructorItem.getSelectedIndex(constructorStack);
    }

    @Override
    protected void init() {
        clampSelection();
        ensureSelectionVisible();

        int left = left();
        int top = top();
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + SCREEN_W - 136, top + SCREEN_H - 24, 58, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> saveAndClose())
                .bounds(left + SCREEN_W - 72, top + SCREEN_H - 24, 58, 18).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);

        int left = left();
        int top = top();
        int right = left + SCREEN_W;
        int bottom = top + SCREEN_H;

        g.fill(left, top, right, bottom, 0xD0101822);
        g.fill(left + 1, top + 1, right - 1, bottom - 1, 0xE4172531);
        g.fill(left + 2, top + 2, right - 2, top + HEADER_H, 0xE12A3948);
        g.fill(left + 2, top + HEADER_H, right - 2, bottom - 28, 0xD0121A23);
        g.fill(left + 2, top + FOOTER_Y, right - 2, bottom - 28, 0xCC0F151D);

        g.drawString(this.font, this.title, left + 14, top + 11, 0xFFF1D98A);
        g.drawString(this.font, Component.literal("Preview the blueprint before placement and choose a build target."),
                left + 14, top + 22, 0xFF91A4B7);

        renderListPanel(g, mouseX, mouseY);
        renderDetailPanel(g);
        renderFooter(g);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderListPanel(GuiGraphics g, int mouseX, int mouseY) {
        int x = left() + LIST_X;
        int y = top() + LIST_Y;
        int x2 = x + LIST_W;
        int y2 = y + LIST_H;

        g.fill(x, y, x2, y2, 0xAA10171E);
        g.fill(x + 1, y + 1, x2 - 1, y2 - 1, 0x99222F3B);
        g.drawString(this.font, Component.literal("Structure Catalog"), x + 8, y - 12, 0xFFBFD1E2);

        int start = clampScroll(this.scrollOffset);
        int end = Math.min(start + VISIBLE_ROWS, StructureType.ALL.size());
        for (int index = start; index < end; index++) {
            int row = index - start;
            int rowY = y + 4 + row * ROW_H;
            StructureType type = StructureType.ALL.get(index);
            boolean selected = index == this.selectedIndex;
            boolean hovered = isMouseOverRow(mouseX, mouseY, rowY);

            int background = selected ? 0xCC31546C : hovered ? 0x66354657 : 0x44212D39;
            g.fill(x + 4, rowY, x2 - 10, rowY + ROW_H - 2, background);
            if (selected) {
                g.fill(x + 4, rowY, x + 7, rowY + ROW_H - 2, 0xFFF1D98A);
            }

            ItemStack icon = iconFor(type);
            if (!icon.isEmpty()) {
                g.renderItem(icon, x + 10, rowY + 3);
            }

            g.drawString(this.font, Component.translatable(type.translationKey()), x + 30, rowY + 5,
                    selected ? 0xFFF7F3D0 : 0xFFD8E4EF);
            g.drawString(this.font, Component.literal(type.w() + "x" + type.h() + "x" + type.d()), x + 30, rowY + 14,
                    0xFF91A4B7);
            g.drawString(this.font, Component.literal(sizeLabel(type)), x2 - 54, rowY + 9, 0xFFE7C977);
        }

        renderScrollBar(g, x2 - 7, y + 4, LIST_H - 8, StructureType.ALL.size(), VISIBLE_ROWS, start);
    }

    private void renderDetailPanel(GuiGraphics g) {
        StructureType type = selectedType();
        int x = left() + DETAIL_X;
        int y = top() + DETAIL_Y;
        int x2 = x + DETAIL_W;
        int y2 = y + DETAIL_H;

        g.fill(x, y, x2, y2, 0xAA10171E);
        g.fill(x + 1, y + 1, x2 - 1, y2 - 1, 0x9924303D);

        g.drawString(this.font, Component.translatable(type.translationKey()), x + 12, y + 10, 0xFFF5E7AF);
        g.drawString(this.font, Component.literal(roleLabel(type)), x + 12, y + 22, 0xFF8CB5C1);

        renderHeroIcon(g, type, x + DETAIL_W - 42, y + 10);
        renderMetricChip(g, x + 12, y + 38, 50, "Width", Integer.toString(type.w()));
        renderMetricChip(g, x + 68, y + 38, 50, "Height", Integer.toString(type.h()));
        renderMetricChip(g, x + 124, y + 38, 50, "Depth", Integer.toString(type.d()));

        renderFootprintPreview(g, type, x + 12, y + 68, 90, 72);
        renderHeightPreview(g, type, x + 112, y + 68, 20, 72);

        drawWrappedText(g, Component.literal(description(type)), x + 140, y + 70, 48, 0xFFD5E0E9);
        g.drawString(this.font, Component.literal("Placement"), x + 12, y + 146, 0xFFE7C977);
        g.drawString(this.font, Component.literal("Preview first, then confirm with Done."), x + 68, y + 146, 0xFFB4C2CD);
    }

    private void renderFooter(GuiGraphics g) {
        int x = left() + 14;
        int y = top() + FOOTER_Y + 8;
        g.drawString(this.font, Component.literal("Controls"), x, y, 0xFFF1D98A);
        g.drawString(this.font, Component.literal("Click: select   Wheel: scroll list   Up/Down: change   Enter: confirm"),
                x + 54, y, 0xFFB9C6D2);
        g.drawString(this.font, Component.literal("Selected blueprint updates the constructor item when you press Done."),
                x, y + 12, 0xFF8798A7);
    }

    private void renderMetricChip(GuiGraphics g, int x, int y, int w, String label, String value) {
        g.fill(x, y, x + w, y + 18, 0x5532404D);
        g.drawCenteredString(this.font, Component.literal(label), x + w / 2, y + 3, 0xFF96A8B7);
        g.drawCenteredString(this.font, Component.literal(value), x + w / 2, y + 10, 0xFFF4E2A0);
    }

    private void renderFootprintPreview(GuiGraphics g, StructureType type, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0x66111A22);
        g.drawString(this.font, Component.literal("Footprint"), x + 4, y + 4, 0xFFBFD1E2);

        int previewX = x + 12;
        int previewY = y + 18;
        int previewW = w - 24;
        int previewH = h - 28;
        g.fill(previewX, previewY, previewX + previewW, previewY + previewH, 0x4430404E);

        float scale = Math.min(previewW / (float) type.w(), previewH / (float) type.d());
        int rectW = Math.max(14, Mth.floor(type.w() * scale));
        int rectH = Math.max(14, Mth.floor(type.d() * scale));
        int rectX = previewX + (previewW - rectW) / 2;
        int rectY = previewY + (previewH - rectH) / 2;
        g.fill(rectX, rectY, rectX + rectW, rectY + rectH, 0xAA4E87A0);
        g.fill(rectX, rectY + rectH - 3, rectX + rectW, rectY + rectH, 0xFFF1D98A);
        g.drawCenteredString(this.font, Component.literal(type.w() + " x " + type.d()), previewX + previewW / 2, previewY + previewH / 2 - 4, 0xFFF6F8FB);
    }

    private void renderHeightPreview(GuiGraphics g, StructureType type, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0x66111A22);
        int barBottom = y + h - 10;
        int maxHeight = 14;
        int barHeight = Math.max(12, Mth.floor((h - 22) * (type.h() / (float) maxHeight)));
        g.fill(x + 6, barBottom - barHeight, x + w - 6, barBottom, 0xAA6E8D9D);
        g.fill(x + 6, barBottom - barHeight, x + w - 6, barBottom - barHeight + 2, 0xFFF1D98A);
        g.drawCenteredString(this.font, Component.literal(Integer.toString(type.h())), x + w / 2, y + 4, 0xFFE7C977);
    }

    private void renderHeroIcon(GuiGraphics g, StructureType type, int x, int y) {
        ItemStack stack = iconFor(type);
        if (stack.isEmpty()) {
            return;
        }
        g.fill(x - 2, y - 2, x + 34, y + 34, 0x55384654);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(2.0F, 2.0F, 1.0F);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }

    private void renderScrollBar(GuiGraphics g, int x, int y, int h, int total, int visible, int scroll) {
        g.fill(x, y, x + 3, y + h, 0x5524323F);
        if (total <= visible) {
            g.fill(x, y, x + 3, y + h, 0xAA6C8598);
            return;
        }
        int maxScroll = Math.max(1, total - visible);
        int thumbH = Math.max(18, h * visible / total);
        int thumbY = y + (h - thumbH) * scroll / maxScroll;
        g.fill(x, thumbY, x + 3, thumbY + thumbH, 0xFFF1D98A);
    }

    private void drawWrappedText(GuiGraphics g, Component text, int x, int y, int maxWidth, int color) {
        List<FormattedCharSequence> lines = this.font.split(text, maxWidth);
        int drawY = y;
        for (FormattedCharSequence line : lines) {
            g.drawString(this.font, line, x, drawY, color);
            drawY += 10;
            if (drawY > y + 66) {
                break;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int clicked = rowIndexAt(mouseX, mouseY);
            if (clicked >= 0) {
                this.selectedIndex = clicked;
                ensureSelectionVisible();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int x = left() + LIST_X;
        int y = top() + LIST_Y;
        if (mouseX >= x && mouseX < x + LIST_W && mouseY >= y && mouseY < y + LIST_H) {
            this.scrollOffset = clampScroll(this.scrollOffset - (delta > 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_UP) {
            cycleSelection(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            cycleSelection(1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            cycleSelection(-VISIBLE_ROWS);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            cycleSelection(VISIBLE_ROWS);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            saveAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void cycleSelection(int delta) {
        int size = StructureType.ALL.size();
        if (size <= 0) {
            return;
        }
        this.selectedIndex = Math.floorMod(this.selectedIndex + delta, size);
        ensureSelectionVisible();
    }

    private void saveAndClose() {
        BankConstructorItem.setSelectedIndex(this.constructorStack, this.selectedIndex);
        onClose();
    }

    private void clampSelection() {
        int size = StructureType.ALL.size();
        this.selectedIndex = size <= 0 ? 0 : Mth.clamp(this.selectedIndex, 0, size - 1);
        this.scrollOffset = clampScroll(this.scrollOffset);
    }

    private void ensureSelectionVisible() {
        this.scrollOffset = clampScroll(this.scrollOffset);
        if (this.selectedIndex < this.scrollOffset) {
            this.scrollOffset = this.selectedIndex;
        } else if (this.selectedIndex >= this.scrollOffset + VISIBLE_ROWS) {
            this.scrollOffset = this.selectedIndex - VISIBLE_ROWS + 1;
        }
        this.scrollOffset = clampScroll(this.scrollOffset);
    }

    private int clampScroll(int value) {
        return Mth.clamp(value, 0, Math.max(0, StructureType.ALL.size() - VISIBLE_ROWS));
    }

    private int rowIndexAt(double mouseX, double mouseY) {
        int x = left() + LIST_X;
        int y = top() + LIST_Y + 4;
        if (mouseX < x + 4 || mouseX >= x + LIST_W - 10 || mouseY < y) {
            return -1;
        }
        int row = (int) ((mouseY - y) / ROW_H);
        if (row < 0 || row >= VISIBLE_ROWS) {
            return -1;
        }
        int index = this.scrollOffset + row;
        return index >= 0 && index < StructureType.ALL.size() ? index : -1;
    }

    private boolean isMouseOverRow(int mouseX, int mouseY, int rowY) {
        int x = left() + LIST_X;
        return mouseX >= x + 4 && mouseX < x + LIST_W - 10 && mouseY >= rowY && mouseY < rowY + ROW_H - 2;
    }

    private StructureType selectedType() {
        clampSelection();
        return StructureType.ALL.get(this.selectedIndex);
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

    private String sizeLabel(StructureType type) {
        int volume = type.w() * type.h() * type.d();
        if (volume >= 7000) {
            return "Grand";
        }
        if (volume >= 2500) {
            return "Major";
        }
        if (volume >= 1000) {
            return "Medium";
        }
        return "Small";
    }

    private String roleLabel(StructureType type) {
        return switch (type) {
            case VICTORIAN_BANK -> "Treasury and storage hub";
            case VICTORIAN_TOWN_HALL -> "Town core and management";
            case NATION_CAPITOL -> "Nation center and authority";
            case OPEN_AIR_MARKETPLACE -> "Trade and exchange district";
            case WATERFRONT_DOCK -> "Shipping and route terminal";
            case COTTAGE -> "Basic resident housing";
            case TAVERN -> "Service and leisure building";
            case SCHOOL -> "Education and training";
        };
    }

    private String description(StructureType type) {
        return switch (type) {
            case VICTORIAN_BANK -> "Best for starting treasury operations and enabling blueprint-backed material spending.";
            case VICTORIAN_TOWN_HALL -> "Use this as the first major civic structure for a town center.";
            case NATION_CAPITOL -> "Large ceremonial build suited for national administration and landmark placement.";
            case OPEN_AIR_MARKETPLACE -> "Compact commercial square for orders, listings, and supply movement.";
            case WATERFRONT_DOCK -> "Coastal logistics point for boats, routes, and imported construction goods.";
            case COTTAGE -> "Low-cost housing choice with a small footprint and fast placement.";
            case TAVERN -> "Useful mid-size public venue that adds a social-service building to the district.";
            case SCHOOL -> "Education-focused building that supports more advanced settlement progression.";
        };
    }

    private int left() {
        return (this.width - SCREEN_W) / 2;
    }

    private int top() {
        return (this.height - SCREEN_H) / 2;
    }
}
