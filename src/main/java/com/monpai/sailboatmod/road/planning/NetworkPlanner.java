package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.model.StructureConnection;
import net.minecraft.core.BlockPos;
import java.util.List;

public interface NetworkPlanner {
    List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks);
}
