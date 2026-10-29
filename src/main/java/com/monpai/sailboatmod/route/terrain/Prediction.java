package com.monpai.sailboatmod.route.terrain;

import com.monpai.sailboatmod.route.water.NbtChunkWaterReader;

/** 一个区块的地形预测结果:READY 带判水列;NOT_READY = 数据未下载完(等待超时);FAILED = 预测出错。 */
public record Prediction(Status status, NbtChunkWaterReader.ChunkColumns columns) {
    public enum Status {
        READY,
        NOT_READY,
        FAILED
    }

    private static final Prediction NOT_READY = new Prediction(Status.NOT_READY, null);
    private static final Prediction FAILED = new Prediction(Status.FAILED, null);

    public static Prediction ready(NbtChunkWaterReader.ChunkColumns columns) {
        return new Prediction(Status.READY, columns);
    }

    public static Prediction notReady() {
        return NOT_READY;
    }

    public static Prediction failed() {
        return FAILED;
    }
}
