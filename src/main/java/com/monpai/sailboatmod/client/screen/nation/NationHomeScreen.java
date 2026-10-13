package com.monpai.sailboatmod.client.screen.nation;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.client.NationClientHooks;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileSyncReceiver;
import com.monpai.sailboatmod.client.screen.ClaimMapViewport;
import com.monpai.sailboatmod.client.screen.ClaimWorldMapView;
import com.monpai.sailboatmod.client.texture.NationFlagTextureCache;
import com.monpai.sailboatmod.client.texture.NationFlagUploadClient;
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
import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import com.monpai.sailboatmod.nation.menu.NationOverviewDiplomacyEntry;
import com.monpai.sailboatmod.nation.menu.NationOverviewDiplomacyRequest;
import com.monpai.sailboatmod.nation.menu.NationOverviewMember;
import com.monpai.sailboatmod.nation.menu.NationOverviewNationEntry;
import com.monpai.sailboatmod.nation.menu.NationOverviewTown;
import com.monpai.sailboatmod.nation.model.NationClaimAccessLevel;
import com.monpai.sailboatmod.nation.model.NationOfficeIds;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.service.NationClaimService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.NationGuiActionPacket;
import com.monpai.sailboatmod.network.packet.OpenTownMenuPacket;
import com.monpai.sailboatmod.network.packet.RefreshClaimMapViewportPacket;
import com.monpai.sailboatmod.network.packet.RequestClaimMapViewportPacket;
import com.monpai.sailboatmod.network.packet.SetClaimPermissionPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
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
 * Nation home: overview, members, territory, war, diplomacy, treasury and flag, on a left page rail.
 *
 * <p>The element tree is built once; {@link #refresh()} re-reads {@link NationOverviewData} into it, so typed names,
 * selections and the map position survive the periodic refresh. Every action sends the same
 * {@link NationGuiActionPacket} / {@link SetClaimPermissionPacket} / chat command as the previous screen, and the
 * server keeps checking ranks and permissions. Removing the core, declaring war and releasing territory need a second
 * press. The territory map keeps the previous viewport protocol (request, retry, queued centre, prefetch and forced
 * tile render) unchanged; only its drawing moved into {@link ClaimMapElement}.</p>
 */
public class NationHomeScreen extends ThemedScreen implements RoadPlannerTileSyncReceiver {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean CLAIM_TRACE_ENABLED = true;
    private static final int AUTO_REFRESH_INTERVAL_TICKS = 40;
    private static final int PENDING_RETRY_INTERVAL_TICKS = 8;
    private static final int ACTIVE_WAR_REFRESH_INTERVAL_TICKS = 20;
    private static final int MEMBER_VISIBLE_ROWS = 12;
    private static final int CLAIM_MAP_W = 164;
    private static final int CLAIM_MAP_H = 164;
    private static final int VIEWPORT_PREFETCH_RADIUS = 2;
    private static final String CONFIRM_REMOVE_CORE = "remove-core";
    private static final String CONFIRM_DECLARE_WAR = "declare-war";
    private static final String CONFIRM_DIP_WAR = "dip-war";
    private static final String CONFIRM_UNCLAIM = "unclaim";
    private static final String[] PERMISSION_ACTIONS = {"break", "place", "use", "container", "redstone", "entity_use", "entity_damage"};

    private NationOverviewData data;
    private Page currentPage = Page.OVERVIEW;
    private Component statusLine = Component.empty();
    private int memberScroll;
    private int autoRefreshTicks;
    private String selectedMemberUuid = "";
    private String selectedTownId = "";
    private int selectedClaimChunkX = Integer.MIN_VALUE;
    private int selectedClaimChunkZ = Integer.MIN_VALUE;
    private int areaCorner1X = Integer.MIN_VALUE;
    private int areaCorner1Z = Integer.MIN_VALUE;
    private int areaCorner2X = Integer.MIN_VALUE;
    private int areaCorner2Z = Integer.MIN_VALUE;
    private int claimsSubPage;
    private String selectedDiplomacyNationId = "";
    private boolean showTreasuryCommands;
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

    // ---- UI (created in buildBody; null until the screen is first initialised)
    private boolean built;
    private TabBar<Page> tabs;
    private final Map<Page, UIElement> pages = new EnumMap<>(Page.class);
    private Label pageTitle;
    private Label statusLabel;
    private Label headerLabel;
    private Label economyLabel;

    // overview
    private UIElement overviewLines;
    private UIElement noNationForm;
    private UIElement nationForm;
    private TextField nationNameInput;
    private TextField createNameInput;
    private TextField shortNameInput;
    private TextField joinNationInput;
    private ActionButton saveNationInfoButton;
    private ActionButton createNationButton;
    private ActionButton joinNationButton;
    private Button previousTownButton;
    private Button nextTownButton;
    private ActionButton openCapitalTownButton;
    private ActionButton removeCoreButton;
    private Label townSelected;
    private Label townMayor;
    private Label townClaims;
    private Label townColony;

    // members
    private ScrollerView memberList;
    private Label memberCountLabel;
    private Label memberName;
    private Label memberRole;
    private Label memberStatus;
    private Label memberUuid;
    private TextField officerTitleInput;
    private ActionButton saveOfficerTitleButton;
    private ActionButton appointOfficerButton;
    private ActionButton removeOfficerButton;
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

    // war
    private UIElement warLines;
    private UIElement peacePanel;
    private UIElement peaceProposeRow;
    private UIElement peaceAnswerRow;
    private ActionButton warInfoButton;
    private TextField warTargetInput;
    private ActionButton declareWarButton;
    private Label warHint;

    // diplomacy
    private ScrollerView nationList;
    private UIElement diplomacyDetail;
    private UIElement diplomacyEmpty;
    private Label dipSelected;
    private Label dipStatus;
    private ActionButton dipAllyButton;
    private ActionButton dipTradeButton;
    private ActionButton dipEnemyButton;
    private ActionButton dipNeutralButton;
    private ActionButton dipDeclareWarButton;
    private ActionButton dipAcceptAllyButton;
    private ActionButton dipRejectAllyButton;
    private ActionButton dipOpenTradeButton;
    private UIElement dipRequests;
    private Object shownNations;
    private String shownNationSelection;

    // treasury
    private Label metricBalance;
    private Label metricTowns;
    private Label metricTrades;
    private Label metricItems;
    private MeterBar salesTaxBar;
    private MeterBar tariffBar;
    private Label salesTaxValue;
    private Label tariffValue;
    private final List<Button> taxButtons = new ArrayList<>();
    private ScrollerView treasuryItemList;
    private Label treasuryHint;
    private Button treasuryCommandsButton;
    private Object shownTreasuryItems;

    // flag
    private FlagPreview flagPreview;
    private Label flagLine1;
    private Label flagLine2;
    private Label flagLine3;
    private ActionButton toggleMirrorButton;
    private TextField primaryColorInput;
    private TextField secondaryColorInput;
    private UIElement primarySwatch;
    private UIElement secondarySwatch;
    private ActionButton applyColorsButton;
    private TextField flagPathInput;
    private Button browseButton;
    private ActionButton uploadButton;


    // =====================================================================================================
    // UI construction
    // =====================================================================================================

    @Override
    protected float windowWidth() {
        return 620;
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
                Ui.iconButton(new ItemStack(Items.COMPASS), Component.translatable("screen.sailboatmod.nation.action.refresh"), this::requestRefresh)};
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.row();
        body.layout(l -> l.alignItems(AlignItems.STRETCH).height(250).gapAll(Ui.GAP_M));
        tabs = TabBar.rail(this::switchPage);
        tabs.add(Page.OVERVIEW, new ItemStack(Items.WHITE_BANNER), Page.OVERVIEW.title())
                .add(Page.MEMBERS, new ItemStack(Items.PLAYER_HEAD), Page.MEMBERS.title())
                .add(Page.CLAIMS, new ItemStack(Items.FILLED_MAP), Page.CLAIMS.title())
                .add(Page.WAR, new ItemStack(Items.IRON_SWORD), Page.WAR.title())
                .add(Page.DIPLOMACY, new ItemStack(Items.WRITABLE_BOOK), Page.DIPLOMACY.title())
                .add(Page.TREASURY, new ItemStack(Items.GOLD_INGOT), Page.TREASURY.title())
                .add(Page.FLAG, new ItemStack(Items.PAINTING), Page.FLAG.title());
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
        pages.put(Page.WAR, page(buildWarPage()));
        pages.put(Page.DIPLOMACY, buildDiplomacyPage());
        pages.put(Page.TREASURY, page(buildTreasuryPage()));
        pages.put(Page.FLAG, page(buildFlagPage()));
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

    /** Wraps a page so it scrolls when the window is short (small GUI scale). */
    private static UIElement page(UIElement content) {
        ScrollerView scroller = Ui.scroller();
        content.layout(l -> l.widthPercent(100));
        scroller.addScrollViewChild(content);
        return scroller;
    }

    private UIElement buildOverviewPage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_S));
        overviewLines = Ui.column("sm-pane");
        overviewLines.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));

        // no nation: create / join
        noNationForm = Ui.column();
        noNationForm.layout(l -> l.gapAll(Ui.GAP_S));
        createNameInput = Ui.field("", Component.translatable("screen.sailboatmod.nation.name"), value -> refreshButtons());
        createNameInput.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(40));
        createNationButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.create"), Ui.Tone.PRIMARY, this::submitCreateNation);
        joinNationInput = Ui.field("", Component.translatable("screen.sailboatmod.nation.overview.join_input"), value -> refreshButtons());
        joinNationInput.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(40));
        joinNationButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.join"), Ui.Tone.SECONDARY, this::submitJoinNation);
        noNationForm.addChildren(
                Ui.muted(Component.translatable("screen.sailboatmod.nation.overview.create_label")),
                actionRow(createNameInput, createNationButton.inRow().element()),
                Ui.muted(Component.translatable("screen.sailboatmod.nation.overview.join_label")),
                actionRow(joinNationInput, joinNationButton.inRow().element()),
                row(Ui.paragraph(Component.translatable("screen.sailboatmod.nation.overview.help_hint"), "sm-muted"),
                        Ui.button(Component.translatable("screen.sailboatmod.nation.action.help"), Ui.Tone.SECONDARY, () -> runCommand("nation help"))));

        // nation: name, towns, core
        nationForm = Ui.column();
        nationForm.layout(l -> l.gapAll(Ui.GAP_S));
        nationNameInput = Ui.field("", Component.translatable("screen.sailboatmod.nation.name"), value -> refreshButtons());
        nationNameInput.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(40));
        shortNameInput = Ui.field("", Component.translatable("screen.sailboatmod.nation.short_name"), value -> refreshButtons());
        shortNameInput.layout(l -> l.width(80));
        saveNationInfoButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.save_info"), Ui.Tone.PRIMARY, this::submitNationInfoUpdate);
        UIElement nameRow = Ui.row();
        nameRow.layout(l -> l.widthPercent(100).minWidth(0).flexWrap(FlexWrap.WRAP).gapAll(Ui.GAP_S));
        nameRow.addChildren(nationNameInput, shortNameInput, saveNationInfoButton.inRow().element());

        UIElement towns = Ui.column("sm-pane");
        towns.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));
        previousTownButton = Ui.button(Component.literal("<"), Ui.Tone.SECONDARY, this::previousTownSelection);
        nextTownButton = Ui.button(Component.literal(">"), Ui.Tone.SECONDARY, this::nextTownSelection);
        townSelected = Ui.clippedText("", "sm-title");
        townSelected.layout(l -> l.width(0).flexGrow(1).minWidth(40));
        openCapitalTownButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.open_town"), Ui.Tone.SECONDARY, this::openCapitalTown);
        townMayor = Ui.clippedText("", "sm-muted");
        townClaims = Ui.clippedText("", "sm-muted");
        townColony = Ui.clippedText("", "sm-muted");
        towns.addChildren(actionRow(previousTownButton, townSelected, nextTownButton, openCapitalTownButton.inRow().element()), townMayor, townClaims, townColony);

        removeCoreButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.remove_core"), Ui.Tone.DANGER, () -> {
            if (confirm.press(CONFIRM_REMOVE_CORE, ticks())) {
                submitRemoveCore();
            }
            refresh();
        });
        nationForm.addChildren(
                row(Ui.muted(Component.translatable("screen.sailboatmod.nation.name")), Ui.spacer(),
                        Ui.muted(Component.translatable("screen.sailboatmod.nation.short_name"))),
                nameRow, towns, actionRow(Ui.spacer(), removeCoreButton.inRow().element()));
        page.addChildren(overviewLines, noNationForm, nationForm);
        return page;
    }

    private UIElement buildMembersPage() {
        UIElement page = Ui.row();
        page.layout(l -> l.gapAll(Ui.GAP_M).alignItems(AlignItems.STRETCH).flexGrow(1).minHeight(0));
        UIElement left = Ui.column();
        left.layout(l -> l.widthPercent(45).maxWidth(190).minHeight(0).flexShrink(0).gapAll(Ui.GAP_XS));
        memberCountLabel = Ui.clippedText("", "sm-muted");
        memberList = Ui.scroller();
        left.addChildren(memberCountLabel, memberList);

        UIElement right = Ui.column("sm-pane");
        right.layout(l -> l.widthPercent(100).minWidth(0).paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        memberName = Ui.clippedText("", "sm-title");
        memberRole = Ui.clippedText("");
        memberStatus = Ui.clippedText("");
        memberUuid = Ui.clippedText("", "sm-muted");
        officerTitleInput = Ui.field("", Component.translatable("screen.sailboatmod.nation.members.title_input"), value -> refreshButtons());
        officerTitleInput.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(40));
        saveOfficerTitleButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.save_title"), Ui.Tone.SECONDARY, this::submitOfficerTitleUpdate);
        appointOfficerButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.appoint_officer"), Ui.Tone.PRIMARY, this::appointSelectedMember);
        removeOfficerButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.remove_officer"), Ui.Tone.DANGER, this::removeSelectedOfficer);
        appointMayorButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.appoint_mayor"), Ui.Tone.SECONDARY, this::appointSelectedMayor);
        right.addChildren(Ui.muted(Component.translatable("screen.sailboatmod.nation.members.selected")),
                memberName, memberRole, memberStatus, memberUuid, Ui.divider(),
                Ui.muted(Component.translatable("screen.sailboatmod.nation.members.title_label")),
                actionRow(officerTitleInput, saveOfficerTitleButton.inRow().element()),
                actionRow(appointOfficerButton.inRow().element(), removeOfficerButton.inRow().element()),
                appointMayorButton.element(),
                Ui.spacer(),
                Ui.paragraph(Component.translatable("screen.sailboatmod.nation.members.manage_hint"), "sm-muted"));
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
        mapTools.addChildren(Ui.muted(Component.translatable("screen.sailboatmod.nation.claims.map_title")), Ui.spacer(),
                Ui.iconButton(new ItemStack(Items.COMPASS), Component.translatable("screen.sailboatmod.ui.territory.recenter"), this::resetMapOffset),
                Ui.iconButton(new ItemStack(Items.CLOCK), Component.translatable("screen.sailboatmod.nation.action.refresh"), this::requestRefresh));
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

    private UIElement buildWarPage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_S));
        warLines = Ui.column("sm-pane");
        warLines.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));
        peacePanel = Ui.column("sm-pane");
        peacePanel.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));
        peaceProposeRow = row(
                Ui.button(Component.translatable("command.sailboatmod.nation.peace.type.ceasefire"), Ui.Tone.SECONDARY, () -> submitPeaceProposal("ceasefire", 0, 0)),
                Ui.button(Component.translatable("command.sailboatmod.nation.peace.type.cede_territory"), Ui.Tone.SECONDARY, () -> submitPeaceProposal("cede_territory", 5, 0)),
                Ui.button(Component.translatable("command.sailboatmod.nation.peace.type.reparation"), Ui.Tone.SECONDARY, () -> submitPeaceProposal("reparation", 0, 500)));
        peaceAnswerRow = row(
                Ui.button(Component.translatable("screen.sailboatmod.nation.action.accept"), Ui.Tone.PRIMARY,
                        () -> sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.ACCEPT_PEACE), Component.translatable("command.sailboatmod.nation.peace.accepted_self"))),
                Ui.button(Component.translatable("screen.sailboatmod.nation.action.reject"), Ui.Tone.DANGER,
                        () -> sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.REJECT_PEACE), Component.translatable("command.sailboatmod.nation.peace.rejected"))));
        warInfoButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.war_info"), Ui.Tone.SECONDARY, () -> runCommand("nation war info"));
        warTargetInput = Ui.field("", Component.translatable("screen.sailboatmod.nation.war.target_input"), value -> refreshButtons());
        warTargetInput.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(40));
        declareWarButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.declare_war"), Ui.Tone.DANGER, () -> {
            if (confirm.press(CONFIRM_DECLARE_WAR, ticks())) {
                submitDeclareWar();
            }
            refresh();
        });
        warHint = Ui.paragraph(Component.empty(), "sm-muted");
        page.addChildren(warLines, peacePanel, peaceProposeRow, peaceAnswerRow, warInfoButton.element(),
                actionRow(warTargetInput, declareWarButton.inRow().element()), warHint);
        return page;
    }

    private UIElement buildDiplomacyPage() {
        UIElement page = Ui.row();
        page.layout(l -> l.gapAll(Ui.GAP_M).alignItems(AlignItems.STRETCH).flexGrow(1).minHeight(0));
        nationList = Ui.scroller();
        nationList.layout(l -> l.heightAuto().widthPercent(45).maxWidth(200).flexGrow(0).flexShrink(0));

        UIElement right = Ui.column();
        right.layout(l -> l.widthPercent(100).minWidth(0).gapAll(Ui.GAP_S));
        diplomacyEmpty = Ui.emptyState(Component.translatable("screen.sailboatmod.nation.diplomacy.select_hint"));
        diplomacyDetail = Ui.column("sm-pane");
        diplomacyDetail.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        dipSelected = Ui.clippedText("", "sm-title");
        dipStatus = Ui.clippedText("", "sm-muted");
        dipAllyButton = dipButton("screen.sailboatmod.nation.action.ally", Ui.Tone.PRIMARY, NationGuiActionPacket.Action.DIPLOMACY_ALLY);
        dipTradeButton = dipButton("screen.sailboatmod.nation.action.trade", Ui.Tone.SECONDARY, NationGuiActionPacket.Action.DIPLOMACY_TRADE);
        dipEnemyButton = dipButton("screen.sailboatmod.nation.diplomacy.enemy", Ui.Tone.SECONDARY, NationGuiActionPacket.Action.DIPLOMACY_ENEMY);
        dipNeutralButton = dipButton("screen.sailboatmod.nation.action.neutral", Ui.Tone.SECONDARY, NationGuiActionPacket.Action.DIPLOMACY_NEUTRAL);
        dipAcceptAllyButton = dipButton("screen.sailboatmod.nation.action.accept", Ui.Tone.PRIMARY, NationGuiActionPacket.Action.DIPLOMACY_ACCEPT);
        dipRejectAllyButton = dipButton("screen.sailboatmod.nation.action.reject", Ui.Tone.SECONDARY, NationGuiActionPacket.Action.DIPLOMACY_REJECT);
        dipDeclareWarButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.declare_war"), Ui.Tone.DANGER, () -> {
            if (confirm.press(CONFIRM_DIP_WAR + ":" + selectedDiplomacyNationId, ticks())) {
                submitDipAction(NationGuiActionPacket.Action.DECLARE_WAR);
            }
            refresh();
        });
        dipOpenTradeButton = new ActionButton(Component.translatable("screen.sailboatmod.trade.open"), Ui.Tone.PRIMARY, this::openSelectedTradeWindow);
        Button back = Ui.button(Component.translatable("screen.sailboatmod.nation.diplomacy.back"), Ui.Tone.SECONDARY, () -> {
            selectedDiplomacyNationId = "";
            refresh();
        });
        diplomacyDetail.addChildren(dipSelected, dipStatus,
                actionRow(dipAllyButton.inRow().element(), dipTradeButton.inRow().element()),
                actionRow(dipEnemyButton.inRow().element(), dipNeutralButton.inRow().element()),
                actionRow(dipAcceptAllyButton.inRow().element(), dipRejectAllyButton.inRow().element()),
                actionRow(dipOpenTradeButton.inRow().element(), dipDeclareWarButton.inRow().element()),
                row(Ui.spacer(), back));
        dipRequests = Ui.column("sm-pane");
        dipRequests.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));
        right.addChildren(diplomacyEmpty, diplomacyDetail, dipRequests);
        ScrollerView detail = Ui.scroller();
        detail.layout(l -> l.heightAuto().width(0).flexGrow(1).minWidth(0));
        detail.addScrollViewChild(right);
        page.addChildren(nationList, detail);
        return page;
    }

    private ActionButton dipButton(String key, Ui.Tone tone, NationGuiActionPacket.Action action) {
        ActionButton button = new ActionButton(Component.translatable(key), tone, () -> submitDipAction(action));
        button.inRow();
        return button;
    }

    private UIElement buildTreasuryPage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_S));
        UIElement metrics = Ui.row();
        metrics.layout(l -> l.gapAll(Ui.GAP_S).flexWrap(FlexWrap.WRAP));
        metricBalance = Ui.text("", "sm-price");
        metricTowns = Ui.text("", "sm-price");
        metricTrades = Ui.text("", "sm-price");
        metricItems = Ui.text("", "sm-price");
        metrics.addChildren(metric("screen.sailboatmod.nation.treasury.metric.balance", Items.GOLD_INGOT, metricBalance),
                metric("screen.sailboatmod.nation.treasury.metric.towns", Items.BELL, metricTowns),
                metric("screen.sailboatmod.nation.treasury.metric.trades", Items.EMERALD, metricTrades),
                metric("screen.sailboatmod.nation.treasury.metric.items", Items.CHEST, metricItems));

        UIElement policy = Ui.column("sm-pane");
        policy.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        salesTaxBar = new MeterBar();
        tariffBar = new MeterBar();
        salesTaxValue = Ui.alignRight(Ui.clippedText(""));
        tariffValue = Ui.alignRight(Ui.clippedText(""));
        treasuryCommandsButton = Ui.button(Component.translatable("screen.sailboatmod.nation.treasury.commands"), Ui.Tone.SECONDARY, () -> {
            showTreasuryCommands = !showTreasuryCommands;
            refresh();
        });
        policy.addChildren(row(Ui.text(Component.translatable("screen.sailboatmod.nation.treasury.policy_title"), "sm-title"), Ui.spacer(), treasuryCommandsButton),
                policyRow("screen.sailboatmod.nation.treasury.sales_tax_label", salesTaxBar, salesTaxValue, () -> adjustSalesTax(-100), () -> adjustSalesTax(100)),
                policyRow("screen.sailboatmod.nation.treasury.import_tariff_label", tariffBar, tariffValue, () -> adjustTariff(-100), () -> adjustTariff(100)));

        treasuryItemList = Ui.scroller();
        treasuryItemList.layout(l -> l.height(84).flexGrow(0));
        treasuryHint = Ui.paragraph(Component.empty(), "sm-muted");
        page.addChildren(metrics, policy,
                Ui.text(Component.translatable("screen.sailboatmod.nation.treasury.items_label"), "sm-muted"), treasuryItemList,
                Ui.paragraph(Component.translatable("screen.sailboatmod.nation.treasury.bank_access_hint"), "sm-muted"),
                Ui.paragraph(Component.translatable("screen.sailboatmod.nation.treasury.detail_hint"), "sm-muted"), treasuryHint);
        return page;
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

    private UIElement policyRow(String labelKey, MeterBar bar, Label value, Runnable down, Runnable up) {
        UIElement line = Ui.column();
        line.layout(l -> l.widthPercent(100).minWidth(0).gapAll(Ui.GAP_XS));
        Label label = Ui.clippedText(Component.translatable(labelKey), "sm-muted");
        label.layout(l -> l.width(0).flexGrow(1).minWidth(60));
        bar.layout(l -> l.widthPercent(100));
        value.layout(l -> l.width(40));
        Button minus = Ui.button(Component.literal("-"), Ui.Tone.SECONDARY, down);
        Button plus = Ui.button(Component.literal("+"), Ui.Tone.SECONDARY, up);
        taxButtons.add(minus);
        taxButtons.add(plus);
        line.addChildren(actionRow(label, value, minus, plus), bar);
        return line;
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

        UIElement colors = Ui.column("sm-pane");
        colors.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        primaryColorInput = Ui.field("", Component.translatable("screen.sailboatmod.nation.color.primary"), value -> refresh());
        secondaryColorInput = Ui.field("", Component.translatable("screen.sailboatmod.nation.color.secondary"), value -> refresh());
        primaryColorInput.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(60));
        secondaryColorInput.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(60));
        primarySwatch = swatch();
        secondarySwatch = swatch();
        applyColorsButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.apply_colors"), Ui.Tone.PRIMARY, this::submitColorUpdate);
        colors.addChildren(
                row(fixed(Ui.muted(Component.translatable("screen.sailboatmod.nation.color.primary")), 90), primarySwatch, primaryColorInput),
                row(fixed(Ui.muted(Component.translatable("screen.sailboatmod.nation.color.secondary")), 90), secondarySwatch, secondaryColorInput),
                actionRow(Ui.spacer(), applyColorsButton.inRow().element()));

        UIElement upload = Ui.column("sm-pane");
        upload.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        flagPathInput = Ui.field("", Component.translatable("screen.sailboatmod.nation.flag_path"), value -> { });
        flagPathInput.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(40));
        browseButton = Ui.button(Component.translatable("screen.sailboatmod.nation.action.browse"), Ui.Tone.SECONDARY, this::browseForImage);
        uploadButton = new ActionButton(Component.translatable("screen.sailboatmod.nation.action.upload"), Ui.Tone.PRIMARY, this::submitUpload);
        upload.addChildren(Ui.muted(Component.translatable("screen.sailboatmod.nation.flag.upload_hint_short")),
                actionRow(flagPathInput, browseButton, uploadButton.inRow().element()));
        page.addChildren(top, colors, upload);
        return page;
    }

    private static UIElement swatch() {
        UIElement swatch = new UIElement();
        swatch.addClass("sm-chip");
        swatch.layout(l -> l.width(18).height(12).flexShrink(0));
        return swatch;
    }

    private static <T extends UIElement> T fixed(T element, float width) {
        element.layout(l -> l.width(width).flexShrink(0));
        return element;
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

    /** Re-reads the data and selection into the UI. Safe to call before the UI exists. */
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
            case WAR -> refreshWar();
            case DIPLOMACY -> refreshDiplomacy();
            case TREASURY -> refreshTreasury();
            case FLAG -> refreshFlag();
        }
        refreshButtons();
    }

    private void refreshOverview() {
        fillLines(overviewLines, buildOverviewLines());
        boolean hasNation = data.hasNation();
        noNationForm.setDisplay(!hasNation);
        nationForm.setDisplay(hasNation);
        NationOverviewTown town = selectedTown();
        if (town == null) {
            townSelected.setValue(Component.translatable("screen.sailboatmod.nation.overview.town_none"));
            townMayor.setValue(Component.empty());
            townClaims.setValue(Component.empty());
            townColony.setValue(Component.empty());
        } else {
            townSelected.setValue(Component.translatable("screen.sailboatmod.nation.overview.town_selected", town.townName()));
            townMayor.setValue(Component.translatable("screen.sailboatmod.nation.overview.town_mayor", town.mayorName()));
            townClaims.setValue(Component.translatable("screen.sailboatmod.nation.overview.town_claims", town.claimCount(),
                    town.capital() ? Component.translatable("screen.sailboatmod.nation.overview.town_capital").getString() : ""));
            if (town.externalColony().present()) {
                String colonyName = town.externalColony().colonyName().isBlank()
                        ? "#" + town.externalColony().colonyId()
                        : town.externalColony().colonyName();
                townColony.setValue(Component.translatable("screen.sailboatmod.nation.overview.town_minecolonies",
                        colonyName, town.externalColony().population(), town.externalColony().maxPopulation()));
            } else {
                townColony.setValue(Component.empty());
            }
        }
    }

    private void refreshMembers() {
        memberCountLabel.setValue(Component.translatable("screen.sailboatmod.nation.overview.members", data.memberCount()));
        if (shownMembers != data.members() || !selectedMemberUuid.equals(shownMemberSelection)) {
            shownMembers = data.members();
            shownMemberSelection = selectedMemberUuid;
            memberList.clearAllScrollViewChildren();
            if (!data.hasNation()) {
                memberList.addScrollViewChild(Ui.emptyState(Component.translatable("screen.sailboatmod.nation.members.none")));
            } else if (data.members().isEmpty()) {
                memberList.addScrollViewChild(Ui.emptyState(Component.translatable("screen.sailboatmod.nation.members.empty")));
            }
            for (NationOverviewMember member : data.members()) {
                UIElement row = Ui.row("sm-row");
                row.layout(l -> l.widthPercent(100).height(14).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).flexShrink(0));
                UIElement dot = new UIElement();
                dot.layout(l -> l.width(4).height(4).flexShrink(0));
                dot.style(s -> s.background(new ColorRectTexture(member.online() ? 0xFF56DDB4 : 0xFF71808F)));
                Label name = Ui.clippedText(member.playerName());
                name.layout(l -> l.width(0).flexGrow(1));
                Label office = Ui.clippedText(member.officeName(), "sm-warn");
                office.layout(l -> l.widthPercent(35).maxWidth(70));
                row.addChildren(dot, name, office);
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
            memberName.setValue(Component.translatable("screen.sailboatmod.nation.members.no_selection"));
            memberRole.setValue(Component.empty());
            memberStatus.setValue(Component.empty());
            memberUuid.setValue(Component.empty());
        } else {
            memberName.setValue(Component.literal(selected.playerName()));
            memberRole.setValue(Component.translatable("screen.sailboatmod.nation.overview.role", selected.officeName()));
            memberStatus.setValue(Component.translatable(selected.online() ? "screen.sailboatmod.nation.members.status.online" : "screen.sailboatmod.nation.members.status.offline"));
            Ui.setClass(memberStatus, "sm-up", selected.online());
            Ui.setClass(memberStatus, "sm-muted", !selected.online());
            memberUuid.setValue(Component.literal(selected.playerUuid()));
        }
    }

    private void refreshClaims() {
        claimsTabs.select(claimsSubPage);
        boolean perms = claimsSubPage == 1;
        claimsMapView.setDisplay(!perms);
        claimsPermView.setDisplay(perms);
        fillLines(claimLines, buildClaimLines());
        NationOverviewClaim selected = selectedClaim();
        if (selected == null) {
            permissionChunk.setValue(Component.translatable("screen.sailboatmod.nation.claims.perms_select_hint"));
            permissionOwner.setValue(Component.empty());
        } else {
            permissionChunk.setValue(Component.translatable("screen.sailboatmod.nation.claims.selected_chunk", selectedClaimChunkX, selectedClaimChunkZ));
            permissionOwner.setValue(Component.translatable("screen.sailboatmod.nation.claims.owner", selected.nationName()));
        }
        for (Map.Entry<String, ActionButton> entry : permissionButtons.entrySet()) {
            entry.getValue().setLabel(Component.translatable("screen.sailboatmod.nation.claims.button." + entry.getKey(),
                    accessName(selectedAccessLevel(entry.getKey()))));
        }
    }

    private void refreshWar() {
        fillLines(warLines, buildWarLines());
        boolean proposal = data.hasNation() && data.hasPeaceProposal();
        peacePanel.setDisplay(proposal);
        if (proposal) {
            peacePanel.clearAllChildren();
            peacePanel.addChild(row(Ui.text(Component.translatable("screen.sailboatmod.nation.peace.proposal_title"), "sm-title"), Ui.spacer(),
                    Ui.text(Component.translatable("screen.sailboatmod.nation.peace.remaining", data.peaceProposalRemainingSeconds()), "sm-muted")));
            peacePanel.addChild(Ui.text(Component.translatable("command.sailboatmod.nation.peace.type." + data.peaceProposalType())));
            String details = "";
            if (data.peaceProposalCede() > 0) details += Component.translatable("screen.sailboatmod.nation.peace.cede_count", data.peaceProposalCede()).getString() + "  ";
            if (data.peaceProposalAmount() > 0) details += Component.translatable("screen.sailboatmod.nation.peace.reparation_amount", data.peaceProposalAmount()).getString();
            if (!details.isBlank()) {
                peacePanel.addChild(Ui.text(details, "sm-muted"));
            }
        }
        warHint.setValue(data.canDeclareWar()
                ? Component.translatable("screen.sailboatmod.nation.war.declare_hint")
                : Component.translatable("command.sailboatmod.nation.diplomacy.no_permission"));
    }

    private void refreshDiplomacy() {
        List<NationOverviewNationEntry> nations = data.allNations();
        if (shownNations != nations || !selectedDiplomacyNationId.equals(shownNationSelection)) {
            shownNations = nations;
            shownNationSelection = selectedDiplomacyNationId;
            nationList.clearAllScrollViewChildren();
            if (!data.hasNation()) {
                nationList.addScrollViewChild(Ui.emptyState(Component.translatable("screen.sailboatmod.nation.diplomacy.no_nation")));
            } else if (nations.isEmpty()) {
                nationList.addScrollViewChild(Ui.emptyState(Component.translatable("screen.sailboatmod.nation.diplomacy.no_nations")));
            }
            for (NationOverviewNationEntry entry : nations) {
                UIElement row = Ui.row("sm-row");
                row.layout(l -> l.widthPercent(100).height(20).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).flexShrink(0));
                FlagPreview flag = new FlagPreview();
                flag.layout(l -> l.width(16).height(16).flexShrink(0));
                flag.set(entry.flagId().isBlank() ? null
                                : NationFlagTextureCache.resolve(entry.flagId(), entry.primaryColorRgb(), entry.secondaryColorRgb(), entry.flagMirrored()),
                        16, 16, entry.primaryColorRgb());
                Label name = Ui.clippedText(entry.nationName());
                name.layout(l -> l.width(0).flexGrow(1));
                Label relation = Ui.clippedText(statusName(entry.diplomacyStatusId()), statusClass(entry.diplomacyStatusId()));
                relation.layout(l -> l.widthPercent(35).maxWidth(70));
                row.addChildren(flag, name, relation);
                Ui.setClass(row, "selected", entry.nationId().equals(selectedDiplomacyNationId));
                Ui.clickable(row, () -> {
                    selectedDiplomacyNationId = entry.nationId();
                    confirm.disarm();
                    refresh();
                });
                nationList.addScrollViewChild(row);
            }
        }
        NationOverviewNationEntry selected = selectedDiplomacyNation();
        diplomacyEmpty.setDisplay(selected == null);
        diplomacyDetail.setDisplay(selected != null);
        if (selected != null) {
            dipSelected.setValue(Component.translatable("screen.sailboatmod.nation.diplomacy.selected", selected.nationName()));
            dipStatus.setValue(Component.translatable("screen.sailboatmod.nation.diplomacy.status", statusName(selected.diplomacyStatusId())));
        }
        dipRequests.clearAllChildren();
        dipRequests.addChild(Ui.clippedText(Component.translatable("screen.sailboatmod.nation.war.requests_title"), "sm-title"));
        for (Component line : buildIncomingRequestLines()) {
            dipRequests.addChild(Ui.clippedText(line));
        }
    }

    private void refreshTreasury() {
        metricBalance.setValue(Component.literal(formatCompactMoney(data.treasuryBalance())));
        metricTowns.setValue(Component.literal(Integer.toString(data.towns().size())));
        metricTrades.setValue(Component.literal(Integer.toString(data.recentTradeCount())));
        metricItems.setValue(Component.literal(occupiedTreasurySlots() + " / " + data.treasuryItems().size()));
        salesTaxBar.set(data.salesTaxBasisPoints() / 3000f, 0xFF7AA2C6);
        tariffBar.set(data.importTariffBasisPoints() / 5000f, 0xFFC69263);
        salesTaxValue.setValue(Component.literal(formatBasisPoints(data.salesTaxBasisPoints())));
        tariffValue.setValue(Component.literal(formatBasisPoints(data.importTariffBasisPoints())));
        treasuryHint.setValue(Component.translatable(showTreasuryCommands
                ? (data.canManageTreasury() ? "screen.sailboatmod.nation.treasury.commands_hint" : "screen.sailboatmod.nation.treasury.bank_hint")
                : "screen.sailboatmod.nation.treasury.policy_hint"));
        if (shownTreasuryItems != data.treasuryItems()) {
            shownTreasuryItems = data.treasuryItems();
            treasuryItemList.clearAllScrollViewChildren();
            boolean any = false;
            for (ItemStack stack : data.treasuryItems()) {
                if (stack.isEmpty()) {
                    continue;
                }
                any = true;
                treasuryItemList.addScrollViewChild(Ui.itemRow(stack, stack.getHoverName(), Component.literal("x" + stack.getCount()), "sm-price"));
            }
            if (!any) {
                treasuryItemList.addScrollViewChild(Ui.emptyState(Component.translatable("screen.sailboatmod.market.empty")));
            }
        }
    }

    private void refreshFlag() {
        List<Component> lines = buildFlagLines();
        flagLine1.setValue(firstLine(lines));
        flagLine2.setValue(secondLine(lines));
        flagLine3.setValue(thirdLine(lines));
        flagPreview.set(NationFlagTextureCache.resolve(data.flagId(), previewPrimaryColor(), previewSecondaryColor(), data.flagMirrored()),
                data.flagWidth(), data.flagHeight(), previewPrimaryColor());
        toggleMirrorButton.setLabel(Component.translatable(data.flagMirrored() ? "screen.sailboatmod.nation.action.unmirror" : "screen.sailboatmod.nation.action.mirror"));
        setSwatch(primarySwatch, previewPrimaryColor(), parseHexColor(valueOf(primaryColorInput)) != null);
        setSwatch(secondarySwatch, previewSecondaryColor(), parseHexColor(valueOf(secondaryColorInput)) != null);
        Ui.setClass(primaryColorInput, "sm-invalid", parseHexColor(valueOf(primaryColorInput)) == null);
        Ui.setClass(secondaryColorInput, "sm-invalid", parseHexColor(valueOf(secondaryColorInput)) == null);
    }

    private final Map<UIElement, Integer> shownSwatches = new HashMap<>();

    private void setSwatch(UIElement swatch, int rgb, boolean valid) {
        int argb = valid ? 0xFF000000 | (rgb & 0xFFFFFF) : 0xFF7A2020;
        Integer previous = shownSwatches.put(swatch, argb);
        if (previous == null || previous != argb) {
            swatch.style(s -> s.background(new ColorRectTexture(argb)));
        }
    }

    /** Enabled state of every action, from the same rules as before. */
    private void refreshButtons() {
        if (!built) {
            return;
        }
        boolean hasNation = data.hasNation();
        boolean canManageInfo = hasNation && data.canManageInfo();
        boolean noNation = !hasNation;
        long now = ticks();

        // overview
        nationNameInput.setActive(!hasNation || canManageInfo);
        shortNameInput.setActive(canManageInfo);
        saveNationInfoButton.sync(canManageInfo && nationInfoChanged(), false, false);
        createNationButton.sync(noNation && !valueOf(createNameInput).trim().isBlank(), false, false);
        joinNationButton.sync(noNation && !valueOf(joinNationInput).trim().isBlank(), false, false);
        openCapitalTownButton.sync(hasNation && selectedTown() != null, false, false);
        removeCoreButton.sync(hasNation && data.hasCore() && data.canManageClaims(), false, confirm.isArmed(CONFIRM_REMOVE_CORE, now));
        previousTownButton.setActive(hasNation && data.towns().size() > 1);
        nextTownButton.setActive(hasNation && data.towns().size() > 1);

        // members
        boolean leaderControls = hasNation && data.isLeader();
        officerTitleInput.setActive(leaderControls);
        saveOfficerTitleButton.sync(leaderControls && officerTitleChanged(), false, false);
        appointOfficerButton.sync(leaderControls && canAppointSelectedMember(), false, false);
        removeOfficerButton.sync(leaderControls && canRemoveSelectedOfficer(), false, false);
        appointMayorButton.sync(leaderControls && canAssignSelectedMemberAsMayor(), false, false);

        // claims
        NationOverviewClaim selectedClaim = selectedClaim();
        boolean ownClaim = selectedClaim != null && data.nationId().equals(selectedClaim.nationId());
        boolean canClaims = hasNation && data.canManageClaims();
        claimButton.sync(canClaims && selectedClaim == null, false, false);
        unclaimButton.sync(canClaims && ownClaim, false, confirm.isArmed(CONFIRM_UNCLAIM, now));
        for (ActionButton button : permissionButtons.values()) {
            button.sync(canClaims && ownClaim, false, false);
        }

        // war
        boolean canDiplomacy = hasNation && data.canDeclareWar();
        boolean hasWar = data.hasActiveWar();
        boolean incomingPeace = data.hasPeaceProposal() && data.peaceProposalIncoming();
        peaceProposeRow.setDisplay(hasWar && !incomingPeace);
        peaceAnswerRow.setDisplay(incomingPeace);
        for (UIElement child : peaceProposeRow.getChildren()) {
            child.setActive(canDiplomacy && hasWar);
        }
        for (UIElement child : peaceAnswerRow.getChildren()) {
            child.setActive(canDiplomacy && hasWar && incomingPeace);
        }
        warInfoButton.sync(hasNation, false, false);
        warTargetInput.setActive(canDiplomacy);
        declareWarButton.sync(canDiplomacy && !valueOf(warTargetInput).trim().isBlank() && !hasWar && data.warCooldownRemainingSeconds() <= 0,
                false, confirm.isArmed(CONFIRM_DECLARE_WAR, now));

        // diplomacy
        NationOverviewNationEntry selectedNation = selectedDiplomacyNation();
        boolean dipSelection = selectedNation != null;
        boolean allied = dipSelection && "allied".equals(selectedNation.diplomacyStatusId());
        boolean pendingAlly = dipSelection && hasPendingAllianceRequest(selectedDiplomacyNationId);
        dipAllyButton.sync(canDiplomacy && dipSelection && !allied, false, false);
        dipTradeButton.sync(canDiplomacy && dipSelection, false, false);
        dipEnemyButton.sync(canDiplomacy && dipSelection, false, false);
        dipNeutralButton.sync(canDiplomacy && dipSelection, false, false);
        dipAcceptAllyButton.element().setDisplay(pendingAlly);
        dipRejectAllyButton.element().setDisplay(pendingAlly);
        dipAcceptAllyButton.sync(canDiplomacy && pendingAlly, false, false);
        dipRejectAllyButton.sync(canDiplomacy && pendingAlly, false, false);
        dipDeclareWarButton.sync(canDiplomacy && dipSelection && !hasWar && data.warCooldownRemainingSeconds() <= 0,
                false, confirm.isArmed(CONFIRM_DIP_WAR + ":" + selectedDiplomacyNationId, now));
        dipOpenTradeButton.sync(canOpenTradeWindow(currentPage == Page.DIPLOMACY, hasNation, dipSelection, data.canDeclareWar(), data.canManageTreasury()), false, false);

        // treasury
        boolean canTreasury = hasNation && data.canManageTreasury();
        for (Button button : taxButtons) {
            button.setActive(canTreasury);
        }
        treasuryCommandsButton.setDisplay(hasNation);

        // flag
        primaryColorInput.setActive(canManageInfo);
        secondaryColorInput.setActive(canManageInfo);
        applyColorsButton.sync(canManageInfo && colorInputsValid(), false, false);
        boolean canFlag = hasNation && data.canUploadFlag();
        flagPathInput.setActive(canFlag);
        browseButton.setActive(canFlag);
        uploadButton.sync(canFlag, false, false);
        toggleMirrorButton.sync(canFlag && !data.flagId().isBlank(), false, false);
    }

    private static void fillLines(UIElement container, List<Component> lines) {
        container.clearAllChildren();
        for (Component line : lines) {
            container.addChild(Ui.paragraph(line));
        }
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

    private static String statusName(String statusId) {
        String id = statusId == null || statusId.isBlank() ? "neutral" : statusId.toLowerCase(Locale.ROOT);
        return Component.translatable("command.sailboatmod.nation.diplomacy.status." + id).getString();
    }

    private static String statusClass(String statusId) {
        if (statusId == null) {
            return "sm-muted";
        }
        return switch (statusId) {
            case "allied", "trade" -> "sm-up";
            case "enemy" -> "sm-down";
            default -> "sm-muted";
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
                ClaimMapPainter.drawClaims(g, claimWorldMapView, viewport, NationHomeScreen.this::findClaim, NationOverviewClaim::nationId);
                ClaimMapPainter.drawMarker(g, claimWorldMapView, viewport, data.currentChunkX(), data.currentChunkZ(), 0xFFFFFFFF);
                ClaimMapPainter.drawMarker(g, claimWorldMapView, viewport, selectedClaimChunkX, selectedClaimChunkZ, 0xFFFFD166);
                ClaimMapPainter.drawArea(g, claimWorldMapView, viewport, areaCorner1X, areaCorner1Z, areaCorner2X, areaCorner2Z);
                ClaimMapPainter.drawProgress(g, viewport, data.claimMapState(),
                        claimMapProgressWidth(data.claimMapState(), viewport.width() - 2, true),
                        claimMapProgressWidth(data.claimMapState(), viewport.width() - 2, false));
                ClaimMapPainter.drawHoverLabel(g, font, claimWorldMapView, viewport, mouseX, mouseY, NationHomeScreen.this::findClaim,
                        NationOverviewClaim::nationId, NationOverviewClaim::nationName, data.nearbyClaims());
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
    protected boolean onTextFieldKey(UIElement field, int keyCode) {
        if (!isEnter(keyCode)) {
            return false;
        }
        if (field == createNameInput) return submitAndTrue(this::submitCreateNation);
        if (field == nationNameInput) return submitAndTrue(this::submitNationInfoUpdate);
        if (field == shortNameInput) return submitAndTrue(this::submitNationInfoUpdate);
        if (field == joinNationInput) return submitAndTrue(this::submitJoinNation);
        if (field == primaryColorInput || field == secondaryColorInput) return submitAndTrue(this::submitColorUpdate);
        if (field == flagPathInput) return submitAndTrue(this::submitUpload);
        if (field == officerTitleInput) return submitAndTrue(this::submitOfficerTitleUpdate);
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        NationClientHooks.onScreenClosed(this.data.nationId());
        this.claimWorldMapView.close();
    }

    private void switchPage(Page page) {
        this.currentPage = page == null ? Page.OVERVIEW : page;
        confirm.disarm();
        if (this.currentPage == Page.CLAIMS) {
            ensureClaimPreviewVisible();
        }
        refresh();
    }

    private void syncNationInfoInputs() {
        if (!this.data.hasNation() || nationNameInput == null) return;
        if (!this.nationNameInput.isFocused()) this.nationNameInput.setText(this.data.nationName(), false);
        if (!this.shortNameInput.isFocused()) this.shortNameInput.setText(this.data.shortName(), false);
    }

    private void syncColorInputs() {
        if (primaryColorInput == null) return;
        if (!this.primaryColorInput.isFocused()) this.primaryColorInput.setText(hex(this.data.primaryColorRgb()), false);
        if (!this.secondaryColorInput.isFocused()) this.secondaryColorInput.setText(hex(this.data.secondaryColorRgb()), false);
    }

    private void syncOfficerTitleInput() {
        if (this.officerTitleInput != null && !this.officerTitleInput.isFocused()) this.officerTitleInput.setText(this.data.officerTitle(), false);
    }

    @Override
    protected void afterInit() {
        syncNationInfoInputs();
        syncColorInputs();
        syncOfficerTitleInput();
        refresh();
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

    public NationHomeScreen(NationOverviewData data) {
        super(Component.translatable("screen.sailboatmod.nation.home.title"));
        this.data = data == null ? NationOverviewData.empty() : data;
        rememberClaimRadius(this.data);
        syncSelections();
        syncTownSelection();
        syncClaimWorldMapViewToMapCenter(false);
    }

    public void updateData(NationOverviewData updated) {
        NationOverviewData previousData = this.data;
        rememberClaimRadius(previousData);
        NationOverviewData nextData = updated == null ? NationOverviewData.empty() : updated;
        boolean preserveVisibleCenter = shouldPreserveClaimMapVisibleCenter(
                previousData == null ? "" : previousData.nationId(),
                nextData.nationId(),
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
        syncTownSelection();
        syncNationInfoInputs();
        syncColorInputs();
        syncOfficerTitleInput();
        this.statusLine = Component.translatable("screen.sailboatmod.nation.status.synced");
        refresh();
        flushQueuedPreviewRefresh();
    }

    private void tickAutoRefresh() {
        if (!this.data.hasNation()) {
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
        this.autoRefreshTicks++;
        int interval = this.data.hasActiveWar() ? ACTIVE_WAR_REFRESH_INTERVAL_TICKS : AUTO_REFRESH_INTERVAL_TICKS;
        if (this.autoRefreshTicks < interval) {
            return;
        }
        this.autoRefreshTicks = 0;
        requestRefresh();
    }

    private NationOverviewNationEntry selectedDiplomacyNation() {
        for (NationOverviewNationEntry entry : this.data.allNations()) {
            if (entry.nationId().equals(this.selectedDiplomacyNationId)) {
                return entry;
            }
        }
        return null;
    }

    private boolean hasPendingAllianceRequest(String nationId) {
        for (NationOverviewDiplomacyRequest request : this.data.incomingDiplomacyRequests()) {
            if (request.nationId().equals(nationId) && "allied".equals(request.statusId())) {
                return true;
            }
        }
        return false;
    }

    private void submitDipAction(NationGuiActionPacket.Action action) {
        NationOverviewNationEntry selected = selectedDiplomacyNation();
        if (selected == null || !this.data.hasNation() || !this.data.canDeclareWar()) {
            return;
        }
        sendNationAction(new NationGuiActionPacket(action, selected.nationName(), true), Component.translatable("screen.sailboatmod.nation.status.sending"));
    }

    private void openSelectedTradeWindow() {
        NationOverviewNationEntry selected = selectedDiplomacyNation();
        String target = tradeOpenTarget(selected);
        if (target.isBlank() || !this.data.hasNation()) {
            return;
        }
        LOGGER.info("[NationTradeUI] client request open targetId={} targetName={}",
                selected == null ? "" : selected.nationId(),
                selected == null ? "" : selected.nationName());
        sendNationAction(
                new NationGuiActionPacket(NationGuiActionPacket.Action.OPEN_TRADE_SCREEN, target, true),
                Component.translatable("screen.sailboatmod.nation.status.sending")
        );
    }

    private static int diplomacyStatusColor(String statusId) {
        if (statusId == null || statusId.isBlank()) return 0xFF8D98A3;
        return switch (statusId) {
            case "allied" -> 0xFF55FF55;
            case "trade" -> 0xFF55FFFF;
            case "enemy" -> 0xFFFF5555;
            default -> 0xFF8D98A3;
        };
    }

    private List<Component> buildOverviewLines() {
        List<Component> lines = new ArrayList<>();
        if (!this.data.hasNation()) {
            lines.add(Component.translatable("screen.sailboatmod.nation.overview.none"));
            lines.add(Component.translatable("screen.sailboatmod.nation.overview.create_hint"));
            lines.add(Component.translatable("screen.sailboatmod.nation.overview.join_hint"));
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.nation.overview.name", this.data.nationName(), this.data.shortName()));
        lines.add(Component.translatable("screen.sailboatmod.nation.overview.role", this.data.officeName()));
        lines.add(Component.translatable("screen.sailboatmod.nation.overview.leader", this.data.leaderName()));
        lines.add(Component.translatable("screen.sailboatmod.nation.overview.members", this.data.memberCount()));
        lines.add(Component.translatable("screen.sailboatmod.nation.overview.capital_town", this.data.capitalTownName().isBlank() ? "-" : this.data.capitalTownName()));
        lines.add(Component.translatable("screen.sailboatmod.nation.overview.colors", hex(this.data.primaryColorRgb()), hex(this.data.secondaryColorRgb())));
        lines.add(this.data.hasCore() ? Component.translatable("screen.sailboatmod.nation.overview.core", formatCoreLocation()) : Component.translatable("screen.sailboatmod.nation.overview.core_missing"));
        return lines;
    }

    private List<Component> buildClaimLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.sailboatmod.nation.claims.current_chunk", this.data.currentChunkX(), this.data.currentChunkZ()));
        lines.add(Component.translatable("screen.sailboatmod.nation.claims.selected_chunk", this.selectedClaimChunkX, this.selectedClaimChunkZ));
        if (!this.data.hasNation()) {
            lines.add(Component.translatable("screen.sailboatmod.nation.claims.no_nation"));
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.nation.claims.total", this.data.totalClaims()));
        lines.add(Component.translatable("screen.sailboatmod.nation.claims.cost", NationClaimService.claimCost()));
        NationOverviewClaim selected = selectedClaim();
        if (selected == null) {
            lines.add(Component.translatable("screen.sailboatmod.nation.claims.unclaimed_selected"));
            lines.add(Component.translatable("screen.sailboatmod.nation.claims.selection_hint"));
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.nation.claims.owner", selected.nationName()));
        return lines;
    }

    private List<Component> buildWarLines() {
        List<Component> lines = new ArrayList<>();
        if (!this.data.hasNation()) {
            lines.add(Component.translatable("screen.sailboatmod.nation.war.no_nation"));
            return lines;
        }
        if (this.data.hasActiveWar()) {
            lines.add(Component.translatable("screen.sailboatmod.nation.war.opponent", this.data.warOpponentName()));
            lines.add(Component.translatable("screen.sailboatmod.nation.war.score", this.data.warScoreSelf(), this.data.warScoreOpponent()));
            lines.add(Component.translatable("screen.sailboatmod.nation.war.capture", this.data.warCaptureProgress(), this.data.warScoreLimit()));
            lines.add(Component.translatable("screen.sailboatmod.nation.war.status", warStatusName(this.data.warStatus())));
            lines.add(Component.translatable("screen.sailboatmod.nation.war.timer", formatDuration(this.data.warTimeRemainingSeconds())));
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.nation.war.peace"));
        lines.add(this.data.warCooldownRemainingSeconds() > 0 ? Component.translatable("screen.sailboatmod.nation.war.cooldown", formatDuration(this.data.warCooldownRemainingSeconds())) : Component.translatable("screen.sailboatmod.nation.war.declare_hint"));
        return lines;
    }

    private List<Component> buildFlagLines() {
        List<Component> lines = new ArrayList<>();
        if (!this.data.hasNation()) {
            lines.add(Component.translatable("screen.sailboatmod.nation.flag.none"));
            lines.add(Component.translatable("screen.sailboatmod.nation.flag.upload_hint_short"));
            lines.add(Component.empty());
            return lines;
        }
        lines.add(this.data.flagId().isBlank() ? Component.translatable("screen.sailboatmod.nation.flag.placeholder") : Component.translatable("screen.sailboatmod.nation.flag.meta", this.data.flagWidth(), this.data.flagHeight(), this.data.flagByteSize()));
        lines.add(Component.translatable("screen.sailboatmod.nation.flag.id", this.data.flagId().isBlank() ? "-" : shortText(this.data.flagId(), 22)));
        lines.add(Component.translatable(this.data.flagMirrored() ? "screen.sailboatmod.nation.flag.mirror.on" : "screen.sailboatmod.nation.flag.mirror.off"));
        return lines;
    }

    private int mapCenterX() { return this.data.previewCenterChunkX() + this.mapOffsetX; }
    private int mapCenterZ() { return this.data.previewCenterChunkZ() + this.mapOffsetZ; }

    static boolean shouldShowClaimMapProgress(ClaimPreviewMapState mapState) {
        ClaimPreviewMapState safeMapState = mapState == null ? ClaimPreviewMapState.empty() : mapState;
        return safeMapState.loading() || safeMapState.hasPendingProgress();
    }

    static boolean canOpenTradeWindow(boolean diplomacyPage,
                                      boolean hasNation,
                                      boolean hasSelection,
                                      boolean canDeclareWar,
                                      boolean canManageTreasury) {
        return diplomacyPage && hasNation && hasSelection;
    }

    private static String tradeOpenTarget(NationOverviewNationEntry selected) {
        if (selected == null) {
            return "";
        }
        String nationId = selected.nationId();
        return nationId.isBlank() ? selected.nationName() : nationId;
    }

    static String tradeOpenTargetForTest(NationOverviewNationEntry selected) {
        return tradeOpenTarget(selected);
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

    private List<Component> buildDiplomacyRelationLines() {
        List<Component> lines = new ArrayList<>();
        if (this.data.diplomacyRelations().isEmpty()) {
            lines.add(Component.translatable("screen.sailboatmod.nation.war.relations_none"));
            return lines;
        }
        for (NationOverviewDiplomacyEntry entry : this.data.diplomacyRelations()) {
            lines.add(Component.translatable(
                    "command.sailboatmod.nation.diplomacy.info.entry",
                    trimToWidth(entry.nationName(), 98),
                    Component.translatable("command.sailboatmod.nation.diplomacy.status." + entry.statusId().toLowerCase(Locale.ROOT))
            ));
        }
        return lines;
    }

    private List<Component> buildIncomingRequestLines() {
        List<Component> lines = new ArrayList<>();
        boolean found = false;
        for (NationOverviewDiplomacyRequest request : this.data.incomingDiplomacyRequests()) {
            if (!"allied".equalsIgnoreCase(request.statusId())) continue;
            lines.add(Component.translatable(
                    "command.sailboatmod.nation.diplomacy.request.entry",
                    trimToWidth(request.nationName(), 98),
                    Component.translatable("command.sailboatmod.nation.diplomacy.status." + request.statusId().toLowerCase(Locale.ROOT))
            ));
            found = true;
        }
        if (!found) lines.add(Component.translatable("screen.sailboatmod.nation.war.requests_none"));
        return lines;
    }

    private NationOverviewDiplomacyRequest firstIncomingAllianceRequest() {
        for (NationOverviewDiplomacyRequest request : this.data.incomingDiplomacyRequests()) {
            if ("allied".equalsIgnoreCase(request.statusId())) return request;
        }
        return null;
    }

    private boolean hasIncomingAllianceRequest(String target) {
        if (target == null || target.isBlank()) return false;
        for (NationOverviewDiplomacyRequest request : this.data.incomingDiplomacyRequests()) {
            if (!"allied".equalsIgnoreCase(request.statusId())) continue;
            if (matchesNationTarget(target, request.nationId(), request.nationName())) return true;
        }
        return false;
    }

    private boolean matchesNationTarget(String target, String nationId, String nationName) {
        return target.equalsIgnoreCase(nationId) || target.equalsIgnoreCase(nationName);
    }

    private void requestRefresh() { requestRefresh(mapCenterX(), mapCenterZ(), true); }
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
        long revision = NationClientHooks.beginClaimPreviewRequest(centerChunkX, centerChunkZ, viewportRadius());
        this.pendingPreviewRevision = revision;
        sendViewportRequest(centerChunkX, centerChunkZ, revision, explicitRefresh);
        this.statusLine = Component.translatable("screen.sailboatmod.nation.status.refreshing");
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
                    RequestClaimMapViewportPacket.ScreenKind.NATION,
                    this.data.nationId(),
                    currentDimensionId(),
                    revision,
                    viewportRadius(),
                    centerChunkX,
                    centerChunkZ
            ));
        } else {
            ModNetwork.CHANNEL.sendToServer(new RequestClaimMapViewportPacket(
                    RequestClaimMapViewportPacket.ScreenKind.NATION,
                    this.data.nationId(),
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

    private static boolean shouldClearClaimOverlayCache(NationOverviewData previousData, NationOverviewData nextData) {
        if (previousData == null || nextData == null) {
            return false;
        }
        return shouldClearClaimOverlayCacheForTest(
                previousData.nationId(),
                nextData.nationId(),
                previousData.primaryColorRgb(),
                nextData.primaryColorRgb(),
                previousData.secondaryColorRgb(),
                nextData.secondaryColorRgb()
        );
    }

    static boolean shouldClearClaimOverlayCacheForTest(String previousNationId,
                                                       String nextNationId,
                                                       int previousPrimaryColor,
                                                       int nextPrimaryColor,
                                                       int previousSecondaryColor,
                                                       int nextSecondaryColor) {
        return !normalizedId(previousNationId).equals(normalizedId(nextNationId))
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

    private void rememberClaimRadius(NationOverviewData snapshot) {
        int radius = resolvedRadiusForSnapshot(snapshot);
        if (radius >= 0) {
            this.lastKnownClaimRadius = radius;
        }
    }

    private int resolvedRadiusForSnapshot(NationOverviewData snapshot) {
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
        LOGGER.info("[NationClaimPreview] {}", message);
    }

    private void openCapitalTown() { NationOverviewTown town = selectedTown(); if (town == null) { this.statusLine = Component.translatable("screen.sailboatmod.nation.overview.town_none"); return; } com.monpai.sailboatmod.client.TownClientHooks.requestOpen(); com.monpai.sailboatmod.client.TownClientHooks.openCachedOrEmpty(); ModNetwork.CHANNEL.sendToServer(new OpenTownMenuPacket(town.townId())); this.statusLine = Component.translatable("screen.sailboatmod.nation.overview.opening_town", town.townName().isBlank() ? town.townId() : town.townName()); }
    private void previousTownSelection() { cycleTownSelection(-1); }
    private void nextTownSelection() { cycleTownSelection(1); }
    private void claimSelectedChunk() {
        if (hasAreaSelection()) {
            int x1 = this.areaCorner1X;
            int z1 = this.areaCorner1Z;
            int x2 = this.areaCorner2X;
            int z2 = this.areaCorner2Z;
            int count = (Math.abs(x2 - x1) + 1) * (Math.abs(z2 - z1) + 1);
            ModNetwork.CHANNEL.sendToServer(new NationGuiActionPacket(NationGuiActionPacket.Action.CLAIM_AREA, x1, z1, "", x2 + "," + z2));
            this.statusLine = Component.translatable("screen.sailboatmod.nation.claims.action_batch_claiming", count);
            return;
        }
        sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.CLAIM_CHUNK, this.selectedClaimChunkX, this.selectedClaimChunkZ), Component.translatable("screen.sailboatmod.nation.claims.action_claiming", this.selectedClaimChunkX, this.selectedClaimChunkZ));
    }
    private void unclaimSelectedChunk() {
        if (hasAreaSelection()) {
            int x1 = this.areaCorner1X;
            int z1 = this.areaCorner1Z;
            int x2 = this.areaCorner2X;
            int z2 = this.areaCorner2Z;
            int count = (Math.abs(x2 - x1) + 1) * (Math.abs(z2 - z1) + 1);
            ModNetwork.CHANNEL.sendToServer(new NationGuiActionPacket(NationGuiActionPacket.Action.UNCLAIM_AREA, x1, z1, "", x2 + "," + z2));
            this.statusLine = Component.translatable("screen.sailboatmod.nation.claims.action_batch_unclaiming", count);
            return;
        }
        sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.UNCLAIM_CHUNK, this.selectedClaimChunkX, this.selectedClaimChunkZ), Component.translatable("screen.sailboatmod.nation.claims.action_unclaiming", this.selectedClaimChunkX, this.selectedClaimChunkZ));
    }
    private boolean hasAreaSelection() { return this.areaCorner1X != Integer.MIN_VALUE && this.areaCorner2X != Integer.MIN_VALUE; }
    private void appointSelectedMember() { NationOverviewMember selected = selectedMember(); if (selected != null) sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.APPOINT_OFFICER, selected.playerUuid()), Component.translatable("screen.sailboatmod.nation.members.action_appointing", selected.playerName())); }
    private void removeSelectedOfficer() { NationOverviewMember selected = selectedMember(); if (selected != null) sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.REMOVE_OFFICER, selected.playerUuid()), Component.translatable("screen.sailboatmod.nation.members.action_removing", selected.playerName())); }
    private void appointSelectedMayor() { NationOverviewMember selected = selectedMember(); if (selected != null && !this.data.capitalTownId().isBlank()) sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.APPOINT_MAYOR, selected.playerUuid(), this.data.capitalTownId()), Component.translatable("screen.sailboatmod.nation.members.action_assigning_mayor", selected.playerName(), this.data.capitalTownName().isBlank() ? this.data.capitalTownId() : this.data.capitalTownName())); }
    private void sendNationAction(NationGuiActionPacket packet, Component status) { ModNetwork.CHANNEL.sendToServer(packet); this.statusLine = status == null ? Component.empty() : status; }
    private void submitUpload() { this.statusLine = NationFlagUploadClient.uploadFromPath(valueOf(this.flagPathInput)); }

    private void submitNationInfoUpdate() {
        if (!this.data.hasNation() || !this.data.canManageInfo()) return;
        String nationName = valueOf(this.nationNameInput).trim();
        String shortName = valueOf(this.shortNameInput).trim();
        boolean changed = false;
        if (!nationName.isBlank() && !nationName.equals(this.data.nationName())) {
            sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.RENAME_NATION, nationName, true), null);
            changed = true;
        }
        if (!shortName.isBlank() && !shortName.equals(this.data.shortName())) {
            sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.SET_SHORT_NAME, shortName, true), null);
            changed = true;
        }
        if (!changed) { this.statusLine = Component.translatable("screen.sailboatmod.nation.info.unchanged"); return; }
        this.statusLine = Component.translatable("screen.sailboatmod.nation.info.updating");
    }

    private void submitCreateNation() {
        if (this.data.hasNation()) return;
        String nationName = valueOf(this.createNameInput).trim();
        if (nationName.isBlank()) {
            this.statusLine = Component.translatable("screen.sailboatmod.nation.overview.create_required");
            return;
        }
        sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.CREATE_NATION, nationName, true), Component.translatable("screen.sailboatmod.nation.overview.create_submitting", nationName));
    }

    private void submitJoinNation() {
        if (this.data.hasNation()) return;
        String targetNation = valueOf(this.joinNationInput).trim();
        if (targetNation.isBlank()) {
            this.statusLine = Component.translatable("screen.sailboatmod.nation.overview.join_required");
            return;
        }
        sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.JOIN_NATION, targetNation, true), Component.translatable("screen.sailboatmod.nation.overview.join_submitting", targetNation));
    }

    private void submitRemoveCore() {
        if (!this.data.hasNation() || !this.data.hasCore() || !this.data.canManageClaims()) return;
        sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.REMOVE_CORE), Component.translatable("screen.sailboatmod.nation.core.removing"));
    }

    private void submitMirrorToggle() {
        if (!this.data.hasNation() || !this.data.canUploadFlag() || this.data.flagId().isBlank()) return;
        sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.TOGGLE_FLAG_MIRROR), Component.translatable("screen.sailboatmod.nation.flag.mirror.toggling"));
    }

    private void adjustSalesTax(int deltaBp) {
        int current = this.data.salesTaxBasisPoints();
        int next = Math.max(0, Math.min(3000, current + deltaBp));
        sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.SET_SALES_TAX, String.valueOf(next), true), Component.translatable("command.sailboatmod.nation.treasury.tax.sales_set", String.format("%.1f%%", next / 100.0)));
    }

    private void adjustTariff(int deltaBp) {
        int current = this.data.importTariffBasisPoints();
        int next = Math.max(0, Math.min(5000, current + deltaBp));
        sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.SET_IMPORT_TARIFF, String.valueOf(next), true), Component.translatable("command.sailboatmod.nation.treasury.tax.tariff_set", String.format("%.1f%%", next / 100.0)));
    }

    private void adjustRadius(int delta) {
        int current = com.monpai.sailboatmod.ModConfig.CLAIM_PREVIEW_RADIUS.get();
        int next = Math.max(5, Math.min(60, current + delta));
        com.monpai.sailboatmod.ModConfig.CLAIM_PREVIEW_RADIUS.set(next);
        com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService.clearCache();
        requestRefresh();
    }

    private void resetMapOffset() {
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

    private void submitPeaceProposal(String type, int cede, long reparation) {
        sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.PROPOSE_PEACE, type + "," + cede + "," + reparation, true), Component.translatable("command.sailboatmod.nation.peace.sent"));
    }

    private void submitDeclareWar() {
        if (!this.data.hasNation() || !this.data.canDeclareWar()) return;
        String target = valueOf(this.warTargetInput).trim();
        if (target.isBlank()) return;
        sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.DECLARE_WAR, target, true), Component.translatable("screen.sailboatmod.nation.war.action.declaring", target));
    }

    private void submitOfficerTitleUpdate() {
        if (!this.data.hasNation() || !this.data.isLeader()) return;
        String title = valueOf(this.officerTitleInput).trim();
        if (title.isBlank()) { this.statusLine = Component.translatable("command.sailboatmod.nation.office.rename.invalid", 24); return; }
        if (!officerTitleChanged()) { this.statusLine = Component.translatable("screen.sailboatmod.nation.members.title_unchanged"); return; }
        sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.RENAME_OFFICER_TITLE, title, true), Component.translatable("screen.sailboatmod.nation.members.action_renaming", title));
    }

    private void submitColorUpdate() {
        if (!this.data.hasNation() || !this.data.canManageInfo()) return;
        Integer primary = parseHexColor(valueOf(this.primaryColorInput));
        Integer secondary = parseHexColor(valueOf(this.secondaryColorInput));
        if (primary == null || secondary == null) { this.statusLine = Component.translatable("screen.sailboatmod.nation.color.invalid"); return; }
        boolean changed = false;
        if (primary.intValue() != this.data.primaryColorRgb()) {
            sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.SET_COLOR_PRIMARY, commandHex(primary), true), null);
            changed = true;
        }
        if (secondary.intValue() != this.data.secondaryColorRgb()) {
            sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.SET_COLOR_SECONDARY, commandHex(secondary), true), null);
            changed = true;
        }
        if (!changed) { this.statusLine = Component.translatable("screen.sailboatmod.nation.color.unchanged"); return; }
        this.statusLine = Component.translatable("screen.sailboatmod.nation.color.updating", hex(primary), hex(secondary));
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
                    ModNetwork.CHANNEL.sendToServer(new SetClaimPermissionPacket(ax, az, actionId, next.id()));
                }
            }
            int count = (maxX - minX + 1) * (maxZ - minZ + 1);
            this.statusLine = Component.translatable("screen.sailboatmod.nation.claims.updating_batch", count, actionName(actionId), accessName(next.id()));
        } else {
            ModNetwork.CHANNEL.sendToServer(new SetClaimPermissionPacket(this.selectedClaimChunkX, this.selectedClaimChunkZ, actionId, next.id()));
            this.statusLine = Component.translatable("screen.sailboatmod.nation.claims.updating", actionName(actionId), accessName(next.id()));
        }
    }

    private void runCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.connection != null) {
            minecraft.player.connection.sendCommand(command);
            this.statusLine = Component.translatable("screen.sailboatmod.nation.command_sent", "/" + command);
        }
    }
    private void browseForImage() { this.statusLine = Component.translatable("screen.sailboatmod.nation.upload.dialog_unavailable"); }
    private NationClaimAccessLevel nextAccessLevel(String id) { NationClaimAccessLevel c = NationClaimAccessLevel.fromId(id); if (c == null) c = NationClaimAccessLevel.MEMBER; return switch (c) { case MEMBER -> NationClaimAccessLevel.OFFICER; case OFFICER -> NationClaimAccessLevel.LEADER; case LEADER -> NationClaimAccessLevel.ALLY; case ALLY -> NationClaimAccessLevel.NEUTRAL; case NEUTRAL -> NationClaimAccessLevel.ANYONE; case ANYONE -> NationClaimAccessLevel.MEMBER; }; }
    private boolean submitAndTrue(Runnable r) { r.run(); return true; }

    private void syncSelections() { if (this.selectedClaimChunkX == Integer.MIN_VALUE || Math.abs(this.selectedClaimChunkX - this.data.currentChunkX()) > claimRadius() || Math.abs(this.selectedClaimChunkZ - this.data.currentChunkZ()) > claimRadius()) { this.selectedClaimChunkX = this.data.currentChunkX(); this.selectedClaimChunkZ = this.data.currentChunkZ(); } if (this.data.members().isEmpty()) { this.selectedMemberUuid = ""; this.memberScroll = 0; return; } if (this.selectedMemberUuid.isBlank()) this.selectedMemberUuid = this.data.members().get(0).playerUuid(); for (NationOverviewMember member : this.data.members()) if (member.playerUuid().equals(this.selectedMemberUuid)) return; this.selectedMemberUuid = this.data.members().get(0).playerUuid(); }
    private NationOverviewMember selectedMember() { for (NationOverviewMember m : this.data.members()) if (m.playerUuid().equals(this.selectedMemberUuid)) return m; return null; }
    private NationOverviewClaim selectedClaim() { return findClaim(this.selectedClaimChunkX, this.selectedClaimChunkZ); }
    private void cacheNearbyClaims() {
        for (NationOverviewClaim claim : this.data.nearbyClaims()) {
            this.cachedClaimOverlays.put(claimKey(claim.chunkX(), claim.chunkZ()), claim);
        }
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
    private boolean sameOwner(String ownerId, int x, int z) { NationOverviewClaim c = findClaim(x, z); return c != null && ownerId.equals(c.nationId()); }
    private boolean canAppointSelectedMember() { NationOverviewMember s = selectedMember(); return s != null && !NationOfficeIds.LEADER.equals(s.officeId()) && !NationOfficeIds.OFFICER.equals(s.officeId()); }
    private boolean canRemoveSelectedOfficer() { NationOverviewMember s = selectedMember(); return s != null && NationOfficeIds.OFFICER.equals(s.officeId()); }
    private boolean canAssignSelectedMemberAsMayor() { NationOverviewMember s = selectedMember(); return s != null && !this.data.capitalTownId().isBlank(); }
    private boolean officerTitleChanged() { return !valueOf(this.officerTitleInput).trim().equals(this.data.officerTitle()); }
    private boolean nationInfoChanged() { return !valueOf(this.nationNameInput).trim().equals(this.data.nationName()) || !valueOf(this.shortNameInput).trim().equals(this.data.shortName()); }
    private void syncTownSelection() { if (this.data.towns().isEmpty()) { this.selectedTownId = ""; return; } if (!this.selectedTownId.isBlank()) { for (NationOverviewTown town : this.data.towns()) if (town.townId().equals(this.selectedTownId)) return; } if (!this.data.capitalTownId().isBlank()) { for (NationOverviewTown town : this.data.towns()) { if (town.townId().equals(this.data.capitalTownId())) { this.selectedTownId = town.townId(); return; } } } this.selectedTownId = this.data.towns().get(0).townId(); }
    private NationOverviewTown selectedTown() { for (NationOverviewTown town : this.data.towns()) if (town.townId().equals(this.selectedTownId)) return town; return this.data.towns().isEmpty() ? null : this.data.towns().get(0); }
    private void cycleTownSelection(int delta) { if (this.data.towns().isEmpty()) return; int index = 0; for (int i = 0; i < this.data.towns().size(); i++) { if (this.data.towns().get(i).townId().equals(this.selectedTownId)) { index = i; break; } } int size = this.data.towns().size(); int next = Math.floorMod(index + delta, size); this.selectedTownId = this.data.towns().get(next).townId(); refresh(); }

    private int wrappedHeight(Component line, int width) { return Math.max(10, wrap(line, width).size() * 10); }
    private void drawRect(GuiGraphics g, int x1, int y1, int x2, int y2, int color) { int minX = Math.min(x1, x2), maxX = Math.max(x1, x2), minY = Math.min(y1, y2), maxY = Math.max(y1, y2); g.fill(minX, minY, maxX + 1, minY + 1, color); g.fill(minX, maxY, maxX + 1, maxY + 1, color); g.fill(minX, minY, minX + 1, maxY + 1, color); g.fill(maxX, minY, maxX + 1, maxY + 1, color); }
    private List<FormattedCharSequence> wrap(Component text, int width) { return this.font.split(text, width); }
    private int clampMemberScroll(int value) { return Math.max(0, Math.min(value, Math.max(0, this.data.members().size() - MEMBER_VISIBLE_ROWS))); }
    private String trimToWidth(String src, int maxPixels) { if (src == null || src.isEmpty() || maxPixels <= 0 || this.font.width(src) <= maxPixels) return src == null ? "" : src; String ellipsis = "..."; int ew = this.font.width(ellipsis); int end = src.length(); while (end > 0 && this.font.width(src.substring(0, end)) + ew > maxPixels) end--; return src.substring(0, Math.max(0, end)) + ellipsis; }
    private String shortText(String value, int maxLength) { if (value == null || value.isBlank()) return "-"; return value.length() <= maxLength ? value : value.substring(0, Math.max(0, maxLength)) + "..."; }
    private String valueOf(TextField box) { return box == null ? "" : box.getValue(); }
    private String hex(int color) { return String.format(Locale.ROOT, "#%06X", color & 0x00FFFFFF); }
    private String commandHex(int color) { return String.format(Locale.ROOT, "%06X", color & 0x00FFFFFF); }
    private Integer parseHexColor(String raw) { return NationRecord.parseHexColor(raw); }
    private String selectedBreakAccessLevel() { NationOverviewClaim c = selectedClaim(); return c == null ? "" : c.breakAccessLevel(); }
    private String selectedPlaceAccessLevel() { NationOverviewClaim c = selectedClaim(); return c == null ? "" : c.placeAccessLevel(); }
    private String selectedUseAccessLevel() { NationOverviewClaim c = selectedClaim(); return c == null ? "" : c.useAccessLevel(); }
    private String selectedContainerAccessLevel() { NationOverviewClaim c = selectedClaim(); return c == null ? "" : c.containerAccessLevel(); }
    private String selectedRedstoneAccessLevel() { NationOverviewClaim c = selectedClaim(); return c == null ? "" : c.redstoneAccessLevel(); }
    private String selectedEntityUseAccessLevel() { NationOverviewClaim c = selectedClaim(); return c == null ? "" : c.entityUseAccessLevel(); }
    private String selectedEntityDamageAccessLevel() { NationOverviewClaim c = selectedClaim(); return c == null ? "" : c.entityDamageAccessLevel(); }
    private int previewPrimaryColor() { Integer p = parseHexColor(valueOf(this.primaryColorInput)); return p == null ? this.data.primaryColorRgb() : p; }
    private int previewSecondaryColor() { Integer p = parseHexColor(valueOf(this.secondaryColorInput)); return p == null ? this.data.secondaryColorRgb() : p; }
    private boolean colorInputsValid() { return parseHexColor(valueOf(this.primaryColorInput)) != null && parseHexColor(valueOf(this.secondaryColorInput)) != null; }
    private void drawColorSwatch(GuiGraphics g, int x, int y, int color, boolean valid) { g.fill(x, y, x + 18, y + 12, valid ? 0xFF000000 : 0xFF7A2020); g.fill(x + 1, y + 1, x + 17, y + 11, 0xFF000000 | (color & 0x00FFFFFF)); }
    private int previewFlagWidth() { int width = this.data.flagWidth() <= 0 ? 96 : this.data.flagWidth(); int height = this.data.flagHeight() <= 0 ? 48 : this.data.flagHeight(); double scale = Math.min(140.0D / width, 96.0D / height); return Math.max(1, (int) Math.round(width * scale)); }
    private int previewFlagHeight() { int width = this.data.flagWidth() <= 0 ? 96 : this.data.flagWidth(); int height = this.data.flagHeight() <= 0 ? 48 : this.data.flagHeight(); double scale = Math.min(140.0D / width, 96.0D / height); return Math.max(1, (int) Math.round(height * scale)); }
    private String headerText() { return !this.data.hasNation() ? Component.translatable("screen.sailboatmod.nation.header.none").getString() : Component.translatable("screen.sailboatmod.nation.header.value", this.data.nationName(), this.data.officeName()).getString(); }
    private String buildEconomyHeaderLine() {
        if (!this.data.hasNation()) {
            return "";
        }
        return String.join("   ",
                Component.translatable("screen.sailboatmod.econbar.treasury", formatCompactMoney(this.data.treasuryBalance())).getString(),
                Component.translatable("screen.sailboatmod.econbar.tax", formatBasisPoints(this.data.salesTaxBasisPoints())).getString(),
                Component.translatable("screen.sailboatmod.econbar.tariff", formatBasisPoints(this.data.importTariffBasisPoints())).getString(),
                Component.translatable("screen.sailboatmod.econbar.trades", this.data.recentTradeCount()).getString());
    }
    private String formatCompactMoney(long value) { return GoldStandardEconomy.formatBalance(value); }
    private String formatBasisPoints(int basisPoints) { return String.format(Locale.ROOT, "%.1f%%", basisPoints / 100.0); }
    private int occupiedTreasurySlots() { int count = 0; for (net.minecraft.world.item.ItemStack stack : this.data.treasuryItems()) if (!stack.isEmpty()) count++; return count; }
    private String formatCoreLocation() { if (!this.data.hasCore()) return "-"; BlockPos pos = BlockPos.of(this.data.corePos()); return shortText(this.data.coreDimension(), 24) + " @ " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ(); }
    private String accessName(String id) { return (id == null || id.isBlank()) ? Component.translatable("screen.sailboatmod.nation.access.unknown").getString() : Component.translatable("screen.sailboatmod.nation.access." + id.toLowerCase(Locale.ROOT)).getString(); }
    private String actionName(String id) { return (id == null || id.isBlank()) ? Component.translatable("screen.sailboatmod.nation.claims.action.unknown").getString() : Component.translatable("screen.sailboatmod.nation.claims.action." + id.toLowerCase(Locale.ROOT)).getString(); }
    private String warStatusName(String id) { return (id == null || id.isBlank()) ? Component.translatable("screen.sailboatmod.nation.war.status.idle").getString() : Component.translatable("screen.sailboatmod.nation.war.status." + id.toLowerCase(Locale.ROOT)).getString(); }
    private String formatDuration(int totalSeconds) { int safe = Math.max(0, totalSeconds); return String.format(Locale.ROOT, "%d:%02d", safe / 60, safe % 60); }
    private Component firstLine(List<Component> lines) { return lines.size() > 0 ? lines.get(0) : Component.empty(); }
    private Component secondLine(List<Component> lines) { return lines.size() > 1 ? lines.get(1) : Component.empty(); }
    private Component thirdLine(List<Component> lines) { return lines.size() > 2 ? lines.get(2) : Component.empty(); }

    private enum Page {
        OVERVIEW("screen.sailboatmod.nation.section.overview"), MEMBERS("screen.sailboatmod.nation.section.members"), CLAIMS("screen.sailboatmod.nation.section.claims"), WAR("screen.sailboatmod.nation.section.war"), DIPLOMACY("screen.sailboatmod.nation.section.diplomacy"), TREASURY("screen.sailboatmod.nation.section.treasury"), FLAG("screen.sailboatmod.nation.section.flag");
        private final String titleKey;
        Page(String titleKey) { this.titleKey = titleKey; }
        private Component title() { return Component.translatable(this.titleKey); }
    }
}
