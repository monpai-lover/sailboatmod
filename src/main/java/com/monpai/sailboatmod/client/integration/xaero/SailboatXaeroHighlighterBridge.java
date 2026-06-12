package com.monpai.sailboatmod.client.integration.xaero;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

public final class SailboatXaeroHighlighterBridge {
    public static boolean regionHasHighlights(Object dimension, int regionX, int regionZ) {
        return SailboatClaimHighlightIndex.INSTANCE.regionHasHighlights(dimensionId(dimension), regionX, regionZ);
    }

    public static boolean chunkIsHighlit(Object dimension, int chunkX, int chunkZ) {
        return SailboatClaimHighlightIndex.INSTANCE.chunkIsHighlighted(dimensionId(dimension), chunkX, chunkZ);
    }

    public static int[] getColors(Object dimension, int chunkX, int chunkZ) {
        return SailboatClaimHighlightIndex.INSTANCE.colorsFor(dimensionId(dimension), chunkX, chunkZ);
    }

    public static int calculateRegionHash(Object dimension, int regionX, int regionZ) {
        return SailboatClaimHighlightIndex.INSTANCE.regionHash(dimensionId(dimension), regionX, regionZ);
    }

    public static Component getSubtleTooltip(Object dimension, int chunkX, int chunkZ) {
        return SailboatClaimHighlightIndex.INSTANCE.tooltipFor(dimensionId(dimension), chunkX, chunkZ);
    }

    public static Component getBluntTooltip(Object dimension, int chunkX, int chunkZ) {
        return SailboatClaimHighlightIndex.INSTANCE.tooltipFor(dimensionId(dimension), chunkX, chunkZ);
    }

    public static void addMinimapTooltip(Object compiler, Object dimension, int chunkX, int chunkZ, int ignored) {
        Component tooltip = SailboatClaimHighlightIndex.INSTANCE.tooltipFor(dimensionId(dimension), chunkX, chunkZ);
        if (tooltip == null || compiler == null) {
            return;
        }
        try {
            Method addLine = compiler.getClass().getMethod("addLine", Component.class);
            addLine.invoke(compiler, tooltip);
        } catch (ReflectiveOperationException ignoredException) {
        }
    }

    @SuppressWarnings("unchecked")
    public static void addWorldMapMinimapTooltip(Object list, Object dimension, int chunkX, int chunkZ, int ignored) {
        Component tooltip = SailboatClaimHighlightIndex.INSTANCE.tooltipFor(dimensionId(dimension), chunkX, chunkZ);
        if (tooltip == null || !(list instanceof List<?> rawList)) {
            return;
        }
        ((List<Component>) rawList).add(tooltip);
    }

    private static String dimensionId(Object dimension) {
        if (dimension instanceof ResourceKey<?> key) {
            return key.location().toString().toLowerCase(Locale.ROOT);
        }
        if (dimension == null) {
            return "";
        }
        try {
            Object location = dimension.getClass().getMethod("location").invoke(dimension);
            return location == null ? "" : location.toString().toLowerCase(Locale.ROOT);
        } catch (ReflectiveOperationException ignored) {
            return dimension.toString().trim().toLowerCase(Locale.ROOT);
        }
    }

    private SailboatXaeroHighlighterBridge() {
    }
}
