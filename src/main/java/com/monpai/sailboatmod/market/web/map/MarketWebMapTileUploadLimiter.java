package com.monpai.sailboatmod.market.web.map;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MarketWebMapTileUploadLimiter {
    private static final long WINDOW_MILLIS = 60_000L;

    private final Map<UUID, Deque<Long>> uploadsByPlayer = new ConcurrentHashMap<>();

    public boolean allow(UUID playerId, long nowMillis) {
        if (playerId == null) {
            return false;
        }
        Deque<Long> uploads = uploadsByPlayer.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        synchronized (uploads) {
            while (!uploads.isEmpty() && nowMillis - uploads.peekFirst() > WINDOW_MILLIS) {
                uploads.removeFirst();
            }
            if (uploads.size() >= MarketWebMapConstants.MAX_UPLOADS_PER_PLAYER_PER_MINUTE) {
                return false;
            }
            uploads.addLast(nowMillis);
            return true;
        }
    }

    public void clear() {
        uploadsByPlayer.clear();
    }
}
