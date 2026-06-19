package com.monpai.sailboatmod.route.water;

public interface WaterRouteWorld {
    WaterColumn sample(int x, int z, WaterRoutePolicy policy);

    boolean canLoadMoreChunks(int requested);

    int consumedChunkLoads();

    /**
     * (x,z) 不可航/可航的<b>精确原因</b>(寻路 seed 起终点判不可航时打日志定位用)。
     * 默认 null(噪声世界无此细节);{@link RealBlockWaterWorld} 覆写返回 3×3 校验细节(中心非水/区块读不到/占地哪格陆)。
     */
    default String sampleDiagnostic(int x, int z, WaterRoutePolicy policy) {
        return null;
    }
}
