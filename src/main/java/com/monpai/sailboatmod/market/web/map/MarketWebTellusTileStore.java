package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Tellus 预测层瓦片磁盘缓存:{@code <网页地图快取目录>/tellus/{z}/{x}_{y}.png}。与现有网页地图快取放在一起,
 * 一次删除就能全部重画。预测地形不会变,瓦片不需要失效。
 */
public final class MarketWebTellusTileStore {
    private final Path root;

    public MarketWebTellusTileStore(Path root) {
        this.root = root;
    }

    public static MarketWebTellusTileStore forServer(MinecraftServer server) {
        Path root = server == null
                ? Path.of(MarketWebMapConstants.CACHE_DATA_DIR)
                : server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(MarketWebMapConstants.CACHE_DATA_DIR);
        return new MarketWebTellusTileStore(root);
    }

    public Optional<byte[]> read(int zoom, int tileX, int tileY) {
        Path file = file(zoom, tileX, tileY);
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** 先写同目录暂存档再搬移,读端永远看不到半个檔。 */
    public void write(int zoom, int tileX, int tileY, byte[] png) {
        Path file = file(zoom, tileX, tileY);
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.write(temp, png);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private Path file(int zoom, int tileX, int tileY) {
        return root.resolve("tellus").resolve(Integer.toString(zoom)).resolve(tileX + "_" + tileY + ".png");
    }
}
