package com.monpai.sailboatmod.transport.control;

import com.monpai.sailboatmod.transport.LogisticsCarrier;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

/** Persistent business identity, independent of replaceable physical assembly ids. */
public final class ControllerRegistration {
    private final UUID registrationId;
    private final String ownerUuid;
    private final LogisticsCarrier.Kind kind;
    private UUID physicalId;
    private boolean registered;

    public ControllerRegistration(
            UUID registrationId, String ownerUuid, LogisticsCarrier.Kind kind) {
        this.registrationId = registrationId;
        this.ownerUuid = ownerUuid == null ? "" : ownerUuid;
        this.kind = kind;
    }

    public UUID registrationId() {
        return registrationId;
    }

    public String ownerUuid() {
        return ownerUuid;
    }

    public LogisticsCarrier.Kind kind() {
        return kind;
    }

    public UUID physicalId() {
        return physicalId;
    }

    public boolean registered() {
        return registered;
    }

    public static boolean preserveLoadingOnRemoval(
            boolean serverStopping, boolean active, boolean paused) {
        return serverStopping && active && !paused;
    }

    /** Each native command owns one physical output face; conflicting wiring cannot be enabled. */
    public static boolean validControlPorts(Map<ControlChannel, Direction> ports) {
        if (ports == null || ports.size() != ControlChannel.values().length) return false;
        EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
        for (ControlChannel channel : ControlChannel.values()) {
            Direction face = ports.get(channel);
            if (face == null || !faces.add(face)) return false;
        }
        return true;
    }

    public boolean register(UUID physicalId, double mass, boolean unique) {
        if (physicalId == null || !Double.isFinite(mass) || mass <= 0 || !unique) return false;
        this.physicalId = physicalId;
        registered = true;
        return true;
    }

    public void unregister() {
        registered = false;
        physicalId = null;
    }

    public boolean canManage(UUID actor, boolean admin, double worldDistanceSquared) {
        return actor != null
                && Double.isFinite(worldDistanceSquared)
                && worldDistanceSquared >= 0
                && worldDistanceSquared <= 64
                && (admin || actor.toString().equals(ownerUuid));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("RegistrationId", registrationId);
        tag.putString("OwnerUuid", ownerUuid);
        tag.putString("Kind", kind.name());
        tag.putBoolean("Registered", registered);
        if (physicalId != null) tag.putUUID("PhysicalId", physicalId);
        return tag;
    }

    public static ControllerRegistration load(CompoundTag tag) {
        LogisticsCarrier.Kind kind;
        try {
            kind = LogisticsCarrier.Kind.valueOf(tag.getString("Kind"));
        } catch (IllegalArgumentException ignored) {
            kind = LogisticsCarrier.Kind.WATER;
        }
        ControllerRegistration state =
                new ControllerRegistration(
                        tag.hasUUID("RegistrationId")
                                ? tag.getUUID("RegistrationId")
                                : UUID.randomUUID(),
                        tag.getString("OwnerUuid"),
                        kind);
        state.physicalId = tag.hasUUID("PhysicalId") ? tag.getUUID("PhysicalId") : null;
        state.registered = tag.getBoolean("Registered");
        return state;
    }
}
