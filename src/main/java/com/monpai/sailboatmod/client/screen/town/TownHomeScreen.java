package com.monpai.sailboatmod.client.screen.town;

import com.monpai.sailboatmod.client.cache.TerrainColorClientCache;
import com.monpai.sailboatmod.client.texture.NationFlagTextureCache;
import com.monpai.sailboatmod.client.texture.TownFlagUploadClient;
import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;
import com.monpai.sailboatmod.nation.menu.NationOverviewMember;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import com.monpai.sailboatmod.nation.model.NationClaimAccessLevel;
import com.monpai.sailboatmod.nation.service.TownClaimService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.OpenTownMenuPacket;
import com.monpai.sailboatmod.network.packet.SetTownClaimPermissionPacket;
import com.monpai.sailboatmod.network.packet.TownGuiActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TownHomeScreen extends Screen {
    private static final int SCREEN_W = 440;
    private static final int SCREEN_H = 304;
    private static final int TAB_W = 90;
    private static final int BODY_X = 12;
    private static final int BODY_Y = 60;
    private static final int BODY_W = SCREEN_W - 24;
    private static final int BODY_H = 220;
    private static final int CLAIM_MAP_W = 164;
    private static final int CLAIM_MAP_H = 164;
    private int claimRadius() {
        int size = this.data.nearbyTerrainColors().size();
        if (size <= 0) return com.monpai.sailboatmod.ModConfig.claimPreviewRadius();
        int diameter = (int) Math.round(Math.sqrt(size));
        return (diameter - 1) / 2;
    }
    private static final int AUTO_REFRESH_INTERVAL_TICKS = 40;
    private static final int MEMBER_LIST_W = 180;
    private static final int MEMBER_ROW_H = 14;
    private static final int MEMBER_VISIBLE_ROWS = 8;

    private TownOverviewData data;
    private Page currentPage = Page.OVERVIEW;
    private EditBox townNameInput;
    private EditBox flagPathInput;
    private Component statusLine = Component.empty();
    private Button refreshButton;
    private Button overviewTabButton;
    private Button membersTabButton;
    private Button claimsTabButton;
    private Button flagTabButton;
    private Button economyTabButton;
    private Button saveTownNameButton;
    private Button appointMayorButton;
    private Button claimButton;
    private Button unclaimButton;
    private Button breakPermissionButton;
    private Button placePermissionButton;
    private Button usePermissionButton;
    private Button containerPermissionButton;
    private Button redstonePermissionButton;
    private Button entityUsePermissionButton;
    private Button entityDamagePermissionButton;
    private Button uploadButton;
    private Button browseButton;
    private Button toggleMirrorButton;
    private Button abandonTownButton;
    private int selectedClaimChunkX = Integer.MIN_VALUE;
    private int selectedClaimChunkZ = Integer.MIN_VALUE;
    private int areaCorner1X = Integer.MIN_VALUE;
    private int areaCorner1Z = Integer.MIN_VALUE;
    private int areaCorner2X = Integer.MIN_VALUE;
    private int areaCorner2Z = Integer.MIN_VALUE;
    private int claimsSubPage;
    private Button claimsSubPageButton;
    private Button resetMapButton;
    private int autoRefreshTicks;
    private int memberScroll;
    private String selectedMemberUuid = "";
    private int mapOffsetX = 0;
    private int mapOffsetZ = 0;
    private boolean isDraggingMap = false;
    private double dragStartX = 0;
    private double dragStartY = 0;
    private int pendingPreviewCenterX = Integer.MIN_VALUE;
    private int pendingPreviewCenterZ = Integer.MIN_VALUE;
    private boolean resetPending = false;
    private boolean refreshPending;

    public TownHomeScreen(TownOverviewData data) {
        super(Component.translatable("screen.sailboatmod.town.home.title"));
        this.data = data == null ? TownOverviewData.empty() : data;
        syncSelections();
    }

    public void updateData(TownOverviewData updated) {
        int visibleCenterX = mapCenterX();
        int visibleCenterZ = mapCenterZ();
        this.data = updated == null ? TownOverviewData.empty() : updated;
        this.refreshPending = false;
        this.autoRefreshTicks = 0;
        if (this.data.previewCenterChunkX() == this.pendingPreviewCenterX && this.data.previewCenterChunkZ() == this.pendingPreviewCenterZ) {
            this.pendingPreviewCenterX = Integer.MIN_VALUE;
            this.pendingPreviewCenterZ = Integer.MIN_VALUE;
        }
        if (this.resetPending) {
            this.mapOffsetX = 0;
            this.mapOffsetZ = 0;
            this.resetPending = false;
        } else {
            this.mapOffsetX = visibleCenterX - this.data.previewCenterChunkX();
            this.mapOffsetZ = visibleCenterZ - this.data.previewCenterChunkZ();
        }
        this.memberScroll = clampMemberScroll(this.memberScroll);
        syncSelections();
        syncTownNameInput();
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
        this.statusLine = Component.translatable("screen.sailboatmod.town.status.synced");
        updateButtonState();
    }

    @Override
    protected void init() {
        int left = left();
        int top = top();
        this.refreshButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.town.action.refresh"), b -> requestRefresh()).bounds(left + SCREEN_W - 82, top + 12, 70, 18).build());
        this.overviewTabButton = addTabButton(left + 12, top + 34, Page.OVERVIEW, Component.translatable("screen.sailboatmod.town.section.overview"));
        this.membersTabButton = addTabButton(left + 110, top + 34, Page.MEMBERS, Component.translatable("screen.sailboatmod.town.section.members"));
        this.claimsTabButton = addTabButton(left + 208, top + 34, Page.CLAIMS, Component.translatable("screen.sailboatmod.town.section.claims"));
        this.flagTabButton = addTabButton(left + 306, top + 34, Page.FLAG, Component.translatable("screen.sailboatmod.town.section.flag"));
        this.economyTabButton = addTabButton(left + 12, top + 54, Page.ECONOMY, Component.translatable("screen.sailboatmod.town.section.economy"));

        this.townNameInput = new EditBox(this.font, left + BODY_X + 12, top + BODY_Y + 148, 144, 18, Component.translatable("screen.sailboatmod.town.name"));
        this.townNameInput.setMaxLength(24);
        this.addRenderableWidget(this.townNameInput);
        this.saveTownNameButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.town.action.save_info"), b -> submitRenameTown()).bounds(left + BODY_X + 162, top + BODY_Y + 148, 96, 18).build());
        this.abandonTownButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.town.action.abandon"), b -> submitAbandonTown()).bounds(left + BODY_X + 266, top + BODY_Y + 148, 96, 18).build());
        this.appointMayorButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.town.action.appoint_mayor"), b -> appointSelectedMayor()).bounds(left + BODY_X + 222, top + BODY_Y + 196, 180, 18).build());

        this.claimButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.claim"), b -> claimSelectedChunk()).bounds(left + BODY_X + 12, top + BODY_Y + BODY_H - 26, 72, 18).build());
        this.unclaimButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.unclaim"), b -> unclaimSelectedChunk()).bounds(left + BODY_X + 90, top + BODY_Y + BODY_H - 26, 86, 18).build());
        this.claimsSubPageButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.claims.show_perms"), b -> { this.claimsSubPage = this.claimsSubPage == 0 ? 1 : 0; updateButtonState(); }).bounds(left + BODY_X + 184, top + BODY_Y + BODY_H - 26, 120, 18).build());
        this.resetMapButton = this.addRenderableWidget(Button.builder(Component.literal("\u2316"), b -> resetMapOffset()).bounds(left + BODY_X + BODY_W - CLAIM_MAP_W - 16, top + BODY_Y + 10, 24, 14).build());
        this.breakPermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("break", selectedBreakAccessLevel())).bounds(left + BODY_X + 12, top + BODY_Y + 50, 100, 18).build());
        this.placePermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("place", selectedPlaceAccessLevel())).bounds(left + BODY_X + 120, top + BODY_Y + 50, 100, 18).build());
        this.usePermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("use", selectedUseAccessLevel())).bounds(left + BODY_X + 12, top + BODY_Y + 74, 100, 18).build());
        this.containerPermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("container", selectedContainerAccessLevel())).bounds(left + BODY_X + 120, top + BODY_Y + 74, 100, 18).build());
        this.redstonePermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("redstone", selectedRedstoneAccessLevel())).bounds(left + BODY_X + 12, top + BODY_Y + 98, 100, 18).build());
        this.entityUsePermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("entity_use", selectedEntityUseAccessLevel())).bounds(left + BODY_X + 120, top + BODY_Y + 98, 100, 18).build());
        this.entityDamagePermissionButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleClaimPermission("entity_damage", selectedEntityDamageAccessLevel())).bounds(left + BODY_X + 12, top + BODY_Y + 122, 208, 18).build());

        this.flagPathInput = new EditBox(this.font, left + BODY_X + 12, top + BODY_Y + BODY_H - 54, 248, 18, Component.translatable("screen.sailboatmod.town.flag_path"));
        this.flagPathInput.setMaxLength(512);
        this.addRenderableWidget(this.flagPathInput);
        this.browseButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.browse"), b -> browseForImage()).bounds(left + BODY_X + 268, top + BODY_Y + BODY_H - 54, 56, 18).build());
        this.uploadButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.upload"), b -> submitUpload()).bounds(left + BODY_X + 330, top + BODY_Y + BODY_H - 54, 72, 18).build());
        this.toggleMirrorButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.action.toggle_mirror"), b -> submitMirrorToggle()).bounds(left + BODY_X + 170, top + BODY_Y + 82, 126, 18).build());

        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_name.cancel"), b -> onClose()).bounds(left + SCREEN_W - 82, top + SCREEN_H - 24, 70, 18).build());

        syncTownNameInput();
        updateButtonState();
    }

    private Button addTabButton(int x, int y, Page page, Component label) {
        return this.addRenderableWidget(Button.builder(label, b -> switchPage(page)).bounds(x, y, TAB_W, 18).build());
    }

    @Override
    public void removed() {
        super.removed();
        com.monpai.sailboatmod.client.TownClientHooks.onScreenClosed();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.townNameInput != null) this.townNameInput.tick();
        if (this.flagPathInput != null) this.flagPathInput.tick();
        tickAutoRefresh();
        updateButtonState();
    }

    private void tickAutoRefresh() {
        if (!this.data.hasTown() || this.refreshPending) {
            this.autoRefreshTicks = 0;
            return;
        }
        this.autoRefreshTicks++;
        if (this.autoRefreshTicks < AUTO_REFRESH_INTERVAL_TICKS) {
            return;
        }
        this.autoRefreshTicks = 0;
        requestRefresh();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            if (this.currentPage == Page.OVERVIEW && this.townNameInput != null && this.townNameInput.isFocused()) return submitAndTrue(this::submitRenameTown);
            if (this.currentPage == Page.FLAG && this.flagPathInput != null && this.flagPathInput.isFocused()) return submitAndTrue(this::submitUpload);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.currentPage == Page.MEMBERS) {
            int[] bounds = memberListBounds();
            if (mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2] && mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3]) {
                this.memberScroll = clampMemberScroll(this.memberScroll + (delta > 0 ? -1 : 1));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.currentPage == Page.MEMBERS && trySelectMember(mouseX, mouseY)) return true;
        if (button == 0 && this.currentPage == Page.CLAIMS && trySelectClaim(mouseX, mouseY)) return true;
        if (button == 2 && this.currentPage == Page.CLAIMS) {
            int mapX = claimMapX(left() + BODY_X);
            int mapY = claimMapY(top() + BODY_Y);
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
            maybeRequestPreviewRefresh();
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
        g.drawString(this.font, headerText(), left + 136, top + 18, 0xFFDCEEFF);
        drawPanelFrame(g, left + BODY_X, top + BODY_Y, BODY_W, BODY_H);
        g.drawString(this.font, this.currentPage.title(), left + BODY_X + 10, top + BODY_Y + 10, 0xFFE7C977);
        switch (this.currentPage) {
            case OVERVIEW -> drawOverviewPage(g, left + BODY_X, top + BODY_Y);
            case MEMBERS -> drawMembersPage(g, left + BODY_X, top + BODY_Y);
            case CLAIMS -> drawClaimsPage(g, left + BODY_X, top + BODY_Y, mouseX, mouseY);
            case FLAG -> drawFlagPage(g, left + BODY_X, top + BODY_Y);
            case ECONOMY -> drawEconomyPage(g, left + BODY_X, top + BODY_Y, mouseX, mouseY);
        }
        if (!this.statusLine.getString().isBlank()) g.drawCenteredString(this.font, this.statusLine, left + SCREEN_W / 2, top + SCREEN_H - 12, 0xFFF1D98A);
    }

    private void drawOverviewPage(GuiGraphics g, int x, int y) {
        int drawY = y + 34;
        for (Component line : buildOverviewLines()) {
            drawWrappedLine(g, line, x + 12, drawY, BODY_W - 24, 0xFFDCEEFF);
            drawY += wrappedHeight(line, BODY_W - 24) + 6;
        }
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.name"), x + 12, y + 126, 0xFFB8C0C8);
        drawWrappedLine(g, Component.translatable("screen.sailboatmod.town.overview.manage_hint"), x + 12, y + 176, BODY_W - 24, 0xFF8D98A3);
    }

    private void drawMembersPage(GuiGraphics g, int x, int y) {
        int[] bounds = memberListBounds();
        int listX = bounds[0];
        int listY = bounds[1];
        int listW = bounds[2];
        int listH = bounds[3];
        int panelX = x + 214;
        int panelY = y + 42;
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.members.list_title"), listX, y + 14, 0xFFB8C0C8);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.members.detail_title"), panelX, y + 14, 0xFFB8C0C8);
        g.fill(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, 0xAA4C5E6A);
        g.fill(listX, listY, listX + listW, listY + listH, 0xAA182632);
        if (this.data.members().isEmpty()) {
            drawWrappedLine(g, Component.translatable("screen.sailboatmod.town.members.empty"), listX + 8, listY + 8, listW - 16, 0xFF8D98A3);
        } else {
            int start = clampMemberScroll(this.memberScroll);
            int end = Math.min(this.data.members().size(), start + MEMBER_VISIBLE_ROWS);
            int rowY = listY + 4;
            for (int i = start; i < end; i++) {
                NationOverviewMember member = this.data.members().get(i);
                boolean selected = member.playerUuid().equals(this.selectedMemberUuid);
                int bg = selected ? 0x886C8EA1 : 0x44304250;
                g.fill(listX + 4, rowY - 1, listX + listW - 4, rowY + MEMBER_ROW_H - 1, bg);
                String office = member.officeName().isBlank() ? member.officeId() : member.officeName();
                String label = trimToWidth(member.playerName(), 86) + " | " + trimToWidth(office, 60);
                g.drawString(this.font, label, listX + 8, rowY + 2, member.online() ? 0xFFE8F6FF : 0xFFB5C2CC);
                rowY += MEMBER_ROW_H;
            }
        }
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.overview.mayor_current", this.data.mayorName()), panelX, panelY, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.members.total", this.data.members().size()), panelX, panelY + 14, 0xFFB8C0C8);
        NationOverviewMember selected = selectedMember();
        if (selected == null) {
            drawWrappedLine(g, Component.translatable("screen.sailboatmod.town.members.no_selection"), panelX, panelY + 36, 180, 0xFF8D98A3);
            return;
        }
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.members.selected"), panelX, panelY + 36, 0xFFB8C0C8);
        g.drawString(this.font, trimToWidth(selected.playerName(), 176), panelX, panelY + 50, 0xFFDCEEFF);
        String officeText = selected.officeName().isBlank() ? selected.officeId() : selected.officeName();
        g.drawString(this.font, trimToWidth(officeText, 176), panelX, panelY + 64, 0xFFB8C0C8);
        g.drawString(this.font, Component.translatable(selected.online() ? "screen.sailboatmod.town.members.status.online" : "screen.sailboatmod.town.members.status.offline"), panelX, panelY + 78, 0xFF8D98A3);
        drawWrappedLine(g, Component.translatable("screen.sailboatmod.town.members.manage_hint"), panelX, panelY + 102, 180, 0xFF8D98A3);
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
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.claims.map_title"), mapX, y + 12, 0xFFB8C0C8);
        drawClaimMap(g, mapX, mapY, mouseX, mouseY);
    }

    private void drawClaimsPermPage(GuiGraphics g, int x, int y) {
        g.drawString(this.font, Component.translatable("screen.sailboatmod.nation.claims.perms_title"), x + 12, y + 30, 0xFFB8C0C8);
        NationOverviewClaim selected = firstSelectedAreaClaim();
        if (selected == null) {
            drawWrappedLine(g, Component.translatable("screen.sailboatmod.nation.claims.perms_select_hint"), x + 12, y + 150, BODY_W - 24, 0xFF8D98A3);
        } else {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.town.claims.selected_chunk", this.selectedClaimChunkX, this.selectedClaimChunkZ), x + 12, y + 150, 0xFFDCEEFF);
            g.drawString(this.font, Component.translatable("screen.sailboatmod.town.claims.owner", selected.nationName()), x + 12, y + 166, 0xFFB8C0C8);
        }
    }

    private void drawFlagPage(GuiGraphics g, int x, int y) {
        List<Component> lines = buildFlagLines();
        ResourceLocation texture = NationFlagTextureCache.resolve(this.data.flagId(), this.data.primaryColorRgb(), this.data.secondaryColorRgb(), this.data.flagMirrored());
        int frameX = x + 20;
        int frameY = y + 42;
        int frameW = 124;
        int frameH = 64;
        int textureWidth = previewFlagWidth();
        int textureHeight = previewFlagHeight();
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
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.flag.upload_hint_short"), x + 12, y + BODY_H - 82, 0xFFB8C0C8);
    }

    private void drawEconomyPage(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        if (!this.data.hasTown()) {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.no_town"), x + 12, y + 40, 0xFFDCEEFF);
            return;
        }

        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.population", this.data.residentCount()), x + 12, y + 30, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.employment", formatPercent(this.data.employmentRate())), x + 12, y + 44, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.literacy", formatPercent(this.data.averageLiteracy())), x + 12, y + 58, 0xFFDCEEFF);

        int leftX = x + 12;
        int rightX = x + 224;
        int baseY = y + 92;
        int rowH = 14;

        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.stockpile_types", this.data.stockpileCommodityTypes()), leftX, baseY, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.stockpile_units", this.data.stockpileTotalUnits()), leftX, baseY + rowH, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.open_demands", this.data.openDemandCount()), leftX, baseY + rowH * 2, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.shortage_units", this.data.openDemandUnits()), leftX, baseY + rowH * 3, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.active_procurements", this.data.activeProcurementCount()), leftX, baseY + rowH * 4, 0xFFDCEEFF);

        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.total_income", formatMoney(this.data.totalIncome())), rightX, baseY, 0xFFBDF5C8);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.total_expense", formatMoney(this.data.totalExpense())), rightX, baseY + rowH, 0xFFFFC7C7);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.net_balance", formatMoney(this.data.netBalance())), rightX, baseY + rowH * 2, this.data.netBalance() >= 0L ? 0xFFBDF5C8 : 0xFFFFC7C7);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.town.economy.summary_hint"), leftX, y + BODY_H - 32, 0xFFB8C0C8);
    }

    private static String formatPercent(float ratio) {
        return Math.round(Math.max(0.0f, Math.min(1.0f, ratio)) * 100.0f) + "%";
    }

    private static String formatMoney(long value) {
        return Long.toString(value);
    }

    private void drawProfessionPieChart(GuiGraphics g, int cx, int cy, int r, int mouseX, int mouseY) {
        int[] colors = {0xFF4A9EFF, 0xFFFF6B6B, 0xFF4ECDC4, 0xFFFFA07A, 0xFF98D8C8, 0xFFFFD93D, 0xFFB4A7D6, 0xFFE0E0E0};
        String[] labels = {"Farmer", "Miner", "Lumberjack", "Fisherman", "Blacksmith", "Baker", "Guard", "Unemployed"};
        int[] values = {8, 6, 5, 4, 3, 3, 3, 13};

        int total = 0;
        for (int v : values) total += v;

        float angle = 0;
        for (int i = 0; i < values.length; i++) {
            float sweep = (float) values[i] / total * 360;
            drawPieSlice(g, cx, cy, r, angle, sweep, colors[i]);

            // Tooltip
            if (isMouseInPieSlice(mouseX, mouseY, cx, cy, r, angle, sweep)) {
                g.renderTooltip(this.font, Component.literal(labels[i] + ": " + values[i]), mouseX, mouseY);
            }
            angle += sweep;
        }
    }

    private void drawPieSlice(GuiGraphics g, int cx, int cy, int r, float startAngle, float sweep, int color) {
        for (float a = startAngle; a < startAngle + sweep; a += 1) {
            double rad = Math.toRadians(a - 90);
            int x1 = cx + (int)(Math.cos(rad) * r);
            int y1 = cy + (int)(Math.sin(rad) * r);
            g.fill(cx, cy, x1, y1, color);
        }
    }

    private boolean isMouseInPieSlice(int mx, int my, int cx, int cy, int r, float startAngle, float sweep) {
        int dx = mx - cx;
        int dy = my - cy;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist > r) return false;
        double angle = Math.toDegrees(Math.atan2(dy, dx)) + 90;
        if (angle < 0) angle += 360;
        return angle >= startAngle && angle < startAngle + sweep;
    }

    private void drawEconomyLineChart(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        g.fill(x, y, x + w, y + h, 0xFF2A3540);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF8090A0);
        g.fill(x, y, x + 1, y + h, 0xFF8090A0);

        // Sample data points
        int[] popData = {20, 25, 30, 35, 40, 42, 45};
        int[] empData = {60, 65, 68, 70, 72, 71, 71};

        drawLine(g, x, y, w, h, popData, 0xFF4A9EFF);
        drawLine(g, x, y, w, h, empData, 0xFF4ECDC4);

        if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h) {
            g.renderTooltip(this.font, Component.literal("Population: " + this.data.residentCount() + ", Employment: 71%"), mouseX, mouseY);
        }
    }

    private void drawLine(GuiGraphics g, int x, int y, int w, int h, int[] data, int color) {
        int step = w / (data.length - 1);
        for (int i = 0; i < data.length - 1; i++) {
            int x1 = x + i * step;
            int y1 = y + h - (data[i] * h / 100);
            int x2 = x + (i + 1) * step;
            int y2 = y + h - (data[i + 1] * h / 100);
            g.fill(x1, y1, x2, y1 + 2, color);
        }
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
        lines.add(Component.translatable("screen.sailboatmod.town.overview.claims", this.data.totalClaims()));
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

    private void updateButtonState() {
        if (this.overviewTabButton != null) this.overviewTabButton.active = this.currentPage != Page.OVERVIEW;
        if (this.membersTabButton != null) this.membersTabButton.active = this.currentPage != Page.MEMBERS;
        if (this.claimsTabButton != null) this.claimsTabButton.active = this.currentPage != Page.CLAIMS;
        if (this.flagTabButton != null) this.flagTabButton.active = this.currentPage != Page.FLAG;
        if (this.economyTabButton != null) this.economyTabButton.active = this.currentPage != Page.ECONOMY;

        boolean overviewPage = this.currentPage == Page.OVERVIEW;
        boolean membersPage = this.currentPage == Page.MEMBERS;
        boolean hasTown = this.data.hasTown();
        boolean canManageTown = hasTown && this.data.canManageTown();
        if (this.townNameInput != null) { this.townNameInput.visible = overviewPage; this.townNameInput.setEditable(overviewPage && canManageTown); }
        if (this.saveTownNameButton != null) { this.saveTownNameButton.visible = overviewPage; this.saveTownNameButton.active = overviewPage && canManageTown && townNameChanged(); }
        if (this.abandonTownButton != null) { this.abandonTownButton.visible = overviewPage && hasTown; this.abandonTownButton.active = overviewPage && hasTown && this.data.isMayor(); }
        if (this.appointMayorButton != null) { this.appointMayorButton.visible = membersPage; this.appointMayorButton.active = membersPage && hasTown && canAssignSelectedMemberAsMayor(); }

        boolean claimsPage = this.currentPage == Page.CLAIMS;
        boolean claimsMapView = claimsPage && this.claimsSubPage == 0;
        boolean claimsPermView = claimsPage && this.claimsSubPage == 1;
        boolean areaHasClaim = selectedClaimAreaHasAnyClaim();
        boolean ownClaim = selectedClaimAreaOwnedByTown();
        if (this.claimButton != null) { this.claimButton.visible = claimsMapView; this.claimButton.active = claimsMapView && hasTown && this.data.canManageTown() && !areaHasClaim; }
        if (this.unclaimButton != null) { this.unclaimButton.visible = claimsMapView; this.unclaimButton.active = claimsMapView && hasTown && this.data.canManageTown() && ownClaim; }
        if (this.claimsSubPageButton != null) { this.claimsSubPageButton.visible = claimsPage; this.claimsSubPageButton.active = claimsPage && hasTown; this.claimsSubPageButton.setMessage(Component.translatable(claimsPermView ? "screen.sailboatmod.nation.claims.show_map" : "screen.sailboatmod.nation.claims.show_perms")); }
        if (this.breakPermissionButton != null) { this.breakPermissionButton.visible = claimsPermView; this.breakPermissionButton.active = claimsPermView && hasTown && this.data.canManageTown() && ownClaim; this.breakPermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.break", accessName(selectedBreakAccessLevel()))); }
        if (this.placePermissionButton != null) { this.placePermissionButton.visible = claimsPermView; this.placePermissionButton.active = claimsPermView && hasTown && this.data.canManageTown() && ownClaim; this.placePermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.place", accessName(selectedPlaceAccessLevel()))); }
        if (this.usePermissionButton != null) { this.usePermissionButton.visible = claimsPermView; this.usePermissionButton.active = claimsPermView && hasTown && this.data.canManageTown() && ownClaim; this.usePermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.use", accessName(selectedUseAccessLevel()))); }
        if (this.containerPermissionButton != null) { this.containerPermissionButton.visible = claimsPermView; this.containerPermissionButton.active = claimsPermView && hasTown && this.data.canManageTown() && ownClaim; this.containerPermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.container", accessName(selectedContainerAccessLevel()))); }
        if (this.redstonePermissionButton != null) { this.redstonePermissionButton.visible = claimsPermView; this.redstonePermissionButton.active = claimsPermView && hasTown && this.data.canManageTown() && ownClaim; this.redstonePermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.redstone", accessName(selectedRedstoneAccessLevel()))); }
        if (this.entityUsePermissionButton != null) { this.entityUsePermissionButton.visible = claimsPermView; this.entityUsePermissionButton.active = claimsPermView && hasTown && this.data.canManageTown() && ownClaim; this.entityUsePermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.entity_use", accessName(selectedEntityUseAccessLevel()))); }
        if (this.entityDamagePermissionButton != null) { this.entityDamagePermissionButton.visible = claimsPermView; this.entityDamagePermissionButton.active = claimsPermView && hasTown && this.data.canManageTown() && ownClaim; this.entityDamagePermissionButton.setMessage(Component.translatable("screen.sailboatmod.nation.claims.button.entity_damage", accessName(selectedEntityDamageAccessLevel()))); }
        if (this.resetMapButton != null) { this.resetMapButton.visible = claimsPage; this.resetMapButton.active = claimsPage; }

        boolean flagPage = this.currentPage == Page.FLAG;
        if (this.flagPathInput != null) { this.flagPathInput.visible = flagPage; this.flagPathInput.setEditable(flagPage && hasTown && this.data.canUploadFlag()); }
        if (this.browseButton != null) { this.browseButton.visible = flagPage; this.browseButton.active = flagPage && hasTown && this.data.canUploadFlag(); }
        if (this.uploadButton != null) { this.uploadButton.visible = flagPage; this.uploadButton.active = flagPage && hasTown && this.data.canUploadFlag(); }
        if (this.toggleMirrorButton != null) {
            boolean canMirror = flagPage && hasTown && this.data.canUploadFlag() && !this.data.flagId().isBlank();
            this.toggleMirrorButton.visible = flagPage;
            this.toggleMirrorButton.active = canMirror;
            this.toggleMirrorButton.setMessage(Component.translatable(this.data.flagMirrored() ? "screen.sailboatmod.nation.action.unmirror" : "screen.sailboatmod.nation.action.mirror"));
        }
    }
    private void drawClaimMap(GuiGraphics g, int mapX, int mapY, int mouseX, int mouseY) {
        g.fill(mapX - 1, mapY - 1, mapX + CLAIM_MAP_W + 1, mapY + CLAIM_MAP_H + 1, 0xFF6F8390);
        g.fill(mapX, mapY, mapX + CLAIM_MAP_W, mapY + CLAIM_MAP_H, 0xFF22323C);
        int centerX = mapCenterX();
        int centerZ = mapCenterZ();
        for (int gz = 0; gz <= claimRadius() * 2; gz++) {
            int y1 = mapY + gz * CLAIM_MAP_H / (claimRadius() * 2 + 1);
            int y2 = mapY + (gz + 1) * CLAIM_MAP_H / (claimRadius() * 2 + 1);
            int chunkZ = centerZ + gz - claimRadius();
            for (int gx = 0; gx <= claimRadius() * 2; gx++) {
                int x1 = mapX + gx * CLAIM_MAP_W / (claimRadius() * 2 + 1);
                int x2 = mapX + (gx + 1) * CLAIM_MAP_W / (claimRadius() * 2 + 1);
                int chunkX = centerX + gx - claimRadius();
                int color = sampleClaimTerrainColor(chunkX, chunkZ);
                NationOverviewClaim claim = findClaim(chunkX, chunkZ);
                if (claim != null) {
                    double overlayStrength = this.data.nationId().equals(claim.nationId()) ? 0.50D : 0.38D;
                    color = blendColor(color, 0xFF000000 | claim.primaryColorRgb(), overlayStrength);
                }
                g.fill(x1, y1, Math.max(x1 + 1, x2), Math.max(y1 + 1, y2), color);
            }
        }
        for (int gz = 0; gz <= claimRadius() * 2; gz++) {
            int y1 = mapY + gz * CLAIM_MAP_H / (claimRadius() * 2 + 1);
            int y2 = mapY + (gz + 1) * CLAIM_MAP_H / (claimRadius() * 2 + 1);
            int chunkZ = centerZ + gz - claimRadius();
            for (int gx = 0; gx <= claimRadius() * 2; gx++) {
                int x1 = mapX + gx * CLAIM_MAP_W / (claimRadius() * 2 + 1);
                int x2 = mapX + (gx + 1) * CLAIM_MAP_W / (claimRadius() * 2 + 1);
                int chunkX = centerX + gx - claimRadius();
                NationOverviewClaim claim = findClaim(chunkX, chunkZ);
                if (claim == null) continue;
                int borderColor = 0xFF000000 | claim.secondaryColorRgb();
                String ownerId = claimOwnerKey(claim);
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
        String hoverId = hoverClaim.townId().isBlank() ? hoverClaim.nationId() : hoverClaim.townId();
        String label = hoverClaim.townId().isBlank() ? hoverClaim.nationName() : hoverClaim.townName();
        if (label.isBlank()) return;
        int count = 0;
        for (NationOverviewClaim c : this.data.nearbyClaims()) {
            String cid = c.townId().isBlank() ? c.nationId() : c.townId();
            if (cid.equals(hoverId)) count++;
        }
        int color = 0xFF000000 | hoverClaim.primaryColorRgb();
        String text = label + "(" + count + ")";
        int tw = this.font.width(text);
        int tx = Math.max(mapX, Math.min(mouseX - tw / 2, mapX + CLAIM_MAP_W - tw));
        int ty = Math.max(mapY, Math.min(mouseY - 14, mapY + CLAIM_MAP_H - 10));
        g.fill(tx - 1, ty - 1, tx + tw + 1, ty + 9, 0xCC000000);
        g.drawString(this.font, text, tx, ty, color);
    }

    private int sampleClaimTerrainColor(int chunkX, int chunkZ) {
        int gridX = chunkX - this.data.previewCenterChunkX() + claimRadius();
        int gridZ = chunkZ - this.data.previewCenterChunkZ() + claimRadius();
        int diameter = claimRadius() * 2 + 1;
        if (gridX >= 0 && gridX < diameter && gridZ >= 0 && gridZ < diameter) {
            int index = gridZ * diameter + gridX;
            if (index >= 0 && index < this.data.nearbyTerrainColors().size()) {
                return this.data.nearbyTerrainColors().get(index);
            }
        }
        Integer cached = TerrainColorClientCache.get(chunkX, chunkZ);
        if (cached != null) return cached;
        return sampleLocalTerrainColor(chunkX, chunkZ);
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

    private void switchPage(Page page) {
        this.currentPage = page;
        updateButtonState();
    }

    private void requestRefresh() {
        requestRefresh(mapCenterX(), mapCenterZ());
    }

    private void requestRefresh(int centerChunkX, int centerChunkZ) {
        if (this.refreshPending) {
            return;
        }
        this.refreshPending = true;
        this.pendingPreviewCenterX = centerChunkX;
        this.pendingPreviewCenterZ = centerChunkZ;
        ModNetwork.CHANNEL.sendToServer(new OpenTownMenuPacket(this.data.townId(), centerChunkX, centerChunkZ));
        this.statusLine = Component.translatable("screen.sailboatmod.town.status.refreshing");
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

    private void maybeRequestPreviewRefresh() {
        int centerChunkX = mapCenterX();
        int centerChunkZ = mapCenterZ();
        boolean farFromPreview = Math.abs(centerChunkX - this.data.previewCenterChunkX()) >= 1
                || Math.abs(centerChunkZ - this.data.previewCenterChunkZ()) >= 1;
        if (!farFromPreview) return;
        if (centerChunkX == this.pendingPreviewCenterX && centerChunkZ == this.pendingPreviewCenterZ) return;
        requestRefresh(centerChunkX, centerChunkZ);
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
    private void syncTownNameInput() {
        if (this.townNameInput != null && !this.townNameInput.isFocused()) {
            this.townNameInput.setValue(this.data.townName());
        }
    }

    private void syncSelections() {
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

    private boolean trySelectMember(double mouseX, double mouseY) {
        int[] bounds = memberListBounds();
        if (mouseX < bounds[0] || mouseX >= bounds[0] + bounds[2] || mouseY < bounds[1] || mouseY >= bounds[1] + bounds[3] || this.data.members().isEmpty()) return false;
        int row = (int) ((mouseY - bounds[1] - 4) / MEMBER_ROW_H);
        if (row < 0 || row >= MEMBER_VISIBLE_ROWS) return false;
        int index = clampMemberScroll(this.memberScroll) + row;
        if (index < 0 || index >= this.data.members().size()) return false;
        this.selectedMemberUuid = this.data.members().get(index).playerUuid();
        updateButtonState();
        return true;
    }

    private boolean trySelectClaim(double mouseX, double mouseY) {
        int mapX = claimMapX(left() + BODY_X);
        int mapY = claimMapY(top() + BODY_Y);
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
        updateButtonState();
        return true;
    }

    private int[] memberListBounds() { return new int[] { left() + BODY_X + 12, top() + BODY_Y + 42, MEMBER_LIST_W, MEMBER_VISIBLE_ROWS * MEMBER_ROW_H + 8 }; }
    private int claimMapX(int bodyX) { return bodyX + BODY_W - CLAIM_MAP_W - 16; }
    private int claimMapY(int bodyY) { return bodyY + 24; }
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

    private NationOverviewClaim findClaim(int x, int z) { for (NationOverviewClaim claim : this.data.nearbyClaims()) if (claim.chunkX() == x && claim.chunkZ() == z) return claim; return null; }
    private boolean sameOwner(String ownerId, int x, int z) { NationOverviewClaim c = findClaim(x, z); return c != null && ownerId.equals(claimOwnerKey(c)); }
    private String claimOwnerKey(NationOverviewClaim claim) { return claim == null ? "" : (claim.townId().isBlank() ? claim.nationId() : claim.townId()); }
    private boolean canAssignSelectedMemberAsMayor() { NationOverviewMember selected = selectedMember(); return this.data.canAssignMayor() && selected != null && !selected.playerUuid().equals(this.data.mayorUuid()); }
    private boolean townNameChanged() { return !valueOf(this.townNameInput).trim().equals(this.data.townName()); }
    private String valueOf(EditBox box) { return box == null ? "" : box.getValue(); }
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
    private Component firstLine(List<Component> lines) { return lines.size() > 0 ? lines.get(0) : Component.empty(); }
    private Component secondLine(List<Component> lines) { return lines.size() > 1 ? lines.get(1) : Component.empty(); }
    private Component thirdLine(List<Component> lines) { return lines.size() > 2 ? lines.get(2) : Component.empty(); }
    private int left() { return (this.width - SCREEN_W) / 2; }
    private int top() { return (this.height - SCREEN_H) / 2; }

    private int sampleLocalTerrainColor(int chunkX, int chunkZ) {
        Integer cached = TerrainColorClientCache.get(chunkX, chunkZ);
        if (cached != null) return cached;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.hasChunk(chunkX, chunkZ)) return 0xFF33414A;

        int color = 0xFF33414A;
        try {
            int worldX = (chunkX << 4) + 8;
            int worldZ = (chunkZ << 4) + 8;
            int worldY = minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
            if (worldY >= minecraft.level.getMinBuildHeight()) {
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, worldY, worldZ);
                BlockState state = minecraft.level.getBlockState(pos);
                while (state.isAir() && worldY > minecraft.level.getMinBuildHeight()) {
                    worldY--;
                    pos.set(worldX, worldY, worldZ);
                    state = minecraft.level.getBlockState(pos);
                }
                if (state.getFluidState().is(FluidTags.WATER)) {
                    color = 0xFF2F8FBF;
                } else {
                    MapColor mapColor = state.getMapColor(minecraft.level, pos);
                    int base = mapColor == null ? 0x55606A : mapColor.col;
                    color = 0xFF000000 | (base & 0x00FFFFFF);
                }
            }
        } catch (Exception ignored) {
            color = 0xFF33414A;
        }

        TerrainColorClientCache.put(chunkX, chunkZ, color);
        return color;
    }

    private void drawPanelFrame(GuiGraphics g, int x, int y, int w, int h) { g.fill(x, y, x + w, y + h, 0x66203037); g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x66131C23); }
    private void drawWrappedLine(GuiGraphics g, Component line, int x, int y, int w, int color) { int drawY = y; for (FormattedCharSequence seq : this.font.split(line, w)) { g.drawString(this.font, seq, x, drawY, color); drawY += 10; } }
    private int wrappedHeight(Component line, int width) { return Math.max(10, this.font.split(line, width).size() * 10); }
    private void drawRect(GuiGraphics g, int x1, int y1, int x2, int y2, int color) { int minX = Math.min(x1, x2), maxX = Math.max(x1, x2), minY = Math.min(y1, y2), maxY = Math.max(y1, y2); g.fill(minX, minY, maxX + 1, minY + 1, color); g.fill(minX, maxY, maxX + 1, maxY + 1, color); g.fill(minX, minY, minX + 1, maxY + 1, color); g.fill(maxX, minY, maxX + 1, maxY + 1, color); }

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
