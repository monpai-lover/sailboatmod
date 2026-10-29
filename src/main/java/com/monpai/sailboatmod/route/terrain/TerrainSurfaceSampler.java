package com.monpai.sailboatmod.route.terrain;

import java.util.Optional;

/**
 * 地图用的地表取样(未生成区域的预测)。{@code blocksPerSample} 是一个像素代表的格数,实现据此选数据解析度。
 * 失败返回 empty,不抛异常;只在后台线程调用。
 */
public interface TerrainSurfaceSampler {
    Optional<SurfaceSample> sample(int x, int z, int blocksPerSample);
}
