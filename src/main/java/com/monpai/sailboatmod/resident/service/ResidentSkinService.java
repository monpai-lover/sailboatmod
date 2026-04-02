package com.monpai.sailboatmod.resident.service;

import com.monpai.sailboatmod.resident.model.Gender;
import com.monpai.sailboatmod.resident.model.Profession;

import java.util.Locale;

public final class ResidentSkinService {
    private static final int DEFAULT_VARIANT_COUNT = 4;
    private static final String RESIDENT_TEXTURE_PREFIX = "textures/entity/resident/";

    public static String resolveSkinHash(String skinHash, String seed, Profession profession, Gender gender) {
        String normalized = normalizeSkinHash(skinHash);
        return normalized.isBlank() ? chooseFallbackSkin(seed, profession, gender) : normalized;
    }

    public static String chooseFallbackSkin(String seed, Profession profession, Gender gender) {
        Profession resolvedProfession = profession == null ? Profession.UNEMPLOYED : profession;
        Gender resolvedGender = gender == null ? Gender.MALE : gender;
        int variant = selectVariant(seed, DEFAULT_VARIANT_COUNT);
        if (resolvedProfession == Profession.TEACHER) {
            return "teacher/" + resolvedGender.id() + "_" + variant;
        }
        return resolvedProfession.id() + "_" + resolvedGender.id() + "_0" + variant;
    }

    public static String normalizeSkinHash(String skinHash) {
        if (skinHash == null || skinHash.isBlank()) {
            return "";
        }

        String normalized = skinHash.trim().replace('\\', '/');
        int namespaceIndex = normalized.indexOf(':');
        if (namespaceIndex >= 0 && namespaceIndex < normalized.length() - 1) {
            normalized = normalized.substring(namespaceIndex + 1);
        }
        if (normalized.startsWith(RESIDENT_TEXTURE_PREFIX)) {
            normalized = normalized.substring(RESIDENT_TEXTURE_PREFIX.length());
        } else if (normalized.startsWith("entity/resident/")) {
            normalized = normalized.substring("entity/resident/".length());
        } else if (normalized.startsWith("resident/")) {
            normalized = normalized.substring("resident/".length());
        }
        if (normalized.endsWith(".png")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("teacher_")) {
            normalized = "teacher/" + normalized.substring("teacher_".length());
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static int selectVariant(String seed, int variantCount) {
        String stableSeed = (seed == null || seed.isBlank()) ? "resident" : seed;
        return Math.floorMod(stableSeed.hashCode(), Math.max(1, variantCount)) + 1;
    }

    private ResidentSkinService() {
    }
}
