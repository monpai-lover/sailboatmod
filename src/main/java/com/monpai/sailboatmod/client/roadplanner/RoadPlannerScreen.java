package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadMergeCandidatesPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotSyncPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoCompleteRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadCancelPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadProgressPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoMergeRouteRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoMergeRouteSyncPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMergeCandidateRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlayRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlaySyncPacket;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class RoadPlannerScreen extends Screen implements RoadPlannerTileSyncReceiver {
    private static final int OWN_ROAD_OVERLAY_COLOR = 0xCC8BD3FF;
    private static final int SHARED_ROAD_OVERLAY_COLOR = 0xCCB18CFF;
    private static final int SELECTED_MERGE_ANCHOR_COLOR = 0xFFFFF176;
    private static final int HOVERED_ROAD_OVERLAY_COLOR = 0xEEFFFFFF;
    private static final double ROAD_OVERLAY_HOVER_THRESHOLD = 5.0D;
    private static final DateTimeFormatter ROAD_OVERLAY_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final List<RoadToolType> TOOLS = List.of(
            RoadToolType.SELECT,
            RoadToolType.ROAD,
            RoadToolType.MERGE,
            RoadToolType.BRIDGE,
            RoadToolType.TUNNEL,
            RoadToolType.ERASE,
            RoadToolType.WATER_CROSSING,
            RoadToolType.BEZIER,
            RoadToolType.ENDPOINT,
            RoadToolType.FORCE_RENDER
    );
    private static final List<String> TOOL_LABELS = List.of("\u9009\u62e9", "\u9053\u8def", "\u5e76\u5165", "\u6865\u6881", "\u96a7\u9053", "\u64e6\u9664", "\u8de8\u6c34", "\u8d1d\u585e\u5c14", "\u7aef\u70b9", "\u5f3a\u5236\u6e32\u67d3");
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
    private RoadPlannerMapView mapView;
    private boolean mapViewUsingFallbackOrigin;
    private RoadPlannerMapCanvas canvas;
    private RoadPlannerClaimOverlayRenderer claimOverlayRenderer = new RoadPlannerClaimOverlayRenderer(List.of());
    private RoadPlannerTileManager tileManager;
    private boolean closeTileManagerOnRemoved;
    private RoadPlannerVanillaContextMenu contextMenu;
    private RoadNetworkGraph graph = new RoadNetworkGraph();
    private final RoadPlannerLinePlan linePlan = new RoadPlannerLinePlan();
    private final RoadPlannerForceRenderQueue forceRenderQueue = new RoadPlannerForceRenderQueue();
    private final RoadPlannerTileRenderScheduler tileRenderScheduler = new RoadPlannerTileRenderScheduler();
    private final RoadPlannerMinimapRequestScheduler mapRequestScheduler = new RoadPlannerMinimapRequestScheduler(350L);
    private final RoadPlannerAutoCompleteService autoCompleteService = new RoadPlannerAutoCompleteService();
    private final RoadPlannerBridgeRuleService bridgeRuleService = new RoadPlannerBridgeRuleService(RoadPlannerScreen::isClientLand);
    private final RoadPlannerNodeHitTester nodeHitTester = new RoadPlannerNodeHitTester(8.0D);
    private final RoadPlannerRouteHitTester routeHitTester = new RoadPlannerRouteHitTester(8.0D, 6.0D);
    private final RoadPlannerEraseTool eraseTool = new RoadPlannerEraseTool();
    private final RoadPlannerDraftPersistence draftPersistence;
    private final RoadPlannerRoutePreloadScheduler routePreloadScheduler = new RoadPlannerRoutePreloadScheduler();
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
    private String mapStatusLine = "等待地图请求";
    private RoadPlannerMapPreloadRequestPacket lastMapPreloadRequest;
    private RoadPlannerMapPreloadProgressPacket lastMapPreloadProgress;
    private RoadPlannerMapPreloadCancelPacket lastMapPreloadCancel;
    private RoadPlannerMergeScope mergeScope = RoadPlannerMergeScope.OWN_NATION;
    private List<OpenRoadMergeCandidatesPacket.Entry> mergeCandidates = List.of();
    private int selectedMergeCandidateIndex = -1;
    private RoadPlannerMergeCandidateRequestPacket lastMergeCandidateRequest;
    private RoadPlannerAutoMergeRouteRequestPacket lastAutoMergeRouteRequest;
    private RoadPlannerAutoMergeState autoMergeState = RoadPlannerAutoMergeState.idle();
    private List<RoadPlannerRoadOverlaySyncPacket.Entry> roadOverlays = List.of();
    private RoadPlannerRoadOverlayRequestPacket lastRoadOverlayRequest;
    private RoadPlannerRoadOverlaySyncPacket.Entry selectedMergeOverlay;
    private RoadPlannerRoadOverlaySyncPacket.Entry selectedBuiltRoadOverlay;
    private int selectedBuiltRoadNodeIndex = -1;
    private RoadPlannerSharedRoadSpan startReuseSpan = RoadPlannerSharedRoadSpan.none();
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
        BlockPos playerPos = testMode ? null : currentClientPlayerPos();
        this.mapView = initialMapViewForPlayer(playerPos);
        this.mapViewUsingFallbackOrigin = playerPos == null;
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
        this.mapViewUsingFallbackOrigin = false;
        linePlan.clear();
        RoadPlannerDraftStore.Draft draft = testMode ? null : RoadPlannerDraftStore.get(state.sessionId());
        if (!testMode && draft == null) {
            draft = draftPersistence.load(state.sessionId()).orElse(null);
        }
        if (!testMode && draft == null && routeDraftId != null) {
            draft = RoadPlannerDraftStore.get(routeDraftId);
        }
        if (!testMode && draft == null && routeDraftId != null) {
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

    private static int clientWaterDepth(int x, int z) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return 0;
        }
        ClientLevel level = minecraft.level;
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (surfaceY < level.getMinBuildHeight()) {
            return 0;
        }
        BlockPos surface = new BlockPos(x, surfaceY, z);
        if (!level.getBlockState(surface).getFluidState().is(Fluids.WATER)
                && !level.getBlockState(surface.above()).getFluidState().is(Fluids.WATER)) {
            return 0;
        }
        int depth = 0;
        for (int y = surfaceY; y >= level.getMinBuildHeight(); y--) {
            BlockPos probe = new BlockPos(x, y, z);
            if (!level.getBlockState(probe).getFluidState().is(Fluids.WATER)) {
                break;
            }
            depth++;
            if (depth >= 16) {
                break;
            }
        }
        return depth;
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

    public BlockPos plannedNodeForTest(int nodeIndex) {
        return linePlan.nodes().get(nodeIndex);
    }

    public List<BlockPos> plannedNodesForTest() {
        return linePlan.nodes();
    }

    public RoadPlannerSegmentType segmentTypeForTest(int segmentIndex) {
        return linePlan.segments().get(segmentIndex);
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

    public String mapStatusLineForTest() {
        return mapStatusLine;
    }

    public RoadPlannerMapPreloadRequestPacket lastMapPreloadRequestForTest() {
        return lastMapPreloadRequest;
    }

    public RoadPlannerMapPreloadProgressPacket lastMapPreloadProgressForTest() {
        return lastMapPreloadProgress;
    }

    public RoadPlannerMapPreloadCancelPacket lastMapPreloadCancelForTest() {
        return lastMapPreloadCancel;
    }

    public RoadPlannerMergeCandidateRequestPacket lastMergeCandidateRequestForTest() {
        return lastMergeCandidateRequest;
    }

    public RoadPlannerAutoMergeRouteRequestPacket lastAutoMergeRouteRequestForTest() {
        return lastAutoMergeRouteRequest;
    }

    public RoadPlannerRoadOverlayRequestPacket lastRoadOverlayRequestForTest() {
        return lastRoadOverlayRequest;
    }

    public int roadOverlayCountForTest() {
        return roadOverlays.size();
    }

    public List<RoadOverlayRenderStateForTest> roadOverlayRenderStateForTest() {
        RoadPlannerMergeSelection selectedMerge = activeMergeSelectionForRendering();
        List<RoadPlannerSharedRoadSpan> activeSharedSpans = activeSharedSpansForRendering(selectedMerge);
        List<RoadOverlayRenderStateForTest> states = new ArrayList<>(roadOverlays.size());
        for (RoadPlannerRoadOverlaySyncPacket.Entry overlay : roadOverlays) {
            states.add(new RoadOverlayRenderStateForTest(
                    overlay.roadId(),
                    overlay.relationship(),
                    roadOverlayColor(overlay.relationship()),
                    overlay.displayPath().size(),
                    isSelectedMergeAnchor(overlay, selectedMerge),
                    sharedSpanNodeCount(overlay, activeSharedSpans)));
        }
        return List.copyOf(states);
    }

    public RoadOverlayTooltipForTest roadOverlayTooltipForTest(int mouseX, int mouseY) {
        RoadPlannerRoadOverlayHitTester.Result hit = hoveredRoadOverlay(mouseX, mouseY);
        if (!hit.hit()) {
            return null;
        }
        List<String> lines = roadOverlayTooltipLines(hit.entry()).stream()
                .map(Component::getString)
                .toList();
        return new RoadOverlayTooltipForTest(hit.entry().roadId(), hit.entry().displayName(), lines);
    }

    public RoadPlannerMergeScope mergeScopeForTest() {
        return mergeScope;
    }

    public int mergeCandidateCountForTest() {
        return mergeCandidates.size();
    }

    public RoadPlannerMergeSelection selectedMergeSelectionForTest() {
        return selectedMergeSelection();
    }

    public void setTileManagerForTest(RoadPlannerTileManager tileManager) {
        if (this.tileManager != null && closeTileManagerOnRemoved && this.tileManager != tileManager) {
            this.tileManager.close();
        }
        this.tileManager = tileManager;
        this.closeTileManagerOnRemoved = tileManager != null;
    }

    public void applyMapPreloadProgress(RoadPlannerMapPreloadProgressPacket packet) {
        if (packet == null || !state.sessionId().equals(packet.sessionId())) {
            return;
        }
        if (!routePreloadScheduler.acceptsResponse(packet.sessionId(), packet.requestId(), packet.purpose(), packet.worldId(), packet.dimensionId())) {
            return;
        }
        lastMapPreloadProgress = packet;
        int percent = packet.totalTiles() <= 0 ? 0 : Math.min(100, (int) Math.round(packet.completedTiles() * 100.0D / packet.totalTiles()));
        String message = packet.message() == null || packet.message().isBlank() ? packet.state().name() : packet.message();
        mapStatusLine = "地图预加载: " + percent + "% " + message;
    }

    public void applyMapTileSync(RoadPlannerMapTileSyncPacket packet) {
        if (packet == null) {
            return;
        }
        if (packet.purpose() == RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH) {
            if (tileManager != null && matchesTileManagerWorld(packet)) {
                int applied = tileManager.applyTileSync(packet);
                tileRenderScheduler.clear();
                if (applied > 0) {
                    mapStatusLine = "\u5730\u56fe: \u5df2\u5237\u65b0\u5df2\u5efa\u9053\u8def\u533a\u5757";
                }
            }
            return;
        }
        if (!state.sessionId().equals(packet.sessionId())) {
            return;
        }
        if (!routePreloadScheduler.acceptsResponse(packet.sessionId(), packet.requestId(), packet.purpose(), packet.worldId(), packet.dimensionId())) {
            return;
        }
        if (tileManager != null) {
            tileManager.applyTileSync(packet);
            mapStatusLine = "地图: 已接收预加载切片";
        }
    }

    private boolean matchesTileManagerWorld(RoadPlannerMapTileSyncPacket packet) {
        if (tileManager == null || packet == null) {
            return false;
        }
        return (packet.worldId().isBlank() || packet.worldId().equals(tileManager.worldId()))
                && (packet.dimensionId().isBlank() || packet.dimensionId().equals(tileManager.dimensionId()));
    }

    private String currentDimensionId() {
        if (tileManager != null && !tileManager.dimensionId().isBlank()) {
            return tileManager.dimensionId();
        }
        if (minecraft != null && minecraft.level != null) {
            return minecraft.level.dimension().location().toString();
        }
        return "minecraft:overworld";
    }

    private void requestEnterPlannerPreload() {
        if (!hasTownRoute && linePlan.nodeCount() == 0) {
            return;
        }
        String worldId = tileManager == null ? "" : tileManager.worldId();
        String dimensionId = tileManager == null ? "" : tileManager.dimensionId();
        routePreloadScheduler.entryRequest(
                state.sessionId(),
                worldId,
                dimensionId,
                startTownPos,
                destinationTownPos,
                linePlan.nodes())
                .ifPresent(this::sendMapPreloadRequest);
    }

    private void requestRoutePreload(List<BlockPos> routeNodes) {
        String worldId = tileManager == null ? "" : tileManager.worldId();
        String dimensionId = tileManager == null ? "" : tileManager.dimensionId();
        routePreloadScheduler.routeRequest(
                state.sessionId(),
                worldId,
                dimensionId,
                routeNodes)
                .ifPresent(this::sendMapPreloadRequest);
    }

    private void requestForceRenderPreload(BlockPos start, BlockPos destination) {
        String worldId = tileManager == null ? "" : tileManager.worldId();
        String dimensionId = tileManager == null ? "" : tileManager.dimensionId();
        routePreloadScheduler.forceRenderRequest(
                state.sessionId(),
                worldId,
                dimensionId,
                start,
                destination)
                .ifPresent(this::sendMapPreloadRequest);
    }

    private void sendMapPreloadRequest(RoadPlannerRoutePreloadScheduler.Request request) {
        if (request == null) {
            return;
        }
        lastMapPreloadRequest = request.toPacket();
        mapStatusLine = switch (request.purpose()) {
            case ENTER_PLANNER_PRELOAD -> "地图: 进入规划器预热中";
            case ROUTE_PRELOAD -> "地图: 路线预热中";
            case FORCE_RENDER -> "地图: 强制渲染预热中";
            case BUILT_ROAD_REFRESH -> "地图: 已建道路刷新中";
        };
        if (testMode || minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(lastMapPreloadRequest);
    }

    private void sendMapPreloadCancel() {
        RoadPlannerRoutePreloadScheduler.Request activeRequest = routePreloadScheduler.latestRequest().orElse(null);
        if (activeRequest == null) {
            return;
        }
        lastMapPreloadCancel = activeRequest.toCancelPacket();
        if (testMode || minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(lastMapPreloadCancel);
    }

    private void requestMergeCandidates(BlockPos probe, RoadPlannerSegmentType segmentType) {
        RoadPlannerSegmentType safeSegmentType = segmentType == null ? RoadPlannerSegmentType.ROAD : segmentType;
        if (probe == null || !mergeScope.enabled()) {
            return;
        }
        if (state.activeTool() == RoadToolType.BRIDGE || state.activeTool() == RoadToolType.WATER_CROSSING) {
            return;
        }
        if (isBridgeLikeSegment(safeSegmentType)) {
            return;
        }
        RoadPlannerMergeCandidateRequestPacket request = new RoadPlannerMergeCandidateRequestPacket(
                state.sessionId(),
                UUID.randomUUID(),
                probe,
                RoadPlannerMergeCandidateRequestPacket.MAX_RADIUS,
                mergeScope,
                safeSegmentType
        );
        lastMergeCandidateRequest = request;
        if (testMode || minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(request);
    }

    private void requestAutoMergeRoute() {
        BlockPos currentEndpoint = lastNode();
        if (currentEndpoint == null || !mergeScope.enabled() || isBridgeLikeSegment(lastSegmentType())) {
            autoMergeState = RoadPlannerAutoMergeState.idle();
            lastAutoMergeRouteRequest = null;
            return;
        }
        RoadPlannerAutoMergeRouteRequestPacket request = new RoadPlannerAutoMergeRouteRequestPacket(
                state.sessionId(),
                UUID.randomUUID(),
                currentDimensionId(),
                linePlan.nodes(),
                linePlan.segments(),
                destinationTownPos,
                mergeScope,
                "",
                -1);
        lastAutoMergeRouteRequest = request;
        autoMergeState = RoadPlannerAutoMergeState.pending(request.requestId());
        if (testMode || minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(request);
    }

    private void requestMergeToolRoutes() {
        clearMergeCandidateState();
        requestMergeCandidates(lastNode(), lastSegmentType());
        requestAutoMergeRoute();
    }

    private void clearMergeCandidateState() {
        mergeCandidates = List.of();
        selectedMergeCandidateIndex = -1;
        lastMergeCandidateRequest = null;
        lastAutoMergeRouteRequest = null;
        autoMergeState = RoadPlannerAutoMergeState.idle();
        selectedMergeOverlay = null;
    }

    private void clearBuiltRoadNodeSelection() {
        selectedBuiltRoadOverlay = null;
        selectedBuiltRoadNodeIndex = -1;
    }

    private void clearStartReuseSpan() {
        startReuseSpan = RoadPlannerSharedRoadSpan.none();
        clearBuiltRoadNodeSelection();
    }

    private void clearAndRequestMergeCandidates() {
        clearMergeCandidateState();
        requestMergeCandidates(lastNode(), lastSegmentType());
        if (state.activeTool() == RoadToolType.MERGE) {
            requestAutoMergeRoute();
        }
    }

    private boolean selectMergeAnchorAt(double mouseX, double mouseY) {
        if (!mergeScope.enabled()) {
            clearMergeCandidateState();
            statusLine = "\u5438\u9644\u8303\u56f4\u5df2\u5173\u95ed";
            return true;
        }
        BlockPos currentEndpoint = lastNode();
        if (currentEndpoint == null) {
            clearMergeCandidateState();
            statusLine = "\u8bf7\u5148\u653e\u7f6e\u89c4\u5212\u9053\u8def\u8282\u70b9";
            return true;
        }
        if (isBridgeLikeSegment(lastSegmentType())) {
            clearMergeCandidateState();
            statusLine = "\u6865\u6881\u6bb5\u7981\u6b62\u5e76\u5165\u73b0\u6709\u9053\u8def";
            return true;
        }
        RoadPlannerRoadOverlayHitTester.Result hit = hoveredRoadOverlay((int) Math.round(mouseX), (int) Math.round(mouseY));
        if (hit == null || !hit.hit()) {
            clearMergeCandidateState();
            statusLine = "\u672a\u9009\u4e2d\u53ef\u5e76\u5165\u9053\u8def";
            return true;
        }
        OverlayAnchor anchor = nearestOverlayAnchor(hit.entry(), currentEndpoint);
        if (anchor == null) {
            clearMergeCandidateState();
            statusLine = "\u73b0\u6709\u9053\u8def\u6ca1\u6709\u53ef\u7528\u8282\u70b9";
            return true;
        }
        String[] routeNames = routeNamesFromDisplayName(hit.entry().displayName());
        mergeCandidates = List.of(new OpenRoadMergeCandidatesPacket.Entry(
                hit.entry().roadId(),
                anchor.pos(),
                anchor.pathIndex(),
                horizontalDistanceBlocks(currentEndpoint, anchor.pos()),
                routeNames[0],
                routeNames[1],
                "",
                hit.entry().relationship()));
        selectedMergeCandidateIndex = 0;
        selectedMergeOverlay = hit.entry();
        autoMergeState = autoMergeState.manualFallback();
        statusLine = "\u5df2\u9009\u62e9\u5e76\u5165: " + hit.entry().displayName() + "\uff0c\u8bf7\u70b9\u51fb\u786e\u8ba4\u5e76\u5165";
        return true;
    }

    private void confirmSelectedMergeAnchor() {
        RoadPlannerMergeSelection selection = selectedMergeSelection();
        if (!selection.present()) {
            statusLine = "\u6682\u65e0\u53ef\u786e\u8ba4\u5e76\u5165\u9053\u8def";
            return;
        }
        if (lastNode() == null) {
            clearMergeCandidateState();
            statusLine = "\u8bf7\u5148\u653e\u7f6e\u89c4\u5212\u9053\u8def\u8282\u70b9";
            return;
        }
        if (isBridgeLikeSegment(lastSegmentType())) {
            clearMergeCandidateState();
            statusLine = "\u6865\u6881\u6bb5\u7981\u6b62\u5e76\u5165\u73b0\u6709\u9053\u8def";
            return;
        }
        if (autoMergeState.found()) {
            confirmAutoMergeRoute(selection);
            return;
        }
        OpenRoadMergeCandidatesPacket.Entry candidate = mergeCandidates.get(selectedMergeCandidateIndex);
        BlockPos anchor = selection.anchorPos();
        if (!anchor.equals(lastNode())) {
            linePlan.addClickNode(anchor, RoadPlannerSegmentType.ROAD);
        }
        int appendedTailNodes = appendSharedMergeTail(selection);
        selectedNode = null;
        saveDraft();
        requestRoutePreload(linePlan.nodes());
        statusLine = "\u5df2\u786e\u8ba4\u5e76\u5165: " + candidate.sourceName() + " -> " + candidate.targetName();
    }

    private void confirmAutoMergeRoute(RoadPlannerMergeSelection selection) {
        if (selection == null || !selection.present() || !autoMergeState.found()) {
            statusLine = "\u6682\u65e0\u53ef\u786e\u8ba4\u5e76\u5165\u9053\u8def";
            return;
        }
        BlockPos anchor = selection.anchorPos();
        if (!anchor.equals(lastNode())) {
            linePlan.addClickNode(anchor, RoadPlannerSegmentType.ROAD);
        }
        for (BlockPos node : autoMergeState.displayPath()) {
            if (node == null || node.equals(lastNode())) {
                continue;
            }
            linePlan.addClickNode(node, RoadPlannerSegmentType.ROAD);
        }
        selectedMergeOverlay = roadOverlays.stream()
                .filter(entry -> entry != null && autoMergeState.roadIds().contains(entry.roadId()))
                .findFirst()
                .orElse(selectedMergeOverlay);
        selectedNode = null;
        saveDraft();
        requestRoutePreload(linePlan.nodes());
        statusLine = "\u5df2\u81ea\u52a8\u5e76\u5165\u73b0\u6709\u9053\u8def";
    }

    private int appendSharedMergeTail(RoadPlannerMergeSelection selection) {
        if (selection == null || !selection.present()) {
            return 0;
        }
        RoadPlannerRoadOverlaySyncPacket.Entry overlay = selectedMergeOverlay;
        if (overlay == null || !overlay.roadId().equals(selection.roadId())) {
            overlay = roadOverlays.stream()
                    .filter(entry -> entry != null && entry.roadId().equals(selection.roadId()))
                    .findFirst()
                    .orElse(null);
        }
        if (overlay == null || overlay.displayPath().isEmpty()) {
            return 0;
        }
        List<BlockPos> path = overlay.displayPath();
        int anchorIndex = displayIndexForPathIndex(overlay, selection.pathIndex(), selection.anchorPos());
        if (anchorIndex < 0) {
            anchorIndex = indexOfNode(path, selection.anchorPos());
        }
        if (anchorIndex < 0) {
            return 0;
        }
        int targetIndex = nearestPathNodeIndex(path, destinationTownPos);
        if (targetIndex < 0 || targetIndex == anchorIndex) {
            return 0;
        }
        int step = targetIndex > anchorIndex ? 1 : -1;
        int appended = 0;
        for (int index = anchorIndex + step; ; index += step) {
            BlockPos node = path.get(index);
            if (!node.equals(lastNode())) {
                linePlan.addClickNode(node, RoadPlannerSegmentType.ROAD);
                appended++;
            }
            if (index == targetIndex) {
                break;
            }
        }
        return appended;
    }

    private void continuePlanningFromBuiltRoadNode() {
        RoadPlannerRoadOverlaySyncPacket.Entry overlay = selectedBuiltRoadOverlay;
        if (overlay == null || overlay.displayPath().isEmpty() || selectedBuiltRoadNodeIndex < 0 || selectedBuiltRoadNodeIndex >= overlay.displayPath().size()) {
            statusLine = "\u672a\u9009\u4e2d\u53ef\u590d\u7528\u7684\u5df2\u5efa\u9053\u8def\u8282\u70b9";
            clearBuiltRoadNodeSelection();
            return;
        }
        List<BlockPos> path = overlay.displayPath();
        int startIndex = nearestPathNodeIndex(path, startTownPos);
        if (startIndex < 0 || startIndex == selectedBuiltRoadNodeIndex) {
            statusLine = "\u5df2\u5efa\u9053\u8def\u590d\u7528\u6bb5\u8fc7\u77ed";
            return;
        }
        int step = selectedBuiltRoadNodeIndex > startIndex ? 1 : -1;
        List<BlockPos> reusedNodes = new ArrayList<>();
        for (int index = startIndex; ; index += step) {
            reusedNodes.add(path.get(index));
            if (index == selectedBuiltRoadNodeIndex) {
                break;
            }
        }
        List<RoadPlannerSegmentType> reusedSegments = new ArrayList<>();
        for (int index = 1; index < reusedNodes.size(); index++) {
            reusedSegments.add(RoadPlannerSegmentType.ROAD);
        }
        linePlan.replaceWith(reusedNodes, reusedSegments);
        int startPathIndex = pathIndexForDisplayIndex(overlay, startIndex);
        int selectedPathIndex = pathIndexForDisplayIndex(overlay, selectedBuiltRoadNodeIndex);
        startReuseSpan = new RoadPlannerSharedRoadSpan(
                overlay.roadId(),
                startPathIndex,
                selectedPathIndex,
                path.get(startIndex),
                path.get(selectedBuiltRoadNodeIndex),
                mergeScope.enabled() ? mergeScope : RoadPlannerMergeScope.OWN_NATION,
                RoadPlannerSharedRoadSpan.Role.START_REUSE);
        selectedNode = null;
        clearMergeCandidateState();
        saveDraft();
        requestRoutePreload(linePlan.nodes());
        requestMergeCandidates(lastNode(), lastSegmentType());
        if (contextMenu != null) {
            contextMenu.close();
        }
        statusLine = "\u5df2\u590d\u7528\u5df2\u5efa\u9053\u8def: " + overlay.displayName() + "\uff0c\u53ef\u4ece\u8be5\u8282\u70b9\u7ee7\u7eed\u89c4\u5212";
    }

    private RoadPlannerMergeSelection selectedMergeSelection() {
        if (autoMergeState.found()) {
            return autoMergeState.selection();
        }
        if (!mergeScope.enabled() || selectedMergeCandidateIndex < 0 || selectedMergeCandidateIndex >= mergeCandidates.size()) {
            return RoadPlannerMergeSelection.none();
        }
        OpenRoadMergeCandidatesPacket.Entry candidate = mergeCandidates.get(selectedMergeCandidateIndex);
        return new RoadPlannerMergeSelection(candidate.roadId(), candidate.pathIndex(), candidate.anchorPos(), mergeScope);
    }

    private void cycleMergeScope() {
        mergeScope = switch (mergeScope) {
            case OWN_NATION -> RoadPlannerMergeScope.ALLIED_OR_TRADE;
            case ALLIED_OR_TRADE -> RoadPlannerMergeScope.DISABLED;
            case DISABLED -> RoadPlannerMergeScope.OWN_NATION;
        };
        clearMergeCandidateState();
        if (mergeScope.enabled()) {
            requestMergeCandidates(lastNode(), lastSegmentType());
            if (state.activeTool() == RoadToolType.MERGE) {
                requestAutoMergeRoute();
            }
        }
        requestRoadOverlays();
        statusLine = "\u5438\u9644\u8303\u56f4: " + mergeScope.name();
    }

    private void cycleMergeCandidate() {
        if (mergeCandidates.isEmpty()) {
            selectedMergeCandidateIndex = -1;
            statusLine = "\u6682\u65e0\u53ef\u5e76\u5165\u9053\u8def";
            return;
        }
        selectedMergeCandidateIndex = selectedMergeCandidateIndex < 0
                ? 0
                : (selectedMergeCandidateIndex + 1) % mergeCandidates.size();
        OpenRoadMergeCandidatesPacket.Entry candidate = mergeCandidates.get(selectedMergeCandidateIndex);
        statusLine = "\u5e76\u5165: " + candidate.sourceName() + " -> " + candidate.targetName();
    }

    private BlockPos lastNode() {
        return linePlan.nodeCount() == 0 ? null : linePlan.nodes().get(linePlan.nodeCount() - 1);
    }

    private RoadPlannerSegmentType lastSegmentType() {
        return linePlan.segmentCount() == 0 ? RoadPlannerSegmentType.ROAD : linePlan.segments().get(linePlan.segmentCount() - 1);
    }

    private OverlayAnchor nearestOverlayAnchor(RoadPlannerRoadOverlaySyncPacket.Entry overlay, BlockPos probe) {
        if (overlay == null || overlay.displayPath().isEmpty() || probe == null) {
            return null;
        }
        int bestDisplayIndex = -1;
        int bestPathIndex = -1;
        BlockPos bestPos = null;
        long bestDistance = Long.MAX_VALUE;
        List<BlockPos> path = overlay.displayPath();
        for (int index = 0; index < path.size(); index++) {
            BlockPos pos = path.get(index);
            long dx = (long) pos.getX() - probe.getX();
            long dz = (long) pos.getZ() - probe.getZ();
            long distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestDisplayIndex = index;
                bestPathIndex = pathIndexForDisplayIndex(overlay, index);
                bestPos = pos;
            }
        }
        return bestPos == null ? null : new OverlayAnchor(bestDisplayIndex, bestPathIndex, bestPos);
    }

    private int horizontalDistanceBlocks(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return 0;
        }
        long dx = (long) to.getX() - from.getX();
        long dz = (long) to.getZ() - from.getZ();
        return (int) Math.max(0, Math.round(Math.sqrt(dx * dx + dz * dz)));
    }

    private int nearestPathNodeIndex(List<BlockPos> path, BlockPos target) {
        if (path == null || path.isEmpty() || target == null) {
            return -1;
        }
        int bestIndex = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 0; index < path.size(); index++) {
            BlockPos pos = path.get(index);
            if (pos == null) {
                continue;
            }
            long dx = (long) pos.getX() - target.getX();
            long dz = (long) pos.getZ() - target.getZ();
            long distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private int pathIndexForDisplayIndex(RoadPlannerRoadOverlaySyncPacket.Entry overlay, int displayIndex) {
        if (overlay == null || displayIndex < 0 || displayIndex >= overlay.displayPath().size()) {
            return -1;
        }
        List<Integer> indices = overlay.displayPathPathIndices();
        if (displayIndex < indices.size()) {
            int mapped = indices.get(displayIndex);
            if (mapped >= 0) {
                return mapped;
            }
        }
        return indexOfNode(overlay.path(), overlay.displayPath().get(displayIndex));
    }

    private int displayIndexForPathIndex(RoadPlannerRoadOverlaySyncPacket.Entry overlay, int pathIndex, BlockPos anchorPos) {
        if (overlay == null || overlay.displayPath().isEmpty()) {
            return -1;
        }
        List<Integer> indices = overlay.displayPathPathIndices();
        for (int index = 0; index < indices.size() && index < overlay.displayPath().size(); index++) {
            if (indices.get(index) == pathIndex) {
                return index;
            }
        }
        if (anchorPos != null) {
            return indexOfNode(overlay.displayPath(), anchorPos);
        }
        return -1;
    }

    private int indexOfNode(List<BlockPos> path, BlockPos target) {
        if (path == null || target == null) {
            return -1;
        }
        for (int index = 0; index < path.size(); index++) {
            if (target.equals(path.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private String[] routeNamesFromDisplayName(String displayName) {
        String safeName = displayName == null ? "" : displayName.trim();
        int separator = safeName.indexOf(" - ");
        if (separator > 0 && separator + 3 < safeName.length()) {
            return new String[] {
                    safeName.substring(0, separator).trim(),
                    safeName.substring(separator + 3).trim()
            };
        }
        return new String[] { safeName.isBlank() ? "-" : safeName, "-" };
    }

    private boolean isBridgeLikeSegment(RoadPlannerSegmentType segmentType) {
        return segmentType == RoadPlannerSegmentType.BRIDGE_SMALL
                || segmentType == RoadPlannerSegmentType.BRIDGE_MAJOR
                || segmentType == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE;
    }

    public void applyMapSnapshot(RoadMapSnapshotSyncPacket packet) {
        if (packet == null || !state.sessionId().equals(packet.sessionId())) {
            return;
        }
        BlockPos center = new BlockPos(packet.regionCenterX(), 0, packet.regionCenterZ());
        if (!mapRequestScheduler.acceptsResponse(packet.requestId(), packet.purpose(), center, packet.regionSize())) {
            mapStatusLine = "等待地图请求";
            return;
        }
        if (tileManager == null) {
            mapStatusLine = "地图: tile 管理器未就绪";
            return;
        }
        int applied = tileManager.applySnapshot(packet);
        mapRequestScheduler.markCompleted(packet.requestId());
        mapStatusLine = applied > 0 ? "地图: 已更新" : "地图: 未收到可用像素";
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
            statusLine = message == null || message.isBlank() ? "\u81ea\u52a8\u8865\u5168\u5931\u8d25" : message;
            return;
        }
        clearMergeCandidateState();
        clearStartReuseSpan();
        RoadPlannerRouteExpander.Result expanded = expandRoute(nodes, segmentTypes);
        linePlan.replaceWith(expanded.nodes(), expanded.segmentTypes());
        saveDraft();
        if (expanded.nodes().size() >= 2) {
            forceRenderQueue.enqueueCorridor(expanded.nodes().get(0), expanded.nodes().get(expanded.nodes().size() - 1), 64, "\u81ea\u52a8\u8def\u7ebf\u7f13\u5b58");
        }
        requestRoutePreload(expanded.nodes());
        requestMergeCandidates(lastNode(), lastSegmentType());
        statusLine = message == null || message.isBlank() ? "\u81ea\u52a8\u8865\u5168\u5b8c\u6210" : message;
    }

    public void applyRoadMergeCandidates(UUID sessionId, List<OpenRoadMergeCandidatesPacket.Entry> candidates) {
        applyRoadMergeCandidates(sessionId, new UUID(0L, 0L), candidates);
    }

    public void applyRoadMergeCandidates(UUID sessionId, UUID requestId, List<OpenRoadMergeCandidatesPacket.Entry> candidates) {
        if (!state.sessionId().equals(sessionId) || lastMergeCandidateRequest == null
                || !lastMergeCandidateRequest.requestId().equals(requestId)) {
            return;
        }
        mergeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        selectedMergeCandidateIndex = mergeCandidates.isEmpty() ? -1 : 0;
    }

    public void applyAutoMergeRoute(RoadPlannerAutoMergeRouteSyncPacket packet) {
        if (packet == null || !state.sessionId().equals(packet.sessionId()) || lastAutoMergeRouteRequest == null
                || !lastAutoMergeRouteRequest.requestId().equals(packet.requestId())) {
            return;
        }
        autoMergeState = RoadPlannerAutoMergeState.fromSync(packet);
        if (autoMergeState.found()) {
            selectedMergeOverlay = roadOverlays.stream()
                    .filter(entry -> entry != null && autoMergeState.roadIds().contains(entry.roadId()))
                    .findFirst()
                    .orElse(selectedMergeOverlay);
            statusLine = "\u5df2\u627e\u5230\u53ef\u81ea\u52a8\u5e76\u5165\u76ee\u7684\u5730\u7684\u73b0\u6709\u9053\u8def\uff0c\u8bf7\u786e\u8ba4\u5e76\u5165";
        } else {
            statusLine = "\u6ca1\u6709\u627e\u5230\u53ef\u76f4\u8fbe\u76ee\u7684\u5730\u7684\u73b0\u6709\u9053\u8def\uff0c\u53ef\u7ee7\u7eed\u624b\u52a8\u591a\u6b21\u5e76\u5165";
            requestMergeCandidates(lastNode(), lastSegmentType());
        }
    }

    public void applyRoadOverlays(UUID sessionId, List<RoadPlannerRoadOverlaySyncPacket.Entry> roads) {
        RoadPlannerRoadOverlayRequestPacket request = lastRoadOverlayRequest;
        if (request != null) {
            applyRoadOverlays(sessionId, request.regionCenter(), request.regionSize(), request.scope(), roads);
            return;
        }
        if (!state.sessionId().equals(sessionId)) {
            return;
        }
        roadOverlays = roads == null ? List.of() : List.copyOf(roads);
    }

    public void applyRoadOverlays(UUID sessionId,
                                  BlockPos regionCenter,
                                  int regionSize,
                                  RoadPlannerMergeScope scope,
                                  List<RoadPlannerRoadOverlaySyncPacket.Entry> roads) {
        if (!state.sessionId().equals(sessionId)) {
            return;
        }
        RoadPlannerRoadOverlayRequestPacket request = lastRoadOverlayRequest;
        if (request == null
                || !request.regionCenter().equals(regionCenter)
                || request.regionSize() != RoadPlannerRoadOverlayRequestPacket.normalizeRegionSize(regionSize)
                || request.scope() != scope) {
            return;
        }
        roadOverlays = roads == null ? List.of() : List.copyOf(roads);
    }

    public void setGraphForTest(RoadNetworkGraph graph) {
        this.graph = graph == null ? new RoadNetworkGraph() : graph;
    }

    public boolean rightClickMapForTest(double worldX, double worldZ, int mouseX, int mouseY) {
        return openContextMenuAtWorld(worldX, worldZ, mouseX, mouseY);
    }

    public boolean clickWorldForTest(int worldX, int worldZ) {
        return setEndpointAt(new BlockPos(worldX, 64, worldZ));
    }

    @Override
    protected void init() {
        centerFallbackMapViewOnPlayerIfAvailable();
        recomputeLayout();
        requestEnterPlannerPreload();
        requestInitialMapSnapshot();
    }

    @Override
    public void removed() {
        sendMapPreloadCancel();
        super.removed();
        if (tileManager != null && closeTileManagerOnRemoved) {
            tileManager.close();
        }
        tileManager = null;
        closeTileManagerOnRemoved = false;
        tileRenderScheduler.close();
    }

    private void recomputeLayout() {
        mapLayout = RoadPlannerMapLayout.compute(width, height);
        compatibilityLayout = RoadPlannerVanillaLayout.compute(width, height);
        RoadMapRegion region = RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_1);
        RoadMapViewport viewport = new RoadMapViewport(mapLayout.map().x(), mapLayout.map().y(), mapLayout.map().width(), mapLayout.map().height());
        if (!testMode && tileManager == null) {
            tileManager = RoadPlannerTileManager.sharedDefault();
            closeTileManagerOnRemoved = false;
        }
        RoadPlannerHeightSampler heightSampler = testMode ? (x, z) -> 64 : RoadPlannerHeightSampler.clientLoadedTerrain();
        canvas = new RoadPlannerMapCanvas(mapLayout.map().asVanillaRect(), new RoadPlannerMapComponent(region, viewport), mapView, tileManager, heightSampler);
    }

    static RoadPlannerMapView initialMapViewForPlayerForTest(BlockPos playerPos) {
        return initialMapViewForPlayer(playerPos);
    }

    private static RoadPlannerMapView initialMapViewForPlayer(BlockPos playerPos) {
        if (playerPos == null) {
            return RoadPlannerMapView.centered(0, 0, 2.0D);
        }
        return RoadPlannerMapView.centered(playerPos.getX(), playerPos.getZ(), 2.0D);
    }

    private void centerFallbackMapViewOnPlayerIfAvailable() {
        if (testMode || hasTownRoute || !mapViewUsingFallbackOrigin) {
            return;
        }
        BlockPos playerPos = currentClientPlayerPos();
        if (playerPos == null) {
            return;
        }
        this.mapView = initialMapViewForPlayer(playerPos);
        this.mapViewUsingFallbackOrigin = false;
    }

    private static BlockPos currentClientPlayerPos() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return null;
        }
        return minecraft.player.blockPosition();
    }

    private void requestInitialMapSnapshot() {
        if (testMode || tileManager == null || mapLayout == null) {
            requestRoadOverlays();
            mapStatusLine = "等待地图请求";
            return;
        }
        mapRequestScheduler.initialRequest(System.currentTimeMillis(), state.sessionId(), tileManager.worldId(), tileManager.dimensionId(), mapView, mapLayout.map())
                .ifPresent(request -> {
                    sendMapSnapshotRequest(request);
                    requestRoadOverlays();
                });
    }

    private void requestViewportMapSnapshot() {
        if (testMode || tileManager == null || mapLayout == null) {
            requestRoadOverlays();
            return;
        }
        mapRequestScheduler.viewportRequest(System.currentTimeMillis(), state.sessionId(), tileManager.worldId(), tileManager.dimensionId(), mapView, mapLayout.map())
                .ifPresent(request -> {
                    sendMapSnapshotRequest(request);
                    requestRoadOverlays();
                });
    }

    private void requestForceRenderMapSnapshot(BlockPos start, BlockPos end) {
        if (testMode || tileManager == null) {
            return;
        }
        mapRequestScheduler.forceRenderRequest(System.currentTimeMillis(), state.sessionId(), tileManager.worldId(), tileManager.dimensionId(), start, end)
                .ifPresent(this::sendMapSnapshotRequest);
    }

    private void sendMapSnapshotRequest(RoadPlannerMinimapRequestScheduler.Request request) {
        ModNetwork.CHANNEL.sendToServer(request.toPacket());
        mapStatusLine = switch (request.purpose()) {
            case INITIAL_VIEWPORT -> "地图: 初始加载中";
            case VIEWPORT -> "地图: 视口更新中";
            case FORCE_RENDER -> "地图: 强制渲染中";
        };
    }

    private void requestRoadOverlays() {
        RoadPlannerRoadOverlayRequestPacket request = new RoadPlannerRoadOverlayRequestPacket(
                state.sessionId(),
                tileManager == null ? "" : tileManager.worldId(),
                tileManager == null ? "" : tileManager.dimensionId(),
                currentMapRegionCenter(),
                currentMapRegionSize(),
                roadOverlayRequestScope()
        );
        lastRoadOverlayRequest = request;
        if (testMode || minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(request);
    }

    private RoadPlannerMergeScope roadOverlayRequestScope() {
        return mergeScope.enabled() ? mergeScope : RoadPlannerMergeScope.OWN_NATION;
    }

    private BlockPos currentMapRegionCenter() {
        return new BlockPos((int) Math.round(mapView.centerX()), 0, (int) Math.round(mapView.centerZ()));
    }

    private int currentMapRegionSize() {
        if (mapLayout == null) {
            return RoadPlannerMinimapRequestScheduler.MIN_REGION_SIZE;
        }
        RoadPlannerMapLayout.Rect map = mapLayout.map();
        int minWorldX = mapView.screenToWorldX(map.x(), map);
        int maxWorldX = mapView.screenToWorldX(map.right(), map);
        int minWorldZ = mapView.screenToWorldZ(map.y(), map);
        int maxWorldZ = mapView.screenToWorldZ(map.bottom(), map);
        int span = Math.max(Math.abs(maxWorldX - minWorldX), Math.abs(maxWorldZ - minWorldZ));
        return RoadPlannerRoadOverlayRequestPacket.normalizeRegionSize(nextPowerOfTwo(
                Math.max(RoadPlannerMinimapRequestScheduler.MIN_REGION_SIZE, span)));
    }

    private int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    @Override
    public void tick() {
        if (tileManager == null) {
            return;
        }
        renderPlayerAreaChunks(2);
        processCorridorDirect(2);
    }

    private void renderPlayerAreaChunks(int maxChunks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        int playerChunkX = mc.player.blockPosition().getX() >> 4;
        int playerChunkZ = mc.player.blockPosition().getZ() >> 4;
        int rendered = 0;
        for (int radius = 0; rendered < maxChunks && radius <= 16; radius++) {
            for (int dx = -radius; dx <= radius && rendered < maxChunks; dx++) {
                for (int dz = -radius; dz <= radius && rendered < maxChunks; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    ChunkPos cp = new ChunkPos(playerChunkX + dx, playerChunkZ + dz);
                    if (tileRenderScheduler.alreadySubmitted(cp)) {
                        continue;
                    }
                    if (renderChunkDirect(cp)) {
                        rendered++;
                    }
                }
            }
        }
    }

    private void processCorridorDirect(int maxChunks) {
        forceRenderQueue.processChunks(maxChunks,
                chunk -> tileRenderScheduler.alreadySubmitted(chunk),
                this::renderChunkDirect);
    }

    private boolean renderChunkDirect(ChunkPos cp) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        try {
            if (!mc.level.hasChunk(cp.x, cp.z)) {
                return false;
            }
            tileRenderScheduler.markSubmitted(cp);
            RoadPlannerChunkImage image = new RoadPlannerChunkImage(mc.level, cp);
            int tileX = Math.floorDiv(cp.x, 16);
            int tileZ = Math.floorDiv(cp.z, 16);
            RoadPlannerTile tile = tileManager.getOrCreateTile(tileX, tileZ, MapLod.LOD_1);
            int localX = Math.floorMod(cp.x, 16);
            int localZ = Math.floorMod(cp.z, 16);
            tile.updateChunkDirect(image, localX, localZ);
            image.close();
            tileManager.saveTile(tile);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        canvas.render(graphics, font);
        claimOverlayRenderer.render(graphics, mapView, mapLayout.map());
        renderRoadOverlay(graphics, mouseX, mouseY);
        renderToolbar(graphics, mouseX, mouseY);
        renderInspector(graphics);
        renderStatusBar(graphics);
        if (contextMenu != null && contextMenu.isOpen()) {
            contextMenu.render(graphics, font, mouseX, mouseY, width, height);
        }
        if (canvas.contains(mouseX, mouseY)) {
            claimOverlayRenderer.renderTooltip(graphics, font, mapView, mapLayout.map(), mouseX, mouseY);
            RoadPlannerRoadOverlayHitTester.Result roadHit = hoveredRoadOverlay(mouseX, mouseY);
            if (roadHit.hit()) {
                graphics.renderComponentTooltip(font, roadOverlayTooltipLines(roadHit.entry()), mouseX, mouseY);
            }
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

    private void renderRoadOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        RoadPlannerMapLayout.Rect map = mapLayout.map();
        renderSyncedRoadOverlays(graphics, map, hoveredRoadOverlay(mouseX, mouseY));
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

    private void renderSyncedRoadOverlays(GuiGraphics graphics,
                                          RoadPlannerMapLayout.Rect map,
                                          RoadPlannerRoadOverlayHitTester.Result hoveredRoad) {
        RoadPlannerMergeSelection selectedMerge = activeMergeSelectionForRendering();
        List<RoadPlannerSharedRoadSpan> activeSharedSpans = activeSharedSpansForRendering(selectedMerge);
        Set<String> selectedReuseRoadIds = selectedReuseRoadIds(activeSharedSpans);
        if (autoMergeState.found()) {
            selectedReuseRoadIds = new HashSet<>(autoMergeState.roadIds());
        }
        String hoveredRoadId = hoveredRoad != null && hoveredRoad.hit() ? hoveredRoad.entry().roadId() : "";
        int lodStep = RoadPlannerOverlayLod.stepForPixelsPerBlock(mapView.scale());
        for (RoadPlannerRoadOverlayRenderModel.RoadLayer layer : RoadPlannerRoadOverlayRenderModel.layers(
                roadOverlays, selectedMerge, selectedReuseRoadIds, hoveredRoadId, lodStep)) {
            int color = switch (layer.kind()) {
                case HOVERED -> HOVERED_ROAD_OVERLAY_COLOR;
                case SELECTED_REUSE -> SELECTED_MERGE_ANCHOR_COLOR;
                case BASE -> roadOverlayColor(layer.relationship());
            };
            drawRoadOverlayPath(graphics, map, layer.path(), color, layer.thickness(), layer.dashed());
        }
        for (RoadPlannerRoadOverlaySyncPacket.Entry overlay : roadOverlays) {
            if (isSelectedMergeAnchor(overlay, selectedMerge)) {
                drawSelectedMergeAnchor(graphics, map, selectedMerge.anchorPos());
            }
            drawSharedSpansForOverlay(graphics, map, overlay, activeSharedSpans);
            for (BlockPos node : RoadPlannerRoadOverlayRenderModel.keyNodes(overlay, selectedMerge, selectedReuseRoadIds, null, true, lodStep)) {
                drawRoadOverlayNode(graphics, map, node, roadOverlayColor(overlay.relationship()));
            }
        }
    }

    private RoadPlannerMergeSelection activeMergeSelectionForRendering() {
        return autoMergeState.found() ? autoMergeState.selection() : selectedMergeSelection();
    }

    private List<RoadPlannerSharedRoadSpan> activeSharedSpansForRendering(RoadPlannerMergeSelection selectedMerge) {
        if (autoMergeState.found()) {
            return autoMergeState.sharedSpans();
        }
        return sharedSpansForSubmission(selectedMerge);
    }

    private Set<String> selectedReuseRoadIds(List<RoadPlannerSharedRoadSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return Set.of();
        }
        return spans.stream()
                .filter(span -> span != null && span.present())
                .map(RoadPlannerSharedRoadSpan::roadId)
                .collect(Collectors.toSet());
    }

    private int sharedSpanNodeCount(RoadPlannerRoadOverlaySyncPacket.Entry overlay, List<RoadPlannerSharedRoadSpan> spans) {
        if (overlay == null || spans == null || spans.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (RoadPlannerSharedRoadSpan span : spans) {
            if (span != null && span.present() && overlay.roadId().equals(span.roadId())) {
                int from = displayIndexForPathIndex(overlay, span.fromPathIndex(), span.fromPos());
                int to = displayIndexForPathIndex(overlay, span.toPathIndex(), span.toPos());
                count += from >= 0 && to >= 0
                        ? Math.abs(to - from) + 1
                        : Math.abs(span.toPathIndex() - span.fromPathIndex()) + 1;
            }
        }
        return count;
    }

    private void drawSharedSpansForOverlay(GuiGraphics graphics,
                                           RoadPlannerMapLayout.Rect map,
                                           RoadPlannerRoadOverlaySyncPacket.Entry overlay,
                                           List<RoadPlannerSharedRoadSpan> spans) {
        if (overlay == null || overlay.displayPath().size() < 2 || spans == null || spans.isEmpty()) {
            return;
        }
        for (RoadPlannerSharedRoadSpan span : spans) {
            if (span == null || !span.present() || !overlay.roadId().equals(span.roadId())) {
                continue;
            }
            drawRoadOverlaySpan(graphics, map, overlay, span.fromPathIndex(), span.toPathIndex(), SELECTED_MERGE_ANCHOR_COLOR, 6);
        }
    }

    private void drawRoadOverlaySpan(GuiGraphics graphics,
                                     RoadPlannerMapLayout.Rect map,
                                     RoadPlannerRoadOverlaySyncPacket.Entry overlay,
                                     int fromIndex,
                                     int toIndex,
                                     int color,
                                     int thickness) {
        List<BlockPos> path = overlay.displayPath();
        if (path.size() < 2) {
            return;
        }
        int start = displayIndexForPathIndex(overlay, fromIndex, null);
        int end = displayIndexForPathIndex(overlay, toIndex, null);
        if (start < 0 || end < 0) {
            return;
        }
        start = Math.max(0, Math.min(path.size() - 1, start));
        end = Math.max(0, Math.min(path.size() - 1, end));
        if (start == end) {
            drawSelectedMergeAnchor(graphics, map, path.get(start));
            return;
        }
        int step = end > start ? 1 : -1;
        for (int index = start; index != end; index += step) {
            BlockPos previous = path.get(index);
            BlockPos current = path.get(index + step);
            drawRoadOverlayPath(graphics, map, List.of(previous, current), color, thickness, false);
        }
    }

    private void drawRoadOverlayPath(GuiGraphics graphics,
                                     RoadPlannerMapLayout.Rect map,
                                     RoadPlannerRoadOverlaySyncPacket.Entry overlay,
                                     int color,
                                     int thickness) {
        if (overlay == null || overlay.displayPath().size() < 2) {
            return;
        }
        drawRoadOverlayPath(graphics, map, overlay.displayPath(), color, thickness, false);
    }

    private void drawRoadOverlayPath(GuiGraphics graphics,
                                     RoadPlannerMapLayout.Rect map,
                                     List<BlockPos> path,
                                     int color,
                                     int thickness,
                                     boolean dashed) {
        if (path == null || path.size() < 2) {
            return;
        }
        for (int index = 1; index < path.size(); index++) {
            BlockPos previous = path.get(index - 1);
            BlockPos current = path.get(index);
            int x1 = mapView.worldToScreenX(previous.getX(), map);
            int y1 = mapView.worldToScreenZ(previous.getZ(), map);
            int x2 = mapView.worldToScreenX(current.getX(), map);
            int y2 = mapView.worldToScreenZ(current.getZ(), map);
            if (!lineIntersectsRect(x1, y1, x2, y2, map)) {
                continue;
            }
            if (dashed) {
                RoadPlannerOverlayLineRenderer.drawThickDashedLine(graphics, x1, y1, x2, y2, color, thickness, 8, 6,
                        map.x(), map.y(), map.right(), map.bottom());
            } else {
                RoadPlannerOverlayLineRenderer.drawThickLine(graphics, x1, y1, x2, y2, color, thickness,
                        map.x(), map.y(), map.right(), map.bottom());
            }
        }
    }

    private void drawRoadOverlayNodes(GuiGraphics graphics,
                                      RoadPlannerMapLayout.Rect map,
                                      RoadPlannerRoadOverlaySyncPacket.Entry overlay,
                                      int color) {
        if (overlay == null || overlay.displayPath().isEmpty()) {
            return;
        }
        for (BlockPos node : overlay.displayPath()) {
            int x = mapView.worldToScreenX(node.getX(), map);
            int y = mapView.worldToScreenZ(node.getZ(), map);
            if (!map.contains(x, y)) {
                continue;
            }
            graphics.fill(x - 2, y - 2, x + 3, y + 3, color);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, 0xEE101418);
        }
    }

    private RoadPlannerRoadOverlayHitTester.Result hoveredRoadOverlay(int mouseX, int mouseY) {
        RoadPlannerMapLayout.Rect map = mapLayout.map();
        if (!map.contains(mouseX, mouseY)) {
            return RoadPlannerRoadOverlayHitTester.Result.miss();
        }
        return RoadPlannerRoadOverlayHitTester.find(
                mouseX,
                mouseY,
                roadOverlays,
                pos -> mapView.worldToScreenX(pos.getX(), map),
                pos -> mapView.worldToScreenZ(pos.getZ(), map),
                selectedMergeSelection(),
                ROAD_OVERLAY_HOVER_THRESHOLD);
    }

    private RoadPlannerRoadOverlayHitTester.NodeResult hoveredRoadOverlayNode(int mouseX, int mouseY) {
        RoadPlannerMapLayout.Rect map = mapLayout.map();
        if (!map.contains(mouseX, mouseY)) {
            return RoadPlannerRoadOverlayHitTester.NodeResult.miss();
        }
        return RoadPlannerRoadOverlayHitTester.findNode(
                mouseX,
                mouseY,
                roadOverlays,
                pos -> mapView.worldToScreenX(pos.getX(), map),
                pos -> mapView.worldToScreenZ(pos.getZ(), map),
                selectedMergeSelection(),
                ROAD_OVERLAY_HOVER_THRESHOLD + 2.0D);
    }

    private List<Component> roadOverlayTooltipLines(RoadPlannerRoadOverlaySyncPacket.Entry entry) {
        if (entry == null) {
            return List.of();
        }
        return List.of(
                Component.literal(entry.displayName()),
                Component.literal("Length: " + entry.lengthBlocks() + " blocks"),
                Component.literal("Creator: " + (entry.creatorName().isBlank() ? "Unknown" : entry.creatorName())),
                Component.literal("Created: " + roadOverlayCreatedText(entry)));
    }

    private String roadOverlayCreatedText(RoadPlannerRoadOverlaySyncPacket.Entry entry) {
        if (entry == null || entry.createdAt() <= 0L) {
            return "Unknown";
        }
        if (entry.legacyMetadata()) {
            return "Old road data";
        }
        return ROAD_OVERLAY_TIME_FORMAT.format(Instant.ofEpochMilli(entry.createdAt()));
    }

    private void drawSelectedMergeAnchor(GuiGraphics graphics, RoadPlannerMapLayout.Rect map, BlockPos anchor) {
        int x = mapView.worldToScreenX(anchor.getX(), map);
        int y = mapView.worldToScreenZ(anchor.getZ(), map);
        if (!map.contains(x, y)) {
            return;
        }
        graphics.fill(x - 7, y - 1, x + 8, y + 2, SELECTED_MERGE_ANCHOR_COLOR);
        graphics.fill(x - 1, y - 7, x + 2, y + 8, SELECTED_MERGE_ANCHOR_COLOR);
    }

    private int roadOverlayColor(RoadPlannerMergeRelationship relationship) {
        return relationship == RoadPlannerMergeRelationship.OWN ? OWN_ROAD_OVERLAY_COLOR : SHARED_ROAD_OVERLAY_COLOR;
    }

    private boolean isSelectedMergeAnchor(RoadPlannerRoadOverlaySyncPacket.Entry overlay, RoadPlannerMergeSelection selectedMerge) {
        if (overlay == null || selectedMerge == null || !selectedMerge.present()) {
            return false;
        }
        if (!overlay.roadId().equals(selectedMerge.roadId())) {
            return false;
        }
        return overlay.displayPath().stream().anyMatch(pos -> pos.equals(selectedMerge.anchorPos()));
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
        MapProgressView progress = currentMapProgressView();
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
        MapProgressView progress = currentMapProgressView();
        String routeText = "节点 " + linePlan.nodeCount() + " | " + displayTownName(startTownName, "未选择") + " -> "
                + displayTownName(destinationTownName, "未选择") + " | " + progress.percent() + "%";
        graphics.drawString(font, statusLine + " | " + routeText + " | " + progress.summary(), status.x() + 10, status.y() + 9, RoadPlannerMapTheme.TEXT, false);
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

    private MapProgressView currentMapProgressView() {
        RoadPlannerMapPreloadProgressPacket packet = currentAcceptedMapPreloadProgress();
        if (packet != null) {
            int percent = packet.totalTiles() <= 0 ? 0 : Math.min(100, (int) Math.round(packet.completedTiles() * 100.0D / packet.totalTiles()));
            String message = packet.message() == null || packet.message().isBlank() ? packet.state().name() : packet.message();
            return new MapProgressView(percent, "预加载 " + percent + "% " + message);
        }
        RoadPlannerForceRenderProgress progress = forceRenderQueue.progress();
        if (progress.totalChunks() > 0) {
            return new MapProgressView(progress.percent(), "强制渲染 " + progress.label() + " " + progress.completedChunks() + "/" + progress.totalChunks());
        }
        return new MapProgressView(0, mapStatusLine);
    }

    private RoadPlannerMapPreloadProgressPacket currentAcceptedMapPreloadProgress() {
        RoadPlannerMapPreloadProgressPacket packet = lastMapPreloadProgress;
        if (packet == null) {
            return null;
        }
        if (!routePreloadScheduler.acceptsResponse(packet.sessionId(), packet.requestId(), packet.purpose(), packet.worldId(), packet.dimensionId())) {
            return null;
        }
        return packet;
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
                activateTool(toolbarItem.toolType());
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
                activateTool(TOOLS.get(index));
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
                return openContextMenuAtWorld(world.getX(), world.getZ(), (int) mouseX, (int) mouseY);
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
            if (button == 0 && state.activeTool() == RoadToolType.MERGE) {
                return selectMergeAnchorAt(mouseX, mouseY);
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
                    clearAndRequestMergeCandidates();
                    statusLine = "已添加贝塞尔曲线节点: " + linePlan.nodeCount();
                    return true;
                }
                addNodeWithWaterSplit(target, segmentType);
                normalizeCurrentBridgeSegments();
                selectedNode = null;
                saveDraft();
                clearAndRequestMergeCandidates();
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

    private void activateTool(RoadToolType toolType) {
        state = state.withActiveTool(toolType == null ? RoadToolType.SELECT : toolType);
        if (state.activeTool() == RoadToolType.MERGE) {
            requestMergeToolRoutes();
        }
    }

    private void drawRoadOverlayNode(GuiGraphics graphics, RoadPlannerMapLayout.Rect map, BlockPos node, int color) {
        if (node == null) {
            return;
        }
        int x = mapView.worldToScreenX(node.getX(), map);
        int y = mapView.worldToScreenZ(node.getZ(), map);
        if (!map.contains(x, y)) {
            return;
        }
        graphics.fill(x - 2, y - 2, x + 3, y + 3, color);
        graphics.fill(x - 1, y - 1, x + 2, y + 2, 0xEE101418);
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
        boolean wasPanning = panning;
        panning = false;
        if (button == 0 && state.activeTool() == RoadToolType.FORCE_RENDER && forceRenderSelectionStart != null) {
            if (canvas.contains(mouseX, mouseY)) {
                forceRenderSelectionEnd = canvas.mouseToWorld(mouseX, mouseY);
            }
            BlockPos renderEnd = forceRenderSelectionEnd == null ? forceRenderSelectionStart : forceRenderSelectionEnd;
            forceRenderQueue.enqueueSelection(forceRenderSelectionStart, renderEnd, "\u9009\u533a\u6e32\u67d3");
            requestForceRenderPreload(forceRenderSelectionStart, renderEnd);
            statusLine = "\u5df2\u53d1\u9001\u5f3a\u5236\u6e32\u67d3\u9009\u533a";
            forceRenderSelectionStart = null;
            forceRenderSelectionEnd = null;
            return true;
        }
        if (wasPanning) {
            requestViewportMapSnapshot();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (canvas.contains(mouseX, mouseY)) {
            mapView.zoomAround(mouseX, mouseY, delta > 0 ? 1.2D : 0.833333D, mapLayout.map());
            requestViewportMapSnapshot();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private boolean openContextMenuForGraph(double worldX, double worldZ, int mouseX, int mouseY) {
        RoadPlannerMapInteractionResult result = canvas.rightClickGraph(state, graph, worldX, worldZ, mouseX, mouseY);
        state = result.state();
        if (result.contextMenu().isEmpty()) {
            contextMenu = null;
            return false;
        }
        contextMenu = RoadPlannerVanillaContextMenu.forRoadEdge(result.contextMenu().orElseThrow().roadEdgeId());
        contextMenu.open(mouseX, mouseY);
        return true;
    }

    private boolean openContextMenuAtWorld(double worldX, double worldZ, int mouseX, int mouseY) {
        if (openContextMenuForPlannedRoute(worldX, worldZ, mouseX, mouseY)) {
            return true;
        }
        if (openContextMenuForBuiltRoadNode(mouseX, mouseY)) {
            return true;
        }
        return openContextMenuForGraph(worldX, worldZ, mouseX, mouseY);
    }

    private boolean openContextMenuForBuiltRoadNode(int mouseX, int mouseY) {
        RoadPlannerRoadOverlayHitTester.NodeResult hit = hoveredRoadOverlayNode(mouseX, mouseY);
        if (hit == null || !hit.hit()) {
            clearBuiltRoadNodeSelection();
            return false;
        }
        selectedBuiltRoadOverlay = hit.entry();
        selectedBuiltRoadNodeIndex = hit.pathIndex();
        state = state.withSelectedRoadEdge(null);
        contextMenu = RoadPlannerVanillaContextMenu.forBuiltRoadNode();
        contextMenu.open(mouseX, mouseY);
        return true;
    }

    private boolean openContextMenuForPlannedRoute(double worldX, double worldZ, int mouseX, int mouseY) {
        RoadPlannerRouteHitTester.Hit hit = routeHitTester.hit(linePlan.nodes(), worldX, worldZ).orElse(null);
        if (hit == null || hit.segmentIndex() < 0 || hit.segmentIndex() >= linePlan.segmentCount()) {
            return false;
        }
        selectedNode = new RoadPlannerNodeSelection(hit.segmentIndex());
        state = state.withSelectedRoadEdge(null);
        contextMenu = RoadPlannerVanillaContextMenu.forPlannedRoute();
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
        if (action == RoadPlannerContextMenuAction.CONTINUE_FROM_BUILT_ROAD_NODE) {
            continuePlanningFromBuiltRoadNode();
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
        if (state.selectedRoadEdgeId() != null) {
            graph.updateEdgeType(state.selectedRoadEdgeId(), type).ifPresentOrElse(
                    edge -> statusLine = "\u5df2\u5c06\u8be5\u6bb5\u5c5e\u6027\u8bbe\u4e3a: " + editableTypeLabel(type),
                    () -> statusLine = "\u672a\u627e\u5230\u9009\u4e2d\u9053\u8def\u6bb5"
            );
            return;
        }
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
        clearAndRequestMergeCandidates();
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
            clearAndRequestMergeCandidates();
            statusLine = "已撤销";
            return;
        }
        if (RoadPlannerTopToolbar.ACTION_CLEAR.equals(label)) {
            resetLineToStartNode();
            saveDraft();
            clearMergeCandidateState();
            statusLine = "已清除当前路线";
            return;
        }
        if (RoadPlannerTopToolbar.ACTION_AUTO_COMPLETE.equals(label)) {
            if (testMode) {
                RoadPlannerAutoCompleteResult result = autoCompleteService.complete(startTownPos, destinationTownPos, linePlan.nodes(), 24);
                applyAutoCompleteResult(state.sessionId(), result.success(), result.nodes(), result.segmentTypes(), result.message());
                return;
            }
            if (minecraft != null && minecraft.getConnection() != null) {
                ModNetwork.CHANNEL.sendToServer(new RoadPlannerAutoCompleteRequestPacket(
                        state.sessionId(), startTownPos, destinationTownPos, linePlan.nodes(), 24
                ));
                statusLine = "正在请求自动补全...";
            }
            return;
        }
        if (RoadPlannerTopToolbar.ACTION_MERGE_SCOPE.equals(label)) {
            cycleMergeScope();
            return;
        }
        if (RoadPlannerTopToolbar.ACTION_NEXT_MERGE.equals(label)) {
            confirmSelectedMergeAnchor();
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
        PreviewSubmission submission = previewSubmission();
        if (submission.buildNodes().size() < 2) {
            statusLine = "\u5df2\u5efa\u9053\u8def\u590d\u7528\u6bb5\u4e0d\u9700\u8981\u91cd\u590d\u5efa\u9020";
            return;
        }
        RoadPlannerRouteExpander.Result expanded = expandRoute(submission.buildNodes(), submission.buildSegments());
        if (!expanded.success()) {
            statusLine = "\u6865\u6881\u7f3a\u5c11\u9646\u5730\u951a\u70b9\u6216\u8def\u7ebf\u8282\u70b9\u4e0d\u8db3\uff0c\u8bf7\u68c0\u67e5\u8def\u7ebf";
            return;
        }
        saveDraft();
        if (RoadPlannerGhostPreviewBridge.submitPreview(
                startTownName,
                destinationTownName,
                expanded.nodes(),
                expanded.segmentTypes(),
                buildSettings,
                submission.mergeSelection(),
                submission.logicalNodes(),
                submission.sharedSpans())) {
            if (minecraft != null) {
                onClose();
            }
        }
    }


    private List<BlockPos> nodesForPreviewSubmission() {
        return previewSubmission().buildNodes();
    }

    private List<RoadPlannerSegmentType> segmentTypesForPreviewSubmission(List<BlockPos> previewNodes) {
        return previewSubmission().buildSegments();
    }

    private PreviewSubmission previewSubmission() {
        List<BlockPos> nodes = linePlan.nodes();
        List<RoadPlannerSegmentType> segments = linePlan.segments();
        RoadPlannerMergeSelection selection = selectedMergeSelection();
        DetectedStartReuse startReuse = startReuseForSubmission(nodes, segments);
        int buildStartIndex = startReuse.lineNodeIndex();
        if (buildStartIndex < 0) {
            buildStartIndex = 0;
        }
        int buildEndIndex = nodes.size() - 1;
        if (selection.present()) {
            int anchorIndex = indexOfNode(nodes, selection.anchorPos());
            if (anchorIndex >= buildStartIndex) {
                buildEndIndex = anchorIndex;
            }
        }
        List<BlockPos> buildNodes = buildEndIndex >= buildStartIndex && !nodes.isEmpty()
                ? List.copyOf(nodes.subList(buildStartIndex, buildEndIndex + 1))
                : List.of();
        int segmentStart = Math.max(0, buildStartIndex);
        int segmentEndExclusive = Math.min(segments.size(), buildEndIndex);
        List<RoadPlannerSegmentType> buildSegments = segmentEndExclusive > segmentStart
                ? List.copyOf(segments.subList(segmentStart, segmentEndExclusive))
                : List.of();
        List<RoadPlannerSharedRoadSpan> sharedSpans = sharedSpansForSubmission(selection, startReuse.span());
        return new PreviewSubmission(buildNodes, buildSegments, nodes, selection, sharedSpans);
    }

    private List<RoadPlannerSharedRoadSpan> sharedSpansForSubmission(RoadPlannerMergeSelection selection) {
        DetectedStartReuse startReuse = startReuseForSubmission(linePlan.nodes(), linePlan.segments());
        return sharedSpansForSubmission(selection, startReuse.span());
    }

    private List<RoadPlannerSharedRoadSpan> sharedSpansForSubmission(RoadPlannerMergeSelection selection,
                                                                     RoadPlannerSharedRoadSpan startSpan) {
        List<RoadPlannerSharedRoadSpan> spans = new ArrayList<>();
        if (startSpan != null && startSpan.present()) {
            spans.add(startSpan);
        }
        if (autoMergeState.found() && autoMergeState.selection().equals(selection)) {
            spans.addAll(autoMergeState.sharedSpans());
        } else {
            RoadPlannerSharedRoadSpan endSpan = endMergeSpan(selection);
            if (endSpan.present()) {
                spans.add(endSpan);
            }
        }
        return List.copyOf(spans);
    }

    private DetectedStartReuse startReuseForSubmission(List<BlockPos> nodes, List<RoadPlannerSegmentType> segments) {
        if (startReuseSpan.present()) {
            int nodeIndex = indexOfNode(nodes, startReuseSpan.toPos());
            if (nodeIndex > 0) {
                return new DetectedStartReuse(startReuseSpan, nodeIndex);
            }
        }
        return detectStartReusePrefix(nodes, segments);
    }

    private DetectedStartReuse detectStartReusePrefix(List<BlockPos> nodes, List<RoadPlannerSegmentType> segments) {
        if (nodes == null || nodes.size() < 2 || roadOverlays.isEmpty() || !mergeScope.enabled()) {
            return DetectedStartReuse.none();
        }
        double threshold = Math.max(6.0D, state.selectedWidth() + 2.0D);
        double thresholdSqr = threshold * threshold;
        DetectedStartReuse best = DetectedStartReuse.none();
        for (RoadPlannerRoadOverlaySyncPacket.Entry overlay : roadOverlays) {
            if (overlay == null || overlay.displayPath().size() < 2 || !mergeScopeAllowsOverlay(overlay)) {
                continue;
            }
            DetectedStartReuse forward = detectStartReusePrefixInDirection(overlay, nodes, segments, 1, thresholdSqr);
            if (forward.lineNodeIndex() > best.lineNodeIndex()) {
                best = forward;
            }
            DetectedStartReuse backward = detectStartReusePrefixInDirection(overlay, nodes, segments, -1, thresholdSqr);
            if (backward.lineNodeIndex() > best.lineNodeIndex()) {
                best = backward;
            }
        }
        return best.lineNodeIndex() > 0 ? best : DetectedStartReuse.none();
    }

    private boolean mergeScopeAllowsOverlay(RoadPlannerRoadOverlaySyncPacket.Entry overlay) {
        if (overlay == null) {
            return false;
        }
        if (overlay.relationship() == RoadPlannerMergeRelationship.OWN) {
            return true;
        }
        return mergeScope.allowsExternalRoads();
    }

    private DetectedStartReuse detectStartReusePrefixInDirection(RoadPlannerRoadOverlaySyncPacket.Entry overlay,
                                                                 List<BlockPos> nodes,
                                                                 List<RoadPlannerSegmentType> segments,
                                                                 int direction,
                                                                 double thresholdSqr) {
        List<BlockPos> path = overlay.displayPath();
        RoadPlannerSharedRoadSpan bestSpan = RoadPlannerSharedRoadSpan.none();
        int bestLineIndex = -1;
        for (int pathStart = 0; pathStart < path.size(); pathStart++) {
            if (horizontalDistanceSqr(path.get(pathStart), nodes.get(0)) > thresholdSqr) {
                continue;
            }
            int currentPathIndex = pathStart;
            int matchedLineIndex = 0;
            for (int lineIndex = 1; lineIndex < nodes.size(); lineIndex++) {
                if (lineIndex - 1 < segments.size() && isBridgeLikeSegment(segments.get(lineIndex - 1))) {
                    break;
                }
                int nextPathIndex = nearestPathIndexInDirection(path, nodes.get(lineIndex), currentPathIndex, direction, thresholdSqr);
                if (nextPathIndex < 0) {
                    break;
                }
                currentPathIndex = nextPathIndex;
                matchedLineIndex = lineIndex;
            }
            if (matchedLineIndex > bestLineIndex) {
                bestLineIndex = matchedLineIndex;
                int fromPathIndex = pathIndexForDisplayIndex(overlay, pathStart);
                int toPathIndex = pathIndexForDisplayIndex(overlay, currentPathIndex);
                bestSpan = new RoadPlannerSharedRoadSpan(
                        overlay.roadId(),
                        fromPathIndex,
                        toPathIndex,
                        path.get(pathStart),
                        path.get(currentPathIndex),
                        mergeScope,
                        RoadPlannerSharedRoadSpan.Role.START_REUSE);
            }
        }
        return bestLineIndex > 0 ? new DetectedStartReuse(bestSpan, bestLineIndex) : DetectedStartReuse.none();
    }

    private int nearestPathIndexInDirection(List<BlockPos> path,
                                            BlockPos target,
                                            int fromIndex,
                                            int direction,
                                            double thresholdSqr) {
        int start = fromIndex + direction;
        int endExclusive = direction > 0 ? path.size() : -1;
        int bestIndex = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int index = start; index != endExclusive; index += direction) {
            double distance = horizontalDistanceSqr(path.get(index), target);
            if (distance <= thresholdSqr && distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private double horizontalDistanceSqr(BlockPos left, BlockPos right) {
        if (left == null || right == null) {
            return Double.MAX_VALUE;
        }
        long dx = (long) left.getX() - right.getX();
        long dz = (long) left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private RoadPlannerSharedRoadSpan endMergeSpan(RoadPlannerMergeSelection selection) {
        if (selection == null || !selection.present()) {
            return RoadPlannerSharedRoadSpan.none();
        }
        RoadPlannerRoadOverlaySyncPacket.Entry overlay = selectedMergeOverlay;
        if (overlay == null || !overlay.roadId().equals(selection.roadId())) {
            overlay = roadOverlays.stream()
                    .filter(entry -> entry != null && entry.roadId().equals(selection.roadId()))
                    .findFirst()
                    .orElse(null);
        }
        if (overlay == null || overlay.displayPath().isEmpty()) {
            return RoadPlannerSharedRoadSpan.none();
        }
        List<BlockPos> path = overlay.displayPath();
        int anchorIndex = displayIndexForPathIndex(overlay, selection.pathIndex(), selection.anchorPos());
        if (anchorIndex < 0) {
            anchorIndex = indexOfNode(path, selection.anchorPos());
        }
        int targetIndex = nearestPathNodeIndex(path, destinationTownPos);
        if (anchorIndex < 0 || targetIndex < 0 || anchorIndex == targetIndex) {
            return RoadPlannerSharedRoadSpan.none();
        }
        int anchorPathIndex = pathIndexForDisplayIndex(overlay, anchorIndex);
        int targetPathIndex = pathIndexForDisplayIndex(overlay, targetIndex);
        return new RoadPlannerSharedRoadSpan(
                selection.roadId(),
                anchorPathIndex,
                targetPathIndex,
                path.get(anchorIndex),
                path.get(targetIndex),
                selection.scope(),
                RoadPlannerSharedRoadSpan.Role.END_MERGE);
    }

    private RoadPlannerRouteExpander.Result expandRoute(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes) {
        return RoadPlannerRouteExpander.expand(
                nodes,
                segmentTypes,
                RoadPlannerScreen::isClientLand,
                testMode ? (x, z) -> 64 : RoadPlannerHeightSampler.clientLoadedTerrain(),
                testMode ? (x, z) -> 1 : RoadPlannerScreen::clientWaterDepth
        );
    }

    private void addNodeWithWaterSplit(BlockPos target, RoadPlannerSegmentType segmentType) {
        if (linePlan.nodeCount() == 0) {
            clearStartReuseSpan();
            linePlan.addClickNode(target, segmentType);
            return;
        }
        BlockPos from = linePlan.nodes().get(linePlan.nodeCount() - 1);
        RoadPlannerWaterCrossingSplitter.SplitResult split = RoadPlannerWaterCrossingSplitter.split(
                from,
                target,
                RoadPlannerScreen::isClientLand,
                testMode ? (x, z) -> 64 : RoadPlannerHeightSampler.clientLoadedTerrain(),
                testMode ? (x, z) -> 1 : RoadPlannerScreen::clientWaterDepth
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
        clearStartReuseSpan();
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
        clearAndRequestMergeCandidates();
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
            if (linePlan.nodeCount() == 0) {
                startTownPos = target.immutable();
                linePlan.setStartNode(target);
                statusLine = "\u5df2\u8bbe\u7f6e\u9053\u8def\u8d77\u70b9";
            } else {
                destinationTownPos = target.immutable();
                statusLine = "\u5df2\u8bbe\u7f6e\u9053\u8def\u7ec8\u70b9\uff0c\u672a\u81ea\u52a8\u8fde\u63a5";
            }
            selectedNode = null;
            saveDraft();
            clearAndRequestMergeCandidates();
            return true;
        }
        if (RoadPlannerEndpointRules.isInRoleClaim(claimOverlayRenderer, target, RoadPlannerClaimOverlay.Role.START)) {
            startTownPos = target.immutable();
            linePlan.setStartNode(target);
            selectedNode = null;
            saveDraft();
            clearAndRequestMergeCandidates();
            statusLine = "\u5df2\u8bbe\u7f6e\u9053\u8def\u8d77\u70b9";
            return true;
        }
        if (RoadPlannerEndpointRules.isInRoleClaim(claimOverlayRenderer, target, RoadPlannerClaimOverlay.Role.DESTINATION)) {
            if (linePlan.nodeCount() == 0) {
                statusLine = "\u8bf7\u5148\u5728\u8d77\u70b9 Town \u8bbe\u7f6e\u8d77\u70b9";
                return true;
            }
            destinationTownPos = target.immutable();
            selectedNode = null;
            saveDraft();
            clearAndRequestMergeCandidates();
            statusLine = "\u5df2\u8bbe\u7f6e\u9053\u8def\u7ec8\u70b9\uff0c\u672a\u81ea\u52a8\u8fde\u63a5";
            return true;
        }
        statusLine = "\u7aef\u70b9\u5fc5\u987b\u653e\u5728\u8d77\u70b9\u6216\u76ee\u6807 Town \u9886\u5730\u5185";
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
                && state.activeTool() != RoadToolType.MERGE
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

    public record RoadOverlayRenderStateForTest(String roadId,
                                                RoadPlannerMergeRelationship relationship,
                                                int color,
                                                int nodeCount,
                                                boolean selectedMergeAnchor,
                                                int sharedSpanNodeCount) {
    }

    public record RoadOverlayTooltipForTest(String roadId, String displayName, List<String> lines) {
    }

    private record OverlayAnchor(int displayIndex, int pathIndex, BlockPos pos) {
    }

    private record PreviewSubmission(List<BlockPos> buildNodes,
                                     List<RoadPlannerSegmentType> buildSegments,
                                     List<BlockPos> logicalNodes,
                                     RoadPlannerMergeSelection mergeSelection,
                                     List<RoadPlannerSharedRoadSpan> sharedSpans) {
        private PreviewSubmission {
            buildNodes = buildNodes == null ? List.of() : buildNodes.stream().map(BlockPos::immutable).toList();
            buildSegments = buildSegments == null ? List.of() : List.copyOf(buildSegments);
            logicalNodes = logicalNodes == null || logicalNodes.isEmpty()
                    ? buildNodes
                    : logicalNodes.stream().map(BlockPos::immutable).toList();
            mergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
            sharedSpans = sharedSpans == null ? List.of() : List.copyOf(sharedSpans);
        }
    }

    private record DetectedStartReuse(RoadPlannerSharedRoadSpan span, int lineNodeIndex) {
        private DetectedStartReuse {
            span = span == null ? RoadPlannerSharedRoadSpan.none() : span;
            lineNodeIndex = Math.max(-1, lineNodeIndex);
        }

        static DetectedStartReuse none() {
            return new DetectedStartReuse(RoadPlannerSharedRoadSpan.none(), -1);
        }
    }

    private record MapProgressView(int percent, String summary) {
    }
}
