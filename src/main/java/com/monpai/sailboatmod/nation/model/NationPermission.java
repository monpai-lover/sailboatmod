package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.EnumSet;
import java.util.Set;

public enum NationPermission {
    INVITE_MEMBERS,
    KICK_MEMBERS,
    MANAGE_INFO,
    MANAGE_OFFICES,
    MANAGE_CLAIMS,
    DECLARE_WAR,
    UPLOAD_FLAG,
    PLACE_CORE,
    MANAGE_TREASURY;

    public static Set<NationPermission> loadSet(ListTag tag) {
        EnumSet<NationPermission> permissions = EnumSet.noneOf(NationPermission.class);
        for (Tag raw : tag) {
            if (!(raw instanceof StringTag stringTag)) {
                continue;
            }
            try {
                permissions.add(NationPermission.valueOf(stringTag.getAsString()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return permissions;
    }

    public static ListTag saveSet(Set<NationPermission> permissions) {
        ListTag tag = new ListTag();
        if (permissions == null) {
            return tag;
        }
        for (NationPermission permission : permissions) {
            tag.add(StringTag.valueOf(permission.name()));
        }
        return tag;
    }
}
