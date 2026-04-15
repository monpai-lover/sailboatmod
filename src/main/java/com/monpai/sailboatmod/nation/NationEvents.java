package com.monpai.sailboatmod.nation;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.NationCoreBlock;
import com.monpai.sailboatmod.block.TownCoreBlock;
import com.monpai.sailboatmod.nation.command.NationCommands;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.service.BankConstructionManager;
import com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService;
import com.monpai.sailboatmod.nation.service.NationClaimService;
import com.monpai.sailboatmod.nation.service.NationFlagBlockTracker;
import com.monpai.sailboatmod.nation.service.NationFlagSyncService;
import com.monpai.sailboatmod.nation.service.NationFlagUploadService;
import com.monpai.sailboatmod.nation.service.RoadPlanningTaskService;
import com.monpai.sailboatmod.nation.service.NationService;
import com.monpai.sailboatmod.nation.service.NationWarService;
import com.monpai.sailboatmod.nation.service.TerrainCacheInvalidator;
import com.monpai.sailboatmod.nation.service.TownFlagBlockTracker;
import com.monpai.sailboatmod.nation.service.TownService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NationEvents {
    private static final int CONTAINER_ACCESS_TTL_TICKS = 4;
    private static final int ROAD_SPEED_DURATION_TICKS = 40;
    private static final int ROAD_SPEED_REFRESH_THRESHOLD_TICKS = 10;
    private static final Map<UUID, TerritoryPresence> LAST_TERRITORY = new HashMap<>();
    private static final Map<UUID, String> LAST_TAB_LIST_KEYS = new HashMap<>();
    private static final Map<UUID, ContainerAccessAttempt> PENDING_CONTAINER_ACCESS = new HashMap<>();

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        NationCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NationService.updateKnownPlayer(player);
            NationFlagSyncService.syncAllFlagsTo(player);
            LAST_TERRITORY.remove(player.getUUID());
            LAST_TAB_LIST_KEYS.remove(player.getUUID());
            PENDING_CONTAINER_ACCESS.remove(player.getUUID());
            syncPlayerNames(player, true);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        NationWarService.tickWars(ServerLifecycleHooks.getCurrentServer());
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            for (var level : ServerLifecycleHooks.getCurrentServer().getAllLevels()) {
                BankConstructionManager.tick(level);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        refreshRoadTravelEffects(player);
        TerritoryPresence current = territoryPresence(player);
        TerritoryPresence previous = LAST_TERRITORY.put(player.getUUID(), current);
        if ((player.tickCount % 20) == 0) {
            syncPlayerNames(player, false);
            ClaimPreviewTerrainService.queueAround(
                    player.serverLevel(),
                    player.chunkPosition(),
                    com.monpai.sailboatmod.ModConfig.claimPreviewRadius() + 1
            );
        }
        if (previous != null && previous.sameChunk(current)) {
            return;
        }
        if (previous != null && previous.sameNation(current)) {
            return;
        }
        if (current.nationId().isBlank()) {
            if (previous != null && !previous.nationId().isBlank()) {
                NationSavedData data = NationSavedData.get(player.level());
                NationRecord leftNation = data.getNation(previous.nationId());
                if (leftNation != null) {
                    String leftName = leftNation.name().isBlank() ? leftNation.nationId() : leftNation.name();
                    MutableComponent leaveMsg = Component.translatable("actionbar.sailboatmod.nation.territory.left", leftName)
                            .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(leftNation.primaryColorRgb())));
                    player.displayClientMessage(leaveMsg, true);
                }
            }
            return;
        }

        NationSavedData data = NationSavedData.get(player.level());
        NationRecord nation = data.getNation(current.nationId());
        if (nation == null) {
            return;
        }
        player.displayClientMessage(buildTerritoryMessage(data, player, nation), true);
    }

    @SubscribeEvent
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        Component prefix = NationService.buildNamePrefix(event.getEntity().level(), event.getEntity().getUUID());
        if (prefix.getString().isBlank()) {
            return;
        }
        event.setDisplayname(Component.empty().append(prefix).append(event.getDisplayname()));
    }

    @SubscribeEvent
    public static void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        Component prefix = NationService.buildNamePrefix(event.getEntity().level(), event.getEntity().getUUID());
        if (prefix.getString().isBlank()) {
            event.setDisplayName(null);
            return;
        }
        String playerName = event.getEntity().getGameProfile().getName();
        event.setDisplayName(Component.empty().append(prefix).append(Component.literal(playerName)));
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        boolean allowed;
        if (event.getState().getBlock() instanceof TownCoreBlock) {
            allowed = false;
            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.town.core.break_denied"));
        } else if (event.getState().getBlock() instanceof NationCoreBlock) {
            allowed = false;
            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.core.break_denied"));
        } else {
            allowed = NationClaimService.canBreak(player.level(), player.getUUID(), event.getPos());
            if (!allowed) {
                player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.protect.break_denied"));
            }
        }
        if (!allowed) {
            event.setCanceled(true);
        }
        TerrainCacheInvalidator.onBlockBreak(event);
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!NationClaimService.canPlace(player.level(), player.getUUID(), event.getPos())) {
            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.protect.place_denied"));
            event.setCanceled(true);
        }
        TerrainCacheInvalidator.onBlockPlace(event);
    }

    @SubscribeEvent
    public static void onEnvironmentalBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            return;
        }
        if (!(event.getLevel() instanceof Level level) || level.isClientSide()) {
            return;
        }
        if (!isEnvironmentalGriefState(event.getPlacedBlock())) {
            return;
        }
        if (NationClaimService.isClaimed(level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        if (!(event.getLevel() instanceof Level level) || level.isClientSide()) {
            return;
        }
        if (!isEnvironmentalGriefState(event.getNewState())) {
            return;
        }
        NationRecord targetNation = NationClaimService.getNationAt(level, event.getPos());
        if (targetNation == null) {
            return;
        }
        NationRecord sourceNation = NationClaimService.getNationAt(level, event.getLiquidPos());
        if (sourceNation != null && sourceNation.nationId().equals(targetNation.nationId())) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!NationClaimService.canUseBlock(player.level(), player.getUUID(), event.getPos())) {
            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.protect.use_denied"));
            event.setCanceled(true);
            return;
        }
        rememberContainerAccess(player, event.getPos());
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!NationClaimService.canUseBlock(player.level(), player.getUUID(), event.getTarget().blockPosition())) {
            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.protect.use_denied"));
            event.setCanceled(true);
            return;
        }
        rememberContainerAccess(player, event.getTarget().blockPosition());
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!NationClaimService.canUseBlock(player.level(), player.getUUID(), event.getTarget().blockPosition())) {
            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.protect.use_denied"));
            event.setCanceled(true);
            return;
        }
        rememberContainerAccess(player, event.getTarget().blockPosition());
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ContainerAccessAttempt attempt = PENDING_CONTAINER_ACCESS.remove(player.getUUID());
        if (attempt == null || player.tickCount - attempt.tickCount() > CONTAINER_ACCESS_TTL_TICKS) {
            return;
        }
        if (!NationClaimService.canOpenContainer(player.level(), player.getUUID(), attempt.pos())) {
            player.closeContainer();
            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.protect.container_denied"));
        }
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        event.getAffectedBlocks().removeIf(pos -> NationClaimService.isClaimed(event.getLevel(), pos)
                || event.getLevel().getBlockState(pos).getBlock() instanceof TownCoreBlock
                || event.getLevel().getBlockState(pos).getBlock() instanceof NationCoreBlock);
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        LAST_TERRITORY.remove(event.getEntity().getUUID());
        LAST_TAB_LIST_KEYS.remove(event.getEntity().getUUID());
        PENDING_CONTAINER_ACCESS.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) {
            syncPlayerNames(player, true);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        LAST_TERRITORY.remove(event.getEntity().getUUID());
        LAST_TAB_LIST_KEYS.remove(event.getEntity().getUUID());
        PENDING_CONTAINER_ACCESS.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) {
            syncPlayerNames(player, true);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_TERRITORY.remove(event.getEntity().getUUID());
        LAST_TAB_LIST_KEYS.remove(event.getEntity().getUUID());
        PENDING_CONTAINER_ACCESS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LAST_TERRITORY.clear();
        LAST_TAB_LIST_KEYS.clear();
        PENDING_CONTAINER_ACCESS.clear();
        RoadPlanningTaskService.onServerStopping();
        NationWarService.clearRuntimeState();
        BankConstructionManager.clearAll();
        NationFlagUploadService.clearSessions();
        NationFlagBlockTracker.clearTrackedFlags();
        TownFlagBlockTracker.clearTrackedFlags();
        ClaimPreviewTerrainService.clearCache();
    }

    private static void rememberContainerAccess(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) {
            return;
        }
        PENDING_CONTAINER_ACCESS.put(player.getUUID(), new ContainerAccessAttempt(pos.immutable(), player.tickCount));
    }

    private static void refreshRoadTravelEffects(ServerPlayer player) {
        if (player == null || player.isSpectator()) {
            return;
        }
        BlockPos supportPos = BlockPos.containing(player.getX(), player.getBoundingBox().minY - 0.1D, player.getZ());
        if (!RoadTravelHelper.shouldGrantRoadSpeed(
                player.serverLevel().getBlockState(supportPos),
                player.serverLevel().getBlockState(supportPos.below())
        )) {
            return;
        }
        MobEffectInstance existing = player.getEffect(MobEffects.MOVEMENT_SPEED);
        if (existing != null) {
            if (existing.getAmplifier() > 0) {
                return;
            }
            if (existing.getAmplifier() == 0 && existing.getDuration() > ROAD_SPEED_REFRESH_THRESHOLD_TICKS) {
                return;
            }
        }
        player.addEffect(new MobEffectInstance(
                MobEffects.MOVEMENT_SPEED,
                ROAD_SPEED_DURATION_TICKS,
                0,
                true,
                false,
                false
        ));
    }

    private static TerritoryPresence territoryPresence(ServerPlayer player) {
        ChunkPos chunkPos = player.chunkPosition();
        NationSavedData data = NationSavedData.get(player.level());
        NationClaimRecord claim = data.getClaim(player.level(), chunkPos);
        return new TerritoryPresence(
                player.level().dimension().location().toString(),
                chunkPos.x,
                chunkPos.z,
                claim == null ? "" : claim.nationId()
        );
    }

    private static MutableComponent buildTerritoryMessage(NationSavedData data, ServerPlayer player, NationRecord territoryNation) {
        String nationName = territoryNation.name().isBlank() ? territoryNation.nationId() : territoryNation.name();
        NationMemberRecord self = data.getMember(player.getUUID());
        boolean homeNation = self != null && territoryNation.nationId().equals(self.nationId());
        MutableComponent message = homeNation
                ? Component.translatable("actionbar.sailboatmod.nation.territory.home", nationName)
                : Component.translatable("actionbar.sailboatmod.nation.territory.entered", nationName)
                        .append(Component.literal(" "))
                        .append(Component.translatable("actionbar.sailboatmod.nation.territory.leader", leaderName(data, player, territoryNation)));
        return message.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(territoryNation.primaryColorRgb())));
    }

    private static void syncPlayerNames(ServerPlayer player, boolean force) {
        if (player == null) {
            return;
        }
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        NationRecord nation = member == null ? null : data.getNation(member.nationId());
        String playerName = player.getGameProfile().getName();
        String tabKey = tabListKey(nation, member, playerName);
        if (!force && tabKey.equals(LAST_TAB_LIST_KEYS.get(player.getUUID()))) {
            return;
        }
        LAST_TAB_LIST_KEYS.put(player.getUUID(), tabKey);
        invokeRefreshName(player, "refreshDisplayName");
        invokeRefreshName(player, "refreshTabListName");
        syncBukkitDisplayName(player);
    }

    private static String tabListKey(NationRecord nation, NationMemberRecord member, String playerName) {
        if (nation == null) {
            return "|" + playerName;
        }
        String officeId = member != null ? member.officeId() : "";
        return nation.nationId() + "|" + nation.primaryColorRgb() + "|" + (nation.shortName().isBlank() ? nation.name() : nation.shortName()) + "|" + officeId + "|" + playerName;
    }

    private static String leaderName(NationSavedData data, ServerPlayer viewer, NationRecord nation) {
        if (viewer.getServer() != null) {
            ServerPlayer onlineLeader = viewer.getServer().getPlayerList().getPlayer(nation.leaderUuid());
            if (onlineLeader != null) {
                return onlineLeader.getGameProfile().getName();
            }
        }
        NationMemberRecord member = data.getMember(nation.leaderUuid());
        if (member != null && !member.lastKnownName().isBlank()) {
            return member.lastKnownName();
        }
        return nation.leaderUuid().toString();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        syncBukkitDisplayName(player);
        Component prefix = NationService.buildNamePrefix(player.level(), player.getUUID());
        if (prefix.getString().isBlank()) {
            return;
        }
        event.setMessage(Component.empty().append(prefix).append(event.getMessage()));
    }

    private static void syncBukkitDisplayName(ServerPlayer player) {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            java.lang.reflect.Method getPlayer = bukkitClass.getMethod("getPlayer", UUID.class);
            Object bukkitPlayer = getPlayer.invoke(null, player.getUUID());
            if (bukkitPlayer == null) {
                return;
            }
            NationSavedData data = NationSavedData.get(player.level());
            NationMemberRecord member = data.getMember(player.getUUID());
            NationRecord nation = member == null ? null : data.getNation(member.nationId());
            String playerName = player.getGameProfile().getName();
            String displayName;
            if (nation != null) {
                String colorCode = nearestChatColor(nation.primaryColorRgb());
                String shortName = nation.shortName().isBlank() ? NationRecord.buildShortName(nation.name()) : nation.shortName();
                String officeName = NationService.resolveOfficeName(data, member);
                displayName = colorCode + "[" + shortName + "][" + officeName + "]" + "\u00A7r" + playerName;
            } else {
                displayName = playerName;
            }
            java.lang.reflect.Method setDisplayName = bukkitPlayer.getClass().getMethod("setDisplayName", String.class);
            setDisplayName.invoke(bukkitPlayer, displayName);
        } catch (Throwable ignored) {
        }
    }

    private static String nearestChatColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int[][] colors = {
            {0, 0, 0, 0}, {0, 0, 170, 1}, {0, 170, 0, 2}, {0, 170, 170, 3},
            {170, 0, 0, 4}, {170, 0, 170, 5}, {255, 170, 0, 6}, {170, 170, 170, 7},
            {85, 85, 85, 8}, {85, 85, 255, 9}, {85, 255, 85, 0x0A}, {85, 255, 255, 0x0B},
            {255, 85, 85, 0x0C}, {255, 85, 255, 0x0D}, {255, 255, 85, 0x0E}, {255, 255, 255, 0x0F}
        };
        int bestDist = Integer.MAX_VALUE;
        int bestCode = 0x0F;
        for (int[] c : colors) {
            int dr = r - c[0], dg = g - c[1], db = b - c[2];
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) { bestDist = dist; bestCode = c[3]; }
        }
        return "\u00A7" + Integer.toHexString(bestCode);
    }

    private static void invokeRefreshName(ServerPlayer player, String methodName) {
        try {
            player.getClass().getMethod(methodName).invoke(player);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static boolean isEnvironmentalGriefState(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.getBlock() instanceof BaseFireBlock || !state.getFluidState().isEmpty();
    }

    private NationEvents() {
    }

    private record ContainerAccessAttempt(BlockPos pos, int tickCount) {
    }

    private record TerritoryPresence(String dimensionId, int chunkX, int chunkZ, String nationId) {
        private boolean sameChunk(TerritoryPresence other) {
            return other != null
                    && this.chunkX == other.chunkX
                    && this.chunkZ == other.chunkZ
                    && this.dimensionId.equals(other.dimensionId);
        }

        private boolean sameNation(TerritoryPresence other) {
            return other != null && this.nationId.equals(other.nationId());
        }
    }
}
