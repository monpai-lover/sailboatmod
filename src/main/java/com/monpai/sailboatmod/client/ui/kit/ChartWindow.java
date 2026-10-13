package com.monpai.sailboatmod.client.ui.kit;

import java.util.List;

/** Pure helpers for chart viewports, kept free of LDLib2 types so they can be unit-tested without a client. */
public final class ChartWindow {
    private ChartWindow() {
    }

    /** The newest {@code visible} entries, oldest first; the whole list when it is shorter. */
    public static <T> List<T> newest(List<T> all, int visible) {
        if (visible <= 0) {
            return List.of();
        }
        if (all.size() <= visible) {
            return all;
        }
        return all.subList(all.size() - visible, all.size());
    }
}
