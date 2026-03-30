package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record NationFlagRecord(
        String flagId,
        String nationId,
        String sha256,
        int width,
        int height,
        long uploadedAt,
        String uploadedBy,
        long byteSize,
        boolean mirrored
) {
    public NationFlagRecord {
        flagId = normalize(flagId);
        nationId = normalize(nationId);
        sha256 = normalize(sha256);
        uploadedBy = normalize(uploadedBy);
        width = Math.max(0, width);
        height = Math.max(0, height);
        uploadedAt = Math.max(0L, uploadedAt);
        byteSize = Math.max(0L, byteSize);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("FlagId", flagId);
        tag.putString("NationId", nationId);
        tag.putString("Sha256", sha256);
        tag.putInt("Width", width);
        tag.putInt("Height", height);
        tag.putLong("UploadedAt", uploadedAt);
        tag.putString("UploadedBy", uploadedBy);
        tag.putLong("ByteSize", byteSize);
        tag.putBoolean("Mirrored", mirrored);
        return tag;
    }

    public static NationFlagRecord load(CompoundTag tag) {
        return new NationFlagRecord(
                tag.getString("FlagId"),
                tag.getString("NationId"),
                tag.getString("Sha256"),
                tag.getInt("Width"),
                tag.getInt("Height"),
                tag.getLong("UploadedAt"),
                tag.getString("UploadedBy"),
                tag.getLong("ByteSize"),
                tag.contains("Mirrored") && tag.getBoolean("Mirrored")
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
