package com.monpai.sailboatmod.client.screen.town;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.client.TownClientHooks;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileSyncReceiver;
import com.monpai.sailboatmod.client.screen.ClaimMapViewport;
import com.monpai.sailboatmod.client.screen.ClaimWorldMapView;
import com.monpai.sailboatmod.client.texture.NationFlagTextureCache;
import com.monpai.sailboatmod.client.texture.TownFlagUploadClient;
import com.monpai.sailboatmod.client.ui.kit.ActionButton;
import com.monpai.sailboatmod.client.ui.kit.MeterBar;
import com.monpai.sailboatmod.client.ui.kit.TabBar;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.state.ConfirmGate;
import com.monpai.sailboatmod.client.ui.territory.ClaimMapElement;
import com.monpai.sailboatmod.client.ui.territory.ClaimMapPainter;
import com.monpai.sailboatmod.client.ui.territory.FlagPreview;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;
import com.monpai.sailboatmod.nation.menu.NationOverviewMember;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import com.monpai.sailboatmod.nation.model.NationClaimAccessLevel;
import com.monpai.sailboatmod.nation.service.TownClaimService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.RefreshClaimMapViewportPacket;
import com.monpai.sailboatmod.network.packet.RequestClaimMapViewportPacket;
import com.monpai.sailboatmod.network.packet.SetTownClaimPermissionPacket;
import com.monpai.sailboatmod.network.packet.TownGuiActionPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Town home: overview, residents, territory, flag and economy, on a left page rail.
 *
 * <p>Built once and refreshed in place, so typed names, selections and the map position survive the periodic refresh.
 * Actions send the same {@link TownGuiActionPacket} / {@link SetTownClaimPermissionPacket} as before; abandoning the
 * town, removing the core and releasing territory need a second press. Joining a nation opens an in-page picker
 * (keyboard: arrows, Enter, Escape). The territory map keeps the previous viewport protocol unchanged.</p>
 */
public class TownHomeScreen extends ThemedScreen implements RoadPlannerTileSyncReceiver {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean CLAIM_TRACE_ENABLED = true;
    // Kept for the join-nation layout contract (JOIN_NATION_* bounds) checked by tests.
    private static final int SCREEN_W = 440;
    private static final int SCREEN_H = 304;
    private static final int BODY_X = 12;
    private static final int BODY_Y = 60;
    private static final int BODY_W = SCREEN_W - 24;
    private static final int CLAIM_MAP_W = 164;
    private static final int CLAIM_MAP_H = 164;
    private static final int VIEWPORT_PREFETCH_RADIUS = 2;
    private static final int AUTO_REFRESH_INTERVAL_TICKS = 40;
    private static final int PENDING_RETRY_INTERVAL_TICKS = 8;
    private static final int MEMBER_VISIBLE_ROWS = 8;
    private static final int JOIN_NATION_OVERLAY_LOCAL_X = 194;
    private static final int JOIN_NATION_OVERLAY_LOCAL_Y = 36;
    private static final int JOIN_NATION_OVERLAY_W = BODY_W - 206;
    private static final int JOIN_NATION_OVERLAY_H = 130;
    private static final int JOIN_NATION_ROW_H = 16;
    private static final int JOIN_NATION_VISIBLE_ROWS = 5;
    private static final String CONFIRM_ABANDON = "abandon";
    private static final String CONFIRM_REMOVE_CORE = "remove-core";
    private static final String CONFIRM_UNCLAIM = "unclaim";
    private static final String[] PERMISSION_ACTIONS = {"break", "place", "use", "container", "redstone", "entity_use", "entity_damage"};

    private TownOverviewData data;
    private Page currentPage = Page.OVERVIEW;
    private Component statusLine = Component.empty();
    private int selectedClaimChunkX = Integer.MIN_VALUE;
    private int selectedClaimChunkZ = Integer.MIN_VALUE;
    private int areaCorner1X = Integer.MIN_VALUE;
    private int areaCorner1Z = Integer.MIN_VALUE;
    private int areaCorner2X = Integer.MIN_VALUE;
    private int areaCorner2Z = Integer.MIN_VALUE;
    private int claimsSubPage;
    private int autoRefreshTicks;
    private int memberScroll;
    private boolean joinNationOverlayOpen;
    private int joinNationScroll;
    private int selectedJoinNationIndex;
    private String selectedMemberUuid = "";
    private int mapOffsetX = 0;
    private int mapOffsetZ = 0;
    private int lastKnownClaimRadius = -1;
    private int pendingPreviewCenterX = Integer.MIN_VALUE;
    private int pendingPreviewCenterZ = Integer.MIN_VALUE;
    private long pendingPreviewRevision = Long.MIN_VALUE;
    private int queuedPreviewCenterX = Integer.MIN_VALUE;
    private int queuedPreviewCenterZ = Integer.MIN_VALUE;
    private boolean resetPending = false;
    private boolean refreshPending;
    private final ClaimWorldMapView claimWorldMapView = new ClaimWorldMapView();
    private final Map<Long, NationOverviewClaim> cachedClaimOverlays = new HashMap<>();
    private final ConfirmGate confirm = new ConfirmGate();

    // ---- UI (created in buildBody)
    private boolean built;
    private TabBar<Page> tabs;
    private final Map<Page, UIElement> pages = new EnumMap<>(Page.class);
    private Label pageTitle;
    private Label statusLabel;
    private Label headerLabel;
    private Label economyLabel;

    // overview
    private Label popMetric;
    private Label employmentMetric;
    private Label literacyMetric;
    private Label claimsMetric;
    private UIElement overviewLines;
    private UIElement cultureChart;
    private UIElement educationChart;
    private TextField townNameInput;
    private ActionButton saveTownNameButton;
    private ActionButton abandonTownButton;
    private ActionButton joinNationButton;
    private ActionButton removeCoreButton;
    private UIElement joinNationPanel;
    private ScrollerView joinNationList;
    private ActionButton joinNationConfirmButton;
    private Object shownJoinTargets;
    private int shownJoinSelection = -1;

    // members
    private ScrollerView memberList;
    private Label mayorLabel;
    private Label memberTotal;
    private Label memberName;
    private Label memberOffice;
    private Label memberStatus;
    private ActionButton appointMayorButton;
    private Object shownMembers;
    private String shownMemberSelection;

    // claims
    private TabBar<Integer> claimsTabs;
    private UIElement claimsMapView;
    private UIElement claimsPermView;
    private UIElement claimLines;
    private ClaimMapElement claimMap;
    private ActionButton claimButton;
    private ActionButton unclaimButton;
    private final Map<String, ActionButton> permissionButtons = new LinkedHashMap<>();
    private Label permissionChunk;
    private Label permissionOwner;

    // flag
    private FlagPreview flagPreview;
    private Label flagLine1;
    private Label flagLine2;
    private Label flagLine3;
    private ActionButton toggleMirrorButton;
    private TextField flagPathInput;
    private Button browseButton;
    private ActionButton uploadButton;

    // economy
    private UIElement economyBody;
    private Label economyEmpty;
    private Label ecoPopulation;
    private Label ecoEmployment;
    private Label ecoLiteracy;
    private Label ecoNet;
    private final Map<String, MeterBar> ecoBars = new LinkedHashMap<>();
    private final Map<String, Label> ecoBarValues = new LinkedHashMap<>();
    private UIElement stockpilePanel;
    private UIElement demandPanel;
    private UIElement procurementPanel;
    private UIElement financePanel;

    // =====================================================================================================
    // UI construction
    // =====================================================================================================

    @Override
    protected float windowWidth() {
        return 600;
    }

    @Override
    protected UIElement[] headerExtras() {
        UIElement chip = Ui.row("sm-chip");
        chip.layout(l -> l.width(170).height(Ui.CONTROL_HEIGHT).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).maxWidth(170));
        headerLabel = Ui.clippedText("");
        economyLabel = Ui.clippedText("", "sm-muted");
        headerLabel.layout(l -> l.width(0).flexGrow(1));
        economyLabel.layout(l -> l.width(0).flexGrow(1));
        chip.addChildren(headerLabel, economyLabel);
        return new UIElement[] {chip,
                Ui.iconButton(new ItemStack(Items.COMPASS), Component.translatable("screen.sailboatmod.town.action.refresh"), this::requestRefresh)};
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.row();
        body.layout(l -> l.alignItems(AlignItems.STRETCH).height(250).gapAll(Ui.GAP_M));
        tabs = TabBar.rail(this::switchPage);
        tabs.add(Page.OVERVIEW, new ItemStack(Items.BELL), Page.OVERVIEW.title())
                .add(Page.MEMBERS, new ItemStack(Items.PLAYER_HEAD), Page.MEMBERS.title())
                .add(Page.CLAIMS, new ItemStack(Items.FILLED_MAP), Page.CLAIMS.title())
                .add(Page.FLAG, new ItemStack(Items.WHITE_BANNER), Page.FLAG.title())
                .add(Page.ECONOMY, new ItemStack(Items.EMERALD), Page.ECONOMY.title());
        tabs.element().layout(l -> l.width(76));

