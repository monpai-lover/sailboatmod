package com.monpai.sailboatmod.transport.air.airport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Durable published geometry for maps and planning; never evidence that unloaded terrain is safe. */
public final class AirportSavedData extends SavedData {
    public static final String DATA_NAME = "sailboatmod_airports";
    public record AirportSnapshot(String dimension, BlockPos pos, String name, String townId, String ownerUuid, List<AirportFacility> facilities) {
        public AirportSnapshot {
            Objects.requireNonNull(dimension); pos = Objects.requireNonNull(pos).immutable();
            name = name == null ? "" : name; townId = townId == null ? "" : townId; ownerUuid = ownerUuid == null ? "" : ownerUuid;
            facilities = List.copyOf(facilities);
        }
    }
    private static final int MAX_ROUTE_NAMES = 64;
    private final Map<String, AirportSnapshot> entries = new LinkedHashMap<>();
    private final Map<String, LinkedHashSet<String>> previousRouteNames = new HashMap<>();
    public static AirportSavedData get(ServerLevel level) {
        ServerLevel root = level.getServer().overworld() == null ? level : level.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(new Factory<>(AirportSavedData::new, (tag, provider) -> load(tag)), DATA_NAME);
    }
    public void put(AirportSnapshot value) {
        String key = key(value.dimension(), value.pos());
        AirportSnapshot previous = entries.get(key);
        if (value.equals(previous)) return;
        if (previous != null && !value.name().equalsIgnoreCase(previous.name())
                && uniqueRouteAirport(previous.dimension(), previous.name()).orElse(null) == previous) {
            rememberRouteName(key, previous.name());
        }
        entries.put(key, value);
        setDirty();
    }

    /** Invoked on actual block destruction, never chunk unload. */
    public void remove(String dimension, BlockPos pos) {
        String key = key(dimension, pos);
        if (entries.remove(key) != null) {
            previousRouteNames.remove(key);
            setDirty();
        }
    }

    /** Name-only route books may predate the current airport label. Never choose between matches. */
    public Optional<AirportSnapshot> uniqueRouteAirport(String dimension, String routeName) {
        if (routeName == null || routeName.isBlank()) return Optional.empty();
        AirportSnapshot match = null;
        for (var entry : entries.entrySet()) {
            AirportSnapshot airport = entry.getValue();
            if (!airport.dimension().equals(dimension)) continue;
            var history = previousRouteNames.get(entry.getKey());
            if (!airport.name().equalsIgnoreCase(routeName) && (history == null || history.stream().noneMatch(routeName::equalsIgnoreCase))) continue;
            if (match != null) return Optional.empty();
            match = airport;
        }
        return Optional.ofNullable(match);
    }

    private void rememberRouteName(String key, String name) {
        if (name.isBlank() || name.length() > 256) return;
        var history = previousRouteNames.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        if (history.stream().noneMatch(name::equalsIgnoreCase) && history.add(name) && history.size() > MAX_ROUTE_NAMES)
            history.remove(history.getFirst());
    }
    public List<AirportSnapshot> airports() { return List.copyOf(entries.values()); }
    public List<AirportSnapshot> airports(String dimension) { return entries.values().stream().filter(value -> value.dimension().equals(dimension)).toList(); }
    public AirportSnapshot airport(String dimension, BlockPos pos) { return entries.get(key(dimension, pos)); }
    private static String key(String dimension, BlockPos pos) { return dimension + ":" + pos.asLong(); }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag airports = new ListTag();
        for (AirportSnapshot value : entries.values()) {
            CompoundTag item = new CompoundTag(); item.putString("Dimension", value.dimension()); item.putLong("Pos", value.pos().asLong()); item.putString("Name", value.name()); item.putString("TownId", value.townId()); item.putString("OwnerUuid", value.ownerUuid());
            ListTag facilities = new ListTag(); for (AirportFacility facility : value.facilities()) facilities.add(facility.save()); item.put("Facilities", facilities);
            var history = previousRouteNames.get(key(value.dimension(), value.pos()));
            if (history != null && !history.isEmpty()) {
                ListTag names = new ListTag();
                for (String name : history) names.add(StringTag.valueOf(name));
                item.put("PreviousRouteNames", names);
            }
            airports.add(item);
        }
        tag.put("Airports", airports); return tag;
    }
    public static AirportSavedData load(CompoundTag tag) {
        AirportSavedData data = new AirportSavedData();
        for (Tag raw : tag.getList("Airports", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) raw;
            if (item.getString("Dimension").isBlank()) continue;
            List<AirportFacility> facilities = new ArrayList<>();
            for (Tag rawFacility : item.getList("Facilities", Tag.TAG_COMPOUND)) {
                if (facilities.size() >= 16) break;
                try { facilities.add(AirportFacility.load((CompoundTag) rawFacility)); } catch (IllegalArgumentException ignored) { }
            }
            AirportSnapshot value = new AirportSnapshot(item.getString("Dimension"), BlockPos.of(item.getLong("Pos")), item.getString("Name"), item.getString("TownId"), item.getString("OwnerUuid"), facilities);
            String key = key(value.dimension(), value.pos());
            data.entries.put(key, value);
            ListTag names = item.getList("PreviousRouteNames", Tag.TAG_STRING);
            for (int i = 0; i < Math.min(names.size(), MAX_ROUTE_NAMES); i++)
                data.rememberRouteName(key, names.getString(i));
        }
        return data;
    }
}
