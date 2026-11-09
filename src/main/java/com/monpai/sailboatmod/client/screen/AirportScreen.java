package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.monpai.sailboatmod.client.AirportClientHooks;
import com.monpai.sailboatmod.client.airport.AirportMapGeometry;
import com.monpai.sailboatmod.client.airport.AirportMapOverlay;
import com.monpai.sailboatmod.client.airport.AirportPlannerModel;
import com.monpai.sailboatmod.client.roadplanner.*;
import com.monpai.sailboatmod.client.ui.airport.AirportMapElement;
import com.monpai.sailboatmod.client.ui.kit.*;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.airport.AirportActionPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Consumer;

/** Terrain-first airport planner. Draft geometry lives exclusively in the pure planner model. */
public final class AirportScreen extends ThemedScreen {
    private static final long AUTO_LAYOUT_ACK_TIMEOUT = 100;
    private enum Section { HOME, PLANNING, SETTINGS }
    private Section section = Section.HOME;
    private final AirportPlannerModel planner;
    private final Consumer<AirportActionPacket> packetSender;
    private final RoadPlannerMapView camera;
    private final EnumMap<AirportPlannerModel.Tool, Button> tools = new EnumMap<>(AirportPlannerModel.Tool.class);
    private final Map<String, String> precision = new HashMap<>();
    private final Map<String, TextField> precisionFields = new HashMap<>();
    private AirportPlannerModel.Draft propertyDraft;
    private String nameDraft;
    private long nameRevision;
    private String pendingName;
    private long pendingNameRevision = -1;
    private long routeReplyDeadline = -1;
    private BlockPos routeTargetPos;
    private String routeCruiseY = "";
    private Button publishOneWay, publishRoundTrip;
    private Selector<AirportScreenData.RouteTarget> routeSelector;
    private String planeY;
    private int selectedPoint;
    private AirportMapGeometry.Hit dragHandle;
    private boolean overview = true, precisionInvalid;
    private boolean autoLayoutMode, autoLayoutPending, autoLayoutPrefab;
    private BlockPos autoLayoutFirst, autoLayoutSecond, autoLayoutIssue;
    private List<AirportPrefab.Piece> autoLayoutPrefabPieces = List.of();
    private AirportPrefab.Materials autoLayoutPrefabMaterials;
    private String autoLayoutY;
    private AirportEditorSession.Proposal autoLayoutProposal;
    private long autoLayoutRevision = -1, autoLayoutReplyDeadline = -1;
    private Button autoLayoutTool, autoLayoutSave;
    private Label autoLayoutStatus;
    private UIElement body, inspectorPane, toolbar;
    private Label status;
    private Label mapHint;
    private ScrollerView inspector;
    private AirportMapElement map;

