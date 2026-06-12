package com.monpai.sailboatmod.client.integration.xaero;

import com.monpai.sailboatmod.integration.xaero.SailboatClaimHighlightEntry;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class SailboatClaimHighlightIndex {
    public static final SailboatClaimHighlightIndex INSTANCE = new SailboatClaimHighlightIndex();

    private static final int REGION_CHUNK_SHIFT = 5;
    private static final int FILL_ALPHA = 0x66000000;
    private static final int BORDER_ALPHA = 0xCC000000;

    private final Map<String, DimensionClaims> dimensions = new HashMap<>();
    private long revision;

    public synchronized void replaceAll(Collection<SailboatClaimHighlightEntry> entries) {
        dimensions.clear();
        if (entries != null) {
            for (SailboatClaimHighlightEntry entry : entries) {
                addEntry(entry);
            }
        }
        rebuildRegions();
        revision++;
    }

    public synchronized void replaceDimension(String dimensionId, Collection<SailboatClaimHighlightEntry> entries) {
        String normalizedDimensionId = normalizeDimension(dimensionId);
        if (normalizedDimensionId.isBlank()) {
            return;
        }
        dimensions.remove(normalizedDimensionId);
        if (entries != null) {
            for (SailboatClaimHighlightEntry entry : entries) {
                if (normalizedDimensionId.equals(entry.dimensionId())) {
                    addEntry(entry);
                }
            }
        }
        rebuildRegions();
        revision++;
    }

    public synchronized Optional<SailboatClaimHighlightEntry> claimAt(String dimensionId, int chunkX, int chunkZ) {
        DimensionClaims claims = dimensions.get(normalizeDimension(dimensionId));
        if (claims == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(claims.byChunk.get(chunkKey(chunkX, chunkZ)));
    }

    public synchronized boolean chunkIsHighlighted(String dimensionId, int chunkX, int chunkZ) {
        return claimAt(dimensionId, chunkX, chunkZ).isPresent();
    }

    public synchronized boolean regionHasHighlights(String dimensionId, int regionX, int regionZ) {
        DimensionClaims claims = dimensions.get(normalizeDimension(dimensionId));
        return claims != null && claims.regions.containsKey(regionKey(regionX, regionZ));
    }

    public synchronized int regionHash(String dimensionId, int regionX, int regionZ) {
        DimensionClaims claims = dimensions.get(normalizeDimension(dimensionId));
        if (claims == null) {
            return 0;
        }
        return claims.regions.getOrDefault(regionKey(regionX, regionZ), 0);
    }

    public synchronized int[] colorsFor(String dimensionId, int chunkX, int chunkZ) {
        DimensionClaims claims = dimensions.get(normalizeDimension(dimensionId));
        if (claims == null) {
            return null;
        }
        SailboatClaimHighlightEntry claim = claims.byChunk.get(chunkKey(chunkX, chunkZ));
        if (claim == null) {
            return null;
        }

        int fill = FILL_ALPHA | claim.primaryColorRgb();
        int borderRgb = claim.secondaryColorRgb() == 0 ? claim.primaryColorRgb() : claim.secondaryColorRgb();
        int border = BORDER_ALPHA | borderRgb;
        return new int[] {
                fill,
                sameOwner(claim, claims.byChunk.get(chunkKey(chunkX, chunkZ - 1))) ? fill : border,
                sameOwner(claim, claims.byChunk.get(chunkKey(chunkX + 1, chunkZ))) ? fill : border,
                sameOwner(claim, claims.byChunk.get(chunkKey(chunkX, chunkZ + 1))) ? fill : border,
                sameOwner(claim, claims.byChunk.get(chunkKey(chunkX - 1, chunkZ))) ? fill : border
        };
    }

    public synchronized Component tooltipFor(String dimensionId, int chunkX, int chunkZ) {
        SailboatClaimHighlightEntry claim = claimAt(dimensionId, chunkX, chunkZ).orElse(null);
        if (claim == null) {
            claim = claimAt(dimensionId, chunkX >> 4, chunkZ >> 4).orElse(null);
        }
        if (claim == null) {
            return null;
        }
        String displayName = claim.displayName();
        if (displayName.isBlank()) {
            return null;
        }
        return Component.literal("Sailboat: " + displayName);
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized int size() {
        int size = 0;
        for (DimensionClaims claims : dimensions.values()) {
            size += claims.byChunk.size();
        }
        return size;
    }

    private void addEntry(SailboatClaimHighlightEntry entry) {
        if (entry == null || entry.dimensionId().isBlank()) {
            return;
        }
        dimensions.computeIfAbsent(entry.dimensionId(), ignored -> new DimensionClaims())
                .byChunk.put(chunkKey(entry.chunkX(), entry.chunkZ()), entry);
    }

    private void rebuildRegions() {
        for (DimensionClaims claims : dimensions.values()) {
            claims.regions.clear();
            Map<Long, List<SailboatClaimHighlightEntry>> byRegion = new HashMap<>();
            for (SailboatClaimHighlightEntry entry : claims.byChunk.values()) {
                int regionX = entry.chunkX() >> REGION_CHUNK_SHIFT;
                int regionZ = entry.chunkZ() >> REGION_CHUNK_SHIFT;
                byRegion.computeIfAbsent(regionKey(regionX, regionZ), ignored -> new ArrayList<>()).add(entry);
            }
            for (Map.Entry<Long, List<SailboatClaimHighlightEntry>> region : byRegion.entrySet()) {
                region.getValue().sort(Comparator
                        .comparingInt(SailboatClaimHighlightEntry::chunkX)
                        .thenComparingInt(SailboatClaimHighlightEntry::chunkZ));
                int hash = 1;
                for (SailboatClaimHighlightEntry entry : region.getValue()) {
                    hash = 31 * hash + entry.chunkX();
                    hash = 31 * hash + entry.chunkZ();
                    hash = 31 * hash + entry.ownerKey().hashCode();
                    hash = 31 * hash + entry.primaryColorRgb();
                    hash = 31 * hash + entry.secondaryColorRgb();
                }
                claims.regions.put(region.getKey(), hash == 0 ? 1 : hash);
            }
        }
    }

    private static boolean sameOwner(SailboatClaimHighlightEntry claim, SailboatClaimHighlightEntry neighbor) {
        return neighbor != null && claim.ownerKey().equals(neighbor.ownerKey());
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    private static String normalizeDimension(String dimensionId) {
        return dimensionId == null ? "" : dimensionId.trim().toLowerCase(Locale.ROOT);
    }

    private static final class DimensionClaims {
        private final Map<Long, SailboatClaimHighlightEntry> byChunk = new HashMap<>();
        private final Map<Long, Integer> regions = new HashMap<>();
    }
}
