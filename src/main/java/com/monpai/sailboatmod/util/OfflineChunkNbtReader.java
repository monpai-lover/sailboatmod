package com.monpai.sailboatmod.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * <b>统一安全离线区块 NBT 读取器</b>(照 Xaero World Map 的安全范式)。
 *
 * <p>三处「读未加载区块地形 NBT」(水路判水 / marketweb 地图 / claim 预览)收口到本类,消除各自踩过的雷:
 * claim force 未生成区块卡服崩、水路后台直读并发改 tag 堆损坏。</p>
 *
 * <p><b>安全范式</b>:
 * <ul>
 *   <li>{@code chunkMap.read(ChunkPos)} 走 vanilla IOWorker 单线程异步读盘,<b>绝不生成区块</b>(不 force)。
 *       区块不存在 → {@code Optional.empty()} → 调用方留空。</li>
 *   <li><b>读前 flush</b>(可选):把待写区块落盘,消除 {@code pendingWrites} 共享引用被 vanilla datafix 并发改
 *       HashMap 的堆损坏隐患。flush 方式按调用线程区分(见下,死锁关键)。</li>
 *   <li>返回的 {@link CompoundTag} 是<b>调用方独占</b>的全新对象,可安全交 {@link OfflineChunkPalette} 解码。</li>
 * </ul></p>
 *
 * <p><b>flush 死锁铁律</b>(见 [[marketweb_snapshot_deadlock]]):
 * <ul>
 *   <li><b>主线程</b>入口 {@link #readSafeOnMainThread}:flush <b>直接</b> {@code chunkSource.save(false)}——
 *       绝不 {@code server.submit().join()} 给自己再等(重入死锁)。</li>
 *   <li><b>后台线程</b>入口 {@link #readSafeFromWorkerThread}:flush 走 {@code server.submit(save).join()}
 *       单向投递主线程(主线程没在等该 worker → 不死锁;先例 RouteDebugService 已生产跑通)。</li>
 * </ul>
 * 两入口都<b>绝不</b> {@code getChunk}/force(那才会触发同步生成卡服)。</p>
 */
public final class OfflineChunkNbtReader {
    /** 区块生成状态分类。 */
    public enum ChunkStatusClass {
        /** tag 存在且 status=full:已生成,可解码。 */
        FULL,
        /** tag 存在但 status 非 full:部分生成,留空。 */
        NOT_FULL,
        /** 磁盘上没有(Optional.empty):未生成/未探索,留空。 */
        ABSENT
    }

    private OfflineChunkNbtReader() {
    }

    /**
     * <b>后台线程</b>读一个区块的独占 NBT。可选读前 flush(server.submit(save).join() 单向投递,不死锁)。
     * read 不 join 主线程,{@code future.get(timeout)} 单向等磁盘 IO。读不到/超时/异常 → empty。
     * 只返回 full status 的 tag(非 full / 不存在 → empty)。
     */
    public static Optional<CompoundTag> readSafeFromWorkerThread(ServerLevel level, ChunkPos pos,
                                                                 long timeoutMs, boolean flush) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        if (flush) {
            flushFromWorkerThread(level);
        }
        return readFullTag(level, pos, timeoutMs);
    }

    /**
     * <b>主线程</b>读一个区块的独占 NBT。可选读前 flush(直接 save,绝不 submit 给自己)。
     * 主线程同步等磁盘 IO,timeoutMs 兜底。只返回 full status 的 tag。
     * <p>注意:主线程同步 {@code future.get} 会阻塞 tick——仅在确知该区块在盘上(已探测)且批量可控时用;
     * 大批量优先用 {@link #beginProbe} 异步探测 + 后台读盘。</p>
     */
    public static Optional<CompoundTag> readSafeOnMainThread(ServerLevel level, ChunkPos pos,
                                                             long timeoutMs, boolean flush) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        if (flush) {
            // 主线程直接 save:绝不 server.submit().join() 给自己(重入死锁)。
            try {
                level.getChunkSource().save(false);
            } catch (Throwable ignored) {
                // flush 失败不致命:最坏读到稍旧的盘上数据。
            }
        }
        return readFullTag(level, pos, timeoutMs);
    }

    /**
     * 主线程异步发起一次 {@code chunkMap.read}(不阻塞、不生成),返回 future。
     * 调用方下 tick 看 {@code future.isDone()} 后用 {@link #classify} 判档。
     */
    public static CompletableFuture<Optional<CompoundTag>> beginProbe(ServerLevel level, ChunkPos pos) {
        if (level == null || pos == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            return level.getChunkSource().chunkMap.read(pos);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /** 探测结果分类:empty→ABSENT,full→FULL,否则 NOT_FULL。 */
    public static ChunkStatusClass classify(Optional<CompoundTag> tag) {
        if (tag == null || tag.isEmpty()) {
            return ChunkStatusClass.ABSENT;
        }
        return OfflineChunkPalette.isFullStatus(tag.get()) ? ChunkStatusClass.FULL : ChunkStatusClass.NOT_FULL;
    }

    // ===== 内部 =====

    private static Optional<CompoundTag> readFullTag(ServerLevel level, ChunkPos pos, long timeoutMs) {
        try {
            CompletableFuture<Optional<CompoundTag>> future = level.getChunkSource().chunkMap.read(pos);
            Optional<CompoundTag> tag = timeoutMs > 0
                    ? future.get(timeoutMs, TimeUnit.MILLISECONDS)
                    : future.get();
            if (tag.isEmpty() || !OfflineChunkPalette.isFullStatus(tag.get())) {
                return Optional.empty();
            }
            return tag;
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** 后台线程 flush:投递主线程 save 并单向等(主线程不等该 worker → 不死锁)。 */
    private static void flushFromWorkerThread(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        try {
            server.submit(() -> {
                try {
                    level.getChunkSource().save(false);
                } catch (Throwable ignored) {
                }
                return null;
            }).get(2000L, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // flush 失败不致命:最坏读到稍旧的盘上数据。
        }
    }
}
