package com.monpai.sailboatmod.road.construction.execution;

import com.monpai.sailboatmod.road.config.ConstructionConfig;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.server.level.ServerLevel;

public class NpcWorkerExecutor implements ConstructionExecutor {
    private final ConstructionConfig config;
    private int tickCounter = 0;

    public NpcWorkerExecutor(ConstructionConfig config) {
        this.config = config;
    }

    @Override
    public boolean tick(ConstructionQueue queue, ServerLevel level) {
        if (!queue.hasNext()) {
            queue.complete();
            return true;
        }
        tickCounter++;
        if (tickCounter >= config.getNpcRate()) {
            tickCounter = 0;
            BuildStep step = queue.next();
            if (step != null) {
                queue.executeStep(step, level);
            }
        }
        return !queue.hasNext();
    }
}
