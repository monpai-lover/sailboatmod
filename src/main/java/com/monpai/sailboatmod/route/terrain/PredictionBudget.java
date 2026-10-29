package com.monpai.sailboatmod.route.terrain;

import java.util.function.LongSupplier;

/** 一次航线请求内所有「等地形数据下载」共用的时间预算(规格:60 秒);用完后未就绪区块一律当陆。线程安全。 */
public final class PredictionBudget {
    public static final long DEFAULT_MILLIS = 60_000L;

    private final long deadline;
    private final LongSupplier nowMillis;

    public PredictionBudget(long totalMillis, LongSupplier nowMillis) {
        this.nowMillis = nowMillis;
        this.deadline = nowMillis.getAsLong() + Math.max(0L, totalMillis);
    }

    public static PredictionBudget sixtySeconds() {
        return new PredictionBudget(DEFAULT_MILLIS, System::currentTimeMillis);
    }

    public long remainingMillis() {
        return Math.max(0L, deadline - nowMillis.getAsLong());
    }
}
