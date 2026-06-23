package com.monpai.sailboatmod.market.terminal;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 全服持久化的「终端连通网络」清单——记录每个港口(PORT)/驿站(POST_STATION)的归属城镇、名称、出航路线终点名、
 * 以及(驿站)建立陆路自动航线时算出的可达城镇集合。
 *
 * <p><b>为什么要这份</b>:市场可见性原先靠 {@code level.getBlockEntity(远端码头)} + 实时寻路判定,远端区块没加载时
 * 误判"不可见"(见 [[chunkload_entity_visibility]] / [[poststation_reachable_chunk_gate]])。本清单把判定所需数据
 * 持久化到内存 SavedData,{@link TerminalVisibility} 纯读它即可判定,<b>彻底脱离区块加载</b>。</p>
 *
 * <p><b>与既有 SavedData 区分</b>:已有 {@code MarketTerminalSavedData}(DATA_NAME=sailboatmod_market_terminals,
 * 存"市场方块"清单)、{@code DockLocationSavedData}(只存港口坐标)。本类 DATA_NAME=
 * {@value #DATA_NAME},语义是"码头/驿站连通网络",别名相撞。范式照搬 DockLocationSavedData。</p>
 *
 * <p><b>写入时机</b>:DockBlockEntity.onLoad/setChanged 刷新本条(港口名/城镇/路线终点),setRemoved 删本条;
 * 驿站建立陆路自动航线成功时(RoadAutoRouteService)往 reachableTownIds 加目标城镇。onLoad 刷新时<b>保留</b>已存
 * reachableTownIds(onLoad 不知可达,不能清)。</p>
 *
 * <p>全服一份,挂 overworld 根存储,与 DockLocationSavedData 同。</p>
 */
public class TerminalNetworkSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_terminal_network";

    public enum Kind {
        PORT,
        POST_STATION;

        static Kind fromOrdinal(int ordinal) {
            Kind[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : PORT;
        }
    }

    // key = dimensionId + "|" + packedPos
    private final Map<String, TerminalEntry> entries = new LinkedHashMap<>();

    public static TerminalNetworkSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new TerminalNetworkSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(TerminalNetworkSavedData::load, TerminalNetworkSavedData::new, DATA_NAME);
    }

    public static TerminalNetworkSavedData load(CompoundTag tag) {
        TerminalNetworkSavedData data = new TerminalNetworkSavedData();
        ListTag list = tag.getList("Terminals", Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            if (raw instanceof CompoundTag compound) {
                TerminalEntry entry = TerminalEntry.load(compound);
                if (!entry.dimId().isBlank()) {
                    data.entries.put(key(entry.dimId(), entry.packedPos()), entry);
                }
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (TerminalEntry entry : entries.values()) {
            list.add(entry.save());
        }
        tag.put("Terminals", list);
        return tag;
    }

    /** 写入/更新一条;与现有完全相等时短路不写盘(setChanged 每 tick 调,防止反复 setDirty)。 */
    public void putEntry(TerminalEntry entry) {
        if (entry == null || entry.dimId().isBlank()) {
            return;
        }
        String key = key(entry.dimId(), entry.packedPos());
        TerminalEntry current = entries.get(key);
        if (entry.equals(current)) {
            return;
        }
        entries.put(key, entry);
        setDirty();
    }

    public void removeEntry(String dimId, long packedPos) {
        if (dimId == null || dimId.isBlank()) {
            return;
        }
        if (entries.remove(key(dimId, packedPos)) != null) {
            setDirty();
        }
    }

    public TerminalEntry getEntry(String dimId, long packedPos) {
        if (dimId == null || dimId.isBlank()) {
            return null;
        }
        return entries.get(key(dimId, packedPos));
    }

    /** 该城镇下指定类型的终端(用于可见性判定按城镇分组)。 */
    public List<TerminalEntry> forTown(String townId, Kind kind) {
        List<TerminalEntry> out = new ArrayList<>();
        if (townId == null || townId.isBlank()) {
            return out;
        }
        for (TerminalEntry entry : entries.values()) {
            if (entry.kind() == kind && townId.equals(entry.townId())) {
                out.add(entry);
            }
        }
        return out;
    }

    public List<TerminalEntry> all() {
        return new ArrayList<>(entries.values());
    }

    /** 驿站建立陆路自动航线成功时调:把目标城镇加入该驿站 entry 的可达集合(原子加,不清已存)。 */
    public void addReachableTown(String dimId, long packedPos, String reachableTownId) {
        if (dimId == null || dimId.isBlank() || reachableTownId == null || reachableTownId.isBlank()) {
            return;
        }
        TerminalEntry current = entries.get(key(dimId, packedPos));
        if (current == null) {
            return;
        }
        if (current.reachableTownIds().contains(reachableTownId)) {
            return;
        }
        Set<String> updated = new LinkedHashSet<>(current.reachableTownIds());
        updated.add(reachableTownId);
        entries.put(key(dimId, packedPos), current.withReachableTownIds(updated));
        setDirty();
    }

    private static String key(String dimId, long packedPos) {
        return dimId + "|" + packedPos;
    }

    /**
     * 一个终端的连通快照。{@code routeEndDockNames} = 该终端出航路线的终点 dock 名(从 RouteDefinition.endDockName 提取);
     * {@code reachableTownIds} = (驿站)陆路自动航线建立时算出的可达城镇 id 集合。
     */
    public record TerminalEntry(
            String dimId,
            long packedPos,
            Kind kind,
            String dockName,
            String townId,
            String nationId,
            List<String> routeEndDockNames,
            Set<String> reachableTownIds
    ) {
        public TerminalEntry {
            dimId = dimId == null ? "" : dimId.trim();
            kind = kind == null ? Kind.PORT : kind;
            dockName = dockName == null ? "" : dockName;
            townId = townId == null ? "" : townId;
            nationId = nationId == null ? "" : nationId;
            routeEndDockNames = routeEndDockNames == null ? List.of() : List.copyOf(routeEndDockNames);
            reachableTownIds = reachableTownIds == null
                    ? Set.of()
                    : new LinkedHashSet<>(reachableTownIds);
        }

        public BlockPos pos() {
            return BlockPos.of(packedPos);
        }

        public TerminalEntry withReachableTownIds(Set<String> updated) {
            return new TerminalEntry(dimId, packedPos, kind, dockName, townId, nationId, routeEndDockNames, updated);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("DimId", dimId);
            tag.putLong("PackedPos", packedPos);
            tag.putInt("Kind", kind.ordinal());
            tag.putString("DockName", dockName);
            tag.putString("TownId", townId);
            tag.putString("NationId", nationId);
            tag.put("RouteEndDockNames", toStringList(routeEndDockNames));
            tag.put("ReachableTownIds", toStringList(reachableTownIds));
            return tag;
        }

        public static TerminalEntry load(CompoundTag tag) {
            return new TerminalEntry(
                    tag.contains("DimId") ? tag.getString("DimId") : "",
                    tag.contains("PackedPos") ? tag.getLong("PackedPos") : 0L,
                    Kind.fromOrdinal(tag.getInt("Kind")),
                    tag.contains("DockName") ? tag.getString("DockName") : "",
                    tag.contains("TownId") ? tag.getString("TownId") : "",
                    tag.contains("NationId") ? tag.getString("NationId") : "",
                    fromStringList(tag.getList("RouteEndDockNames", Tag.TAG_STRING)),
                    new LinkedHashSet<>(fromStringList(tag.getList("ReachableTownIds", Tag.TAG_STRING))));
        }

        // record 默认 equals 对 List/Set 按内容比较(LinkedHashSet/List 的 equals 是内容相等),putEntry 短路可靠。
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TerminalEntry other)) {
                return false;
            }
            return packedPos == other.packedPos
                    && kind == other.kind
                    && dimId.equals(other.dimId)
                    && dockName.equals(other.dockName)
                    && townId.equals(other.townId)
                    && nationId.equals(other.nationId)
                    && routeEndDockNames.equals(other.routeEndDockNames)
                    && reachableTownIds.equals(other.reachableTownIds);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dimId, packedPos, kind, dockName, townId, nationId, routeEndDockNames, reachableTownIds);
        }

        private static ListTag toStringList(Iterable<String> values) {
            ListTag list = new ListTag();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    list.add(StringTag.valueOf(value));
                }
            }
            return list;
        }

        private static List<String> fromStringList(ListTag list) {
            List<String> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                String value = list.getString(i);
                if (value != null && !value.isBlank()) {
                    out.add(value);
                }
            }
            return out;
        }
    }
}
