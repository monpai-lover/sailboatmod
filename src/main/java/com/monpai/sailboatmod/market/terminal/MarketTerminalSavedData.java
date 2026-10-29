package com.monpai.sailboatmod.market.terminal;

import com.monpai.sailboatmod.compat.PersistedBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MarketTerminalSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_market_terminals";

    private final Map<String, MarketTerminalEntry> entries = new LinkedHashMap<>();
    private final Map<String, PositionAlias> positionAliases = new LinkedHashMap<>();
    private final Map<String, PersistedBlockPos.Evidence> positionEvidence = new LinkedHashMap<>();

    public static MarketTerminalSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new MarketTerminalSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(new SavedData.Factory<>(MarketTerminalSavedData::new, (tag, provider) -> com.monpai.sailboatmod.compat.ItemStackNbt.withProvider(provider, () -> MarketTerminalSavedData.load(tag, root))), DATA_NAME);
    }

    public static MarketTerminalSavedData load(CompoundTag tag) {
        return load(tag, null);
    }

    private static MarketTerminalSavedData load(CompoundTag tag, ServerLevel context) {
        MarketTerminalSavedData data = new MarketTerminalSavedData();
        ListTag list = tag.getList("Entries", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            MarketTerminalEntry entry = MarketTerminalEntry.load(compound, context);
            BlockPos previous = PersistedBlockPos.read(compound, "MarketPos");
            CompoundTag source = compound;
            if (!entry.marketPos().equals(previous)) {
                data.addAlias(new PositionAlias(entry.dimensionId(), previous, entry.marketPos()));
                source = compound.copy();
                PersistedBlockPos.write(source, "MarketPos", entry.marketPos());
                data.setDirty();
            }
            if (!entry.dimensionId().isBlank()) {
                data.entries.put(entry.key(), entry);
                data.positionEvidence.put(entry.key(), PersistedBlockPos.remember(source, "MarketPos", entry.marketPos()));
            }
        }
        for (Tag raw : tag.getList("PositionAliases", Tag.TAG_COMPOUND)) {
            if (raw instanceof CompoundTag alias) {
                int[] old = alias.getIntArray("OldXYZ");
                int[] canonical = alias.getIntArray("CanonicalXYZ");
                if (old.length == 3 && canonical.length == 3) {
                    data.addAlias(new PositionAlias(alias.getString("DimensionId"),
                            new BlockPos(old[0], old[1], old[2]), new BlockPos(canonical[0], canonical[1], canonical[2])));
                }
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
        return com.monpai.sailboatmod.compat.ItemStackNbt.withProvider(provider, () -> save(tag));
    }

    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (MarketTerminalEntry entry : entries.values()) {
            CompoundTag saved = entry.save();
            PersistedBlockPos.writePreservingEvidence(saved, "MarketPos", entry.marketPos(), positionEvidence.get(entry.key()));
            list.add(saved);
        }
        tag.put("Entries", list);
        ListTag aliases = new ListTag();
        for (PositionAlias alias : positionAliases.values()) {
            if (!containsPosition(alias.dimensionId(), alias.canonical())) continue;
            CompoundTag value = new CompoundTag();
            value.putString("DimensionId", alias.dimensionId());
            value.putIntArray("OldXYZ", new int[]{alias.previous().getX(), alias.previous().getY(), alias.previous().getZ()});
            value.putIntArray("CanonicalXYZ", new int[]{alias.canonical().getX(), alias.canonical().getY(), alias.canonical().getZ()});
            aliases.add(value);
        }
        tag.put("PositionAliases", aliases);
        return tag;
    }

    public void putEntry(MarketTerminalEntry entry) {
        if (entry == null || entry.dimensionId().isBlank()) {
            return;
        }
        entries.put(entry.key(), entry);
        // Registered by a physical MarketBlockEntity: its explicit current location is authoritative.
        positionEvidence.remove(entry.key());
        setDirty();
    }

    public void removeEntry(String dimensionId, BlockPos pos) {
        if (dimensionId == null || dimensionId.isBlank() || pos == null) {
            return;
        }
        if (entries.remove(MarketTerminalEntry.keyFor(dimensionId, pos)) != null) {
            positionEvidence.remove(MarketTerminalEntry.keyFor(dimensionId, pos));
            setDirty();
        }
    }

    public List<MarketTerminalEntry> entries() {
        return new ArrayList<>(entries.values());
    }

    public BlockPos canonicalPosition(String dimensionId, BlockPos position) {
        // A real registered terminal always wins over an old link that happens to use its coordinates.
        if (position == null || containsPosition(dimensionId, position)) return position;
        PositionAlias alias = positionAliases.get(aliasKey(dimensionId, position));
        return alias != null && containsPosition(dimensionId, alias.canonical()) ? alias.canonical() : position;
    }

    public boolean containsPosition(String dimensionId, BlockPos position) {
        if (position == null) return false;
        MarketTerminalEntry entry = entries.get(MarketTerminalEntry.keyFor(dimensionId, position));
        return entry != null && entry.dimensionId().equals(dimensionId) && entry.marketPos().equals(position);
    }

    private void addAlias(PositionAlias alias) {
        if (!alias.dimensionId().isBlank() && !alias.previous().equals(alias.canonical())) {
            positionAliases.putIfAbsent(aliasKey(alias.dimensionId(), alias.previous()), alias);
        }
    }

    private static String aliasKey(String dimensionId, BlockPos pos) {
        // Explicit XYZ also preserves links outside the active Tellus packed-coordinate bounds.
        return dimensionId + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    private record PositionAlias(String dimensionId, BlockPos previous, BlockPos canonical) { }

    public record MarketTerminalEntry(
            String dimensionId,
            BlockPos marketPos,
            String marketName,
            String ownerUuid,
            String ownerName
    ) {
        public MarketTerminalEntry {
            dimensionId = dimensionId == null ? "" : dimensionId.trim();
            marketPos = marketPos == null ? BlockPos.ZERO : marketPos.immutable();
            marketName = marketName == null ? "" : marketName.trim();
            ownerUuid = ownerUuid == null ? "" : ownerUuid.trim();
            ownerName = ownerName == null ? "" : ownerName.trim();
        }

        public String key() {
            return keyFor(dimensionId, marketPos);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("DimensionId", dimensionId);
            PersistedBlockPos.write(tag, "MarketPos", marketPos);
            tag.putString("MarketName", marketName);
            tag.putString("OwnerUuid", ownerUuid);
            tag.putString("OwnerName", ownerName);
            return tag;
        }

        public static MarketTerminalEntry load(CompoundTag tag) {
            return load(tag, null);
        }

        private static MarketTerminalEntry load(CompoundTag tag, ServerLevel context) {
            return new MarketTerminalEntry(
                    tag.getString("DimensionId"),
                    PersistedBlockPos.readVerified(PersistedBlockPos.forDimension(context, tag.getString("DimensionId")),
                            tag, "MarketPos", "sailboatmod:market", "OwnerUuid", tag.getString("OwnerUuid")),
                    tag.getString("MarketName"),
                    tag.getString("OwnerUuid"),
                    tag.getString("OwnerName")
            );
        }

        public static String keyFor(String dimensionId, BlockPos pos) {
            BlockPos location = pos == null ? BlockPos.ZERO : pos;
            return (dimensionId == null ? "" : dimensionId.trim()) + "|"
                    + location.getX() + "|" + location.getY() + "|" + location.getZ();
        }
    }
}
