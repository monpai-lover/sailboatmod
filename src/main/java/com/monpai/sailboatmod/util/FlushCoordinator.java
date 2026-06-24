package com.monpai.sailboatmod.util;

import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>读前 flush 节流</b>:离线读区块前要 flush 待写区块(消除 pendingWrites 堆损坏隐患),但每块都 flush 太重。
 * Xaero 是「每 region 一次」。本类按 region(32×32 区块)去重 + 可选时间窗:同 region 在窗口内只首读 flush 一次。
 *
 * <p>线程安全(ConcurrentHashMap),三处(水路后台 / claim 主线程 / marketweb 主线程)可各持一个实例。
 * 时间戳由调用方传(本类不调 System.currentTimeMillis,便于测试与确定性);传 0 即「每 region 仅首次」永久去重。</p>
 */
public final class FlushCoordinator {
    private final long windowMillis;
    // regionKey -> 上次 flush 时间戳(millis)
    private final ConcurrentHashMap<Long, AtomicLong> lastFlushByRegion = new ConcurrentHashMap<>();

    /**
     * @param windowMillis 同 region 两次 flush 的最小间隔;<=0 表示永久去重(每 region 仅首读 flush 一次)。
     */
    public FlushCoordinator(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /**
     * 该区块所在 region 是否需要这次 flush。需要则记录时间戳并返回 true(调用方随即执行真正的 flush+read);
     * 否则返回 false(直接 read,跳过 flush)。
     *
     * @param nowMillis 当前时间戳(调用方传 System.currentTimeMillis());windowMillis<=0 时忽略。
     */
    public boolean shouldFlush(ChunkPos pos, long nowMillis) {
        if (pos == null) {
            return false;
        }
        long regionKey = regionKey(pos.x >> 5, pos.z >> 5);
        AtomicLong last = lastFlushByRegion.computeIfAbsent(regionKey, k -> new AtomicLong(Long.MIN_VALUE));
        long prev = last.get();
        if (prev == Long.MIN_VALUE) {
            // 该 region 首次:flush。
            return last.compareAndSet(prev, nowMillis);
        }
        if (windowMillis <= 0) {
            return false; // 永久去重:首读已 flush 过,不再 flush。
        }
        if (nowMillis - prev >= windowMillis) {
            return last.compareAndSet(prev, nowMillis);
        }
        return false;
    }

    public void clear() {
        lastFlushByRegion.clear();
    }

    private static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }
}
