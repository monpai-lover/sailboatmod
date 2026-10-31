package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import java.util.UUID;

/** Server-owned task identity and progress. It survives closing/reopening client screens. */
public record RouteTaskView(UUID id, String dimension, BlockPos source, BlockPos target,
                            int percent, int state, String stage, long startedMillis, long updatedMillis) {
    public RouteTaskView {
        source = source.immutable();
        target = target.immutable();
        percent = Math.clamp(percent, 0, 100);
        state = Math.clamp(state, 0, 4);
        stage = stage == null ? "" : stage.substring(0, Math.min(128, stage.length()));
    }
    public boolean active() { return state == 0; }
    public long elapsedMillis(long now) {
        return Math.max(0, (active() ? now : updatedMillis) - startedMillis);
    }
}
