package com.monpai.sailboatmod.route.terrain;

/**
 * 未生成区块的地形预测(NBT 读不到时的后备)。实现必须可在后台线程调用;{@code maxWaitMillis} 只限制等待数据下载,
 * 超时返回 {@link Prediction#notReady()}。
 */
public interface TerrainPredictor {
    Prediction predictChunk(int cx, int cz, long maxWaitMillis);

    /** 预取口(进度条/走廊预下载用)。 */
    PrefetchGate gate();
}
