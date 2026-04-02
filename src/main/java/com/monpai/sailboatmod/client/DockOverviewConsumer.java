package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.dock.DockScreenData;
import net.minecraft.core.BlockPos;

public interface DockOverviewConsumer {
    boolean isForDock(BlockPos pos);

    void updateData(DockScreenData data);
}
