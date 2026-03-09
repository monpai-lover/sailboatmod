package com.example.examplemod.market;

import net.minecraft.core.BlockPos;

import java.util.List;

public record MarketOverviewData(
        BlockPos marketPos,
        String marketName,
        String ownerName,
        String ownerUuid,
        int pendingCredits,
        boolean linkedDock,
        String linkedDockName,
        String linkedDockPosText,
        List<String> listingLines,
        List<String> orderLines,
        List<String> shippingLines
) {
}
