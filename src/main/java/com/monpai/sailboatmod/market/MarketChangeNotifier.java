package com.monpai.sailboatmod.market;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global revision of everything a market screen shows: listings, purchase and shipping orders, pending credits
 * ({@link MarketSavedData}), buy orders (SQLite) and wallets. Every write bumps it; open market screens
 * ({@link MarketLiveSync}) and the optional web addon poll it and push coalesced updates, so writers never need to
 * know who is watching.
 */
public final class MarketChangeNotifier {
    private static final AtomicLong REVISION = new AtomicLong();
    private static final int TRADE_HISTORY = 64;
    private static final ArrayDeque<Trade> TRADES = new ArrayDeque<>();
    private static long tradeSequence;

    /** A recorded trade, for live tickers; {@code sequence} increases by one per trade. */
    public record Trade(long sequence, String commodityKey, int unitPrice, int quantity, long at) {
    }

    private MarketChangeNotifier() {
    }

    /** Something a market screen may show has changed. Cheap and thread-safe; call it after the write. */
    public static void changed() {
        REVISION.incrementAndGet();
    }

    public static long revision() {
        return REVISION.get();
    }

    /** A trade was written to the commodity history (the last few are kept for live tickers). */
    public static void traded(String commodityKey, int unitPrice, int quantity) {
        synchronized (TRADES) {
            TRADES.addLast(new Trade(++tradeSequence, commodityKey, unitPrice, quantity, System.currentTimeMillis()));
            while (TRADES.size() > TRADE_HISTORY) {
                TRADES.removeFirst();
            }
        }
        changed();
    }

    /** Trades with a sequence above {@code sequence}, oldest first; at most the last {@value #TRADE_HISTORY}. */
    public static List<Trade> tradesAfter(long sequence) {
        synchronized (TRADES) {
            List<Trade> out = new ArrayList<>();
            for (Trade trade : TRADES) {
                if (trade.sequence() > sequence) {
                    out.add(trade);
                }
            }
            return out;
        }
    }

    public static long lastTradeSequence() {
        synchronized (TRADES) {
            return tradeSequence;
        }
    }
}
