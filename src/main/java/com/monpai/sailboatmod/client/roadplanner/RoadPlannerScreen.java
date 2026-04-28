package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoCompleteRequestPacket;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class RoadPlannerScreen extends Screen {
    private static final List<RoadToolType> TOOLS = List.of(
            RoadToolType.SELECT,
            RoadToolType.ROAD,
            RoadToolType.BRIDGE,
            RoadToolType.TUNNEL,
            RoadToolType.ERASE,
            RoadToolType.WATER_CROSSING,
            RoadToolType.BEZIER,
            RoadToolType.ENDPOINT,
            RoadToolType.FORCE_RENDER
    );
    private static final List<String> TOOL_LABELS = List.of("\u9009\u62e9", "\u9053\u8def", "\u6865\u6881", "\u96a7\u9053", "\u64e6\u9664", "\u8de8\u6c34", "\u8d1d\u585e\u5c14", "\u7aef\u70b9", "\u5f3a\u5236\u6e32\u67d3");
    private static final List<String> ACTION_LABELS = List.of(
            RoadPlannerTopToolbar.ACTION_UNDO,
            RoadPlannerTopToolbar.ACTION_CLEAR,
            RoadPlannerTopToolbar.ACTION_AUTO_COMPLETE,
            RoadPlannerTopToolbar.ACTION_CONFIRM_BUILD,
            RoadPlannerTopToolbar.ACTION_CANCEL
    );

    private RoadPlannerClientState state;
    private RoadPlannerMapLayout mapLayout;
    private RoadPlannerVanillaLayout compatibilityLayout;
    private RoadPlannerMapView mapView = RoadPlannerMapView.centered(0, 0, 2.0D);
    private RoadPlannerMapCanvas canvas;
    private RoadPlannerClaimOverlayRenderer claimOverlayRenderer = new RoadPlannerClaimOverlayRenderer(List.of());
    private RoadPlannerTileManager tileManager;
    private RoadPlannerVanillaContextMenu contextMenu;
    private RoadNetworkGraph graph = new RoadNetworkGraph();
    private final RoadPlannerLinePlan linePlan = new RoadPlannerLinePlan();
    private final RoadPlannerForceRenderQueue forceRenderQueue = new RoadPlannerForceRenderQueue();
    private final RoadPlannerTileRenderScheduler tileRenderScheduler = new RoadPlannerTileRenderScheduler();
    private final RoadPlannerAutoCompleteService autoCompleteService = new RoadPlannerAutoCompleteService();
    private final RoadPlannerBridgeRuleService bridgeRuleService = new RoadPlannerBridgeRuleService(RoadPlannerScreen::isClientLand);
    private final RoadPlannerNodeHitTester nodeHitTester = new RoadPlannerNodeHitTester(8.0D);
    private final RoadPlannerEraseTool eraseTool = new RoadPlannerEraseTool();
    private final RoadPlannerDraftPersistence draftPersistence;
    private RoadPlannerBuildSettings buildSettings = RoadPlannerBuildSettings.DEFAULTS;
    private RoadPlannerNodeSelection selectedNode;
    private BlockPos startTownPos = BlockPos.ZERO;
    private BlockPos destinationTownPos = new BlockPos(160, 64, 0);
    private String startTownName = "";
    private String destinationTownName = "";
    private boolean hasTownRoute;
    private UUID routeDraftId;
    private BlockPos hoverWorldPos;
    private BlockPos forceRenderSelectionStart;
    private BlockPos forceRenderSelectionEnd;
    private String statusLine = "\u8bf7\u5148\u9009\u62e9\u76ee\u6807 Town\uff0c\u518d\u5728\u8d77\u70b9 Town \u9886\u5730\u5185\u8bbe\u7f6e\u9053\u8def\u8d77\u70b9";
    private boolean panning;
    private double lastMouseX;
    private double lastMouseY;
    private RoadPlannerTopToolbar.Group expandedToolbarGroup = RoadPlannerTopToolbar.Group.NONE;
    private final boolean testMode;

    public RoadPlannerScreen(UUID sessionId) {
        this(sessionId, false);
    }

    public RoadPlannerScreen(UUID sessionId,
                             String startTownId,
                             String startTownName,
                             BlockPos startTownPos,
                             String destinationTownId,
                             String destinationTownName,
                             BlockPos destinationTownPos) {
        this(sessionId, startTownId, startTownName, startTownPos, destinationTownId, destinationTownName, destinationTownPos, List.of());
    }

    public RoadPlannerScreen(UUID sessionId,
                             String startTownId,
                             String startTownName,
                             BlockPos startTownPos,
                             String destinationTownId,
                             String destinationTownName,
                             BlockPos destinationTownPos,
                             List<RoadPlannerClaimOverlay> claimOverlays) {
        this(sessionId, false);
        this.claimOverlayRenderer = new RoadPlannerClaimOverlayRenderer(claimOverlays);
        applyTownRoute(startTownName, startTownPos, destinationTownName, destinationTownPos);
    }

    private RoadPlannerScreen(UUID sessionId, boolean testMode) {
        super(Component.literal("RoadPlanner"));
        this.state = RoadPlannerClientState.open(sessionId);
        this.testMode = testMode;
        this.draftPersistence = new RoadPlannerDraftPersistence(draftRootDir(testMode));
    }

    private static File draftRootDir(boolean testMode) {
        if (testMode) {
            return new File("roadplanner_drafts_test");
        }
        return new File(net.minecraft.client.Minecraft.getInstance().gameDirectory, "roadplanner_drafts");
    }

    private void applyTownRoute(String startTownName, BlockPos startTownPos, String destinationTownName, BlockPos destinationTownPos) {
        this.startTownName = startTownName == null ? "" : startTownName;
        this.destinationTownName = destinationTownName == null ? "" : destinationTownName;
        this.startTownPos = startTownPos == null ? BlockPos.ZERO : startTownPos.immutable();
        this.destinationTownPos = destinationTownPos == null ? new BlockPos(160, 64, 0) : destinationTownPos.immutable();
        this.hasTownRoute = true;
        this.routeDraftId = routeDraftId();
        this.mapView = RoadPlannerMapView.centered(
                (this.startTownPos.getX() + this.destinationTownPos.getX()) / 2.0D,
                (this.startTownPos.getZ() + this.destinationTownPos.getZ()) / 2.0D,
                2.0D
        );
        linePlan.clear();
        RoadPlannerDraftStore.Draft draft = RoadPlannerDraftStore.get(state.sessionId());
        if (draft == null) {
            draft = draftPersistence.load(state.sessionId()).orElse(null);
        }
        if (draft == null && routeDraftId != null) {
            draft = RoadPlannerDraftStore.get(routeDraftId);
        }
        if (draft == null && routeDraftId != null) {
            draft = draftPersistence.load(routeDraftId).orElse(null);
        }
        if (draft != null && !draft.nodes().isEmpty()) {
            linePlan.replaceWith(draft.nodes(), draft.segmentTypes());
            if (!draft.startPos().equals(BlockPos.ZERO)) {
                this.startTownPos = draft.startPos();
            }
            if (!draft.endPos().equals(BlockPos.ZERO)) {
                this.destinationTownPos = draft.endPos();
            }
        }
        selectedNode = null;
        statusLine = "路线: " + displayTownName(this.startTownName, "起点 Town") + " -> "
                + displayTownName(this.destinationTownName, "目标 Town") + "，请点击设置道路起点";
    }

    private static String displayTownName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isClientLand(int x, int z) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return true;
        }
        ClientLevel level = minecraft.level;
        if (level == null) {
            return true;
        }
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (surfaceY < level.getMinBuildHeight()) {
            return false;
        }
        BlockPos surface = new BlockPos(x, surfaceY, z);
        return !level.getBlockState(surface).getFluidState().is(Fluids.WATER)
                && !level.getBlockState(surface.above()).getFluidState().is(Fluids.WATER);
    }

    public static RoadPlannerScreen forTest(UUID sessionId, int width, int height) {
        RoadPlannerScreen screen = new RoadPlannerScreen(sessionId, true);
        screen.width = width;
        screen.height = height;
        screen.recomputeLayout();
        return screen;
    }

    public static RoadPlannerScreen forTest(UUID sessionId, int width, int height, BlockPos startTownPos, BlockPos destinationTownPos) {
        RoadPlannerScreen screen = new RoadPlannerScreen(sessionId, true);
        screen.applyTownRoute("Start", startTownPos, "Destination", destinationTownPos);
        screen.resetLineToStartNode();
        screen.width = width;
        screen.height = height;
        screen.recomputeLayout();
        return screen;
    }

    public static RoadPlannerScreen forTest(UUID sessionId,
                                            int width,
                                            int height,
                                            BlockPos startTownPos,
                                            BlockPos destinationTownPos,
                                            List<RoadPlannerClaimOverlay> claimOverlays) {
        RoadPlannerScreen screen = new RoadPlannerScreen(sessionId, true);
        screen.claimOverlayRenderer = new RoadPlannerClaimOverlayRenderer(claimOverlays);
        screen.applyTownRoute("Start", startTownPos, "Destination", destinationTownPos);
        screen.resetLineToStartNode();
        screen.width = width;
        screen.height = height;
        screen.recomputeLayout();
        return screen;
    }

    public RoadPlannerClientState state() {
        return state;
    }

    public RoadPlannerVanillaLayout layoutForTest() {
        return compatibilityLayout;
    }

    public RoadPlannerMapLayout mapLayoutForTest() {
        return mapLayout;
    }

    public RoadPlannerVanillaContextMenu contextMenuForTest() {
        return contextMenu;
    }

    public int plannedNodeCountForTest() {
        return linePlan.nodeCount();
    }

    public BlockPos startTownPosForTest() {
        return startTownPos;
    }

    public BlockPos destinationTownPosForTest() {
        return destinationTownPos;
    }

    public int actionCountForTest() {
        return ACTION_LABELS.size();
    }

    public RoadPlannerForceRenderProgress forceRenderProgressForTest() {
        return forceRenderQueue.progress();
    }

    public RoadPlannerClaimOverlayRenderer claimOverlayRendererForTest() {
        return claimOverlayRenderer;
    }

    public int forceRenderTotalChunksForTest() {
        return forceRenderQueue.progress().totalChunks();
    }

    public void handleContextActionForTest(RoadPlannerContextMenuAction action) {
        handleContextAction(action);
    }

    public RoadToolType activeToolForTest() {
        return state.activeTool();
    }

    public String statusLineForTest() {
        return statusLine;
    }

    public void applyAutoCompleteResult(UUID sessionId,
                                        boolean success,
                                        List<BlockPos> nodes,
                                        List<RoadPlannerSegmentType> segmentTypes,
                                        String message) {
        if (!state.sessionId().equals(sessionId)) {
            return;
        }
        if (!success) {
            statusLine = message == null || message.isBlank() ? "自动补全失败" : message;
            return;
        }
        RoadPlannerBridgeSegmentNormalizer.Result normalized = normalizeBridgeSegments(nodes, segmentTypes);
        linePlan.replaceWith(normalized.nodes(), normalized.segmentTypes());
        saveDraft();
        if (normalized.nodes().size() >= 2) {
            forceRenderQueue.enqueueCorridor(normalized.nodes().get(0), normalized.nodes().get(normalized.nodes().size() - 1), 64, "自动路线缓存");
        }
        statusLine = message == null || message.isBlank() ? "自动补全完成" : message;
    }

    public void setGraphForTest(RoadNetworkGraph graph) {
        this.graph = graph == null ? new RoadNetworkGraph() : graph;
    }

    public boolean rightClickMapForTest(double worldX, double worldZ, int mouseX, int mouseY) {
        return openContextMenuForGraph(worldX, worldZ, mouseX, mouseY);
    }

    public boolean clickWorldForTest(int worldX, int worldZ) {
        return setEndpointAt(new BlockPos(worldX, 64, worldZ));
    }

    @Override
    protected void init() {
        recomputeLayout();
        forceRenderQueue.enqueueCorridor(startTownPos, destinationTownPos, 64, "路线走廊");
    }

    @Override
    public void removed() {
        super.removed();
        if (tileManager != null) {
            tileManager.close();
            tileManager = null;
        }
        tileRenderScheduler.close();
    }

    private void recomputeLayout() {
        mapLayout = RoadPlannerMapLayout.compute(width, height);
        compatibilityLayout = RoadPlannerVanillaLayout.compute(width, height);
        RoadMapRegion region = RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_1);
        RoadMapViewport viewport = new RoadMapViewport(mapLayout.map().x(), mapLayout.map().y(), mapLayout.map().width(), mapLayout.map().height());
        if (!testMode && tileManager == null) {
            tileManager = RoadPlannerTileManager.createDefault();
        }
        RoadPlannerHeightSampler heightSampler = testMode ? (x, z) -> 64 : RoadPlannerHeightSampler.clientLoadedTerrain();
        canvas = new RoadPlannerMapCanvas(mapLayout.map().asVanillaRect(), new RoadPlannerMapComponent(region, viewport), mapView, tileManager, heightSampler);
    }

    @Override
    public void tick() {
        if (tileManager != null) {
            forceRenderQueue.processChunks(6,
                    chunk -> tileManager.hasCachedTileForChunk(chunk) || tileRenderScheduler.alreadySubmitted(chunk),
                    chunk -> tileRenderScheduler.submit(chunk, () -> tileManager.forceRenderChunk(chunk)));
        } else {
            forceRenderQueue.processChunks(8);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        canvas.render(graphics, font);
        claimOverlayRenderer.render(graphics, mapView, mapLayout.map());
        renderRoadOverlay(graphics);
        renderToolbar(graphics, mouseX, mouseY);
        renderInspector(graphics);
        renderStatusBar(graphics);
        if (contextMenu != null && contextMenu.isOpen()) {
            contextMenu.render(graphics, font, mouseX, mouseY, width, height);
        }
        if (canvas.contains(mouseX, mouseY)) {
            claimOverlayRenderer.renderTooltip(graphics, font, mapView, mapLayout.map(), mouseX, mouseY);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, width, height, RoadPlannerMapTheme.BACKGROUND);
    }

    private void renderToolbar(GuiGraphics graphics, int mouseX, int mouseY) {
        if (renderTopToolbar(graphics, mouseX, mouseY)) {
            return;
        }
        RoadPlannerMapLayout.Rect toolbar = mapLayout.toolbar();
        fillPanel(graphics, toolbar);
        graphics.drawCenteredString(font, "工具", toolbar.x() + toolbar.width() / 2, toolbar.y() + 8, RoadPlannerMapTheme.TEXT);
        RoadPlannerTopToolbar topToolbar = RoadPlannerTopToolbar.defaultToolbar(width);
        RoadPlannerTopToolbar.Item toolbarItem = topToolbar.itemAt(mouseX, mouseY);
        if (toolbarItem != null) {
            if (toolbarItem.kind() == RoadPlannerTopToolbar.Kind.TOOL) {
                state = state.withActiveTool(toolbarItem.toolType());
                statusLine = "当前工具: " + toolbarItem.label();
                return;
            }
            handleAction(toolbarItem.label());
            return;
        }
        if (legacyToolbarHitTestingEnabled()) for (int index = 0; index < TOOLS.size(); index++) {
            RoadPlannerMapLayout.Rect button = toolButtonRect(index);
            int color = state.activeTool() == TOOLS.get(index) ? RoadPlannerMapTheme.TOOL_SELECTED
                    : button.contains(mouseX, mouseY) ? RoadPlannerMapTheme.TOOL_HOVER : 0x00000000;
            graphics.fill(button.x(), button.y(), button.right(), button.bottom(), color);
            graphics.drawCenteredString(font, TOOL_LABELS.get(index), button.x() + button.width() / 2, button.y() + 7, RoadPlannerMapTheme.TEXT);
        }
    }

    private boolean renderTopToolbar(GuiGraphics graphics, int mouseX, int mouseY) {
        RoadPlannerTopToolbar toolbar = RoadPlannerTopToolbar.toolbar(width, expandedToolbarGroup);
        fillPanel(graphics, mapLayout.toolbar());
        for (RoadPlannerTopToolbar.Item item : toolbar.items()) {
            boolean selectedGroup = item.kind() == RoadPlannerTopToolbar.Kind.GROUP && item.group() == expandedToolbarGroup;
            int color = item.kind() == RoadPlannerTopToolbar.Kind.TOOL && state.activeTool() == item.toolType() ? RoadPlannerMapTheme.TOOL_SELECTED
                    : selectedGroup ? RoadPlannerMapTheme.TOOL_SELECTED
                    : item.bounds().contains(mouseX, mouseY) ? RoadPlannerMapTheme.TOOL_HOVER : RoadPlannerMapTheme.FLOATING_PANEL;
            graphics.fill(item.bounds().x(), item.bounds().y(), item.bounds().right(), item.bounds().bottom(), color);
            graphics.fill(item.bounds().x(), item.bounds().y(), item.bounds().right(), item.bounds().y() + 1, RoadPlannerMapTheme.FLOATING_PANEL_BORDER);
            graphics.drawCenteredString(font, item.label(), item.bounds().x() + item.bounds().width() / 2, item.bounds().y() + 7, RoadPlannerMapTheme.TEXT);
        }
        return true;
    }

    private void renderRoadOverlay(GuiGraphics graphics) {
        RoadPlannerMapLayout.Rect map = mapLayout.map();
        List<BlockPos> nodes = linePlan.nodes();
        List<RoadPlannerSegmentType> segments = linePlan.segments();
        for (int index = 1; index < nodes.size(); index++) {
            BlockPos previous = nodes.get(index - 1);
            BlockPos current = nodes.get(index);
            RoadPlannerSegmentType segmentType = segments.get(index - 1);
            drawMapLine(graphics, map,
                    mapView.worldToScreenX(previous.getX(), map), mapView.worldToScreenZ(previous.getZ(), map),
                    mapView.worldToScreenX(current.getX(), map), mapView.worldToScreenZ(current.getZ(), map),
                    RoadPlannerOverlayStyle.lineColor(segmentType), Math.max(2, state.selectedWidth()));
        }
        for (int index = 0; index < nodes.size(); index++) {
            BlockPos node = nodes.get(index);
            int x = mapView.worldToScreenX(node.getX(), map);
            int y = mapView.worldToScreenZ(node.getZ(), map);
            boolean selected = selectedNode != null && selectedNode.nodeIndex() == index;
            int radius = selected ? 6 : 3;
            if (selected) {
                graphics.fill(x - 8, y - 1, x + 9, y + 2, 0xFFFFF176);
                graphics.fill(x - 1, y - 8, x + 2, y + 9, 0xFFFFF176);
            }
            if (map.contains(x, y)) {
                graphics.fill(x - radius, y - radius, x + radius, y + radius, selected ? 0xFFFFFFFF : RoadPlannerOverlayStyle.nodeColor());
            }
            if (selected) {
                graphics.fill(x - 3, y - 3, x + 3, y + 3, RoadPlannerOverlayStyle.nodeColor());
            }
        }
        renderTownMarker(graphics, map, startTownPos, "起点", 0xFFFFE066);
        renderTownMarker(graphics, map, destinationTownPos, "终点", 0xFFFF5555);
        if (hoverWorldPos != null && !nodes.isEmpty() && showsHoverPreviewLine()) {
            BlockPos previous = nodes.get(nodes.size() - 1);
            int previewColor = state.activeTool() == RoadToolType.ROAD && requiresBridgeTool(hoverWorldPos) ? 0xCCFF4D4D : 0xCCFFFFFF;
            drawMapLine(graphics, map,
                    mapView.worldToScreenX(previous.getX(), map), mapView.worldToScreenZ(previous.getZ(), map),
                    mapView.worldToScreenX(hoverWorldPos.getX(), map), mapView.worldToScreenZ(hoverWorldPos.getZ(), map),
                    previewColor, Math.max(2, state.selectedWidth() / 2));
        }
        renderForceRenderSelection(graphics, map);
    }

    private void renderForceRenderSelection(GuiGraphics graphics, RoadPlannerMapLayout.Rect map) {
        if (forceRenderSelectionStart == null || forceRenderSelectionEnd == null) {
            return;
        }
        int x1 = mapView.worldToScreenX(forceRenderSelectionStart.getX(), map);
        int y1 = mapView.worldToScreenZ(forceRenderSelectionStart.getZ(), map);
        int x2 = mapView.worldToScreenX(forceRenderSelectionEnd.getX(), map);
        int y2 = mapView.worldToScreenZ(forceRenderSelectionEnd.getZ(), map);
        int left = Math.max(map.x(), Math.min(x1, x2));
        int right = Math.min(map.right(), Math.max(x1, x2));
        int top = Math.max(map.y(), Math.min(y1, y2));
        int bottom = Math.min(map.bottom(), Math.max(y1, y2));
        if (right <= left || bottom <= top) {
            return;
        }
        graphics.fill(left, top, right, top + 1, 0xCC7DD3FC);
        graphics.fill(left, bottom - 1, right, bottom, 0xCC7DD3FC);
        graphics.fill(left, top, left + 1, bottom, 0xCC7DD3FC);
        graphics.fill(right - 1, top, right, bottom, 0xCC7DD3FC);
        graphics.fill(left, top, right, bottom, 0x227DD3FC);
    }

    private void drawMapLine(GuiGraphics graphics, RoadPlannerMapLayout.Rect map, int x1, int y1, int x2, int y2, int color, int thickness) {
        if (!lineIntersectsRect(x1, y1, x2, y2, map)) {
            return;
        }
        drawLine(graphics,
                clamp(x1, map.x(), map.right()), clamp(y1, map.y(), map.bottom()),
                clamp(x2, map.x(), map.right()), clamp(y2, map.y(), map.bottom()),
                color,
                thickness);
    }

    private boolean lineIntersectsRect(int x1, int y1, int x2, int y2, RoadPlannerMapLayout.Rect rect) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        return maxX >= rect.x() && minX <= rect.right() && maxY >= rect.y() && minY <= rect.bottom();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void renderInspector(GuiGraphics graphics) {
        if (compactInspectorRendered()) {
            return;
        }
        RoadPlannerMapLayout.Rect inspector = mapLayout.inspector();
        fillPanel(graphics, inspector);
        RoadPlannerForceRenderProgress progress = forceRenderQueue.progress();
        graphics.drawString(font, "路线信息", inspector.x() + 10, inspector.y() + 10, RoadPlannerMapTheme.TEXT, false);
        graphics.drawString(font, "工具: " + state.activeTool(), inspector.x() + 10, inspector.y() + 30, RoadPlannerMapTheme.MUTED_TEXT, false);
        graphics.drawString(font, "起点: " + displayTownName(startTownName, "未选择"), inspector.x() + 10, inspector.y() + 48, RoadPlannerMapTheme.MUTED_TEXT, false);
        graphics.drawString(font, "终点: " + displayTownName(destinationTownName, "未选择"), inspector.x() + 10, inspector.y() + 66, RoadPlannerMapTheme.MUTED_TEXT, false);
        graphics.drawString(font, "节点: " + linePlan.nodeCount() + " 渲染 " + progress.percent() + "%", inspector.x() + 10, inspector.y() + 84, RoadPlannerMapTheme.MUTED_TEXT, false);
        renderProgressBar(graphics, inspector.x() + 10, inspector.y() + 100, inspector.width() - 20, 6, progress.percent());
    }

    private void renderTownMarker(GuiGraphics graphics, RoadPlannerMapLayout.Rect map, BlockPos pos, String label, int color) {
        int x = mapView.worldToScreenX(pos.getX(), map);
        int y = mapView.worldToScreenZ(pos.getZ(), map);
        graphics.fill(x - 5, y - 5, x + 5, y + 5, color);
        graphics.drawString(font, label, x + 7, y - 4, RoadPlannerMapTheme.TEXT, false);
    }

    private boolean compactInspectorRendered() {
        return true;
    }

    private void renderActionStrip(GuiGraphics graphics, int mouseX, int mouseY) {
        RoadPlannerMapLayout.Rect inspector = mapLayout.inspector();
        int buttonWidth = inspector.width() - 20;
        int gap = 6;
        int x = inspector.x() + 10;
        int y = inspector.y() + 108;
        for (int index = 0; index < ACTION_LABELS.size(); index++) {
            RoadPlannerMapLayout.Rect button = new RoadPlannerMapLayout.Rect(x, y + index * (22 + gap), buttonWidth, 22);
            graphics.fill(button.x(), button.y(), button.right(), button.bottom(), button.contains(mouseX, mouseY) ? RoadPlannerMapTheme.TOOL_HOVER : RoadPlannerMapTheme.FLOATING_PANEL);
            graphics.fill(button.x(), button.y(), button.right(), button.y() + 1, RoadPlannerMapTheme.FLOATING_PANEL_BORDER);
            graphics.drawCenteredString(font, ACTION_LABELS.get(index), button.x() + button.width() / 2, button.y() + 6, RoadPlannerMapTheme.TEXT);
        }
    }

    private void renderStatusBar(GuiGraphics graphics) {
        RoadPlannerMapLayout.Rect status = mapLayout.statusBar();
        fillPanel(graphics, status);
        RoadPlannerForceRenderProgress progress = forceRenderQueue.progress();
        String routeText = "节点 " + linePlan.nodeCount() + " | " + displayTownName(startTownName, "未选择") + " -> "
                + displayTownName(destinationTownName, "未选择") + " | " + progress.percent() + "%";
        String progressText = progress.totalChunks() > 0 ? " 渲染 " + progress.label() + " " + progress.completedChunks() + "/" + progress.totalChunks() : "";
        graphics.drawString(font, statusLine + " | " + routeText + progressText, status.x() + 10, status.y() + 9, RoadPlannerMapTheme.TEXT, false);
    }

    private void renderProgressBar(GuiGraphics graphics, int x, int y, int width, int height, int percent) {
        graphics.fill(x, y, x + width, y + height, 0xAA000000);
        graphics.fill(x, y, x + Math.max(0, Math.min(width, width * percent / 100)), y + height, RoadPlannerMapTheme.BRIDGE_LINE);
    }

    private void fillPanel(GuiGraphics graphics, RoadPlannerMapLayout.Rect rect) {
        graphics.fill(rect.x() + 2, rect.y() + 2, rect.right() + 2, rect.bottom() + 2, RoadPlannerMapTheme.MAP_SHADOW);
        graphics.fill(rect.x() - 1, rect.y() - 1, rect.right() + 1, rect.bottom() + 1, RoadPlannerMapTheme.FLOATING_PANEL_BORDER);
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), RoadPlannerMapTheme.FLOATING_PANEL);
    }

    private void drawLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color, int thickness) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) {
            graphics.fill(x1 - thickness / 2, y1 - thickness / 2, x1 + thickness / 2 + 1, y1 + thickness / 2 + 1, color);
            return;
        }
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            int x = (int) Math.round(x1 + dx * t);
            int y = (int) Math.round(y1 + dy * t);
            graphics.fill(x - thickness / 2, y - thickness / 2, x + thickness / 2 + 1, y + thickness / 2 + 1, color);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (contextMenu != null && contextMenu.isOpen()) {
            RoadPlannerVanillaContextMenu.ClickResult result = contextMenu.click(mouseX, mouseY, button);
            result.action().ifPresent(this::handleContextAction);
            if (result.closeMenu()) {
                contextMenu.close();
            }
            if (result.consumed()) {
                return true;
            }
        }
        RoadPlannerTopToolbar topToolbar = RoadPlannerTopToolbar.toolbar(width, expandedToolbarGroup);
        RoadPlannerTopToolbar.Item toolbarItem = topToolbar.itemAt(mouseX, mouseY);
        if (toolbarItem != null) {
            if (toolbarItem.kind() == RoadPlannerTopToolbar.Kind.GROUP) {
                expandedToolbarGroup = expandedToolbarGroup == toolbarItem.group()
                        ? RoadPlannerTopToolbar.Group.NONE : toolbarItem.group();
                return true;
            }
            if (toolbarItem.kind() == RoadPlannerTopToolbar.Kind.TOOL) {
                state = state.withActiveTool(toolbarItem.toolType());
                expandedToolbarGroup = RoadPlannerTopToolbar.Group.NONE;
                statusLine = "当前工具: " + toolbarItem.label();
                return true;
            }
            expandedToolbarGroup = RoadPlannerTopToolbar.Group.NONE;
            handleAction(toolbarItem.label());
            return true;
        }
        if (legacyToolbarHitTestingEnabled()) for (int index = 0; index < TOOLS.size(); index++) {
            if (toolButtonRect(index).contains(mouseX, mouseY)) {
                state = state.withActiveTool(TOOLS.get(index));
                statusLine = "当前工具: " + TOOL_LABELS.get(index);
                return true;
            }
        }
        int actionIndex = legacyToolbarHitTestingEnabled() ? actionIndexAt(mouseX, mouseY) : -1;
        if (actionIndex >= 0) {
            handleAction(ACTION_LABELS.get(actionIndex));
            return true;
        }
        if (canvas.contains(mouseX, mouseY)) {
            if (button == 1) {
                BlockPos world = canvas.mouseToWorld(mouseX, mouseY);
                return openContextMenuForGraph(world.getX(), world.getZ(), (int) mouseX, (int) mouseY);
            }
            if (button == 0 && state.activeTool() == RoadToolType.SELECT) {
                BlockPos world = canvas.mouseToWorld(mouseX, mouseY);
                selectedNode = nodeHitTester.hitNode(linePlan.nodes(), world.getX(), world.getZ()).orElse(null);
                statusLine = selectedNode == null ? "未选中节点" : "已选中节点 #" + selectedNode.nodeIndex();
                return true;
            }
            if (button == 0 && state.activeTool() == RoadToolType.ERASE) {
                return eraseNodeAt(mouseX, mouseY);
            }
            if (button == 0) {
                RoadPlannerSegmentType segmentType = segmentTypeForActiveTool();
                BlockPos target = canvas.mouseToWorld(mouseX, mouseY);
                if (state.activeTool() == RoadToolType.FORCE_RENDER) {
                    forceRenderSelectionStart = target;
                    forceRenderSelectionEnd = target;
                    statusLine = "\u62d6\u62fd\u9009\u6846\u5f3a\u5236\u6e32\u67d3 tile";
                    return true;
                }
                if (state.activeTool() == RoadToolType.ENDPOINT) {
                    return setEndpointAt(target);
                }
                if (hasTownRoute && linePlan.nodeCount() == 0 && !RoadPlannerEndpointRules.isInRoleClaim(claimOverlayRenderer, target, RoadPlannerClaimOverlay.Role.START)) {
                    statusLine = "\u9053\u8def\u8d77\u70b9\u5fc5\u987b\u8bbe\u7f6e\u5728\u8d77\u70b9 Town \u9886\u5730\u5185";
                    return true;
                }
                if (state.activeTool() == RoadToolType.ROAD && requiresBridgeTool(target)) {
                    statusLine = "该跨越需要桥梁工具，普通道路无法连接";
                    return true;
                }
                if (state.activeTool() == RoadToolType.BRIDGE || state.activeTool() == RoadToolType.WATER_CROSSING) {
                    RoadPlannerBridgeRuleService.Decision decision = bridgeRuleService.evaluateBridgeTool(linePlan.nodes(), target);
                    if (!decision.accepted()) {
                        statusLine = state.activeTool() == RoadToolType.WATER_CROSSING ? "跨水工具需要两岸有效陆地节点" : statusLine;
                        return true;
                    }
                    segmentType = decision.segmentType();
                    if (state.activeTool() == RoadToolType.WATER_CROSSING) {
                        linePlan.setSegmentTypeFromNode(decision.bridgeStartNodeIndex(), RoadPlannerSegmentType.BRIDGE_MAJOR);
                        while (linePlan.nodeCount() > decision.bridgeStartNodeIndex() + 1) {
                            linePlan.removeLastNode();
                        }
                    }
                } else if (state.activeTool() == RoadToolType.BEZIER) {
                    addBezierNodes(target, segmentType);
                    normalizeCurrentBridgeSegments();
                    selectedNode = null;
                    saveDraft();
                    statusLine = "已添加贝塞尔曲线节点: " + linePlan.nodeCount();
                    return true;
                }
                addNodeWithWaterSplit(target, segmentType);
                normalizeCurrentBridgeSegments();
                selectedNode = null;
                saveDraft();
                statusLine = "已添加节点 " + linePlan.nodeCount();
                return true;
            }
            panning = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (panning && canvas.contains(mouseX, mouseY)) {
            mapView.panByScreenDelta(mouseX - lastMouseX, mouseY - lastMouseY);
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        if (button == 0 && canvas.contains(mouseX, mouseY) && state.activeTool() == RoadToolType.FORCE_RENDER && forceRenderSelectionStart != null) {
            forceRenderSelectionEnd = canvas.mouseToWorld(mouseX, mouseY);
            return true;
        }
        if (button == 0 && canvas.contains(mouseX, mouseY) && state.activeTool() == RoadToolType.ERASE) {
            return eraseNodeAt(mouseX, mouseY);
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        hoverWorldPos = canvas != null && canvas.contains(mouseX, mouseY) ? canvas.mouseToWorld(mouseX, mouseY) : null;
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        panning = false;
        if (button == 0 && state.activeTool() == RoadToolType.FORCE_RENDER && forceRenderSelectionStart != null) {
            if (canvas.contains(mouseX, mouseY)) {
                forceRenderSelectionEnd = canvas.mouseToWorld(mouseX, mouseY);
            }
            tileRenderScheduler.clear();
            forceRenderQueue.enqueueSelection(forceRenderSelectionStart, forceRenderSelectionEnd == null ? forceRenderSelectionStart : forceRenderSelectionEnd, "\u9009\u533a\u6e32\u67d3");
            statusLine = "\u5df2\u52a0\u5165\u5f3a\u5236\u6e32\u67d3\u9009\u533a";
            forceRenderSelectionStart = null;
            forceRenderSelectionEnd = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (canvas.contains(mouseX, mouseY)) {
            mapView.zoomAround(mouseX, mouseY, delta > 0 ? 1.2D : 0.833333D, mapLayout.map());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private boolean openContextMenuForGraph(double worldX, double worldZ, int mouseX, int mouseY) {
        RoadPlannerMapInteractionResult result = canvas.rightClickGraph(state, graph, worldX, worldZ, mouseX, mouseY);
        state = result.state();
        contextMenu = result.contextMenu()
                .map(menu -> RoadPlannerVanillaContextMenu.forRoadEdge(menu.roadEdgeId()))
                .orElseGet(() -> RoadPlannerVanillaContextMenu.forRoadEdge(UUID.randomUUID()));
        contextMenu.open(mouseX, mouseY);
        return true;
    }

    private void handleContextAction(RoadPlannerContextMenuAction action) {
        if (action == null) {
            return;
        }
        if (action == RoadPlannerContextMenuAction.SET_ROAD_TYPE || action == RoadPlannerContextMenuAction.SET_BRIDGE_TYPE || action == RoadPlannerContextMenuAction.SET_TUNNEL_TYPE) {
            switch (action) {
                case SET_ROAD_TYPE -> setSelectedEdgeType(CompiledRoadSectionType.ROAD);
                case SET_BRIDGE_TYPE -> setSelectedEdgeType(CompiledRoadSectionType.BRIDGE);
                case SET_TUNNEL_TYPE -> setSelectedEdgeType(CompiledRoadSectionType.TUNNEL);
                default -> {}
            }
            return;
        }
        if (state.selectedRoadEdgeId() == null) {
            statusLine = "\u672a\u9009\u4e2d\u9053\u8def\u6bb5";
            return;
        }
        switch (action) {
            case RENAME_ROAD -> {
                if (minecraft != null) {
                    minecraft.setScreen(new RoadPlannerTextInputScreen(state.sessionId(), state.selectedRoadEdgeId(), "", this));
                }
            }
            case SET_ROAD_TYPE -> setSelectedEdgeType(CompiledRoadSectionType.ROAD);
            case SET_BRIDGE_TYPE -> setSelectedEdgeType(CompiledRoadSectionType.BRIDGE);
            case SET_TUNNEL_TYPE -> setSelectedEdgeType(CompiledRoadSectionType.TUNNEL);
            case DEMOLISH_EDGE -> {
                boolean removed = graph.removeEdge(state.selectedRoadEdgeId()).isPresent();
                state = state.withSelectedRoadEdge(null);
                statusLine = removed ? "\u5df2\u62c6\u9664\u672c\u6bb5\u9053\u8def" : "\u672a\u627e\u5230\u53ef\u62c6\u9664\u7684\u9053\u8def\u6bb5";
            }
            case DEMOLISH_BRANCH -> {
                int removed = graph.removeBranchFromEdge(state.selectedRoadEdgeId());
                state = state.withSelectedRoadEdge(null);
                statusLine = "\u5df2\u62c6\u9664\u5206\u652f\u9053\u8def " + removed + " \u6bb5";
            }
            case CONNECT_TOWN -> {
                state = state.withActiveTool(RoadToolType.ENDPOINT);
                statusLine = "\u8bf7\u5728\u76ee\u6807 Town \u9886\u5730\u5185\u70b9\u51fb\u8bbe\u7f6e\u8fde\u63a5\u7aef\u70b9";
            }
            case VIEW_LEDGER -> statusLine = "\u56de\u6eda\u8d26\u672c\u5df2\u8bb0\u5f55\u5728\u65bd\u5de5\u961f\u5217\uff0c\u8bf7\u5728\u65bd\u5de5\u7ba1\u7406\u4e2d\u67e5\u770b";
        }
    }


    private void setSelectedEdgeType(CompiledRoadSectionType type) {
        if (selectedNode == null) {
            statusLine = "\u8bf7\u5148\u7528\u9009\u62e9\u5de5\u5177\u9009\u4e2d\u4e00\u4e2a\u8282\u70b9";
            return;
        }
        int segIndex = selectedNode.nodeIndex();
        if (segIndex >= linePlan.segmentCount()) {
            segIndex = Math.max(0, segIndex - 1);
        }
        if (segIndex < 0 || segIndex >= linePlan.segmentCount()) {
            statusLine = "\u8be5\u8282\u70b9\u6ca1\u6709\u53ef\u4fee\u6539\u7684\u6bb5";
            return;
        }
        RoadPlannerSegmentType segType = switch (type) {
            case BRIDGE -> RoadPlannerSegmentType.BRIDGE_MAJOR;
            case TUNNEL -> RoadPlannerSegmentType.TUNNEL;
            default -> RoadPlannerSegmentType.ROAD;
        };
        linePlan.setSegmentTypeFromNode(segIndex, segType);
        saveDraft();
        statusLine = "\u5df2\u5c06\u8be5\u6bb5\u8bbe\u4e3a: " + editableTypeLabel(type);
    }

    private String editableTypeLabel(CompiledRoadSectionType type) {
        if (type == CompiledRoadSectionType.BRIDGE) {
            return "\u6865\u6881";
        }
        if (type == CompiledRoadSectionType.TUNNEL) {
            return "\u96a7\u9053";
        }
        return "\u9053\u8def";
    }

    private void handleAction(String label) {
        if (RoadPlannerTopToolbar.ACTION_CANCEL.equals(label)) {
            onClose();
            return;
        }
        if (RoadPlannerTopToolbar.ACTION_UNDO.equals(label)) {
            linePlan.removeLastNode();
            restoreStartNodeIfNeeded();
            saveDraft();
            statusLine = "已撤销";
            return;
        }
        if (RoadPlannerTopToolbar.ACTION_CLEAR.equals(label)) {
            resetLineToStartNode();
            saveDraft();
            statusLine = "已清除当前路线";
            return;
        }
        if (RoadPlannerTopToolbar.ACTION_AUTO_COMPLETE.equals(label)) {
            if (!testMode && minecraft != null && minecraft.getConnection() != null) {
                ModNetwork.CHANNEL.sendToServer(new RoadPlannerAutoCompleteRequestPacket(
                        state.sessionId(), startTownPos, destinationTownPos, linePlan.nodes(), 24
                ));
                statusLine = "正在请求自动补全...";
                return;
            }
            RoadPlannerAutoCompleteResult result = autoCompleteService.complete(startTownPos, destinationTownPos, linePlan.nodes(), 24);
            applyAutoCompleteResult(state.sessionId(), result.success(), result.nodes(), result.segmentTypes(), result.message());
            return;
        }
        if (RoadPlannerTopToolbar.ACTION_CONFIRM_BUILD.equals(label)) {
            if (!linePlan.canConfirm()) {
                statusLine = linePlan.hasUnresolvedBridgeBlocker() ? "路线存在未解决的桥梁跨越" : "至少需要起点和终点";
                return;
            }
            if (hasTownRoute) {
                RoadPlannerEndpointRules.Validation validation = RoadPlannerEndpointRules.validate(linePlan.nodes(), claimOverlayRenderer);
                if (!validation.valid()) {
                    statusLine = validation.message();
                    return;
                }
            }
            if (minecraft != null) {
                minecraft.setScreen(new RoadPlannerBuildSettingsScreen(this, buildSettings, this::submitPreviewWithSettings));
            }
        }
    }

    private void restoreStartNodeIfNeeded() {
    }

    private void submitPreviewWithSettings(RoadPlannerBuildSettings settings) {
        buildSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        RoadPlannerBridgeSegmentNormalizer.Result normalized = normalizeBridgeSegments(linePlan.nodes(), linePlan.segments());
        if (normalized.hasBlockingIssues()) {
            statusLine = "桥梁缺少陆地锚点，请增加桥前/桥后陆地节点";
            return;
        }
        linePlan.replaceWith(normalized.nodes(), normalized.segmentTypes());
        saveDraft();
        if (RoadPlannerGhostPreviewBridge.submitPreview(startTownName, destinationTownName, normalized.nodes(), normalized.segmentTypes(), buildSettings)) {
            onClose();
        }
    }


    private void addNodeWithWaterSplit(BlockPos target, RoadPlannerSegmentType segmentType) {
        if (linePlan.nodeCount() == 0) {
            linePlan.addClickNode(target, segmentType);
            return;
        }
        BlockPos from = linePlan.nodes().get(linePlan.nodeCount() - 1);
        RoadPlannerWaterCrossingSplitter.SplitResult split = RoadPlannerWaterCrossingSplitter.split(
                from,
                target,
                RoadPlannerScreen::isClientLand,
                testMode ? (x, z) -> 64 : RoadPlannerHeightSampler.clientLoadedTerrain()
        );
        if (!split.didSplit()) {
            linePlan.addClickNode(target, segmentTypeForConnection(target, segmentType));
            return;
        }
        List<RoadPlannerWaterCrossingSplitter.SplitNode> splitNodes = split.nodes();
        for (int index = 1; index < splitNodes.size(); index++) {
            RoadPlannerWaterCrossingSplitter.SplitNode node = splitNodes.get(index);
            linePlan.addClickNode(node.pos(), node.segmentType());
        }
        statusLine = "自动检测水域，已插入桥梁节点";
    }

    private void normalizeCurrentBridgeSegments() {
        RoadPlannerBridgeSegmentNormalizer.Result normalized = normalizeBridgeSegments(linePlan.nodes(), linePlan.segments());
        linePlan.replaceWith(normalized.nodes(), normalized.segmentTypes());
    }

    private RoadPlannerBridgeSegmentNormalizer.Result normalizeBridgeSegments(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes) {
        return RoadPlannerBridgeSegmentNormalizer.normalize(nodes, segmentTypes, RoadPlannerScreen::isClientLand);
    }

    private void resetLineToStartNode() {
        linePlan.clear();
        selectedNode = null;
    }

    private void saveDraft() {
        RoadPlannerDraftStore.Draft draft = new RoadPlannerDraftStore.Draft(
                linePlan.nodes(), linePlan.segments(), startTownPos, destinationTownPos);
        RoadPlannerDraftStore.save(state.sessionId(), draft.nodes(), draft.segmentTypes(),
                draft.startPos(), draft.endPos());
        draftPersistence.save(state.sessionId(), draft);
        if (routeDraftId != null) {
            RoadPlannerDraftStore.save(routeDraftId, draft.nodes(), draft.segmentTypes(),
                    draft.startPos(), draft.endPos());
            draftPersistence.save(routeDraftId, draft);
        }
    }

    private UUID routeDraftId() {
        String key = displayTownName(startTownName, "") + "|" + startTownPos.asLong() + "|"
                + displayTownName(destinationTownName, "") + "|" + destinationTownPos.asLong();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private boolean eraseNodeAt(double mouseX, double mouseY) {
        BlockPos world = canvas.mouseToWorld(mouseX, mouseY);
        RoadPlannerNodeSelection hit = nodeHitTester.hitNode(linePlan.nodes(), world.getX(), world.getZ()).orElse(null);
        if (hit == null) {
            statusLine = "未擦到节点";
            return true;
        }
        boolean erased = eraseTool.eraseNode(linePlan, hit.nodeIndex(), false);
        if (!erased) {
            statusLine = "该节点不可删除";
            return true;
        }
        selectedNode = null;
        saveDraft();
        statusLine = "已擦除节点 #" + hit.nodeIndex();
        return true;
    }

    private RoadPlannerSegmentType segmentTypeForActiveTool() {
        return switch (state.activeTool()) {
            case BRIDGE -> RoadPlannerSegmentType.BRIDGE_MAJOR;
            case WATER_CROSSING -> RoadPlannerSegmentType.BRIDGE_MAJOR;
            case TUNNEL -> RoadPlannerSegmentType.TUNNEL;
            default -> RoadPlannerSegmentType.ROAD;
        };
    }

    private boolean setEndpointAt(BlockPos target) {
        if (!hasTownRoute) {
            if (startTownPos.equals(BlockPos.ZERO)) {
                startTownPos = snapToNearestNode(target);
                statusLine = "已设置起点标记";
            } else {
                destinationTownPos = snapToNearestNode(target);
                statusLine = "已设置终点标记";
            }
            selectedNode = null;
            return true;
        }
        if (RoadPlannerEndpointRules.isInRoleClaim(claimOverlayRenderer, target, RoadPlannerClaimOverlay.Role.START)) {
            startTownPos = snapToNearestNode(target);
            selectedNode = null;
            statusLine = "已设置起点标记";
            return true;
        }
        if (RoadPlannerEndpointRules.isInRoleClaim(claimOverlayRenderer, target, RoadPlannerClaimOverlay.Role.DESTINATION)) {
            destinationTownPos = snapToNearestNode(target);
            selectedNode = null;
            statusLine = "已设置终点标记";
            return true;
        }
        statusLine = "端点必须放在起点或目标 Town 领地内";
        return true;
    }

    private BlockPos snapToNearestNode(BlockPos target) {
        List<BlockPos> nodes = linePlan.nodes();
        if (nodes.isEmpty()) {
            return target.immutable();
        }
        BlockPos nearest = nodes.get(0);
        double bestDist = target.distSqr(nearest);
        for (int i = 1; i < nodes.size(); i++) {
            double dist = target.distSqr(nodes.get(i));
            if (dist < bestDist) {
                bestDist = dist;
                nearest = nodes.get(i);
            }
        }
        return nearest.immutable();
    }

    private RoadPlannerSegmentType segmentTypeForConnection(BlockPos target, RoadPlannerSegmentType fallback) {
        RoadPlannerSegmentType safeFallback = fallback == null ? RoadPlannerSegmentType.ROAD : fallback;
        if (safeFallback == RoadPlannerSegmentType.BRIDGE_MAJOR || safeFallback == RoadPlannerSegmentType.BRIDGE_SMALL || safeFallback == RoadPlannerSegmentType.TUNNEL) {
            return safeFallback;
        }
        if (requiresBridgeTool(target)) {
            return RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        if (!isClientLand(target.getX(), target.getZ())) {
            return RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        return safeFallback;
    }

    private boolean showsHoverPreviewLine() {
        return state.activeTool() != RoadToolType.SELECT
                && state.activeTool() != RoadToolType.ERASE
                && state.activeTool() != RoadToolType.ENDPOINT
                && state.activeTool() != RoadToolType.FORCE_RENDER;
    }

    private void addBezierNodes(BlockPos target, RoadPlannerSegmentType segmentType) {
        List<BlockPos> nodes = linePlan.nodes();
        if (nodes.isEmpty()) {
            if (hasTownRoute && !RoadPlannerEndpointRules.isInRoleClaim(claimOverlayRenderer, target, RoadPlannerClaimOverlay.Role.START)) {
                statusLine = "道路起点必须设置在起点 Town 领地内";
                return;
            }
            linePlan.addClickNode(target, segmentType);
            return;
        }
        BlockPos start = nodes.get(nodes.size() - 1);
        int dx = target.getX() - start.getX();
        int dz = target.getZ() - start.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        int samples = Math.max(2, Math.min(12, (int) Math.ceil(distance / 16.0D)));
        double normalX = distance == 0.0D ? 0.0D : -dz / distance;
        double normalZ = distance == 0.0D ? 0.0D : dx / distance;
        double curveStrength = Math.min(32.0D, distance * 0.18D);
        double controlX = (start.getX() + target.getX()) * 0.5D + normalX * curveStrength;
        double controlY = (start.getY() + target.getY()) * 0.5D;
        double controlZ = (start.getZ() + target.getZ()) * 0.5D + normalZ * curveStrength;
        for (int i = 1; i <= samples; i++) {
            double t = i / (double) samples;
            double oneMinusT = 1.0D - t;
            int x = (int) Math.round(oneMinusT * oneMinusT * start.getX() + 2.0D * oneMinusT * t * controlX + t * t * target.getX());
            int y = (int) Math.round(oneMinusT * oneMinusT * start.getY() + 2.0D * oneMinusT * t * controlY + t * t * target.getY());
            int z = (int) Math.round(oneMinusT * oneMinusT * start.getZ() + 2.0D * oneMinusT * t * controlZ + t * t * target.getZ());
            BlockPos sample = new BlockPos(x, y, z);
            if (!sample.equals(linePlan.nodes().get(linePlan.nodeCount() - 1))) {
                RoadPlannerSegmentType sampleType = segmentTypeForConnection(sample, segmentTypeForCurveSample(sample, segmentType));
                if (!isClientLand(sample.getX(), sample.getZ())) {
                    sampleType = RoadPlannerSegmentType.BRIDGE_MAJOR;
                }
                linePlan.addClickNode(sample, sampleType);
            }
        }
    }

    private RoadPlannerSegmentType segmentTypeForCurveSample(BlockPos sample, RoadPlannerSegmentType fallback) {
        RoadPlannerBridgeRuleService.Decision roadDecision = bridgeRuleService.evaluateRoadTool(linePlan.nodes(), sample);
        if (!roadDecision.accepted()) {
            return RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        return fallback == null ? RoadPlannerSegmentType.ROAD : fallback;
    }

    private boolean requiresBridgeTool(BlockPos target) {
        return !bridgeRuleService.evaluateRoadTool(linePlan.nodes(), target).accepted();
    }

    private boolean legacyToolbarHitTestingEnabled() {
        return false;
    }

    private RoadPlannerMapLayout.Rect toolButtonRect(int index) {
        RoadPlannerMapLayout.Rect toolbar = mapLayout.toolbar();
        return new RoadPlannerMapLayout.Rect(toolbar.x() + 8, toolbar.y() + 30 + index * 36, toolbar.width() - 16, 28);
    }

    private int actionIndexAt(double mouseX, double mouseY) {
        RoadPlannerMapLayout.Rect inspector = mapLayout.inspector();
        int buttonWidth = inspector.width() - 20;
        int gap = 6;
        int x = inspector.x() + 10;
        int y = inspector.y() + 108;
        for (int index = 0; index < ACTION_LABELS.size(); index++) {
            RoadPlannerMapLayout.Rect button = new RoadPlannerMapLayout.Rect(x, y + index * (22 + gap), buttonWidth, 22);
            if (button.contains(mouseX, mouseY)) {
                return index;
            }
        }
        return -1;
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