        UIElement content = Ui.column();
        content.layout(l -> l.width(0).flexGrow(1).flexShrink(1).minWidth(0).minHeight(0).gapAll(Ui.GAP_S));
        pageTitle = Ui.text("", "sm-title");
        pageTitle.textStyle(t -> t.adaptiveWidth(false));
        pageTitle.layout(l -> l.widthPercent(100).minWidth(0).flexShrink(0));
        pageTitle.setOverflowVisible(false);
        content.addChild(pageTitle);
        pages.put(Page.OVERVIEW, page(buildOverviewPage()));
        pages.put(Page.MEMBERS, buildMembersPage());
        pages.put(Page.CLAIMS, page(buildClaimsPage()));
        pages.put(Page.FLAG, page(buildFlagPage()));
        pages.put(Page.ECONOMY, page(buildEconomyPage()));
        pages.values().forEach(page -> {
            page.layout(l -> l.widthPercent(100).minWidth(0).height(0).flexGrow(1).flexShrink(1).minHeight(0));
            content.addChild(page);
        });
        statusLabel = Ui.text("", "sm-warn");
        statusLabel.textStyle(t -> t.adaptiveWidth(false));
        statusLabel.layout(l -> l.widthPercent(100).minWidth(0).flexShrink(0));
        statusLabel.setOverflowVisible(false);
        content.addChild(statusLabel);
        body.addChildren(tabs.element(), content);
        built = true;
        refresh();
        return body;
    }

    private static UIElement page(UIElement content) {
        ScrollerView scroller = Ui.scroller();
        content.layout(l -> l.widthPercent(100));
        scroller.addScrollViewChild(content);
        return scroller;
    }

    private UIElement buildOverviewPage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_S));
        popMetric = Ui.text("", "sm-price");
        employmentMetric = Ui.text("", "sm-price");
        literacyMetric = Ui.text("", "sm-price");
        claimsMetric = Ui.text("", "sm-price");
        UIElement metrics = row(metric("screen.sailboatmod.town.economy.metric.population", Items.PLAYER_HEAD, popMetric),
                metric("screen.sailboatmod.town.economy.metric.employment", Items.IRON_PICKAXE, employmentMetric),
                metric("screen.sailboatmod.town.economy.metric.literacy", Items.BOOK, literacyMetric),
                metric("screen.sailboatmod.town.overview.claims_card", Items.FILLED_MAP, claimsMetric));
        metrics.layout(l -> l.flexWrap(FlexWrap.WRAP));

        joinNationPanel = Ui.column("sm-dialog");
        joinNationPanel.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        joinNationList = Ui.scroller();
        joinNationList.layout(l -> l.height(JOIN_NATION_ROW_H * JOIN_NATION_VISIBLE_ROWS + 4).flexGrow(0));
        joinNationConfirmButton = new ActionButton(Component.translatable("gui.yes"), Ui.Tone.PRIMARY, this::submitJoinNationSelection);
        joinNationPanel.addChildren(Ui.text(Component.translatable("screen.sailboatmod.town.join_nation.title"), "sm-title"), joinNationList,
                actionRow(Ui.spacer(), Ui.button(Component.translatable("gui.cancel"), Ui.Tone.SECONDARY, this::closeJoinNationOverlay), joinNationConfirmButton.inRow().element()));

        UIElement middle = Ui.row();
        middle.layout(l -> l.gapAll(Ui.GAP_S).flexWrap(FlexWrap.WRAP)
                .alignItems(AlignItems.STRETCH).widthPercent(100).minWidth(0));
        overviewLines = Ui.column("sm-pane");
        overviewLines.layout(l -> l.width(174).flexGrow(1).flexShrink(0).minWidth(150)
                .paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));
        UIElement charts = Ui.column();
        charts.layout(l -> l.width(150).flexGrow(1).flexShrink(0).minWidth(120).gapAll(Ui.GAP_S));
        cultureChart = Ui.column("sm-pane");
        cultureChart.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));
        educationChart = Ui.column("sm-pane");
        educationChart.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));
        charts.addChildren(cultureChart, educationChart);
        middle.addChildren(overviewLines, charts);

        townNameInput = Ui.field("", Component.translatable("screen.sailboatmod.town.name"), value -> refreshButtons());
        townNameInput.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(40));
        saveTownNameButton = new ActionButton(Component.translatable("screen.sailboatmod.town.action.save_info"), Ui.Tone.PRIMARY, this::submitRenameTown);
        abandonTownButton = new ActionButton(Component.translatable("screen.sailboatmod.town.action.abandon"), Ui.Tone.DANGER, () -> {
            if (confirm.press(CONFIRM_ABANDON, ticks())) {
                submitAbandonTown();
            }
            refresh();
        });
        joinNationButton = new ActionButton(Component.translatable("screen.sailboatmod.town.action.join_nation"), Ui.Tone.SECONDARY, this::openJoinNationOverlay);
        removeCoreButton = new ActionButton(Component.translatable("screen.sailboatmod.town.action.remove_core"), Ui.Tone.DANGER, () -> {
            if (confirm.press(CONFIRM_REMOVE_CORE, ticks())) {
                submitRemoveCore();
            }
            refresh();
        });
        page.addChildren(metrics, joinNationPanel, middle,
                Ui.muted(Component.translatable("screen.sailboatmod.town.name")),
                actionRow(townNameInput, saveTownNameButton.inRow().element()),
                actionRow(joinNationButton.inRow().element(), Ui.spacer(), removeCoreButton.inRow().element(), abandonTownButton.inRow().element()),
                Ui.paragraph(Component.translatable("screen.sailboatmod.town.overview.manage_hint"), "sm-muted"));
        return page;
    }

    private UIElement buildMembersPage() {
        UIElement page = Ui.row();
        page.layout(l -> l.gapAll(Ui.GAP_M).alignItems(AlignItems.STRETCH).flexGrow(1).minHeight(0));
        UIElement left = Ui.column();
        left.layout(l -> l.widthPercent(45).maxWidth(190).minHeight(0).flexShrink(0).gapAll(Ui.GAP_XS));
        memberList = Ui.scroller();
        left.addChildren(Ui.muted(Component.translatable("screen.sailboatmod.town.members.list_title")), memberList);
        UIElement right = Ui.column("sm-pane");
        right.layout(l -> l.widthPercent(100).minWidth(0).paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        mayorLabel = Ui.clippedText("");
        memberTotal = Ui.clippedText("", "sm-muted");
        memberName = Ui.clippedText("", "sm-title");
        memberOffice = Ui.clippedText("");
        memberStatus = Ui.clippedText("", "sm-muted");
        appointMayorButton = new ActionButton(Component.translatable("screen.sailboatmod.town.action.appoint_mayor"), Ui.Tone.PRIMARY, this::appointSelectedMayor);
        right.addChildren(Ui.muted(Component.translatable("screen.sailboatmod.town.members.detail_title")), mayorLabel, memberTotal, Ui.divider(),
                memberName, memberOffice, memberStatus, appointMayorButton.element(), Ui.spacer(),
                Ui.paragraph(Component.translatable("screen.sailboatmod.town.members.manage_hint"), "sm-muted"));
        ScrollerView detail = Ui.scroller();
        detail.layout(l -> l.heightAuto().width(0).flexGrow(1).minWidth(0));
        detail.addScrollViewChild(right);
        page.addChildren(left, detail);
        return page;
    }

    private UIElement buildClaimsPage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_S).flexGrow(1).minHeight(0));
        claimsTabs = TabBar.horizontal(sub -> {
            claimsSubPage = sub;
            refresh();
        });
        claimsTabs.add(0, new ItemStack(Items.FILLED_MAP), Component.translatable("screen.sailboatmod.nation.claims.show_map"))
                .add(1, new ItemStack(Items.SHIELD), Component.translatable("screen.sailboatmod.nation.claims.show_perms"));
        claimsMapView = Ui.row();
        claimsMapView.layout(l -> l.gapAll(Ui.GAP_M).flexWrap(FlexWrap.WRAP).alignItems(AlignItems.STRETCH).flexGrow(1).minHeight(0));
        UIElement info = Ui.column();
        info.layout(l -> l.width(0).flexGrow(1).minWidth(120).gapAll(Ui.GAP_S));
        claimLines = Ui.column("sm-pane");
        claimLines.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));
        claimButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.claim"), Ui.Tone.PRIMARY, this::claimSelectedChunk);
        unclaimButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.unclaim"), Ui.Tone.DANGER, () -> {
            if (confirm.press(CONFIRM_UNCLAIM, ticks())) {
                unclaimSelectedChunk();
            }
            refresh();
        });
        info.addChildren(claimLines, actionRow(claimButton.inRow().element(), unclaimButton.inRow().element()),
                Ui.paragraph(Component.translatable("screen.sailboatmod.ui.territory.map_hint"), "sm-muted"));
        UIElement mapColumn = Ui.column();
        mapColumn.layout(l -> l.width(CLAIM_MAP_W + 2).flexShrink(0).gapAll(Ui.GAP_XS));
        UIElement mapTools = Ui.row();
        mapTools.layout(l -> l.gapAll(Ui.GAP_XS));
        mapTools.addChildren(Ui.muted(Component.translatable("screen.sailboatmod.town.claims.map_title")), Ui.spacer(),
                Ui.iconButton(new ItemStack(Items.COMPASS), Component.translatable("screen.sailboatmod.ui.territory.recenter"), this::resetMapOffset),
                Ui.iconButton(new ItemStack(Items.CLOCK), Component.translatable("screen.sailboatmod.town.action.refresh"), this::requestRefresh));
        claimMap = new ClaimMapElement(mapHost());
        claimMap.layout(l -> l.width(CLAIM_MAP_W + 2).height(CLAIM_MAP_H + 2).flexShrink(0));
        mapColumn.addChildren(mapTools, claimMap);
        claimsMapView.addChildren(info, mapColumn);

        claimsPermView = Ui.column("sm-pane");
        claimsPermView.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        UIElement grid = Ui.wrapRow(Ui.GAP_S);
        grid.layout(l -> l.widthPercent(100));
        for (String action : PERMISSION_ACTIONS) {
            ActionButton button = new ActionButton(Component.empty(), Ui.Tone.SECONDARY,
                    () -> cycleClaimPermission(action, selectedAccessLevel(action)));
            button.element().layout(l -> l.width(150));
            permissionButtons.put(action, button);
            grid.addChild(button.element());
        }
        permissionChunk = Ui.clippedText("");
        permissionOwner = Ui.clippedText("", "sm-muted");
        claimsPermView.addChildren(Ui.muted(Component.translatable("screen.sailboatmod.nation.claims.perms_title")), grid,
                Ui.divider(), permissionChunk, permissionOwner);
        page.addChildren(claimsTabs.element(), claimsMapView, claimsPermView);
        return page;
    }

    private UIElement buildFlagPage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_S));
        UIElement top = Ui.row();
        top.layout(l -> l.gapAll(Ui.GAP_M).flexWrap(FlexWrap.WRAP).alignItems(AlignItems.STRETCH));
        flagPreview = new FlagPreview();
        flagPreview.layout(l -> l.width(130).height(70).flexShrink(0).paddingAll(Ui.GAP_XS));
        UIElement meta = Ui.column();
        meta.layout(l -> l.width(0).flexGrow(1).minWidth(100).gapAll(Ui.GAP_XS));
        flagLine1 = Ui.clippedText("");
        flagLine2 = Ui.clippedText("");
        flagLine3 = Ui.clippedText("", "sm-muted");
        toggleMirrorButton = new ActionButton(Component.empty(), Ui.Tone.SECONDARY, this::submitMirrorToggle);
        meta.addChildren(flagLine1, flagLine2, flagLine3, toggleMirrorButton.element());
        top.addChildren(flagPreview, meta);
        UIElement upload = Ui.column("sm-pane");
        upload.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        flagPathInput = Ui.field("", Component.translatable("screen.sailboatmod.town.flag_path"), value -> { });
        flagPathInput.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(40));
        browseButton = Ui.button(Component.translatable("screen.sailboatmod.nation.action.browse"), Ui.Tone.SECONDARY, this::browseForImage);
        uploadButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.upload"), Ui.Tone.PRIMARY, this::submitUpload);
        upload.addChildren(Ui.muted(Component.translatable("screen.sailboatmod.town.flag.upload_hint_short")),
                actionRow(flagPathInput, browseButton, uploadButton.inRow().element()));
        page.addChildren(top, upload);
        return page;
    }

    private UIElement buildEconomyPage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_S));
        economyEmpty = Ui.paragraph(Component.translatable("screen.sailboatmod.town.economy.no_town"));
        economyBody = Ui.column();
        economyBody.layout(l -> l.gapAll(Ui.GAP_S));
        ecoPopulation = Ui.text("", "sm-price");
        ecoEmployment = Ui.text("", "sm-price");
        ecoLiteracy = Ui.text("", "sm-price");
        ecoNet = Ui.text("", "sm-price");
        UIElement metrics = row(metric("screen.sailboatmod.town.economy.metric.population", Items.PLAYER_HEAD, ecoPopulation),
                metric("screen.sailboatmod.town.economy.metric.employment", Items.IRON_PICKAXE, ecoEmployment),
                metric("screen.sailboatmod.town.economy.metric.literacy", Items.BOOK, ecoLiteracy),
                metric("screen.sailboatmod.town.economy.metric.net", Items.EMERALD, ecoNet));
        metrics.layout(l -> l.flexWrap(FlexWrap.WRAP));
        UIElement status = Ui.column("sm-pane");
        status.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));
        status.addChild(Ui.text(Component.translatable("screen.sailboatmod.town.economy.status_title"), "sm-title"));
        for (String key : new String[] {"cashflow", "coverage", "workforce", "education"}) {
            MeterBar bar = new MeterBar();
            Label value = Ui.alignRight(Ui.clippedText("", "sm-muted"));
            Label label = Ui.clippedText(Component.translatable("screen.sailboatmod.town.economy.bar." + key), "sm-muted");
            label.layout(l -> l.width(0).flexGrow(1));
            bar.layout(l -> l.widthPercent(100));
            value.layout(l -> l.width(0).flexGrow(1));
            ecoBars.put(key, bar);
            ecoBarValues.put(key, value);
            UIElement measure = Ui.column();
            measure.layout(l -> l.widthPercent(100).minWidth(0).gapAll(Ui.GAP_XS));
            measure.addChildren(row(label, value), bar);
            status.addChild(measure);
        }
        stockpilePanel = previewPanel();
        demandPanel = previewPanel();
        procurementPanel = previewPanel();
        financePanel = previewPanel();
        economyBody.addChildren(metrics, status, actionRow(stockpilePanel, demandPanel), actionRow(procurementPanel, financePanel),
                Ui.paragraph(Component.translatable("screen.sailboatmod.town.economy.summary_hint"), "sm-muted"));
        page.addChildren(economyEmpty, economyBody);
        return page;
    }

    private static UIElement previewPanel() {
        UIElement panel = Ui.column("sm-pane");
        panel.layout(l -> l.flexGrow(1).flexBasis(130).flexShrink(0).minWidth(130)
                .paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));
        return panel;
    }

    private static void fillPreview(UIElement panel, Component title, List<String> lines) {
        panel.clearAllChildren();
        panel.addChild(Ui.clippedText(title, "sm-title"));
        int shown = 0;
        for (String line : lines) {
            if (shown++ >= 5) {
                break;
            }
            panel.addChild(Ui.clippedText(line));
        }
    }

    private static UIElement metric(String key, net.minecraft.world.item.Item icon, Label value) {
        UIElement card = Ui.column("sm-chip");
        card.layout(l -> l.flexGrow(1).flexBasis(90).flexShrink(0).minWidth(90)
                .height(30).paddingAll(Ui.GAP_XS).gapAll(Ui.GAP_XS));
        UIElement head = Ui.row();
        head.layout(l -> l.gapAll(Ui.GAP_XS));
        Label caption = Ui.clippedText(Component.translatable(key), "sm-muted");
        caption.layout(l -> l.width(0).flexGrow(1));
        head.addChildren(Ui.icon(new ItemStack(icon), 10), caption);
        value.textStyle(s -> s.adaptiveWidth(false).adaptiveHeight(false));
        value.layout(l -> l.widthPercent(100).height(9).flexShrink(0));
        value.setOverflowVisible(false);
        card.addChildren(head, value);
        return card;
    }

    private static UIElement row(UIElement... children) {
        UIElement row = Ui.row();
        row.layout(l -> l.gapAll(Ui.GAP_S).widthPercent(100).minWidth(0));
        row.addChildren(children);
        return row;
    }

    private static UIElement actionRow(UIElement... children) {
        return row(children).layout(l -> l.flexWrap(FlexWrap.WRAP));
    }

    // =====================================================================================================
    // refresh
    // =====================================================================================================

    private void refresh() {
        if (!built) {
            return;
        }
        headerLabel.setValue(Component.literal(headerText()));
        economyLabel.setValue(Component.literal(buildEconomyHeaderLine()));
        tabs.select(currentPage);
        pageTitle.setValue(currentPage.title());
        pages.forEach((page, element) -> element.setDisplay(page == currentPage));
        statusLabel.setValue(statusLine);
        switch (currentPage) {
            case OVERVIEW -> refreshOverview();
            case MEMBERS -> refreshMembers();
            case CLAIMS -> refreshClaims();
            case FLAG -> refreshFlag();
            case ECONOMY -> refreshEconomy();
        }
        refreshButtons();
    }

    private void refreshOverview() {
        popMetric.setValue(Component.literal(populationMetricValue()));
        employmentMetric.setValue(Component.literal(formatPercent(data.employmentRate())));
        literacyMetric.setValue(Component.literal(formatPercent(data.averageLiteracy())));
        claimsMetric.setValue(Component.literal(Integer.toString(data.totalClaims())));
        overviewLines.clearAllChildren();
        overviewLines.addChild(Ui.text(Component.translatable("screen.sailboatmod.town.overview.city_stats"), "sm-title"));
        for (Component line : buildOverviewLines()) {
            overviewLines.addChild(Ui.paragraph(line));
        }
        fillDistribution(cultureChart, Component.translatable("screen.sailboatmod.town.overview.culture_chart"),
                buildSortedDistribution(data.cultureDistribution()), cultureDisplayName(data.cultureId()), 0xFF6FA4C1);
        fillDistribution(educationChart, Component.translatable("screen.sailboatmod.town.overview.education_chart"),
                buildSortedDistribution(data.educationLevelDistribution()), dominantEducationName(), 0xFFB68D56);

        joinNationPanel.setDisplay(joinNationOverlayOpen);
        List<TownOverviewData.JoinableNationTarget> targets = joinableNationTargets();
        if (joinNationOverlayOpen && (shownJoinTargets != targets || shownJoinSelection != selectedJoinNationIndex)) {
            shownJoinTargets = targets;
            shownJoinSelection = selectedJoinNationIndex;
            joinNationList.clearAllScrollViewChildren();
            if (targets.isEmpty()) {
                joinNationList.addScrollViewChild(Ui.emptyState(Component.translatable("screen.sailboatmod.town.join_nation.empty")));
            }
            for (int i = 0; i < targets.size(); i++) {
                int index = i;
                UIElement row = Ui.row("sm-row");
                row.layout(l -> l.widthPercent(100).height(JOIN_NATION_ROW_H).paddingHorizontal(Ui.GAP_S).flexShrink(0));
                row.addChild(Ui.clippedText(targets.get(i).nationName()));
                Ui.setClass(row, "selected", i == selectedJoinNationIndex);
                Ui.clickable(row, () -> {
                    selectedJoinNationIndex = index;
                    refresh();
                });
                joinNationList.addScrollViewChild(row);
            }
        }
    }

    private void fillDistribution(UIElement panel, Component title, List<Map.Entry<String, Integer>> entries, String focus, int color) {
        panel.clearAllChildren();
        Label heading = Ui.clippedText(title, "sm-title");
        Label detail = Ui.clippedText(focus, "sm-muted");
        heading.layout(l -> l.width(0).flexGrow(1));
        detail.layout(l -> l.width(0).flexGrow(1));
        panel.addChild(row(heading, detail));
        if (entries.isEmpty()) {
            panel.addChild(Ui.text(Component.translatable("screen.sailboatmod.town.overview.chart_none"), "sm-muted"));
            return;
        }
        int total = 0;
        for (Map.Entry<String, Integer> entry : entries) {
            total += Math.max(0, entry.getValue());
        }
        int shown = 0;
        for (Map.Entry<String, Integer> entry : entries) {
            if (shown++ >= 3) {
                break;
            }
            Label name = Ui.clippedText(overviewCategoryName(entry.getKey()));
            name.layout(l -> l.width(0).flexGrow(1));
            MeterBar bar = new MeterBar().set(total <= 0 ? 0f : Math.max(0, entry.getValue()) / (float) total, color);
            bar.layout(l -> l.widthPercent(100));
            UIElement measure = Ui.column();
            measure.layout(l -> l.widthPercent(100).minWidth(0).gapAll(Ui.GAP_XS));
            measure.addChildren(row(name, Ui.text(Integer.toString(entry.getValue()), "sm-muted")), bar);
            panel.addChild(measure);
        }
    }

    private void refreshMembers() {
        mayorLabel.setValue(Component.translatable("screen.sailboatmod.town.overview.mayor_current", data.mayorName()));
        memberTotal.setValue(Component.translatable("screen.sailboatmod.town.members.total", data.members().size()));
        if (shownMembers != data.members() || !selectedMemberUuid.equals(shownMemberSelection)) {
            shownMembers = data.members();
            shownMemberSelection = selectedMemberUuid;
            memberList.clearAllScrollViewChildren();
            if (data.members().isEmpty()) {
                memberList.addScrollViewChild(Ui.emptyState(Component.translatable("screen.sailboatmod.town.members.empty")));
            }
            for (NationOverviewMember member : data.members()) {
                UIElement row = Ui.row("sm-row");
                row.layout(l -> l.widthPercent(100).height(14).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).flexShrink(0));
                Label name = Ui.clippedText(member.playerName(), member.online() ? "" : "sm-muted");
                name.layout(l -> l.width(0).flexGrow(1));
                String office = member.officeName().isBlank() ? member.officeId() : member.officeName();
                Label officeLabel = Ui.clippedText(office, "sm-warn");
                officeLabel.layout(l -> l.widthPercent(35).maxWidth(70));
                row.addChildren(name, officeLabel);
                Ui.setClass(row, "selected", member.playerUuid().equals(selectedMemberUuid));
                Ui.clickable(row, () -> {
                    selectedMemberUuid = member.playerUuid();
                    refresh();
                });
                memberList.addScrollViewChild(row);
            }
        }
        NationOverviewMember selected = selectedMember();
        if (selected == null) {
            memberName.setValue(Component.translatable("screen.sailboatmod.town.members.no_selection"));
            memberOffice.setValue(Component.empty());
            memberStatus.setValue(Component.empty());
        } else {
            memberName.setValue(Component.literal(selected.playerName()));
            memberOffice.setValue(Component.literal(selected.officeName().isBlank() ? selected.officeId() : selected.officeName()));
            memberStatus.setValue(Component.translatable(selected.online() ? "screen.sailboatmod.town.members.status.online" : "screen.sailboatmod.town.members.status.offline"));
        }
    }

    private void refreshClaims() {
        claimsTabs.select(claimsSubPage);
        boolean perms = claimsSubPage == 1;
        claimsMapView.setDisplay(!perms);
        claimsPermView.setDisplay(perms);
        claimLines.clearAllChildren();
        for (Component line : buildClaimLines()) {
            claimLines.addChild(Ui.paragraph(line));
        }
        NationOverviewClaim selected = firstSelectedAreaClaim();
        if (selected == null) {
            permissionChunk.setValue(Component.translatable("screen.sailboatmod.nation.claims.perms_select_hint"));
            permissionOwner.setValue(Component.empty());
        } else {
            permissionChunk.setValue(Component.translatable("screen.sailboatmod.town.claims.selected_chunk", selectedClaimChunkX, selectedClaimChunkZ));
            permissionOwner.setValue(Component.translatable("screen.sailboatmod.town.claims.owner", selected.nationName()));
        }
        for (Map.Entry<String, ActionButton> entry : permissionButtons.entrySet()) {
            entry.getValue().setLabel(Component.translatable("screen.sailboatmod.nation.claims.button." + entry.getKey(),
                    accessName(selectedAccessLevel(entry.getKey()))));
        }
    }

    private void refreshFlag() {
        List<Component> lines = buildFlagLines();
        flagLine1.setValue(firstLine(lines));
        flagLine2.setValue(secondLine(lines));
        flagLine3.setValue(thirdLine(lines));
        flagPreview.set(NationFlagTextureCache.resolve(data.flagId(), data.primaryColorRgb(), data.secondaryColorRgb(), data.flagMirrored()),
                data.flagWidth(), data.flagHeight(), data.primaryColorRgb());
        toggleMirrorButton.setLabel(Component.translatable(data.flagMirrored() ? "screen.sailboatmod.nation.action.unmirror" : "screen.sailboatmod.nation.action.mirror"));
    }

    private void refreshEconomy() {
        boolean hasTown = data.hasTown();
        economyEmpty.setDisplay(!hasTown);
        economyBody.setDisplay(hasTown);
        if (!hasTown) {
            return;
        }
        ecoPopulation.setValue(Component.literal(populationMetricValue()));
        ecoEmployment.setValue(Component.literal(formatPercent(data.employmentRate())));
        ecoLiteracy.setValue(Component.literal(formatPercent(data.averageLiteracy())));
        ecoNet.setValue(Component.literal(formatMoney(data.netBalance())));
        Ui.setClass(ecoNet, "sm-down", data.netBalance() < 0L);
        long income = Math.max(0L, data.totalIncome());
        long expense = Math.max(0L, data.totalExpense());
        setBar("cashflow", income + expense <= 0 ? 0f : income / (float) (income + expense), data.netBalance() >= 0L ? 0xFF5DA06A : 0xFFA05D5D,
                formatMoney(data.totalIncome()) + " / -" + formatMoney(data.totalExpense()));
        int stock = Math.max(0, data.stockpileTotalUnits());
        setBar("coverage", stock / (float) Math.max(1, data.stockpileTotalUnits() + data.openDemandUnits()), 0xFF7A9D62,
                data.stockpileTotalUnits() + " / " + data.openDemandUnits());
        setBar("workforce", data.employmentRate(), 0xFF5D8FA0, formatPercent(data.employmentRate()));
        setBar("education", data.averageLiteracy(), 0xFFB68D56, formatPercent(data.averageLiteracy()));
        fillPreview(stockpilePanel, Component.translatable("screen.sailboatmod.town.economy.section.stockpile"),
                previewLines(data.stockpilePreviewLines(),
                        Component.translatable("screen.sailboatmod.town.economy.stockpile_types", data.stockpileCommodityTypes()),
                        Component.translatable("screen.sailboatmod.town.economy.stockpile_units", data.stockpileTotalUnits())));
        fillPreview(demandPanel, Component.translatable("screen.sailboatmod.town.economy.section.demands"),
                previewLines(data.demandPreviewLines(),
                        Component.translatable("screen.sailboatmod.town.economy.open_demands", data.openDemandCount()),
                        Component.translatable("screen.sailboatmod.town.economy.shortage_units", data.openDemandUnits())));
        fillPreview(procurementPanel, Component.translatable("screen.sailboatmod.town.economy.section.procurements"),
                previewLines(data.procurementPreviewLines(),
                        Component.translatable("screen.sailboatmod.town.economy.active_procurements", data.activeProcurementCount())));
        fillPreview(financePanel, Component.translatable("screen.sailboatmod.town.economy.section.finance"),
                previewLines(data.financePreviewLines(),
                        Component.translatable("screen.sailboatmod.town.economy.total_income", formatMoney(data.totalIncome())),
                        Component.translatable("screen.sailboatmod.town.economy.total_expense", formatMoney(data.totalExpense())),
                        Component.translatable("screen.sailboatmod.town.economy.net_balance", formatMoney(data.netBalance()))));
    }

    private void setBar(String key, float ratio, int color, String value) {
        ecoBars.get(key).set(ratio, color);
        ecoBarValues.get(key).setValue(Component.literal(value));
    }

    private void refreshButtons() {
        if (!built) {
            return;
        }
        long now = ticks();
        boolean hasTown = data.hasTown();
        boolean canManageTown = hasTown && data.canManageTown();
        townNameInput.setActive(canManageTown);
        saveTownNameButton.sync(canManageTown && townNameChanged(), false, false);
        abandonTownButton.element().setDisplay(hasTown);
        abandonTownButton.sync(hasTown && data.isMayor(), false, confirm.isArmed(CONFIRM_ABANDON, now));
        joinNationButton.element().setDisplay(shouldShowJoinNationButton() && !joinNationOverlayOpen);
        joinNationButton.sync(shouldShowJoinNationButton(), false, false);
        removeCoreButton.element().setDisplay(hasTown);
        removeCoreButton.sync(hasTown && data.isMayor() && data.hasCore(), false, confirm.isArmed(CONFIRM_REMOVE_CORE, now));
        joinNationConfirmButton.sync(joinNationOverlayOpen && selectedJoinNationTarget() != null, false, false);
        appointMayorButton.sync(hasTown && canAssignSelectedMemberAsMayor(), false, false);

        boolean areaHasClaim = selectedClaimAreaHasAnyClaim();
        boolean ownClaim = selectedClaimAreaOwnedByTown();
        boolean canClaims = hasTown && data.canManageTown();
        claimButton.sync(canClaims && !areaHasClaim, false, false);
        unclaimButton.sync(canClaims && ownClaim, false, confirm.isArmed(CONFIRM_UNCLAIM, now));
        for (ActionButton button : permissionButtons.values()) {
            button.sync(canClaims && ownClaim, false, false);
        }

        boolean canFlag = hasTown && data.canUploadFlag();
        flagPathInput.setActive(canFlag);
        browseButton.setActive(canFlag);
        uploadButton.sync(canFlag, false, false);
        toggleMirrorButton.sync(canFlag && !data.flagId().isBlank(), false, false);
    }

    private String selectedAccessLevel(String action) {
        return switch (action) {
            case "break" -> selectedBreakAccessLevel();
            case "place" -> selectedPlaceAccessLevel();
            case "use" -> selectedUseAccessLevel();
            case "container" -> selectedContainerAccessLevel();
            case "redstone" -> selectedRedstoneAccessLevel();
            case "entity_use" -> selectedEntityUseAccessLevel();
            default -> selectedEntityDamageAccessLevel();
        };
    }

    // =====================================================================================================
    // map host
    // =====================================================================================================

    private ClaimMapElement.Host mapHost() {
        return new ClaimMapElement.Host() {
            @Override
            public void paintMap(GuiGraphics g, ClaimMapViewport viewport, int mouseX, int mouseY) {
                claimWorldMapView.renderBase(g, font, viewport, mapCenterX(), mapCenterZ(), claimRadius());
                requestInitialVisibleClaimMapForceRender(viewport);
                ClaimMapPainter.drawClaims(g, claimWorldMapView, viewport, TownHomeScreen.this::findClaim, TownHomeScreen.this::claimOwnerKey);
                ClaimMapPainter.drawMarker(g, claimWorldMapView, viewport, data.currentChunkX(), data.currentChunkZ(), 0xFFFFFFFF);
                ClaimMapPainter.drawMarker(g, claimWorldMapView, viewport, selectedClaimChunkX, selectedClaimChunkZ, 0xFFFFD166);
                ClaimMapPainter.drawArea(g, claimWorldMapView, viewport, areaCorner1X, areaCorner1Z, areaCorner2X, areaCorner2Z);
                ClaimMapPainter.drawProgress(g, viewport, data.claimMapState(),
                        claimMapProgressWidth(data.claimMapState(), viewport.width() - 2, true),
                        claimMapProgressWidth(data.claimMapState(), viewport.width() - 2, false));
                ClaimMapPainter.drawHoverLabel(g, font, claimWorldMapView, viewport, mouseX, mouseY, TownHomeScreen.this::findClaim,
                        TownHomeScreen.this::claimOwnerKey,
                        claim -> claim.townId().isBlank() ? claim.nationName() : claim.townName(), data.nearbyClaims());
            }

            @Override
            public void zoomMap(double mouseX, double mouseY, double factor, ClaimMapViewport viewport) {
                claimWorldMapView.zoomAround(mouseX, mouseY, factor, viewport.x(), viewport.y(), viewport.width(), viewport.height());
                syncMapOffsetFromClaimWorldMap();
            }

            @Override
            public void clickMap(double mouseX, double mouseY, ClaimMapViewport viewport, boolean extendArea) {
                ChunkPos chunk = claimWorldMapView.screenToChunk(mouseX, mouseY, viewport);
                if (extendArea && areaCorner1X != Integer.MIN_VALUE) {
                    areaCorner2X = chunk.x;
                    areaCorner2Z = chunk.z;
                } else {
                    selectedClaimChunkX = chunk.x;
                    selectedClaimChunkZ = chunk.z;
                    areaCorner1X = chunk.x;
                    areaCorner1Z = chunk.z;
                    areaCorner2X = Integer.MIN_VALUE;
                    areaCorner2Z = Integer.MIN_VALUE;
                }
                confirm.disarm();
                refresh();
            }

            @Override
            public void panMap(double deltaX, double deltaY) {
                claimWorldMapView.panByScreenDelta(deltaX, deltaY);
                syncMapOffsetFromClaimWorldMap();
            }

            @Override
            public void panFinished() {
                requestRefresh(mapCenterX(), mapCenterZ());
                requestVisibleClaimMapForceRender();
            }
        };
    }

    private ClaimMapViewport claimMapViewport() {
        return claimMap == null ? new ClaimMapViewport(0, 0, CLAIM_MAP_W, CLAIM_MAP_H) : claimMap.viewport();
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (claimMap != null && claimMap.dragTo(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (claimMap != null && shouldRequestRefreshAfterMapDragRelease(button, claimMap.isDragging())) {
            claimMap.release(button);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // =====================================================================================================
    // lifecycle and keys
    // =====================================================================================================

    @Override
    protected void onUiTick() {
        tickAutoRefresh();
        if (built) {
            statusLabel.setValue(statusLine);
            refreshButtons();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.joinNationOverlayOpen && (modularUI() == null || modularUI().getFocusedElement() == null
                || !modularUI().getFocusedElement().isTextInput())) {
            switch (keyCode) {
                case 256 -> {
                    closeJoinNationOverlay();
                    return true;
                }
                case 264 -> {
                    moveJoinNationSelection(1);
                    return true;
                }
                case 265 -> {
                    moveJoinNationSelection(-1);
                    return true;
                }
                case 257, 335 -> {
                    return submitAndTrue(this::submitJoinNationSelection);
                }
                default -> {
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected boolean onTextFieldKey(UIElement field, int keyCode) {
        if (!isEnter(keyCode)) {
            return false;
        }
        if (field == townNameInput) return submitAndTrue(this::submitRenameTown);
        if (field == flagPathInput) return submitAndTrue(this::submitUpload);
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        TownClientHooks.onScreenClosed(this.data.townId());
        this.claimWorldMapView.close();
    }

    private void switchPage(Page page) {
        this.currentPage = page;
        confirm.disarm();
        if (this.currentPage == Page.CLAIMS) {
            ensureClaimPreviewVisible();
        }
        refresh();
    }

    private void syncTownNameInput() {
        if (this.townNameInput != null && !this.townNameInput.isFocused()) {
            this.townNameInput.setText(this.data.townName(), false);
        }
    }

    @Override
    protected void afterInit() {
        syncTownNameInput();
        refresh();
    }

    private static String formatPercent(float ratio) {
        return Math.round(Math.max(0.0f, Math.min(1.0f, ratio)) * 100.0f) + "%";
    }

    private static String formatMoney(long value) {
        return GoldStandardEconomy.formatBalance(value);
    }

    private String populationMetricValue() {
        if (this.data.externalColony().present() && this.data.externalColony().population() > 0) {
            return this.data.residentCount() + "+" + this.data.externalColony().population();
        }
        return Integer.toString(this.data.residentCount());
    }


    // =====================================================================================================
    // data, map protocol and actions (kept from the previous screen)
    // =====================================================================================================

    private int claimRadius() {
        int stateRadius = this.data.claimMapState().radius();
        if (stateRadius > 0) {
            this.lastKnownClaimRadius = stateRadius;
            return stateRadius;
        }
        Integer inferredRadius = inferClaimRadiusFromTerrainColors(this.data.nearbyTerrainColors().size());
        if (inferredRadius != null) {
            this.lastKnownClaimRadius = inferredRadius;
            return inferredRadius;
        }
        if (this.lastKnownClaimRadius >= 0) {
            return this.lastKnownClaimRadius;
        }
        int configuredRadius = configuredClaimRadiusOrZero();
        this.lastKnownClaimRadius = configuredRadius;
        return configuredRadius;
    }

    public TownHomeScreen(TownOverviewData data) {
        super(Component.translatable("screen.sailboatmod.town.home.title"));
        this.data = data == null ? TownOverviewData.empty() : data;
        rememberClaimRadius(this.data);
        syncSelections();
        syncClaimWorldMapViewToMapCenter(false);
    }

    public void updateData(TownOverviewData updated) {
        TownOverviewData previousData = this.data;
        rememberClaimRadius(previousData);
        TownOverviewData nextData = updated == null ? TownOverviewData.empty() : updated;
        boolean preserveVisibleCenter = shouldPreserveClaimMapVisibleCenter(
                previousData == null ? "" : previousData.townId(),
                nextData.townId(),
                this.mapOffsetX,
                this.mapOffsetZ,
                this.refreshPending,
                this.resetPending
        );
        int visibleCenterX = preserveVisibleCenter ? mapCenterX() : Integer.MIN_VALUE;
        int visibleCenterZ = preserveVisibleCenter ? mapCenterZ() : Integer.MIN_VALUE;
        this.data = nextData;
        rememberClaimRadius(this.data);
        traceClaim("updateData previewCenter=" + this.data.previewCenterChunkX() + "," + this.data.previewCenterChunkZ()
                + " visibleCenterBefore=" + visibleCenterX + "," + visibleCenterZ
                + " terrainCount=" + this.data.nearbyTerrainColors().size()
                + " nearbyClaims=" + this.data.nearbyClaims().size()
                + " pending=" + this.pendingPreviewCenterX + "," + this.pendingPreviewCenterZ
                + " queued=" + this.queuedPreviewCenterX + "," + this.queuedPreviewCenterZ
                + " resetPending=" + this.resetPending);
        boolean pendingResolved = this.refreshPending
                && this.pendingPreviewRevision != Long.MIN_VALUE
                && this.data.claimMapState().revision() >= this.pendingPreviewRevision
                && !this.data.claimMapState().loading();
        if (pendingResolved) {
            this.refreshPending = false;
            this.pendingPreviewCenterX = Integer.MIN_VALUE;
            this.pendingPreviewCenterZ = Integer.MIN_VALUE;
            this.pendingPreviewRevision = Long.MIN_VALUE;
        }
        this.autoRefreshTicks = 0;
        if (shouldClearClaimOverlayCache(previousData, this.data)) {
            this.cachedClaimOverlays.clear();
        }
        cacheNearbyClaims();
        if (this.resetPending) {
            this.mapOffsetX = 0;
            this.mapOffsetZ = 0;
            this.resetPending = false;
        } else if (!preserveVisibleCenter) {
            this.mapOffsetX = 0;
            this.mapOffsetZ = 0;
        } else {
            this.mapOffsetX = visibleCenterX - this.data.previewCenterChunkX();
            this.mapOffsetZ = visibleCenterZ - this.data.previewCenterChunkZ();
        }
        syncClaimWorldMapViewToMapCenter(true);
        this.memberScroll = clampMemberScroll(this.memberScroll);
        syncSelections();
        syncTownNameInput();
        this.statusLine = Component.translatable("screen.sailboatmod.town.status.synced");
        refresh();
        flushQueuedPreviewRefresh();
    }


    private void tickAutoRefresh() {
        if (!this.data.hasTown()) {
            this.autoRefreshTicks = 0;
            return;
        }
        if (shouldRetryPendingPreviewRequest()) {
            this.autoRefreshTicks++;
            if (this.autoRefreshTicks < PENDING_RETRY_INTERVAL_TICKS) {
                return;
            }
            this.autoRefreshTicks = 0;
            retryPendingPreviewRequest();
            return;
        }
        if (this.refreshPending) {
            this.autoRefreshTicks = 0;
            return;
        }
        int interval = AUTO_REFRESH_INTERVAL_TICKS;
        this.autoRefreshTicks++;
        if (this.autoRefreshTicks < interval) {
            return;
        }
        this.autoRefreshTicks = 0;
        requestRefresh();
    }

    private List<Component> buildOverviewLines() {
        List<Component> lines = new ArrayList<>();
        if (!this.data.hasTown()) {
            lines.add(Component.translatable("screen.sailboatmod.town.overview.none"));
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.town.overview.name", this.data.townName()));
        lines.add(Component.translatable("screen.sailboatmod.town.overview.nation", this.data.nationName().isBlank() ? "-" : this.data.nationName()));
        lines.add(Component.translatable("screen.sailboatmod.town.overview.mayor", this.data.mayorName()));
        if (this.data.externalColony().present()) {
            lines.add(Component.translatable(
                    "screen.sailboatmod.town.overview.minecolonies",
                    this.data.externalColony().colonyName().isBlank() ? "#" + this.data.externalColony().colonyId() : this.data.externalColony().colonyName(),
                    this.data.externalColony().population(),
                    this.data.externalColony().maxPopulation()));
        }
        lines.add(Component.translatable("screen.sailboatmod.town.overview.culture", cultureDisplayName(this.data.cultureId())));
        lines.add(this.data.hasCore() ? Component.translatable("screen.sailboatmod.town.overview.core", formatCoreLocation()) : Component.translatable("screen.sailboatmod.town.overview.core_missing"));
        return lines;
    }

    private List<Component> buildClaimLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.sailboatmod.town.claims.current_chunk", this.data.currentChunkX(), this.data.currentChunkZ()));
        if (mapCenterX() != this.data.currentChunkX() || mapCenterZ() != this.data.currentChunkZ()) {
            lines.add(Component.literal("View chunk: " + mapCenterX() + ", " + mapCenterZ()));
        }
        lines.add(Component.translatable("screen.sailboatmod.town.claims.selected_chunk", this.selectedClaimChunkX, this.selectedClaimChunkZ));
        if (!this.data.hasTown()) {
            lines.add(Component.translatable("screen.sailboatmod.town.claims.no_town"));
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.town.claims.total", this.data.totalClaims()));
        lines.add(Component.translatable("screen.sailboatmod.town.claims.cost", TownClaimService.batchClaimCost()));
        NationOverviewClaim selected = firstSelectedAreaClaim();
        if (selected == null) {
            lines.add(Component.translatable("screen.sailboatmod.town.claims.unclaimed_selected"));
            lines.add(Component.translatable("screen.sailboatmod.town.claims.selection_hint"));
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.town.claims.owner", selected.nationName()));
        return lines;
    }

    private List<Component> buildFlagLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(this.data.flagId().isBlank() ? Component.translatable("screen.sailboatmod.town.flag.placeholder") : Component.translatable("screen.sailboatmod.town.flag.meta", this.data.flagWidth(), this.data.flagHeight(), this.data.flagByteSize()));
        lines.add(Component.translatable("screen.sailboatmod.town.flag.id", this.data.flagId().isBlank() ? "-" : shortText(this.data.flagId(), 22)));
        lines.add(Component.translatable(this.data.flagMirrored() ? "screen.sailboatmod.nation.flag.mirror.on" : "screen.sailboatmod.nation.flag.mirror.off"));
        return lines;
    }

    static boolean shouldShowClaimMapProgress(ClaimPreviewMapState mapState) {
        ClaimPreviewMapState safeMapState = mapState == null ? ClaimPreviewMapState.empty() : mapState;
        return safeMapState.loading() || safeMapState.hasPendingProgress();
    }

    static int claimMapProgressWidthForTest(ClaimPreviewMapState mapState, int mapWidth, boolean visibleLayer) {
        return claimMapProgressWidth(mapState, mapWidth, visibleLayer);
    }

    private static int claimMapProgressWidth(ClaimPreviewMapState mapState, int mapWidth, boolean visibleLayer) {
        ClaimPreviewMapState safeMapState = mapState == null ? ClaimPreviewMapState.empty() : mapState;
        int safeMapWidth = Math.max(0, mapWidth);
        int ready = visibleLayer ? safeMapState.visibleReadyChunkCount() : safeMapState.prefetchReadyChunkCount();
        int total = visibleLayer ? safeMapState.visibleChunkCount() : safeMapState.prefetchChunkCount();
        if (total <= 0 || safeMapWidth <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(safeMapWidth, Math.round((ready / (float) total) * safeMapWidth)));
    }

    private void requestRefresh() {
        requestRefresh(mapCenterX(), mapCenterZ(), true);
    }

    private void requestRefresh(int centerChunkX, int centerChunkZ) {
        requestRefresh(centerChunkX, centerChunkZ, false);
    }

    private void requestRefresh(int centerChunkX, int centerChunkZ, boolean explicitRefresh) {
        if (this.refreshPending) {
            traceClaim("requestRefresh queued center=" + centerChunkX + "," + centerChunkZ);
            this.queuedPreviewCenterX = centerChunkX;
            this.queuedPreviewCenterZ = centerChunkZ;
            return;
        }
        traceClaim("requestRefresh send center=" + centerChunkX + "," + centerChunkZ
                + " previewCenter=" + this.data.previewCenterChunkX() + "," + this.data.previewCenterChunkZ()
                + " mapCenter=" + mapCenterX() + "," + mapCenterZ()
                + " terrainCount=" + this.data.nearbyTerrainColors().size());
        this.refreshPending = true;
        this.pendingPreviewCenterX = centerChunkX;
        this.pendingPreviewCenterZ = centerChunkZ;
        long revision = TownClientHooks.beginClaimPreviewRequest(centerChunkX, centerChunkZ, viewportRadius());
        this.pendingPreviewRevision = revision;
        sendViewportRequest(centerChunkX, centerChunkZ, revision, explicitRefresh);
        this.statusLine = Component.translatable("screen.sailboatmod.town.status.refreshing");
    }

    private void retryPendingPreviewRequest() {
        if (this.pendingPreviewRevision == Long.MIN_VALUE
                || this.pendingPreviewCenterX == Integer.MIN_VALUE
                || this.pendingPreviewCenterZ == Integer.MIN_VALUE) {
            return;
        }
        traceClaim("retryPendingPreviewRequest center=" + this.pendingPreviewCenterX + "," + this.pendingPreviewCenterZ
                + " revision=" + this.pendingPreviewRevision);
        sendViewportRequest(this.pendingPreviewCenterX, this.pendingPreviewCenterZ, this.pendingPreviewRevision, false);
    }

    private void sendViewportRequest(int centerChunkX, int centerChunkZ, long revision, boolean explicitRefresh) {
        if (explicitRefresh) {
            ModNetwork.CHANNEL.sendToServer(new RefreshClaimMapViewportPacket(
                    RequestClaimMapViewportPacket.ScreenKind.TOWN,
                    this.data.townId(),
                    currentDimensionId(),
                    revision,
                    viewportRadius(),
                    centerChunkX,
                    centerChunkZ
            ));
        } else {
            ModNetwork.CHANNEL.sendToServer(new RequestClaimMapViewportPacket(
                    RequestClaimMapViewportPacket.ScreenKind.TOWN,
                    this.data.townId(),
                    currentDimensionId(),
                    revision,
                    viewportRadius(),
                    centerChunkX,
                    centerChunkZ,
                    VIEWPORT_PREFETCH_RADIUS
            ));
        }
    }

    private boolean shouldRetryPendingPreviewRequest() {
        return shouldRetryPendingPreviewRequest(
                this.refreshPending,
                this.pendingPreviewRevision,
                this.pendingPreviewCenterX,
                this.pendingPreviewCenterZ,
                this.data.claimMapState()
        );
    }

    static boolean shouldRetryPendingPreviewRequest(boolean refreshPending,
                                                    long pendingPreviewRevision,
                                                    int pendingPreviewCenterX,
                                                    int pendingPreviewCenterZ,
                                                    ClaimPreviewMapState mapState) {
        if (!refreshPending
                || pendingPreviewRevision == Long.MIN_VALUE
                || pendingPreviewCenterX == Integer.MIN_VALUE
                || pendingPreviewCenterZ == Integer.MIN_VALUE) {
            return false;
        }
        ClaimPreviewMapState safeMapState = mapState == null ? ClaimPreviewMapState.empty() : mapState;
        return safeMapState.loading() && safeMapState.revision() == pendingPreviewRevision;
    }

    static boolean shouldRequestRefreshAfterMapDragRelease(int button, boolean isDraggingMap) {
        return button == 2 && isDraggingMap;
    }

    static boolean shouldFlushQueuedPreviewRefresh(boolean refreshPending,
                                                   int queuedPreviewCenterX,
                                                   int queuedPreviewCenterZ,
                                                   int previewCenterChunkX,
                                                   int previewCenterChunkZ) {
        if (refreshPending
                || queuedPreviewCenterX == Integer.MIN_VALUE
                || queuedPreviewCenterZ == Integer.MIN_VALUE) {
            return false;
        }
        return queuedPreviewCenterX != previewCenterChunkX || queuedPreviewCenterZ != previewCenterChunkZ;
    }

    private static boolean shouldClearClaimOverlayCache(TownOverviewData previousData, TownOverviewData nextData) {
        if (previousData == null || nextData == null) {
            return false;
        }
        return shouldClearClaimOverlayCacheForTest(
                previousData.townId(),
                nextData.townId(),
                previousData.nationId(),
                nextData.nationId(),
                previousData.primaryColorRgb(),
                nextData.primaryColorRgb(),
                previousData.secondaryColorRgb(),
                nextData.secondaryColorRgb()
        );
    }

    static boolean shouldClearClaimOverlayCacheForTest(String previousTownId,
                                                       String nextTownId,
                                                       String previousNationId,
                                                       String nextNationId,
                                                       int previousPrimaryColor,
                                                       int nextPrimaryColor,
                                                       int previousSecondaryColor,
                                                       int nextSecondaryColor) {
        return !normalizedId(previousTownId).equals(normalizedId(nextTownId))
                || !normalizedId(previousNationId).equals(normalizedId(nextNationId))
                || previousPrimaryColor != nextPrimaryColor
                || previousSecondaryColor != nextSecondaryColor;
    }

    private static String normalizedId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static boolean shouldPreserveClaimMapVisibleCenter(String previousOwnerId,
                                                       String nextOwnerId,
                                                       int mapOffsetX,
                                                       int mapOffsetZ,
                                                       boolean refreshPending,
                                                       boolean resetPending) {
        if (resetPending) {
            return false;
        }
        String previous = previousOwnerId == null ? "" : previousOwnerId.trim();
        String next = nextOwnerId == null ? "" : nextOwnerId.trim();
        if (previous.isBlank() || !previous.equals(next)) {
            return false;
        }
        return refreshPending || mapOffsetX != 0 || mapOffsetZ != 0;
    }

    private int viewportRadius() {
        int configured = this.data.claimMapState().radius();
        return configured > 0 ? configured : claimRadius();
    }

    private String currentDimensionId() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            return minecraft.level.dimension().location().toString();
        }
        if (this.data.hasCore() && this.data.coreDimension() != null && !this.data.coreDimension().isBlank()) {
            return this.data.coreDimension();
        }
        return "minecraft:overworld";
    }

    private void flushQueuedPreviewRefresh() {
        int queuedX = this.queuedPreviewCenterX;
        int queuedZ = this.queuedPreviewCenterZ;
        this.queuedPreviewCenterX = Integer.MIN_VALUE;
        this.queuedPreviewCenterZ = Integer.MIN_VALUE;
        if (!shouldFlushQueuedPreviewRefresh(
                this.refreshPending,
                queuedX,
                queuedZ,
                this.data.previewCenterChunkX(),
                this.data.previewCenterChunkZ()
        )) {
            return;
        }
        requestRefresh(queuedX, queuedZ);
    }

    private void resetMapOffset() {
        traceClaim("resetMapOffset hasCore=" + this.data.hasCore() + " currentChunk=" + this.data.currentChunkX() + "," + this.data.currentChunkZ());
        this.mapOffsetX = 0;
        this.mapOffsetZ = 0;
        syncClaimWorldMapViewToMapCenter(false);
        this.resetPending = true;
        if (this.data.hasCore()) {
            net.minecraft.core.BlockPos corePos = net.minecraft.core.BlockPos.of(this.data.corePos());
            requestRefresh(corePos.getX() >> 4, corePos.getZ() >> 4);
        } else {
            requestRefresh(this.data.currentChunkX(), this.data.currentChunkZ());
        }
    }

    @Override
    public void applyMapTileSync(RoadPlannerMapTileSyncPacket packet) {
        if (this.claimWorldMapView.applyTileSync(packet) > 0) {
            this.statusLine = Component.literal("地图: 已接收强制渲染切片");
        }
    }

    private void requestInitialVisibleClaimMapForceRender(ClaimMapViewport viewport) {
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        if (this.claimWorldMapView.markVisibleForceRenderRequested(viewport)) {
            requestVisibleClaimMapForceRender(viewport);
        }
    }

    private void requestVisibleClaimMapForceRender() {
        requestVisibleClaimMapForceRender(claimMapViewport());
    }

    private void requestVisibleClaimMapForceRender(ClaimMapViewport viewport) {
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        RoadPlannerMapPreloadRequestPacket packet = this.claimWorldMapView.createVisibleForceRenderRequest(
                this.claimWorldMapView.worldId(),
                currentDimensionId(),
                viewport
        );
        ModNetwork.CHANNEL.sendToServer(packet);
    }

    private void syncClaimWorldMapViewToMapCenter(boolean preserveScale) {
        this.claimWorldMapView.centerOnChunk(mapCenterX(), mapCenterZ(), claimRadius(), CLAIM_MAP_W, CLAIM_MAP_H, preserveScale);
    }

    private void syncMapOffsetFromClaimWorldMap() {
        this.mapOffsetX = this.claimWorldMapView.centerChunkX() - this.data.previewCenterChunkX();
        this.mapOffsetZ = this.claimWorldMapView.centerChunkZ() - this.data.previewCenterChunkZ();
    }

    private void maybeRequestPreviewRefresh() {
        int centerChunkX = mapCenterX();
        int centerChunkZ = mapCenterZ();
        boolean farFromPreview = Math.abs(centerChunkX - this.data.previewCenterChunkX()) >= 1
                || Math.abs(centerChunkZ - this.data.previewCenterChunkZ()) >= 1;
        if (!farFromPreview) return;
        if (centerChunkX == this.pendingPreviewCenterX && centerChunkZ == this.pendingPreviewCenterZ) return;
        requestRefresh(centerChunkX, centerChunkZ);
    }

    private void ensureClaimPreviewVisible() {
        if (this.refreshPending) {
            traceClaim("ensureClaimPreviewVisible skipped refreshPending=true");
            return;
        }
        int centerChunkX = mapCenterX();
        int centerChunkZ = mapCenterZ();
        boolean offCenter = centerChunkX != this.data.previewCenterChunkX() || centerChunkZ != this.data.previewCenterChunkZ();
        traceClaim("ensureClaimPreviewVisible center=" + centerChunkX + "," + centerChunkZ
                + " previewCenter=" + this.data.previewCenterChunkX() + "," + this.data.previewCenterChunkZ()
                + " terrainCount=" + this.data.nearbyTerrainColors().size()
                + " offCenter=" + offCenter);
        if (offCenter) {
            requestRefresh(centerChunkX, centerChunkZ);
        }
    }

    private void rememberClaimRadius(TownOverviewData snapshot) {
        int radius = resolvedRadiusForSnapshot(snapshot);
        if (radius >= 0) {
            this.lastKnownClaimRadius = radius;
        }
    }

    private int resolvedRadiusForSnapshot(TownOverviewData snapshot) {
        if (snapshot == null) {
            return -1;
        }
        int stateRadius = snapshot.claimMapState().radius();
        if (stateRadius > 0) {
            return stateRadius;
        }
        Integer inferredRadius = inferClaimRadiusFromTerrainColors(snapshot.nearbyTerrainColors().size());
        return inferredRadius == null ? -1 : inferredRadius;
    }

    private Integer inferClaimRadiusFromTerrainColors(int colorCount) {
        if (colorCount <= 0) {
            return null;
        }
        int sub = com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService.SUB;
        int colorsPerChunk = sub * sub;
        if (colorsPerChunk <= 0 || colorCount % colorsPerChunk != 0) {
            return null;
        }
        int chunkCount = colorCount / colorsPerChunk;
        if (chunkCount <= 0) {
            return null;
        }
        int diameter = (int) Math.round(Math.sqrt(chunkCount));
        if (diameter <= 0 || diameter * diameter != chunkCount || (diameter & 1) == 0) {
            return null;
        }
        return (diameter - 1) / 2;
    }

    private int configuredClaimRadiusOrZero() {
        try {
            return Math.max(0, com.monpai.sailboatmod.ModConfig.claimPreviewRadius());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private void traceClaim(String message) {
        if (!CLAIM_TRACE_ENABLED) {
            return;
        }
        LOGGER.info("[TownClaimPreview] {}", message);
    }

    private void submitRenameTown() {
        if (!this.data.hasTown() || !this.data.canManageTown()) return;
        String townName = valueOf(this.townNameInput).trim();
        if (townName.isBlank()) {
            this.statusLine = Component.translatable("screen.sailboatmod.town.name_required");
            return;
        }
        if (!townNameChanged()) {
            this.statusLine = Component.translatable("screen.sailboatmod.town.info.unchanged");
            return;
        }
        sendTownAction(new TownGuiActionPacket(TownGuiActionPacket.Action.RENAME_TOWN, this.data.townId(), townName), Component.translatable("screen.sailboatmod.town.info.updating", townName));
    }

    private void appointSelectedMayor() {
        NationOverviewMember selected = selectedMember();
        if (selected == null || !this.data.canAssignMayor()) return;
        sendTownAction(new TownGuiActionPacket(TownGuiActionPacket.Action.APPOINT_MAYOR, this.data.townId(), selected.playerUuid()), Component.translatable("screen.sailboatmod.town.members.action_assigning_mayor", selected.playerName(), this.data.townName()));
    }

    private void claimSelectedChunk() {
        if (hasAreaSelection()) {
            int x1 = this.areaCorner1X;
            int z1 = this.areaCorner1Z;
            int x2 = this.areaCorner2X;
            int z2 = this.areaCorner2Z;
            int count = (Math.abs(x2 - x1) + 1) * (Math.abs(z2 - z1) + 1);
            ModNetwork.CHANNEL.sendToServer(new TownGuiActionPacket(TownGuiActionPacket.Action.CLAIM_AREA, this.data.townId(), x1, z1, x2 + "," + z2));
            this.statusLine = Component.translatable("screen.sailboatmod.nation.claims.action_batch_claiming", count);
            return;
        }
        sendTownAction(new TownGuiActionPacket(TownGuiActionPacket.Action.CLAIM_CHUNK, this.data.townId(), this.selectedClaimChunkX, this.selectedClaimChunkZ), Component.translatable("screen.sailboatmod.town.claims.action_claiming", this.selectedClaimChunkX, this.selectedClaimChunkZ));
    }

    private void unclaimSelectedChunk() {
        if (hasAreaSelection()) {
            int x1 = this.areaCorner1X;
            int z1 = this.areaCorner1Z;
            int x2 = this.areaCorner2X;
            int z2 = this.areaCorner2Z;
            int count = (Math.abs(x2 - x1) + 1) * (Math.abs(z2 - z1) + 1);
            ModNetwork.CHANNEL.sendToServer(new TownGuiActionPacket(TownGuiActionPacket.Action.UNCLAIM_AREA, this.data.townId(), x1, z1, x2 + "," + z2));
            this.statusLine = Component.translatable("screen.sailboatmod.nation.claims.action_batch_unclaiming", count);
            return;
        }
        sendTownAction(new TownGuiActionPacket(TownGuiActionPacket.Action.UNCLAIM_CHUNK, this.data.townId(), this.selectedClaimChunkX, this.selectedClaimChunkZ), Component.translatable("screen.sailboatmod.town.claims.action_unclaiming", this.selectedClaimChunkX, this.selectedClaimChunkZ));
    }

    private boolean hasAreaSelection() { return this.areaCorner1X != Integer.MIN_VALUE && this.areaCorner2X != Integer.MIN_VALUE; }

    private void submitUpload() {
        this.statusLine = TownFlagUploadClient.uploadFromPath(this.data.townId(), valueOf(this.flagPathInput));
    }

    private void submitMirrorToggle() {
        if (!this.data.hasTown() || !this.data.canUploadFlag() || this.data.flagId().isBlank()) return;
        sendTownAction(new TownGuiActionPacket(TownGuiActionPacket.Action.TOGGLE_FLAG_MIRROR, this.data.townId()), Component.translatable("screen.sailboatmod.nation.flag.mirror.toggling"));
    }

    private void submitAbandonTown() {
        if (!this.data.hasTown() || !this.data.isMayor()) return;
        sendTownAction(new TownGuiActionPacket(TownGuiActionPacket.Action.ABANDON_TOWN, this.data.townId()), Component.translatable("screen.sailboatmod.town.abandon.submitting", this.data.townName()));
        this.onClose();
    }

    private void submitRemoveCore() {
        if (!this.data.hasTown() || !this.data.isMayor() || !this.data.hasCore()) return;
        sendTownAction(new TownGuiActionPacket(TownGuiActionPacket.Action.REMOVE_CORE, this.data.townId()), Component.translatable("screen.sailboatmod.town.core.removing"));
    }

    private void cycleClaimPermission(String actionId, String currentLevelId) {
        NationClaimAccessLevel next = nextAccessLevel(currentLevelId);
        if (hasAreaSelection()) {
            int minX = Math.min(this.areaCorner1X, this.areaCorner2X);
            int maxX = Math.max(this.areaCorner1X, this.areaCorner2X);
            int minZ = Math.min(this.areaCorner1Z, this.areaCorner2Z);
            int maxZ = Math.max(this.areaCorner1Z, this.areaCorner2Z);
            for (int az = minZ; az <= maxZ; az++) {
                for (int ax = minX; ax <= maxX; ax++) {
                    ModNetwork.CHANNEL.sendToServer(new SetTownClaimPermissionPacket(this.data.townId(), ax, az, actionId, next.id()));
                }
            }
            int count = (maxX - minX + 1) * (maxZ - minZ + 1);
            this.statusLine = Component.translatable("screen.sailboatmod.nation.claims.updating_batch", count, actionName(actionId), accessName(next.id()));
        } else {
            ModNetwork.CHANNEL.sendToServer(new SetTownClaimPermissionPacket(this.data.townId(), this.selectedClaimChunkX, this.selectedClaimChunkZ, actionId, next.id()));
            this.statusLine = Component.translatable("screen.sailboatmod.nation.claims.updating", actionName(actionId), accessName(next.id()));
        }
    }

    private void sendTownAction(TownGuiActionPacket packet, Component status) {
        ModNetwork.CHANNEL.sendToServer(packet);
        this.statusLine = status == null ? Component.empty() : status;
    }

    private void browseForImage() {
        this.statusLine = Component.translatable("screen.sailboatmod.nation.upload.dialog_unavailable");
    }

    private NationClaimAccessLevel nextAccessLevel(String id) {
        NationClaimAccessLevel current = NationClaimAccessLevel.fromId(id);
        if (current == null) current = NationClaimAccessLevel.MEMBER;
        return switch (current) {
            case MEMBER -> NationClaimAccessLevel.OFFICER;
            case OFFICER -> NationClaimAccessLevel.LEADER;
            case LEADER -> NationClaimAccessLevel.ALLY;
            case ALLY -> NationClaimAccessLevel.NEUTRAL;
            case NEUTRAL -> NationClaimAccessLevel.ANYONE;
            case ANYONE -> NationClaimAccessLevel.MEMBER;
        };
    }

    private boolean submitAndTrue(Runnable runnable) {
        runnable.run();
        return true;
    }

    private void syncSelections() {
        syncJoinNationSelection();
        if (this.selectedClaimChunkX == Integer.MIN_VALUE || Math.abs(this.selectedClaimChunkX - mapCenterX()) > claimRadius() || Math.abs(this.selectedClaimChunkZ - mapCenterZ()) > claimRadius()) {
            this.selectedClaimChunkX = mapCenterX();
            this.selectedClaimChunkZ = mapCenterZ();
        }
        if (this.data.members().isEmpty()) {
            this.selectedMemberUuid = "";
            this.memberScroll = 0;
            return;
        }
        if (this.selectedMemberUuid.isBlank()) this.selectedMemberUuid = this.data.members().get(0).playerUuid();
        for (NationOverviewMember member : this.data.members()) if (member.playerUuid().equals(this.selectedMemberUuid)) return;
        this.selectedMemberUuid = this.data.members().get(0).playerUuid();
    }

    private int mapCenterX() { return this.data.previewCenterChunkX() + this.mapOffsetX; }
    private int mapCenterZ() { return this.data.previewCenterChunkZ() + this.mapOffsetZ; }
    private NationOverviewMember selectedMember() { for (NationOverviewMember member : this.data.members()) if (member.playerUuid().equals(this.selectedMemberUuid)) return member; return null; }
    private NationOverviewClaim selectedClaim() { return findClaim(this.selectedClaimChunkX, this.selectedClaimChunkZ); }
    private boolean selectedClaimAreaHasAnyClaim() { return firstSelectedAreaClaim() != null; }
    private boolean selectedClaimAreaOwnedByTown() {
        NationOverviewClaim claim = selectedClaim();
        return claim != null && this.data.townId().equals(claimOwnerKey(claim));
    }
    private NationOverviewClaim firstSelectedAreaClaim() {
        return selectedClaim();
    }
    private List<NationOverviewClaim> selectedAreaClaims() {
        NationOverviewClaim claim = selectedClaim();
        return claim == null ? List.of() : List.of(claim);
    }

    private void cacheNearbyClaims() {
        for (NationOverviewClaim claim : this.data.nearbyClaims()) {
            this.cachedClaimOverlays.put(claimKey(claim.chunkX(), claim.chunkZ()), claim);
        }
    }

    private void syncJoinNationSelection() {
        if (!shouldShowJoinNationButton()) {
            this.joinNationOverlayOpen = false;
            this.joinNationScroll = 0;
            this.selectedJoinNationIndex = 0;
            return;
        }
        this.selectedJoinNationIndex = Math.max(0, Math.min(this.selectedJoinNationIndex, Math.max(0, joinableNationTargets().size() - 1)));
        this.joinNationScroll = clampJoinNationScroll(this.joinNationScroll);
    }

    private boolean shouldShowJoinNationButton() {
        return this.currentPage == Page.OVERVIEW
                && this.data.hasTown()
                && this.data.canManageTown()
                && this.data.nationId().isBlank()
                && !joinableNationTargets().isEmpty();
    }

    private List<TownOverviewData.JoinableNationTarget> joinableNationTargets() {
        return this.data.joinableNationTargets();
    }

    private void openJoinNationOverlay() {
        if (!shouldShowJoinNationButton()) {
            return;
        }
        this.joinNationOverlayOpen = true;
        syncJoinNationSelection();
        refresh();
    }

    private void closeJoinNationOverlay() {
        this.joinNationOverlayOpen = false;
        refresh();
    }

    private void moveJoinNationSelection(int delta) {
        if (joinableNationTargets().isEmpty()) {
            return;
        }
        this.selectedJoinNationIndex = Math.max(0, Math.min(this.selectedJoinNationIndex + delta, joinableNationTargets().size() - 1));
        this.joinNationScroll = clampJoinNationScroll(this.selectedJoinNationIndex);
        refresh();
    }

    private void submitJoinNationSelection() {
        TownOverviewData.JoinableNationTarget target = selectedJoinNationTarget();
        if (target == null) {
            this.statusLine = Component.translatable("screen.sailboatmod.town.join_nation.none");
            return;
        }
        sendTownAction(
                new TownGuiActionPacket(TownGuiActionPacket.Action.JOIN_NATION, this.data.townId(), target.nationName()),
                Component.translatable("screen.sailboatmod.town.join_nation.submitting", target.nationName())
        );
        this.joinNationOverlayOpen = false;
        refresh();
    }

    private TownOverviewData.JoinableNationTarget selectedJoinNationTarget() {
        if (joinableNationTargets().isEmpty() || this.selectedJoinNationIndex < 0 || this.selectedJoinNationIndex >= joinableNationTargets().size()) {
            return null;
        }
        return joinableNationTargets().get(this.selectedJoinNationIndex);
    }

    private boolean trySelectJoinNation(double mouseX, double mouseY) {
        int[] list = joinNationListBounds(left(), top());
        int listX = list[0];
        int listY = list[1];
        int listW = list[2];
        int rowH = JOIN_NATION_ROW_H;
        int visibleRows = JOIN_NATION_VISIBLE_ROWS;
        if (!isInside(listX, listY, listW, list[3], mouseX, mouseY) || joinableNationTargets().isEmpty()) {
            return false;
        }
        int row = (int) ((mouseY - listY - 2) / rowH);
        if (row < 0 || row >= visibleRows) {
            return false;
        }
        int index = this.joinNationScroll + row;
        if (index < 0 || index >= joinableNationTargets().size()) {
            return false;
        }
        this.selectedJoinNationIndex = index;
        refresh();
        return true;
    }

    private boolean isInsideJoinNationList(double mouseX, double mouseY) {
        int[] list = joinNationListBounds(left(), top());
        return isInside(list[0], list[1], list[2], list[3], mouseX, mouseY);
    }

    private boolean isInside(int x, int y, int width, int height, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private int clampJoinNationScroll(int value) {
        int maxScroll = Math.max(0, joinableNationTargets().size() - JOIN_NATION_VISIBLE_ROWS);
        return Math.max(0, Math.min(value, maxScroll));
    }

    private static int[] joinNationOverlayBounds(int screenLeft, int screenTop) {
        return new int[] {
                screenLeft + BODY_X + JOIN_NATION_OVERLAY_LOCAL_X,
                screenTop + BODY_Y + JOIN_NATION_OVERLAY_LOCAL_Y,
                JOIN_NATION_OVERLAY_W,
                JOIN_NATION_OVERLAY_H
        };
    }

    private static int[] joinNationListBounds(int screenLeft, int screenTop) {
        int[] overlay = joinNationOverlayBounds(screenLeft, screenTop);
        return new int[] {
                overlay[0] + 10,
                overlay[1] + 28,
                overlay[2] - 20,
                JOIN_NATION_ROW_H * JOIN_NATION_VISIBLE_ROWS + 4
        };
    }

    static int[] joinNationOverlayBoundsForTest(int screenLeft, int screenTop) {
        return joinNationOverlayBounds(screenLeft, screenTop);
    }

    static int[] joinNationListBoundsForTest(int screenLeft, int screenTop) {
        return joinNationListBounds(screenLeft, screenTop);
    }

    boolean shouldShowJoinNationButtonForTest() {
        return shouldShowJoinNationButton();
    }

    void openJoinNationOverlayForTest() {
        openJoinNationOverlay();
    }

    void selectJoinNationIndexForTest(int index) {
        this.selectedJoinNationIndex = Math.max(0, Math.min(index, Math.max(0, joinableNationTargets().size() - 1)));
        this.joinNationScroll = clampJoinNationScroll(this.selectedJoinNationIndex);
    }

    String selectedJoinNationIdForTest() {
        TownOverviewData.JoinableNationTarget target = selectedJoinNationTarget();
        return target == null ? "" : target.nationId();
    }

    boolean joinNationOverlayOpenForTest() {
        return this.joinNationOverlayOpen;
    }

    void submitJoinNationSelectionForTest() {
        TownOverviewData.JoinableNationTarget target = selectedJoinNationTarget();
        if (target == null) {
            this.statusLine = Component.translatable("screen.sailboatmod.town.join_nation.none");
            return;
        }
        this.statusLine = Component.translatable("screen.sailboatmod.town.join_nation.submitting", target.nationName());
        this.joinNationOverlayOpen = false;
    }


    private static long claimKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private NationOverviewClaim findClaim(int x, int z) {
        for (NationOverviewClaim claim : this.data.nearbyClaims()) {
            if (claim.chunkX() == x && claim.chunkZ() == z) {
                this.cachedClaimOverlays.put(claimKey(x, z), claim);
                return claim;
            }
        }
        return this.cachedClaimOverlays.get(claimKey(x, z));
    }
    private boolean sameOwner(String ownerId, int x, int z) { NationOverviewClaim c = findClaim(x, z); return c != null && ownerId.equals(claimOwnerKey(c)); }
    private String claimOwnerKey(NationOverviewClaim claim) { return claim == null ? "" : (claim.townId().isBlank() ? claim.nationId() : claim.townId()); }
    private boolean canAssignSelectedMemberAsMayor() { NationOverviewMember selected = selectedMember(); return this.data.canAssignMayor() && selected != null && !selected.playerUuid().equals(this.data.mayorUuid()); }
    private boolean townNameChanged() { return !valueOf(this.townNameInput).trim().equals(this.data.townName()); }
    private String valueOf(TextField box) { return box == null ? "" : box.getValue(); }
    private String selectedBreakAccessLevel() { NationOverviewClaim claim = firstSelectedAreaClaim(); return claim == null ? "" : claim.breakAccessLevel(); }
    private String selectedPlaceAccessLevel() { NationOverviewClaim claim = firstSelectedAreaClaim(); return claim == null ? "" : claim.placeAccessLevel(); }
    private String selectedUseAccessLevel() { NationOverviewClaim claim = firstSelectedAreaClaim(); return claim == null ? "" : claim.useAccessLevel(); }
    private String selectedContainerAccessLevel() { NationOverviewClaim claim = firstSelectedAreaClaim(); return claim == null ? "" : claim.containerAccessLevel(); }
    private String selectedRedstoneAccessLevel() { NationOverviewClaim claim = firstSelectedAreaClaim(); return claim == null ? "" : claim.redstoneAccessLevel(); }
    private String selectedEntityUseAccessLevel() { NationOverviewClaim claim = firstSelectedAreaClaim(); return claim == null ? "" : claim.entityUseAccessLevel(); }
    private String selectedEntityDamageAccessLevel() { NationOverviewClaim claim = firstSelectedAreaClaim(); return claim == null ? "" : claim.entityDamageAccessLevel(); }
    private int clampMemberScroll(int value) { return Math.max(0, Math.min(value, Math.max(0, this.data.members().size() - MEMBER_VISIBLE_ROWS))); }
    private int previewFlagWidth() { int width = this.data.flagWidth() <= 0 ? 96 : this.data.flagWidth(); int height = this.data.flagHeight() <= 0 ? 48 : this.data.flagHeight(); double scale = Math.min(140.0D / width, 96.0D / height); return Math.max(1, (int) Math.round(width * scale)); }
    private int previewFlagHeight() { int width = this.data.flagWidth() <= 0 ? 96 : this.data.flagWidth(); int height = this.data.flagHeight() <= 0 ? 48 : this.data.flagHeight(); double scale = Math.min(140.0D / width, 96.0D / height); return Math.max(1, (int) Math.round(height * scale)); }
    private String formatCoreLocation() { if (!this.data.hasCore()) return "-"; BlockPos pos = BlockPos.of(this.data.corePos()); return shortText(this.data.coreDimension(), 24) + " @ " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ(); }
    private String accessName(String id) { return (id == null || id.isBlank()) ? Component.translatable("screen.sailboatmod.nation.access.unknown").getString() : Component.translatable("screen.sailboatmod.nation.access." + id.toLowerCase(Locale.ROOT)).getString(); }
    private String actionName(String id) { return (id == null || id.isBlank()) ? Component.translatable("screen.sailboatmod.nation.claims.action.unknown").getString() : Component.translatable("screen.sailboatmod.nation.claims.action." + id.toLowerCase(Locale.ROOT)).getString(); }
    private String shortText(String value, int maxLength) { if (value == null || value.isBlank()) return "-"; return value.length() <= maxLength ? value : value.substring(0, Math.max(0, maxLength)) + "..."; }
    private String trimToWidth(String src, int maxPixels) { if (src == null || src.isEmpty() || maxPixels <= 0 || this.font.width(src) <= maxPixels) return src == null ? "" : src; String ellipsis = "..."; int ew = this.font.width(ellipsis); int end = src.length(); while (end > 0 && this.font.width(src.substring(0, end)) + ew > maxPixels) end--; return src.substring(0, Math.max(0, end)) + ellipsis; }
    private String headerText() { return !this.data.hasTown() ? Component.translatable("screen.sailboatmod.town.header.none").getString() : Component.translatable("screen.sailboatmod.town.header.value", this.data.townName(), this.data.nationName().isBlank() ? "-" : this.data.nationName()).getString(); }
    private String buildEconomyHeaderLine() {
        if (!this.data.hasTown()) {
            return "";
        }
        return String.join("   ",
                Component.translatable("screen.sailboatmod.econbar.net", formatMoney(this.data.netBalance())).getString(),
                Component.translatable("screen.sailboatmod.econbar.income", formatMoney(this.data.totalIncome())).getString(),
                Component.translatable("screen.sailboatmod.econbar.jobs", formatPercent(this.data.employmentRate())).getString(),
                Component.translatable("screen.sailboatmod.econbar.literacy", formatPercent(this.data.averageLiteracy())).getString());
    }
    private Component firstLine(List<Component> lines) { return lines.size() > 0 ? lines.get(0) : Component.empty(); }
    private Component secondLine(List<Component> lines) { return lines.size() > 1 ? lines.get(1) : Component.empty(); }
    private Component thirdLine(List<Component> lines) { return lines.size() > 2 ? lines.get(2) : Component.empty(); }
    private int left() { return (this.width - SCREEN_W) / 2; }
    private int top() { return (this.height - SCREEN_H) / 2; }

    private List<java.util.Map.Entry<String, Integer>> buildSortedDistribution(java.util.Map<String, Integer> distribution) {
        if (distribution == null || distribution.isEmpty()) {
            return List.of();
        }
        return distribution.entrySet().stream()
                .sorted((a, b) -> Integer.compare(Math.max(0, b.getValue()), Math.max(0, a.getValue())))
                .toList();
    }
    private String cultureDisplayName(String id) { return overviewCategoryName(id); }
    private String dominantEducationName() {
        List<java.util.Map.Entry<String, Integer>> entries = buildSortedDistribution(this.data.educationLevelDistribution());
        return entries.isEmpty() ? Component.translatable("screen.sailboatmod.town.overview.chart_none").getString() : overviewCategoryName(entries.get(0).getKey());
    }
    private String overviewCategoryName(String id) {
        if (id == null || id.isBlank()) {
            return Component.translatable("screen.sailboatmod.town.overview.chart_none").getString();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        String key = "screen.sailboatmod.town.overview.category." + normalized;
        Component translated = Component.translatable(key);
        String resolved = translated.getString();
        if (!resolved.equals(key)) {
            return resolved;
        }
        String spaced = normalized.replace('_', ' ').replace('-', ' ');
        String[] parts = spaced.split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.isEmpty() ? id : builder.toString();
    }
    private List<String> previewLines(List<String> lines, Component... fallback) {
        if (lines != null && !lines.isEmpty()) {
            return lines;
        }
        List<String> generated = new ArrayList<>();
        if (fallback != null) {
            for (Component component : fallback) {
                if (component != null && !component.getString().isBlank()) {
                    generated.add(component.getString());
                }
            }
        }
        return generated.isEmpty() ? List.of(Component.translatable("screen.sailboatmod.market.empty").getString()) : generated;
    }

    private enum Page {
        OVERVIEW("screen.sailboatmod.town.section.overview"),
        MEMBERS("screen.sailboatmod.town.section.members"),
        CLAIMS("screen.sailboatmod.town.section.claims"),
        FLAG("screen.sailboatmod.town.section.flag"),
        ECONOMY("screen.sailboatmod.town.section.economy");

        private final String titleKey;

        Page(String titleKey) {
            this.titleKey = titleKey;
        }

        private Component title() {
            return Component.translatable(this.titleKey);
        }
    }
}
