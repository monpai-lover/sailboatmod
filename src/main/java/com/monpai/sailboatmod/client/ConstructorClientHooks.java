package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.nation.service.StructureConstructionManager;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class ConstructorClientHooks {
    public record ConstructionProgress(BlockPos origin, String structureId, int progressPercent, int activeWorkers) {
    }

    private static List<ConstructionProgress> activeConstructions = List.of();
    private static long lastSyncAtMs = 0L;

    public static void updateProgress(List<ConstructionProgress> constructions) {
        activeConstructions = List.copyOf(constructions);
        lastSyncAtMs = System.currentTimeMillis();
    }

    public static ConstructionProgress findNearest(BlockPos playerPos, StructureConstructionManager.StructureType preferredType) {
        return findNearest(playerPos, preferredType, null);
    }

    public static ConstructionProgress findNearest(BlockPos playerPos,
                                                   StructureConstructionManager.StructureType preferredType,
                                                   BlockPos preferredOrigin) {
        if (playerPos == null || activeConstructions.isEmpty() || isStale()) {
            return null;
        }

        if (preferredOrigin != null) {
            for (ConstructionProgress progress : activeConstructions) {
                if (preferredOrigin.equals(progress.origin())) {
                    return progress;
                }
            }
        }

        if (preferredType != null) {
            ConstructionProgress bestTyped = null;
            double bestTypedDistance = Double.MAX_VALUE;
            for (ConstructionProgress progress : activeConstructions) {
                if (!preferredType.nbtName().equals(progress.structureId())) {
                    continue;
                }
                double distance = playerPos.distSqr(progress.origin());
                if (distance < bestTypedDistance) {
                    bestTypedDistance = distance;
                    bestTyped = progress;
                }
            }
            if (bestTyped != null) {
                return bestTyped;
            }
        }

        ConstructionProgress best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ConstructionProgress progress : activeConstructions) {
            double distance = playerPos.distSqr(progress.origin());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = progress;
            }
        }
        return best;
    }

    public static void syncHeldSettings(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        net.minecraft.core.BlockPos pendingOrigin = com.monpai.sailboatmod.item.BankConstructorItem.getPendingProjectionOrigin(stack, minecraft.level);
        com.monpai.sailboatmod.network.ModNetwork.CHANNEL.sendToServer(
                new com.monpai.sailboatmod.network.packet.SyncConstructorSettingsPacket(
                        pendingOrigin == null ? net.minecraft.core.BlockPos.ZERO : pendingOrigin,
                        com.monpai.sailboatmod.item.BankConstructorItem.getSelectedIndex(stack),
                        com.monpai.sailboatmod.item.BankConstructorItem.getAdjustModeIndex(stack),
                        com.monpai.sailboatmod.item.BankConstructorItem.getOffsetX(stack),
                        com.monpai.sailboatmod.item.BankConstructorItem.getOffsetY(stack),
                        com.monpai.sailboatmod.item.BankConstructorItem.getOffsetZ(stack),
                        com.monpai.sailboatmod.item.BankConstructorItem.getRotation(stack),
                        pendingOrigin != null,
                        com.monpai.sailboatmod.network.packet.SyncConstructorSettingsPacket.Action.SYNC_ONLY
                )
        );
    }

    public static List<ConstructionProgress> activeConstructions() {
        if (isStale()) {
            activeConstructions = List.of();
        }
        return new ArrayList<>(activeConstructions);
    }

    private static boolean isStale() {
        return (System.currentTimeMillis() - lastSyncAtMs) > 3000L;
    }

    private ConstructorClientHooks() {
    }
}
