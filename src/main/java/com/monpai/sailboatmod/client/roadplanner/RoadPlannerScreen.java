package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

public class RoadPlannerScreen extends Screen {
    private static final int BG = 0xB0050C18;
    private static final int PANEL = 0xEE0D1626;
    private static final int PANEL_2 = 0xEE121E32;
    private static final int TEXT = 0xFFE5E7EB;
    private static final int MUTED = 0xFF94A3B8;
    private static final int ACCENT = 0xFF34D399;

    private static final List<RoadToolType> TOOLS = List.of(
            RoadToolType.ROAD,
            RoadToolType.BRIDGE,
            RoadToolType.TUNNEL,
            RoadToolType.ERASE,
            RoadToolType.SELECT
    );
    private static final List<String> TOOL_LABELS = List.of("道路", "桥梁", "隧道", "擦除", "选择");
    private static final List<String> ACTION_LABELS = List.of("上一阶段", "下一阶段", "撤销节点", "清除区域", "自动补全", "确认建造", "取消");

    private RoadPlannerClientState state;
    private RoadPlannerVanillaLayout layout;
    private RoadPlannerMapCanvas canvas;
    private RoadPlannerVanillaContextMenu contextMenu;
    private RoadNetworkGraph graph = new RoadNetworkGraph();
    private String statusLine = "拖动画线路径节点；右键已建道路打开 RoadWeaver 风格编辑菜单";
    private final boolean testMode;

    public RoadPlannerScreen(UUID sessionId) {
        this(sessionId, false);
    }

    private RoadPlannerScreen(UUID sessionId, boolean testMode) {
        super(Component.literal("RoadPlanner"));
        this.state = RoadPlannerClientState.open(sessionId);
        this.testMode = testMode;
    }

    public static RoadPlannerScreen forTest(UUID sessionId, int width, int height) {
        RoadPlannerScreen screen = new RoadPlannerScreen(sessionId, true);
        screen.width = width;
        screen.height = height;
        screen.recomputeLayout();
        return screen;
    }

    public RoadPlannerClientState state() {
        return state;
    }

    public RoadPlannerVanillaLayout layoutForTest() {
        return layout;
    }

    public RoadPlannerVanillaContextMenu contextMenuForTest() {
        return contextMenu;
    }

    public void setGraphForTest(RoadNetworkGraph graph) {
        this.graph = graph == null ? new RoadNetworkGraph() : graph;
    }

    @Override
    protected void init() {
        recomputeLayout();
        addToolButtons();
        addActionButtons();
    }

