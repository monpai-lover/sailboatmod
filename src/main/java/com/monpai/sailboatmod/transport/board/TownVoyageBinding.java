package com.monpai.sailboatmod.transport.board;

import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.Endpoint;

/** Per-carrier board identity only; owns no navigation or shipment state. */
public final class TownVoyageBinding {
    public static final String NBT_KEY = "TownVoyageBinding";
    private static final int VERSION = 1;
    private static final Set<String> BINDING_KEYS = Set.of("Version", "LegId", "Source", "Target");
    private static final Set<String> ENDPOINT_KEYS = Set.of("DimensionId", "TerminalPos", "TownId", "DisplayName");
    private Leg leg;
    public record Leg(UUID legId, Endpoint source, Endpoint target) {
        public Leg { if (legId == null || source == null || target == null) throw invalid(); }
    }
    public Optional<Leg> current() { return Optional.ofNullable(leg); }
    /** Call only after the carrier's existing start operation succeeded; this method cannot start motion. */
    public Leg startSuccessfulVoyage(Endpoint source, Endpoint target) {
        leg = new Leg(UUID.randomUUID(), source, target);
        return leg;
    }
    /** Renames, pauses, detours and newly resolved endpoints leave the actual voyage ID intact. */
    public boolean observeEndpoints(Endpoint source, Endpoint target) {
        if (leg == null) return false;
        leg = new Leg(leg.legId(), source, target);
        return true;
    }
    public void finish() { leg = null; }
    /** Invalid or future board metadata is ignored without changing the carrier's original tag. */
    public static TownVoyageBinding readFrom(CompoundTag carrierTag) {
        TownVoyageBinding binding = new TownVoyageBinding();
        if (carrierTag == null || !carrierTag.contains(NBT_KEY, Tag.TAG_COMPOUND)) return binding;
        try {
            CompoundTag value = carrierTag.getCompound(NBT_KEY);
            if (!value.getAllKeys().equals(BINDING_KEYS) || !value.contains("Version", Tag.TAG_INT)
                    || value.getInt("Version") != VERSION || !value.hasUUID("LegId")
                    || !value.contains("Source", Tag.TAG_COMPOUND) || !value.contains("Target", Tag.TAG_COMPOUND)) return binding;
            binding.leg = new Leg(value.getUUID("LegId"), readEndpoint(value.getCompound("Source")), readEndpoint(value.getCompound("Target")));
        } catch (IllegalArgumentException ignored) { /* Board metadata never prevents carrier navigation loading. */ }
        return binding;
    }
    public void writeTo(CompoundTag carrierTag) {
        if (carrierTag == null) throw invalid();
        if (leg == null) { carrierTag.remove(NBT_KEY); return; }
        CompoundTag value = new CompoundTag();
        value.putInt("Version", VERSION);
        value.putUUID("LegId", leg.legId());
        value.put("Source", writeEndpoint(leg.source()));
        value.put("Target", writeEndpoint(leg.target()));
        carrierTag.put(NBT_KEY, value);
    }
    private static CompoundTag writeEndpoint(Endpoint endpoint) {
        CompoundTag value = new CompoundTag();
        value.putString("DimensionId", endpoint.dimensionId());
        value.putLong("TerminalPos", endpoint.terminalPos());
        value.putString("TownId", endpoint.townId());
        value.putString("DisplayName", endpoint.displayName());
        return value;
    }
    private static Endpoint readEndpoint(CompoundTag value) {
        if (!value.getAllKeys().equals(ENDPOINT_KEYS) || !value.contains("DimensionId", Tag.TAG_STRING)
                || !value.contains("TerminalPos", Tag.TAG_LONG) || !value.contains("TownId", Tag.TAG_STRING)
                || !value.contains("DisplayName", Tag.TAG_STRING)) throw invalid();
        String name = value.getString("DisplayName");
        if (name.codePointCount(0, name.length()) > 64) throw invalid();
        return new Endpoint(value.getString("DimensionId"), value.getLong("TerminalPos"), value.getString("TownId"), name);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_town_voyage_binding"); }
}
