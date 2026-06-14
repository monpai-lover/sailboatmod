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
    private static final int REGION_CHUNK_SIZE = 1 << REGION_CHUNK_SHIFT;
    private static final int FILL_ALPHA = 0x66;
    private static final int BORDER_ALPHA = 0xCC;

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

        int fill = xaeroColor(claim.primaryColorRgb(), FILL_ALPHA);
        int borderRgb = claim.secondaryColorRgb() == 0 ? claim.primaryColorRgb() : claim.secondaryColorRgb();
        int border = xaeroColor(borderRgb, BORDER_ALPHA);
        return new int[] {
                fill,
                sameTerritory(claim, claims.byChunk.get(chunkKey(chunkX, chunkZ - 1))) ? fill : border,
                sameTerritory(claim, claims.byChunk.get(chunkKey(chunkX + 1, chunkZ))) ? fill : border,
                sameTerritory(claim, claims.byChunk.get(chunkKey(chunkX, chunkZ + 1))) ? fill : border,
                sameTerritory(claim, claims.byChunk.get(chunkKey(chunkX - 1, chunkZ))) ? fill : border
        };
    }

    public synchronized Component tooltipFor(String dimensionId, int chunkX, int chunkZ) {
        SailboatClaimHighlightEntry claim = claimAt(dimensionId, chunkX, chunkZ).orElse(null);
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
                long regionKey = region.getKey();
                int regionX = regionX(regionKey);
                int regionZ = regionZ(regionKey);
                List<SailboatClaimHighlightEntry> hashEntries = new ArrayList<>(region.getValue());
                addBorderEntries(hashEntries, byRegion, regionX, regionZ);
                hashEntries.sort(Comparator
                        .comparingInt(SailboatClaimHighlightEntry::chunkX)
                        .thenComparingInt(SailboatClaimHighlightEntry::chunkZ)
                        .thenComparing(SailboatClaimHighlightIndex::territoryKey));
                int hash = 1;
                for (SailboatClaimHighlightEntry entry : hashEntries) {
                    hash = 31 * hash + entry.chunkX();
                    hash = 31 * hash + entry.chunkZ();
                    hash = 31 * hash + territoryKey(entry).hashCode();
                    hash = 31 * hash + entry.primaryColorRgb();
                    hash = 31 * hash + entry.secondaryColorRgb();
                }
                claims.regions.put(regionKey, hash == 0 ? 1 : hash);
            }
        }
    }

    private static void addBorderEntries(List<SailboatClaimHighlightEntry> target,
                                         Map<Long, List<SailboatClaimHighlightEntry>> byRegion,
                                         int regionX,
                                         int regionZ) {
        int minX = regionX << REGION_CHUNK_SHIFT;
        int maxX = minX + REGION_CHUNK_SIZE - 1;
        int minZ = regionZ << REGION_CHUNK_SHIFT;
        int maxZ = minZ + REGION_CHUNK_SIZE - 1;
        addMatching(target, byRegion.get(regionKey(regionX, regionZ - 1)), minX, maxX, minZ - 1, minZ - 1);
        addMatching(target, byRegion.get(regionKey(regionX + 1, regionZ)), maxX + 1, maxX + 1, minZ, maxZ);
        addMatching(target, byRegion.get(regionKey(regionX, regionZ + 1)), minX, maxX, maxZ + 1, maxZ + 1);
        addMatching(target, byRegion.get(regionKey(regionX - 1, regionZ)), minX - 1, minX - 1, minZ, maxZ);
    }

    private static void addMatching(List<SailboatClaimHighlightEntry> target,
                                    List<SailboatClaimHighlightEntry> candidates,
                                    int minX,
                                    int maxX,
                                    int minZ,
                                    int maxZ) {
        if (candidates == null) {
            return;
        }
        for (SailboatClaimHighlightEntry entry : candidates) {
            if (entry.chunkX() >= minX && entry.chunkX() <= maxX && entry.chunkZ() >= minZ && entry.chunkZ() <= maxZ) {
                target.add(entry);
            }
        }
    }

    private static boolean sameTerritory(SailboatClaimHighlightEntry claim, SailboatClaimHighlightEntry neighbor) {
        return neighbor != null && territoryKey(claim).equals(territoryKey(neighbor));
    }

    private static String territoryKey(SailboatClaimHighlightEntry claim) {
        if (!claim.nationId().isBlank()) {
            return "nation:" + claim.nationId();
        }
        if (!claim.townId().isBlank()) {
            return "town:" + claim.townId();
        }
        return claim.ownerKey();
    }

    private static int xaeroColor(int rgb, int alpha) {
        int clampedRgb = rgb & 0x00FFFFFF;
        return ((clampedRgb & 0x0000FF) << 24)
                | ((clampedRgb & 0x00FF00) << 8)
                | ((clampedRgb & 0xFF0000) >> 8)
                | (alpha & 0xFF);
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    private static int regionX(long regionKey) {
        return (int) (regionKey >> 32);
    }

    private static int regionZ(long regionKey) {
        return (int) regionKey;
    }

    private static String normalizeDimension(String dimensionId) {
        return dimensionId == null ? "" : dimensionId.trim().toLowerCase(Locale.ROOT);
    }

    private static final class DimensionClaims {
        private final Map<Long, SailboatClaimHighlightEntry> byChunk = new HashMap<>();
        private final Map<Long, Integer> regions = new HashMap<>();
    }
}
