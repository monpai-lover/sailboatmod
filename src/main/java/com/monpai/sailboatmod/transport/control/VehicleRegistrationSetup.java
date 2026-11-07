package com.monpai.sailboatmod.transport.control;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;

/** Inputs and coordinate bounds shared by first registration and optional native setup. */
public final class VehicleRegistrationSetup {
    private VehicleRegistrationSetup() { }

    public record Request(VehicleRegistrationMode mode, String name) { }

    public static Request parse(String value) {
        if (value.isBlank()) return new Request(null, null);
        int separator = value.indexOf(':');
        if (separator < 0) return new Request(VehicleRegistrationMode.valueOf(value), null);
        var mode = VehicleRegistrationMode.valueOf(value.substring(0, separator));
        String supplied = value.substring(separator + 1);
        String name = supplied.strip();
        if (name.isBlank() || name.codePointCount(0, name.length()) > 64
                || supplied.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid_vehicle_name");
        }
        return new Request(mode, name);
    }

    public static Optional<BlockPos> uniqueOffset(BlockPos origin, List<BlockPos> matches) {
        if (matches.size() != 1) return Optional.empty();
        BlockPos candidate = matches.getFirst();
        long dx = (long) candidate.getX() - origin.getX();
        long dy = (long) candidate.getY() - origin.getY();
        long dz = (long) candidate.getZ() - origin.getZ();
        if (Math.abs(dx) > 64 || Math.abs(dy) > 64 || Math.abs(dz) > 64) return Optional.empty();
        return Optional.of(new BlockPos((int) dx, (int) dy, (int) dz));
    }
}
