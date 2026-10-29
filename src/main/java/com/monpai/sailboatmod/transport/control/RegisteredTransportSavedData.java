package com.monpai.sailboatmod.transport.control;

import com.monpai.sailboatmod.transport.TransportRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/** Registration and recovery references only: inventory remains in real ship/vehicle handlers. */
public final class RegisteredTransportSavedData extends SavedData {
    private static final String NAME = "sailboatmod_registered_transports";
    private static final Set<MinecraftServer> STOPPING =
            Collections.newSetFromMap(new WeakHashMap<>());

    public static synchronized void markServerStopping(MinecraftServer server) {
        STOPPING.add(server);
    }

    public static synchronized boolean isServerStopping(MinecraftServer server) {
        return STOPPING.contains(server);
    }

    private final Map<String, UUID> physicalClaims = new HashMap<>();
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Set<UUID> knownRegistrations = new HashSet<>();

    public record Entry(
            UUID registrationId,
            String dimensionId,
            BlockPos controllerPos,
            UUID physicalId,
            String ownerUuid,
            boolean missionActive) {}

    public RegisteredTransportSavedData() {}

    public static RegisteredTransportSavedData get(ServerLevel level) {
        ServerLevel root =
                level.getServer().overworld() == null ? level : level.getServer().overworld();
        return root.getDataStorage()
                .computeIfAbsent(
                        new Factory<>(
                                RegisteredTransportSavedData::new, (tag, provider) -> load(tag)),
                        NAME);
    }

    public boolean claim(String dimensionId, UUID physicalId, UUID registrationId) {
        String key = dimensionId + ":" + physicalId;
        UUID existing = physicalClaims.get(key);
        if (existing != null && !existing.equals(registrationId)) return false;
        if (registrationId.equals(existing)) return true;
        physicalClaims
                .entrySet()
                .removeIf(
                        entry ->
                                entry.getValue().equals(registrationId)
                                        && !entry.getKey().equals(key));
        physicalClaims.put(key, registrationId);
        setDirty();
        return true;
    }

    public void put(Entry entry) {
        knownRegistrations.add(entry.registrationId());
        if (!entry.equals(entries.put(entry.registrationId(), entry))) setDirty();
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public boolean isOwnedRegistration(UUID id) {
        return knownRegistrations.contains(id);
    }

    public boolean shouldRetainPhysicalTicket(UUID id) {
        Entry entry = entries.get(id);
        return entry != null && entry.missionActive();
    }

    public void forget(UUID uuid) {
        knownRegistrations.add(uuid);
        entries.remove(uuid);
        physicalClaims.values().removeIf(uuid::equals);
        setDirty();
    }

    /** Removal always releases both loading and uniqueness, including failed native actuators. */
    public void removeController(UUID registration, Runnable stopNative, Runnable releaseTickets) {
        try {
            stopNative.run();
        } finally {
            try {
                releaseTickets.run();
            } finally {
                forget(registration);
            }
        }
    }

    public static void restoreKnown(MinecraftServer server) {
        if (server == null || server.overworld() == null) return;
        RegisteredTransportSavedData data = get(server.overworld());
        Map<ServerLevel, Map<UUID, Set<UUID>>> expected = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) expected.put(level, new HashMap<>());
        for (Entry entry : data.entries()) {
            ResourceLocation id = ResourceLocation.tryParse(entry.dimensionId());
            ServerLevel level =
                    id == null
                            ? null
                            : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (level != null) {
                TransportRegistry.remember(level, entry.registrationId());
                if (entry.missionActive() && entry.physicalId() != null) {
                    expected.get(level)
                            .computeIfAbsent(entry.physicalId(), ignored -> new HashSet<>())
                            .add(entry.registrationId());
                    com.monpai.sailboatmod.compat.aeronautics.SableLoadingTickets.update(
                            level, entry.physicalId(), entry.registrationId(), true);
                }
            }
        }
        expected.forEach(
                com.monpai.sailboatmod.compat.aeronautics.SableLoadingTickets::reconcileOwned);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag values = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag item = new CompoundTag();
            item.putUUID("RegistrationId", entry.registrationId());
            item.putString("DimensionId", entry.dimensionId());
            item.putLong("ControllerPos", entry.controllerPos().asLong());
            if (entry.physicalId() != null) item.putUUID("PhysicalId", entry.physicalId());
            item.putString("OwnerUuid", entry.ownerUuid());
            item.putBoolean("MissionActive", entry.missionActive());
            values.add(item);
        }
        tag.put("Entries", values);
        ListTag known = new ListTag();
        for (UUID id : knownRegistrations) known.add(StringTag.valueOf(id.toString()));
        tag.put("KnownRegistrations", known);
        return tag;
    }

    private static RegisteredTransportSavedData load(CompoundTag tag) {
        RegisteredTransportSavedData data = new RegisteredTransportSavedData();
        for (Tag raw : tag.getList("Entries", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) raw;
            if (!item.hasUUID("RegistrationId")) continue;
            Entry entry =
                    new Entry(
                            item.getUUID("RegistrationId"),
                            item.getString("DimensionId"),
                            BlockPos.of(item.getLong("ControllerPos")),
                            item.hasUUID("PhysicalId") ? item.getUUID("PhysicalId") : null,
                            item.getString("OwnerUuid"),
                            item.getBoolean("MissionActive"));
            data.entries.put(entry.registrationId(), entry);
            data.knownRegistrations.add(entry.registrationId());
            if (entry.physicalId() != null)
                data.physicalClaims.put(
                        entry.dimensionId() + ":" + entry.physicalId(), entry.registrationId());
        }
        for (Tag raw : tag.getList("KnownRegistrations", Tag.TAG_STRING))
            try {
                data.knownRegistrations.add(UUID.fromString(raw.getAsString()));
            } catch (IllegalArgumentException ignored) {
            }
        return data;
    }
}
