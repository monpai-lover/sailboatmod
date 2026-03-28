package com.example.examplemod.nation.model;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Set;

public record NationOfficeRecord(
        String officeId,
        String name,
        int priority,
        Set<NationPermission> permissions
) {
    public NationOfficeRecord {
        officeId = sanitizeId(officeId);
        name = sanitize(name);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("OfficeId", officeId);
        tag.putString("Name", name);
        tag.putInt("Priority", priority);
        tag.put("Permissions", NationPermission.saveSet(permissions));
        return tag;
    }

    public static NationOfficeRecord load(CompoundTag tag) {
        ListTag permissionsTag = tag.getList("Permissions", Tag.TAG_STRING);
        return new NationOfficeRecord(
                tag.getString("OfficeId"),
                tag.getString("Name"),
                tag.getInt("Priority"),
                NationPermission.loadSet(permissionsTag)
        );
    }

    public boolean hasPermission(NationPermission permission) {
        return permission != null && permissions.contains(permission);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeId(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
