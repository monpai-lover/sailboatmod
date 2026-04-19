package com.monpai.sailboatmod.road.construction.execution;

import net.minecraft.server.level.ServerLevel;

public interface ConstructionExecutor {
    boolean tick(ConstructionQueue queue, ServerLevel level);
}
