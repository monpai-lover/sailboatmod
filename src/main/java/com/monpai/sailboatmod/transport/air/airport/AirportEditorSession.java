package com.monpai.sailboatmod.transport.air.airport;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/** One immutable airport edit, using the existing bounded terrain scanners. Never persists configuration. */
public final class AirportEditorSession {
    public record Proposal(List<AirportFacility> facilities, AirportGroundLayout ground, AirportFacility changedRunway,
                           boolean validationOnly) {
        public Proposal { facilities = List.copyOf(facilities); Objects.requireNonNull(ground); }
    }
    public static final int PER_TICK_BUDGET = 2048;
    // All runway-linked geometry stays within the 2176 endpoint radius plus the
    // 96m deck margin/width padding: its common chunk rectangle is <87k keys.
    public static final int MAX_METADATA_CHUNKS = 131072;
    private static final int MAX_AREAS = 16 + AirportGroundLayout.MAX_STANDS + AirportGroundLayout.MAX_ROUTES;
    private static final int MAX_CHUNK_SPAN = (AirportGroundValidation.LINKED_PERMISSION_SPAN + 15) / 16 + 2;
    // Runway Scan has two phases; include its conservative step bound, both ground phases,
    // two full bounded claim passes and scheduling slack. This is not a snapshot cache TTL.
    public static final long MAX_TICKS = (AirportValidation.MAX_SCAN_STEPS + (long) AirportGroundValidation.MAX_SCAN_STEPS
            + 2L * MAX_AREAS * MAX_CHUNK_SPAN * MAX_CHUNK_SPAN + PER_TICK_BUDGET - 1) / PER_TICK_BUDGET + 40;
    public final UUID owner;
    public final long baseRevision, startedAt;
    public final Proposal proposal;
    private final String townId;
    private final List<AABB> claims = new ArrayList<>();
    private final Map<Long, int[]> requiredChunks = new LinkedHashMap<>();
    private final AirportValidation.Scan runwayScan;
    private AirportGroundValidation.Scan groundScan;
    private int stage, areaIndex, chunkX, chunkZ, minZ, maxX, maxZ, used;
    private boolean areaReady;
    private long processed;
    private long finalClaimRevision;
    private boolean finalClaimsStarted;
    private String result = "pending";
    public AirportEditorSession(UUID owner, long baseRevision, long startedAt, Proposal proposal, String townId) {
        this.owner = Objects.requireNonNull(owner); this.baseRevision = baseRevision; this.startedAt = startedAt;
        this.proposal = Objects.requireNonNull(proposal); this.townId = Objects.requireNonNull(townId);
        if (baseRevision < 0 || townId.isBlank() || proposal.facilities().size() > 16) result = "invalid_geometry";
        for (var facility : proposal.facilities()) {
            addClaim(facility.clearanceBounds(), facility.type() == AirportFacility.Type.RUNWAY ? AirportFacility.MAX_RUNWAY_CLEARANCE_SPAN : 2048);
            var footprint=facility.clearanceChunks();
            if(footprint.isEmpty())result="too_large";
            else for(long key:footprint.get())rememberChunk(key,facility.start().getY()-1,(int)Math.ceil(facility.clearanceBounds().maxY)-1);
        }
        for (var stand : proposal.ground().stands()) addClaim(stand.bounds(), 2048);
        for (var route : proposal.ground().taxiRoutes()) addClaim(route.bounds(), AirportGroundValidation.linkedEndpoint(route,proposal.facilities(),proposal.ground().stands())
                ? AirportGroundValidation.LINKED_PERMISSION_SPAN : 2048);
        runwayScan = proposal.changedRunway() == null ? null : new AirportValidation.Scan(proposal.changedRunway());
    }
    private void addClaim(AABB area, int maximumSpan) {
        if (!Double.isFinite(area.minX) || !Double.isFinite(area.maxX) || !Double.isFinite(area.minZ) || !Double.isFinite(area.maxZ)
                || area.getXsize() > maximumSpan || area.getZsize() > maximumSpan) result = "foreign_town";
        claims.add(area);
    }
    public String result() { return result; }
    public int stepsUsed() { return used; }
    public long processedSteps() { return processed; }
    public void cancel() { result = "cancelled"; }
    public boolean isCurrent(AirportEditorSession current, UUID player, long revision, boolean authorized, long now) {
        return this == current && owner.equals(player) && baseRevision == revision && authorized
                && now >= startedAt && now - startedAt < MAX_TICKS && !result.equals("cancelled");
    }
    public boolean canCommit(AirportEditorSession current, UUID player, long revision, boolean authorized, long now) {
        return result.equals("valid") && isCurrent(current, player, revision, authorized, now);
    }
    public boolean canCommit(AirportEditorSession current, UUID player, long revision, boolean authorized, long now, long claimRevision) {
        return canCommit(current, player, revision, authorized, now) && finalClaimsStarted && claimRevision==finalClaimRevision;
    }
    public boolean loadedBeforeCommit(Predicate<BlockPos> loaded) {
        if(!result.equals("valid")||loaded==null)return false;
        for(var entry:requiredChunks.entrySet()){
            long key=entry.getKey();int[] height=entry.getValue();int x=ChunkPos.getX(key)<<4,z=ChunkPos.getZ(key)<<4;
            if(!loaded.test(new BlockPos(x,height[0],z))||!loaded.test(new BlockPos(x,height[1],z)))return false;
        }
        return true;
    }
    public String advance(int budget, Function<BlockPos, String> townAt, Predicate<BlockPos> loaded,
                          Predicate<BlockPos> clear, Predicate<BlockPos> supported) {
        return advance(budget,townAt,loaded,clear,supported,()->0L);
    }
    public String advance(int budget, Function<BlockPos, String> townAt, Predicate<BlockPos> loaded,
                          Predicate<BlockPos> clear, Predicate<BlockPos> supported, LongSupplier claimRevision) {
        used = 0;
        if(finalClaimsStarted&&claimRevision.getAsLong()!=finalClaimRevision)result="stale_permission";
        while (result.equals("pending") && used < Math.max(0, budget)) {
            if (stage == 0 || stage == 3) {
                if (!advanceClaims(townAt)) continue;
                if (stage == 3) { result = "valid"; break; }
                stage = 1;
            } else if (stage == 1) {
                if (runwayScan != null) {
                    var status = runwayScan.advance(budget - used, loaded, clear, supported);
                    used += runwayScan.stepsUsed();
                    if (status == AirportValidation.Result.WAITING) break;
                    if (status != AirportValidation.Result.VALID) { result = status.name().toLowerCase(Locale.ROOT); break; }
                }
                stage = 2;
            } else {
                if (!proposal.validationOnly()) {
                    // Cell construction and both terrain phases share this same tick budget.
                    if (groundScan == null) groundScan = new AirportGroundValidation.Scan(proposal.ground(), proposal.facilities());
                    var status = groundScan.advance(budget - used, loaded, clear, supported);
                    used += groundScan.stepsUsed();
                    if (status == AirportGroundValidation.Result.WAITING) break;
                    if (status != AirportGroundValidation.Result.VALID) { result = status.name().toLowerCase(Locale.ROOT); break; }
                }
                stage = 3; areaIndex = 0; areaReady = false;
                finalClaimRevision=claimRevision.getAsLong();finalClaimsStarted=true;
            }
        }
        if(finalClaimsStarted&&claimRevision.getAsLong()!=finalClaimRevision)result="stale_permission";
        processed += used;
        return result;
    }
    private boolean advanceClaims(Function<BlockPos, String> townAt) {
        if (areaIndex >= claims.size()) return true;
        AABB area = claims.get(areaIndex);
        if (!areaReady) {
            chunkX = (int) Math.floor(area.minX) >> 4;
            minZ = chunkZ = (int) Math.floor(area.minZ) >> 4;
            maxX = ((int) Math.ceil(area.maxX) - 1) >> 4;
            maxZ = ((int) Math.ceil(area.maxZ) - 1) >> 4;
            areaReady = true;
        }
        used++;
        // Reuse the already-budgeted permission cursor for ground metadata. Runway
        // empty AABB corners deliberately stay out of the loaded-chunk footprint.
        if(stage==0&&areaIndex>=proposal.facilities().size())rememberChunk(ChunkPos.asLong(chunkX,chunkZ),(int)Math.floor(area.minY)-1,(int)Math.ceil(area.maxY)-1);
        if (!townId.equals(townAt.apply(new BlockPos(chunkX << 4, (int) area.minY, chunkZ << 4)))) {
            result = "foreign_town"; return false;
        }
        if (++chunkZ > maxZ) { chunkZ = minZ; if (++chunkX > maxX) { areaIndex++; areaReady = false; } }
        return false;
    }
    private void rememberChunk(long key,int minY,int maxY){
        int[] height=requiredChunks.get(key);
        if(height!=null){height[0]=Math.min(height[0],minY);height[1]=Math.max(height[1],maxY);return;}
        if(requiredChunks.size()>=MAX_METADATA_CHUNKS){result="too_large";return;}
        requiredChunks.put(key,new int[]{minY,maxY});
    }
}
