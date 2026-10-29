package com.monpai.sailboatmod.route.terrain;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 地形数据预取口:future 完成 = 该区域数据已就绪。{@link #ALWAYS_READY} 用于无预取能力的生成器
 * (或 Tellus 预取 API 不兼容时),一律视为就绪,预测时由生成器自己阻塞下载。
 */
public interface PrefetchGate {
    PrefetchGate ALWAYS_READY = new PrefetchGate() {
        private final CompletableFuture<Void> done = CompletableFuture.completedFuture(null);

        @Override
        public CompletableFuture<Void> request(int cx, int cz) {
            return done;
        }

        @Override
        public List<CompletableFuture<Void>> requestCorridor(BlockPos a, BlockPos b, int sideBlocks) {
            return List.of();
        }
    };

    /** 请求区块 (cx,cz) 所在区域的数据;返回该区域就绪的 future。 */
    CompletableFuture<Void> request(int cx, int cz);

    /** 请求 a→b 直线两侧各 sideBlocks 格走廊内的所有区域;返回各区域的 future(去重)。 */
    List<CompletableFuture<Void>> requestCorridor(BlockPos a, BlockPos b, int sideBlocks);

    /** Lazily visits one nearby tile at a time. Creating/advancing the iterator never enqueues a distant corridor. */
    default Iterable<CompletableFuture<Void>> corridor(BlockPos a, BlockPos b, int sideBlocks) {
        return () -> new LazyPrefetchCorridor(this, a, b, sideBlocks);
    }
}
