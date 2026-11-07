package com.monpai.sailboatmod.transport.physical;

import com.monpai.sailboatmod.transport.air.airport.*;
import java.util.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/** Pure configured terrain footprint. Never loads terrain or enumerates a flight-route rectangle. */
public final class AirportLoadingFootprint {
    private AirportLoadingFootprint() { }
    public static Optional<Set<ChunkPos>> collect(List<AirportFacility> source, AirportGroundLayout sourceGround,
                                                List<AirportFacility> target, AirportGroundLayout targetGround) {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        if (!addAirport(chunks,source,sourceGround) || !addAirport(chunks,target,targetGround)) return Optional.empty();
        return Optional.of(Collections.unmodifiableSet(chunks));
    }
    private static boolean addAirport(Set<ChunkPos> chunks,List<AirportFacility> facilities,AirportGroundLayout ground) {
        if (facilities == null || facilities.size() > 16 || ground == null) return false;
        for (var facility : facilities) {
            if (facility == null) return false;
            var footprint = facility.clearanceChunks();
            if (footprint.isEmpty()) return false;
            for (long key : footprint.orElseThrow()) if (!add(chunks,new ChunkPos(key))) return false;
        }
        // Match AirportBE.groundLayoutLoaded, including its exact max boundary.
        for (var stand : ground.stands()) if (!addBounds(chunks,stand.bounds())) return false;
        for (var route : ground.taxiRoutes()) if (!addBounds(chunks,route.bounds())) return false;
        return true;
    }
    private static boolean addBounds(Set<ChunkPos> chunks,AABB bounds) {
        if (!Double.isFinite(bounds.minX) || !Double.isFinite(bounds.maxX) || !Double.isFinite(bounds.minZ) || !Double.isFinite(bounds.maxZ)) return false;
        int minX=(int)Math.floor(bounds.minX)>>4,maxX=(int)Math.floor(bounds.maxX)>>4;
        int minZ=(int)Math.floor(bounds.minZ)>>4,maxZ=(int)Math.floor(bounds.maxZ)>>4;
        for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++) if (!add(chunks,new ChunkPos(x,z))) return false;
        return true;
    }
    private static boolean add(Set<ChunkPos> chunks,ChunkPos chunk) {
        if (chunks.contains(chunk)) return true;
        if (chunks.size() >= TransportLoadingWindow.MAX_AIRPORT_CHUNKS) return false;
        chunks.add(chunk); return true;
    }
}
