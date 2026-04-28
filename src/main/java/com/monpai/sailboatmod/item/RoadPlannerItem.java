package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerClaimOverlay;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.ManualRoadPlannerService;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.OpenRoadPlannerScreenPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadPlannerActionMenuPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerActionMenuMode;
import com.monpai.sailboatmod.roadplanner.model.RoadPlanningSession;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerClaimOverlayService;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerDestinationService;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerSessionService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.network.NetworkDirection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RoadPlannerItem extends Item {
    public enum EntryAction {
        OPEN_NEW_PLANNER(true),
        SET_CURRENT_POSITION_DESTINATION(false);

        private final boolean opensPlanner;

        EntryAction(boolean opensPlanner) {
            this.opensPlanner = opensPlanner;
        }

        public boolean opensPlanner() {
            return opensPlanner;
        }

        public boolean storesDestinationOnly() {
            return !opensPlanner;
        }
    }

    public RoadPlannerItem(Properties properties) {
        super(properties);
    }

    public static EntryAction entryAction(boolean sneaking) {
        return sneaking ? EntryAction.SET_CURRENT_POSITION_DESTINATION : EntryAction.OPEN_NEW_PLANNER;
    }

    public static boolean usesLegacyManualPlanner() {
        return false;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (openActiveActionMenu(serverPlayer)) {
                return InteractionResultHolder.sidedSuccess(stack, false);
            }
            EntryAction action = entryAction(player.isShiftKeyDown());
            if (action.opensPlanner()) {
                openMainActionMenu(serverPlayer);
            } else {
                serverPlayer.sendSystemMessage(ManualRoadPlannerService.openTargetSelection(serverPlayer, stack, hand == InteractionHand.OFF_HAND));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private boolean openActiveActionMenu(ServerPlayer player) {
        RoadPlannerBuildControlService service = RoadPlannerBuildControlService.global();
        Optional<UUID> preview = service.previewFor(player.getUUID());
        if (preview.isPresent()) {
            sendActionMenu(player, RoadPlannerActionMenuMode.PREVIEW, preview.get());
            return true;
        }
        Optional<UUID> build = service.buildFor(player.getUUID());
        if (build.isPresent()) {
            sendActionMenu(player, RoadPlannerActionMenuMode.BUILDING, build.get());
            return true;
        }
        return false;
    }

    private void openMainActionMenu(ServerPlayer player) {
        sendActionMenu(player, RoadPlannerActionMenuMode.MAIN, new UUID(0L, 0L));
    }

    private void sendActionMenu(ServerPlayer player, RoadPlannerActionMenuMode mode, UUID sessionId) {
        ModNetwork.CHANNEL.sendTo(
                new OpenRoadPlannerActionMenuPacket(mode, sessionId),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT
        );
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (!context.getLevel().isClientSide && player instanceof ServerPlayer serverPlayer && player.isShiftKeyDown()) {
            serverPlayer.sendSystemMessage(ManualRoadPlannerService.openTargetSelection(serverPlayer,
                    serverPlayer.getItemInHand(context.getHand()), context.getHand() == InteractionHand.OFF_HAND));
            return InteractionResult.CONSUME;
        }
        return super.useOn(context);
    }

    private void openSavedOrEmptyPlanner(ServerPlayer player, InteractionHand hand) {
        Optional<RoadPlannerDestinationService.TownRoute> route = RoadPlannerDestinationService.global().townRouteFor(player.getUUID());
        if (route.isPresent()) {
            RoadPlannerDestinationService.TownRoute townRoute = route.get();
            RoadPlanningSession session = RoadPlannerSessionService.global().startSession(
                    player.getUUID(),
                    player.level().dimension(),
                    townRoute.start().anchorPos(),
                    townRoute.destination().anchorPos()
            );
            NationSavedData data = NationSavedData.get(player.level());
            TownRecord startTown = data.getTown(townRoute.start().townId());
            TownRecord destinationTown = data.getTown(townRoute.destination().townId());
            sendPlannerPacket(player, hand, townRoute, session.sessionId(), RoadPlannerClaimOverlayService.collectRouteClaims(data, startTown, destinationTown));
            return;
        }
        player.sendSystemMessage(Component.literal("请先站在当前 Town 区块内 Shift+右键选择目的地 Town，再打开道路规划器。"));
    }

    private void selectTownDestination(ServerPlayer player, @Nullable BlockPos clickedPos) {
        TownRecord sourceTown = TownService.getManagedTownAt(player, player.blockPosition());
        if (sourceTown == null) {
            player.sendSystemMessage(Component.literal("需要站在你可管理的当前 Town 区块内再选择道路目的地。"));
            return;
        }
        NationSavedData data = NationSavedData.get(player.level());
        TownRecord destinationTown = resolveDestinationTown(player, data, sourceTown, clickedPos);
        if (destinationTown == null) {
            player.sendSystemMessage(Component.literal("没有找到可连接的目的地 Town。Shift+右键目标 Town 区块，或先创建另一个 Town。"));
            return;
        }

        BlockPos sourceAnchor = resolveTownAnchor(player.level(), data, sourceTown, player.blockPosition());
        BlockPos destinationAnchor = resolveTownAnchor(player.level(), data, destinationTown, clickedPos == null ? player.blockPosition() : clickedPos);
        RoadPlannerDestinationService.TownRoute route = new RoadPlannerDestinationService.TownRoute(
                new RoadPlannerDestinationService.TownEndpoint(sourceTown.townId(), sourceTown.name(), sourceAnchor),
                new RoadPlannerDestinationService.TownEndpoint(destinationTown.townId(), destinationTown.name(), destinationAnchor)
        );
        RoadPlannerDestinationService.global().saveTownDestination(player.getUUID(), route.start(), route.destination());
        player.sendSystemMessage(Component.literal("已设置道路目的地 Town: " + destinationTown.name() + "。再次普通右键道路规划器打开规划地图。"));
    }

    private TownRecord resolveDestinationTown(ServerPlayer player, NationSavedData data, TownRecord sourceTown, @Nullable BlockPos clickedPos) {
        if (clickedPos != null) {
            TownRecord clickedTown = TownService.getTownAt(player.level(), clickedPos);
            if (clickedTown != null && !clickedTown.townId().equals(sourceTown.townId())) {
                return clickedTown;
            }
        }
        return nearestOtherTown(player.level(), data, sourceTown, player.blockPosition());
    }

    private List<RoadPlannerClaimOverlay> collectRouteClaims(NationSavedData data, TownRecord start, TownRecord destination) {
        List<RoadPlannerClaimOverlay> overlays = new ArrayList<>();
        addTownClaims(data, overlays, start, RoadPlannerClaimOverlay.Role.START, 0x40D878, 0x1E8B4D);
        addTownClaims(data, overlays, destination, RoadPlannerClaimOverlay.Role.DESTINATION, 0xFF4D4D, 0xB00020);
        return overlays;
    }

    private void addTownClaims(NationSavedData data,
                               List<RoadPlannerClaimOverlay> overlays,
                               TownRecord town,
                               RoadPlannerClaimOverlay.Role role,
                               int primary,
                               int secondary) {
        if (data == null || overlays == null || town == null) {
            return;
        }
        NationRecord nation = town.nationId().isBlank() ? null : data.getNation(town.nationId());
        String nationName = nation == null ? "" : nation.name();
        for (NationClaimRecord claim : data.getClaimsForTown(town.townId())) {
            overlays.add(new RoadPlannerClaimOverlay(
                    claim.chunkX(), claim.chunkZ(), town.townId(), town.name(), town.nationId(), nationName,
                    role, primary, secondary
            ));
        }
    }

    private TownRecord nearestOtherTown(Level level, NationSavedData data, TownRecord sourceTown, BlockPos origin) {
        TownRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (TownRecord town : data.getTowns()) {
            if (town.townId().equals(sourceTown.townId())) {
                continue;
            }
            if (!hasAnchorInDimension(level, data, town)) {
                continue;
            }
            BlockPos anchor = resolveTownAnchor(level, data, town, origin);
            double distance = anchor.distSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = town;
            }
        }
        return best;
    }

    private boolean hasAnchorInDimension(Level level, NationSavedData data, TownRecord town) {
        String dimensionId = level.dimension().location().toString();
        if (town.hasCore() && dimensionId.equalsIgnoreCase(town.coreDimension())) {
            return true;
        }
        for (NationClaimRecord claim : data.getClaimsForTown(town.townId())) {
            if (dimensionId.equalsIgnoreCase(claim.dimensionId())) {
                return true;
            }
        }
        return false;
    }

    private BlockPos resolveTownAnchor(Level level, NationSavedData data, TownRecord town, BlockPos fallback) {
        String dimensionId = level.dimension().location().toString();
        if (town.hasCore() && dimensionId.equalsIgnoreCase(town.coreDimension())) {
            return BlockPos.of(town.corePos()).immutable();
        }
        for (NationClaimRecord claim : data.getClaimsForTown(town.townId())) {
            if (!dimensionId.equalsIgnoreCase(claim.dimensionId())) {
                continue;
            }
            int x = claim.chunkX() * 16 + 8;
            int z = claim.chunkZ() * 16 + 8;
            return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).immutable();
        }
        return fallback == null ? BlockPos.ZERO : fallback.immutable();
    }

    private void sendPlannerPacket(ServerPlayer player,
                                   InteractionHand hand,
                                   RoadPlannerDestinationService.TownRoute route,
                                   UUID sessionId,
                                   List<RoadPlannerClaimOverlay> claimOverlays) {
        RoadPlannerDestinationService.TownEndpoint start = route.start();
        RoadPlannerDestinationService.TownEndpoint destination = route.destination();
        int distance = (int) Math.round(Math.sqrt(start.anchorPos().distSqr(destination.anchorPos())));
        ModNetwork.CHANNEL.sendTo(
                new OpenRoadPlannerScreenPacket(
                        hand == InteractionHand.OFF_HAND,
                        start.townName(),
                        destination.townId(),
                        List.of(new RoadPlannerClientHooks.TargetEntry(destination.townId(), destination.townName(), distance)),
                        sessionId,
                        start.townId(),
                        start.anchorPos(),
                        destination.anchorPos(),
                        claimOverlays
                ),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT
        );
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.sailboatmod.road_planner.tip.use"));
        tooltip.add(Component.translatable("item.sailboatmod.road_planner.tip.select"));
        tooltip.add(Component.translatable("item.sailboatmod.road_planner.tip.confirm"));
        tooltip.add(Component.translatable("item.sailboatmod.road_planner.tip.mode"));
    }
}
