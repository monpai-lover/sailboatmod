package com.monpai.sailboatmod.route.water;

/**
 * 玩家选择的规划方式。
 * <ul>
 *   <li>{@link #OPTIMAL}:不复用航路网络,完整重新规划最短且离岸的航线(建立航线基础设施的默认)。</li>
 *   <li>{@link #REUSE_FIRST}:先复用已建航线的航段,只为缺口规划,更快、与既有航道一致,但可能比最优稍远
 *       (前往目的地 / 市场自动派送的默认)。</li>
 * </ul>
 */
public enum RoutePlanningPreference {
    OPTIMAL(0),
    REUSE_FIRST(1);

    private final int id;

    RoutePlanningPreference(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean reuseNetwork() {
        return this == REUSE_FIRST;
    }

    public static RoutePlanningPreference fromId(int id) {
        return id == REUSE_FIRST.id ? REUSE_FIRST : OPTIMAL;
    }
}