    public AirportScreen(AirportScreenData data) { this(data, packet -> ModNetwork.CHANNEL.sendToServer(packet)); }
    AirportScreen(AirportScreenData data, Consumer<AirportActionPacket> packetSender) {
        super(t("title"));
        this.packetSender = Objects.requireNonNull(packetSender);
        planner = new AirportPlannerModel(data);
        camera = RoadPlannerMapView.centered(data.airportPos().getX() + .5, data.airportPos().getZ() + .5, 1);
        nameDraft = data.customName(); nameRevision = data.revision(); planeY = Integer.toString(data.airportPos().getY());
        autoLayoutY = planeY;
    }
    public BlockPos airportPos() { return data().airportPos(); }
    public AirportScreenData data() { return planner.snapshot(); }
    public AirportPlannerModel planner() { return planner; }
    private static Component t(String key) { return Component.translatable("screen.sailboatmod.airport." + key); }
    @Override protected float windowWidth() { return 720; }
    static float plannerBodyHeight(int screenHeight) { return Math.max(120, screenHeight - 36); }
    static float inspectorWidth(int screenWidth) { return screenWidth < 550 ? 148 : 208; }
    @Override protected void init() { sizeBody(); super.init(); }
    private void sizeBody() {
        if (body != null) body.layout(l -> l.height(plannerBodyHeight(height)));
        if (inspectorPane != null) inspectorPane.layout(l -> l.width(inspectorWidth(width)));
    }
    @Override protected UIElement buildBody() {
        body = Ui.column(); sizeBody();
        status = Ui.paragraph(statusText(), "sm-muted");
        toolbar = Ui.wrapRow(Ui.GAP_XS);
        toolbar.layout(l -> l.widthPercent(100).flexShrink(0));
        renderToolbar();
        UIElement main = Ui.row();
        main.layout(l -> l.widthPercent(100).height(0).flexGrow(1).flexShrink(1).minHeight(64).gapAll(Ui.GAP_S));
        map = new AirportMapElement(new AirportMapElement.Host() {
            @Override public void paintMap(GuiGraphics graphics, ClaimMapViewport box, int x, int y) { paint(graphics, box, x, y); }
            @Override public void zoomMap(double x, double y, double factor, ClaimMapViewport box) { camera.zoomAround(x, y, factor, rect(box)); }
            @Override public void panMap(double dx, double dy) { camera.panByScreenDelta(dx, dy); }
            @Override public void panFinished() { }
            @Override public boolean pressMap(double x, double y, int button, ClaimMapViewport box, boolean shift) { try { return press(x, y, button, box, shift); } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException failure) { toast.show(t("result.invalid_geometry"), false, ticks()); return false; } }
            @Override public void dragMap(double x, double y, int button, ClaimMapViewport box) { drag(x, y, box); }
            @Override public void releaseMap(int button) { finishDrag(false); }
            @Override public void cancelMapInput() { finishDrag(true); }
        });
        map.layout(l -> l.width(0).flexGrow(1).heightPercent(100).minWidth(96).minHeight(64));
        inspectorPane = Ui.column("sm-pane");
        inspectorPane.layout(l -> l.width(inspectorWidth(width)).heightPercent(100).minWidth(130).flexShrink(0));
        inspector = Ui.scroller(); inspectorPane.addChild(inspector);
        main.addChildren(map, inspectorPane);
        mapHint = Ui.paragraph(t("map_controls"), "sm-muted");
        body.addChildren(status, toolbar, main, mapHint);
        renderInspector(); refreshStatus(); return body;
    }

    private void renderToolbar() {
        if (toolbar == null) return;
        toolbar.clearAllChildren();
        tools.clear();
        autoLayoutTool = null;
        toolbar.addChildren(sectionButton("tab.home", Section.HOME, this::showHome),
                sectionButton("tab.planning", Section.PLANNING, this::showPlanning),
                sectionButton("tab.settings", Section.SETTINGS, this::showSettings));
        if (data().supportsAutoLayout() && data().canManage()) {
            autoLayoutTool = Ui.button(t("auto_layout"), Ui.Tone.SECONDARY, () -> safely(this::beginAutoLayout));
            toolbar.addChild(autoLayoutTool);
        }
        if (section == Section.PLANNING && !autoLayoutMode) {
            UIElement planningTools = Ui.wrapRow(Ui.GAP_XS);
            planningTools.layout(l -> l.widthPercent(100).flexShrink(0));
            for (AirportPlannerModel.Tool tool : AirportPlannerModel.Tool.values()) {
                if (tool.isVerticalFacility() && !data().supportsRoutePublication()) continue;
                Button button = Ui.button(t("tool." + tool.name().toLowerCase(Locale.ROOT)), Ui.Tone.SECONDARY,
                        () -> safely(() -> chooseTool(tool)));
                button.layout(l -> l.paddingHorizontal(Ui.GAP_S));
                tools.put(tool, button);
                planningTools.addChild(button);
            }
            planningTools.addChildren(button("recenter", this::recenter),
                    button("preview", () -> AirportClientHooks.togglePreview(this)));
            toolbar.addChild(planningTools);
        }
    }

    private Button sectionButton(String label, Section target, Runnable action) {
        return Ui.button(t(label), section == target && !autoLayoutMode ? Ui.Tone.PRIMARY : Ui.Tone.SECONDARY,
                () -> safely(action));
    }
    private void showHome() { showSection(Section.HOME); }
    private void showPlanning() { showSection(Section.PLANNING); }
    private void showSettings() { showSection(Section.SETTINGS); }
    private void showSection(Section next) {
        if (map != null) map.cancelCapture();
        section = next;
        autoLayoutMode = false;
        overview = next != Section.PLANNING || planner.draft().isEmpty();
        renderToolbar();
        renderInspector();
        refreshStatus();
        AirportClientHooks.updatePreview(this);
    }
    public void update(AirportScreenData update, boolean repaint) {
        if (!data().dimension().equals(update.dimension()) || !airportPos().equals(update.airportPos())) return;
        long previousRevision = data().revision();
        boolean nameChanged = !data().name().equals(update.name());
        boolean targetsChanged = !data().routeTargets().equals(update.routeTargets())
                || data().hasRouteNameConflict() != update.hasRouteNameConflict();
        boolean prefabSupportChanged = data().supportsPrefab() != update.supportsPrefab()
                || data().supportsLitPrefab() != update.supportsLitPrefab();
        boolean toolbarChanged = data().canManage() != update.canManage()
                || data().supportsAutoLayout() != update.supportsAutoLayout()
                || data().supportsRoutePublication() != update.supportsRoutePublication();
        boolean wasBusy = busy();
        if (routeReplyDeadline >= 0 && !update.result().isEmpty()) routeReplyDeadline = -1;
        if (pendingNameRevision >= 0 && !update.result().isEmpty() && !"pending".equals(update.result())) {
            if ("ok".equals(update.result()) && update.customName().equals(pendingName.trim())) {
                nameRevision = update.revision(); nameDraft = update.customName();
            }
            pendingName = null; pendingNameRevision = -1;
        }
        boolean nameUnedited = nameDraft.equals(data().customName());
        planner.applySnapshot(update);
        if (prefabSupportChanged) {
            autoLayoutPrefab &= data().supportsPrefab();
            updateAutoLayoutPrefabMaterials();
        }
        if (autoLayoutPending && update.revision() >= autoLayoutRevision && "pending".equals(update.result()))
            autoLayoutReplyDeadline = ticks() + AUTO_LAYOUT_ACK_TIMEOUT;
        if (autoLayoutPending && update.revision() >= autoLayoutRevision && !update.result().isEmpty() && !"pending".equals(update.result())) {
            autoLayoutPending = false;
            autoLayoutReplyDeadline = -1;
            autoLayoutIssue = update.editorIssue().orElse(null);
            if ("ok".equals(update.result()) && update.revision() == autoLayoutRevision + 1
                    && autoLayoutProposal != null && update.facilities().containsAll(autoLayoutProposal.facilities())
                    && update.groundLayout().stands().containsAll(autoLayoutProposal.ground().stands())
                    && update.groundLayout().taxiRoutes().containsAll(autoLayoutProposal.ground().taxiRoutes())) {
                clearAutoLayoutSelection();
                autoLayoutMode = false;
                overview = true;
                toolbarChanged = true;
            }
        }
        if (nameUnedited) { nameDraft = update.customName(); nameRevision = update.revision(); }
        if (toolbarChanged) renderToolbar();
        refreshStatus();
        if (!update.result().isEmpty() && !"pending".equals(update.result())) toast.show(resultText(update.result(), update.editorIssue().orElse(null)),
                "ok".equals(update.result()) || "valid".equals(update.result()), ticks());
        // Async replies must refresh existing controls without reopening a closed screen.
        if (repaint || nameChanged || targetsChanged || prefabSupportChanged || previousRevision != data().revision() || (wasBusy && !busy()))
            renderInspector();
        AirportClientHooks.updatePreview(this);
    }
    private Component statusText() {
        String state = draftStatus(planner, precisionInvalid);
        var text = Component.literal(data().name() + " · " + data().town());
        if (section != Section.HOME || autoLayoutMode || !state.equals("draft_saved")) text.append(" · ").append(t(state));
        if (!data().canManage()) text.append(" · ").append(t("read_only"));
        if (!planner.lastResult().isEmpty() && !List.of("ok", "valid").contains(planner.lastResult()))
            text = text.copy().append(" · ").append(resultText(planner.lastResult(), autoLayoutMode ? autoLayoutIssue : null));
        if (busy()) text = text.copy().append("\n").append(Component.translatable("screen.sailboatmod.airport.validation_progress", planner.validationProgress()));
        return text;
    }
    private static Component resultText(String result, BlockPos issue) {
        var message = t("result." + result);
        return issue == null ? message : message.copy().append(" · ").append(
                Component.translatable("screen.sailboatmod.airport.issue_position", issue.getX(), issue.getY(), issue.getZ()));
    }
    static String draftStatus(AirportPlannerModel model, boolean invalidText) {
        if (invalidText) return "draft_invalid";
        if (model.awaitingReply()) return "draft_pending";
        if (model.conflict()) return "draft_conflict";
        String validation = model.validationError();
        if (validation.equals("incomplete")) return "draft_incomplete";
        if (validation.equals("invalid_geometry")) return "draft_invalid";
        return model.dirty() ? "draft_dirty" : "draft_saved";
    }
    private boolean busy() { return planner.awaitingReply() || autoLayoutPending || pendingNameRevision >= 0
            || routeReplyDeadline >= 0 || "pending".equals(planner.lastResult()); }
    private void refreshStatus() {
        if (status != null) status.setValue(statusText());
        if (mapHint != null) mapHint.setDisplay(section == Section.PLANNING && !autoLayoutMode);
        refreshToolButtons();
        precisionFields.values().forEach(field -> field.setActive(data().canManage() && !busy()));
        if (autoLayoutStatus != null) autoLayoutStatus.setValue(autoLayoutStatusText());
        if (autoLayoutSave != null) autoLayoutSave.setActive(canSaveAutoLayout());
        var target = routeTarget();
        boolean canPublish = target != null && data().canManage() && !busy() && !hasManualAirportDraft()
                && validCruiseY();
        if (publishOneWay != null) publishOneWay.setActive(canPublish);
        if (publishRoundTrip != null) publishRoundTrip.setActive(canPublish && target.canPublishReturn());
        if (routeSelector != null) routeSelector.setActive(data().canManage() && !busy());
    }
    private void refreshToolButtons() {
        tools.forEach((tool, button) -> { Ui.setClass(button, "sm-primary", !autoLayoutMode && planner.tool() == tool); button.setActive(!busy() && (tool == AirportPlannerModel.Tool.SELECT || data().canManage())); });
        if (autoLayoutTool != null) {
            Ui.setClass(autoLayoutTool, "sm-primary", autoLayoutMode);
            autoLayoutTool.setActive(data().supportsAutoLayout() && data().canManage() && !busy());
        }
    }
    private UIElement button(String key, Runnable clicked) { return Ui.button(t(key), Ui.Tone.SECONDARY, () -> safely(clicked)); }
    private void safely(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException exception) { toast.show(t("result.invalid_geometry"), false, ticks()); }
    }
    private boolean editable() {
        if (busy()) { toast.show(t("result.pending"), false, ticks()); return false; }
        if (data().canManage()) return true;
        toast.show(t("result.forbidden"), false, ticks()); return false;
    }
    private void chooseTool(AirportPlannerModel.Tool tool) {
        if (tool == AirportPlannerModel.Tool.SELECT) { autoLayoutMode = false; planner.setTool(tool); overview = false; refreshStatus(); renderInspector(); return; }
        if (!editable() || !applyPrecision()) return;
        if (!planner.startNew(tool)) { toast.show(t("unsaved_selection"), false, ticks()); return; }
        autoLayoutMode = false;
        overview = false; selectedPoint = 0; synchronizePrecision(); renderInspector(); refreshStatus();
    }
    private void recenter() {
        camera.panByScreenDelta((camera.centerX() - airportPos().getX() - .5) * camera.scale(),
                (camera.centerZ() - airportPos().getZ() - .5) * camera.scale());
    }
    private void paint(GuiGraphics graphics, ClaimMapViewport box, int mouseX, int mouseY) {
        RoadPlannerTileManager terrain = RoadPlannerTileManager.sharedDefault(); terrain.refreshWorldContext();
        new RoadPlannerMapCanvas(new RoadPlannerVanillaLayout.Rect(box.x(), box.y(), box.width(), box.height()),
                new RoadPlannerMapComponent(RoadMapRegion.centeredOn(airportPos(), 128, MapLod.LOD_1),
                        new RoadMapViewport(box.x(), box.y(), box.width(), box.height())), camera, terrain).render(graphics, font);
        AirportMapOverlay.render(graphics, font, camera, box, planner);
        if (autoLayoutMode && autoLayoutFirst != null) {
            AirportMapOverlay.renderAutoLayout(graphics, font, camera, box, autoLayoutFirst, autoLayoutSecond, autoLayoutProposal);
            if (!autoLayoutPrefabPieces.isEmpty()) AirportMapOverlay.renderPrefabDetails(graphics, camera, box, autoLayoutPrefabPieces);
            if (autoLayoutIssue != null) AirportMapOverlay.renderIssue(graphics, font, camera, box, autoLayoutIssue);
        }
        int x = camera.worldToScreenX(airportPos().getX() + .5, rect(box));
        int y = camera.worldToScreenZ(airportPos().getZ() + .5, rect(box));
        if (x >= box.x() && x < box.x() + box.width() && y >= box.y() && y < box.y() + box.height()) {
            graphics.enableScissor(box.x(), box.y(), box.x() + box.width(), box.y() + box.height());
            graphics.fill(x - 2, y - 2, x + 3, y + 3, 0xFFFFFFFF);
            graphics.drawString(font, t("map_airport"), x + 5, y - 4, 0xFFFFFFFF, true);
            graphics.disableScissor();
        }
    }
    private static RoadPlannerMapLayout.Rect rect(ClaimMapViewport box) { return new RoadPlannerMapLayout.Rect(box.x(), box.y(), box.width(), box.height()); }
    private boolean press(double x, double y, int button, ClaimMapViewport box, boolean shift) {
        if (autoLayoutMode) {
            if (button == 1) { endAutoLayout(); return true; }
            if (button != 0 || !editable()) return false;
            Vec3 corner = AirportMapGeometry.unproject(x, y, autoLayoutGroundY(), camera, box)
                    .orElseThrow(() -> new IllegalArgumentException("Outside map"));
            pickAutoLayoutCorner(corner);
            return true;
        }
        if (section != Section.PLANNING) return false;
        if (button == 1) { planner.setTool(AirportPlannerModel.Tool.SELECT); refreshStatus(); renderInspector(); return true; }
        if (button != 0 || !applyPrecision()) return false;
        var draft = planner.draft();
        var hit = draft.flatMap(value -> AirportMapGeometry.hitDraft(value, x, y, camera, box));
        if (hit.isPresent()) {
            AirportMapGeometry.Hit handle = hit.get();
            if (handle.handle() == AirportMapGeometry.Handle.POINT || handle.handle() == AirportMapGeometry.Handle.HEADING) {
                selectedPoint = Math.max(0, handle.index()); overview = false;
                if (editable()) { planner.beginEdit(); dragHandle = handle; }
                renderInspector(); return true;
            }
            if (shift && handle.handle() == AirportMapGeometry.Handle.SEGMENT && editable()) {
                Vec3 point = pointAt(x, y, box); planner.insertPoint(handle.index() + 1, point);
                selectedPoint = handle.index() + 1; changed(); return true;
            }
        }
        if (planner.tool() == AirportPlannerModel.Tool.SELECT) {
            var saved = AirportMapGeometry.hitSaved(data(), x, y, camera, box);
            if (saved.isEmpty()) return false;
            boolean selected = switch (saved.get().kind()) {
                case RUNWAY -> planner.selectFacility(saved.get().id());
                case TAXI -> planner.selectTaxi(saved.get().id());
                case STAND -> planner.selectStand(saved.get().id());
            };
            if (!selected) toast.show(t("unsaved_selection"), false, ticks());
            else { planner.setTool(AirportPlannerModel.Tool.SELECT); overview = false; selectedPoint = 0; synchronizePrecision(); renderInspector(); refreshStatus(); }
            return true;
        }
        if (!editable()) return false;
        safely(() -> { planner.click(pointAt(x, y, box)); selectedPoint = Math.max(0, planner.draft().orElseThrow().points().size() - 1); changed(); });
        return true;
    }
    private Vec3 pointAt(double x, double y, ClaimMapViewport box) {
        double yValue = finite(planeY);
        return AirportMapGeometry.unproject(x, y, yValue, camera, box).orElseThrow(() -> new IllegalArgumentException("Outside map"));
    }
    private void drag(double x, double y, ClaimMapViewport box) {
        if (dragHandle == null || !data().canManage()) return;
        safely(() -> {
            Vec3 point = pointAt(x, y, box);
            if (dragHandle.handle() == AirportMapGeometry.Handle.HEADING) planner.headingToward(point);
            else planner.movePoint(dragHandle.index(), point);
            synchronizePrecision(); renderInspector(); refreshStatus(); AirportClientHooks.updatePreview(this);
        });
    }
    private void finishDrag(boolean cancel) {
        if (dragHandle == null) return;
        if (cancel) planner.cancelEdit(); else planner.endEdit();
        dragHandle = null; changed();
    }
    private void changed() { synchronizePrecision(); renderInspector(); refreshStatus(); AirportClientHooks.updatePreview(this); }
    private void synchronizePrecision() {
        precision.clear(); propertyDraft = null; precisionInvalid = false;
        planner.draft().ifPresent(draft -> {
            propertyDraft = draft;
            precision.put("id", draft.id()); precision.put("runway", draft.runwayId()); precision.put("stand", draft.standId());
            precision.put("width", decimal(draft.width())); precision.put("length", decimal(draft.length()));
            precision.put("clearance", Integer.toString(draft.clearance())); precision.put("yaw", decimal(draft.yaw()));
            precision.put("speed", decimal(draft.speed())); precision.put("hold", Integer.toString(draft.holdPoint() >= 0 ? draft.holdPoint() + 1 : -1));
            selectedPoint = Math.max(0, Math.min(selectedPoint, draft.points().size() - 1));
            if (!draft.points().isEmpty()) { precision.put("point", format(draft.points().get(selectedPoint))); planeY = decimal(draft.points().get(0).y); }
        });
    }
    /** Invalid raw precision text is retained and prevents map edits/saves from silently using the old value. */
    private boolean applyPrecision() { return applyPrecision(true); }
    private boolean applyPrecision(boolean report) {
        if (busy()) return false;
        var selected = planner.draft(); if (!data().canManage() || selected.isEmpty() || propertyDraft == null) return true;
        try {
            var draft = selected.get().withId(precision.get("id"))
                    .withDimensions(finite(precision.get("length")), finite(precision.get("width")), Integer.parseInt(precision.get("clearance")))
                    .withYaw(finite(precision.get("yaw"))).withSpeed(finite(precision.get("speed")))
                    .withHoldPoint(holdIndex(precision.get("hold")))
                    .withRunwayId(precision.get("runway")).withStandId(precision.get("stand"));
            double parsedY = finite(planeY);
            if (parsedY != Math.rint(parsedY) || parsedY < -2048 || parsedY > 2047) throw new IllegalArgumentException("Invalid ground Y");
            if (!draft.points().isEmpty() && selectedPoint < draft.points().size() && precision.containsKey("point")
                    && !precision.get("point").equals(format(propertyDraft.points().get(selectedPoint)))) {
                Vec3 point = parse(precision.get("point"));
                if (point.y != parsedY) throw new IllegalArgumentException("Point height must match the ground plane");
                var points = new ArrayList<>(draft.points()); points.set(selectedPoint, point); draft = draft.withPoints(points);
            }
            if (!draft.equals(selected.get())) planner.updateProperties(draft);
            if (parsedY != planner.groundY()) planner.setGroundY((int) parsedY);
            propertyDraft = planner.draft().orElse(null);
            if (propertyDraft != null && !propertyDraft.points().isEmpty()) precision.put("point", format(propertyDraft.points().get(selectedPoint)));
            planner.clearUnsavedInput(); precisionInvalid = false;
            TextField pointField = precisionFields.get("point");
            if (pointField != null && (modularUI() == null || modularUI().getFocusedElement() != pointField)) pointField.setText(precision.getOrDefault("point", ""), false);
            return true;
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException exception) {
            precisionInvalid = true; planner.retainUnsavedInput();
            if (report) toast.show(t("result.invalid_geometry"), false, ticks());
            return false;
        }
    }
    private void renderInspector() {
        if (inspector == null) return;
        inspector.clearAllScrollViewChildren(); precisionFields.clear();
        autoLayoutStatus = null; autoLayoutSave = null;
        publishOneWay = null; publishRoundTrip = null;
        routeSelector = null;
        if (autoLayoutMode) { autoLayoutInspector(); return; }
        if (!Objects.equals(propertyDraft, planner.draft().orElse(null))) synchronizePrecision();
        if (section == Section.SETTINGS) {
            settings();
        } else if (!overview || section == Section.PLANNING) {
            if (!overview && planner.draft().isPresent()) properties(planner.draft().orElseThrow());
            else text(t("visual_hint"));
            objects();
        } else {
            overview();
        }
    }
    private void text(Component value) { inspector.addScrollViewChild(Ui.paragraph(value, "sm-muted")); }
    private void actions(UIElement... values) { UIElement row = Ui.wrapRow(Ui.GAP_XS); row.addChildren(values); inspector.addScrollViewChild(row); }
    private void field(String key, String label) {
        TextField input = field(label, precision.getOrDefault(key, ""), value -> {
            precision.put(key, value);
            // Valid dimensions repaint the overlay immediately; partial or invalid text stays in its field.
            if (data().canManage()) applyPrecision(false);
            refreshStatus(); AirportClientHooks.updatePreview(this);
        });
        precisionFields.put(key, input);
    }
    private TextField field(String label, String initial, Consumer<String> change) {
        UIElement row = Ui.column(); row.layout(l -> l.widthPercent(100).gapAll(1).flexShrink(0));
        TextField input = Ui.field(initial, label.equals("name") ? Component.literal(data().name()) : t(label), change); input.layout(l -> l.widthPercent(100)); input.setActive(data().canManage() && !busy());
        row.addChildren(Ui.clippedText(t(label), "sm-muted"), input);
        inspector.addScrollViewChild(row); return input;
    }
    private void overview() {
        if (data().facilities().isEmpty()) {
            text(t("quick_start_empty"));
            if (data().supportsRoutePublication() && data().canManage())
                actions(button("quick_helipad", () -> startFacility(AirportPlannerModel.Tool.HELIPAD)),
                        button("quick_mooring", () -> startFacility(AirportPlannerModel.Tool.MOORING)));
        } else {
            text(Component.translatable("screen.sailboatmod.airport.counts", data().facilities().size(),
                    data().groundLayout().taxiRoutes().size(), data().groundLayout().stands().size()));
        }
        if (data().supportsRoutePublication() && data().canManage() && !data().facilities().isEmpty())
            routePublicationControls();
    }
    private void startFacility(AirportPlannerModel.Tool tool) {
        if (!editable()) return;
        showPlanning();
        chooseTool(tool);
    }
    private void settings() {
        text(Component.literal(data().owner() + " · ").append(t("revision")).append(" " + data().revision()));
        field("name", nameDraft, value -> nameDraft = value);
        text(t("name_default_hint"));
        actions(button("rename", () -> send(AirportActionPacket.Action.RENAME, nameDraft)),
                button("name_follow_town", () -> { nameDraft = ""; send(AirportActionPacket.Action.RENAME, ""); }),
                button("refresh", () -> send(AirportActionPacket.Action.REFRESH, "")));
        if (nameRevision != data().revision()) actions(button("review_current", () -> { nameRevision = data().revision(); refreshStatus(); }));
        for (String line : data().statuses()) {
            String[] parts = line.split("\\|", -1);
            if (parts.length == 3 && !parts[0].equals("editor") && !parts[0].equals("feature"))
                text(Component.literal(parts[1] + " · ").append(t("state." + parts[2])));
        }
    }
    private AirportScreenData.RouteTarget routeTarget() {
        return data().routeTargets().stream().filter(target -> target.pos().equals(routeTargetPos)).findFirst().orElse(null);
    }
    private boolean validCruiseY() {
        try { Integer.parseInt(routeCruiseY.trim()); return true; }
        catch (NumberFormatException ignored) { return false; }
    }
    private void routePublicationControls() {
        inspector.addScrollViewChild(Ui.paragraph(t("quick_route_title"), "sm-strong"));
        if (data().hasRouteNameConflict()) {
            text(t("route_name_conflict"));
            actions(button("tab.settings", this::showSettings));
            return;
        }
        var targets = data().routeTargets();
        if (targets.isEmpty()) { text(t("route_no_targets")); return; }
        var chosen = routeTarget();
        if (chosen == null) {
            chosen = targets.getFirst();
            routeTargetPos = chosen.pos();
            routeCruiseY = Integer.toString(chosen.suggestedCruiseY());
        }
        var selector = new Selector<AirportScreenData.RouteTarget>();
        routeSelector = selector;
        Scrollbars.install(selector.scrollerView);
        selector.setCandidates(targets);
        selector.setSelected(chosen, false);
        selector.setCandidateUIProvider(target -> Ui.clippedText(target == null ? Component.empty()
                : Component.literal(target.name() + " · " + target.pos().toShortString())));
        selector.setOnValueChanged(target -> {
            if (target == null || target.pos().equals(routeTargetPos)) return;
            routeTargetPos = target.pos();
            routeCruiseY = Integer.toString(target.suggestedCruiseY());
            var height = precisionFields.get("routeCruiseY");
            if (height != null) height.setText(routeCruiseY, false);
            refreshStatus();
        });
        selector.layout(layout -> layout.widthPercent(100).height(Ui.CONTROL_HEIGHT).flexShrink(0));
        selector.setActive(!busy());
        inspector.addScrollViewChild(selector);
        precisionFields.put("routeCruiseY", field("route_cruise_y", routeCruiseY, value -> {
            routeCruiseY = value;
            refreshStatus();
        }));
        publishOneWay = Ui.button(t("route_publish"), Ui.Tone.SECONDARY, () -> safely(() -> publishRoute(false)));
        publishRoundTrip = Ui.button(t("route_publish_return"), Ui.Tone.PRIMARY, () -> safely(() -> publishRoute(true)));
        actions(publishRoundTrip, publishOneWay);
        text(t("quick_route_hint"));
        refreshStatus();
    }
    private void publishRoute(boolean returnRoute) {
        var target = routeTarget();
        if (target == null || !editable() || !validCruiseY() || hasManualAirportDraft()
                || data().hasRouteNameConflict() || returnRoute && !target.canPublishReturn()) return;
        var packet = AirportActionPacket.publishRoute(data().dimension(), airportPos(), data().revision(), target.pos(),
                Integer.parseInt(routeCruiseY.trim()), returnRoute);
        routeReplyDeadline = ticks() + AUTO_LAYOUT_ACK_TIMEOUT;
        send(packet);
        refreshStatus();
    }
    private void beginAutoLayout() {
        if (!data().supportsAutoLayout() || !editable()) return;
        if (map != null) map.cancelCapture();
        if (autoLayoutFirst == null) autoLayoutPrefab = data().supportsPrefab() && data().facilities().isEmpty();
        autoLayoutMode = true;
        renderToolbar();
        renderInspector(); refreshStatus(); AirportClientHooks.updatePreview(this);
    }
    private void endAutoLayout() {
        autoLayoutMode = false;
        renderToolbar();
        renderInspector(); refreshStatus(); AirportClientHooks.updatePreview(this);
    }
    private void clearAutoLayoutSelection() {
        autoLayoutFirst = null; autoLayoutSecond = null; autoLayoutProposal = null; autoLayoutRevision = -1;
        autoLayoutIssue = null;
        updateAutoLayoutPrefabMaterials();
    }
    private int autoLayoutGroundY() {
        int height = Integer.parseInt(autoLayoutY.trim());
        if (height < -2047 || height > 2047) throw new IllegalArgumentException("Invalid region height");
        return height;
    }
    private void pickAutoLayoutCorner(Vec3 corner) {
        if (!autoLayoutMode || !editable()) return;
        if (!Double.isFinite(corner.x) || !Double.isFinite(corner.z) || Math.abs(corner.x) > 30_000_000
                || Math.abs(corner.z) > 30_000_000 || corner.y != Math.rint(corner.y) || corner.y < -2047 || corner.y > 2047)
            throw new IllegalArgumentException("Invalid region corner");
        BlockPos picked = BlockPos.containing(corner);
        if (autoLayoutFirst == null || autoLayoutSecond != null) {
            clearAutoLayoutSelection();
            autoLayoutFirst = picked;
            autoLayoutY = Integer.toString(picked.getY());
        } else {
            if (picked.getY() != autoLayoutFirst.getY()) { toast.show(t("auto_layout_level"), false, ticks()); return; }
            autoLayoutSecond = picked;
        }
        autoLayoutIssue = null;
        previewAutoLayout(); renderInspector();
    }
    private void previewAutoLayout() {
        if (!autoLayoutMode || !data().supportsAutoLayout() || !editable()) return;
        autoLayoutProposal = null; autoLayoutRevision = -1;
        try {
            int height = autoLayoutGroundY();
            if (autoLayoutFirst != null) autoLayoutFirst = autoLayoutFirst.atY(height);
            if (autoLayoutSecond != null) autoLayoutSecond = autoLayoutSecond.atY(height);
            autoLayoutProposal = AirportAutoLayout.propose(autoLayoutFirst, autoLayoutSecond, data().facilities(), data().groundLayout()).orElse(null);
            if (autoLayoutProposal != null) autoLayoutRevision = data().revision();
        } catch (IllegalArgumentException ignored) { }
        updateAutoLayoutPrefabMaterials();
        refreshStatus(); AirportClientHooks.updatePreview(this);
    }
    private boolean litPrefab() { return autoLayoutPrefab && data().supportsLitPrefab(); }
    private void updateAutoLayoutPrefabMaterials() {
        autoLayoutPrefabPieces = List.of();
        autoLayoutPrefabMaterials = null;
        if (!autoLayoutPrefab || autoLayoutProposal == null) return;
        if (litPrefab()) {
            autoLayoutPrefabPieces = AirportPrefab.planDetailed(autoLayoutProposal, autoLayoutFirst, autoLayoutSecond).orElse(List.of());
            if (!autoLayoutPrefabPieces.isEmpty()) autoLayoutPrefabMaterials = AirportPrefab.materials(autoLayoutPrefabPieces);
        } else {
            autoLayoutPrefabMaterials = AirportPrefab.plan(autoLayoutProposal, autoLayoutFirst, autoLayoutSecond)
                    .map(blocks -> new AirportPrefab.Materials(blocks.size(), 0, 0)).orElse(null);
        }
    }
    private boolean hasManualAirportDraft() { return planner.dirty() || !nameDraft.equals(data().customName()); }
    private boolean canSaveAutoLayout() {
        return autoLayoutMode && data().supportsAutoLayout() && data().canManage() && !busy() && !hasManualAirportDraft()
                && autoLayoutProposal != null && autoLayoutRevision == data().revision()
                && (!autoLayoutPrefab || data().supportsPrefab() && autoLayoutPrefabMaterials != null);
    }
    private void saveAutoLayout() {
        if (!canSaveAutoLayout()) return;
        AirportActionPacket packet = new AirportActionPacket(data().dimension(), airportPos(), autoLayoutRevision,
                AirportActionPacket.Action.APPLY_AUTO_LAYOUT, litPrefab() ? AirportActionPacket.LIT_PREFAB_ID : "", "", "",
                List.of(Vec3.atLowerCornerOf(autoLayoutFirst), Vec3.atLowerCornerOf(autoLayoutSecond)),
                autoLayoutPrefab, 0, 0, 0, 0, 0, -1);
        autoLayoutIssue = null;
        autoLayoutPending = true;
        autoLayoutReplyDeadline = ticks() + AUTO_LAYOUT_ACK_TIMEOUT;
        send(packet); refreshStatus();
    }
    private String autoLayoutStatusKey() {
        if (autoLayoutPending) return data().statuses().contains("editor_terrain||loading")
                ? "auto_layout_loading" : "draft_pending";
        if (autoLayoutFirst == null) return "auto_layout_first";
        if (autoLayoutSecond == null) return "auto_layout_second";
        if (autoLayoutProposal == null) return "auto_layout_invalid";
        if (autoLayoutRevision != data().revision()) return "auto_layout_stale";
        return "auto_layout_ready";
    }
    private Component autoLayoutStatusText() {
        String statusKey = autoLayoutStatusKey();
        if (!autoLayoutPrefab || !statusKey.equals("auto_layout_ready")) return t(statusKey);
        var cost = autoLayoutPrefabMaterials;
        if (cost == null) return t("prefab_unavailable");
        if (litPrefab()) return Component.translatable("screen.sailboatmod.airport.prefab_lit_cost",
                cost.stone(), cost.markings(), cost.lights());
        return Component.translatable("screen.sailboatmod.airport.prefab_cost", cost.stone());
    }
    private void chooseAutoLayoutMode(boolean prefab) {
        if (!editable() || prefab && !data().supportsPrefab()) return;
        autoLayoutPrefab = prefab;
        autoLayoutIssue = null;
        previewAutoLayout();
        renderInspector();
    }
    private void autoLayoutInspector() {
        text(Component.translatable("screen.sailboatmod.airport.auto_layout_hint",
                AirportAutoLayout.MIN_LONG_SIDE, AirportAutoLayout.MIN_SHORT_SIDE, AirportAutoLayout.MAX_SIDE));
        if (data().supportsPrefab()) actions(
                Ui.button(t("auto_layout_register_existing"), autoLayoutPrefab ? Ui.Tone.SECONDARY : Ui.Tone.PRIMARY,
                        () -> chooseAutoLayoutMode(false)),
                Ui.button(t("auto_layout_build_prefab"), autoLayoutPrefab ? Ui.Tone.PRIMARY : Ui.Tone.SECONDARY,
                        () -> chooseAutoLayoutMode(true)));
        if (autoLayoutPrefab) text(t(litPrefab() ? "prefab_lit_hint" : "prefab_hint"));
        if (hasManualAirportDraft()) text(t("auto_layout_manual_draft"));
        precisionFields.put("autoLayoutY", field("map_plane_y", autoLayoutY, value -> { autoLayoutY = value; previewAutoLayout(); }));
        autoLayoutStatus = Ui.paragraph(autoLayoutStatusText(), "sm-muted");
        inspector.addScrollViewChild(autoLayoutStatus);
        if (autoLayoutFirst != null) text(Component.literal("1 · " + pointLabel(Vec3.atLowerCornerOf(autoLayoutFirst))));
        if (autoLayoutSecond != null) text(Component.literal("2 · " + pointLabel(Vec3.atLowerCornerOf(autoLayoutSecond))));
        autoLayoutSave = Ui.button(t(autoLayoutPrefab ? "prefab_confirm" : "auto_layout_save"), Ui.Tone.PRIMARY,
                () -> safely(this::saveAutoLayout));
        autoLayoutSave.setActive(canSaveAutoLayout());
        actions(button("auto_layout_pick", () -> {
                    if (editable()) AirportClientHooks.beginPick(this, guardedPick(this::pickAutoLayoutCorner));
                }), button("auto_layout_preview", this::previewAutoLayout), autoLayoutSave,
                button("auto_layout_clear", () -> {
                    if (editable()) { clearAutoLayoutSelection(); renderInspector(); AirportClientHooks.updatePreview(this); }
                }), button("auto_layout_back", this::endAutoLayout));
    }
    private void properties(AirportPlannerModel.Draft draft) {
        text(t("tool." + draft.kind().name().toLowerCase(Locale.ROOT)));
        if (planner.conflict()) {
            text(t("draft_conflict_hint"));
            actions(button("review_current", () -> { if (applyPrecision()) { planner.acceptCurrentRevision(); refreshStatus(); } }));
        }
        actions(button("save", this::saveSelected), button("undo", () -> { planner.undo(); changed(); }),
                button("discard", () -> { planner.discard(); changed(); }), button("delete", this::deleteSelected));
        field("id", "id");
        field("map_plane_y", planeY, value -> { planeY = value; if (data().canManage()) applyPrecision(false); refreshStatus(); AirportClientHooks.updatePreview(this); });
        text(t("plane_hint"));
        if (draft.kind().isVerticalFacility()) text(t("vertical_facility_hint"));
        if (draft.kind() == AirportPlannerModel.Tool.RUNWAY) {
            actions(button("reverse_direction", () -> { if (applyPrecision()) { planner.reverseRunway(); changed(); } }),
                    button("validate", () -> send(AirportActionPacket.Action.VALIDATE_RUNWAY, precision.get("id"))));
        } else if (draft.kind().isVerticalFacility()) {
            actions(button("validate_facility", () -> send(AirportActionPacket.Action.VALIDATE_RUNWAY, precision.get("id"))));
        }
        if (draft.kind() == AirportPlannerModel.Tool.OUTBOUND || draft.kind() == AirportPlannerModel.Tool.INBOUND) {
            field("runway", "assigned_runway"); field("stand", "assigned_stand"); field("speed", "speed");
            if (draft.kind() == AirportPlannerModel.Tool.OUTBOUND) {
                field("hold", "hold_point");
                actions(button("set_hold", this::setSelectedHoldPoint),
                        button("clear_hold", () -> { if (applyPrecision()) { planner.setHoldPoint(-1); changed(); } }));
            } else text(t("inbound_no_hold"));
        }
        if (draft.kind() == AirportPlannerModel.Tool.STAND) {
            field("yaw", "yaw"); field("length", "length");
            actions(button("use_heading", () -> { if (minecraft != null && minecraft.player != null && applyPrecision()) { planner.setHeading(minecraft.player.getYRot()); changed(); } }),
                    button(draft.cargoEnabled() ? "cargo_on" : "cargo_off", () -> { if (applyPrecision()) { planner.updateProperties(planner.draft().orElseThrow().withCargoEnabled(!draft.cargoEnabled())); changed(); } }));
        }
        field("width", "width"); field("clearance", "clearance");
        for (int i = 0; i < draft.points().size(); i++) {
            final int index = i;
            inspector.addScrollViewChild(Ui.button(Component.literal((selectedPoint == i ? "● " : "") + (i + 1) + ": " + pointLabel(draft.points().get(i))), Ui.Tone.SECONDARY,
                    () -> safely(() -> { if (applyPrecision()) { selectedPoint = index; synchronizePrecision(); renderInspector(); } })));
        }
        if (!draft.points().isEmpty()) {
            field("point", "point");
            actions(button("apply_point", () -> { if (applyPrecision()) { planner.movePoint(selectedPoint, parse(precision.get("point"))); changed(); } }),
                    button("pick_point", () -> pick(this::movePickedPoint)));
        }
        if (draft.kind() == AirportPlannerModel.Tool.OUTBOUND || draft.kind() == AirportPlannerModel.Tool.INBOUND) {
            actions(button("insert_point", () -> { if (applyPrecision()) insertPoint(parse(precision.getOrDefault("point", "0 0 0"))); }),
                    button("remove_point", this::removePoint), button("point_up", () -> reorderPoint(-1)), button("point_down", () -> reorderPoint(1)));
        }
        text(t("map_edit_hint"));
    }
    private void objects() {
        text(t("objects"));
        for (AirportFacility facility : data().facilities()) {
            if (facility.type() == AirportFacility.Type.RUNWAY || data().supportsRoutePublication())
                objectButton("▰ " + facility.id(), () -> planner.selectFacility(facility.id()));
        }
        for (AirportTaxiRoute taxi : data().groundLayout().taxiRoutes()) objectButton((taxi.direction() == AirportTaxiRoute.Direction.INBOUND ? "← " : "→ ") + taxi.id(), () -> planner.selectTaxi(taxi.id()));
        for (AirportParkingStand stand : data().groundLayout().stands()) objectButton("P " + stand.id(), () -> planner.selectStand(stand.id()));
    }
    private void objectButton(String text, java.util.function.BooleanSupplier selection) {
        inspector.addScrollViewChild(Ui.button(Component.literal(text), Ui.Tone.SECONDARY, () -> safely(() -> {
            if (!applyPrecision()) return;
            if (!selection.getAsBoolean()) toast.show(t("unsaved_selection"), false, ticks());
            else { planner.setTool(AirportPlannerModel.Tool.SELECT); overview = false; selectedPoint = 0; changed(); }
        })));
    }
    private void saveSelected() {
        if (!editable() || !applyPrecision()) return;
        if (planner.conflict()) { toast.show(t("draft_conflict_hint"), false, ticks()); return; }
        if (pendingNameRevision >= 0) throw new IllegalStateException("Awaiting rename reply");
        AirportActionPacket packet = planner.savePacket();
        planner.markSubmitted(packet); send(packet); refreshStatus();
    }
    private void deleteSelected() {
        if (!editable() || !applyPrecision()) return;
        if (planner.conflict()) { toast.show(t("draft_conflict_hint"), false, ticks()); return; }
        if (pendingNameRevision >= 0) throw new IllegalStateException("Awaiting rename reply");
        AirportActionPacket packet = planner.removePacket();
        planner.markSubmitted(packet); send(packet); refreshStatus();
    }
    private void insertPoint(Vec3 point) { planner.insertPoint(Math.min(planner.draft().orElseThrow().points().size(), selectedPoint + 1), point); selectedPoint++; changed(); }
    private void removePoint() {
        if (!editable() || planner.draft().isEmpty() || planner.draft().orElseThrow().points().isEmpty() || !applyPrecision()) return;
        planner.deletePoint(selectedPoint); selectedPoint = Math.max(0, selectedPoint - 1); changed();
    }
    private void setSelectedHoldPoint() {
        if (editable() && applyPrecision()) { planner.setHoldPoint(selectedPoint); changed(); }
    }
    /** Visible node labels start at one; native route point indices stay zero based. */
    private static int holdIndex(String text) {
        int number = Integer.parseInt(text);
        if (number == -1) return -1;
        if (number < 1) throw new IllegalArgumentException("Use a map node number or -1");
        return number - 1;
    }
    private void reorderPoint(int delta) {
        if (!editable() || !applyPrecision()) return;
        var draft = planner.draft().orElseThrow(); int next = selectedPoint + delta;
        if (next < 0 || next >= draft.points().size()) return;
        var points = new ArrayList<>(draft.points()); Collections.swap(points, selectedPoint, next);
        int hold = draft.holdPoint() == selectedPoint ? next : draft.holdPoint() == next ? selectedPoint : draft.holdPoint();
        if (hold == points.size() - 1) hold = -1;
        planner.updateProperties(draft.withPoints(points).withHoldPoint(hold)); selectedPoint = next; changed();
    }
    void movePickedPoint(Vec3 point) {
        if (!editable()) return;
        if (!Double.isFinite(point.y) || point.y != Math.rint(point.y) || point.y < -2048 || point.y > 2047)
            throw new IllegalArgumentException("Invalid picked ground plane");
        planner.beginEdit();
        try {
            planner.setGroundY((int) point.y); planner.movePoint(selectedPoint, point); planner.endEdit();
        } catch (RuntimeException failure) { planner.cancelEdit(); throw failure; }
        changed();
    }
    private void pick(Consumer<Vec3> selected) { if (editable() && applyPrecision()) AirportClientHooks.beginPick(this, guardedPick(selected)); }
    private Consumer<Vec3> guardedPick(Consumer<Vec3> selected) { return value -> safely(() -> { selected.accept(value); renderInspector(); }); }
    private void send(AirportActionPacket.Action action, String value) {
        safely(() -> {
            if (action != AirportActionPacket.Action.REFRESH && busy())
                throw new IllegalStateException("Awaiting server reply");
            long revision = action == AirportActionPacket.Action.RENAME ? nameRevision : data().revision();
            AirportActionPacket packet = AirportActionPacket.simple(data().dimension(), airportPos(), revision, action, value);
            if (action == AirportActionPacket.Action.RENAME && !editable()) return;
            send(packet);
            if (action == AirportActionPacket.Action.RENAME) { pendingName = value; pendingNameRevision = revision; refreshStatus(); }
        });
    }
    private void send(AirportActionPacket packet) {
        // Save captures the draft as pending before it reaches this low-level sender.
        if (packet.action() != AirportActionPacket.Action.REFRESH && !data().canManage()) return;
        packetSender.accept(packet);
    }
    public List<AirportFacility> previewRunways() { return autoLayoutMode && autoLayoutProposal != null ? autoLayoutProposal.facilities() : planner.previewRunways(); }
    public List<AirportParkingStand> previewStands() { return autoLayoutMode && autoLayoutProposal != null ? autoLayoutProposal.ground().stands() : planner.previewStands(); }
    public List<AirportTaxiRoute> previewTaxiRoutes() { return autoLayoutMode && autoLayoutProposal != null ? autoLayoutProposal.ground().taxiRoutes() : planner.previewTaxiRoutes(); }
    private static double finite(String text) { double value = Double.parseDouble(text); if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid number"); return value; }
    public static Vec3 parse(String text) {
        String[] parts = text.trim().split("[,\\s]+"); if (parts.length != 3) throw new IllegalArgumentException("Expected x y z");
        return new Vec3(finite(parts[0]), finite(parts[1]), finite(parts[2]));
    }
    private static String decimal(double value) { return Double.toString(value); }
    private static String format(Vec3 value) { return decimal(value.x) + " " + decimal(value.y) + " " + decimal(value.z); }
    private static String pointLabel(Vec3 value) { return labelNumber(value.x) + " " + labelNumber(value.y) + " " + labelNumber(value.z); }
    private static String labelNumber(double value) { return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    @Override protected void onUiTick() {
        if (routeReplyDeadline >= 0 && ticks() >= routeReplyDeadline) {
            routeReplyDeadline = -1;
            toast.show(t("route_no_reply"), false, ticks());
            renderInspector();
            refreshStatus();
        }
        if (autoLayoutPending && autoLayoutReplyDeadline >= 0 && ticks() >= autoLayoutReplyDeadline) {
            autoLayoutPending = false; autoLayoutReplyDeadline = -1;
            toast.show(t("auto_layout_no_reply"), false, ticks());
            refreshStatus();
        }
        if (ticks() % 40 == 0) send(AirportActionPacket.Action.REFRESH, "");
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) { return map != null && map.dragTo(x, y, button) || super.mouseDragged(x, y, button, dx, dy); }
    @Override public boolean mouseReleased(double x, double y, int button) { return map != null && map.release(button) || super.mouseReleased(x, y, button); }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (modularUI() != null && modularUI().getFocusedElement() != null && modularUI().getFocusedElement().isTextInput()) return super.keyPressed(key, scanCode, modifiers);
        if (autoLayoutMode || section != Section.PLANNING) return super.keyPressed(key, scanCode, modifiers);
        if ((modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0 && key == GLFW.GLFW_KEY_Z) { safely(() -> { planner.undo(); changed(); }); return true; }
        if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) { safely(this::removePoint); return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }
    @Override protected void onEscape() {
        if (map != null) map.cancelCapture();
        if (autoLayoutMode) { endAutoLayout(); return; }
        if (section == Section.PLANNING && planner.tool() != AirportPlannerModel.Tool.SELECT) { planner.setTool(AirportPlannerModel.Tool.SELECT); refreshStatus(); renderInspector(); }
        else super.onEscape();
    }
    @Override public void removed() { if (map != null) map.cancelCapture(); super.removed(); }
}