    private void recomputeLayout() {
        layout = RoadPlannerVanillaLayout.compute(width, height);
        RoadMapRegion region = RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_1);
        RoadMapViewport viewport = new RoadMapViewport(layout.map().x(), layout.map().y(), layout.map().width(), layout.map().height());
        canvas = new RoadPlannerMapCanvas(layout.map(), new RoadPlannerMapComponent(region, viewport));
    }

    private void addToolButtons() {
        int x = layout.toolbar().x() + 12;
        int y = layout.toolbar().y() + 56;
        int buttonWidth = layout.toolbar().width() - 24;
        for (int index = 0; index < TOOLS.size(); index++) {
            RoadToolType tool = TOOLS.get(index);
            Button button = Button.builder(Component.literal(TOOL_LABELS.get(index)), ignored -> state = state.withActiveTool(tool))
                    .bounds(x, y + index * 34, buttonWidth, 26)
                    .build();
            addRenderableWidget(button);
        }
    }

    private void addActionButtons() {
        for (int index = 0; index < ACTION_LABELS.size(); index++) {
            RoadPlannerVanillaLayout.Rect rect = layout.bottomButtons().get(index);
            String label = ACTION_LABELS.get(index);
            Button button = Button.builder(Component.literal(label), ignored -> handleAction(label))
                    .bounds(rect.x(), rect.y(), rect.width(), rect.height())
                    .build();
            addRenderableWidget(button);
        }
    }

    private void handleAction(String label) {
        if ("取消".equals(label)) {
            onClose();
            return;
        }
        statusLine = label + " 已点击";
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(0, 0, width, height, BG);
        renderPanels(graphics);
        canvas.renderPlaceholder(graphics, font);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (contextMenu != null && contextMenu.isOpen()) {
            contextMenu.render(graphics, font, mouseX, mouseY, width, height);
        }
    }

    private void renderPanels(GuiGraphics graphics) {
        fillRect(graphics, layout.toolbar(), PANEL);
        fillRect(graphics, layout.sidebar(), PANEL);
        graphics.drawString(font, "工具", layout.toolbar().x() + 16, layout.toolbar().y() + 18, ACCENT, false);
        graphics.drawString(font, "RoadPlanner 道路规划小地图", layout.map().x(), layout.map().y() - 22, TEXT, false);
        graphics.drawString(font, statusLine, layout.map().x(), layout.map().y() - 8, MUTED, false);
        graphics.drawString(font, "道路信息", layout.sidebar().x() + 16, layout.sidebar().y() + 18, TEXT, false);
        graphics.drawString(font, "阶段: 1", layout.sidebar().x() + 16, layout.sidebar().y() + 48, MUTED, false);
        graphics.drawString(font, "距离目的地: 未设置", layout.sidebar().x() + 16, layout.sidebar().y() + 68, MUTED, false);
        graphics.drawString(font, "宽度: " + state.selectedWidth(), layout.sidebar().x() + 16, layout.sidebar().y() + 88, MUTED, false);
        graphics.drawString(font, "当前工具: " + state.activeTool(), layout.sidebar().x() + 16, layout.sidebar().y() + 108, MUTED, false);
        graphics.drawString(font, "ESC: 关闭菜单 / 关闭规划器", layout.sidebar().x() + 16, layout.sidebar().bottom() - 28, MUTED, false);
    }

    private void fillRect(GuiGraphics graphics, RoadPlannerVanillaLayout.Rect rect, int color) {
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (contextMenu != null && contextMenu.isOpen()) {
            RoadPlannerVanillaContextMenu.ClickResult result = contextMenu.click(mouseX, mouseY, button);
            if (result.consumed()) {
                result.action().ifPresent(this::handleContextAction);
                return true;
            }
        }
        if (button == 1 && canvas.contains(mouseX, mouseY)) {
            BlockPos world = canvas.mouseToWorld(mouseX, mouseY);
            RoadPlannerMapInteractionResult result = canvas.rightClickGraph(state, graph, world.getX(), world.getZ(), (int) mouseX, (int) mouseY);
            state = result.state();
            result.contextMenu().ifPresent(menu -> contextMenu = RoadPlannerVanillaContextMenu.forRoadEdge(menu.roadEdgeId()));
            if (contextMenu != null) {
                contextMenu.open((int) mouseX, (int) mouseY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleContextAction(RoadPlannerContextMenuAction action) {
        statusLine = "菜单: " + action.name();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            EscapeResult result = handleEscape(contextMenu != null && contextMenu.isOpen(), false);
            if (result == EscapeResult.CLOSE_CONTEXT_MENU) {
                contextMenu.close();
                return true;
            }
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    public EscapeResult handleEscapeForTest(boolean contextMenuOpen, boolean textInputOpen) {
        return handleEscape(contextMenuOpen, textInputOpen);
    }

    private EscapeResult handleEscape(boolean contextMenuOpen, boolean textInputOpen) {
        if (textInputOpen) {
            return EscapeResult.CLOSE_TEXT_INPUT;
        }
        if (contextMenuOpen) {
            return EscapeResult.CLOSE_CONTEXT_MENU;
        }
        return EscapeResult.CLOSE_SCREEN;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public enum EscapeResult {
        CLOSE_CONTEXT_MENU,
        CLOSE_TEXT_INPUT,
        CLOSE_SCREEN
    }
}
