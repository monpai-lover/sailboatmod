package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.ManualRoadPlannerService;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerDestinationService;
import com.monpai.sailboatmod.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SelectRoadPlannerTargetPacket {
    private final boolean offhand;
    private final String targetTownId;

    public SelectRoadPlannerTargetPacket(boolean offhand, String targetTownId) {
        this.offhand = offhand;
        this.targetTownId = targetTownId == null ? "" : targetTownId;
    }

    public static void encode(SelectRoadPlannerTargetPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.offhand);
        buf.writeUtf(msg.targetTownId, 40);
    }

    public static SelectRoadPlannerTargetPacket decode(FriendlyByteBuf buf) {
        return new SelectRoadPlannerTargetPacket(buf.readBoolean(), buf.readUtf(40));
    }

    public static void handle(SelectRoadPlannerTargetPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ItemStack stack = findPlannerStack(player, msg.offhand);
            if (stack.isEmpty()) {
                return;
            }
            player.sendSystemMessage(ManualRoadPlannerService.setSelectedTarget(player, stack, msg.targetTownId));
            saveNewPlannerTownRoute(player, msg.targetTownId);
        });
        ctx.get().setPacketHandled(true);
    }

    private static ItemStack findPlannerStack(ServerPlayer player, boolean offhand) {
        if (player == null) {
            return ItemStack.EMPTY;
        }
        if (offhand && player.getOffhandItem().is(ModItems.ROAD_PLANNER_ITEM.get())) {
            return player.getOffhandItem();
        }
        if (player.getMainHandItem().is(ModItems.ROAD_PLANNER_ITEM.get())) {
            return player.getMainHandItem();
        }
        if (player.getOffhandItem().is(ModItems.ROAD_PLANNER_ITEM.get())) {
            return player.getOffhandItem();
        }
        return ItemStack.EMPTY;
    }

    private static void saveNewPlannerTownRoute(ServerPlayer player, String targetTownId) {
        TownRecord sourceTown = TownService.getManagedTownAt(player, player.blockPosition());
        if (sourceTown == null || targetTownId == null || targetTownId.isBlank()) {
            return;
        }
        NationSavedData data = NationSavedData.get(player.level());
        TownRecord destinationTown = data.getTown(targetTownId);
        if (destinationTown == null) {
            return;
        }
        BlockPos sourceAnchor = resolveTownAnchor(player, data, sourceTown, player.blockPosition());
        BlockPos destinationAnchor = resolveTownAnchor(player, data, destinationTown, player.blockPosition());
        RoadPlannerDestinationService.global().saveTownDestination(
                player.getUUID(),
                new RoadPlannerDestinationService.TownEndpoint(sourceTown.townId(), sourceTown.name(), sourceAnchor),
                new RoadPlannerDestinationService.TownEndpoint(destinationTown.townId(), destinationTown.name(), destinationAnchor)
        );
    }

    private static BlockPos resolveTownAnchor(ServerPlayer player, NationSavedData data, TownRecord town, BlockPos fallback) {
        String dimensionId = player.level().dimension().location().toString();
        if (town.hasCore() && dimensionId.equalsIgnoreCase(town.coreDimension())) {
            return BlockPos.of(town.corePos()).immutable();
        }
        for (NationClaimRecord claim : data.getClaimsForTown(town.townId())) {
            if (!dimensionId.equalsIgnoreCase(claim.dimensionId())) {
                continue;
            }
            int x = claim.chunkX() * 16 + 8;
            int z = claim.chunkZ() * 16 + 8;
            return player.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).immutable();
        }
        return fallback == null ? BlockPos.ZERO : fallback.immutable();
    }
}
