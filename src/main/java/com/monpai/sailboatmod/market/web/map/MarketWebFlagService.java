package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.nation.service.NationFlagStorage;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class MarketWebFlagService {
    public Optional<byte[]> readFlag(ServerLevel overworld, String flagId) {
        if (overworld == null || flagId == null || flagId.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = NationFlagStorage.resolveFlagPath(overworld, flagId);
            if (Files.isRegularFile(path)) {
                return Optional.of(Files.readAllBytes(path));
            }
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }
}
