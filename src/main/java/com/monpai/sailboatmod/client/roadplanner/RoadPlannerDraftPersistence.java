package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RoadPlannerDraftPersistence {
    private final File rootDir;

    public RoadPlannerDraftPersistence(File rootDir) {
        this.rootDir = rootDir;
    }

    public void save(UUID sessionId, RoadPlannerDraftStore.Draft draft) {
        if (sessionId == null || draft == null) {
            return;
        }
        File file = file(sessionId);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        List<String> lines = new ArrayList<>();
        for (BlockPos node : draft.nodes()) {
            lines.add("N," + node.getX() + "," + node.getY() + "," + node.getZ());
        }
        for (RoadPlannerSegmentType type : draft.segmentTypes()) {
            lines.add("S," + type.name());
        }
        if (draft.startPos() != null && !draft.startPos().equals(BlockPos.ZERO)) {
            lines.add("P," + draft.startPos().getX() + "," + draft.startPos().getY() + "," + draft.startPos().getZ());
        }
        if (draft.endPos() != null && !draft.endPos().equals(BlockPos.ZERO)) {
            lines.add("E," + draft.endPos().getX() + "," + draft.endPos().getY() + "," + draft.endPos().getZ());
        }
        try {
            Files.write(file.toPath(), lines);
        } catch (IOException ignored) {
        }
    }

    public Optional<RoadPlannerDraftStore.Draft> load(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        File file = file(sessionId);
        if (!file.exists()) {
            return Optional.empty();
        }
        List<BlockPos> nodes = new ArrayList<>();
        List<RoadPlannerSegmentType> segments = new ArrayList<>();
        BlockPos startPos = BlockPos.ZERO;
        BlockPos endPos = BlockPos.ZERO;
        try {
            for (String line : Files.readAllLines(file.toPath())) {
                String[] parts = line.split(",");
                if (parts.length == 4 && "N".equals(parts[0])) {
                    nodes.add(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
                } else if (parts.length == 2 && "S".equals(parts[0])) {
                    segments.add(RoadPlannerSegmentType.valueOf(parts[1]));
                } else if (parts.length == 4 && "P".equals(parts[0])) {
                    startPos = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                } else if (parts.length == 4 && "E".equals(parts[0])) {
                    endPos = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                }
            }
        } catch (RuntimeException | IOException ignored) {
            return Optional.empty();
        }
        return Optional.of(new RoadPlannerDraftStore.Draft(nodes, segments, startPos, endPos));
    }

    private File file(UUID sessionId) {
        return new File(rootDir, sessionId + ".draft");
    }
}
