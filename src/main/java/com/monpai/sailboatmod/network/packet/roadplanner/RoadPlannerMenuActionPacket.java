package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerClaimOverlay;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.OpenRoadPlannerScreenPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlanningSession;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerDestinationService;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerClaimOverlayService;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerSessionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public record RoadPlannerMenuActionPacket(Action action) {
    public RoadPlannerMenuActionPacket {
        action = action == null ? Action.OPEN_PLANNER : action;
    }

    public static void encode(RoadPlannerMenuActionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.action());
    }

    public static RoadPlannerMenuActionPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerMenuActionPacket(buffer.readEnum(Action.class));
    }

    public static void handle(RoadPlannerMenuActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            if (packet.action() == Action.OPEN_DEMOLITION_PLANNER) {
                sender.sendSystemMessage(Component.literal("已进入道路拆除入口：请在道路规划地图中使用选择工具选中道路后执行拆除。"));
                return;
            }
            if (packet.action() == Action.RETURN_TO_PLANNER) {
                RoadPlannerBuildControlService.global().cancelPreview(sender.getUUID(), new java.util.UUID(0L, 0L));
            }
            openPlanner(sender);
        });
        context.setPacketHandled(true);
    }

    private static void openPlanner(ServerPlayer player) {
        Optional<RoadPlannerDestinationService.TownRoute> route = RoadPlannerDestinationService.global().townRouteFor(player.getUUID());
        if (route.isEmpty()) {
            player.sendSystemMessage(Component.literal("请先站在当前 Town 区块内 Shift+右键选择目的地 Town。"));
            return;
        }
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
        List<RoadPlannerClaimOverlay> claimOverlays = RoadPlannerClaimOverlayService.collectRouteClaims(data, startTown, destinationTown);
        int distance = (int) Math.round(Math.sqrt(townRoute.start().anchorPos().distSqr(townRoute.destination().anchorPos())));
        ModNetwork.CHANNEL.sendTo(
                new OpenRoadPlannerScreenPacket(
                        false,
                        townRoute.start().townName(),
                        townRoute.destination().townId(),
                        List.of(new RoadPlannerClientHooks.TargetEntry(townRoute.destination().townId(), townRoute.destination().townName(), distance)),
                        session.sessionId(),
                        townRoute.start().townId(),
                        townRoute.start().anchorPos(),
                        townRoute.destination().anchorPos(),
                        claimOverlays
                ),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT
        );
    }

    public enum Action {
        OPEN_PLANNER,
        RETURN_TO_PLANNER,
        OPEN_DEMOLITION_PLANNER
    }
}
