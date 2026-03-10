package com.example.examplemod.dock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public record DockScreenData(
        BlockPos dockPos,
        String dockName,
        String dockOwnerName,
        String dockOwnerUuid,
        boolean canManageDock,
        ItemStack routeBook,
        List<String> routeNames,
        List<String> routeMetas,
        int selectedRouteIndex,
        List<Vec3> selectedRouteWaypoints,
        int zoneMinX,
        int zoneMaxX,
        int zoneMinZ,
        int zoneMaxZ,
        List<Integer> nearbyBoatIds,
        List<String> nearbyBoatNames,
        List<Vec3> nearbyBoatPositions,
        int selectedBoatIndex,
        List<String> storageLines,
        int selectedStorageIndex,
        List<String> waybillNames,
        int selectedWaybillIndex,
        List<String> selectedWaybillInfoLines,
        List<String> selectedWaybillCargoLines
) {
}
