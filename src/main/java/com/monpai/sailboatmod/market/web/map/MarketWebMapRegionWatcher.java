package com.monpai.sailboatmod.market.web.map;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 监听存档 region 目录的 .mca 文件变化（向 Pl3xMap {@code RegionFileWatcher} 看齐）。
 *
 * <p>玩家走过某区域→区块被游戏保存→对应 .mca 文件被修改→本监听器解析出 region 坐标，
 * 记入「脏 region」集合。{@link MarketWebMapRenderService} 据此强制加载并重渲那些区块，
 * 因此即便偏远、当前未加载、无市场的区域，只要曾被保存过，也能被自动刷新成新配色（修复旧红水）。
 *
 * <p>独立守护线程跑 {@link WatchService}；事件去抖动后将脏 region 暴露给主线程消费。
 */
public final class MarketWebMapRegionWatcher {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Object lock = new Object();
    private final Set<Long> dirtyRegions = new LinkedHashSet<>();

    private Thread thread;
    private WatchService watchService;
    private volatile boolean running;

    /** 启动监听指定世界的 region 目录。重复调用会先停掉旧线程。 */
    public void start(ServerLevel level) {
        if (level == null) {
            return;
        }
        stop();
        Path regionDir = level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("region")
                .normalize();
        if (!java.nio.file.Files.isDirectory(regionDir)) {
            return;
        }
        try {
            this.watchService = regionDir.getFileSystem().newWatchService();
            regionDir.register(this.watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException exception) {
            LOGGER.warn("Failed to start market web map region watcher for {}", regionDir, exception);
            closeWatchService();
            return;
        }
        this.running = true;
        this.thread = new Thread(() -> watchLoop(regionDir), "MarketWebMap-RegionWatcher");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public void stop() {
        this.running = false;
        closeWatchService();
        if (this.thread != null) {
            this.thread.interrupt();
            this.thread = null;
        }
        synchronized (lock) {
            dirtyRegions.clear();
        }
    }

    /**
     * 取出并清空当前累积的脏 region（打包为 long，用 {@link #packRegion}）。主线程每 tick 调用。
     */
    public Set<Long> drainDirtyRegions() {
        synchronized (lock) {
            if (dirtyRegions.isEmpty()) {
                return Set.of();
            }
            Set<Long> drained = new LinkedHashSet<>(dirtyRegions);
            dirtyRegions.clear();
            return drained;
        }
    }

    public boolean isRunning() {
        return running;
    }

    static long packRegion(int regionX, int regionZ) {
        return ((long) regionX & 0xFFFFFFFFL) | (((long) regionZ & 0xFFFFFFFFL) << 32);
    }

    static int unpackRegionX(long packed) {
        return (int) (packed & 0xFFFFFFFFL);
    }

    static int unpackRegionZ(long packed) {
        return (int) (packed >> 32);
    }

    private void watchLoop(Path regionDir) {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (ClosedWatchServiceException | InterruptedException ignore) {
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                Object context = event.context();
                if (!(context instanceof Path relative)) {
                    continue;
                }
                long packed = parseRegionFileName(relative.getFileName().toString());
                if (packed != Long.MIN_VALUE) {
                    synchronized (lock) {
                        dirtyRegions.add(packed);
                    }
                }
            }
            if (!key.reset()) {
                return;
            }
        }
    }

    /** 解析 {@code r.X.Z.mca} → 打包 region 坐标；非法名返回 {@link Long#MIN_VALUE}。 */
    static long parseRegionFileName(String name) {
        if (name == null || !name.startsWith("r.") || !name.endsWith(".mca") || name.length() <= 6) {
            return Long.MIN_VALUE;
        }
        String body = name.substring(2, name.length() - 4);
        int split = body.indexOf('.');
        if (split <= 0 || split >= body.length() - 1) {
            return Long.MIN_VALUE;
        }
        try {
            int regionX = Integer.parseInt(body.substring(0, split));
            int regionZ = Integer.parseInt(body.substring(split + 1));
            return packRegion(regionX, regionZ);
        } catch (NumberFormatException exception) {
            return Long.MIN_VALUE;
        }
    }

    private void closeWatchService() {
        if (this.watchService != null) {
            try {
                this.watchService.close();
            } catch (IOException ignore) {
            }
            this.watchService = null;
        }
    }
}
