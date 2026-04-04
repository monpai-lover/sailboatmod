package com.monpai.sailboatmod.client.screen.nation;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.client.cache.TerrainColorClientCache;
import com.monpai.sailboatmod.client.texture.NationFlagTextureCache;
import com.monpai.sailboatmod.client.texture.NationFlagUploadClient;
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
import com.monpai.sailboatmod.network.packet.OpenNationMenuPacket;
import com.monpai.sailboatmod.network.packet.OpenTownMenuPacket;
import com.monpai.sailboatmod.network.packet.SetClaimPermissionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NationHomeScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean CLAIM_TRACE_ENABLED = true;
    private static final int AUTO_REFRESH_INTERVAL_TICKS = 40;
    private static final int ACTIVE_WAR_REFRESH_INTERVAL_TICKS = 20;
    private static final int SCREEN_W = 468;
    private static final int SCREEN_H = 330;
    private static final int TAB_W = 62;
    private static final int BODY_X = 12;
    private static final int BODY_Y = 64;
    private static final int BODY_W = SCREEN_W - 24;
    private static final int BODY_H = 232;
    private static final int MEMBER_LIST_W = 192;
    private static final int MEMBER_ROW_H = 14;
    private static final int MEMBER_VISIBLE_ROWS = 12;
    private static final int TREASURY_ITEM_ROW_H = 20;
    private static final int TREASURY_ITEM_VISIBLE_ROWS = 4;
    private static final int CLAIM_MAP_W = 164;
    private static final int CLAIM_MAP_H = 164;
    private static final int PREVIEW_DEFAULT_TERRAIN_COLOR = 0xFF33414A;
    private int claimRadius() {
        int size = this.data.nearbyTerrainColors().size();
        if (size <= 0) return com.monpai.sailboatmod.ModConfig.claimPreviewRadius();
        int sub = com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService.SUB;
        int chunkCount = size / (sub * sub);
        int diameter = (int) Math.round(Math.sqrt(chunkCount));
        return (diameter - 1) / 2;
    }

    private NationOverviewData data;
    private Page currentPage = Page.OVERVIEW;
    private EditBox nationNameInput;
    private EditBox shortNameInput;
    private EditBox joinNationInput;
    private EditBox primaryColorInput;
    private EditBox secondaryColorInput;
    private EditBox flagPathInput;
    private EditBox warTargetInput;
    private EditBox officerTitleInput;
    private Component statusLine = Component.empty();
    private Button refreshButton;
    private Button overviewTabButton;
    private Button membersTabButton;
    private Button claimsTabButton;
    private Button warTabButton;
    private Button flagTabButton;
    private Button diplomacyTabButton;
    private Button treasuryTabButton;
    private Button claimButton;
    private Button unclaimButton;
    private Button warButton;
    private Button declareWarButton;
    private Button uploadButton;
    private Button browseButton;
    private Button applyColorsButton;
    private Button saveNationInfoButton;
    private Button createNationButton;
    private Button joinNationButton;
    private Button nationHelpButton;
    private Button openCapitalTownButton;
    private Button removeCoreButton;
    private Button previousTownButton;
    private Button nextTownButton;
    private Button toggleMirrorButton;
    private Button breakPermissionButton;
    private Button placePermissionButton;
    private Button usePermissionButton;
    private Button containerPermissionButton;
    private Button redstonePermissionButton;
    private Button entityUsePermissionButton;
    private Button entityDamagePermissionButton;
    private Button appointOfficerButton;
    private Button removeOfficerButton;
    private Button appointMayorButton;
    private Button saveOfficerTitleButton;
    private int memberScroll;
    private int treasuryItemScroll;
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
    private Button claimsSubPageButton;
    private int diplomacyScroll;
    private int pageScroll;
    private String selectedDiplomacyNationId = "";
    private Button dipAllyButton;
    private Button dipTradeButton;
    private Button dipEnemyButton;
    private Button dipNeutralButton;
    private Button dipDeclareWarButton;
    private Button dipAcceptAllyButton;
    private Button dipRejectAllyButton;
    private Button dipBackButton;
    private Button dipOpenTradeButton;
    private Button salesTaxUpButton;
    private Button salesTaxDownButton;
    private Button tariffUpButton;
    private Button tariffDownButton;
    private Button treasuryCommandsButton;
    private boolean showTreasuryCommands;
    private Button proposeCeasefireButton;
    private Button proposeCedeButton;
    private Button proposeReparationButton;
    private Button acceptPeaceButton;
    private Button rejectPeaceButton;
    private Button resetMapButton;
    private int mapOffsetX = 0;
    private int mapOffsetZ = 0;
    private boolean isDraggingMap = false;
    private double dragStartX = 0;
    private double dragStartY = 0;
    private int pendingPreviewCenterX = Integer.MIN_VALUE;
    private int pendingPreviewCenterZ = Integer.MIN_VALUE;
    private int queuedPreviewCenterX = Integer.MIN_VALUE;
    private int queuedPreviewCenterZ = Integer.MIN_VALUE;
    private boolean resetPending = false;
    private boolean refreshPending;
    private final Map<Long, NationOverviewClaim> cachedClaimOverlays = new HashMap<>();
    private static final int DIP_ROW_H = 20;
    private static final int DIP_VISIBLE_ROWS = 9;

    public NationHomeScreen(NationOverviewData data) {
        super(Component.translatable("screen.sailboatmod.nation.home.title"));
        this.data = data == null ? NationOverviewData.empty() : data;
        syncSelections();
        syncTownSelection();
    }

    public void updateData(NationOverviewData updated) {
        NationOverviewData previousData = this.data;
        boolean preserveVisibleCenter = previousData != null && !previousData.nearbyTerrainColors().isEmpty();
        int visibleCenterX = preserveVisibleCenter ? mapCenterX() : Integer.MIN_VALUE;
        int visibleCenterZ = preserveVisibleCenter ? mapCenterZ() : Integer.MIN_VALUE;
        this.data = updated == null ? NationOverviewData.empty() : updated;
        traceClaim("updateData previewCenter=" + this.data.previewCenterChunkX() + "," + this.data.previewCenterChunkZ()
                + " visibleCenterBefore=" + visibleCenterX + "," + visibleCenterZ
                + " terrainCount=" + this.data.nearbyTerrainColors().size()
                + " nearbyClaims=" + this.data.nearbyClaims().size()
                + " pending=" + this.pendingPreviewCenterX + "," + this.pendingPreviewCenterZ
                + " queued=" + this.queuedPreviewCenterX + "," + this.queuedPreviewCenterZ
                + " resetPending=" + this.resetPending);
        this.refreshPending = false;
        this.autoRefreshTicks = 0;
        cacheNearbyClaims();
        if (this.data.previewCenterChunkX() == this.pendingPreviewCenterX && this.data.previewCenterChunkZ() == this.pendingPreviewCenterZ) {
            this.pendingPreviewCenterX = Integer.MIN_VALUE;
            this.pendingPreviewCenterZ = Integer.MIN_VALUE;
        }
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
        this.memberScroll = clampMemberScroll(this.memberScroll);
        syncSelections();
        syncTownSelection();
        syncNationInfoInputs();
        syncColorInputs();
        syncOfficerTitleInput();
        int diameter = claimRadius() * 2 + 1;
        for (int gz = 0; gz < diameter; gz++) {
            for (int gx = 0; gx < diameter; gx++) {
                int idx = gz * diameter + gx;
                if (idx < this.data.nearbyTerrainColors().size()) {
                    int cx = this.data.previewCenterChunkX() + gx - claimRadius();
                    int cz = this.data.previewCenterChunkZ() + gz - claimRadius();
                    TerrainColorClientCache.put(cx, cz, this.data.nearbyTerrainColors().get(idx));
                }
            }
        }
        this.statusLine = Component.translatable("screen.sailboatmod.nation.status.synced");
        updateButtonState();
        flushQueuedPreviewRefresh();
    }

    @Override
    protected void init() {
        int left = left();
        int top = top();
        this.refreshButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.refresh"), b -> requestRefresh()).bounds(left + SCREEN_W - 82, top + 12, 70, 18).build());
        this.overviewTabButton = addTabButton(left + 12, top + 36, Page.OVERVIEW, Component.translatable("screen.sailboatmod.nation.section.overview"));
        this.membersTabButton = addTabButton(left + 76, top + 36, Page.MEMBERS, Component.translatable("screen.sailboatmod.nation.section.members"));
        this.claimsTabButton = addTabButton(left + 140, top + 36, Page.CLAIMS, Component.translatable("screen.sailboatmod.nation.section.claims"));
        this.warTabButton = addTabButton(left + 204, top + 36, Page.WAR, Component.translatable("screen.sailboatmod.nation.section.war"));
        this.diplomacyTabButton = addTabButton(left + 268, top + 36, Page.DIPLOMACY, Component.translatable("screen.sailboatmod.nation.section.diplomacy"));
        this.treasuryTabButton = addTabButton(left + 332, top + 36, Page.TREASURY, Component.translatable("screen.sailboatmod.nation.section.treasury"));
        this.flagTabButton = addTabButton(left + 396, top + 36, Page.FLAG, Component.translatable("screen.sailboatmod.nation.section.flag"));

        this.claimButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.claim"), b -> claimSelectedChunk()).bounds(left + BODY_X + 12, top + BODY_Y + BODY_H - 26, 72, 18).build());
        this.unclaimButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.unclaim"), b -> unclaimSelectedChunk()).bounds(left + BODY_X + 90, top + BODY_Y + BODY_H - 26, 86, 18).build());
        this.claimsSubPageButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.claims.toggle_perms"), b -> { this.claimsSubPage = this.claimsSubPage == 0 ? 1 : 0; updateButtonState(); }).bounds(left + BODY_X + 184, top + BODY_Y + BODY_H - 26, 120, 18).build());
        this.breakPermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("break", selectedBreakAccessLevel())).bounds(left + BODY_X + 12, top + BODY_Y + 50, 100, 18).build());
        this.placePermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("place", selectedPlaceAccessLevel())).bounds(left + BODY_X + 120, top + BODY_Y + 50, 100, 18).build());
        this.usePermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("use", selectedUseAccessLevel())).bounds(left + BODY_X + 12, top + BODY_Y + 74, 100, 18).build());
        this.containerPermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("container", selectedContainerAccessLevel())).bounds(left + BODY_X + 120, top + BODY_Y + 74, 100, 18).build());
        this.redstonePermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("redstone", selectedRedstoneAccessLevel())).bounds(left + BODY_X + 12, top + BODY_Y + 98, 100, 18).build());
        this.entityUsePermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("entity_use", selectedEntityUseAccessLevel())).bounds(left + BODY_X + 120, top + BODY_Y + 98, 100, 18).build());
        this.entityDamagePermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("entity_damage", selectedEntityDamageAccessLevel())).bounds(left + BODY_X + 12, top + BODY_Y + 122, 208, 18).build());
        this.warButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.war_info"), b -> runCommand("nation war info")).bounds(left + BODY_X + 12, top + BODY_Y + 164, 184, 18).build());
        this.warTargetInput = new EditBox(this.font, left + BODY_X + 12, top + BODY_Y + 192, 280, 18, Component.translatable("screen.sailboatmod.nation.war.target_input"));
        this.warTargetInput.setMaxLength(48);
        this.addRenderableWidget(this.warTargetInput);
        this.declareWarButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.declare_war"), b -> submitDeclareWar()).bounds(left + BODY_X + 300, top + BODY_Y + 192, 120, 18).build());

        this.dipAllyButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.ally"), b -> submitDipAction(NationGuiActionPacket.Action.DIPLOMACY_ALLY)).bounds(left + BODY_X + 234, top + BODY_Y + 40, 100, 18).build());
        this.dipTradeButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.trade"), b -> submitDipAction(NationGuiActionPacket.Action.DIPLOMACY_TRADE)).bounds(left + BODY_X + 340, top + BODY_Y + 40, 100, 18).build());
        this.dipEnemyButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.diplomacy.enemy"), b -> submitDipAction(NationGuiActionPacket.Action.DIPLOMACY_ENEMY)).bounds(left + BODY_X + 234, top + BODY_Y + 64, 100, 18).build());
        this.dipNeutralButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.neutral"), b -> submitDipAction(NationGuiActionPacket.Action.DIPLOMACY_NEUTRAL)).bounds(left + BODY_X + 340, top + BODY_Y + 64, 100, 18).build());
        this.dipDeclareWarButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.declare_war"), b -> submitDipAction(NationGuiActionPacket.Action.DECLARE_WAR)).bounds(left + BODY_X + 234, top + BODY_Y + 88, 206, 18).build());
        this.dipAcceptAllyButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.accept"), b -> submitDipAction(NationGuiActionPacket.Action.DIPLOMACY_ACCEPT)).bounds(left + BODY_X + 234, top + BODY_Y + 112, 100, 18).build());
        this.dipRejectAllyButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.reject"), b -> submitDipAction(NationGuiActionPacket.Action.DIPLOMACY_REJECT)).bounds(left + BODY_X + 340, top + BODY_Y + 112, 100, 18).build());
        this.dipBackButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.diplomacy.back"), b -> { this.selectedDiplomacyNationId = ""; updateButtonState(); }).bounds(left + BODY_X + 234, top + BODY_Y + 136, 206, 18).build());
        this.dipOpenTradeButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.trade.open"), b -> {
            ModNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.SERVER.noArg(), new NationGuiActionPacket(NationGuiActionPacket.Action.OPEN_TRADE_SCREEN, this.selectedDiplomacyNationId, true));
        }).bounds(left + BODY_X + 234, top + BODY_Y + 160, 206, 18).build());

        this.nationNameInput = new EditBox(this.font, left + BODY_X + 12, top + BODY_Y + 148, 196, 18, Component.translatable("screen.sailboatmod.nation.name"));
        this.nationNameInput.setMaxLength(24);
        this.addRenderableWidget(this.nationNameInput);
        this.shortNameInput = new EditBox(this.font, left + BODY_X + 216, top + BODY_Y + 148, 76, 18, Component.translatable("screen.sailboatmod.nation.short_name"));
        this.shortNameInput.setMaxLength(12);
        this.addRenderableWidget(this.shortNameInput);
        this.saveNationInfoButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.save_info"), b -> submitNationInfoUpdate()).bounds(left + BODY_X + 300, top + BODY_Y + 148, 110, 18).build());
        this.createNationButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.create"), b -> submitCreateNation()).bounds(left + BODY_X + 300, top + BODY_Y + 148, 110, 18).build());
        this.joinNationInput = new EditBox(this.font, left + BODY_X + 12, top + BODY_Y + 176, 280, 18, Component.translatable("screen.sailboatmod.nation.overview.join_input"));
        this.joinNationInput.setMaxLength(48);
        this.addRenderableWidget(this.joinNationInput);
        this.joinNationButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.join"), b -> submitJoinNation()).bounds(left + BODY_X + 300, top + BODY_Y + 176, 110, 18).build());
        this.nationHelpButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.help"), b -> runCommand("nation help")).bounds(left + BODY_X + 300, top + BODY_Y + 204, 110, 18).build());
        this.openCapitalTownButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.open_town"), b -> openCapitalTown()).bounds(left + BODY_X + 300, top + BODY_Y + 204, 110, 18).build());
        this.removeCoreButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.remove_core"), b -> submitRemoveCore()).bounds(left + BODY_X + 300, top + BODY_Y + 226, 110, 18).build());
        this.previousTownButton = this.addRenderableWidget(Button.builder(Component.literal("<"), b -> previousTownSelection()).bounds(left + BODY_X + 300, top + BODY_Y + 176, 52, 18).build());
        this.nextTownButton = this.addRenderableWidget(Button.builder(Component.literal(">"), b -> nextTownSelection()).bounds(left + BODY_X + 358, top + BODY_Y + 176, 52, 18).build());

        this.primaryColorInput = new EditBox(this.font, left + BODY_X + 170, top + BODY_Y + 126, 96, 18, Component.translatable("screen.sailboatmod.nation.color.primary"));
        this.primaryColorInput.setMaxLength(7);
        this.addRenderableWidget(this.primaryColorInput);
        this.secondaryColorInput = new EditBox(this.font, left + BODY_X + 170, top + BODY_Y + 154, 96, 18, Component.translatable("screen.sailboatmod.nation.color.secondary"));
        this.secondaryColorInput.setMaxLength(7);
        this.addRenderableWidget(this.secondaryColorInput);
        this.applyColorsButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.apply_colors"), b -> submitColorUpdate()).bounds(left + BODY_X + 278, top + BODY_Y + 139, 120, 18).build());

        this.flagPathInput = new EditBox(this.font, left + BODY_X + 12, top + BODY_Y + BODY_H - 54, 248, 18, Component.translatable("screen.sailboatmod.nation.flag_path"));
        this.flagPathInput.setMaxLength(512);
        this.addRenderableWidget(this.flagPathInput);
        this.browseButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.browse"), b -> browseForImage()).bounds(left + BODY_X + 268, top + BODY_Y + BODY_H - 54, 56, 18).build());
        this.uploadButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.upload"), b -> submitUpload()).bounds(left + BODY_X + 330, top + BODY_Y + BODY_H - 54, 72, 18).build());
        this.toggleMirrorButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.toggle_mirror"), b -> submitMirrorToggle()).bounds(left + BODY_X + 170, top + BODY_Y + 82, 126, 18).build());

        // Treasury tax buttons
        this.salesTaxUpButton = this.addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustSalesTax(100)).bounds(left + BODY_X + 200, top + BODY_Y + 72, 20, 14).build());
        this.salesTaxDownButton = this.addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustSalesTax(-100)).bounds(left + BODY_X + 224, top + BODY_Y + 72, 20, 14).build());
        this.tariffUpButton = this.addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustTariff(100)).bounds(left + BODY_X + 200, top + BODY_Y + 90, 20, 14).build());
        this.tariffDownButton = this.addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustTariff(-100)).bounds(left + BODY_X + 224, top + BODY_Y + 90, 20, 14).build());
        this.treasuryCommandsButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.treasury.commands"), b -> this.showTreasuryCommands = !this.showTreasuryCommands).bounds(left + BODY_X + BODY_W - 90, top + BODY_Y + 68, 78, 18).build());

        // Claim radius buttons
        this.resetMapButton = this.addRenderableWidget(Button.builder(Component.literal("⌖"), b -> resetMapOffset()).bounds(left + BODY_X + BODY_W - CLAIM_MAP_W - 16, top + BODY_Y + 10, 24, 14).build());

        // Peace proposal buttons
        this.proposeCeasefireButton = this.addRenderableWidget(Button.builder(Component.translatable("command.sailboatmod.nation.peace.type.ceasefire"), b -> submitPeaceProposal("ceasefire", 0, 0)).bounds(left + BODY_X + 12, top + BODY_Y + 148, 90, 18).build());
        this.proposeCedeButton = this.addRenderableWidget(Button.builder(Component.translatable("command.sailboatmod.nation.peace.type.cede_territory"), b -> submitPeaceProposal("cede_territory", 5, 0)).bounds(left + BODY_X + 108, top + BODY_Y + 148, 90, 18).build());
        this.proposeReparationButton = this.addRenderableWidget(Button.builder(Component.translatable("command.sailboatmod.nation.peace.type.reparation"), b -> submitPeaceProposal("reparation", 0, 500)).bounds(left + BODY_X + 204, top + BODY_Y + 148, 90, 18).build());
        this.acceptPeaceButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.accept"), b -> { sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.ACCEPT_PEACE), Component.translatable("command.sailboatmod.nation.peace.accepted_self")); }).bounds(left + BODY_X + 12, top + BODY_Y + 148, 90, 18).build());
        this.rejectPeaceButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.reject"), b -> { sendNationAction(new NationGuiActionPacket(NationGuiActionPacket.Action.REJECT_PEACE), Component.translatable("command.sailboatmod.nation.peace.rejected")); }).bounds(left + BODY_X + 108, top + BODY_Y + 148, 90, 18).build());

        this.officerTitleInput = new EditBox(this.font, left + BODY_X + 224, top + BODY_Y + 152, 136, 18, Component.translatable("screen.sailboatmod.nation.members.title_input"));
        this.officerTitleInput.setMaxLength(24);
        this.addRenderableWidget(this.officerTitleInput);
        this.saveOfficerTitleButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.save_title"), b -> submitOfficerTitleUpdate()).bounds(left + BODY_X + 366, top + BODY_Y + 152, 64, 18).build());
        this.appointOfficerButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.appoint_officer"), b -> appointSelectedMember()).bounds(left + BODY_X + 224, top + BODY_Y + 182, 98, 18).build());
        this.removeOfficerButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.remove_officer"), b -> removeSelectedOfficer()).bounds(left + BODY_X + 332, top + BODY_Y + 182, 98, 18).build());
        this.appointMayorButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.appoint_mayor"), b -> appointSelectedMayor()).bounds(left + BODY_X + 224, top + BODY_Y + 206, 206, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_name.cancel"), b -> onClose()).bounds(left + SCREEN_W - 82, top + SCREEN_H - 24, 70, 18).build());

        syncNationInfoInputs();
        syncColorInputs();
        syncOfficerTitleInput();
        this.setInitialFocus(this.nationNameInput != null ? this.nationNameInput : this.primaryColorInput);
        updateButtonState();
    }

    private Button addTabButton(int x, int y, Page page, Component label) {
        return this.addRenderableWidget(Button.builder(label, b -> switchPage(page)).bounds(x, y, TAB_W, 18).build());
    }

    @Override
    public void removed() {
        super.removed();
        com.monpai.sailboatmod.client.NationClientHooks.onScreenClosed();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.nationNameInput != null) this.nationNameInput.tick();
        if (this.shortNameInput != null) this.shortNameInput.tick();
        if (this.primaryColorInput != null) this.primaryColorInput.tick();
        if (this.secondaryColorInput != null) this.secondaryColorInput.tick();
        if (this.flagPathInput != null) this.flagPathInput.tick();
        if (this.warTargetInput != null) this.warTargetInput.tick();
        if (this.officerTitleInput != null) this.officerTitleInput.tick();
        tickAutoRefresh();
        updateButtonState();
    }

    private void tickAutoRefresh() {
        if (!this.data.hasNation() || this.refreshPending) {
            this.autoRefreshTicks = 0;
            return;
        }
        this.autoRefreshTicks++;
        int interval = this.currentPage == Page.CLAIMS && hasIncompletePreviewTerrain()
                ? 8
                : (this.data.hasActiveWar() ? ACTIVE_WAR_REFRESH_INTERVAL_TICKS : AUTO_REFRESH_INTERVAL_TICKS);
        if (this.autoRefreshTicks < interval) {
            return;
        }
        this.autoRefreshTicks = 0;
        requestRefresh();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            if (this.currentPage == Page.OVERVIEW) {
                if (!this.data.hasNation()) {
                    if (this.nationNameInput != null && this.nationNameInput.isFocused()) return submitAndTrue(this::submitCreateNation);
                    if (this.joinNationInput != null && this.joinNationInput.isFocused()) return submitAndTrue(this::submitJoinNation);
                } else {
                    if (this.nationNameInput != null && this.nationNameInput.isFocused()) return submitAndTrue(this::submitNationInfoUpdate);
                    if (this.shortNameInput != null && this.shortNameInput.isFocused()) return submitAndTrue(this::submitNationInfoUpdate);
                }
            }
            if (this.currentPage == Page.FLAG) {
                if (this.primaryColorInput != null && this.primaryColorInput.isFocused()) return submitAndTrue(this::submitColorUpdate);
                if (this.secondaryColorInput != null && this.secondaryColorInput.isFocused()) return submitAndTrue(this::submitColorUpdate);
                if (this.flagPathInput != null && this.flagPathInput.isFocused()) return submitAndTrue(this::submitUpload);
            }
            if (this.currentPage == Page.WAR && this.warTargetInput != null && this.warTargetInput.isFocused()) return submitAndTrue(this::submitDeclareWar);
            if (this.currentPage == Page.MEMBERS && this.officerTitleInput != null && this.officerTitleInput.isFocused()) return submitAndTrue(this::submitOfficerTitleUpdate);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.currentPage == Page.MEMBERS) {
            int[] b = memberListBounds();
            if (mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3]) {
                this.memberScroll = clampMemberScroll(this.memberScroll + (delta > 0 ? -1 : 1));
                return true;
            }
        }
        if (this.currentPage == Page.TREASURY) {
            int itemListX = left() + BODY_X + 20;
            int itemListY = top() + BODY_Y + 140;
            int itemListW = BODY_W - 24;
            int itemListH = TREASURY_ITEM_VISIBLE_ROWS * TREASURY_ITEM_ROW_H;
            if (mouseX >= itemListX && mouseX < itemListX + itemListW && mouseY >= itemListY && mouseY < itemListY + itemListH) {
                this.treasuryItemScroll = Math.max(0, this.treasuryItemScroll + (delta > 0 ? -1 : 1));
                return true;
            }
        }
        if (this.currentPage == Page.DIPLOMACY) {
            int listX = left() + BODY_X + 8;
            int listY = top() + BODY_Y + 28;
            int listW = 220;
            int listH = DIP_VISIBLE_ROWS * DIP_ROW_H;
            if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
                int maxScroll = Math.max(0, this.data.allNations().size() - DIP_VISIBLE_ROWS);
                this.diplomacyScroll = Math.max(0, Math.min(maxScroll, this.diplomacyScroll + (delta > 0 ? -1 : 1)));
                return true;
            }
        }
        if (isInsideBodyViewport(mouseX, mouseY) && maxPageScroll() > 0) {
            this.pageScroll = Math.max(0, Math.min(maxPageScroll(), this.pageScroll + (delta > 0 ? -16 : 16)));
            updateButtonState();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.currentPage == Page.MEMBERS && trySelectMember(mouseX, mouseY)) return true;
        if (button == 0 && this.currentPage == Page.CLAIMS && trySelectClaim(mouseX, mouseY)) return true;
        if (button == 0 && this.currentPage == Page.DIPLOMACY && trySelectDiplomacyNation(mouseX, mouseY)) return true;
        if (button == 2 && this.currentPage == Page.CLAIMS) {
            int left = left();
            int top = top();
            int mapX = left + BODY_X + BODY_W - CLAIM_MAP_W - 8;
            int mapY = top + BODY_Y + 28;
            if (mouseX >= mapX && mouseX < mapX + CLAIM_MAP_W && mouseY >= mapY && mouseY < mapY + CLAIM_MAP_H) {
                this.isDraggingMap = true;
                this.dragStartX = mouseX;
                this.dragStartY = mouseY;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 2 && this.isDraggingMap) {
            this.isDraggingMap = false;
            requestRefresh(mapCenterX(), mapCenterZ());
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 2 && this.isDraggingMap && this.currentPage == Page.CLAIMS) {
            int diameter = claimRadius() * 2 + 1;
            double cellW = (double) CLAIM_MAP_W / diameter;
            double cellH = (double) CLAIM_MAP_H / diameter;
            double dx = mouseX - this.dragStartX;
            double dy = mouseY - this.dragStartY;
            if (Math.abs(dx) >= cellW) {
                int chunks = (int) (dx / cellW);
                this.mapOffsetX -= chunks;
                this.dragStartX += chunks * cellW;
            }
            if (Math.abs(dy) >= cellH) {
                int chunks = (int) (dy / cellH);
                this.mapOffsetZ -= chunks;
                this.dragStartY += chunks * cellH;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        drawContents(guiGraphics, mouseX, mouseY);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void drawContents(GuiGraphics g, int mouseX, int mouseY) {
        int left = left();
        int top = top();
        g.fill(left, top, left + SCREEN_W, top + SCREEN_H, 0xCC101820);
        g.fill(left + 1, top + 1, left + SCREEN_W - 1, top + SCREEN_H - 1, 0xCC182632);
        g.drawString(this.font, this.title, left + 12, top + 18, 0xFFE7C977);
        String economyBar = buildEconomyHeaderLine();
        int reserveRight = 96;
        int economyX = left + SCREEN_W - reserveRight - this.font.width(economyBar);
        int headerWidth = Math.max(48, economyX - (left + 132) - 8);
        g.drawString(this.font, trimToWidth(headerText(), headerWidth), left + 132, top + 18, 0xFFDCEEFF);
        if (!economyBar.isBlank()) {
            g.drawString(this.font, economyBar, economyX, top + 18, 0xFFB8C0C8);
        }
        drawPanelFrame(g, left + BODY_X, top + BODY_Y, BODY_W, BODY_H);
        g.drawString(this.font, currentPage.title(), left + BODY_X + 10, top + BODY_Y + 10, 0xFFE7C977);
        g.enableScissor(left + BODY_X + 1, bodyViewportTop(), left + BODY_X + BODY_W - 1, top + BODY_Y + BODY_H - 1);
        g.pose().pushPose();
        g.pose().translate(0.0F, -this.pageScroll, 0.0F);
        switch (this.currentPage) {
            case OVERVIEW -> drawOverviewPage(g, left + BODY_X, top + BODY_Y);
            case MEMBERS -> drawMembersPage(g, left + BODY_X, top + BODY_Y);
            case CLAIMS -> drawClaimsPage(g, left + BODY_X, top + BODY_Y, mouseX, mouseY);
            case WAR -> drawWarPage(g, left + BODY_X, top + BODY_Y);
            case DIPLOMACY -> drawDiplomacyPage(g, left + BODY_X, top + BODY_Y);
            case TREASURY -> drawTreasuryPage(g, left + BODY_X, top + BODY_Y);
            case FLAG -> drawFlagPage(g, left + BODY_X, top + BODY_Y);
        }
        g.pose().popPose();
        g.disableScissor();
        if (!this.statusLine.getString().isBlank()) g.drawCenteredString(this.font, this.statusLine, left + SCREEN_W / 2, top + SCREEN_H - 12, 0xFFF1D98A);
    }

    private void drawOverviewPage(GuiGraphics g, int x, int y) {
        int drawY = y + 34;
        for (Component line : buildOverviewLines()) {
            drawWrappedLine(g, line, x + 12, drawY, BODY_W - 24, 0xFFDCEEFF);
            drawY += wrappedHeight(line, BODY_W - 24) + 6;
        }
        if (!this.data.hasNation()) {
            int formY = Math.max(y + 120, drawY + 8);
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.overview.create_label"), x + 12, formY, 0xFFB8C0C8);
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.overview.join_label"), x + 12, formY + 48, 0xFFB8C0C8);
            drawWrappedLine(g, Component.translatable("screen.sailboatmod.nation.overview.help_hint"), x + 12, formY + 94, 272, 0xFF8D98A3);
            return;
        }
        int formY = Math.max(y + 118, drawY + 8);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.name"), x + 12, formY, 0xFFB8C0C8);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.short_name"), x + 216, formY, 0xFFB8C0C8);
        NationOverviewTown selectedTown = selectedTown();
        int townInfoY = formY + 104;
        if (selectedTown != null) {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.overview.town_selected", shortText(selectedTown.townName(), 18)), x + 12, townInfoY, 0xFFDCEEFF);
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.overview.town_mayor", shortText(selectedTown.mayorName(), 18)), x + 12, townInfoY + 16, 0xFFB8C0C8);
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.overview.town_claims", selectedTown.claimCount(), selectedTown.capital() ? Component.translatable("screen.sailboatmod.nation.overview.town_capital").getString() : ""), x + 12, townInfoY + 32, 0xFF8D98A3);
        } else {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.overview.town_none"), x + 12, townInfoY + 8, 0xFF8D98A3);
        }
    }

    private void drawMembersPage(GuiGraphics g, int x, int y) {
        if (!this.data.hasNation()) {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.members.none"), x + 12, y + 36, 0xFF8D98A3);
            return;
        }
        int[] list = memberListBounds();
        drawPanelFrame(g, list[0], list[1], list[2], list[3]);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.overview.members", this.data.memberCount()), list[0] + 6, list[1] - 14, 0xFFB8C0C8);
        if (this.data.members().isEmpty()) {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.members.empty"), list[0] + 8, list[1] + 8, 0xFF8D98A3);
        } else {
            int start = clampMemberScroll(this.memberScroll);
            int end = Math.min(this.data.members().size(), start + MEMBER_VISIBLE_ROWS);
            int drawY = list[1] + 4;
            for (int i = start; i < end; i++) {
                NationOverviewMember member = this.data.members().get(i);
                boolean selected = member.playerUuid().equals(this.selectedMemberUuid);
                int rowColor = selected ? 0x66487EA1 : (((i & 1) == 0) ? 0x331F2D39 : 0x33293A48);
                g.fill(list[0] + 2, drawY, list[0] + list[2] - 2, drawY + MEMBER_ROW_H - 1, rowColor);
                g.fill(list[0] + 6, drawY + 4, list[0] + 10, drawY + 8, member.online() ? 0xFF56DDB4 : 0xFF71808F);
                g.drawString(this.font, Component.literal(trimToWidth(member.playerName(), 108)), list[0] + 16, drawY + 3, member.online() ? 0xFFE6FFF6 : 0xFFD2D7DC);
                g.drawString(this.font, Component.literal(trimToWidth(member.officeName(), 56)), list[0] + 118, drawY + 3, 0xFFE7C977);
                drawY += MEMBER_ROW_H;
            }
        }
        NationOverviewMember selected = selectedMember();
        int infoX = x + 218;
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.members.selected"), infoX, y + 40, 0xFFB8C0C8);
        if (selected == null) {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.members.no_selection"), infoX, y + 58, 0xFF8D98A3);
            return;
        }
        g.drawString(this.font, Component.literal(trimToWidth(selected.playerName(), 170)), infoX, y + 58, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.overview.role", selected.officeName()), infoX, y + 76, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable(selected.online() ? "screen.sailboatmod.nation.members.status.online" : "screen.sailboatmod.nation.members.status.offline"), infoX, y + 94, selected.online() ? 0xFF56DDB4 : 0xFF8D98A3);
        g.drawString(this.font, Component.literal(shortText(selected.playerUuid(), 28)), infoX, y + 112, 0xFF8D98A3);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.members.title_label"), infoX, y + 140, 0xFFB8C0C8);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.members.manage_hint"), infoX, y + 228, 0xFF8D98A3);
    }

    private void drawClaimsPage(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        if (this.claimsSubPage == 1) {
            drawClaimsPermPage(g, x, y);
            return;
        }
        int drawY = y + 34;
        for (Component line : buildClaimLines()) {
            drawWrappedLine(g, line, x + 12, drawY, 206, 0xFFDCEEFF);
            drawY += wrappedHeight(line, 206) + 6;
        }
        int mapX = claimMapX(x);
        int mapY = claimMapY(y);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.claims.map_title"), mapX, y + 12, 0xFFB8C0C8);
        drawClaimMap(g, mapX, mapY, mouseX, mouseY);
    }

    private void drawClaimsPermPage(GuiGraphics g, int x, int y) {
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.claims.perms_title"), x + 12, y + 30, 0xFFB8C0C8);
        NationOverviewClaim selected = selectedClaim();
        if (selected == null) {
            drawWrappedLine(g, Component.translatable("screen.sailboatmod.nation.claims.perms_select_hint"), x + 12, y + 150, BODY_W - 24, 0xFF8D98A3);
        } else {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.claims.selected_chunk", this.selectedClaimChunkX, this.selectedClaimChunkZ), x + 12, y + 150, 0xFFDCEEFF);
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.claims.owner", selected.nationName()), x + 12, y + 166, 0xFFB8C0C8);
        }
    }

    private void drawWarPage(GuiGraphics g, int x, int y) {
        int warPanelX = x + 8;
        int warPanelY = y + 28;
        int warPanelW = BODY_W - 16;
        int warPanelH = 126;
        drawPanelFrame(g, warPanelX, warPanelY, warPanelW, warPanelH);

        int drawY = warPanelY + 10;
        for (Component line : buildWarLines()) {
            drawWrappedLine(g, line, warPanelX + 8, drawY, warPanelW - 16, 0xFFDCEEFF);
            drawY += wrappedHeight(line, warPanelW - 16) + 6;
        }
        if (!this.data.hasNation()) {
            return;
        }

        if (this.data.hasPeaceProposal()) {
            int proposalY = y + 160;
            drawPanelFrame(g, warPanelX, proposalY, warPanelW, 60);
            String typeKey = "command.sailboatmod.nation.peace.type." + this.data.peaceProposalType();
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.peace.proposal_title"), warPanelX + 8, proposalY + 6, 0xFFE7C977);
            g.drawString(this.font, Component.translatable(typeKey), warPanelX + 8, proposalY + 20, 0xFFDCEEFF);
            String details = "";
            if (this.data.peaceProposalCede() > 0) details += Component.translatable("screen.sailboatmod.nation.peace.cede_count", this.data.peaceProposalCede()).getString() + "  ";
            if (this.data.peaceProposalAmount() > 0) details += Component.translatable("screen.sailboatmod.nation.peace.reparation_amount", this.data.peaceProposalAmount()).getString();
            if (!details.isBlank()) g.drawString(this.font, details, warPanelX + 8, proposalY + 34, 0xFFB8C0C8);
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.peace.remaining", this.data.peaceProposalRemainingSeconds()), warPanelX + warPanelW - 80, proposalY + 6, 0xFF8D98A3);
        }

        Component hint = this.data.canDeclareWar()
                ? Component.translatable("screen.sailboatmod.nation.war.declare_hint")
                : Component.translatable("command.sailboatmod.nation.diplomacy.no_permission");
        drawWrappedLine(g, hint, x + 12, y + 196, BODY_W - 24, 0xFF8D98A3);
    }

    private void drawDiplomacyPage(GuiGraphics g, int x, int y) {
        if (!this.data.hasNation()) {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.diplomacy.no_nation"), x + 12, y + 36, 0xFF8D98A3);
            return;
        }
        List<NationOverviewNationEntry> nations = this.data.allNations();
        if (nations.isEmpty()) {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.diplomacy.no_nations"), x + 12, y + 36, 0xFF8D98A3);
            return;
        }

        int listX = x + 8;
        int listY = y + 28;
        int listW = 220;
        int listH = DIP_VISIBLE_ROWS * DIP_ROW_H;
        drawPanelFrame(g, listX, listY, listW, listH);

        int start = Math.max(0, Math.min(this.diplomacyScroll, nations.size() - DIP_VISIBLE_ROWS));
        int end = Math.min(nations.size(), start + DIP_VISIBLE_ROWS);
        for (int i = start; i < end; i++) {
            NationOverviewNationEntry entry = nations.get(i);
            int rowY = listY + (i - start) * DIP_ROW_H;
            boolean selected = entry.nationId().equals(this.selectedDiplomacyNationId);
            if (selected) {
                g.fill(listX + 1, rowY, listX + listW - 1, rowY + DIP_ROW_H, 0x44FFD700);
            }

            int flagX = listX + 4;
            int flagY = rowY + 2;
            int flagW = 16;
            int flagH = 16;
            if (!entry.flagId().isBlank()) {
                ResourceLocation flagTex = NationFlagTextureCache.resolve(entry.flagId(), entry.primaryColorRgb(), entry.secondaryColorRgb(), entry.flagMirrored());
                g.blit(flagTex, flagX, flagY, 0, 0, flagW, flagH, flagW, flagH);
            } else {
                g.fill(flagX, flagY, flagX + flagW, flagY + flagH, 0xFF000000 | entry.primaryColorRgb());
            }

            String name = shortText(entry.nationName(), 14);
            g.drawString(this.font, name, listX + 24, rowY + 5, 0xFFDCEEFF);

            String statusLabel = entry.diplomacyStatusId().isBlank() ? "-" : entry.diplomacyStatusId();
            int statusColor = diplomacyStatusColor(entry.diplomacyStatusId());
            g.drawString(this.font, statusLabel, listX + listW - this.font.width(statusLabel) - 6, rowY + 5, statusColor);
        }

        if (!this.selectedDiplomacyNationId.isBlank()) {
            NationOverviewNationEntry selected = selectedDiplomacyNation();
            if (selected != null) {
                int detailX = x + 236;
                int detailY = y + 28;
                g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.diplomacy.selected", selected.nationName()), detailX, detailY, 0xFFE7C977);
                String status = selected.diplomacyStatusId().isBlank() ? "neutral" : selected.diplomacyStatusId();
                g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.diplomacy.status", status), detailX, detailY + 14, 0xFFB8C0C8);
            }
        } else {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.diplomacy.select_hint"), x + 236, y + 40, 0xFF8D98A3);
        }

        drawDiplomacySection(g,
                Component.translatable("screen.sailboatmod.nation.war.requests_title"),
                buildIncomingRequestLines(),
                x + 236, y + 164, 200, 3);
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

    private static int diplomacyStatusColor(String statusId) {
        if (statusId == null || statusId.isBlank()) return 0xFF8D98A3;
        return switch (statusId) {
            case "allied" -> 0xFF55FF55;
            case "trade" -> 0xFF55FFFF;
            case "enemy" -> 0xFFFF5555;
            default -> 0xFF8D98A3;
        };
    }

    private void drawTreasuryPage(GuiGraphics g, int x, int y) {
        int cardX = x + 12;
        int cardY = y + 32;
        int cardGap = 6;
        int cardW = (BODY_W - 24 - cardGap * 3) / 4;
        int cardH = 22;
        drawMetricCard(g, cardX, cardY, cardW, cardH,
                Component.translatable("screen.sailboatmod.nation.treasury.metric.balance"),
                formatCompactMoney(this.data.treasuryBalance()),
                0xC08E7448);
        drawMetricCard(g, cardX + (cardW + cardGap), cardY, cardW, cardH,
                Component.translatable("screen.sailboatmod.nation.treasury.metric.towns"),
                Integer.toString(this.data.towns().size()),
                0xC05C7F90);
        drawMetricCard(g, cardX + (cardW + cardGap) * 2, cardY, cardW, cardH,
                Component.translatable("screen.sailboatmod.nation.treasury.metric.trades"),
                Integer.toString(this.data.recentTradeCount()),
                0xC07F6750);
        drawMetricCard(g, cardX + (cardW + cardGap) * 3, cardY, cardW, cardH,
                Component.translatable("screen.sailboatmod.nation.treasury.metric.items"),
                occupiedTreasurySlots() + " / " + this.data.treasuryItems().size(),
                0xC0567164);

        int policyX = x + 12;
        int policyY = y + 62;
        int policyW = 236;
        int policyH = 58;
        drawPanelFrame(g, policyX, policyY, policyW, policyH);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.treasury.policy_title"), policyX + 8, policyY + 8, 0xFFE7C977);
        drawPolicyBar(g, policyX + 8, y + 72,
                Component.translatable("screen.sailboatmod.nation.treasury.sales_tax_label"),
                this.data.salesTaxBasisPoints(),
                3000,
                0xFF7AA2C6);
        drawPolicyBar(g, policyX + 8, y + 90,
                Component.translatable("screen.sailboatmod.nation.treasury.import_tariff_label"),
                this.data.importTariffBasisPoints(),
                5000,
                0xFFC69263);

        int summaryX = x + 256;
        int summaryY = y + 62;
        int summaryW = BODY_W - 24 - policyW - 8;
        int summaryH = 58;
        drawPanelFrame(g, summaryX, summaryY, summaryW, summaryH);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.treasury.inventory_title"), summaryX + 8, summaryY + 8, 0xFFE7C977);
        g.drawString(this.font, Component.literal(Component.translatable("screen.sailboatmod.nation.treasury.trade_count_label").getString() + " " + this.data.recentTradeCount()), summaryX + 8, summaryY + 22, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.treasury.item_slots", occupiedTreasurySlots(), this.data.treasuryItems().size()), summaryX + 8, summaryY + 34, 0xFFDCEEFF);
        g.drawString(this.font, Component.literal(trimToWidth(Component.translatable("screen.sailboatmod.nation.treasury.bank_access_hint").getString(), summaryW - 16)), summaryX + 8, summaryY + 46, 0xFF8D98A3);

        int itemPanelX = x + 12;
        int itemPanelY = y + 128;
        int itemPanelW = BODY_W - 24;
        int itemPanelH = 88;
        drawPanelFrame(g, itemPanelX, itemPanelY, itemPanelW, itemPanelH);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.treasury.items_label"), itemPanelX + 8, itemPanelY + 8, 0xFFE7C977);

        int itemListX = itemPanelX + 8;
        int itemListY = itemPanelY + 12;
        int itemListW = itemPanelW - 16;
        g.fill(itemListX, itemListY, itemListX + itemListW, itemListY + TREASURY_ITEM_VISIBLE_ROWS * TREASURY_ITEM_ROW_H, 0x44000000);

        int startIdx = Math.max(0, this.treasuryItemScroll);
        int drawn = 0;
        for (int i = 0; i < this.data.treasuryItems().size() && drawn < TREASURY_ITEM_VISIBLE_ROWS; i++) {
            net.minecraft.world.item.ItemStack stack = this.data.treasuryItems().get(i);
            if (stack.isEmpty()) continue;
            if (startIdx > 0) {
                startIdx--;
                continue;
            }
            int rowY = itemListY + drawn * TREASURY_ITEM_ROW_H + 2;
            int rowColor = (drawn & 1) == 0 ? 0x331F2D39 : 0x33293A48;
            g.fill(itemListX + 2, rowY - 1, itemListX + itemListW - 2, rowY + TREASURY_ITEM_ROW_H - 3, rowColor);
            g.renderItem(stack, itemListX + 4, rowY);
            g.drawString(this.font, trimToWidth(stack.getHoverName().getString(), itemListW - 72), itemListX + 24, rowY + 4, 0xFFDCEEFF);
            g.drawString(this.font, "x" + stack.getCount(), itemListX + itemListW - 30, rowY + 4, 0xFFE7C977);
            drawn++;
        }
        if (drawn == 0) {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.market.empty"), itemListX + 4, itemListY + 6, 0xFF8D98A3);
        }

        int hintY = itemPanelY + itemPanelH + 4;
        drawWrappedLine(g, Component.translatable("screen.sailboatmod.nation.treasury.detail_hint"), x + 12, hintY, BODY_W - 24, 0xFF8D98A3);
        if (this.showTreasuryCommands) {
            drawWrappedLine(g,
                    Component.translatable(this.data.canManageTreasury()
                            ? "screen.sailboatmod.nation.treasury.commands_hint"
                            : "screen.sailboatmod.nation.treasury.bank_hint"),
                    x + 12,
                    hintY + 12,
                    BODY_W - 24,
                    0xFF8D98A3);
        } else {
            drawWrappedLine(g, Component.translatable("screen.sailboatmod.nation.treasury.policy_hint"), x + 12, hintY + 12, BODY_W - 24, 0xFF8D98A3);
        }
    }

    private void drawFlagPage(GuiGraphics g, int x, int y) {
        List<Component> lines = buildFlagLines();
        ResourceLocation texture = NationFlagTextureCache.resolve(this.data.flagId(), previewPrimaryColor(), previewSecondaryColor(), this.data.flagMirrored());
        int frameX = x + 20;
        int frameY = y + 42;
        int frameW = 124;
        int frameH = 64;
        int textureWidth = this.data.flagWidth() <= 0 ? 64 : this.data.flagWidth();
        int textureHeight = this.data.flagHeight() <= 0 ? 32 : this.data.flagHeight();
        g.fill(x + 12, y + 36, x + 152, y + 112, 0xFF36424C);
        g.enableScissor(frameX, frameY, frameX + frameW, frameY + frameH);
        int previewX = frameX + (frameW - textureWidth) / 2;
        int previewY = frameY + (frameH - textureHeight) / 2;
        g.blit(texture, previewX, previewY, 0, 0, textureWidth, textureHeight, textureWidth, textureHeight);
        g.disableScissor();
        int textX = x + 170;
        g.drawString(this.font, firstLine(lines), textX, y + 40, 0xFFDCEEFF);
        g.drawString(this.font, secondLine(lines), textX, y + 54, 0xFFDCEEFF);
        g.drawString(this.font, thirdLine(lines), textX, y + 68, 0xFFB8C0C8);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.color.primary"), textX, y + 112, 0xFFB8C0C8);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.color.secondary"), textX, y + 140, 0xFFB8C0C8);
        drawColorSwatch(g, x + 134, y + 114, previewPrimaryColor(), parseHexColor(valueOf(this.primaryColorInput)) != null);
        drawColorSwatch(g, x + 134, y + 142, previewSecondaryColor(), parseHexColor(valueOf(this.secondaryColorInput)) != null);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.flag.upload_hint_short"), x + 12, y + BODY_H - 82, 0xFFB8C0C8);
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

    private void updateButtonState() {
        if (this.overviewTabButton != null) this.overviewTabButton.active = this.currentPage != Page.OVERVIEW;
        if (this.membersTabButton != null) this.membersTabButton.active = this.currentPage != Page.MEMBERS;
        if (this.claimsTabButton != null) this.claimsTabButton.active = this.currentPage != Page.CLAIMS;
        if (this.warTabButton != null) this.warTabButton.active = this.currentPage != Page.WAR;
        if (this.flagTabButton != null) this.flagTabButton.active = this.currentPage != Page.FLAG;
        if (this.diplomacyTabButton != null) this.diplomacyTabButton.active = this.currentPage != Page.DIPLOMACY;
        if (this.treasuryTabButton != null) this.treasuryTabButton.active = this.currentPage != Page.TREASURY;

        boolean overviewPage = this.currentPage == Page.OVERVIEW;
        boolean hasNation = this.data.hasNation();
        boolean canManageInfo = hasNation && this.data.canManageInfo();
        boolean noNationOverview = overviewPage && !hasNation;
        if (this.nationNameInput != null) { this.nationNameInput.visible = overviewPage; this.nationNameInput.setEditable(overviewPage && (!hasNation || canManageInfo)); }
        if (this.shortNameInput != null) { this.shortNameInput.visible = overviewPage && hasNation; this.shortNameInput.setEditable(overviewPage && canManageInfo); }
        if (this.joinNationInput != null) { this.joinNationInput.visible = noNationOverview; this.joinNationInput.setEditable(noNationOverview); }
        if (this.saveNationInfoButton != null) { this.saveNationInfoButton.visible = overviewPage && hasNation; this.saveNationInfoButton.active = overviewPage && canManageInfo && nationInfoChanged(); }
        if (this.createNationButton != null) { this.createNationButton.visible = noNationOverview; this.createNationButton.active = noNationOverview && !valueOf(this.nationNameInput).trim().isBlank(); }
        if (this.joinNationButton != null) { this.joinNationButton.visible = noNationOverview; this.joinNationButton.active = noNationOverview && !valueOf(this.joinNationInput).trim().isBlank(); }
        if (this.nationHelpButton != null) { this.nationHelpButton.visible = noNationOverview; this.nationHelpButton.active = noNationOverview; }
        if (this.openCapitalTownButton != null) { this.openCapitalTownButton.visible = overviewPage && hasNation; this.openCapitalTownButton.active = overviewPage && hasNation && selectedTown() != null; this.openCapitalTownButton.setMessage(Component.translatable("screen.sailboatmod.nation.action.open_town")); }
        if (this.removeCoreButton != null) { this.removeCoreButton.visible = overviewPage && hasNation; this.removeCoreButton.active = overviewPage && hasNation && this.data.hasCore() && this.data.canManageClaims(); }
        if (this.previousTownButton != null) { this.previousTownButton.visible = overviewPage && hasNation; this.previousTownButton.active = overviewPage && hasNation && this.data.towns().size() > 1; }
        if (this.nextTownButton != null) { this.nextTownButton.visible = overviewPage && hasNation; this.nextTownButton.active = overviewPage && hasNation && this.data.towns().size() > 1; }

        boolean membersPage = this.currentPage == Page.MEMBERS;
        boolean leaderControls = this.data.hasNation() && this.data.isLeader();
        if (this.officerTitleInput != null) { this.officerTitleInput.visible = membersPage; this.officerTitleInput.setEditable(membersPage && leaderControls); }
        if (this.saveOfficerTitleButton != null) { this.saveOfficerTitleButton.visible = membersPage; this.saveOfficerTitleButton.active = membersPage && leaderControls && officerTitleChanged(); }
        if (this.appointOfficerButton != null) { this.appointOfficerButton.visible = membersPage; this.appointOfficerButton.active = membersPage && leaderControls && canAppointSelectedMember(); }
        if (this.removeOfficerButton != null) { this.removeOfficerButton.visible = membersPage; this.removeOfficerButton.active = membersPage && leaderControls && canRemoveSelectedOfficer(); }
        if (this.appointMayorButton != null) { this.appointMayorButton.visible = membersPage; this.appointMayorButton.active = membersPage && leaderControls && canAssignSelectedMemberAsMayor(); }

        boolean claimsPage = this.currentPage == Page.CLAIMS;
        boolean claimsMapView = claimsPage && this.claimsSubPage == 0;
        boolean claimsPermView = claimsPage && this.claimsSubPage == 1;
        NationOverviewClaim selectedClaim = selectedClaim();
        boolean ownClaim = selectedClaim != null && this.data.nationId().equals(selectedClaim.nationId());
        if (this.claimButton != null) { this.claimButton.visible = claimsMapView; this.claimButton.active = claimsMapView && this.data.hasNation() && this.data.canManageClaims() && selectedClaim == null; }
        if (this.unclaimButton != null) { this.unclaimButton.visible = claimsMapView; this.unclaimButton.active = claimsMapView && this.data.hasNation() && this.data.canManageClaims() && ownClaim; }
        if (this.claimsSubPageButton != null) { this.claimsSubPageButton.visible = claimsPage; this.claimsSubPageButton.active = claimsPage && this.data.hasNation(); this.claimsSubPageButton.setMessage(Component.translatable(claimsPermView ? "screen.sailboatmod.nation.claims.show_map" : "screen.sailboatmod.nation.claims.show_perms")); }
        if (this.breakPermissionButton != null) { this.breakPermissionButton.visible = claimsPermView; this.breakPermissionButton.active = claimsPermView && this.data.hasNation() && this.data.canManageClaims() && ownClaim; this.breakPermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.break", accessName(selectedBreakAccessLevel()))); }
        if (this.placePermissionButton != null) { this.placePermissionButton.visible = claimsPermView; this.placePermissionButton.active = claimsPermView && this.data.hasNation() && this.data.canManageClaims() && ownClaim; this.placePermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.place", accessName(selectedPlaceAccessLevel()))); }
        if (this.usePermissionButton != null) { this.usePermissionButton.visible = claimsPermView; this.usePermissionButton.active = claimsPermView && this.data.hasNation() && this.data.canManageClaims() && ownClaim; this.usePermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.use", accessName(selectedUseAccessLevel()))); }
        if (this.containerPermissionButton != null) { this.containerPermissionButton.visible = claimsPermView; this.containerPermissionButton.active = claimsPermView && this.data.hasNation() && this.data.canManageClaims() && ownClaim; this.containerPermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.container", accessName(selectedContainerAccessLevel()))); }
        if (this.redstonePermissionButton != null) { this.redstonePermissionButton.visible = claimsPermView; this.redstonePermissionButton.active = claimsPermView && this.data.hasNation() && this.data.canManageClaims() && ownClaim; this.redstonePermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.redstone", accessName(selectedRedstoneAccessLevel()))); }
        if (this.entityUsePermissionButton != null) { this.entityUsePermissionButton.visible = claimsPermView; this.entityUsePermissionButton.active = claimsPermView && this.data.hasNation() && this.data.canManageClaims() && ownClaim; this.entityUsePermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.entity_use", accessName(selectedEntityUseAccessLevel()))); }
        if (this.entityDamagePermissionButton != null) { this.entityDamagePermissionButton.visible = claimsPermView; this.entityDamagePermissionButton.active = claimsPermView && this.data.hasNation() && this.data.canManageClaims() && ownClaim; this.entityDamagePermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.entity_damage", accessName(selectedEntityDamageAccessLevel()))); }

        boolean warPage = this.currentPage == Page.WAR;
        boolean canManageDiplomacy = warPage && this.data.hasNation() && this.data.canDeclareWar();
        String warTarget = valueOf(this.warTargetInput).trim();
        if (this.warTargetInput != null) { this.warTargetInput.visible = warPage; this.warTargetInput.setEditable(canManageDiplomacy); }
        if (this.warButton != null) { this.warButton.visible = warPage; this.warButton.active = this.data.hasNation(); }
        if (this.declareWarButton != null) { this.declareWarButton.visible = warPage; this.declareWarButton.active = canManageDiplomacy && !warTarget.isBlank() && !this.data.hasActiveWar() && this.data.warCooldownRemainingSeconds() <= 0; }

        boolean dipPage = this.currentPage == Page.DIPLOMACY;
        boolean dipHasSelection = !this.selectedDiplomacyNationId.isBlank();
        boolean dipCanManage = dipPage && this.data.hasNation() && this.data.canDeclareWar();
        boolean dipHasPendingAlly = dipPage && dipHasSelection && hasPendingAllianceRequest(this.selectedDiplomacyNationId);
        NationOverviewNationEntry selectedNation = selectedDiplomacyNation();
        boolean dipIsAllied = selectedNation != null && "allied".equals(selectedNation.diplomacyStatusId());
        if (this.dipAllyButton != null) { this.dipAllyButton.visible = dipPage && dipHasSelection; this.dipAllyButton.active = dipCanManage && !dipIsAllied; }
        if (this.dipTradeButton != null) { this.dipTradeButton.visible = dipPage && dipHasSelection; this.dipTradeButton.active = dipCanManage; }
        if (this.dipEnemyButton != null) { this.dipEnemyButton.visible = dipPage && dipHasSelection; this.dipEnemyButton.active = dipCanManage; }
        if (this.dipNeutralButton != null) { this.dipNeutralButton.visible = dipPage && dipHasSelection; this.dipNeutralButton.active = dipCanManage; }
        if (this.dipDeclareWarButton != null) { this.dipDeclareWarButton.visible = dipPage && dipHasSelection; this.dipDeclareWarButton.active = dipCanManage && !this.data.hasActiveWar() && this.data.warCooldownRemainingSeconds() <= 0; }
        if (this.dipAcceptAllyButton != null) { this.dipAcceptAllyButton.visible = dipPage && dipHasSelection && dipHasPendingAlly; this.dipAcceptAllyButton.active = dipCanManage && dipHasPendingAlly; }
        if (this.dipRejectAllyButton != null) { this.dipRejectAllyButton.visible = dipPage && dipHasSelection && dipHasPendingAlly; this.dipRejectAllyButton.active = dipCanManage && dipHasPendingAlly; }
        if (this.dipBackButton != null) { this.dipBackButton.visible = dipPage && dipHasSelection; this.dipBackButton.active = dipPage && dipHasSelection; }
        if (this.dipOpenTradeButton != null) { this.dipOpenTradeButton.visible = dipPage && dipHasSelection; this.dipOpenTradeButton.active = dipCanManage; }

        boolean treasuryPage = this.currentPage == Page.TREASURY;
        boolean canTreasury = treasuryPage && this.data.hasNation() && this.data.canManageTreasury();
        if (this.salesTaxUpButton != null) { this.salesTaxUpButton.visible = treasuryPage; this.salesTaxUpButton.active = canTreasury; }
        if (this.salesTaxDownButton != null) { this.salesTaxDownButton.visible = treasuryPage; this.salesTaxDownButton.active = canTreasury; }
        if (this.tariffUpButton != null) { this.tariffUpButton.visible = treasuryPage; this.tariffUpButton.active = canTreasury; }
        if (this.tariffDownButton != null) { this.tariffDownButton.visible = treasuryPage; this.tariffDownButton.active = canTreasury; }
        if (this.treasuryCommandsButton != null) { this.treasuryCommandsButton.visible = treasuryPage && this.data.hasNation(); this.treasuryCommandsButton.active = treasuryPage; }
        if (this.tariffDownButton != null) { this.tariffDownButton.visible = treasuryPage; this.tariffDownButton.active = canTreasury; }

        if (this.resetMapButton != null) { this.resetMapButton.visible = claimsPage; this.resetMapButton.active = claimsPage; }

        boolean hasWar = this.data.hasActiveWar();
        boolean canWar = warPage && this.data.hasNation() && this.data.canDeclareWar() && hasWar;
        boolean hasIncomingProposal = this.data.hasPeaceProposal() && this.data.peaceProposalIncoming();
        if (this.proposeCeasefireButton != null) { this.proposeCeasefireButton.visible = warPage && hasWar && !hasIncomingProposal; this.proposeCeasefireButton.active = canWar; }
        if (this.proposeCedeButton != null) { this.proposeCedeButton.visible = warPage && hasWar && !hasIncomingProposal; this.proposeCedeButton.active = canWar; }
        if (this.proposeReparationButton != null) { this.proposeReparationButton.visible = warPage && hasWar && !hasIncomingProposal; this.proposeReparationButton.active = canWar; }
        if (this.acceptPeaceButton != null) { this.acceptPeaceButton.visible = warPage && hasIncomingProposal; this.acceptPeaceButton.active = canWar && hasIncomingProposal; }
        if (this.rejectPeaceButton != null) { this.rejectPeaceButton.visible = warPage && hasIncomingProposal; this.rejectPeaceButton.active = canWar && hasIncomingProposal; }

        boolean flagPage = this.currentPage == Page.FLAG;
        if (this.primaryColorInput != null) { this.primaryColorInput.visible = flagPage; this.primaryColorInput.setEditable(flagPage && canManageInfo); }
        if (this.secondaryColorInput != null) { this.secondaryColorInput.visible = flagPage; this.secondaryColorInput.setEditable(flagPage && canManageInfo); }
        if (this.applyColorsButton != null) { this.applyColorsButton.visible = flagPage; this.applyColorsButton.active = flagPage && canManageInfo && colorInputsValid(); }
        if (this.flagPathInput != null) { this.flagPathInput.visible = flagPage; this.flagPathInput.setEditable(flagPage && this.data.hasNation() && this.data.canUploadFlag()); }
        if (this.browseButton != null) { this.browseButton.visible = flagPage; this.browseButton.active = flagPage && this.data.hasNation() && this.data.canUploadFlag(); }
        if (this.uploadButton != null) { this.uploadButton.visible = flagPage; this.uploadButton.active = flagPage && this.data.hasNation() && this.data.canUploadFlag(); }
        if (this.toggleMirrorButton != null) {
            boolean canMirror = flagPage && this.data.hasNation() && this.data.canUploadFlag() && !this.data.flagId().isBlank();
            this.toggleMirrorButton.visible = flagPage;
            this.toggleMirrorButton.active = canMirror;
            this.toggleMirrorButton.setMessage(Component.translatable(this.data.flagMirrored() ? "screen.sailboatmod.nation.action.unmirror" : "screen.sailboatmod.nation.action.mirror"));
        }
        layoutScrollableWidgets();
    }

    private int mapCenterX() { return this.data.previewCenterChunkX() + this.mapOffsetX; }
    private int mapCenterZ() { return this.data.previewCenterChunkZ() + this.mapOffsetZ; }

    private void drawClaimMap(GuiGraphics g, int mapX, int mapY, int mouseX, int mouseY) {
        g.fill(mapX - 1, mapY - 1, mapX + CLAIM_MAP_W + 1, mapY + CLAIM_MAP_H + 1, 0xFF8EAF9E);
        g.fill(mapX, mapY, mapX + CLAIM_MAP_W, mapY + CLAIM_MAP_H, 0xAA0B110F);
        int centerX = mapCenterX();
        int centerZ = mapCenterZ();
        int sub = com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService.SUB;
        int totalCells = claimRadius() * 2 + 1;
        int totalSubCells = totalCells * sub;
        for (int gz = 0; gz < totalCells; gz++) {
            int chunkZ = centerZ + gz - claimRadius();
            for (int gx = 0; gx < totalCells; gx++) {
                int chunkX = centerX + gx - claimRadius();
                NationOverviewClaim claim = findClaim(chunkX, chunkZ);
                double overlayStrength = claim == null ? 0 : (this.data.nationId().equals(claim.nationId()) ? 0.24D : 0.18D);
                int claimOverlay = claim == null ? 0 : (0xFF000000 | claim.primaryColorRgb());
                for (int sz = 0; sz < sub; sz++) {
                    int subCellZ = gz * sub + sz;
                    int y1 = mapY + subCellZ * CLAIM_MAP_H / totalSubCells;
                    int y2 = mapY + (subCellZ + 1) * CLAIM_MAP_H / totalSubCells;
                    for (int sx = 0; sx < sub; sx++) {
                        int subCellX = gx * sub + sx;
                        int x1 = mapX + subCellX * CLAIM_MAP_W / totalSubCells;
                        int x2 = mapX + (subCellX + 1) * CLAIM_MAP_W / totalSubCells;
                        int color = sampleClaimTerrainColor(chunkX, chunkZ, sx, sz);
                        if (claim != null) color = blendColor(color, claimOverlay, overlayStrength);
                        g.fill(x1, y1, Math.max(x1 + 1, x2), Math.max(y1 + 1, y2), color);
                    }
                }
            }
        }
        for (int gz = 0; gz < totalCells; gz++) {
            int chunkZ = centerZ + gz - claimRadius();
            for (int gx = 0; gx < totalCells; gx++) {
                int chunkX = centerX + gx - claimRadius();
                NationOverviewClaim claim = findClaim(chunkX, chunkZ);
                if (claim == null) continue;
                int subCellX0 = gx * sub;
                int subCellZ0 = gz * sub;
                int x1 = mapX + subCellX0 * CLAIM_MAP_W / totalSubCells;
                int x2 = mapX + (subCellX0 + sub) * CLAIM_MAP_W / totalSubCells;
                int y1 = mapY + subCellZ0 * CLAIM_MAP_H / totalSubCells;
                int y2 = mapY + (subCellZ0 + sub) * CLAIM_MAP_H / totalSubCells;
                int borderColor = 0xFF000000 | claim.secondaryColorRgb();
                String ownerId = claim.nationId();
                if (!sameOwner(ownerId, chunkX, chunkZ - 1)) g.fill(x1, y1, x2, y1 + 1, borderColor);
                if (!sameOwner(ownerId, chunkX, chunkZ + 1)) g.fill(x1, y2 - 1, x2, y2, borderColor);
                if (!sameOwner(ownerId, chunkX - 1, chunkZ)) g.fill(x1, y1, x1 + 1, y2, borderColor);
                if (!sameOwner(ownerId, chunkX + 1, chunkZ)) g.fill(x2 - 1, y1, x2, y2, borderColor);
            }
        }
        drawClaimMarker(g, mapX, mapY, this.data.currentChunkX(), this.data.currentChunkZ(), 0xFFFFFFFF);
        drawClaimMarker(g, mapX, mapY, this.selectedClaimChunkX, this.selectedClaimChunkZ, 0xFFFFD166);
        if (hasAreaSelection()) {
            int minX = Math.min(this.areaCorner1X, this.areaCorner2X);
            int maxX = Math.max(this.areaCorner1X, this.areaCorner2X);
            int minZ = Math.min(this.areaCorner1Z, this.areaCorner2Z);
            int maxZ = Math.max(this.areaCorner1Z, this.areaCorner2Z);
            for (int az = minZ; az <= maxZ; az++) {
                for (int ax = minX; ax <= maxX; ax++) {
                    drawClaimMarker(g, mapX, mapY, ax, az, 0xAAFF8844);
                }
            }
        } else if (this.areaCorner1X != Integer.MIN_VALUE) {
            drawClaimMarker(g, mapX, mapY, this.areaCorner1X, this.areaCorner1Z, 0xAAFF8844);
        }
        drawTownLabels(g, mapX, mapY, mouseX, mouseY);
    }

    private void drawTownLabels(GuiGraphics g, int mapX, int mapY, int mouseX, int mouseY) {
        if (mouseX < mapX || mouseX >= mapX + CLAIM_MAP_W || mouseY < mapY || mouseY >= mapY + CLAIM_MAP_H) return;
        int cellX = Math.max(0, Math.min(claimRadius() * 2, (int) ((mouseX - mapX) * (claimRadius() * 2 + 1) / CLAIM_MAP_W)));
        int cellZ = Math.max(0, Math.min(claimRadius() * 2, (int) ((mouseY - mapY) * (claimRadius() * 2 + 1) / CLAIM_MAP_H)));
        int hoverChunkX = mapCenterX() + cellX - claimRadius();
        int hoverChunkZ = mapCenterZ() + cellZ - claimRadius();
        NationOverviewClaim hoverClaim = findClaim(hoverChunkX, hoverChunkZ);
        if (hoverClaim == null) return;
        String hoverId = hoverClaim.nationId();
        String label = hoverClaim.nationName();
        if (label.isBlank()) return;
        int count = 0;
        for (NationOverviewClaim c : this.data.nearbyClaims()) {
            if (c.nationId().equals(hoverId)) count++;
        }
        int color = 0xFF000000 | hoverClaim.primaryColorRgb();
        String text = label + "(" + count + ")";
        int tw = this.font.width(text);
        int tx = Math.max(mapX, Math.min(mouseX - tw / 2, mapX + CLAIM_MAP_W - tw));
        int ty = Math.max(mapY, Math.min(mouseY - 14, mapY + CLAIM_MAP_H - 10));
        g.fill(tx - 1, ty - 1, tx + tw + 1, ty + 9, 0xCC000000);
        g.drawString(this.font, text, tx, ty, color);
    }

    private int sampleClaimTerrainColor(int chunkX, int chunkZ, int sx, int sz) {
        int sub = com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService.SUB;
        int gridX = chunkX - this.data.previewCenterChunkX() + claimRadius();
        int gridZ = chunkZ - this.data.previewCenterChunkZ() + claimRadius();
        int diameter = claimRadius() * 2 + 1;
        if (gridX >= 0 && gridX < diameter && gridZ >= 0 && gridZ < diameter) {
            int chunkIndex = gridZ * diameter + gridX;
            int subIndex = chunkIndex * sub * sub + sz * sub + sx;
            List<Integer> colors = this.data.nearbyTerrainColors();
            if (subIndex >= 0 && subIndex < colors.size()) {
                int color = colors.get(subIndex);
                if (color != PREVIEW_DEFAULT_TERRAIN_COLOR) {
                    TerrainColorClientCache.put(chunkX, chunkZ, color);
                    return color;
                }
            }
        }
        Integer cached = TerrainColorClientCache.get(chunkX, chunkZ);
        if (cached != null) return cached;
        Integer local = sampleLoadedTerrainColor(chunkX, chunkZ);
        if (local != null) return local;
        return PREVIEW_DEFAULT_TERRAIN_COLOR;
    }


    private int blendColor(int base, int overlay, double factor) {
        double clamped = Math.max(0.0D, Math.min(1.0D, factor));
        int br = (base >> 16) & 0xFF;
        int bg = (base >> 8) & 0xFF;
        int bb = base & 0xFF;
        int or = (overlay >> 16) & 0xFF;
        int og = (overlay >> 8) & 0xFF;
        int ob = overlay & 0xFF;
        int rr = (int) Math.round(br * (1.0D - clamped) + or * clamped);
        int rg = (int) Math.round(bg * (1.0D - clamped) + og * clamped);
        int rb = (int) Math.round(bb * (1.0D - clamped) + ob * clamped);
        return 0xFF000000 | (rr << 16) | (rg << 8) | rb;
    }

    private void drawClaimMarker(GuiGraphics g, int mapX, int mapY, int chunkX, int chunkZ, int color) {
        int lx = chunkX - mapCenterX() + claimRadius();
        int lz = chunkZ - mapCenterZ() + claimRadius();
        if (lx < 0 || lx > claimRadius() * 2 || lz < 0 || lz > claimRadius() * 2) return;
        int x1 = mapX + lx * CLAIM_MAP_W / (claimRadius() * 2 + 1);
        int y1 = mapY + lz * CLAIM_MAP_H / (claimRadius() * 2 + 1);
        int x2 = mapX + (lx + 1) * CLAIM_MAP_W / (claimRadius() * 2 + 1);
        int y2 = mapY + (lz + 1) * CLAIM_MAP_H / (claimRadius() * 2 + 1);
        drawRect(g, x1, y1, Math.max(x1 + 1, x2) - 1, Math.max(y1 + 1, y2) - 1, color);
    }

    private void drawDiplomacySection(GuiGraphics g, Component title, List<Component> lines, int x, int y, int width, int maxLines) {
        g.drawString(this.font, title, x, y, 0xFFE7C977);
        int drawY = y + 14;
        int drawn = 0;
        for (Component line : lines) {
            if (drawn >= maxLines) break;
            List<FormattedCharSequence> wrapped = wrap(line, width);
            for (FormattedCharSequence seq : wrapped) {
                if (drawn >= maxLines) break;
                g.drawString(this.font, seq, x, drawY, 0xFFDCEEFF);
                drawY += 10;
                drawn++;
            }
        }
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

    private void switchPage(Page page) {
        this.currentPage = page == null ? Page.OVERVIEW : page;
        this.pageScroll = 0;
        if (this.currentPage == Page.CLAIMS) {
            ensureClaimPreviewVisible();
        }
        updateButtonState();
    }

    private void layoutScrollableWidgets() {
        boolean overviewPage = this.currentPage == Page.OVERVIEW;
        boolean noNationOverview = overviewPage && !this.data.hasNation();
        boolean nationOverview = overviewPage && this.data.hasNation();
        boolean membersPage = this.currentPage == Page.MEMBERS;
        boolean claimsPage = this.currentPage == Page.CLAIMS;
        boolean claimsPermView = claimsPage && this.claimsSubPage == 1;
        boolean claimsMapView = claimsPage && this.claimsSubPage == 0;
        boolean warPage = this.currentPage == Page.WAR;
        boolean dipPage = this.currentPage == Page.DIPLOMACY;
        boolean treasuryPage = this.currentPage == Page.TREASURY;
        boolean flagPage = this.currentPage == Page.FLAG;

        int overviewFormY = overviewFormLocalY();
        int nationTownButtonY = overviewFormY + 22;
        int noNationJoinY = overviewFormY + 48;

        layoutWidget(this.nationNameInput, 12, overviewFormY + 22, 18, overviewPage);
        layoutWidget(this.shortNameInput, 216, overviewFormY + 22, 18, nationOverview);
        layoutWidget(this.saveNationInfoButton, 300, overviewFormY + 22, 18, nationOverview);
        layoutWidget(this.createNationButton, 300, overviewFormY + 22, 18, noNationOverview);
        layoutWidget(this.joinNationInput, 12, noNationJoinY + 22, 18, noNationOverview);
        layoutWidget(this.joinNationButton, 300, noNationJoinY + 22, 18, noNationOverview);
        layoutWidget(this.nationHelpButton, 300, noNationJoinY + 50, 18, noNationOverview);
        layoutWidget(this.previousTownButton, 300, nationTownButtonY, 18, nationOverview);
        layoutWidget(this.nextTownButton, 358, nationTownButtonY, 18, nationOverview);
        layoutWidget(this.openCapitalTownButton, 300, nationTownButtonY + 28, 18, nationOverview);
        layoutWidget(this.removeCoreButton, 300, nationTownButtonY + 56, 18, nationOverview);

        layoutWidget(this.officerTitleInput, 224, 152, 18, membersPage);
        layoutWidget(this.saveOfficerTitleButton, 366, 152, 18, membersPage);
        layoutWidget(this.appointOfficerButton, 224, 182, 18, membersPage);
        layoutWidget(this.removeOfficerButton, 332, 182, 18, membersPage);
        layoutWidget(this.appointMayorButton, 224, 206, 18, membersPage);

        layoutWidget(this.claimButton, 12, BODY_H - 26, 18, claimsMapView);
        layoutWidget(this.unclaimButton, 90, BODY_H - 26, 18, claimsMapView);
        layoutWidget(this.claimsSubPageButton, 184, BODY_H - 26, 18, claimsPage);
        layoutWidget(this.breakPermissionButton, 12, 50, 18, claimsPermView);
        layoutWidget(this.placePermissionButton, 120, 50, 18, claimsPermView);
        layoutWidget(this.usePermissionButton, 12, 74, 18, claimsPermView);
        layoutWidget(this.containerPermissionButton, 120, 74, 18, claimsPermView);
        layoutWidget(this.redstonePermissionButton, 12, 98, 18, claimsPermView);
        layoutWidget(this.entityUsePermissionButton, 120, 98, 18, claimsPermView);
        layoutWidget(this.entityDamagePermissionButton, 12, 122, 18, claimsPermView);
        layoutFixedWidget(this.resetMapButton, BODY_W - CLAIM_MAP_W - 16, 10, claimsPage);

        layoutWidget(this.warButton, 12, 164, 18, warPage);
        layoutWidget(this.warTargetInput, 12, 192, 18, warPage);
        layoutWidget(this.declareWarButton, 300, 192, 18, warPage);
        layoutWidget(this.proposeCeasefireButton, 12, 148, 18, warPage && this.data.hasActiveWar() && !(this.data.hasPeaceProposal() && this.data.peaceProposalIncoming()));
        layoutWidget(this.proposeCedeButton, 108, 148, 18, warPage && this.data.hasActiveWar() && !(this.data.hasPeaceProposal() && this.data.peaceProposalIncoming()));
        layoutWidget(this.proposeReparationButton, 204, 148, 18, warPage && this.data.hasActiveWar() && !(this.data.hasPeaceProposal() && this.data.peaceProposalIncoming()));
        layoutWidget(this.acceptPeaceButton, 12, 148, 18, warPage && this.data.hasPeaceProposal() && this.data.peaceProposalIncoming());
        layoutWidget(this.rejectPeaceButton, 108, 148, 18, warPage && this.data.hasPeaceProposal() && this.data.peaceProposalIncoming());

        layoutWidget(this.dipAllyButton, 234, 40, 18, dipPage);
        layoutWidget(this.dipTradeButton, 340, 40, 18, dipPage);
        layoutWidget(this.dipEnemyButton, 234, 64, 18, dipPage);
        layoutWidget(this.dipNeutralButton, 340, 64, 18, dipPage);
        layoutWidget(this.dipDeclareWarButton, 234, 88, 18, dipPage);
        layoutWidget(this.dipAcceptAllyButton, 234, 112, 18, dipPage);
        layoutWidget(this.dipRejectAllyButton, 340, 112, 18, dipPage);
        layoutWidget(this.dipBackButton, 234, 136, 18, dipPage);
        layoutWidget(this.dipOpenTradeButton, 234, 160, 18, dipPage);

        layoutWidget(this.salesTaxUpButton, 200, 72, 14, treasuryPage);
        layoutWidget(this.salesTaxDownButton, 224, 72, 14, treasuryPage);
        layoutWidget(this.tariffUpButton, 200, 90, 14, treasuryPage);
        layoutWidget(this.tariffDownButton, 224, 90, 14, treasuryPage);
        layoutWidget(this.treasuryCommandsButton, BODY_W - 90, 68, 18, treasuryPage && this.data.hasNation());

        layoutWidget(this.primaryColorInput, 170, 126, 18, flagPage);
        layoutWidget(this.secondaryColorInput, 170, 154, 18, flagPage);
        layoutWidget(this.applyColorsButton, 278, 139, 18, flagPage);
        layoutWidget(this.flagPathInput, 12, BODY_H - 54, 18, flagPage);
        layoutWidget(this.browseButton, 268, BODY_H - 54, 18, flagPage);
        layoutWidget(this.uploadButton, 330, BODY_H - 54, 18, flagPage);
        layoutWidget(this.toggleMirrorButton, 170, 82, 18, flagPage);
    }

    private void layoutWidget(AbstractWidget widget, int bodyLocalX, int bodyLocalY, int height, boolean pageVisible) {
        if (widget == null) {
            return;
        }
        int absoluteX = left() + BODY_X + bodyLocalX;
        int absoluteY = top() + BODY_Y + bodyLocalY - this.pageScroll;
        widget.setPosition(absoluteX, absoluteY);
        if (!pageVisible) {
            widget.visible = false;
            return;
        }
        int viewportTop = bodyViewportTop();
        int viewportBottom = bodyViewportBottom();
        widget.visible = absoluteY + height > viewportTop && absoluteY < viewportBottom;
    }

    private void layoutFixedWidget(AbstractWidget widget, int bodyLocalX, int bodyLocalY, boolean pageVisible) {
        if (widget == null) {
            return;
        }
        int absoluteX = left() + BODY_X + bodyLocalX;
        int absoluteY = top() + BODY_Y + bodyLocalY;
        widget.setPosition(absoluteX, absoluteY);
        widget.visible = pageVisible;
    }

    private int overviewFormLocalY() {
        int drawY = 34;
        for (Component line : buildOverviewLines()) {
            drawY += wrappedHeight(line, BODY_W - 24) + 6;
        }
        return Math.max(118, drawY + 8);
    }

    private boolean isInsideBodyViewport(double mouseX, double mouseY) {
        return mouseX >= left() + BODY_X
                && mouseX < left() + BODY_X + BODY_W
                && mouseY >= bodyViewportTop()
                && mouseY < bodyViewportBottom();
    }

    private int bodyViewportTop() {
        return top() + BODY_Y + 24;
    }

    private int bodyViewportBottom() {
        return top() + BODY_Y + BODY_H - 1;
    }

    private int maxPageScroll() {
        return Math.max(0, pageContentHeight(this.currentPage) - (BODY_H - 26));
    }

    private int pageContentHeight(Page page) {
        return switch (page) {
            case OVERVIEW -> this.data.hasNation() ? overviewFormLocalY() + 170 : overviewFormLocalY() + 150;
            case MEMBERS -> 254;
            case CLAIMS -> 228;
            case WAR -> 252;
            case DIPLOMACY -> 236;
            case TREASURY -> 248;
            case FLAG -> 230;
        };
    }
    private void requestRefresh() { requestRefresh(mapCenterX(), mapCenterZ()); }
    private void requestRefresh(int centerChunkX, int centerChunkZ) {
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
        ModNetwork.CHANNEL.sendToServer(new OpenNationMenuPacket(centerChunkX, centerChunkZ));
        this.statusLine = Component.translatable("screen.sailboatmod.nation.status.refreshing");
    }

    private void flushQueuedPreviewRefresh() {
        if (this.refreshPending) {
            return;
        }
        if (this.queuedPreviewCenterX == Integer.MIN_VALUE || this.queuedPreviewCenterZ == Integer.MIN_VALUE) {
            return;
        }
        int queuedX = this.queuedPreviewCenterX;
        int queuedZ = this.queuedPreviewCenterZ;
        this.queuedPreviewCenterX = Integer.MIN_VALUE;
        this.queuedPreviewCenterZ = Integer.MIN_VALUE;
        if (queuedX == this.data.previewCenterChunkX() && queuedZ == this.data.previewCenterChunkZ()) {
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
        int diameter = claimRadius() * 2 + 1;
        boolean missingTerrain = hasIncompletePreviewTerrain();
        boolean offCenter = centerChunkX != this.data.previewCenterChunkX() || centerChunkZ != this.data.previewCenterChunkZ();
        traceClaim("ensureClaimPreviewVisible center=" + centerChunkX + "," + centerChunkZ
                + " previewCenter=" + this.data.previewCenterChunkX() + "," + this.data.previewCenterChunkZ()
                + " terrainCount=" + this.data.nearbyTerrainColors().size()
                + " expected=" + (diameter * diameter)
                + " missingTerrain=" + missingTerrain
                + " offCenter=" + offCenter);
        if (missingTerrain || offCenter) {
            requestRefresh(centerChunkX, centerChunkZ);
        }
    }

    private boolean hasIncompletePreviewTerrain() {
        int diameter = claimRadius() * 2 + 1;
        if (this.data.nearbyTerrainColors().size() < diameter * diameter) {
            return true;
        }
        for (Integer color : this.data.nearbyTerrainColors()) {
            if (color == null || color == PREVIEW_DEFAULT_TERRAIN_COLOR) {
                return true;
            }
        }
        return false;
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
        String nationName = valueOf(this.nationNameInput).trim();
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

    private void syncNationInfoInputs() { if (this.data.hasNation()) { if (this.nationNameInput != null && !this.nationNameInput.isFocused()) this.nationNameInput.setValue(this.data.nationName()); if (this.shortNameInput != null && !this.shortNameInput.isFocused()) this.shortNameInput.setValue(this.data.shortName()); } }
    private void syncColorInputs() { if (this.primaryColorInput != null && !this.primaryColorInput.isFocused()) this.primaryColorInput.setValue(hex(this.data.primaryColorRgb())); if (this.secondaryColorInput != null && !this.secondaryColorInput.isFocused()) this.secondaryColorInput.setValue(hex(this.data.secondaryColorRgb())); }
    private void syncOfficerTitleInput() { if (this.officerTitleInput != null && !this.officerTitleInput.isFocused()) this.officerTitleInput.setValue(this.data.officerTitle()); }
    private void syncSelections() { if (this.selectedClaimChunkX == Integer.MIN_VALUE || Math.abs(this.selectedClaimChunkX - this.data.currentChunkX()) > claimRadius() || Math.abs(this.selectedClaimChunkZ - this.data.currentChunkZ()) > claimRadius()) { this.selectedClaimChunkX = this.data.currentChunkX(); this.selectedClaimChunkZ = this.data.currentChunkZ(); } if (this.data.members().isEmpty()) { this.selectedMemberUuid = ""; this.memberScroll = 0; return; } if (this.selectedMemberUuid.isBlank()) this.selectedMemberUuid = this.data.members().get(0).playerUuid(); for (NationOverviewMember member : this.data.members()) if (member.playerUuid().equals(this.selectedMemberUuid)) return; this.selectedMemberUuid = this.data.members().get(0).playerUuid(); }
    private boolean trySelectMember(double mouseX, double mouseY) { int[] b = memberListBounds(); if (mouseX < b[0] || mouseX >= b[0] + b[2] || mouseY < b[1] || mouseY >= b[1] + b[3] || this.data.members().isEmpty()) return false; int row = (int) ((mouseY - b[1] - 4) / MEMBER_ROW_H); if (row < 0 || row >= MEMBER_VISIBLE_ROWS) return false; int idx = clampMemberScroll(this.memberScroll) + row; if (idx < 0 || idx >= this.data.members().size()) return false; this.selectedMemberUuid = this.data.members().get(idx).playerUuid(); updateButtonState(); return true; }
    private boolean trySelectClaim(double mouseX, double mouseY) {
        int mapX = claimMapX(left() + BODY_X); int mapY = claimMapY(top() + BODY_Y) - this.pageScroll;
        if (mouseX < mapX || mouseX >= mapX + CLAIM_MAP_W || mouseY < mapY || mouseY >= mapY + CLAIM_MAP_H) return false;
        int cellX = Math.max(0, Math.min(claimRadius() * 2, (int) ((mouseX - mapX) * (claimRadius() * 2 + 1) / CLAIM_MAP_W)));
        int cellZ = Math.max(0, Math.min(claimRadius() * 2, (int) ((mouseY - mapY) * (claimRadius() * 2 + 1) / CLAIM_MAP_H)));
        int chunkX = mapCenterX() + cellX - claimRadius();
        int chunkZ = mapCenterZ() + cellZ - claimRadius();
        if (hasShiftDown() && this.areaCorner1X != Integer.MIN_VALUE) {
            this.areaCorner2X = chunkX; this.areaCorner2Z = chunkZ;
        } else {
            this.selectedClaimChunkX = chunkX; this.selectedClaimChunkZ = chunkZ;
            this.areaCorner1X = chunkX; this.areaCorner1Z = chunkZ;
            this.areaCorner2X = Integer.MIN_VALUE; this.areaCorner2Z = Integer.MIN_VALUE;
        }
        updateButtonState(); return true;
    }
    private boolean trySelectDiplomacyNation(double mouseX, double mouseY) { int listX = left() + BODY_X + 8; int listY = top() + BODY_Y + 28; int listW = 220; int listH = DIP_VISIBLE_ROWS * DIP_ROW_H; if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH || this.data.allNations().isEmpty()) return false; int row = (int) ((mouseY - listY) / DIP_ROW_H); if (row < 0 || row >= DIP_VISIBLE_ROWS) return false; int idx = Math.max(0, Math.min(this.diplomacyScroll, this.data.allNations().size() - DIP_VISIBLE_ROWS)) + row; if (idx < 0 || idx >= this.data.allNations().size()) return false; this.selectedDiplomacyNationId = this.data.allNations().get(idx).nationId(); updateButtonState(); return true; }
    private int[] memberListBounds() { return new int[] { left() + BODY_X + 10, top() + BODY_Y + 48, MEMBER_LIST_W, MEMBER_VISIBLE_ROWS * MEMBER_ROW_H + 8 }; }
    private int claimMapX(int bodyX) { return bodyX + BODY_W - CLAIM_MAP_W - 16; }
    private int claimMapY(int bodyY) { return bodyY + 24; }
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
    private void cycleTownSelection(int delta) { if (this.data.towns().isEmpty()) return; int index = 0; for (int i = 0; i < this.data.towns().size(); i++) { if (this.data.towns().get(i).townId().equals(this.selectedTownId)) { index = i; break; } } int size = this.data.towns().size(); int next = Math.floorMod(index + delta, size); this.selectedTownId = this.data.towns().get(next).townId(); updateButtonState(); }

    private int sampleLocalTerrainColor(int chunkX, int chunkZ) {
        Integer local = sampleLoadedTerrainColor(chunkX, chunkZ);
        if (local != null) {
            return local;
        }
        Integer cached = TerrainColorClientCache.get(chunkX, chunkZ);
        if (cached != null) return cached;
        return 0xFF33414A;
    }

    private static final int PREVIEW_WATER_COLOR = 0xFF4466B0;
    private static final int PREVIEW_FALLBACK_COLOR = 0xFF000000 | (MapColor.GRASS.col & 0x00FFFFFF);

    private Integer sampleLoadedTerrainColor(int chunkX, int chunkZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.hasChunk(chunkX, chunkZ)) return null;

        int[] sampleOffsets = {2, 6, 10, 14};
        int[] colors = new int[16];
        int[] heights = new int[16];
        int count = 0;
        try {
            for (int lxi = 0; lxi < 4; lxi++) {
                for (int lzi = 0; lzi < 4; lzi++) {
                    int[] result = sampleBlockColorAndHeight(chunkX, chunkZ, sampleOffsets[lxi], sampleOffsets[lzi]);
                    colors[count] = result[0];
                    heights[count] = result[1];
                    count++;
                }
            }
        } catch (Exception ignored) {
            int color = PREVIEW_FALLBACK_COLOR;
            TerrainColorClientCache.put(chunkX, chunkZ, color);
            return color;
        }
        long rSum = 0, gSum = 0, bSum = 0;
        for (int lxi = 0; lxi < 4; lxi++) {
            for (int lzi = 0; lzi < 4; lzi++) {
                int idx = lxi * 4 + lzi;
                int c = colors[idx];
                int shade = 180;
                if (lzi > 0) {
                    int northIdx = lxi * 4 + (lzi - 1);
                    if (heights[idx] > heights[northIdx]) shade = 220;
                    else if (heights[idx] < heights[northIdx]) shade = 135;
                }
                rSum += ((c >> 16) & 0xFF) * shade / 180;
                gSum += ((c >> 8) & 0xFF) * shade / 180;
                bSum += (c & 0xFF) * shade / 180;
            }
        }
        int color = 0xFF000000 | (Math.min(255, (int)(rSum / 16)) << 16) | (Math.min(255, (int)(gSum / 16)) << 8) | Math.min(255, (int)(bSum / 16));
        TerrainColorClientCache.put(chunkX, chunkZ, color);
        return color;
    }

    private int[] sampleBlockColorAndHeight(int chunkX, int chunkZ, int localX, int localZ) {
        Minecraft minecraft = Minecraft.getInstance();
        int worldX = (chunkX << 4) + localX;
        int worldZ = (chunkZ << 4) + localZ;
        int worldY = minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
        if (worldY < minecraft.level.getMinBuildHeight()) return new int[]{PREVIEW_FALLBACK_COLOR, worldY};
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, worldY, worldZ);
        BlockState state = minecraft.level.getBlockState(pos);
        while (state.isAir() && worldY > minecraft.level.getMinBuildHeight()) {
            worldY--;
            pos.set(worldX, worldY, worldZ);
            state = minecraft.level.getBlockState(pos);
        }
        if (state.getFluidState().is(FluidTags.WATER)) return new int[]{PREVIEW_WATER_COLOR, worldY};
        MapColor mapColor = state.getMapColor(minecraft.level, pos);
        if (mapColor == null || mapColor == MapColor.NONE || mapColor.col == 0) return new int[]{PREVIEW_FALLBACK_COLOR, worldY};
        return new int[]{0xFF000000 | (mapColor.col & 0x00FFFFFF), worldY};
    }

    private void drawPanelFrame(GuiGraphics g, int x, int y, int w, int h) { g.fill(x, y, x + w, y + h, 0x66203037); g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x66131C23); }
    private void drawMetricCard(GuiGraphics g, int x, int y, int w, int h, Component label, String value, int accent) {
        g.fill(x, y, x + w, y + h, 0x6E19120D);
        g.fill(x, y, x + w, y + 1, accent);
        g.fill(x, y + h - 1, x + w, y + h, 0xAA3E2B18);
        g.drawString(this.font, Component.literal(trimToWidth(label.getString().replace(":", ""), w - 8)), x + 4, y + 3, 0xFFD0BA8C);
        g.drawString(this.font, Component.literal(trimToWidth(value, w - 8)), x + 4, y + 11, 0xFFF6E9CA);
    }
    private void drawPolicyBar(GuiGraphics g, int x, int y, Component label, int basisPoints, int maxBasisPoints, int accent) {
        int labelW = 76;
        int barW = 62;
        int barH = 10;
        int valueX = x + labelW + barW + 6;
        int clamped = Math.max(0, Math.min(maxBasisPoints, basisPoints));
        int fillW = Math.max(0, Math.min(barW, Math.round((clamped / (float) maxBasisPoints) * barW)));
        g.drawString(this.font, Component.literal(trimToWidth(label.getString().replace(":", ""), labelW - 2)), x, y - 8, 0xFFB8C0C8);
        g.fill(x + labelW, y, x + labelW + barW, y + barH, 0x4426333D);
        g.fill(x + labelW, y, x + labelW + fillW, y + barH, accent);
        g.fill(x + labelW, y + barH - 1, x + labelW + barW, y + barH, 0x66465863);
        g.drawString(this.font, formatBasisPoints(basisPoints), valueX, y + 1, 0xFFDCEEFF);
    }
    private void drawWrappedLine(GuiGraphics g, Component line, int x, int y, int w, int color) { int drawY = y; for (FormattedCharSequence seq : wrap(line, w)) { g.drawString(this.font, seq, x, drawY, color); drawY += 10; } }
    private int wrappedHeight(Component line, int width) { return Math.max(10, wrap(line, width).size() * 10); }
    private void drawRect(GuiGraphics g, int x1, int y1, int x2, int y2, int color) { int minX = Math.min(x1, x2), maxX = Math.max(x1, x2), minY = Math.min(y1, y2), maxY = Math.max(y1, y2); g.fill(minX, minY, maxX + 1, minY + 1, color); g.fill(minX, maxY, maxX + 1, maxY + 1, color); g.fill(minX, minY, minX + 1, maxY + 1, color); g.fill(maxX, minY, maxX + 1, maxY + 1, color); }
    private List<FormattedCharSequence> wrap(Component text, int width) { return this.font.split(text, width); }
    private int clampMemberScroll(int value) { return Math.max(0, Math.min(value, Math.max(0, this.data.members().size() - MEMBER_VISIBLE_ROWS))); }
    private String trimToWidth(String src, int maxPixels) { if (src == null || src.isEmpty() || maxPixels <= 0 || this.font.width(src) <= maxPixels) return src == null ? "" : src; String ellipsis = "..."; int ew = this.font.width(ellipsis); int end = src.length(); while (end > 0 && this.font.width(src.substring(0, end)) + ew > maxPixels) end--; return src.substring(0, Math.max(0, end)) + ellipsis; }
    private String shortText(String value, int maxLength) { if (value == null || value.isBlank()) return "-"; return value.length() <= maxLength ? value : value.substring(0, Math.max(0, maxLength)) + "..."; }
    private String valueOf(EditBox box) { return box == null ? "" : box.getValue(); }
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
    private int left() { return (this.width - SCREEN_W) / 2; }
    private int top() { return (this.height - SCREEN_H) / 2; }

    private enum Page {
        OVERVIEW("screen.sailboatmod.nation.section.overview"), MEMBERS("screen.sailboatmod.nation.section.members"), CLAIMS("screen.sailboatmod.nation.section.claims"), WAR("screen.sailboatmod.nation.section.war"), DIPLOMACY("screen.sailboatmod.nation.section.diplomacy"), TREASURY("screen.sailboatmod.nation.section.treasury"), FLAG("screen.sailboatmod.nation.section.flag");
        private final String titleKey;
        Page(String titleKey) { this.titleKey = titleKey; }
        private Component title() { return Component.translatable(this.titleKey); }
    }
}
