package com.monpai.sailboatmod.integration.minecolonies;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.menu.ExternalColonyOverview;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class MineColoniesIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CACHE_TICKS = 40L;
    private static final int MAX_CLAIM_SAMPLES_PER_REFRESH = 512;
    private static final Map<CacheKey, CachedOverview> CACHE = new ConcurrentHashMap<>();

    private static volatile ReflectionBridge bridge;
    private static volatile boolean initAttempted;
    private static volatile boolean disabled;

    public static ExternalColonyOverview findTownColony(ServerLevel level,
                                                        NationSavedData data,
                                                        TownRecord town,
                                                        NationRecord nation) {
        if (level == null || data == null || town == null) {
            return ExternalColonyOverview.empty();
        }
        CacheKey key = new CacheKey(level.dimension().location().toString(), town.townId());
        long tick = level.getServer() == null ? 0L : level.getServer().getTickCount();
        CachedOverview cached = CACHE.get(key);
        if (cached != null && tick - cached.tick() <= CACHE_TICKS) {
            return cached.overview();
        }

        ReflectionBridge api = bridge();
        if (api == null) {
            return ExternalColonyOverview.empty();
        }

        ExternalColonyOverview overview;
        try {
            overview = api.findTownColony(level, data, town, nation).map(ExternalColonySnapshot::toOverview)
                    .orElseGet(ExternalColonyOverview::empty);
        } catch (Throwable throwable) {
            disabled = true;
            LOGGER.error("Disabling MineColonies integration after regional lookup failure", throwable);
            overview = ExternalColonyOverview.empty();
        }
        CACHE.put(key, new CachedOverview(tick, overview));
        return overview;
    }

    public static void onServerStopped() {
        CACHE.clear();
        bridge = null;
        initAttempted = false;
        disabled = false;
    }

    private static ReflectionBridge bridge() {
        if (disabled) {
            return null;
        }
        if (!ModList.get().isLoaded("minecolonies")) {
            return null;
        }
        if (!initAttempted) {
            synchronized (MineColoniesIntegration.class) {
                if (!initAttempted) {
                    initAttempted = true;
                    try {
                        bridge = new ReflectionBridge();
                    } catch (ClassNotFoundException ignored) {
                        return null;
                    } catch (Throwable throwable) {
                        disabled = true;
                        LOGGER.error("Failed to bootstrap MineColonies integration", throwable);
                    }
                }
            }
        }
        return bridge;
    }

    private static List<NationClaimRecord> managedClaims(NationSavedData data,
                                                         TownRecord town,
                                                         NationRecord nation,
                                                         String dimensionId) {
        List<NationClaimRecord> claims = new ArrayList<>();
        if (nation != null && town.townId().equals(nation.capitalTownId())) {
            for (NationClaimRecord claim : data.getClaimsForNation(nation.nationId())) {
                if (dimensionId.equalsIgnoreCase(claim.dimensionId()) && isClaimManagedByTown(claim, town, nation)) {
                    claims.add(claim);
                }
            }
        } else {
            for (NationClaimRecord claim : data.getClaimsForTown(town.townId())) {
                if (dimensionId.equalsIgnoreCase(claim.dimensionId())) {
                    claims.add(claim);
                }
            }
        }
        claims.sort(Comparator.comparingInt(NationClaimRecord::chunkZ).thenComparingInt(NationClaimRecord::chunkX));
        if (claims.size() <= MAX_CLAIM_SAMPLES_PER_REFRESH) {
            return claims;
        }
        return claims.subList(0, MAX_CLAIM_SAMPLES_PER_REFRESH);
    }

    private static boolean isClaimManagedByTown(NationClaimRecord claim, TownRecord town, NationRecord nation) {
        if (claim == null || town == null) {
            return false;
        }
        if (town.townId().equals(claim.townId())) {
            return true;
        }
        return claim.townId().isBlank()
                && nation != null
                && town.townId().equals(nation.capitalTownId())
                && nation.nationId().equals(claim.nationId());
    }

    private record CacheKey(String dimensionId, String townId) {
    }

    private record CachedOverview(long tick, ExternalColonyOverview overview) {
    }

    private static final class ReflectionBridge {
        private final Object manager;
        private final Method getIColonyMethod;
        private final Method getColonyByWorldMethod;
        private final Method colonyGetIdMethod;
        private final Method colonyGetNameMethod;
        private final Method colonyGetDimensionMethod;
        private final Method colonyGetPermissionsMethod;
        private final Method colonyGetCitizenManagerMethod;
        private final Method colonyGetOverallHappinessMethod;
        private final Method permissionsGetOwnerNameMethod;
        private final Method citizenManagerGetCurrentCitizenCountMethod;
        private final Method citizenManagerGetMaxCitizensMethod;

        private ReflectionBridge() throws Exception {
            Class<?> managerClass = Class.forName("com.minecolonies.api.colony.IColonyManager");
            Class<?> colonyClass = Class.forName("com.minecolonies.api.colony.IColony");
            Class<?> permissionsClass = Class.forName("com.minecolonies.api.colony.permissions.IPermissions");
            Class<?> citizenManagerClass = Class.forName("com.minecolonies.api.colony.managers.interfaces.ICitizenManager");

            this.manager = managerClass.getMethod("getInstance").invoke(null);
            this.getIColonyMethod = managerClass.getMethod("getIColony", Level.class, BlockPos.class);
            this.getColonyByWorldMethod = managerClass.getMethod("getColonyByWorld", int.class, Level.class);
            this.colonyGetIdMethod = colonyClass.getMethod("getID");
            this.colonyGetNameMethod = colonyClass.getMethod("getName");
            this.colonyGetDimensionMethod = colonyClass.getMethod("getDimension");
            this.colonyGetPermissionsMethod = colonyClass.getMethod("getPermissions");
            this.colonyGetCitizenManagerMethod = colonyClass.getMethod("getCitizenManager");
            this.colonyGetOverallHappinessMethod = colonyClass.getMethod("getOverallHappiness");
            this.permissionsGetOwnerNameMethod = permissionsClass.getMethod("getOwnerName");
            this.citizenManagerGetCurrentCitizenCountMethod = citizenManagerClass.getMethod("getCurrentCitizenCount");
            this.citizenManagerGetMaxCitizensMethod = citizenManagerClass.getMethod("getMaxCitizens");
        }

        private Optional<ExternalColonySnapshot> findTownColony(ServerLevel level,
                                                               NationSavedData data,
                                                               TownRecord town,
                                                               NationRecord nation) throws Exception {
            String dimensionId = level.dimension().location().toString();
            Map<ExternalColonyRef, Object> coloniesByRef = new HashMap<>();
            Optional<ExternalColonyRef> coreMatch = Optional.empty();

            if (town.hasCore() && dimensionId.equalsIgnoreCase(town.coreDimension())) {
                Object colony = colonyAt(level, BlockPos.of(town.corePos()));
                if (colony != null) {
                    ExternalColonyRef ref = refOf(colony);
                    coreMatch = Optional.of(ref);
                    coloniesByRef.put(ref, colony);
                }
            }

            List<ExternalColonyRef> claimMatches = new ArrayList<>();
            for (NationClaimRecord claim : managedClaims(data, town, nation, dimensionId)) {
                BlockPos samplePos = new BlockPos((claim.chunkX() << 4) + 8, level.getSeaLevel(), (claim.chunkZ() << 4) + 8);
                Object colony = colonyAt(level, samplePos);
                if (colony == null) {
                    continue;
                }
                ExternalColonyRef ref = refOf(colony);
                claimMatches.add(ref);
                coloniesByRef.putIfAbsent(ref, colony);
            }

            Optional<ExternalColonyRef> selected = MineColoniesTownMatcher.select(coreMatch, claimMatches);
            if (selected.isEmpty()) {
                return Optional.empty();
            }
            Object colony = coloniesByRef.get(selected.get());
            if (colony == null) {
                colony = getColonyByWorld(selected.get().colonyId(), level);
            }
            return colony == null ? Optional.empty() : Optional.of(snapshot(colony));
        }

        private Object colonyAt(Level level, BlockPos pos) throws Exception {
            return this.getIColonyMethod.invoke(this.manager, level, pos);
        }

        private Object getColonyByWorld(int id, Level level) throws Exception {
            return this.getColonyByWorldMethod.invoke(this.manager, id, level);
        }

        private ExternalColonyRef refOf(Object colony) throws Exception {
            return new ExternalColonyRef(dimensionIdOf(colony), (int) this.colonyGetIdMethod.invoke(colony));
        }

        private ExternalColonySnapshot snapshot(Object colony) throws Exception {
            Object permissions = this.colonyGetPermissionsMethod.invoke(colony);
            Object citizenManager = this.colonyGetCitizenManagerMethod.invoke(colony);
            return new ExternalColonySnapshot(
                    "minecolonies",
                    dimensionIdOf(colony),
                    (int) this.colonyGetIdMethod.invoke(colony),
                    safeString(this.colonyGetNameMethod.invoke(colony)),
                    permissions == null ? "" : safeString(this.permissionsGetOwnerNameMethod.invoke(permissions)),
                    citizenManager == null ? 0 : (int) this.citizenManagerGetCurrentCitizenCountMethod.invoke(citizenManager),
                    citizenManager == null ? 0 : (int) this.citizenManagerGetMaxCitizensMethod.invoke(citizenManager),
                    ((Number) this.colonyGetOverallHappinessMethod.invoke(colony)).floatValue()
            );
        }

        private String dimensionIdOf(Object colony) throws Exception {
            @SuppressWarnings("unchecked")
            ResourceKey<Level> dimension = (ResourceKey<Level>) this.colonyGetDimensionMethod.invoke(colony);
            return dimension == null ? "" : dimension.location().toString();
        }

        private static String safeString(Object value) {
            return value == null ? "" : value.toString();
        }
    }

    private MineColoniesIntegration() {
    }
}
