package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.client.ConstructionGhostClientHooks;
import com.monpai.sailboatmod.construction.BuilderHammerChargePlan;
import com.monpai.sailboatmod.construction.BuilderHammerCreditState;
import com.monpai.sailboatmod.construction.ConstructionStepExecutor;
import com.monpai.sailboatmod.construction.ConstructionStepSatisfactionService;
import com.monpai.sailboatmod.construction.RoadBridgePlanner;
import com.monpai.sailboatmod.construction.RoadCorridorPlan;
import com.monpai.sailboatmod.construction.RoadCorridorPlanner;
import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadLegacyJobRebuilder;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import com.monpai.sailboatmod.construction.RoadTerrainShaper;
import com.monpai.sailboatmod.construction.RuntimeRoadGhostWindow;
import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SyncConstructionGhostPreviewPacket;
import com.monpai.sailboatmod.network.packet.SyncConstructionProgressPacket;
import com.monpai.sailboatmod.network.packet.SyncRoadConstructionProgressPacket;
import com.monpai.sailboatmod.nation.data.ConstructionRuntimeSavedData;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.registry.ModBlocks;
import com.monpai.sailboatmod.registry.ModItems;
import com.monpai.sailboatmod.resident.service.ConstructionScaffoldingService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.network.NetworkDirection;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class StructureConstructionManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BRIDGE_HEAD_EXTENSION_MIN = 1;
    private static final int BRIDGE_HEAD_EXTENSION_MAX = 3;
    private static final int MIN_WATER_CLEARANCE_ABOVE_SURFACE = 5;

    public enum StructureType {
        VICTORIAN_BANK("victorian_bank", "item.sailboatmod.structure.victorian_bank", 19, 10, 18),
        VICTORIAN_TOWN_HALL("victorian_town_hall", "item.sailboatmod.structure.victorian_town_hall", 21, 12, 21),
        NATION_CAPITOL("nation_capitol", "item.sailboatmod.structure.nation_capitol", 25, 14, 25),
        OPEN_AIR_MARKETPLACE("open_air_marketplace", "item.sailboatmod.structure.open_air_marketplace", 17, 9, 15),
        WATERFRONT_DOCK("waterfront_dock", "item.sailboatmod.structure.waterfront_dock", 16, 8, 12),
        COTTAGE("cottage", "item.sailboatmod.structure.cottage", 9, 7, 9),
        TAVERN("tavern", "item.sailboatmod.structure.tavern", 13, 9, 11),
        SCHOOL("school", "item.sailboatmod.structure.school", 15, 10, 13);

        private final String nbtName;
        private final String translationKey;
        private final int w, h, d;

        StructureType(String nbtName, String translationKey, int w, int h, int d) {
            this.nbtName = nbtName;
            this.translationKey = translationKey;
            this.w = w;
            this.h = h;
            this.d = d;
        }

        public String nbtName() { return nbtName; }
        public String translationKey() { return translationKey; }
        public int w() { return w; }
        public int h() { return h; }
        public int d() { return d; }

        public static final List<StructureType> ALL = List.of(values());
    }

    private record ConstructionJob(ServerLevel level, UUID ownerUuid, StructureType type,
                                   String projectId, String townId, String nationId, StructureConstructionSite site) {}

    public record AssistPlacementResult(boolean success, boolean completed, Component message) {}
    public record WorkerSiteAssignment(String jobId, BlockPos anchorPos, BlockPos approachPos, BlockPos focusPos,
                                       int progressPercent, int activeWorkers) {}
    public enum PreviewRoadTargetKind {
        NONE,
        ROAD,
        STRUCTURE
    }
    public record PreviewRoadConnection(List<BlockPos> path, PreviewRoadTargetKind targetKind, BlockPos targetPos) {
        public PreviewRoadConnection {
            path = path == null ? List.of() : List.copyOf(path);
        }
    }
    public record PreviewRoadHint(List<PreviewRoadConnection> connections) {
        public PreviewRoadHint {
            connections = connections == null ? List.of() : List.copyOf(connections);
        }

        public boolean hasPath() {
            return !connections.isEmpty();
        }

        public List<BlockPos> path() {
            return connections.isEmpty() ? List.of() : connections.get(0).path();
        }

        public PreviewRoadTargetKind targetKind() {
            return connections.isEmpty() ? PreviewRoadTargetKind.NONE : connections.get(0).targetKind();
        }

        public BlockPos targetPos() {
            return connections.isEmpty() ? null : connections.get(0).targetPos();
        }

        public int connectionCount() {
            return connections.size();
        }
    }

    private record RoadConstructionJob(ServerLevel level,
                                       String roadId,
                                       UUID ownerUuid,
                                       String townId,
                                       String nationId,
                                       String sourceTownName,
                                       String targetTownName,
                                       RoadPlacementPlan plan,
                                       List<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> rollbackStates,
                                       int placedStepCount,
                                       double progressSteps,
                                       boolean rollbackActive,
                                       int rollbackActionIndex,
                                       boolean removeRoadNetworkOnComplete,
                                       Set<Long> attemptedStepKeys) {
        private RoadConstructionJob {
            attemptedStepKeys = attemptedStepKeys == null ? Set.of() : Set.copyOf(attemptedStepKeys);
        }
    }
    private record ActiveWorker(BlockPos position, long lastSeenTick, boolean specialist) {}
    private record RoadCandidate(com.monpai.sailboatmod.nation.model.PlacedStructureRecord left,
                                 com.monpai.sailboatmod.nation.model.PlacedStructureRecord right,
                                 double distanceSqr) {}
    private record RoadPlan(RoadNetworkRecord road) {}
    private record RoadAnchor(BlockPos pos, Direction side) {}
    private record PreviewRoadTarget(BlockPos pos, PreviewRoadTargetKind kind) {}
    private record RoadPlacementStyle(BlockState surface,
                                      BlockState support,
                                      BlockState lightSupport,
                                      BlockState lightArm,
                                      boolean bridge) {
        private RoadPlacementStyle(BlockState surface, BlockState support, boolean bridge) {
            this(surface, support, support, Blocks.OAK_FENCE.defaultBlockState(), bridge);
        }
    }
    static record TestRoadPlacementResult(BlockState surfaceState, int foundationTopY) {}
    private record HammerUseResult(boolean success, Component message) {
        private static HammerUseResult failure(String key) {
            return new HammerUseResult(false, Component.translatable(key));
        }
    }
    private record RoadPlacementArtifacts(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                          List<RoadGeometryPlanner.RoadBuildStep> buildSteps,
                                          List<BlockPos> ownedBlocks) {}
    private record HammerChargeResult(boolean success, long walletSpent, long treasurySpent, Component message) {}
    private record RestoredRoadRuntime(RoadPlacementPlan plan,
                                       int placedStepCount,
                                       double progressSteps,
                                       Set<Long> attemptedStepKeys,
                                       String failureReason) {
        private boolean success() {
            return plan != null && failureReason != null && failureReason.isBlank();
        }

        private static RestoredRoadRuntime failure(String reason) {
            return new RestoredRoadRuntime(null, 0, 0.0D, Set.of(), reason == null ? "" : reason);
        }
    }
    private record DisjointSet(Map<String, String> parent) {
        DisjointSet() {
            this(new HashMap<>());
        }
    }

    private static final Map<String, ConstructionJob> ACTIVE_CONSTRUCTIONS = new ConcurrentHashMap<>();
    private static final Map<String, RoadConstructionJob> ACTIVE_ROAD_CONSTRUCTIONS = new ConcurrentHashMap<>();
    private static final Map<String, List<BlockPos>> ACTIVE_ASSIST_SITES = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, ActiveWorker>> ACTIVE_SITE_WORKERS = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, ActiveWorker>> ACTIVE_ROAD_WORKERS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> ACTIVE_BUILDING_HAMMER_CREDITS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> ACTIVE_ROAD_HAMMER_CREDITS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PLAYER_HAMMER_COOLDOWNS = new ConcurrentHashMap<>();
    private static final Set<String> RESTORED_DIMENSIONS = ConcurrentHashMap.newKeySet();
    private static final int BUILD_DURATION_TICKS = 600; // ~30 seconds for better visibility
    private static final int ROAD_BUILD_DURATION_TICKS = 2400; // ~120 seconds baseline for roads
    private static final int ROAD_ROLLBACK_DURATION_TICKS = 600; // ~30 seconds baseline for teardown/rollback
    private static final long ACTIVE_WORKER_TIMEOUT_TICKS = 40L;
    private static final double ROAD_CONNECT_RANGE_SQR = 128.0D * 128.0D;
    private static final double ROAD_EXTRA_EDGE_RANGE_SQR = 56.0D * 56.0D;
    private static final int PREVIEW_ROAD_SEARCH_RADIUS = 48;
    private static final int PREVIEW_ROAD_CANDIDATE_LIMIT = 18;
    private static final int PREVIEW_ROAD_CONNECTION_LIMIT = 3;
    private static final int BUILDER_HAMMER_MAX_CREDITS = 5;
    private static final long BUILDER_HAMMER_COOLDOWN_TICKS = 5L;
    private static final long BUILDER_HAMMER_BUILDING_COST = 48L;
    private static final long BUILDER_HAMMER_ROAD_COST = 16L;
    private static final double GHOST_PREVIEW_RADIUS_SQR = 72.0D * 72.0D;
    private static final int ROAD_GHOST_WINDOW_RADIUS = 20;
    private static final double BUILDER_HAMMER_REACH_SQR = 64.0D;
    private static final int SEGMENT_SUBDIVIDE_MANHATTAN = 96;
    private static final int MAX_SEGMENT_INTERMEDIATE_ANCHORS = 24;
    private static final double NETWORK_ANCHOR_CORRIDOR_DISTANCE = 32.0D;
    private static final double BRIDGE_ANCHOR_CORRIDOR_DISTANCE = 20.0D;
    private static final int ROAD_FOUNDATION_DEPTH = 8;
    private static final int WATER_BRIDGE_PIER_DEPTH = 128;
    private static final int ROAD_FOUNDATION_CAPTURE_DEPTH = 128;

    private StructureConstructionManager() {}

    public static boolean placeStructureAnimated(ServerLevel level, BlockPos origin, ServerPlayer player, StructureType type, int rotation) {
        ensureRuntimeRestored(level);
        BlueprintService.BlueprintPlacement placement = BlueprintService.preparePlacement(level, type.nbtName(), origin, rotation);
        if (placement == null) {
            return false;
        }

        if (!isAreaClear(level, placement.blocks())) {
            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.structure.blocked"));
            return false;
        }

        clearAssistSite(level, origin, type, rotation);

        String projectId = createProjectId(origin, type, rotation);
        ConstructionCostService.PaymentResult payment = ConstructionCostService.chargeForBlueprint(level, player, placement, projectId);
        if (!payment.success()) {
            player.sendSystemMessage(payment.message());
            return false;
        }
        if (!payment.message().getString().isBlank()) {
            player.sendSystemMessage(payment.message());
        }
        prepareConstructionSite(level, placement.bounds());

        List<BlockPos> scaffoldPositions = ConstructionScaffoldingService.placeScaffolding(level, placement.bounds());
        StructureConstructionSite site = StructureConstructionSite.create(level, origin, placement, scaffoldPositions, true);

        String jobId = origin.toShortString() + "_" + System.currentTimeMillis();
        NationSavedData data = NationSavedData.get(level);
        NationMemberRecord member = data.getMember(player.getUUID());
        TownRecord constructionTown = TownService.getTownAt(level, origin);
        if (constructionTown == null && member != null) {
            constructionTown = TownService.getTownForMember(data, member);
        }
        ConstructionJob job = new ConstructionJob(
                level,
                player.getUUID(),
                type,
                projectId,
                constructionTown == null ? "" : constructionTown.townId(),
                member == null ? constructionTown == null ? "" : constructionTown.nationId() : member.nationId(),
                site
        );
        ACTIVE_CONSTRUCTIONS.put(jobId, job);
        persistConstructionJob(jobId, job);

        player.sendSystemMessage(Component.translatable(
                "command.sailboatmod.nation.structure.started",
                Component.translatable(type.translationKey())
        ));
        return true;
    }

    public static AssistPlacementResult assistPlaceNextBlock(ServerLevel level, BlockPos origin, ServerPlayer player, StructureType type, int rotation) {
        ensureRuntimeRestored(level);
        BlueprintService.BlueprintPlacement placement = BlueprintService.preparePlacement(level, type.nbtName(), origin, rotation);
        if (placement == null) {
            return new AssistPlacementResult(false, false,
                    Component.translatable("command.sailboatmod.nation.bank_constructor.failed"));
        }

        ensureAssistSite(level, origin, type, rotation, placement.bounds());

        StructureTemplate.StructureBlockInfo nextBlock = null;
        boolean hasBlockedMismatch = false;
        double bestDistance = Double.MAX_VALUE;
        int currentLayerY = findCurrentPendingLayerY(level, placement.blocks());

        for (StructureTemplate.StructureBlockInfo info : placement.blocks()) {
            if (currentLayerY >= 0 && info.pos().getY() != currentLayerY) {
                continue;
            }
            BlockState currentState = level.getBlockState(info.pos());
            if (currentState.equals(info.state())) {
                continue;
            }
            if (!currentState.isAir() && !currentState.canBeReplaced() && !currentState.liquid()) {
                hasBlockedMismatch = true;
                continue;
            }

            double distance = info.pos().distToCenterSqr(player.getX(), player.getEyeY(), player.getZ());
            if (distance < bestDistance) {
                bestDistance = distance;
                nextBlock = info;
            }
        }

        if (nextBlock == null) {
            if (isPlacementComplete(level, placement.blocks())) {
                completeStructure(level, player.getUUID(), type, placement.bounds(), placement.rotation(),
                        clearAssistSite(level, origin, type, rotation));
                return new AssistPlacementResult(true, true,
                        Component.translatable("command.sailboatmod.nation.structure.placed", Component.translatable(type.translationKey())));
            }

            Component message = hasBlockedMismatch
                    ? Component.translatable("command.sailboatmod.nation.structure.blocked")
                    : Component.literal("No placeable missing blueprint blocks remain on the current layer.");
            return new AssistPlacementResult(false, false, message);
        }

        ConstructionCostService.PaymentResult payment = new ConstructionCostService.PaymentResult(true, 0L, 0L, 0L, Component.empty());
        if (!player.getAbilities().instabuild && !consumeConstructionItem(player, nextBlock.state().getBlock().asItem())) {
            ItemStack purchaseStack = toConstructionItem(nextBlock.state());
            payment = ConstructionCostService.chargeForSingleItem(level, player, purchaseStack,
                    "assist:" + assistSiteKey(level, origin, type, rotation));
            if (!payment.success()) {
                return new AssistPlacementResult(false, false, payment.message());
            }
        }

        level.setBlock(nextBlock.pos(), nextBlock.state(), Block.UPDATE_ALL);

        boolean completed = isPlacementComplete(level, placement.blocks());
        if (completed) {
            completeStructure(level, player.getUUID(), type, placement.bounds(), placement.rotation(),
                    clearAssistSite(level, origin, type, rotation));
        }

        if (!payment.message().getString().isBlank()) {
            player.sendSystemMessage(payment.message());
        }

        Component resultMessage = Component.translatable(
                completed ? "command.sailboatmod.nation.structure.placed" : "command.sailboatmod.nation.structure.placed",
                nextBlock.state().getBlock().getName());
        if (!completed) {
            resultMessage = Component.literal("Placed " + nextBlock.state().getBlock().getName().getString() + " into the blueprint.");
        }
        return new AssistPlacementResult(true, completed, resultMessage);
    }

    public static void tick(ServerLevel level) {
        ensureRuntimeRestored(level);
        if (!ACTIVE_CONSTRUCTIONS.isEmpty()) {
            tickConstructions(level);
        }
        if (!ACTIVE_ROAD_CONSTRUCTIONS.isEmpty()) {
            tickRoadConstructions(level);
        }
        syncRuntimeGhostPreviews(level);
    }

    private static void tickConstructions(ServerLevel level) {
        List<String> completed = new ArrayList<>();
        Set<ServerPlayer> playersToSync = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<String, ConstructionJob> entry : ACTIVE_CONSTRUCTIONS.entrySet()) {
            ConstructionJob job = entry.getValue();
            if (job.level != level) continue;
            ServerPlayer owner = ownerPlayer(level, job.ownerUuid);
            if (owner != null) {
                playersToSync.add(owner);
            }

            int activeWorkers = getActiveWorkerCount(level, entry.getKey());
            consumeBuildingHammerCredit(entry.getKey(), job.site);
            job.site.tick(activeWorkers, false);
            if (job.site.consumeProgressUpdate()) {
                if (owner != null) {
                    owner.displayClientMessage(Component.translatable(
                            "message.sailboatmod.constructor.progress",
                            Component.translatable(job.type.translationKey()),
                            job.site.progressPercent()
                    ), true);
                }
            }

            if (job.site.isComplete()) {
                completeStructure(level, job.ownerUuid, job.type, job.site.bounds(), job.site.rotation(), job.site.scaffoldPositions());
                if (owner != null) {
                    owner.sendSystemMessage(Component.translatable("command.sailboatmod.nation.structure.placed", Component.translatable(job.type.translationKey())));
                }
                completed.add(entry.getKey());
            }
        }

        completed.forEach(jobId -> {
            ACTIVE_CONSTRUCTIONS.remove(jobId);
            ACTIVE_SITE_WORKERS.remove(jobId);
            ACTIVE_BUILDING_HAMMER_CREDITS.remove(jobId);
            removePersistedConstructionJob(level, jobId);
        });
        syncConstructionProgress(level, playersToSync);
    }

    public static WorkerSiteAssignment findNearestSite(ServerLevel level, BlockPos workerPos, int maxDistance) {
        ensureRuntimeRestored(level);
        WorkerSiteAssignment best = null;
        double bestScore = Double.MAX_VALUE;
        double maxDistanceSqr = maxDistance <= 0 ? Double.MAX_VALUE : (double) maxDistance * (double) maxDistance;

        for (Map.Entry<String, ConstructionJob> entry : ACTIVE_CONSTRUCTIONS.entrySet()) {
            ConstructionJob job = entry.getValue();
            if (job.level != level || job.site.isComplete()) {
                continue;
            }

            BlockPos focusPos = job.site.focusPos();
            BlockPos approachPos = job.site.approachPos(workerPos);
            double distance = workerPos.distSqr(approachPos);
            if (distance > maxDistanceSqr) {
                continue;
            }

            int activeWorkers = getActiveWorkerCount(level, entry.getKey());
            double score = distance + (activeWorkers * 64.0D);
            if (score < bestScore) {
                bestScore = score;
                best = new WorkerSiteAssignment(
                        entry.getKey(),
                        job.site.anchorPos(),
                        approachPos,
                        focusPos,
                        job.site.progressPercent(),
                        activeWorkers
                );
            }
        }

        for (Map.Entry<String, RoadConstructionJob> entry : ACTIVE_ROAD_CONSTRUCTIONS.entrySet()) {
            RoadConstructionJob job = entry.getValue();
            if (job.level != level || job.plan.centerPath().isEmpty()) {
                continue;
            }
            BlockPos focusPos = getRoadFocusPos(level, job);
            BlockPos approachPos = getRoadApproachPos(level, focusPos);
            double distance = workerPos.distSqr(approachPos);
            if (distance > maxDistanceSqr) {
                continue;
            }

            int activeWorkers = getActiveRoadWorkerCount(level, entry.getKey());
            int progressPercent = roadProgressPercent(level, job);
            double score = distance + (activeWorkers * 48.0D) + 12.0D;
            if (score < bestScore) {
                bestScore = score;
                best = new WorkerSiteAssignment(
                        entry.getKey(),
                        focusPos,
                        approachPos,
                        focusPos.above(),
                        progressPercent,
                        activeWorkers
                );
            }
        }

        return best;
    }

    public static WorkerSiteAssignment getSiteAssignment(ServerLevel level, String jobId, BlockPos workerPos) {
        ensureRuntimeRestored(level);
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        ConstructionJob job = ACTIVE_CONSTRUCTIONS.get(jobId);
        if (job != null && job.level == level && !job.site.isComplete()) {
            BlockPos approachPos = job.site.approachPos(workerPos);
            return new WorkerSiteAssignment(
                    jobId,
                    job.site.anchorPos(),
                    approachPos,
                    job.site.focusPos(),
                    job.site.progressPercent(),
                    getActiveWorkerCount(level, jobId)
            );
        }

        RoadConstructionJob roadJob = ACTIVE_ROAD_CONSTRUCTIONS.get(jobId);
        if (roadJob == null || roadJob.level != level || roadJob.plan.centerPath().isEmpty()) {
            return null;
        }
        BlockPos focusPos = getRoadFocusPos(level, roadJob);
        BlockPos approachPos = getRoadApproachPos(level, focusPos);
        return new WorkerSiteAssignment(
                jobId,
                focusPos,
                approachPos,
                focusPos.above(),
                roadProgressPercent(level, roadJob),
                getActiveRoadWorkerCount(level, jobId)
        );
    }

    public static void reportWorkerActivity(ServerLevel level, String jobId, String workerId, BlockPos position, boolean specialist) {
        ensureRuntimeRestored(level);
        if (workerId == null || workerId.isBlank() || position == null) {
            return;
        }
        ConstructionJob job = ACTIVE_CONSTRUCTIONS.get(jobId);
        if (job != null && job.level == level && !job.site.isComplete()) {
            ACTIVE_SITE_WORKERS
                    .computeIfAbsent(jobId, ignored -> new ConcurrentHashMap<>())
                    .put(workerId, new ActiveWorker(position.immutable(), level.getGameTime(), specialist));
            return;
        }
        RoadConstructionJob roadJob = ACTIVE_ROAD_CONSTRUCTIONS.get(jobId);
        if (roadJob == null || roadJob.level != level || roadJob.plan.centerPath().isEmpty()) {
            return;
        }
        ACTIVE_ROAD_WORKERS
                .computeIfAbsent(jobId, ignored -> new ConcurrentHashMap<>())
                .put(workerId, new ActiveWorker(position.immutable(), level.getGameTime(), specialist));
    }

    public static void releaseWorker(String jobId, String workerId) {
        if (jobId == null || jobId.isBlank() || workerId == null || workerId.isBlank()) {
            return;
        }
        Map<String, ActiveWorker> workers = ACTIVE_SITE_WORKERS.get(jobId);
        if (workers != null) {
            workers.remove(workerId);
            if (workers.isEmpty()) {
                ACTIVE_SITE_WORKERS.remove(jobId);
            }
        }

        Map<String, ActiveWorker> roadWorkers = ACTIVE_ROAD_WORKERS.get(jobId);
        if (roadWorkers != null) {
            roadWorkers.remove(workerId);
            if (roadWorkers.isEmpty()) {
                ACTIVE_ROAD_WORKERS.remove(jobId);
            }
        }
    }

    public static boolean hasActiveConstruction(ServerLevel level, String jobId) {
        ensureRuntimeRestored(level);
        ConstructionJob job = ACTIVE_CONSTRUCTIONS.get(jobId);
        if (job != null && job.level == level && !job.site.isComplete()) {
            return true;
        }
        RoadConstructionJob roadJob = ACTIVE_ROAD_CONSTRUCTIONS.get(jobId);
        return roadJob != null && roadJob.level == level && !roadJob.plan.centerPath().isEmpty();
    }

    public static void tickRoadConstructions(ServerLevel level) {
        List<String> completedBuilds = new ArrayList<>();
        List<String> completedRollbacks = new ArrayList<>();
        Set<ServerPlayer> playersToSync = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Map.Entry<String, RoadConstructionJob> entry : ACTIVE_ROAD_CONSTRUCTIONS.entrySet()) {
            RoadConstructionJob persistedJob = entry.getValue();
            RoadConstructionJob job = refreshRoadConstructionState(level, entry.getValue());
            if (job.level != level) continue;

            ServerPlayer owner = ownerPlayer(level, job.ownerUuid);
            if (owner != null) {
                playersToSync.add(owner);
            }

            int activeWorkers = getActiveRoadWorkerCount(level, entry.getKey());
            job = consumeRoadHammerCredit(level, entry.getKey(), job);
            int totalUnits = job.rollbackActive
                    ? roadRollbackActionOrder(level, job.plan, job.rollbackStates).size()
                    : job.plan.buildSteps().size();
            if (totalUnits <= 0) {
                if (job.rollbackActive) {
                    completedRollbacks.add(entry.getKey());
                } else {
                    completedBuilds.add(entry.getKey());
                }
                continue;
            }

            double speedMultiplier = activeWorkers > 0 ? (activeWorkers + 2.0D) / 3.0D : 1.0D;
            double progressPerTick = job.rollbackActive
                    ? roadRollbackProgressPerTick(totalUnits, speedMultiplier)
                    : roadBuildProgressPerTick(totalUnits, speedMultiplier);
            double currentProgress = job.rollbackActive
                    ? Math.max(job.progressSteps, job.rollbackActionIndex)
                    : Math.max(Math.max(job.progressSteps, job.placedStepCount), Math.max(persistedJob.progressSteps, persistedJob.placedStepCount));
            double targetProgress = currentProgress + progressPerTick;

            if (!job.rollbackActive && targetProgress < 1.0D && job.placedStepCount < totalUnits) {
                targetProgress = 1.0D;
            }

            if (job.rollbackActive) {
                int startIndex = Math.max(0, Math.min(totalUnits, job.rollbackActionIndex));
                int targetActionIndex = Math.min(totalUnits, Math.max(startIndex, (int) targetProgress));
                RoadConstructionJob rolledBackJob = rollbackRoadBuildSteps(level, job, targetActionIndex - startIndex);
                if (rolledBackJob.rollbackActionIndex >= totalUnits) {
                    completedRollbacks.add(entry.getKey());
                } else {
                    RoadConstructionJob updatedJob = new RoadConstructionJob(
                            rolledBackJob.level,
                            rolledBackJob.roadId,
                            rolledBackJob.ownerUuid,
                            rolledBackJob.townId,
                            rolledBackJob.nationId,
                            rolledBackJob.sourceTownName,
                            rolledBackJob.targetTownName,
                            rolledBackJob.plan,
                            rolledBackJob.rollbackStates,
                            rolledBackJob.placedStepCount,
                            Math.max(targetProgress, rolledBackJob.rollbackActionIndex),
                            true,
                            rolledBackJob.rollbackActionIndex,
                            rolledBackJob.removeRoadNetworkOnComplete,
                            rolledBackJob.attemptedStepKeys
                    );
                    ACTIVE_ROAD_CONSTRUCTIONS.put(entry.getKey(), updatedJob);
                    persistRoadConstruction(level, updatedJob.roadId, updatedJob.ownerUuid, updatedJob.plan, updatedJob.rollbackStates, updatedJob.placedStepCount, updatedJob.progressSteps, true, updatedJob.rollbackActionIndex, updatedJob.removeRoadNetworkOnComplete, updatedJob.attemptedStepKeys);
                }
            } else {
                int consumedStepCount = job.placedStepCount;
                int targetPlacedStepCount = Math.min(totalUnits, Math.max(consumedStepCount, (int) targetProgress));
                RoadConstructionJob advancedJob = placeRoadBuildSteps(level, job, targetPlacedStepCount - consumedStepCount);
                int newPlacedStepCount = advancedJob.placedStepCount;

                if (newPlacedStepCount >= totalUnits) {
                    persistRoadConstruction(level, advancedJob.roadId, advancedJob.ownerUuid, advancedJob.plan, advancedJob.rollbackStates, totalUnits, totalUnits, false, 0, false, advancedJob.attemptedStepKeys);
                    if (owner != null) {
                        owner.sendSystemMessage(Component.translatable(
                                "message.sailboatmod.road_planner.completed",
                                advancedJob.sourceTownName,
                                advancedJob.targetTownName
                        ));
                    }
                    completedBuilds.add(entry.getKey());
                } else {
                    RoadConstructionJob updatedJob = new RoadConstructionJob(
                            advancedJob.level,
                            advancedJob.roadId,
                            advancedJob.ownerUuid,
                            advancedJob.townId,
                            advancedJob.nationId,
                            advancedJob.sourceTownName,
                            advancedJob.targetTownName,
                            advancedJob.plan,
                            advancedJob.rollbackStates,
                            newPlacedStepCount,
                            Math.max(targetProgress, newPlacedStepCount),
                            false,
                            0,
                            false,
                            advancedJob.attemptedStepKeys
                    );
                    ACTIVE_ROAD_CONSTRUCTIONS.put(entry.getKey(), updatedJob);
                    persistRoadConstruction(level, updatedJob.roadId, updatedJob.ownerUuid, updatedJob.plan, updatedJob.rollbackStates, updatedJob.placedStepCount, updatedJob.progressSteps, false, 0, false, updatedJob.attemptedStepKeys);
                }
            }
        }

        completedBuilds.forEach(jobId -> {
            clearActiveRoadRuntimeState(jobId);
        });
        completedRollbacks.forEach(jobId -> {
            RoadConstructionJob completedJob = ACTIVE_ROAD_CONSTRUCTIONS.get(jobId);
            clearActiveRoadRuntimeState(jobId);
            removePersistedRoadJob(level, jobId);
            if (completedJob != null && completedJob.removeRoadNetworkOnComplete) {
                NationSavedData.get(level).removeRoadNetwork(jobId);
            }
        });
        syncRoadConstructionProgress(level, playersToSync);
    }

    public static void handleBuilderHammerUse(ServerPlayer player,
                                              ConstructionGhostClientHooks.TargetKind kind,
                                              String jobId,
                                              BlockPos hitPos) {
        if (player == null || kind == null || jobId == null || jobId.isBlank() || hitPos == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        ensureRuntimeRestored(level);
        if (!isHoldingBuilderHammer(player)) {
            return;
        }
        if (player.distanceToSqr(hitPos.getX() + 0.5D, hitPos.getY() + 0.5D, hitPos.getZ() + 0.5D) > BUILDER_HAMMER_REACH_SQR) {
            player.sendSystemMessage(Component.translatable("message.sailboatmod.builder_hammer.too_far"));
            return;
        }
        Long nextAllowedTick = PLAYER_HAMMER_COOLDOWNS.get(player.getUUID());
        long now = level.getGameTime();
        if (nextAllowedTick != null && now < nextAllowedTick) {
            return;
        }

        HammerUseResult result = switch (kind) {
            case BUILDING -> queueBuildingHammer(level, player, jobId, hitPos);
            case ROAD -> queueRoadHammer(level, player, jobId, hitPos);
        };
        if (!result.message().getString().isBlank()) {
            player.sendSystemMessage(result.message());
        }
        if (result.success()) {
            PLAYER_HAMMER_COOLDOWNS.put(player.getUUID(), now + BUILDER_HAMMER_COOLDOWN_TICKS);
            damageBuilderHammer(player);
            syncRuntimeGhostPreviews(level);
        }
    }

    private static void consumeBuildingHammerCredit(String jobId, StructureConstructionSite site) {
        Integer queuedCredits = ACTIVE_BUILDING_HAMMER_CREDITS.get(jobId);
        if (queuedCredits == null || queuedCredits <= 0 || site == null || site.isComplete()) {
            return;
        }
        if (site.advanceOneStep()) {
            if (queuedCredits <= 1) {
                ACTIVE_BUILDING_HAMMER_CREDITS.remove(jobId);
            } else {
                ACTIVE_BUILDING_HAMMER_CREDITS.put(jobId, queuedCredits - 1);
            }
        }
    }

    private static RoadConstructionJob consumeRoadHammerCredit(ServerLevel level, String jobId, RoadConstructionJob job) {
        Integer queuedCredits = ACTIVE_ROAD_HAMMER_CREDITS.get(jobId);
        if (queuedCredits == null || queuedCredits <= 0 || job == null || job.rollbackActive) {
            return job;
        }
        job = refreshRoadConstructionState(level, job);
        Set<Long> consumedStepKeys = consumedRoadBuildStepKeys(level, job);
        int batchSize = nextRoadBuildBatchSize(job.plan, consumedStepKeys);
        if (batchSize <= 0) {
            return job;
        }
        RoadConstructionJob updatedJob = placeRoadBuildSteps(level, job, batchSize);
        if (queuedCredits <= 1) {
            ACTIVE_ROAD_HAMMER_CREDITS.remove(jobId);
        } else {
            ACTIVE_ROAD_HAMMER_CREDITS.put(jobId, queuedCredits - 1);
        }
        return new RoadConstructionJob(
                updatedJob.level,
                updatedJob.roadId,
                updatedJob.ownerUuid,
                updatedJob.townId,
                updatedJob.nationId,
                updatedJob.sourceTownName,
                updatedJob.targetTownName,
                updatedJob.plan,
                updatedJob.rollbackStates,
                updatedJob.placedStepCount,
                Math.max(updatedJob.progressSteps, updatedJob.placedStepCount),
                false,
                0,
                false,
                updatedJob.attemptedStepKeys
        );
    }

    private static HammerUseResult queueBuildingHammer(ServerLevel level, ServerPlayer player, String jobId, BlockPos hitPos) {
        ConstructionJob job = ACTIVE_CONSTRUCTIONS.get(jobId);
        if (job == null || job.level != level || job.site.isComplete()) {
            return HammerUseResult.failure("message.sailboatmod.builder_hammer.invalid_target");
        }
        if (!canManageConstruction(level, player, job.townId, job.nationId, job.ownerUuid)) {
            return HammerUseResult.failure("message.sailboatmod.builder_hammer.no_permission");
        }
        boolean hitsPreview = job.site.remainingBlocks().stream().anyMatch(block -> hitPos.equals(block.relativePos()));
        if (!hitsPreview) {
            return HammerUseResult.failure("message.sailboatmod.builder_hammer.invalid_target");
        }

        int queued = ACTIVE_BUILDING_HAMMER_CREDITS.getOrDefault(jobId, 0);
        BuilderHammerCreditState queueState = BuilderHammerCreditState.of(queued, BUILDER_HAMMER_MAX_CREDITS).enqueue();
        if (!queueState.accepted()) {
            return HammerUseResult.failure("message.sailboatmod.builder_hammer.queue_full");
        }

        HammerChargeResult charge = chargeBuilderHammer(level, player, job.nationId, BUILDER_HAMMER_BUILDING_COST);
        if (!charge.success()) {
            return new HammerUseResult(false, charge.message());
        }
        ACTIVE_BUILDING_HAMMER_CREDITS.put(jobId, queueState.queuedCredits());
        return new HammerUseResult(true, Component.translatable(
                "message.sailboatmod.builder_hammer.queued_building",
                GoldStandardEconomy.formatBalance(charge.walletSpent()),
                GoldStandardEconomy.formatBalance(charge.treasurySpent())
        ));
    }

    private static HammerUseResult queueRoadHammer(ServerLevel level, ServerPlayer player, String jobId, BlockPos hitPos) {
        RoadConstructionJob job = ACTIVE_ROAD_CONSTRUCTIONS.get(jobId);
        if (job == null || job.level != level) {
            return HammerUseResult.failure("message.sailboatmod.builder_hammer.invalid_target");
        }
        if (!canManageRoad(level, player, job)) {
            return HammerUseResult.failure("message.sailboatmod.builder_hammer.no_permission");
        }
        if (!remainingRoadGhostPositions(level, job).contains(hitPos)) {
            return HammerUseResult.failure("message.sailboatmod.builder_hammer.invalid_target");
        }

        int queued = ACTIVE_ROAD_HAMMER_CREDITS.getOrDefault(jobId, 0);
        BuilderHammerCreditState queueState = BuilderHammerCreditState.of(queued, BUILDER_HAMMER_MAX_CREDITS).enqueue();
        if (!queueState.accepted()) {
            return HammerUseResult.failure("message.sailboatmod.builder_hammer.queue_full");
        }

        HammerChargeResult charge = chargeBuilderHammer(level, player, job.nationId, BUILDER_HAMMER_ROAD_COST);
        if (!charge.success()) {
            return new HammerUseResult(false, charge.message());
        }
        ACTIVE_ROAD_HAMMER_CREDITS.put(jobId, queueState.queuedCredits());
        return new HammerUseResult(true, Component.translatable(
                "message.sailboatmod.builder_hammer.queued_road",
                GoldStandardEconomy.formatBalance(charge.walletSpent()),
                GoldStandardEconomy.formatBalance(charge.treasurySpent())
        ));
    }

    private static BlockPos getRoadFocusPos(ServerLevel level, RoadConstructionJob job) {
        if (job == null || job.plan.centerPath().isEmpty()) {
            return BlockPos.ZERO;
        }
        int index = roadBuildBatchIndex(job.plan, consumedRoadBuildStepKeys(level, job));
        index = Math.max(0, Math.min(index, job.plan.centerPath().size() - 1));
        return job.plan.centerPath().get(index);
    }

    private static BlockPos getRoadApproachPos(ServerLevel level, BlockPos focusPos) {
        BlockPos best = focusPos;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos candidate : List.of(focusPos.north(), focusPos.south(), focusPos.east(), focusPos.west(), focusPos)) {
            if (!isRoadWorkerStandable(level, candidate)) {
                continue;
            }
            double score = candidate.distSqr(focusPos);
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isRoadWorkerStandable(ServerLevel level, BlockPos pos) {
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState below = level.getBlockState(pos.below());
        return (feet.isAir() || feet.canBeReplaced() || feet.liquid())
                && (head.isAir() || head.canBeReplaced() || head.liquid())
                && !below.isAir()
                && !below.liquid();
    }

    private static int roadProgressPercent(ServerLevel level, RoadConstructionJob job) {
        return roadProgressPercent(job == null ? null : job.plan, consumedRoadBuildStepKeys(level, job));
    }

    private static double roadBuildProgressPerTick(int totalSteps, double speedMultiplier) {
        if (totalSteps <= 0) {
            return 0.0D;
        }
        return (totalSteps / (double) ROAD_BUILD_DURATION_TICKS) * Math.max(0.0D, speedMultiplier);
    }

    private static double roadRollbackProgressPerTick(int totalActions, double speedMultiplier) {
        if (totalActions <= 0) {
            return 0.0D;
        }
        return (totalActions / (double) ROAD_ROLLBACK_DURATION_TICKS) * Math.max(0.0D, speedMultiplier);
    }

    private static int roadProgressPercent(RoadPlacementPlan plan, int placedStepCount) {
        if (plan == null || plan.buildSteps().isEmpty()) {
            return 100;
        }
        int clamped = Math.max(0, Math.min(placedStepCount, plan.buildSteps().size()));
        int percent = Math.max(0, Math.min(100, (clamped * 100) / plan.buildSteps().size()));
        if (clamped > 0 && percent == 0) {
            return 1;
        }
        return percent;
    }

    private static int roadProgressPercent(RoadPlacementPlan plan, Set<Long> completedStepKeys) {
        if (plan == null || plan.buildSteps().isEmpty()) {
            return 100;
        }
        int completed = countCompletedRoadBuildSteps(plan, completedStepKeys);
        int percent = Math.max(0, Math.min(100, (completed * 100) / plan.buildSteps().size()));
        if (completed > 0 && percent == 0) {
            return 1;
        }
        return percent;
    }

    private static int getActiveRoadWorkerCount(ServerLevel level, String jobId) {
        Map<String, ActiveWorker> workers = ACTIVE_ROAD_WORKERS.get(jobId);
        if (workers == null || workers.isEmpty()) {
            return 0;
        }

        long currentTick = level.getGameTime();
        int count = 0;
        Iterator<Map.Entry<String, ActiveWorker>> iterator = workers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ActiveWorker> entry = iterator.next();
            ActiveWorker worker = entry.getValue();
            if (worker == null || currentTick - worker.lastSeenTick > ACTIVE_WORKER_TIMEOUT_TICKS) {
                iterator.remove();
                continue;
            }
            count += worker.specialist ? 2 : 1;
        }

        if (workers.isEmpty()) {
            ACTIVE_ROAD_WORKERS.remove(jobId);
        }
        return count;
    }

    // Keep old method for backward compat
    public static boolean placeStructure(ServerLevel level, BlockPos origin, ServerPlayer player, StructureType type) {
        return placeStructureAnimated(level, origin, player, type, 0);
    }

    public static boolean demolishStructure(ServerLevel level, BlockPos pos, ServerPlayer player) {
        NationSavedData data = NationSavedData.get(level);
        com.monpai.sailboatmod.nation.model.PlacedStructureRecord target = findStructureAt(data, level, pos);
        if (target == null) return false;

        // Clear blocks in the structure area
        BlockPos origin = target.origin();
        for (int y = 0; y < target.sizeH(); y++) {
            for (int z = 0; z < target.sizeD(); z++) {
                for (int x = 0; x < target.sizeW(); x++) {
                    level.setBlock(origin.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        data.removePlacedStructure(target.structureId());
        syncRoadNetwork(level, data, target.townId(), target.nationId(), target.dimensionId());
        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.structure.demolished"));
        return true;
    }

    public static boolean relocateStructure(ServerLevel level, BlockPos oldPos, BlockPos newOrigin, ServerPlayer player) {
        NationSavedData data = NationSavedData.get(level);
        com.monpai.sailboatmod.nation.model.PlacedStructureRecord target = findStructureAt(data, level, oldPos);
        if (target == null) return false;

        StructureType type = StructureType.ALL.stream().filter(t -> t.nbtName().equals(target.structureType())).findFirst().orElse(null);
        if (type == null) return false;

        // Demolish old
        BlockPos origin = target.origin();
        for (int y = 0; y < target.sizeH(); y++) {
            for (int z = 0; z < target.sizeD(); z++) {
                for (int x = 0; x < target.sizeW(); x++) {
                    level.setBlock(origin.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        data.removePlacedStructure(target.structureId());
        syncRoadNetwork(level, data, target.townId(), target.nationId(), target.dimensionId());

        // Place at new location
        return placeStructure(level, newOrigin, player, type);
    }

    private static void ensureAssistSite(ServerLevel level, BlockPos origin, StructureType type, int rotation,
                                         BlueprintService.PlacementBounds bounds) {
        String key = assistSiteKey(level, origin, type, rotation);
        ACTIVE_ASSIST_SITES.computeIfAbsent(key, ignored -> ConstructionScaffoldingService.placeScaffolding(level, bounds));
    }

    private static List<BlockPos> clearAssistSite(ServerLevel level, BlockPos origin, StructureType type, int rotation) {
        String key = assistSiteKey(level, origin, type, rotation);
        List<BlockPos> scaffolds = ACTIVE_ASSIST_SITES.remove(key);
        if (scaffolds != null && !scaffolds.isEmpty()) {
            ConstructionScaffoldingService.removeScaffolding(level, scaffolds);
        }
        return scaffolds == null ? List.of() : scaffolds;
    }

    private static String assistSiteKey(ServerLevel level, BlockPos origin, StructureType type, int rotation) {
        return level.dimension().location() + "|" + origin.asLong() + "|" + type.nbtName() + "|" + Math.floorMod(rotation, 4);
    }

    private static void completeStructure(ServerLevel level, UUID ownerUuid, StructureType type,
                                          BlueprintService.PlacementBounds bounds, int rotation,
                                          List<BlockPos> scaffoldPositions) {
        placeCoreBlock(level, bounds, type);
        fixCoreOwnership(level, bounds, ownerUuid);
        if (scaffoldPositions != null && !scaffoldPositions.isEmpty()) {
            ConstructionScaffoldingService.removeScaffolding(level, scaffoldPositions);
        }

        NationSavedData data = NationSavedData.get(level);
        if (findStructureByOrigin(data, level, bounds.min(), type.nbtName()) != null) {
            return;
        }

        NationMemberRecord member = data.getMember(ownerUuid);
        if (member == null) {
            return;
        }

        TownRecord town = TownService.getTownForMember(data, member);
        String structureId = UUID.randomUUID().toString().substring(0, 8);
        com.monpai.sailboatmod.nation.model.PlacedStructureRecord record = new com.monpai.sailboatmod.nation.model.PlacedStructureRecord(
                structureId, member.nationId(), town == null ? "" : town.townId(),
                type.nbtName(), level.dimension().location().toString(),
                bounds.min().asLong(), bounds.width(), bounds.height(), bounds.depth(),
                System.currentTimeMillis(), 1, true, rotation);
        data.putPlacedStructure(record);
        syncRoadNetwork(level, data, record.townId(), record.nationId(), record.dimensionId());
    }

    private static boolean isPlacementComplete(ServerLevel level, List<StructureTemplate.StructureBlockInfo> blocks) {
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            if (!level.getBlockState(info.pos()).equals(info.state())) {
                return false;
            }
        }
        return true;
    }

    private static int findCurrentPendingLayerY(ServerLevel level, List<StructureTemplate.StructureBlockInfo> blocks) {
        int currentLayerY = Integer.MAX_VALUE;
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            if (!level.getBlockState(info.pos()).equals(info.state())) {
                currentLayerY = Math.min(currentLayerY, info.pos().getY());
            }
        }
        return currentLayerY == Integer.MAX_VALUE ? -1 : currentLayerY;
    }

    private static boolean consumeConstructionItem(ServerPlayer player, Item item) {
        if (item == Items.AIR) {
            return true;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                stack.shrink(1);
                player.getInventory().setChanged();
                return true;
            }
        }
        return false;
    }

    private static ItemStack toConstructionItem(BlockState state) {
        Item item = state.getBlock().asItem();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static com.monpai.sailboatmod.nation.model.PlacedStructureRecord findStructureAt(NationSavedData data, ServerLevel level, BlockPos pos) {
        String dim = level.dimension().location().toString();
        for (com.monpai.sailboatmod.nation.model.PlacedStructureRecord s : data.getPlacedStructures()) {
            if (!dim.equals(s.dimensionId())) continue;
            BlockPos o = s.origin();
            if (pos.getX() >= o.getX() && pos.getX() < o.getX() + s.sizeW()
                    && pos.getY() >= o.getY() && pos.getY() < o.getY() + s.sizeH()
                    && pos.getZ() >= o.getZ() && pos.getZ() < o.getZ() + s.sizeD()) {
                return s;
            }
        }
        return null;
    }

    private static com.monpai.sailboatmod.nation.model.PlacedStructureRecord findStructureByOrigin(
            NationSavedData data, ServerLevel level, BlockPos origin, String structureType) {
        String dim = level.dimension().location().toString();
        for (com.monpai.sailboatmod.nation.model.PlacedStructureRecord s : data.getPlacedStructures()) {
            if (!dim.equals(s.dimensionId())) continue;
            if (!origin.equals(s.origin())) continue;
            if (!structureType.equals(s.structureType())) continue;
            return s;
        }
        return null;
    }

    private static void placeCoreBlock(ServerLevel level, BlueprintService.PlacementBounds bounds, StructureType type) {
        Block block = requiredFunctionalBlock(type);
        if (block == null || containsBlock(level, bounds, block)) {
            return;
        }

        BlockPos fallbackPos = bounds.centerAtY(bounds.min().getY() + 1);
        level.setBlock(fallbackPos, block.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void fixCoreOwnership(ServerLevel level, BlueprintService.PlacementBounds bounds, UUID ownerUuid) {
        if (ownerUuid == null) {
            return;
        }
        NationSavedData data = NationSavedData.get(level);
        NationMemberRecord member = data.getMember(ownerUuid);
        if (member == null) {
            return;
        }

        for (int y = bounds.min().getY(); y <= bounds.max().getY(); y++) {
            for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++) {
                for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(ModBlocks.TOWN_CORE_BLOCK.get())) {
                        TownRecord town = TownService.getTownForMember(data, member);
                        if (town != null && !town.hasCore()) {
                            TownService.placeCoreAt(data, level, town, pos);
                        }
                    } else if (state.is(ModBlocks.NATION_CORE_BLOCK.get())) {
                        NationRecord nation = data.getNation(member.nationId());
                        TownRecord capitalTown = nation == null ? null : TownService.getCapitalTown(data, nation);
                        TownRecord occupiedTown = TownService.getTownAt(level, pos);
                        if (nation != null
                                && !nation.hasCore()
                                && capitalTown != null
                                && capitalTown.hasCore()
                                && occupiedTown != null
                                && capitalTown.townId().equals(occupiedTown.townId())) {
                            placeNationCoreForConstruction(data, level, nation, capitalTown, pos);
                        }
                    } else if (state.is(ModBlocks.MARKET_BLOCK.get()) || state.is(ModBlocks.DOCK_BLOCK.get())) {
                        // Market and dock blocks get their town association via chunk claim
                        // Ensure the chunk is claimed for the player's town
                        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(pos);
                        if (data.getClaim(level, cp) == null) {
                            TownRecord town = TownService.getTownForMember(data, member);
                            if (town != null) {
                                data.putClaim(new com.monpai.sailboatmod.nation.model.NationClaimRecord(
                                        level.dimension().location().toString(), cp.x, cp.z,
                                        town.nationId(), town.townId(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        System.currentTimeMillis()));
                            }
                        }
                    }
                }
            }
        }
    }

    private static void syncRoadNetwork(ServerLevel level, NationSavedData data, String townId, String nationId, String dimensionId) {
        Map<String, RoadNetworkRecord> existing = new LinkedHashMap<>();
        for (RoadNetworkRecord road : data.getRoadNetworks()) {
            if (road.sameScope(townId, nationId, dimensionId) && road.isAutoManaged()) {
                existing.put(road.roadId(), road);
            }
        }

        Map<String, RoadNetworkRecord> desired = planRoadNetwork(level, data, townId, nationId, dimensionId);
        List<RoadNetworkRecord> manualCoverageRoads = data.getRoadNetworks().stream()
                .filter(road -> road.sameScope(townId, nationId, dimensionId) && !road.isAutoManaged())
                .toList();
        Set<BlockPos> desiredCoverage = collectRoadCoverage(desired.values());
        desiredCoverage.addAll(collectRoadCoverage(manualCoverageRoads));

        for (RoadNetworkRecord road : existing.values()) {
            RoadNetworkRecord desiredRoad = desired.get(road.roadId());
            if (desiredRoad != null && road.path().equals(desiredRoad.path())) {
                continue;
            }
            clearRoad(level, road, desiredCoverage);
            ACTIVE_ROAD_CONSTRUCTIONS.remove(road.roadId());
            removePersistedRoadJob(level, road.roadId());
            data.removeRoadNetwork(road.roadId());
        }

        for (RoadNetworkRecord road : desired.values()) {
            RoadNetworkRecord existingRoad = existing.get(road.roadId());
            if (existingRoad != null && existingRoad.path().equals(road.path())) {
                continue;
            }
            data.putRoadNetwork(road);
            scheduleRoadConstruction(level, road, null, null, "", "");
        }
    }

    private static Map<String, RoadNetworkRecord> planRoadNetwork(ServerLevel level,
                                                                  NationSavedData data,
                                                                  String townId,
                                                                  String nationId,
                                                                  String dimensionId) {
        List<com.monpai.sailboatmod.nation.model.PlacedStructureRecord> structures = getRoadScopeStructures(data, townId, nationId, dimensionId);
        if (structures.size() < 2) {
            return Map.of();
        }

        List<RoadCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < structures.size(); i++) {
            for (int j = i + 1; j < structures.size(); j++) {
                com.monpai.sailboatmod.nation.model.PlacedStructureRecord left = structures.get(i);
                com.monpai.sailboatmod.nation.model.PlacedStructureRecord right = structures.get(j);
                double distanceSqr = left.center().distSqr(right.center());
                if (distanceSqr <= ROAD_CONNECT_RANGE_SQR) {
                    candidates.add(new RoadCandidate(left, right, distanceSqr));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(RoadCandidate::distanceSqr));

        Map<String, Integer> degrees = new HashMap<>();
        Map<String, RoadNetworkRecord> desired = new LinkedHashMap<>();
        DisjointSet disjointSet = new DisjointSet();

        for (RoadCandidate candidate : candidates) {
            String key = RoadNetworkRecord.edgeKey(candidate.left.structureId(), candidate.right.structureId());
            if (key.isBlank() || isConnected(disjointSet, candidate.left.structureId(), candidate.right.structureId())) {
                continue;
            }
            RoadPlan plan = planRoad(level, candidate.left, candidate.right, townId, nationId, dimensionId);
            if (plan == null) {
                continue;
            }
            union(disjointSet, candidate.left.structureId(), candidate.right.structureId());
            desired.put(key, plan.road());
            degrees.merge(candidate.left.structureId(), 1, Integer::sum);
            degrees.merge(candidate.right.structureId(), 1, Integer::sum);
            if (desired.size() >= structures.size() - 1) {
                break;
            }
        }

        int extrasAllowed = Math.max(1, structures.size() / 4);
        int extrasAdded = 0;
        for (RoadCandidate candidate : candidates) {
            if (extrasAdded >= extrasAllowed || candidate.distanceSqr() > ROAD_EXTRA_EDGE_RANGE_SQR) {
                break;
            }
            String key = RoadNetworkRecord.edgeKey(candidate.left.structureId(), candidate.right.structureId());
            if (key.isBlank() || desired.containsKey(key)) {
                continue;
            }
            if (degrees.getOrDefault(candidate.left.structureId(), 0) >= 3
                    || degrees.getOrDefault(candidate.right.structureId(), 0) >= 3) {
                continue;
            }
            RoadPlan plan = planRoad(level, candidate.left, candidate.right, townId, nationId, dimensionId);
            if (plan == null) {
                continue;
            }
            desired.put(key, plan.road());
            degrees.merge(candidate.left.structureId(), 1, Integer::sum);
            degrees.merge(candidate.right.structureId(), 1, Integer::sum);
            extrasAdded++;
        }

        return desired;
    }

    private static List<com.monpai.sailboatmod.nation.model.PlacedStructureRecord> getRoadScopeStructures(NationSavedData data,
                                                                                                           String townId,
                                                                                                           String nationId,
                                                                                                           String dimensionId) {
        List<com.monpai.sailboatmod.nation.model.PlacedStructureRecord> result = new ArrayList<>();
        for (com.monpai.sailboatmod.nation.model.PlacedStructureRecord structure : data.getPlacedStructures()) {
            if (!dimensionId.equals(structure.dimensionId())) {
                continue;
            }
            if (townId != null && !townId.isBlank()) {
                if (townId.equals(structure.townId())) {
                    result.add(structure);
                }
            } else if (nationId != null && !nationId.isBlank() && nationId.equalsIgnoreCase(structure.nationId())) {
                result.add(structure);
            }
        }
        return result;
    }

    private static RoadPlan planRoad(ServerLevel level,
                                     com.monpai.sailboatmod.nation.model.PlacedStructureRecord first,
                                     com.monpai.sailboatmod.nation.model.PlacedStructureRecord second,
                                     String townId,
                                     String nationId,
                                     String dimensionId) {
        String leftId = first.structureId();
        String rightId = second.structureId();
        if (leftId.compareToIgnoreCase(rightId) > 0) {
            com.monpai.sailboatmod.nation.model.PlacedStructureRecord swap = first;
            first = second;
            second = swap;
            leftId = first.structureId();
            rightId = second.structureId();
        }
        List<BlockPos> path = findBestRoadPath(level, first, second);
        if (path.size() < 2) {
            return null;
        }
        return new RoadPlan(new RoadNetworkRecord(
                RoadNetworkRecord.edgeKey(leftId, rightId),
                nationId,
                townId,
                dimensionId,
                leftId,
                rightId,
                path,
                System.currentTimeMillis(),
                RoadNetworkRecord.SOURCE_TYPE_AUTO
        ));
    }

    private static List<BlockPos> findBestRoadPath(ServerLevel level,
                                                   com.monpai.sailboatmod.nation.model.PlacedStructureRecord first,
                                                   com.monpai.sailboatmod.nation.model.PlacedStructureRecord second) {
        List<RoadAnchor> firstAnchors = getRoadAnchors(first);
        List<RoadAnchor> secondAnchors = getRoadAnchors(second);
        List<RoadNetworkRecord> roads = List.copyOf(NationSavedData.get(level).getRoadNetworks());
        Set<BlockPos> networkNodes = RoadHybridRouteResolver.collectNetworkNodes(roads);
        Map<BlockPos, Set<BlockPos>> adjacency = RoadHybridRouteResolver.collectNetworkAdjacency(roads);
        for (BlockPos sourceAnchor : firstAnchors.stream().map(RoadAnchor::pos).toList()) {
            for (BlockPos targetAnchor : secondAnchors.stream().map(RoadAnchor::pos).toList()) {
                SegmentedRoadPathOrchestrator.OrchestratedPath orchestrated = SegmentedRoadPathOrchestrator.plan(
                        sourceAnchor,
                        targetAnchor,
                        collectSegmentAnchors(level, sourceAnchor, targetAnchor, networkNodes),
                        request -> new SegmentedRoadPathOrchestrator.SegmentPlan(
                                resolveHybridRoadSegment(level, request.from(), request.to(), networkNodes, adjacency),
                                SegmentedRoadPathOrchestrator.FailureReason.SEARCH_EXHAUSTED
                        ),
                        request -> shouldSubdivideSegment(request.from(), request.to())
                );
                if (orchestrated.success()) {
                    return orchestrated.path();
                }
            }
        }
        return findPathWithSnapshot(level, first.center(), second.center(), false);
    }

    private static List<RoadAnchor> getRoadAnchors(com.monpai.sailboatmod.nation.model.PlacedStructureRecord structure) {
        List<RoadAnchor> anchors = new ArrayList<>();
        Direction front = primaryRoadSide(structure.rotation());
        addRoadAnchors(anchors, structure, front);
        addRoadAnchors(anchors, structure, front.getClockWise());
        addRoadAnchors(anchors, structure, front.getCounterClockWise());
        addRoadAnchors(anchors, structure, front.getOpposite());
        return anchors;
    }

    private static void addRoadAnchors(List<RoadAnchor> anchors,
                                       com.monpai.sailboatmod.nation.model.PlacedStructureRecord structure,
                                       Direction side) {
        BlockPos origin = structure.origin();
        int[] offsets = side == Direction.NORTH || side == Direction.SOUTH
                ? buildSideOffsets(structure.sizeW())
                : buildSideOffsets(structure.sizeD());
        for (int offset : offsets) {
            BlockPos pos = switch (side) {
                case NORTH -> origin.offset(clampRoadOffset(structure.sizeW() / 2 + offset, structure.sizeW()), 0, -1);
                case SOUTH -> origin.offset(clampRoadOffset(structure.sizeW() / 2 + offset, structure.sizeW()), 0, structure.sizeD());
                case EAST -> origin.offset(structure.sizeW(), 0, clampRoadOffset(structure.sizeD() / 2 + offset, structure.sizeD()));
                case WEST -> origin.offset(-1, 0, clampRoadOffset(structure.sizeD() / 2 + offset, structure.sizeD()));
                default -> origin;
            };
            if (anchors.stream().noneMatch(existing -> existing.pos().equals(pos))) {
                anchors.add(new RoadAnchor(pos, side));
            }
        }
    }

    private static int[] buildSideOffsets(int span) {
        int edgeOffset = Math.max(1, span / 4);
        return new int[]{0, -edgeOffset, edgeOffset};
    }

    private static int clampRoadOffset(int value, int span) {
        return Math.max(0, Math.min(Math.max(0, span - 1), value));
    }

    private static Direction primaryRoadSide(int rotation) {
        return switch (Math.floorMod(rotation, 4)) {
            case 1 -> Direction.EAST;
            case 2 -> Direction.SOUTH;
            case 3 -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    private static void scheduleRoadConstruction(ServerLevel level,
                                                 RoadNetworkRecord road,
                                                 RoadPlacementPlan plan,
                                                 UUID ownerUuid,
                                                 String sourceTownName,
                                                 String targetTownName) {
        if (road.path().size() < 2) {
            ACTIVE_ROAD_CONSTRUCTIONS.remove(road.roadId());
            removePersistedRoadJob(level, road.roadId());
            return;
        }
        RoadPlacementPlan runtimePlan = plan == null ? createRoadPlacementPlan(level, road) : plan;
        if (runtimePlan.buildSteps().isEmpty()) {
            ACTIVE_ROAD_CONSTRUCTIONS.remove(road.roadId());
            removePersistedRoadJob(level, road.roadId());
            return;
        }
        int placedStepCount = findRoadPlacedStepCount(level, runtimePlan);
        if (placedStepCount >= runtimePlan.buildSteps().size()) {
            ACTIVE_ROAD_CONSTRUCTIONS.remove(road.roadId());
            removePersistedRoadJob(level, road.roadId());
            return;
        }
        String resolvedSource = sourceTownName;
        String resolvedTarget = targetTownName;
        if (resolvedSource == null || resolvedSource.isBlank() || resolvedTarget == null || resolvedTarget.isBlank()) {
            String[] resolvedNames = resolveRoadTownNames(level, road);
            resolvedSource = resolvedSource == null || resolvedSource.isBlank() ? resolvedNames[0] : resolvedSource;
            resolvedTarget = resolvedTarget == null || resolvedTarget.isBlank() ? resolvedNames[1] : resolvedTarget;
        }
        List<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> rollbackStates = captureRoadRollbackStates(level, runtimePlan);
        Set<Long> attemptedStepKeys = new LinkedHashSet<>(completedRoadBuildStepKeys(level, runtimePlan));
        ACTIVE_ROAD_CONSTRUCTIONS.put(road.roadId(), new RoadConstructionJob(
                level,
                road.roadId(),
                ownerUuid,
                road.townId(),
                road.nationId(),
                resolvedSource,
                resolvedTarget,
                runtimePlan,
                rollbackStates,
                placedStepCount,
                placedStepCount,
                false,
                0,
                false,
                attemptedStepKeys
        ));
        persistRoadConstruction(level, road.roadId(), ownerUuid, runtimePlan, rollbackStates, placedStepCount, placedStepCount, false, 0, false, attemptedStepKeys);
    }

    public static void scheduleManualRoad(ServerLevel level, RoadNetworkRecord road) {
        scheduleManualRoad(level, road, null, null, "", "");
    }

    public static void scheduleManualRoad(ServerLevel level,
                                          RoadNetworkRecord road,
                                          RoadPlacementPlan plan,
                                          UUID ownerUuid,
                                          String sourceTownName,
                                          String targetTownName) {
        if (level == null || road == null || road.roadId().isBlank()) {
            return;
        }
        NationSavedData.get(level).putRoadNetwork(road);
        scheduleRoadConstruction(level, road, plan, ownerUuid, sourceTownName, targetTownName);
    }

    public static void scheduleManualRoad(ServerLevel level,
                                          RoadNetworkRecord road,
                                          UUID ownerUuid,
                                          String sourceTownName,
                                          String targetTownName) {
        scheduleManualRoad(level, road, null, ownerUuid, sourceTownName, targetTownName);
    }

    static RoadPlacementPlan createRoadPlacementPlan(ServerLevel level,
                                                     List<BlockPos> centerPath,
                                                     BlockPos sourceInternalAnchor,
                                                     BlockPos sourceBoundaryAnchor,
                                                     BlockPos targetBoundaryAnchor,
                                                     BlockPos targetInternalAnchor) {
        List<BlockPos> trimmedCenterPath = trimExcludedPathEndpoints(
                centerPath == null ? List.of() : centerPath,
                collectCoreExclusionColumns(level)
        );
        List<RoadPlacementPlan.BridgeRange> bridgeRanges = trimmedCenterPath.isEmpty()
                ? List.of()
                : detectBridgeRanges(level, trimmedCenterPath);
        List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges = trimmedCenterPath.isEmpty()
                ? List.of()
                : detectNavigableWaterBridgeRanges(level, trimmedCenterPath, bridgeRanges);
        List<RoadPlacementPlan.BridgeRange> constructionBridgeRanges = trimmedCenterPath.isEmpty()
                ? List.of()
                : expandBridgeConstructionRanges(level, trimmedCenterPath, bridgeRanges, navigableWaterBridgeRanges);
        RoadCorridorPlan corridorPlan = createRoadCorridorPlan(level, trimmedCenterPath, constructionBridgeRanges, navigableWaterBridgeRanges);
        if (trimmedCenterPath.isEmpty()) {
            return new RoadPlacementPlan(List.of(), sourceInternalAnchor, sourceBoundaryAnchor, targetBoundaryAnchor, targetInternalAnchor,
                    List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, corridorPlan);
        }
        RoadPlacementArtifacts artifacts = filterCoreExcludedArtifacts(
                buildRoadPlacementArtifacts(level, corridorPlan),
                collectCoreExclusionColumns(level)
        );
        return new RoadPlacementPlan(
                trimmedCenterPath,
                sourceInternalAnchor,
                sourceBoundaryAnchor,
                targetBoundaryAnchor,
                targetInternalAnchor,
                artifacts.ghostBlocks(),
                artifacts.buildSteps(),
                constructionBridgeRanges,
                navigableWaterBridgeRanges,
                artifacts.ownedBlocks(),
                artifacts.buildSteps().isEmpty() ? null : highlightPos(sourceBoundaryAnchor, trimmedCenterPath, true),
                artifacts.buildSteps().isEmpty() ? null : highlightPos(targetBoundaryAnchor, trimmedCenterPath, false),
                artifacts.buildSteps().isEmpty() ? null : resolveRoadPlanFocusPos(trimmedCenterPath, artifacts.ghostBlocks()),
                corridorPlan
        );
    }

    private static List<BlockPos> trimExcludedPathEndpoints(List<BlockPos> centerPath, Set<Long> excludedColumns) {
        if (centerPath == null || centerPath.isEmpty() || excludedColumns == null || excludedColumns.isEmpty()) {
            return centerPath == null ? List.of() : List.copyOf(centerPath);
        }
        int start = 0;
        int end = centerPath.size() - 1;
        while (start <= end && isExcludedPathColumn(centerPath.get(start), excludedColumns)) {
            start++;
        }
        while (end >= start && isExcludedPathColumn(centerPath.get(end), excludedColumns)) {
            end--;
        }
        if (start > end) {
            return List.of();
        }
        return List.copyOf(centerPath.subList(start, end + 1));
    }

    private static boolean isExcludedPathColumn(BlockPos pos, Set<Long> excludedColumns) {
        return pos != null && excludedColumns.contains(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
    }

    static List<BlockPos> trimExcludedPathEndpointsForTest(List<BlockPos> centerPath, Set<Long> excludedColumns) {
        return trimExcludedPathEndpoints(centerPath, excludedColumns);
    }

    static List<RoadPlacementPlan.BridgeRange> expandBridgeConstructionRangesForTest(ServerLevel level,
                                                                                      List<BlockPos> centerPath,
                                                                                      List<RoadPlacementPlan.BridgeRange> bridgeRanges) {
        return expandBridgeConstructionRanges(
                level,
                centerPath,
                bridgeRanges,
                detectNavigableWaterBridgeRanges(level, centerPath, bridgeRanges)
        );
    }

    private static List<RoadPlacementPlan.BridgeRange> expandBridgeConstructionRanges(ServerLevel level,
                                                                                       List<BlockPos> centerPath,
                                                                                       List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                                                       List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges) {
        if (level == null || centerPath == null || centerPath.isEmpty() || bridgeRanges == null || bridgeRanges.isEmpty()) {
            return bridgeRanges == null ? List.of() : List.copyOf(bridgeRanges);
        }
        List<RoadPlacementPlan.BridgeRange> expanded = new ArrayList<>(bridgeRanges.size());
        for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
            if (range == null) {
                continue;
            }
            expanded.add(expandBridgeConstructionRange(level, centerPath, range, navigableWaterBridgeRanges));
        }
        return RoadBridgePlanner.normalizeRanges(expanded, centerPath.size());
    }

    private static RoadPlacementPlan.BridgeRange expandBridgeConstructionRange(ServerLevel level,
                                                                               List<BlockPos> centerPath,
                                                                               RoadPlacementPlan.BridgeRange range,
                                                                               List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges) {
        if (level == null || centerPath == null || centerPath.isEmpty() || range == null) {
            return range;
        }
        RoadBridgePlanner.BridgeSpanPlan baselinePlan = RoadBridgePlanner.planBridgeSpan(
                centerPath,
                range,
                index -> selectRoadPlacementStyle(level, centerPath.get(index).above()).bridge(),
                index -> isNavigableIndex(index, navigableWaterBridgeRanges),
                index -> findBridgeTerrainSurface(level, centerPath.get(index)).getY(),
                index -> {
                    int waterSurfaceY = findColumnWaterSurfaceY(level, centerPath.get(index).getX(), centerPath.get(index).getZ());
                    return waterSurfaceY == Integer.MIN_VALUE ? 0 : waterSurfaceY;
                },
                index -> hasStableFoundationBelow(level, centerPath.get(index))
        );
        if (baselinePlan.mode() != RoadBridgePlanner.BridgeMode.PIER_BRIDGE) {
            return range;
        }

        int start = Math.max(0, range.startIndex());
        int end = Math.min(centerPath.size() - 1, range.endIndex());
        int maxWaterSurfaceY = Integer.MIN_VALUE;
        for (int i = start; i <= end; i++) {
            BlockPos pos = centerPath.get(i);
            int waterSurfaceY = findColumnWaterSurfaceY(level, pos.getX(), pos.getZ());
            if (waterSurfaceY != Integer.MIN_VALUE) {
                maxWaterSurfaceY = Math.max(maxWaterSurfaceY, waterSurfaceY);
            }
        }
        if (maxWaterSurfaceY == Integer.MIN_VALUE) {
            return range;
        }

        int targetDeckY = maxWaterSurfaceY + MIN_WATER_CLEARANCE_ABOVE_SURFACE;
        int expandedStart = extendBridgeBoundary(level, centerPath, start, -1, targetDeckY);
        int expandedEnd = extendBridgeBoundary(level, centerPath, end, 1, targetDeckY);
        return new RoadPlacementPlan.BridgeRange(expandedStart, expandedEnd);
    }

    private static int extendBridgeBoundary(ServerLevel level,
                                            List<BlockPos> centerPath,
                                            int edgeIndex,
                                            int direction,
                                            int targetDeckY) {
        int shorelineIndex = firstLandIndexOutsideRange(level, centerPath, edgeIndex, direction);
        if (shorelineIndex < 0) {
            return edgeIndex;
        }
        BlockPos shorelinePos = centerPath.get(shorelineIndex);
        int shorelineTerrainY = findBridgeTerrainSurface(level, shorelinePos).getY();
        int shorelineDeckY = Math.max(shorelinePos.getY() + 1, shorelineTerrainY + 1);
        int overrun = Math.max(
                BRIDGE_HEAD_EXTENSION_MIN,
                Math.min(BRIDGE_HEAD_EXTENSION_MAX, targetDeckY - shorelineDeckY)
        );
        return direction < 0
                ? Math.max(0, edgeIndex - overrun)
                : Math.min(centerPath.size() - 1, edgeIndex + overrun);
    }

    private static int firstLandIndexOutsideRange(ServerLevel level,
                                                  List<BlockPos> centerPath,
                                                  int edgeIndex,
                                                  int direction) {
        for (int index = edgeIndex + direction; index >= 0 && index < centerPath.size(); index += direction) {
            BlockPos pos = centerPath.get(index);
            if (findColumnWaterSurfaceY(level, pos.getX(), pos.getZ()) == Integer.MIN_VALUE) {
                return index;
            }
        }
        return -1;
    }

    private static RoadCorridorPlan createRoadCorridorPlan(ServerLevel level,
                                                           List<BlockPos> centerPath,
                                                           List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                           List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges) {
        if (centerPath == null) {
            return RoadCorridorPlanner.plan(List.of());
        }
        List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans = planBridgeSpans(level, centerPath, bridgeRanges, navigableWaterBridgeRanges);
        int[] placementHeights = RoadGeometryPlanner.buildPlacementHeightProfileFromSpanPlans(centerPath, bridgePlans);
        placementHeights = surfaceReplacementPlacementHeights(centerPath, placementHeights, bridgeRanges);
        return RoadCorridorPlanner.plan(centerPath, bridgePlans, placementHeights);
    }

    private static int[] surfaceReplacementPlacementHeights(List<BlockPos> centerPath,
                                                            int[] placementHeights,
                                                            List<RoadPlacementPlan.BridgeRange> bridgeRanges) {
        if (centerPath == null || centerPath.isEmpty() || placementHeights == null || placementHeights.length != centerPath.size()) {
            return placementHeights == null ? new int[0] : placementHeights;
        }
        Set<Integer> preservedIndexes = new HashSet<>();
        if (bridgeRanges != null) {
            for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
                if (range == null) {
                    continue;
                }
                int start = Math.max(0, range.startIndex());
                int end = Math.min(centerPath.size() - 1, range.endIndex());
                for (int i = start; i <= end; i++) {
                    preservedIndexes.add(i);
                }
                if (start > 0) {
                    preservedIndexes.add(start - 1);
                }
                if (end + 1 < centerPath.size()) {
                    preservedIndexes.add(end + 1);
                }
            }
        }
        int[] adjusted = placementHeights.clone();
        for (int i = 0; i < adjusted.length; i++) {
            int terrainDeckY = centerPath.get(i).getY();
            if (adjusted[i] != terrainDeckY) {
                preservedIndexes.add(i);
            }
            if (!preservedIndexes.contains(i)) {
                adjusted[i] = centerPath.get(i).getY();
            }
        }
        return adjusted;
    }

    private static RoadPlacementPlan createRoadPlacementPlan(ServerLevel level, RoadNetworkRecord road) {
        if (road == null) {
            return createRoadPlacementPlan(level, List.of(), null, null, null, null);
        }
        List<BlockPos> centerPath = road.path();
        BlockPos start = centerPath.isEmpty() ? null : centerPath.get(0);
        BlockPos end = centerPath.isEmpty() ? null : centerPath.get(centerPath.size() - 1);
        return createRoadPlacementPlan(level, centerPath, start, start, end, end);
    }

    private static RoadConstructionJob refreshRoadConstructionState(ServerLevel level, RoadConstructionJob job) {
        if (level == null || job == null) {
            return job;
        }
        Set<Long> completedStepKeys = completedRoadBuildStepKeys(level, job.plan);
        int placedStepCount = countCompletedRoadBuildSteps(job.plan, completedStepKeys);
        return new RoadConstructionJob(
                job.level,
                job.roadId,
                job.ownerUuid,
                job.townId,
                job.nationId,
                job.sourceTownName,
                job.targetTownName,
                job.plan,
                job.rollbackStates,
                placedStepCount,
                job.rollbackActive
                        ? Math.max(job.progressSteps, job.rollbackActionIndex)
                        : placedStepCount,
                job.rollbackActive,
                job.rollbackActionIndex,
                job.removeRoadNetworkOnComplete,
                completedStepKeys
        );
    }

    private static RoadConstructionJob placeRoadBuildSteps(ServerLevel level, RoadConstructionJob job, int stepCount) {
        if (job == null || stepCount <= 0) {
            return job;
        }
        job = refreshRoadConstructionState(level, job);
        int totalSteps = job.plan.buildSteps().size();
        Set<Long> attemptedStepKeys = new LinkedHashSet<>(job.attemptedStepKeys);
        int startCount = countCompletedRoadBuildSteps(job.plan, attemptedStepKeys);
        int targetCount = Math.max(startCount, Math.min(totalSteps, startCount + stepCount));
        boolean placedAny = false;
        int completedCount = startCount;
        BlockPos effectPos = null;
        RoadGeometryPlanner.RoadBuildPhase highestPlaceablePhase = RoadGeometryPlanner.RoadBuildPhase.DECOR;
        for (RoadGeometryPlanner.RoadBuildStep step : job.plan.buildSteps()) {
            if (attemptedStepKeys.contains(step.pos().asLong())) {
                continue;
            }
            boolean skippedForPhaseLock = step.phase().compareTo(highestPlaceablePhase) > 0;
            boolean placed = false;
            if (!skippedForPhaseLock) {
                ConstructionStepExecutor.clearNaturalObstacles(level, step.pos());
                ConstructionStepSatisfactionService.StepDecision decision =
                        ConstructionStepSatisfactionService.decide(
                                level.getBlockState(step.pos()),
                                step.state(),
                                step.pos(),
                                toStepKind(step.phase())
                        );
                if (decision == ConstructionStepSatisfactionService.StepDecision.SATISFIED) {
                    attemptedStepKeys.add(step.pos().asLong());
                    completedCount++;
                    effectPos = step.pos();
                } else if (decision == ConstructionStepSatisfactionService.StepDecision.RETRYABLE) {
                    ConstructionStepExecutor.clearNaturalObstacles(level, step.pos());
                    if (step.phase() == RoadGeometryPlanner.RoadBuildPhase.SUPPORT) {
                        highestPlaceablePhase = RoadGeometryPlanner.RoadBuildPhase.SUPPORT;
                    } else if (step.phase() == RoadGeometryPlanner.RoadBuildPhase.DECK) {
                        highestPlaceablePhase = RoadGeometryPlanner.RoadBuildPhase.DECK;
                    }
                } else if (decision == ConstructionStepSatisfactionService.StepDecision.BLOCKED) {
                    if (step.phase() == RoadGeometryPlanner.RoadBuildPhase.SUPPORT) {
                        highestPlaceablePhase = RoadGeometryPlanner.RoadBuildPhase.SUPPORT;
                    } else if (step.phase() == RoadGeometryPlanner.RoadBuildPhase.DECK) {
                        highestPlaceablePhase = RoadGeometryPlanner.RoadBuildPhase.DECK;
                    }
                } else {
                    placed = tryPlaceRoad(level, step.pos(), roadPlacementStyleForState(level, step.pos(), step.state()));
                    placedAny |= placed;
                    if (placed) {
                        attemptedStepKeys.add(step.pos().asLong());
                        completedCount++;
                        effectPos = step.pos();
                    } else {
                        if (step.phase() == RoadGeometryPlanner.RoadBuildPhase.SUPPORT) {
                            highestPlaceablePhase = RoadGeometryPlanner.RoadBuildPhase.SUPPORT;
                        } else if (step.phase() == RoadGeometryPlanner.RoadBuildPhase.DECK) {
                            highestPlaceablePhase = RoadGeometryPlanner.RoadBuildPhase.DECK;
                        }
                    }
                }
            }
            if (completedCount >= targetCount) {
                break;
            }
        }
        if (placedAny) {
            BlockPos center = effectPos == null ? job.plan.buildSteps().get(Math.min(job.plan.buildSteps().size() - 1, Math.max(0, startCount))).pos() : effectPos;
            level.sendParticles(ParticleTypes.CLOUD,
                    center.getX() + 0.5D,
                    center.getY() + 0.25D,
                    center.getZ() + 0.5D,
                    4, 0.2D, 0.1D, 0.2D, 0.01D);
            level.playSound(null, center, Blocks.STONE_BRICK_SLAB.defaultBlockState().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.35F, 0.95F);
        }
        return new RoadConstructionJob(
                job.level,
                job.roadId,
                job.ownerUuid,
                job.townId,
                job.nationId,
                job.sourceTownName,
                job.targetTownName,
                job.plan,
                job.rollbackStates,
                completedCount,
                Math.max(job.progressSteps, completedCount),
                false,
                0,
                false,
                Set.copyOf(attemptedStepKeys)
        );
    }

    private static ConstructionStepSatisfactionService.StepKind toStepKind(RoadGeometryPlanner.RoadBuildPhase phase) {
        if (phase == RoadGeometryPlanner.RoadBuildPhase.SUPPORT) {
            return ConstructionStepSatisfactionService.StepKind.ROAD_SUPPORT;
        }
        if (phase == RoadGeometryPlanner.RoadBuildPhase.DECOR) {
            return ConstructionStepSatisfactionService.StepKind.ROAD_DECOR;
        }
        return ConstructionStepSatisfactionService.StepKind.ROAD_DECK;
    }

    private static RoadConstructionJob rollbackRoadBuildSteps(ServerLevel level, RoadConstructionJob job, int actionCount) {
        if (job == null || actionCount <= 0) {
            return job;
        }
        job = refreshRoadConstructionState(level, job);
        int nextRollbackActionIndex = applyRoadRollbackBatchWithSetter(
                level,
                job.plan,
                job.rollbackStates,
                job.rollbackActionIndex,
                actionCount,
                (pos, state) -> {
                    if (pos == null || state == null || level == null) {
                        return;
                    }
                    level.setBlock(pos, state, Block.UPDATE_ALL);
                }
        );
        int placedStepCount = findRoadPlacedStepCount(level, job.plan);
        List<BlockPos> actionOrder = roadRollbackActionOrder(level, job.plan, job.rollbackStates);
        if (nextRollbackActionIndex > job.rollbackActionIndex && !actionOrder.isEmpty()) {
            BlockPos center = actionOrder.get(Math.max(0, Math.min(actionOrder.size() - 1, nextRollbackActionIndex - 1)));
            level.sendParticles(ParticleTypes.CLOUD,
                    center.getX() + 0.5D,
                    center.getY() + 0.25D,
                    center.getZ() + 0.5D,
                    3, 0.15D, 0.1D, 0.15D, 0.01D);
            level.playSound(null, center, Blocks.STONE.defaultBlockState().getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.3F, 0.9F);
        }
        return new RoadConstructionJob(
                job.level,
                job.roadId,
                job.ownerUuid,
                job.townId,
                job.nationId,
                job.sourceTownName,
                job.targetTownName,
                job.plan,
                job.rollbackStates,
                placedStepCount,
                Math.max(job.progressSteps, nextRollbackActionIndex),
                true,
                nextRollbackActionIndex,
                job.removeRoadNetworkOnComplete,
                job.attemptedStepKeys
        );
    }

    private static int applyRoadRollbackBatchWithSetter(ServerLevel level,
                                                        RoadPlacementPlan plan,
                                                        List<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> rollbackStates,
                                                        int rollbackActionIndex,
                                                        int actionCount,
                                                        java.util.function.BiConsumer<BlockPos, BlockState> stateSetter) {
        if (plan == null || actionCount <= 0 || stateSetter == null) {
            return Math.max(0, rollbackActionIndex);
        }
        List<BlockPos> actionOrder = roadRollbackActionOrder(level, plan, rollbackStates);
        if (actionOrder.isEmpty()) {
            return 0;
        }
        Map<Long, ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> snapshotIndex = new LinkedHashMap<>();
        if (rollbackStates != null) {
            for (ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState rollbackState : rollbackStates) {
                if (rollbackState != null) {
                    snapshotIndex.put(rollbackState.pos(), rollbackState);
                }
            }
        }
        int start = Math.max(0, Math.min(actionOrder.size(), rollbackActionIndex));
        int end = Math.max(start, Math.min(actionOrder.size(), start + actionCount));
        for (int i = start; i < end; i++) {
            BlockPos pos = actionOrder.get(i);
            ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState snapshot = snapshotIndex.get(pos.asLong());
            BlockState restoredState = snapshot == null
                    ? Blocks.AIR.defaultBlockState()
                    : (level == null ? deserializeBlockStatePayload(snapshot.statePayload()) : deserializeBlockState(level, snapshot.statePayload()));
            if (restoredState != null) {
                stateSetter.accept(pos, restoredState);
            }
        }
        return end;
    }

    private static List<BlockPos> roadRollbackActionOrder(ServerLevel level,
                                                          RoadPlacementPlan plan,
                                                          List<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> rollbackStates) {
        if (plan == null) {
            return List.of();
        }
        ArrayList<BlockPos> ordered = new ArrayList<>();
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        LinkedHashSet<Long> headspaceKeys = new LinkedHashSet<>();
        for (int i = plan.buildSteps().size() - 1; i >= 0; i--) {
            RoadGeometryPlanner.RoadBuildStep step = plan.buildSteps().get(i);
            if (step == null || step.pos() == null) {
                continue;
            }
            if (seen.add(step.pos().asLong())) {
                ordered.add(step.pos());
            }
            headspaceKeys.add(step.pos().above().asLong());
        }
        for (BlockPos pos : RoadLifecycleService.removalOrder(roadOwnedBlocks(level, plan))) {
            if (pos != null && seen.add(pos.asLong())) {
                ordered.add(pos);
            }
        }
        if (rollbackStates != null && !rollbackStates.isEmpty()) {
            for (ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState rollbackState : rollbackStates) {
                if (rollbackState == null) {
                    continue;
                }
                long posKey = rollbackState.pos();
                if (headspaceKeys.contains(posKey) || !seen.add(posKey)) {
                    continue;
                }
                ordered.add(BlockPos.of(posKey));
            }
            for (ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState rollbackState : rollbackStates) {
                if (rollbackState == null) {
                    continue;
                }
                long posKey = rollbackState.pos();
                if (!headspaceKeys.contains(posKey) || !seen.add(posKey)) {
                    continue;
                }
                ordered.add(BlockPos.of(posKey));
            }
        }
        return ordered.isEmpty() ? List.of() : List.copyOf(ordered);
    }

    private static Set<BlockPos> collectRoadCoverage(Collection<RoadNetworkRecord> roads) {
        Set<BlockPos> coverage = new HashSet<>();
        for (RoadNetworkRecord road : roads) {
            coverage.addAll(collectRoadPlacementPositions(road.path()));
        }
        return coverage;
    }

    private static Set<BlockPos> collectRoadPlacementPositions(List<BlockPos> path) {
        Set<BlockPos> positions = new HashSet<>();
        for (int i = 0; i < path.size(); i++) {
            positions.addAll(collectRoadSlicePositions(path, i));
        }
        return positions;
    }

    private static Set<BlockPos> collectRoadSlicePositions(List<BlockPos> path, int index) {
        return new LinkedHashSet<>(RoadGeometryPlanner.slicePositions(path, index));
    }

    private static List<RoadPlacementPlan.BridgeRange> detectBridgeRanges(ServerLevel level, List<BlockPos> centerPath) {
        if (level == null || centerPath == null || centerPath.isEmpty()) {
            return List.of();
        }
        List<RoadPlacementPlan.BridgeRange> ranges = new ArrayList<>();
        int rangeStart = -1;
        for (int i = 0; i < centerPath.size(); i++) {
            boolean bridge = isBridgeDeckColumn(level, centerPath.get(i));
            if (bridge && rangeStart < 0) {
                rangeStart = i;
            } else if (!bridge && rangeStart >= 0) {
                ranges.add(new RoadPlacementPlan.BridgeRange(rangeStart, i - 1));
                rangeStart = -1;
            }
        }
        if (rangeStart >= 0) {
            ranges.add(new RoadPlacementPlan.BridgeRange(rangeStart, centerPath.size() - 1));
        }
        return mergeNearbyBridgeRanges(ranges, centerPath.size());
    }

    private static boolean isBridgeDeckColumn(ServerLevel level, BlockPos centerPos) {
        if (level == null || centerPos == null) {
            return false;
        }
        BlockPos terrainSurface = findBridgeTerrainSurface(level, centerPos);
        while (terrainSurface.getY() >= safeMinBuildHeight(level)) {
            BlockState state = level.getBlockState(terrainSurface);
            if (!state.isAir() && !state.liquid() && state.isFaceSturdy(level, terrainSurface, Direction.UP)) {
                break;
            }
            terrainSurface = terrainSurface.below();
        }
        return centerPos.getY() - terrainSurface.getY() >= 2;
    }

    private static List<RoadPlacementPlan.BridgeRange> mergeNearbyBridgeRanges(List<RoadPlacementPlan.BridgeRange> ranges,
                                                                                int pathSize) {
        return RoadBridgePlanner.normalizeRanges(ranges, pathSize);
    }

    private static List<RoadBridgePlanner.BridgeProfile> classifyBridgeProfiles(ServerLevel level,
                                                                                 List<BlockPos> centerPath,
                                                                                 List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                                                 List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges) {
        if (level == null || centerPath == null || centerPath.isEmpty() || bridgeRanges == null || bridgeRanges.isEmpty()) {
            return List.of();
        }
        List<RoadBridgePlanner.BridgeProfile> baseProfiles = RoadBridgePlanner.classifyRanges(
                centerPath,
                bridgeRanges,
                index -> selectRoadPlacementStyle(level, centerPath.get(index).above()).bridge(),
                index -> findBridgeTerrainSurface(level, centerPath.get(index)).getY()
        );
        if (navigableWaterBridgeRanges == null || navigableWaterBridgeRanges.isEmpty()) {
            return baseProfiles;
        }
        List<RoadBridgePlanner.BridgeProfile> profiles = new ArrayList<>(baseProfiles.size());
        for (RoadBridgePlanner.BridgeProfile profile : baseProfiles) {
            if (profile == null) {
                continue;
            }
            int maxWaterSurfaceY = Integer.MIN_VALUE;
            for (RoadPlacementPlan.BridgeRange navigableRange : navigableWaterBridgeRanges) {
                if (!rangesOverlap(profile.startIndex(), profile.endIndex(), navigableRange)) {
                    continue;
                }
                maxWaterSurfaceY = Math.max(maxWaterSurfaceY, findWaterSurfaceY(level, centerPath, navigableRange));
            }
            if (maxWaterSurfaceY != Integer.MIN_VALUE) {
                profiles.add(RoadBridgePlanner.buildNavigableBridgeProfile(profile.startIndex(), profile.endIndex(), maxWaterSurfaceY));
                continue;
            }
            profiles.add(profile);
        }
        return List.copyOf(profiles);
    }

    private static List<RoadBridgePlanner.BridgeSpanPlan> planBridgeSpans(ServerLevel level,
                                                                          List<BlockPos> centerPath,
                                                                          List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                                          List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges) {
        if (level == null || centerPath == null || centerPath.isEmpty() || bridgeRanges == null || bridgeRanges.isEmpty()) {
            return List.of();
        }
        List<RoadBridgePlanner.BridgeSpanPlan> plans = new ArrayList<>(bridgeRanges.size());
        for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
            if (range == null) {
                continue;
            }
            RoadBridgePlanner.BridgeSpanPlan plan = RoadBridgePlanner.planBridgeSpan(
                    centerPath,
                    range,
                    index -> selectRoadPlacementStyle(level, centerPath.get(index).above()).bridge(),
                    index -> isNavigableIndex(index, navigableWaterBridgeRanges),
                    index -> findBridgeTerrainSurface(level, centerPath.get(index)).getY(),
                    index -> {
                        int waterSurfaceY = findColumnWaterSurfaceY(level, centerPath.get(index).getX(), centerPath.get(index).getZ());
                        return waterSurfaceY == Integer.MIN_VALUE ? 0 : waterSurfaceY;
                    },
                    index -> hasStableFoundationBelow(level, centerPath.get(index))
            );
            plans.add(repairFoundationlessPierPlan(level, centerPath, range, navigableWaterBridgeRanges, plan));
        }
        return List.copyOf(plans);
    }

    private static RoadBridgePlanner.BridgeSpanPlan repairFoundationlessPierPlan(ServerLevel level,
                                                                                  List<BlockPos> centerPath,
                                                                                  RoadPlacementPlan.BridgeRange range,
                                                                                  List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges,
                                                                                  RoadBridgePlanner.BridgeSpanPlan originalPlan) {
        if (level == null
                || centerPath == null
                || range == null
                || originalPlan == null
                || originalPlan.mode() != RoadBridgePlanner.BridgeMode.PIER_BRIDGE
                || originalPlan.valid()) {
            return originalPlan;
        }

        RoadBridgePlanner.BridgeSpanPlan repairedPlan = RoadBridgePlanner.planBridgeSpan(
                centerPath,
                range,
                index -> selectRoadPlacementStyle(level, centerPath.get(index).above()).bridge(),
                index -> isNavigableIndex(index, navigableWaterBridgeRanges),
                index -> findBridgeTerrainSurface(level, centerPath.get(index)).getY(),
                index -> {
                    int waterSurfaceY = findColumnWaterSurfaceY(level, centerPath.get(index).getX(), centerPath.get(index).getZ());
                    return waterSurfaceY == Integer.MIN_VALUE ? 0 : waterSurfaceY;
                },
                index -> true
        );
        return repairedPlan.mode() == RoadBridgePlanner.BridgeMode.PIER_BRIDGE && repairedPlan.valid()
                ? repairedPlan
                : originalPlan;
    }

    private static boolean rangesOverlap(int startIndex, int endIndex, RoadPlacementPlan.BridgeRange range) {
        if (range == null) {
            return false;
        }
        return Math.max(startIndex, range.startIndex()) <= Math.min(endIndex, range.endIndex());
    }

    private static List<RoadPlacementPlan.BridgeRange> detectNavigableWaterBridgeRanges(ServerLevel level,
                                                                                         List<BlockPos> centerPath,
                                                                                         List<RoadPlacementPlan.BridgeRange> bridgeRanges) {
        if (level == null || centerPath == null || centerPath.isEmpty() || bridgeRanges == null || bridgeRanges.isEmpty()) {
            return List.of();
        }
        List<RoadPlacementPlan.BridgeRange> rawRanges = RoadCorridorPlanner.detectContiguousSubranges(
                centerPath,
                bridgeRanges,
                index -> isNavigableWaterBridgeColumn(level, centerPath.get(index))
        );
        if (rawRanges.isEmpty()) {
            return List.of();
        }
        List<RoadPlacementPlan.BridgeRange> protectedChannels = new ArrayList<>(bridgeRanges.size());
        for (RoadPlacementPlan.BridgeRange bridgeRange : bridgeRanges) {
            if (bridgeRange == null) {
                continue;
            }
            RoadPlacementPlan.BridgeRange widestWaterRange = null;
            int widestWidth = 0;
            for (RoadPlacementPlan.BridgeRange rawRange : rawRanges) {
                if (rawRange == null || !rangesOverlap(bridgeRange.startIndex(), bridgeRange.endIndex(), rawRange)) {
                    continue;
                }
                int width = rawRange.endIndex() - rawRange.startIndex() + 1;
                if (width > widestWidth) {
                    widestWidth = width;
                    widestWaterRange = rawRange;
                }
            }
            RoadPlacementPlan.BridgeRange narrowedChannel = narrowProtectedMainChannel(widestWaterRange, centerPath.size());
            if (narrowedChannel != null) {
                protectedChannels.add(narrowedChannel);
            }
        }
        return protectedChannels.isEmpty() ? List.of() : List.copyOf(protectedChannels);
    }

    private static RoadPlacementPlan.BridgeRange narrowProtectedMainChannel(RoadPlacementPlan.BridgeRange range, int pathSize) {
        if (range == null || pathSize <= 0) {
            return null;
        }
        int start = Math.max(0, range.startIndex());
        int end = Math.min(pathSize - 1, range.endIndex());
        if (end < start) {
            return null;
        }
        int width = end - start + 1;
        int targetWidth = Math.min(3, width);
        if (targetWidth <= 0) {
            return null;
        }
        int narrowedStart = start + Math.max(0, (width - targetWidth) / 2);
        int narrowedEnd = narrowedStart + targetWidth - 1;
        return new RoadPlacementPlan.BridgeRange(narrowedStart, narrowedEnd);
    }

    private static boolean isNavigableWaterBridgeColumn(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        return findColumnWaterSurfaceY(level, pos.getX(), pos.getZ()) != Integer.MIN_VALUE;
    }

    private static boolean isNavigableIndex(int index, List<RoadPlacementPlan.BridgeRange> navigableRanges) {
        if (navigableRanges == null || navigableRanges.isEmpty()) {
            return false;
        }
        for (RoadPlacementPlan.BridgeRange range : navigableRanges) {
            if (range != null && index >= range.startIndex() && index <= range.endIndex()) {
                return true;
            }
        }
        return false;
    }

    private static int findWaterSurfaceY(ServerLevel level, List<BlockPos> centerPath, RoadPlacementPlan.BridgeRange range) {
        int maxWaterSurfaceY = Integer.MIN_VALUE;
        for (int i = Math.max(0, range.startIndex()); i <= Math.min(centerPath.size() - 1, range.endIndex()); i++) {
            BlockPos pos = centerPath.get(i);
            int y = findColumnWaterSurfaceY(level, pos.getX(), pos.getZ());
            if (y != Integer.MIN_VALUE) {
                maxWaterSurfaceY = Math.max(maxWaterSurfaceY, y);
            }
        }
        return maxWaterSurfaceY == Integer.MIN_VALUE ? 0 : maxWaterSurfaceY;
    }

    private static int findColumnWaterSurfaceY(ServerLevel level, int x, int z) {
        if (level == null) {
            return Integer.MIN_VALUE;
        }
        int minBuildHeight = safeMinBuildHeight(level);
        BlockPos cursor = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z)).below();
        while (cursor.getY() >= minBuildHeight) {
            BlockState state = level.getBlockState(cursor);
            if (state.liquid() || !state.getFluidState().isEmpty()) {
                return cursor.getY();
            }
            cursor = cursor.below();
        }
        return Integer.MIN_VALUE;
    }

    private static boolean hasStableFoundationBelow(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        BlockPos terrainSurface = findBridgeTerrainSurface(level, pos);
        while (terrainSurface.getY() >= safeMinBuildHeight(level)) {
            BlockState state = level.getBlockState(terrainSurface);
            if (!state.isAir() && !state.liquid() && state.isFaceSturdy(level, terrainSurface, Direction.UP)) {
                return true;
            }
            terrainSurface = terrainSurface.below();
        }
        return false;
    }

    private static BlockPos findBridgeTerrainSurface(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return pos == null ? BlockPos.ZERO : pos;
        }
        int minBuildHeight = safeMinBuildHeight(level);
        BlockPos heightmapSurface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos).below();
        BlockPos fallback = heightmapSurface;
        BlockPos cursor = heightmapSurface;
        while (cursor.getY() >= minBuildHeight) {
            BlockState state = level.getBlockState(cursor);
            if (state != null
                    && !state.isAir()
                    && !state.liquid()
                    && !isBridgeTerrainObstacle(state)) {
                return cursor;
            }
            cursor = cursor.below();
        }
        return fallback;
    }

    private static boolean isBridgeTerrainObstacle(BlockState state) {
        return state == null
                || state.isAir()
                || state.liquid()
                || clearanceStateIsSafeToRemove(state);
    }

    private static int safeMinBuildHeight(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        try {
            return level.getMinBuildHeight();
        } catch (NullPointerException ignored) {
            return 0;
        }
    }

    private static BlockPos highlightPos(BlockPos anchorPos, List<BlockPos> centerPath, boolean start) {
        if (anchorPos != null) {
            return anchorPos.above();
        }
        if (centerPath == null || centerPath.isEmpty()) {
            return null;
        }
        return (start ? centerPath.get(0) : centerPath.get(centerPath.size() - 1)).above();
    }

    private static BlockPos resolveRoadPlanFocusPos(List<BlockPos> centerPath,
                                                    List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        if (ghostBlocks != null && !ghostBlocks.isEmpty()) {
            return ghostBlocks.get(ghostBlocks.size() / 2).pos();
        }
        if (centerPath == null || centerPath.isEmpty()) {
            return null;
        }
        return centerPath.get(centerPath.size() / 2).above();
    }

    private static int findRoadPlacedStepCount(ServerLevel level, RoadPlacementPlan plan) {
        return countCompletedRoadBuildSteps(plan, completedRoadBuildStepKeys(level, plan));
    }

    private static boolean isRoadBuildStepPlaced(ServerLevel level, RoadGeometryPlanner.RoadBuildStep step) {
        return level != null && step != null && isRoadBuildStepPlaced(level.getBlockState(step.pos()), step);
    }

    private static boolean isRoadBuildStepPlaced(BlockState currentState, RoadGeometryPlanner.RoadBuildStep step) {
        if (currentState == null || step == null || step.state() == null) {
            return false;
        }
        BlockState plannedState = step.state();
        if (!currentState.is(plannedState.getBlock())) {
            return false;
        }
        for (Property<?> property : plannedState.getProperties()) {
            if (property == BlockStateProperties.WATERLOGGED) {
                continue;
            }
            if (!currentState.hasProperty(property)) {
                return false;
            }
            if (!samePropertyValue(currentState, plannedState, property)) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Comparable<T>> boolean samePropertyValue(BlockState currentState,
                                                                       BlockState plannedState,
                                                                       Property<T> property) {
        return currentState.getValue(property).equals(plannedState.getValue(property));
    }

    private static List<RoadGeometryPlanner.GhostRoadBlock> remainingRoadGhostBlocks(RoadPlacementPlan plan, int placedStepCount) {
        if (plan == null || plan.buildSteps().isEmpty()) {
            return List.of();
        }
        int clamped = Math.max(0, Math.min(placedStepCount, plan.buildSteps().size()));
        return plan.buildSteps().subList(clamped, plan.buildSteps().size()).stream()
                .map(step -> new RoadGeometryPlanner.GhostRoadBlock(step.pos(), step.state()))
                .toList();
    }

    private static List<RoadGeometryPlanner.GhostRoadBlock> remainingRoadGhostBlocks(RoadPlacementPlan plan, Set<Long> completedStepKeys) {
        return remainingRoadBuildSteps(plan, completedStepKeys).stream()
                .map(step -> new RoadGeometryPlanner.GhostRoadBlock(step.pos(), step.state()))
                .toList();
    }

    private static List<RoadGeometryPlanner.GhostRoadBlock> remainingRoadGhostBlocks(ServerLevel level, RoadConstructionJob job) {
        if (level == null || job == null || job.rollbackActive) {
            return List.of();
        }
        return remainingRoadGhostBlocks(job.plan, consumedRoadBuildStepKeys(level, job));
    }

    private static int nextRoadBuildBatchSize(RoadPlacementPlan plan, int placedStepCount) {
        if (plan == null || plan.buildSteps().isEmpty()) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(placedStepCount, plan.buildSteps().size()));
        if (clamped >= plan.buildSteps().size()) {
            return 0;
        }
        int consumed = 0;
        for (Integer batchSize : roadBuildBatchSizes(plan)) {
            int nextConsumed = consumed + batchSize;
            if (clamped < nextConsumed) {
                return nextConsumed - clamped;
            }
            consumed = nextConsumed;
        }
        return plan.buildSteps().size() - clamped;
    }

    private static int nextRoadBuildBatchSize(RoadPlacementPlan plan, Set<Long> completedStepKeys) {
        for (List<RoadGeometryPlanner.RoadBuildStep> batch : roadBuildBatches(plan)) {
            int remaining = 0;
            for (RoadGeometryPlanner.RoadBuildStep step : batch) {
                if (!completedStepKeys.contains(step.pos().asLong())) {
                    remaining++;
                }
            }
            if (remaining > 0) {
                return remaining;
            }
        }
        return 0;
    }

    private static int roadBuildBatchIndex(RoadPlacementPlan plan, int placedStepCount) {
        if (plan == null || plan.centerPath().isEmpty()) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(placedStepCount, plan.buildSteps().size()));
        int consumed = 0;
        List<Integer> batchSizes = roadBuildBatchSizes(plan);
        for (int i = 0; i < batchSizes.size(); i++) {
            consumed += batchSizes.get(i);
            if (clamped < consumed) {
                return i;
            }
        }
        return Math.max(0, Math.min(plan.centerPath().size() - 1, batchSizes.size() - 1));
    }

    private static int roadBuildBatchIndex(RoadPlacementPlan plan, Set<Long> completedStepKeys) {
        if (plan == null || plan.centerPath().isEmpty()) {
            return 0;
        }
        List<List<RoadGeometryPlanner.RoadBuildStep>> batches = roadBuildBatches(plan);
        for (int i = 0; i < batches.size(); i++) {
            for (RoadGeometryPlanner.RoadBuildStep step : batches.get(i)) {
                if (!completedStepKeys.contains(step.pos().asLong())) {
                    return i;
                }
            }
        }
        return Math.max(0, Math.min(plan.centerPath().size() - 1, batches.size() - 1));
    }

    private static List<Integer> roadBuildBatchSizes(RoadPlacementPlan plan) {
        if (plan == null || plan.centerPath().isEmpty() || plan.buildSteps().isEmpty()) {
            return List.of();
        }
        Set<Long> buildStepPositions = plan.buildSteps().stream()
                .map(RoadGeometryPlanner.RoadBuildStep::pos)
                .map(BlockPos::asLong)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> seen = new LinkedHashSet<>();
        List<Integer> batchSizes = new ArrayList<>();
        for (int i = 0; i < plan.centerPath().size(); i++) {
            int count = 0;
            for (BlockPos pos : collectRoadSlicePositions(plan, i)) {
                long key = pos.asLong();
                if (buildStepPositions.contains(key) && seen.add(key)) {
                    count++;
                }
            }
            if (count > 0) {
                batchSizes.add(count);
            }
        }
        if (batchSizes.isEmpty()) {
            batchSizes.add(plan.buildSteps().size());
        }
        return List.copyOf(batchSizes);
    }

    private static List<List<RoadGeometryPlanner.RoadBuildStep>> roadBuildBatches(RoadPlacementPlan plan) {
        if (plan == null || plan.centerPath().isEmpty() || plan.buildSteps().isEmpty()) {
            return List.of();
        }
        LinkedHashMap<Long, RoadGeometryPlanner.RoadBuildStep> buildStepsByPos = new LinkedHashMap<>();
        for (RoadGeometryPlanner.RoadBuildStep step : plan.buildSteps()) {
            buildStepsByPos.put(step.pos().asLong(), step);
        }
        Set<Long> seen = new LinkedHashSet<>();
        List<List<RoadGeometryPlanner.RoadBuildStep>> batches = new ArrayList<>();
        for (int i = 0; i < plan.centerPath().size(); i++) {
            List<RoadGeometryPlanner.RoadBuildStep> batch = new ArrayList<>();
            for (BlockPos pos : collectRoadSlicePositions(plan, i)) {
                long key = pos.asLong();
                if (!seen.add(key)) {
                    continue;
                }
                RoadGeometryPlanner.RoadBuildStep step = buildStepsByPos.get(key);
                if (step != null) {
                    batch.add(step);
                }
            }
            if (!batch.isEmpty()) {
                batches.add(List.copyOf(batch));
            }
        }
        if (batches.isEmpty()) {
            batches.add(List.copyOf(plan.buildSteps()));
        }
        return List.copyOf(batches);
    }

    private static Set<Long> completedRoadBuildStepKeys(ServerLevel level, RoadPlacementPlan plan) {
        if (level == null || plan == null || plan.buildSteps().isEmpty()) {
            return Set.of();
        }
        Set<Long> completed = new LinkedHashSet<>();
        for (RoadGeometryPlanner.RoadBuildStep step : plan.buildSteps()) {
            if (isRoadBuildStepPlaced(level, step)) {
                completed.add(step.pos().asLong());
            }
        }
        return completed;
    }

    private static Set<Long> consumedRoadBuildStepKeys(ServerLevel level, RoadConstructionJob job) {
        if (level == null || job == null || job.plan == null || job.plan.buildSteps().isEmpty()) {
            return Set.of();
        }
        return consumedRoadBuildStepKeys(job.plan, completedRoadBuildStepKeys(level, job.plan), job.attemptedStepKeys);
    }

    private static Set<Long> consumedRoadBuildStepKeys(RoadPlacementPlan plan,
                                                       Set<Long> completedStepKeys,
                                                       Set<Long> attemptedStepKeys) {
        if (plan == null || plan.buildSteps().isEmpty()) {
            return Set.of();
        }
        Set<Long> validStepKeys = plan.buildSteps().stream()
                .map(RoadGeometryPlanner.RoadBuildStep::pos)
                .map(BlockPos::asLong)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<Long> consumed = new LinkedHashSet<>();
        if (completedStepKeys != null) {
            for (Long key : completedStepKeys) {
                if (key != null && validStepKeys.contains(key)) {
                    consumed.add(key);
                }
            }
        }
        return Set.copyOf(consumed);
    }

    private static int countCompletedRoadBuildSteps(RoadPlacementPlan plan, Set<Long> completedStepKeys) {
        if (plan == null || plan.buildSteps().isEmpty()) {
            return 0;
        }
        if (completedStepKeys == null || completedStepKeys.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (RoadGeometryPlanner.RoadBuildStep step : plan.buildSteps()) {
            if (completedStepKeys.contains(step.pos().asLong())) {
                count++;
            }
        }
        return count;
    }

    private static List<RoadGeometryPlanner.RoadBuildStep> remainingRoadBuildSteps(RoadPlacementPlan plan, Set<Long> completedStepKeys) {
        if (plan == null || plan.buildSteps().isEmpty()) {
            return List.of();
        }
        return remainingRoadBuildSteps(plan.buildSteps(), completedStepKeys, Set.of());
    }

    private static List<RoadGeometryPlanner.RoadBuildStep> remainingRoadBuildSteps(List<RoadGeometryPlanner.RoadBuildStep> buildSteps,
                                                                                    Set<Long> completedStepKeys,
                                                                                    Set<Long> attemptedStepKeys) {
        if (buildSteps == null || buildSteps.isEmpty()) {
            return List.of();
        }
        Set<Long> completed = completedStepKeys == null ? Set.of() : completedStepKeys;
        return buildSteps.stream()
                .filter(step -> !completed.contains(step.pos().asLong()))
                .toList();
    }

    private static BlockPos selectRoadGhostTargetPos(List<RoadGeometryPlanner.GhostRoadBlock> remainingGhosts) {
        return remainingGhosts == null || remainingGhosts.isEmpty() ? null : remainingGhosts.get(0).pos();
    }

    private static boolean tryPlaceRoad(ServerLevel level, BlockPos pos, RoadPlacementStyle style) {
        if (!clearRoadDeckSpace(level, pos)) {
            return false;
        }
        BlockState at = level.getBlockState(pos);
        if (at.equals(style.surface())) {
            return false;
        }
        boolean replacingRoadSurface = canReplaceRoadSurface(at, style.surface());
        if (!replacingRoadSurface && isRoadSurface(at)) {
            return false;
        }
        if (!replacingRoadSurface && !isRoadPlacementReplaceable(at)) {
            return false;
        }
        stabilizeRoadFoundation(level, pos, style);
        BlockState below = level.getBlockState(pos.below());
        if (requiresSupportedRoadPlacement(style, below)) {
            return false;
        }
        level.setBlock(pos, style.surface(), Block.UPDATE_ALL);
        return true;
    }

    private static boolean requiresSupportedRoadPlacement(RoadPlacementStyle style, BlockState below) {
        if (style == null) {
            return true;
        }
        if (style.bridge() && !isBridgePierState(style.surface())) {
            return false;
        }
        return below == null || below.isAir() || below.liquid();
    }

    private static boolean canReplaceRoadSurface(BlockState existingState, BlockState plannedState) {
        return existingState != null
                && plannedState != null
                && isRoadSurface(existingState)
                && isRoadSurface(plannedState)
                && !existingState.equals(plannedState);
    }

    private static RoadPlacementStyle selectRoadPlacementStyle(ServerLevel level, BlockPos pos) {
        if (isBridgeSegment(level, pos)) {
            return waterBridgePlacementStyle(Blocks.STONE_BRICKS.defaultBlockState());
        }

        String biomePath = level.getBiome(pos).unwrapKey()
                .map(key -> key.location().getPath())
                .orElse("");
        if (biomePath.contains("desert") || biomePath.contains("badlands") || biomePath.contains("beach")) {
            return landRoadPlacementStyle(Blocks.SMOOTH_SANDSTONE_SLAB.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState());
        }
        if (biomePath.contains("swamp") || biomePath.contains("mangrove") || biomePath.contains("jungle")) {
            return landRoadPlacementStyle(Blocks.MUD_BRICK_SLAB.defaultBlockState(), Blocks.MUD_BRICKS.defaultBlockState());
        }
        return landRoadPlacementStyle(Blocks.STONE_BRICK_SLAB.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
    }

    private static RoadPlacementStyle roadPlacementStyleForState(ServerLevel level, BlockPos pos, BlockState surfaceState) {
        if (surfaceState == null) {
            return selectRoadPlacementStyle(level, pos);
        }
        boolean bridgeContext = isBridgePlacementContext(level, pos);
        if (surfaceState.is(Blocks.STONE_BRICK_SLAB)) {
            return bridgeContext
                ? waterBridgePlacementStyle(surfaceState)
                : landRoadPlacementStyle(surfaceState, Blocks.COBBLESTONE.defaultBlockState());
        }
        if (surfaceState.is(Blocks.STONE_BRICK_STAIRS)) {
            return bridgeContext
                    ? waterBridgePlacementStyle(surfaceState)
                    : landRoadPlacementStyle(surfaceState, Blocks.COBBLESTONE.defaultBlockState());
        }
        if (surfaceState.is(Blocks.STONE_BRICKS)) {
            return bridgeContext
                    ? waterBridgePlacementStyle(surfaceState)
                    : landRoadPlacementStyle(surfaceState, Blocks.COBBLESTONE.defaultBlockState());
        }
        if (surfaceState.is(Blocks.COBBLESTONE_WALL) || surfaceState.is(Blocks.LANTERN)) {
            return bridgeContext
                    ? waterBridgePlacementStyle(surfaceState)
                    : landRoadPlacementStyle(surfaceState, Blocks.COBBLESTONE.defaultBlockState());
        }
        if (surfaceState.is(Blocks.SPRUCE_SLAB)) {
            return new RoadPlacementStyle(
                    surfaceState,
                    Blocks.SPRUCE_FENCE.defaultBlockState(),
                    Blocks.SPRUCE_FENCE.defaultBlockState(),
                    Blocks.SPRUCE_FENCE.defaultBlockState(),
                    true
            );
        }
        if (surfaceState.is(Blocks.SPRUCE_STAIRS)) {
            return new RoadPlacementStyle(
                    surfaceState,
                    Blocks.SPRUCE_FENCE.defaultBlockState(),
                    Blocks.SPRUCE_FENCE.defaultBlockState(),
                    Blocks.SPRUCE_FENCE.defaultBlockState(),
                    true
            );
        }
        if (surfaceState.is(Blocks.SMOOTH_SANDSTONE_SLAB)) {
            return landRoadPlacementStyle(surfaceState, Blocks.SANDSTONE.defaultBlockState());
        }
        if (surfaceState.is(Blocks.SMOOTH_SANDSTONE_STAIRS)) {
            return landRoadPlacementStyle(surfaceState, Blocks.SANDSTONE.defaultBlockState());
        }
        if (surfaceState.is(Blocks.MUD_BRICK_SLAB)) {
            return landRoadPlacementStyle(surfaceState, Blocks.MUD_BRICKS.defaultBlockState());
        }
        if (surfaceState.is(Blocks.MUD_BRICK_STAIRS)) {
            return landRoadPlacementStyle(surfaceState, Blocks.MUD_BRICKS.defaultBlockState());
        }
        return landRoadPlacementStyle(surfaceState, Blocks.COBBLESTONE.defaultBlockState());
    }

    private static RoadPlacementStyle waterBridgePlacementStyle(BlockState surfaceState) {
        return new RoadPlacementStyle(
                surfaceState,
                Blocks.STONE_BRICKS.defaultBlockState(),
                Blocks.COBBLESTONE_WALL.defaultBlockState(),
                Blocks.SPRUCE_FENCE.defaultBlockState(),
                true
        );
    }

    private static RoadPlacementStyle landRoadPlacementStyle(BlockState surfaceState, BlockState supportState) {
        return new RoadPlacementStyle(
                surfaceState,
                supportState,
                supportState,
                Blocks.OAK_FENCE.defaultBlockState(),
                false
        );
    }

    private static boolean isBridgePlacementContext(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        return isBridgeSegment(level, pos)
                || isBridgeSegment(level, pos.above())
                || isBridgeSegment(level, pos.below());
    }

    private static boolean isBridgeSegment(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        BlockState current = level.getBlockState(pos);
        BlockState below = level.getBlockState(pos.below());
        return current.liquid()
                || !current.getFluidState().isEmpty()
                || below.isAir()
                || below.liquid()
                || !below.getFluidState().isEmpty();
    }

    private static void stabilizeRoadFoundation(ServerLevel level, BlockPos pos, RoadPlacementStyle style) {
        if (level == null || pos == null || style == null) {
            return;
        }
        if (style.bridge()) {
            return;
        }
        fillRoadFoundation(level, pos, style.support(), ROAD_FOUNDATION_DEPTH);
    }

    private static void fillRoadFoundation(ServerLevel level, BlockPos pos, BlockState fillState, int maxDepth) {
        if (level == null || pos == null || fillState == null || maxDepth <= 0) {
            return;
        }
        BlockPos cursor = pos.below();
        int depth = 0;
        while (depth < maxDepth && cursor.getY() >= level.getMinBuildHeight()) {
            BlockState state = level.getBlockState(cursor);
            if (!isRoadPlacementReplaceable(state)) {
                return;
            }
            level.setBlock(cursor, fillState, Block.UPDATE_ALL);
            cursor = cursor.below();
            depth++;
        }
    }

    private static boolean isBridgePierState(BlockState state) {
        return state != null
                && (state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.SPRUCE_FENCE));
    }

    private static boolean clearRoadDeckSpace(ServerLevel level, BlockPos pos) {
        return clearRoadBlock(level, pos, true)
                && clearRoadBlock(level, pos.above(), false)
                && clearRoadBlock(level, pos.above(2), false);
    }

    private static boolean clearRoadBlock(ServerLevel level, BlockPos pos, boolean deckBlock) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.liquid() || isRoadSurface(state)) {
            return true;
        }
        boolean safeToRemove = deckBlock ? isRoadPlacementReplaceable(state) : clearanceStateIsSafeToRemove(state);
        if (!safeToRemove) {
            return false;
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }

    private static boolean clearanceStateIsSafeToRemove(BlockState state) {
        if (state == null) {
            return true;
        }
        Block block = state.getBlock();
        return state.isAir()
                || state.canBeReplaced()
                || state.liquid()
                || state.is(BlockTags.LEAVES)
                || block instanceof LeavesBlock
                || block instanceof VineBlock
                || block instanceof SnowLayerBlock
                || state.is(Blocks.VINE)
                || state.is(Blocks.SNOW)
                || state.is(Blocks.GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS)
                || state.is(Blocks.KELP)
                || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.DANDELION)
                || state.is(Blocks.POPPY)
                || state.is(Blocks.BLUE_ORCHID)
                || state.is(Blocks.ALLIUM)
                || state.is(Blocks.AZURE_BLUET)
                || state.is(Blocks.RED_TULIP)
                || state.is(Blocks.ORANGE_TULIP)
                || state.is(Blocks.WHITE_TULIP)
                || state.is(Blocks.PINK_TULIP)
                || state.is(Blocks.OXEYE_DAISY)
                || state.is(Blocks.CORNFLOWER)
                || state.is(Blocks.LILY_OF_THE_VALLEY)
                || state.is(Blocks.SUNFLOWER)
                || state.is(Blocks.LILAC)
                || state.is(Blocks.ROSE_BUSH)
                || state.is(Blocks.PEONY)
                || isNaturalWoodObstacle(state);
    }

    private static boolean isRoadSurface(BlockState state) {
        return state.is(Blocks.STONE_BRICK_SLAB)
                || state.is(Blocks.STONE_BRICK_STAIRS)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.SMOOTH_SANDSTONE_SLAB)
                || state.is(Blocks.SMOOTH_SANDSTONE_STAIRS)
                || state.is(Blocks.MUD_BRICK_SLAB)
                || state.is(Blocks.MUD_BRICK_STAIRS)
                || state.is(Blocks.SPRUCE_SLAB)
                || state.is(Blocks.SPRUCE_STAIRS)
                || state.is(Blocks.SPRUCE_FENCE)
                || state.is(Blocks.COBBLESTONE_WALL)
                || state.is(Blocks.LANTERN)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.MUD_BRICKS);
    }

    private static List<RoadGeometryPlanner.GhostRoadBlock> withLampGhosts(List<RoadGeometryPlanner.GhostRoadBlock> baseGhosts,
                                                                            List<BlockPos> lampBases) {
        LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> merged = new LinkedHashMap<>();
        if (baseGhosts != null) {
            for (RoadGeometryPlanner.GhostRoadBlock block : baseGhosts) {
                if (block != null) {
                    merged.put(block.pos().asLong(), block);
                }
            }
        }
        if (lampBases != null) {
            for (BlockPos base : lampBases) {
                if (base == null) {
                    continue;
                }
                appendGhost(merged, base, Blocks.COBBLESTONE_WALL.defaultBlockState());
                appendGhost(merged, base.above(), Blocks.COBBLESTONE_WALL.defaultBlockState());
                appendGhost(merged, base.above(2), Blocks.LANTERN.defaultBlockState());
            }
        }
        return List.copyOf(merged.values());
    }

    private static List<RoadGeometryPlanner.GhostRoadBlock> decorateNavigableBridgeGhosts(List<RoadGeometryPlanner.GhostRoadBlock> baseGhosts,
                                                                                           List<BlockPos> centerPath,
                                                                                           List<RoadPlacementPlan.BridgeRange> navigableRanges,
                                                                                           List<BlockPos> lampBases,
                                                                                           List<BlockPos> navigableLightPositions) {
        LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> merged = new LinkedHashMap<>();
        if (baseGhosts != null) {
            for (RoadGeometryPlanner.GhostRoadBlock block : baseGhosts) {
                if (block != null) {
                    merged.put(block.pos().asLong(), block);
                }
            }
        }
        appendNavigableBridgeRailings(merged, baseGhosts, centerPath, navigableRanges);
        if (navigableLightPositions != null) {
            for (BlockPos lightPos : navigableLightPositions) {
                if (lightPos == null) {
                    continue;
                }
                appendGhost(merged, lightPos.above(), Blocks.COBBLESTONE_WALL.defaultBlockState());
                appendGhost(merged, lightPos, Blocks.LANTERN.defaultBlockState());
            }
        }
        return withLampGhosts(List.copyOf(merged.values()), lampBases);
    }

    private static void appendNavigableBridgeRailings(LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> merged,
                                                      List<RoadGeometryPlanner.GhostRoadBlock> baseGhosts,
                                                      List<BlockPos> centerPath,
                                                      List<RoadPlacementPlan.BridgeRange> navigableRanges) {
        if (merged == null || baseGhosts == null || centerPath == null || navigableRanges == null || navigableRanges.isEmpty()) {
            return;
        }
        for (RoadPlacementPlan.BridgeRange range : navigableRanges) {
            if (range == null) {
                continue;
            }
            for (int i = Math.max(0, range.startIndex()); i <= Math.min(centerPath.size() - 1, range.endIndex()); i++) {
                BlockPos center = centerPath.get(i);
                BlockPos deckCenter = resolveDeckCenterPos(baseGhosts, center);
                BlockPos previous = centerPath.get(Math.max(0, i - 1));
                BlockPos next = centerPath.get(Math.min(centerPath.size() - 1, i + 1));
                int dx = Integer.compare(next.getX(), previous.getX());
                int dz = Integer.compare(next.getZ(), previous.getZ());
                for (BlockPos railingPos : lateralBridgeOffsets(deckCenter, dx, dz, 4)) {
                    appendGhost(merged, railingPos, Blocks.COBBLESTONE_WALL.defaultBlockState());
                }
            }
        }
    }

    private static BlockPos resolveDeckCenterPos(List<RoadGeometryPlanner.GhostRoadBlock> baseGhosts, BlockPos center) {
        BlockPos best = center.above();
        for (RoadGeometryPlanner.GhostRoadBlock ghost : baseGhosts) {
            if (ghost == null || ghost.pos() == null) {
                continue;
            }
            if (ghost.pos().getX() == center.getX() && ghost.pos().getZ() == center.getZ() && ghost.pos().getY() >= best.getY()) {
                best = ghost.pos();
            }
        }
        return best;
    }

    private static List<BlockPos> lateralBridgeOffsets(BlockPos center, int dx, int dz, int distance) {
        if (dx != 0 && dz == 0) {
            return List.of(center.north(distance), center.south(distance));
        }
        if (dz != 0 && dx == 0) {
            return List.of(center.east(distance), center.west(distance));
        }
        return List.of(center.north(distance), center.south(distance));
    }

    private static void appendGhost(LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> merged,
                                    BlockPos pos,
                                    BlockState state) {
        if (merged == null || pos == null || state == null) {
            return;
        }
        merged.putIfAbsent(pos.asLong(), new RoadGeometryPlanner.GhostRoadBlock(pos, state));
    }

    private static List<RoadGeometryPlanner.RoadBuildStep> toBuildSteps(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        if (ghostBlocks == null || ghostBlocks.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> topRoadYByColumn = new HashMap<>();
        ArrayList<RoadGeometryPlanner.RoadBuildStep> phased = new ArrayList<>(ghostBlocks.size());
        for (RoadGeometryPlanner.GhostRoadBlock block : ghostBlocks) {
            if (block == null || block.pos() == null || block.state() == null || isDecorRoadBuildState(block.state())) {
                continue;
            }
            long key = BlockPos.asLong(block.pos().getX(), 0, block.pos().getZ());
            topRoadYByColumn.merge(key, block.pos().getY(), Math::max);
        }
        for (RoadGeometryPlanner.GhostRoadBlock block : ghostBlocks) {
            if (block == null || block.pos() == null || block.state() == null) {
                continue;
            }
            phased.add(new RoadGeometryPlanner.RoadBuildStep(
                    0,
                    block.pos(),
                    block.state(),
                    classifyRoadBuildPhase(block, topRoadYByColumn)
            ));
        }
        phased.sort(Comparator
                .comparing(RoadGeometryPlanner.RoadBuildStep::phase)
                .thenComparingInt(step -> step.phase() == RoadGeometryPlanner.RoadBuildPhase.SUPPORT
                        ? -step.pos().getY()
                        : step.pos().getY())
                .thenComparingInt(step -> step.pos().getX())
                .thenComparingInt(step -> step.pos().getZ()));

        ArrayList<RoadGeometryPlanner.RoadBuildStep> buildSteps = new ArrayList<>(phased.size());
        for (int i = 0; i < phased.size(); i++) {
            RoadGeometryPlanner.RoadBuildStep step = phased.get(i);
            buildSteps.add(new RoadGeometryPlanner.RoadBuildStep(i, step.pos(), step.state(), step.phase()));
        }
        return List.copyOf(buildSteps);
    }

    private static RoadGeometryPlanner.RoadBuildPhase classifyRoadBuildPhase(RoadGeometryPlanner.GhostRoadBlock block,
                                                                             Map<Long, Integer> topRoadYByColumn) {
        if (block == null || block.pos() == null || block.state() == null) {
            return RoadGeometryPlanner.RoadBuildPhase.DECK;
        }
        if (isDecorRoadBuildState(block.state())) {
            return RoadGeometryPlanner.RoadBuildPhase.DECOR;
        }
        long key = BlockPos.asLong(block.pos().getX(), 0, block.pos().getZ());
        int topRoadY = topRoadYByColumn.getOrDefault(key, block.pos().getY());
        return block.pos().getY() < topRoadY
                ? RoadGeometryPlanner.RoadBuildPhase.SUPPORT
                : RoadGeometryPlanner.RoadBuildPhase.DECK;
    }

    private static boolean isDecorRoadBuildState(BlockState state) {
        return state != null && (state.is(Blocks.COBBLESTONE_WALL) || state.is(Blocks.LANTERN));
    }

    private static RoadPlacementArtifacts buildRoadPlacementArtifacts(ServerLevel level, RoadCorridorPlan corridorPlan) {
        if (level == null) {
            return new RoadPlacementArtifacts(List.of(), List.of(), List.of());
        }
        return buildRoadPlacementArtifacts(
                level,
                corridorPlan,
                pos -> selectRoadPlacementStyle(level, pos),
                ghostBlocks -> deriveTerrainOwnedBlocks(level, ghostBlocks)
        );
    }

    private static RoadPlacementArtifacts buildRoadPlacementArtifacts(ServerLevel level,
                                                                      RoadCorridorPlan corridorPlan,
                                                                      Function<BlockPos, RoadPlacementStyle> styleResolver,
                                                                      Function<List<RoadGeometryPlanner.GhostRoadBlock>, List<BlockPos>> terrainOwnershipResolver) {
        if (!isUsableCorridorPlan(corridorPlan) || styleResolver == null) {
            return new RoadPlacementArtifacts(List.of(), List.of(), List.of());
        }
        LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = new LinkedHashMap<>();
        LinkedHashSet<BlockPos> corridorTerrainOwnership = new LinkedHashSet<>();
        RoadGeometryPlanner.RoadGeometryPlan geometryPlan = RoadGeometryPlanner.plan(
                corridorPlan,
                pos -> {
                    RoadPlacementStyle style = styleResolver.apply(pos);
                    return style == null ? null : style.surface();
                }
        );
        for (RoadGeometryPlanner.GhostRoadBlock block : geometryPlan.ghostBlocks()) {
            ghostBlocks.putIfAbsent(block.pos().asLong(), block);
        }
        for (RoadCorridorPlan.CorridorSlice slice : corridorPlan.slices()) {
            if (slice == null) {
                return new RoadPlacementArtifacts(List.of(), List.of(), List.of());
            }
            RoadPlacementStyle sliceStyle = styleResolver.apply(slice.deckCenter());
            if (sliceStyle == null) {
                return new RoadPlacementArtifacts(List.of(), List.of(), List.of());
            }
            appendCorridorSupportGhosts(
                    ghostBlocks,
                    expandBridgeSupportPositions(level, slice.supportPositions(), sliceStyle.support()),
                    sliceStyle.support()
            );
            appendCorridorSupportGhosts(
                    ghostBlocks,
                    fallbackBridgeHeadSupportPositions(level, corridorPlan, slice, sliceStyle),
                    sliceStyle.support()
            );
            if (sliceStyle.bridge() && sliceStyle.lightSupport().is(Blocks.COBBLESTONE_WALL)) {
                appendBridgeRailingGhosts(ghostBlocks, corridorPlan.centerPath(), slice);
                appendVillageLampGhosts(ghostBlocks, slice.railingLightPositions(), sliceStyle, slice.deckCenter(), true);
                appendVillageLampGhosts(ghostBlocks, slice.pierLightPositions(), sliceStyle, slice.deckCenter(), true);
            } else {
                appendVillageLampGhosts(ghostBlocks, slice.railingLightPositions(), sliceStyle, slice.deckCenter(), false);
                appendVillageLampGhosts(ghostBlocks, slice.pierLightPositions(), sliceStyle, slice.deckCenter(), false);
            }
            corridorTerrainOwnership.addAll(slice.excavationPositions());
            corridorTerrainOwnership.addAll(slice.clearancePositions());
        }
        List<RoadGeometryPlanner.GhostRoadBlock> resolvedGhostBlocks = filterAlreadyPlacedRoadGhostBlocks(
                level,
                List.copyOf(ghostBlocks.values())
        );
        if (terrainOwnershipResolver != null) {
            List<BlockPos> terrainOwnedBlocks = terrainOwnershipResolver.apply(resolvedGhostBlocks);
            if (terrainOwnedBlocks != null) {
                corridorTerrainOwnership.addAll(terrainOwnedBlocks);
            }
        }
        return new RoadPlacementArtifacts(
                resolvedGhostBlocks,
                toBuildSteps(resolvedGhostBlocks),
                collectOwnedRoadBlocks(resolvedGhostBlocks, List.copyOf(corridorTerrainOwnership))
        );
    }

    private static List<RoadGeometryPlanner.GhostRoadBlock> filterAlreadyPlacedRoadGhostBlocks(ServerLevel level,
                                                                                                List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        if (level == null || ghostBlocks == null || ghostBlocks.isEmpty()) {
            return ghostBlocks == null ? List.of() : List.copyOf(ghostBlocks);
        }
        ArrayList<RoadGeometryPlanner.GhostRoadBlock> filtered = new ArrayList<>(ghostBlocks.size());
        for (RoadGeometryPlanner.GhostRoadBlock block : ghostBlocks) {
            if (block == null || block.pos() == null || block.state() == null) {
                continue;
            }
            RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(0, block.pos(), block.state());
            if (!isRoadBuildStepPlaced(level.getBlockState(block.pos()), step)) {
                filtered.add(block);
            }
        }
        return List.copyOf(filtered);
    }

    private static RoadPlacementArtifacts buildRoadPlacementArtifacts(RoadCorridorPlan corridorPlan,
                                                                      Function<BlockPos, RoadPlacementStyle> styleResolver,
                                                                      Function<List<RoadGeometryPlanner.GhostRoadBlock>, List<BlockPos>> terrainOwnershipResolver) {
        return buildRoadPlacementArtifacts(null, corridorPlan, styleResolver, terrainOwnershipResolver);
    }

    private static List<BlockPos> fallbackBridgeHeadSupportPositions(ServerLevel level,
                                                                     RoadCorridorPlan corridorPlan,
                                                                     RoadCorridorPlan.CorridorSlice slice,
                                                                     RoadPlacementStyle sliceStyle) {
        if (level == null
                || corridorPlan == null
                || slice == null
                || sliceStyle == null
                || !sliceStyle.bridge()
                || !isBridgePierState(sliceStyle.support())
                || (slice.segmentKind() != RoadCorridorPlan.SegmentKind.BRIDGE_HEAD
                && slice.segmentKind() != RoadCorridorPlan.SegmentKind.BRIDGE_HEAD_PLATFORM)
                || hasAdjacentSupportSpan(corridorPlan, slice.index())
                || !isBridgeDeckColumn(level, slice.deckCenter())) {
            return List.of();
        }
        return expandBridgeSupportPositions(level, List.of(slice.deckCenter().below()), sliceStyle.support());
    }

    private static boolean hasAdjacentSupportSpan(RoadCorridorPlan corridorPlan, int sliceIndex) {
        if (corridorPlan == null || corridorPlan.slices().isEmpty()) {
            return false;
        }
        if (sliceIndex > 0
                && corridorPlan.slices().get(sliceIndex - 1).segmentKind() == RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN) {
            return true;
        }
        return sliceIndex + 1 < corridorPlan.slices().size()
                && corridorPlan.slices().get(sliceIndex + 1).segmentKind() == RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN;
    }

    private static List<BlockPos> expandBridgeSupportPositions(ServerLevel level,
                                                               List<BlockPos> supportPositions,
                                                               BlockState supportState) {
        if (supportPositions == null || supportPositions.isEmpty()) {
            return List.of();
        }
        if (level == null || !isBridgePierState(supportState)) {
            return supportPositions;
        }
        LinkedHashMap<Long, BlockPos> topAnchors = new LinkedHashMap<>();
        for (BlockPos supportPos : supportPositions) {
            if (supportPos == null) {
                continue;
            }
            long key = RoadCoreExclusion.columnKey(supportPos.getX(), supportPos.getZ());
            BlockPos existing = topAnchors.get(key);
            if (existing == null || supportPos.getY() > existing.getY()) {
                topAnchors.put(key, supportPos.immutable());
            }
        }
        LinkedHashSet<BlockPos> expanded = new LinkedHashSet<>();
        for (BlockPos topAnchor : topAnchors.values()) {
            ArrayList<BlockPos> column = new ArrayList<>();
            BlockPos cursor = topAnchor;
            while (cursor.getY() >= safeMinBuildHeight(level)) {
                BlockState state = level.getBlockState(cursor);
                if (!cursor.equals(topAnchor) && !isRoadPlacementReplaceable(state)) {
                    break;
                }
                column.add(cursor.immutable());
                cursor = cursor.below();
            }
            for (int i = column.size() - 1; i >= 0; i--) {
                expanded.add(column.get(i));
            }
        }
        return expanded.isEmpty() ? supportPositions : List.copyOf(expanded);
    }

    private static RoadPlacementArtifacts filterCoreExcludedArtifacts(RoadPlacementArtifacts artifacts,
                                                                      Set<Long> excludedColumns) {
        if (artifacts == null || excludedColumns == null || excludedColumns.isEmpty()) {
            return artifacts;
        }
        List<RoadGeometryPlanner.GhostRoadBlock> filteredGhostBlocks = filterCoreExcludedGhostBlocks(artifacts.ghostBlocks(), excludedColumns);
        return new RoadPlacementArtifacts(
                filteredGhostBlocks,
                toBuildSteps(filteredGhostBlocks),
                filterCoreExcludedPositions(artifacts.ownedBlocks(), excludedColumns)
        );
    }

    private static List<BlockPos> deriveRoadbedTopFromRoadSurfaceFootprint(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        if (ghostBlocks == null || ghostBlocks.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<Long, BlockPos> highestByColumn = new LinkedHashMap<>();
        for (RoadGeometryPlanner.GhostRoadBlock ghostBlock : ghostBlocks) {
            if (ghostBlock == null || ghostBlock.pos() == null || !isRoadTerrainSurfaceState(ghostBlock.state())) {
                continue;
            }
            BlockPos pos = ghostBlock.pos();
            long columnKey = BlockPos.asLong(pos.getX(), 0, pos.getZ());
            BlockPos current = highestByColumn.get(columnKey);
            if (current == null || pos.getY() > current.getY()) {
                highestByColumn.put(columnKey, pos);
            }
        }
        if (highestByColumn.isEmpty()) {
            return List.of();
        }
        ArrayList<BlockPos> roadbedTop = new ArrayList<>(highestByColumn.size());
        for (BlockPos top : highestByColumn.values()) {
            roadbedTop.add(top.above());
        }
        return List.copyOf(roadbedTop);
    }

    private static boolean isRoadTerrainSurfaceState(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.is(Blocks.STONE_BRICK_SLAB)
                || state.is(Blocks.STONE_BRICK_STAIRS)
                || state.is(Blocks.SMOOTH_SANDSTONE_SLAB)
                || state.is(Blocks.SMOOTH_SANDSTONE_STAIRS)
                || state.is(Blocks.MUD_BRICK_SLAB)
                || state.is(Blocks.MUD_BRICK_STAIRS)
                || state.is(Blocks.SPRUCE_SLAB)
                || state.is(Blocks.SPRUCE_STAIRS);
    }

    private static List<BlockPos> deriveTerrainOwnedBlocks(ServerLevel level,
                                                           List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        if (level == null || ghostBlocks == null || ghostBlocks.isEmpty()) {
            return List.of();
        }
        try {
            List<BlockPos> roadbedTop = deriveRoadbedTopFromRoadSurfaceFootprint(ghostBlocks);
            if (roadbedTop.isEmpty()) {
                return List.of();
            }
            return RoadTerrainShaper.shapeRoadbed(
                            roadbedTop,
                            pos -> level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos).below().getY()
                    ).stream()
                    .map(RoadTerrainShaper.TerrainEdit::pos)
                    .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<BlockPos> captureLiveRoadFoundation(ServerLevel level,
                                                            List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        if (level == null || ghostBlocks == null || ghostBlocks.isEmpty()) {
            return List.of();
        }
        try {
            return captureRoadFoundationFromStateLookup(ghostBlocks, level::getBlockState);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<BlockPos> captureRoadFoundationFromStateLookup(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                                       java.util.function.Function<BlockPos, BlockState> blockStateLookup) {
        if (ghostBlocks == null || ghostBlocks.isEmpty() || blockStateLookup == null) {
            return List.of();
        }
        LinkedHashMap<Long, BlockPos> topByColumn = new LinkedHashMap<>();
        for (RoadGeometryPlanner.GhostRoadBlock ghostBlock : ghostBlocks) {
            if (ghostBlock == null || ghostBlock.pos() == null || !isRoadTerrainSurfaceState(ghostBlock.state())) {
                continue;
            }
            BlockPos pos = ghostBlock.pos();
            long key = BlockPos.asLong(pos.getX(), 0, pos.getZ());
            BlockPos existing = topByColumn.get(key);
            if (existing == null || pos.getY() > existing.getY()) {
                topByColumn.put(key, pos);
            }
        }
        if (topByColumn.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<BlockPos> captured = new LinkedHashSet<>();
        for (BlockPos top : topByColumn.values()) {
            BlockPos cursor = top.below();
            for (int depth = 0; depth < ROAD_FOUNDATION_CAPTURE_DEPTH; depth++) {
                BlockState state = blockStateLookup.apply(cursor);
                if (!isRoadOwnedFoundationState(state)) {
                    break;
                }
                captured.add(cursor);
                cursor = cursor.below();
            }
        }
        return List.copyOf(captured);
    }

    private static boolean isRoadOwnedFoundationState(BlockState state) {
        if (state == null) {
            return false;
        }
        if (isRoadTerrainSurfaceState(state)) {
            return true;
        }
        return state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLESTONE_WALL)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.MUD_BRICKS)
                || state.is(Blocks.SPRUCE_FENCE);
    }

    private static List<BlockPos> collectOwnedRoadBlocks(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                         List<BlockPos> terrainEdits) {
        LinkedHashSet<BlockPos> ownedBlocks = new LinkedHashSet<>();
        if (ghostBlocks != null) {
            for (RoadGeometryPlanner.GhostRoadBlock block : ghostBlocks) {
                if (block != null && block.pos() != null) {
                    ownedBlocks.add(block.pos());
                }
            }
        }
        if (terrainEdits != null) {
            for (BlockPos terrainEdit : terrainEdits) {
                if (terrainEdit != null) {
                    ownedBlocks.add(terrainEdit);
                }
            }
        }
        if (ownedBlocks.isEmpty()) {
            return List.of();
        }
        return List.copyOf(ownedBlocks);
    }

    private static List<RoadGeometryPlanner.GhostRoadBlock> filterCoreExcludedGhostBlocks(
            List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
            Set<Long> excludedColumns) {
        if (ghostBlocks == null || ghostBlocks.isEmpty()) {
            return List.of();
        }
        ArrayList<RoadGeometryPlanner.GhostRoadBlock> filtered = new ArrayList<>(ghostBlocks.size());
        for (RoadGeometryPlanner.GhostRoadBlock block : ghostBlocks) {
            if (block == null || block.pos() == null || RoadCoreExclusion.isExcluded(block.pos(), excludedColumns)) {
                continue;
            }
            filtered.add(block);
        }
        return filtered.isEmpty() ? List.of() : List.copyOf(filtered);
    }

    private static List<BlockPos> filterCoreExcludedPositions(Collection<BlockPos> positions,
                                                              Set<Long> excludedColumns) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        ArrayList<BlockPos> filtered = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            if (pos == null || RoadCoreExclusion.isExcluded(pos, excludedColumns)) {
                continue;
            }
            filtered.add(pos);
        }
        return filtered.isEmpty() ? List.of() : List.copyOf(filtered);
    }

    private static boolean isUsableCorridorPlan(RoadCorridorPlan corridorPlan) {
        if (corridorPlan == null || !corridorPlan.valid() || corridorPlan.centerPath().isEmpty()) {
            return false;
        }
        if (corridorPlan.centerPath().size() != corridorPlan.slices().size()) {
            return false;
        }
        for (int i = 0; i < corridorPlan.slices().size(); i++) {
            RoadCorridorPlan.CorridorSlice slice = corridorPlan.slices().get(i);
            if (slice == null || slice.index() != i) {
                return false;
            }
        }
        return true;
    }

    private static Set<BlockPos> collectRoadSlicePositions(RoadPlacementPlan plan, int index) {
        if (plan == null || plan.centerPath().isEmpty()) {
            return Set.of();
        }
        RoadCorridorPlan corridorPlan = plan.corridorPlan();
        if (!isUsableCorridorPlan(corridorPlan)) {
            return collectRoadSlicePositions(plan.centerPath(), index);
        }
        if (index < 0 || index >= corridorPlan.slices().size()) {
            return Set.of();
        }
        RoadCorridorPlan.CorridorSlice slice = corridorPlan.slices().get(index);
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>(RoadGeometryPlanner.slicePositions(corridorPlan, index));
        positions.addAll(slice.supportPositions());
        appendLightBatchPositions(positions, slice.railingLightPositions());
        appendLightBatchPositions(positions, slice.pierLightPositions());
        return positions;
    }

    private static void appendCorridorSupportGhosts(LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                    List<BlockPos> positions,
                                                    BlockState supportState) {
        if (ghostBlocks == null || positions == null || positions.isEmpty() || supportState == null) {
            return;
        }
        for (BlockPos pos : positions) {
            appendGhost(ghostBlocks, pos, supportState);
        }
    }

    private static void appendVillageLampGhosts(LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                List<BlockPos> positions,
                                                RoadPlacementStyle style,
                                                BlockPos deckCenter,
                                                boolean bridgeLamp) {
        if (ghostBlocks == null || positions == null || positions.isEmpty() || style == null || deckCenter == null) {
            return;
        }
        for (BlockPos lightPos : positions) {
            if (lightPos == null) {
                continue;
            }

            int armX = Integer.compare(lightPos.getX() - deckCenter.getX(), 0);
            int armZ = Integer.compare(lightPos.getZ() - deckCenter.getZ(), 0);
            BlockPos postBase = resolveLampPostBase(ghostBlocks, lightPos, style, bridgeLamp);

            if (style.lightSupport() != null) {
                appendGhost(ghostBlocks, postBase, style.lightSupport());
                appendGhost(ghostBlocks, postBase.above(), style.lightSupport());
            }

            if (armX == 0 && armZ == 0) {
                appendGhost(ghostBlocks, postBase.above(2), Blocks.LANTERN.defaultBlockState());
                continue;
            }

            BlockPos armPos = postBase.above().offset(armX, 0, armZ);
            if (style.lightArm() != null) {
                appendGhost(ghostBlocks, armPos, style.lightArm());
            }
            appendGhost(ghostBlocks, armPos.below(), Blocks.LANTERN.defaultBlockState());
        }
    }

    private static BlockPos resolveLampPostBase(LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                BlockPos lightPos,
                                                RoadPlacementStyle style,
                                                boolean bridgeLamp) {
        if (!bridgeLamp || style.lightSupport() == null || !style.lightSupport().is(Blocks.COBBLESTONE_WALL)) {
            return lightPos.above();
        }
        int railingY = highestGhostYInColumn(ghostBlocks, lightPos.getX(), lightPos.getZ());
        return railingY == Integer.MIN_VALUE
                ? lightPos.above()
                : new BlockPos(lightPos.getX(), railingY, lightPos.getZ());
    }

    private static void appendBridgeRailingGhosts(LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                  List<BlockPos> centerPath,
                                                  RoadCorridorPlan.CorridorSlice slice) {
        if (ghostBlocks == null || centerPath == null || slice == null || slice.surfacePositions().isEmpty()) {
            return;
        }
        for (BlockPos edgePos : bridgeEdgePositions(centerPath, slice)) {
            appendGhost(ghostBlocks, edgePos.above(), Blocks.COBBLESTONE_WALL.defaultBlockState());
        }
    }

    private static int highestGhostYInColumn(LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                             int x,
                                             int z) {
        if (ghostBlocks == null || ghostBlocks.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        int highest = Integer.MIN_VALUE;
        for (RoadGeometryPlanner.GhostRoadBlock ghostBlock : ghostBlocks.values()) {
            if (ghostBlock == null || ghostBlock.pos() == null) {
                continue;
            }
            BlockPos pos = ghostBlock.pos();
            if (pos.getX() == x && pos.getZ() == z && pos.getY() > highest) {
                highest = pos.getY();
            }
        }
        return highest;
    }

    private static int highestGhostYInColumnMatching(LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                     int x,
                                                     int z,
                                                     java.util.function.Predicate<BlockState> matcher) {
        if (ghostBlocks == null || ghostBlocks.isEmpty() || matcher == null) {
            return Integer.MIN_VALUE;
        }
        int highest = Integer.MIN_VALUE;
        for (RoadGeometryPlanner.GhostRoadBlock ghostBlock : ghostBlocks.values()) {
            if (ghostBlock == null || ghostBlock.pos() == null || !matcher.test(ghostBlock.state())) {
                continue;
            }
            BlockPos pos = ghostBlock.pos();
            if (pos.getX() == x && pos.getZ() == z && pos.getY() > highest) {
                highest = pos.getY();
            }
        }
        return highest;
    }

    private static void replaceGhost(LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> merged,
                                     BlockPos pos,
                                     BlockState state) {
        if (merged == null || pos == null || state == null) {
            return;
        }
        merged.put(pos.asLong(), new RoadGeometryPlanner.GhostRoadBlock(pos, state));
    }

    private static void appendLightBatchPositions(Set<BlockPos> positions, List<BlockPos> lightPositions) {
        if (positions == null || lightPositions == null || lightPositions.isEmpty()) {
            return;
        }
        for (BlockPos lightPos : lightPositions) {
            if (lightPos == null) {
                continue;
            }
            positions.add(lightPos);
            positions.add(lightPos.above());
        }
    }

    private static List<BlockPos> bridgeEdgePositions(List<BlockPos> centerPath,
                                                      RoadCorridorPlan.CorridorSlice slice) {
        if (centerPath == null || slice == null || slice.surfacePositions().isEmpty()) {
            return List.of();
        }
        int index = Math.max(0, Math.min(centerPath.size() - 1, slice.index()));
        BlockPos current = centerPath.get(index);
        BlockPos previous = centerPath.get(Math.max(0, index - 1));
        BlockPos next = centerPath.get(Math.min(centerPath.size() - 1, index + 1));
        int dx = Integer.compare(next.getX() - previous.getX(), 0);
        int dz = Integer.compare(next.getZ() - previous.getZ(), 0);
        int normalX = 0;
        int normalZ = 0;
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            normalZ = 1;
        } else if (dz != 0) {
            normalX = 1;
        } else {
            normalX = 1;
        }
        List<BlockPos> left = new ArrayList<>();
        List<BlockPos> right = new ArrayList<>();
        int minProjection = Integer.MAX_VALUE;
        int maxProjection = Integer.MIN_VALUE;
        for (BlockPos pos : slice.surfacePositions()) {
            int projection = ((pos.getX() - slice.deckCenter().getX()) * normalX)
                    + ((pos.getZ() - slice.deckCenter().getZ()) * normalZ);
            minProjection = Math.min(minProjection, projection);
            maxProjection = Math.max(maxProjection, projection);
        }
        for (BlockPos pos : slice.surfacePositions()) {
            int projection = ((pos.getX() - slice.deckCenter().getX()) * normalX)
                    + ((pos.getZ() - slice.deckCenter().getZ()) * normalZ);
            if (projection == minProjection) {
                left.add(pos.immutable());
            }
            if (projection == maxProjection) {
                right.add(pos.immutable());
            }
        }
        LinkedHashSet<BlockPos> edgePositions = new LinkedHashSet<>();
        edgePositions.addAll(left);
        edgePositions.addAll(right);
        return List.copyOf(edgePositions);
    }

    private static boolean isRoadPlacementReplaceable(BlockState state) {
        if (state == null) {
            return true;
        }
        Block block = state.getBlock();
        return state.isAir()
                || state.canBeReplaced()
                || state.liquid()
                || state.is(Blocks.WATER)
                || state.is(Blocks.KELP)
                || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.MUD)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.STONE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.DEEPSLATE)
                || isNaturalWoodObstacle(state)
                || state.is(BlockTags.LEAVES)
                || block instanceof LeavesBlock
                || block instanceof VineBlock
                || block instanceof SnowLayerBlock
                || state.is(Blocks.VINE)
                || state.is(Blocks.SNOW)
                || state.is(Blocks.GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DANDELION)
                || state.is(Blocks.POPPY)
                || state.is(Blocks.BLUE_ORCHID)
                || state.is(Blocks.ALLIUM)
                || state.is(Blocks.AZURE_BLUET)
                || state.is(Blocks.RED_TULIP)
                || state.is(Blocks.ORANGE_TULIP)
                || state.is(Blocks.WHITE_TULIP)
                || state.is(Blocks.PINK_TULIP)
                || state.is(Blocks.OXEYE_DAISY)
                || state.is(Blocks.CORNFLOWER)
                || state.is(Blocks.LILY_OF_THE_VALLEY)
                || state.is(Blocks.TORCHFLOWER)
                || state.is(Blocks.WITHER_ROSE);
    }

    private static boolean isNaturalWoodObstacle(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.is(BlockTags.LOGS)) {
            return true;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.endsWith("_log")
                || path.endsWith("_wood")
                || path.endsWith("_stem")
                || path.endsWith("_hyphae");
    }

    private static boolean isRoadPlacementReplaceableForTest(BlockState state) {
        return isRoadPlacementReplaceable(state);
    }

    static boolean clearanceStateIsSafeToRemoveForTest(BlockState state) {
        return clearanceStateIsSafeToRemove(state);
    }

    static TestRoadPlacementResult placeRoadColumnForTest(BlockState surfaceState,
                                                          BlockState belowState,
                                                          BlockState aboveState,
                                                          BlockState plannedSurfaceState) {
        if (plannedSurfaceState == null || belowState == null || belowState.isAir() || belowState.liquid()) {
            return new TestRoadPlacementResult(surfaceState, Integer.MIN_VALUE);
        }
        if (aboveState != null && !aboveState.isAir() && !aboveState.liquid() && !clearanceStateIsSafeToRemove(aboveState)) {
            return new TestRoadPlacementResult(surfaceState, Integer.MIN_VALUE);
        }
        if (surfaceState != null && !surfaceState.isAir() && !surfaceState.liquid()
                && !isRoadPlacementReplaceable(surfaceState) && !isRoadSurface(surfaceState)) {
            return new TestRoadPlacementResult(surfaceState, Integer.MIN_VALUE);
        }
        return new TestRoadPlacementResult(plannedSurfaceState, 63);
    }

    private static void clearRoad(ServerLevel level, RoadNetworkRecord road, Set<BlockPos> preservedCoverage) {
        for (BlockPos pos : collectRoadPlacementPositions(road.path())) {
            if (!preservedCoverage.contains(pos)) {
                removeRoadAt(level, pos);
            }
        }
    }

    static RoadPlacementPlan findActiveRoadPlacementPlan(ServerLevel level, String roadId) {
        if (level == null || roadId == null || roadId.isBlank()) {
            return null;
        }
        RoadConstructionJob job = ACTIVE_ROAD_CONSTRUCTIONS.get(roadId);
        if (job == null || job.level != level) {
            return null;
        }
        return job.plan;
    }

    static RoadPlacementPlan findRoadPlacementPlan(ServerLevel level, String roadId) {
        if (level == null || roadId == null || roadId.isBlank()) {
            return null;
        }
        RoadPlacementPlan activePlan = findActiveRoadPlacementPlan(level, roadId);
        if (activePlan != null) {
            return activePlan;
        }
        RoadNetworkRecord road = NationSavedData.get(level).getRoadNetwork(roadId);
        return road == null ? null : createRoadPlacementPlan(level, road);
    }

    static RoadPlacementPlan createRoadPlacementPlanForTest(List<BlockPos> centerPath,
                                                            List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                            List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges) {
        List<BlockPos> safePath = centerPath == null ? List.of() : List.copyOf(centerPath);
        List<RoadPlacementPlan.BridgeRange> safeBridgeRanges = bridgeRanges == null ? List.of() : bridgeRanges;
        List<RoadPlacementPlan.BridgeRange> safeNavigableRanges = navigableWaterBridgeRanges == null ? List.of() : navigableWaterBridgeRanges;
        List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans = new ArrayList<>(safeBridgeRanges.size());
        for (RoadPlacementPlan.BridgeRange range : safeBridgeRanges) {
            if (range == null) {
                continue;
            }
            bridgePlans.add(RoadBridgePlanner.planBridgeSpan(
                    safePath,
                    range,
                    index -> rangeContains(safeBridgeRanges, index),
                    index -> rangeContains(safeNavigableRanges, index),
                    index -> Math.max(0, safePath.get(index).getY() - 3),
                    index -> Math.max(0, safePath.get(index).getY() - 4),
                    index -> true
            ));
        }
        int[] placementHeights = surfaceReplacementPlacementHeights(
                safePath,
                RoadGeometryPlanner.buildPlacementHeightProfileFromSpanPlans(safePath, bridgePlans),
                safeBridgeRanges
        );
        RoadCorridorPlan corridorPlan = RoadCorridorPlanner.plan(
                safePath,
                bridgePlans,
                placementHeights
        );
        LinkedHashMap<Long, RoadPlacementStyle> testStyleByPos = new LinkedHashMap<>();
        for (RoadCorridorPlan.CorridorSlice slice : corridorPlan.slices()) {
            if (slice == null) {
                continue;
            }
            RoadPlacementStyle sliceStyle = slice.segmentKind() == RoadCorridorPlan.SegmentKind.LAND_APPROACH
                    ? landRoadPlacementStyle(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState())
                    : waterBridgePlacementStyle(Blocks.STONE_BRICKS.defaultBlockState());
            mergeTestPlacementStyle(testStyleByPos, slice.deckCenter(), sliceStyle);
            for (BlockPos surfacePos : slice.surfacePositions()) {
                mergeTestPlacementStyle(testStyleByPos, surfacePos, sliceStyle);
            }
        }
        RoadPlacementArtifacts artifacts = buildRoadPlacementArtifacts(
                null,
                corridorPlan,
                pos -> testStyleByPos.getOrDefault(
                        pos.asLong(),
                        landRoadPlacementStyle(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState())
                ),
                ghostBlocks -> List.of()
        );
        BlockPos start = safePath.isEmpty() ? null : safePath.get(0);
        BlockPos end = safePath.isEmpty() ? null : safePath.get(safePath.size() - 1);
        return new RoadPlacementPlan(
                safePath,
                start,
                start,
                end,
                end,
                artifacts.ghostBlocks(),
                artifacts.buildSteps(),
                safeBridgeRanges,
                safeNavigableRanges,
                artifacts.ownedBlocks(),
                null,
                null,
                null,
                corridorPlan
        );
    }

    private static boolean rangeContains(List<RoadPlacementPlan.BridgeRange> ranges, int index) {
        if (ranges == null || ranges.isEmpty()) {
            return false;
        }
        for (RoadPlacementPlan.BridgeRange range : ranges) {
            if (range != null && index >= range.startIndex() && index <= range.endIndex()) {
                return true;
            }
        }
        return false;
    }

    private static void mergeTestPlacementStyle(Map<Long, RoadPlacementStyle> styleByPos,
                                                BlockPos pos,
                                                RoadPlacementStyle candidate) {
        if (styleByPos == null || pos == null || candidate == null) {
            return;
        }
        long key = pos.asLong();
        RoadPlacementStyle existing = styleByPos.get(key);
        if (existing == null || (candidate.bridge() && !existing.bridge())) {
            styleByPos.put(key, candidate);
        }
    }

    static Map<String, List<BlockPos>> snapshotRoadOwnedBlocks(ServerLevel level) {
        if (level == null) {
            return Map.of();
        }
        LinkedHashMap<String, List<BlockPos>> ownership = new LinkedHashMap<>();
        for (Map.Entry<String, RoadConstructionJob> entry : ACTIVE_ROAD_CONSTRUCTIONS.entrySet()) {
            RoadConstructionJob job = entry.getValue();
            if (job != null && job.level == level && job.roadId != null && !job.roadId.isBlank()) {
                ownership.put(job.roadId, roadOwnedBlocks(level, job.plan));
            }
        }
        NationSavedData data = safeNationSavedData(level);
        if (data == null) {
            return Map.copyOf(ownership);
        }
        for (RoadNetworkRecord road : data.getRoadNetworks()) {
            if (road == null || road.roadId().isBlank() || ownership.containsKey(road.roadId())) {
                continue;
            }
            ownership.put(road.roadId(), roadOwnedBlocks(level, createRoadPlacementPlan(level, road)));
        }
        return Map.copyOf(ownership);
    }

    static boolean cancelActiveRoadConstruction(ServerLevel level, String roadId) {
        if (level == null || roadId == null || roadId.isBlank()) {
            return false;
        }
        RoadConstructionJob job = ACTIVE_ROAD_CONSTRUCTIONS.get(roadId);
        if (job == null || job.level != level) {
            return false;
        }
        return startRoadRollback(level, new RoadConstructionJob(
                job.level,
                job.roadId,
                job.ownerUuid,
                job.townId,
                job.nationId,
                job.sourceTownName,
                job.targetTownName,
                job.plan,
                job.rollbackStates,
                findRoadPlacedStepCount(level, job.plan),
                job.rollbackActionIndex,
                true,
                Math.max(0, job.rollbackActionIndex),
                true,
                job.attemptedStepKeys
        ));
    }

    static boolean demolishRoadById(ServerLevel level, String roadId) {
        if (level == null || roadId == null || roadId.isBlank()) {
            return false;
        }
        RoadConstructionJob activeJob = ACTIVE_ROAD_CONSTRUCTIONS.get(roadId);
        if (activeJob != null && activeJob.level == level) {
            return startRoadRollback(level, new RoadConstructionJob(
                    activeJob.level,
                    activeJob.roadId,
                    activeJob.ownerUuid,
                    activeJob.townId,
                    activeJob.nationId,
                    activeJob.sourceTownName,
                    activeJob.targetTownName,
                    activeJob.plan,
                    activeJob.rollbackStates,
                    findRoadPlacedStepCount(level, activeJob.plan),
                    activeJob.rollbackActionIndex,
                    true,
                    Math.max(0, activeJob.rollbackActionIndex),
                    true,
                    activeJob.attemptedStepKeys
            ));
        }

        ConstructionRuntimeSavedData.RoadJobState persistedState = findPersistedRoadJob(level, roadId);
        RoadPlacementPlan plan;
        List<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> rollbackStates;
        UUID ownerUuid = null;
        String townId = "";
        String nationId = "";
        String sourceTownName = "-";
        String targetTownName = "-";
        Set<Long> attemptedStepKeys = Set.of();
        RoadNetworkRecord road = NationSavedData.get(level).getRoadNetwork(roadId);
        if (persistedState != null) {
            RestoredRoadRuntime restoredRoad = restorePersistedRoadRuntime(level, persistedState);
            if (!restoredRoad.success()) {
                return false;
            }
            plan = restoredRoad.plan();
            rollbackStates = persistedState.rollbackStates();
            attemptedStepKeys = restoredRoad.attemptedStepKeys();
            ownerUuid = parseUuid(persistedState.ownerUuid());
            if (road != null) {
                townId = road.townId();
                nationId = road.nationId();
                String[] resolvedNames = resolveRoadTownNames(level, road);
                sourceTownName = resolvedNames[0];
                targetTownName = resolvedNames[1];
            }
        } else {
            plan = findRoadPlacementPlan(level, roadId);
            if (plan == null) {
                return false;
            }
            rollbackStates = captureRoadRollbackStates(level, plan);
        }
        return startRoadRollback(level, new RoadConstructionJob(
                level,
                roadId,
                ownerUuid,
                townId,
                nationId,
                sourceTownName,
                targetTownName,
                plan,
                rollbackStates,
                findRoadPlacedStepCount(level, plan),
                0.0D,
                true,
                0,
                true,
                attemptedStepKeys
        ));
    }

    private static boolean startRoadRollback(ServerLevel level, RoadConstructionJob job) {
        if (level == null || job == null || job.roadId == null || job.roadId.isBlank() || job.plan == null) {
            return false;
        }
        RoadConstructionJob rollbackJob = refreshRoadConstructionState(level, job);
        rollbackJob = new RoadConstructionJob(
                rollbackJob.level,
                rollbackJob.roadId,
                rollbackJob.ownerUuid,
                rollbackJob.townId,
                rollbackJob.nationId,
                rollbackJob.sourceTownName,
                rollbackJob.targetTownName,
                rollbackJob.plan,
                rollbackJob.rollbackStates,
                rollbackJob.placedStepCount,
                Math.max(rollbackJob.rollbackActionIndex, 0),
                true,
                Math.max(rollbackJob.rollbackActionIndex, 0),
                job.removeRoadNetworkOnComplete,
                rollbackJob.attemptedStepKeys
        );
        ACTIVE_ROAD_HAMMER_CREDITS.remove(rollbackJob.roadId);
        ACTIVE_ROAD_CONSTRUCTIONS.put(rollbackJob.roadId, rollbackJob);
        persistRoadConstruction(level, rollbackJob.roadId, rollbackJob.ownerUuid, rollbackJob.plan, rollbackJob.rollbackStates, rollbackJob.placedStepCount, rollbackJob.progressSteps, true, rollbackJob.rollbackActionIndex, rollbackJob.removeRoadNetworkOnComplete, rollbackJob.attemptedStepKeys);
        return true;
    }

    private static void clearActiveRoadRuntimeState(String roadId) {
        if (roadId == null || roadId.isBlank()) {
            return;
        }
        ACTIVE_ROAD_CONSTRUCTIONS.remove(roadId);
        ACTIVE_ROAD_WORKERS.remove(roadId);
        ACTIVE_ROAD_HAMMER_CREDITS.remove(roadId);
    }

    private static List<BlockPos> roadOwnedBlocks(ServerLevel level, RoadPlacementPlan plan) {
        if (plan == null) {
            return List.of();
        }
        LinkedHashSet<BlockPos> resolved = new LinkedHashSet<>();
        if (!plan.ownedBlocks().isEmpty()) {
            resolved.addAll(plan.ownedBlocks());
        }
        resolved.addAll(collectCorridorOwnedBlocks(plan.corridorPlan()));
        resolved.addAll(collectOwnedRoadBlocks(plan.ghostBlocks(), List.of()));
        for (RoadGeometryPlanner.RoadBuildStep step : plan.buildSteps()) {
            if (step != null && step.pos() != null) {
                resolved.add(step.pos());
            }
        }
        if (level != null) {
            resolved.addAll(deriveTerrainOwnedBlocks(level, plan.ghostBlocks()));
            resolved.addAll(captureLiveRoadFoundation(level, plan.ghostBlocks()));
            return filterCoreExcludedPositions(List.copyOf(resolved), collectCoreExclusionColumns(level));
        }
        return resolved.isEmpty() ? List.of() : List.copyOf(resolved);
    }

    private static Set<Long> collectCoreExclusionColumns(ServerLevel level) {
        if (level == null) {
            return Set.of();
        }
        NationSavedData data = NationSavedData.get(level);
        if (data == null) {
            return Set.of();
        }
        String dimensionId = safeDimensionId(level);
        if (dimensionId.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<BlockPos> cores = new LinkedHashSet<>();
        for (TownRecord town : data.getTowns()) {
            if (town != null && town.hasCore() && dimensionId.equalsIgnoreCase(town.coreDimension())) {
                cores.add(BlockPos.of(town.corePos()));
            }
        }
        for (NationRecord nation : data.getNations()) {
            if (nation != null && nation.hasCore() && dimensionId.equalsIgnoreCase(nation.coreDimension())) {
                cores.add(BlockPos.of(nation.corePos()));
            }
        }
        return RoadCoreExclusion.collectExcludedColumns(cores, RoadCoreExclusion.DEFAULT_RADIUS);
    }

    static List<BlockPos> filterCoreExcludedPositionsForTest(List<BlockPos> positions,
                                                             List<BlockPos> townCores,
                                                             List<BlockPos> nationCores) {
        LinkedHashSet<BlockPos> cores = new LinkedHashSet<>();
        if (townCores != null) {
            cores.addAll(townCores);
        }
        if (nationCores != null) {
            cores.addAll(nationCores);
        }
        return filterCoreExcludedPositions(
                positions,
                RoadCoreExclusion.collectExcludedColumns(cores, RoadCoreExclusion.DEFAULT_RADIUS)
        );
    }

    private static String safeDimensionId(ServerLevel level) {
        if (level == null) {
            return "";
        }
        try {
            return level.dimension() == null ? "" : level.dimension().location().toString();
        } catch (NullPointerException ignored) {
            return "";
        }
    }

    private static List<BlockPos> collectCorridorOwnedBlocks(RoadCorridorPlan corridorPlan) {
        if (!isUsableCorridorPlan(corridorPlan)) {
            return List.of();
        }
        LinkedHashSet<BlockPos> owned = new LinkedHashSet<>();
        for (RoadCorridorPlan.CorridorSlice slice : corridorPlan.slices()) {
            if (slice == null) {
                continue;
            }
            owned.addAll(slice.surfacePositions());
            owned.addAll(slice.excavationPositions());
            owned.addAll(slice.clearancePositions());
            owned.addAll(slice.supportPositions());
            appendLightBatchPositions(owned, slice.railingLightPositions());
            appendLightBatchPositions(owned, slice.pierLightPositions());
        }
        return owned.isEmpty() ? List.of() : List.copyOf(owned);
    }

    private static List<BlockPos> roadRollbackTrackedPositions(ServerLevel level, RoadPlacementPlan plan) {
        if (plan == null) {
            return List.of();
        }
        LinkedHashSet<BlockPos> tracked = new LinkedHashSet<>(roadOwnedBlocks(level, plan));
        for (RoadGeometryPlanner.RoadBuildStep step : plan.buildSteps()) {
            if (step == null || step.pos() == null) {
                continue;
            }
            tracked.add(step.pos().above());
        }
        return tracked.isEmpty() ? List.of() : List.copyOf(tracked);
    }

    private static List<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> captureRoadRollbackStates(ServerLevel level,
                                                                                                                      RoadPlacementPlan plan) {
        if (level == null || plan == null) {
            return List.of();
        }
        return captureRoadRollbackStatesFromStateLookup(roadRollbackTrackedPositions(level, plan), level::getBlockState);
    }

    private static List<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> captureRoadRollbackStatesFromStateLookup(
            List<BlockPos> positions,
            Function<BlockPos, BlockState> blockStateLookup) {
        if (positions == null || positions.isEmpty() || blockStateLookup == null) {
            return List.of();
        }
        ArrayList<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> snapshots = new ArrayList<>(positions.size());
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        for (BlockPos pos : positions) {
            if (pos == null || !seen.add(pos.asLong())) {
                continue;
            }
            BlockState state = blockStateLookup.apply(pos);
            if (state == null) {
                continue;
            }
            snapshots.add(new ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState(
                    pos.asLong(),
                    NbtUtils.writeBlockState(state)
            ));
        }
        return snapshots.isEmpty() ? List.of() : List.copyOf(snapshots);
    }

    private static void restoreRoadRollbackStates(ServerLevel level,
                                                  List<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> rollbackStates) {
        if (level == null || rollbackStates == null || rollbackStates.isEmpty()) {
            return;
        }
        restoreRoadRollbackStatesWithSetter(rollbackStates, (pos, state) -> {
            if (pos == null || state == null) {
                return;
            }
            level.setBlock(pos, state, Block.UPDATE_ALL);
        }, snapshot -> deserializeBlockState(level, snapshot.statePayload()));
    }

    private static void restoreRoadRollbackStatesWithSetter(
            List<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> rollbackStates,
            java.util.function.BiConsumer<BlockPos, BlockState> stateSetter) {
        restoreRoadRollbackStatesWithSetter(rollbackStates, stateSetter, snapshot -> snapshot == null ? null : deserializeBlockStatePayload(snapshot.statePayload()));
    }

    private static void restoreRoadRollbackStatesWithSetter(
            List<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> rollbackStates,
            java.util.function.BiConsumer<BlockPos, BlockState> stateSetter,
            Function<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState, BlockState> stateResolver) {
        if (rollbackStates == null || rollbackStates.isEmpty() || stateSetter == null || stateResolver == null) {
            return;
        }
        for (ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState rollbackState : rollbackStates) {
            if (rollbackState == null) {
                continue;
            }
            BlockState state = stateResolver.apply(rollbackState);
            if (state == null) {
                continue;
            }
            stateSetter.accept(BlockPos.of(rollbackState.pos()), state);
        }
    }

    private static void removeOwnedRoadBlocks(ServerLevel level, RoadPlacementPlan plan) {
        if (level == null || plan == null) {
            return;
        }
        for (BlockPos pos : RoadLifecycleService.removalOrder(roadOwnedBlocks(level, plan))) {
            if (pos != null && !level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static NationSavedData safeNationSavedData(ServerLevel level) {
        if (level == null) {
            return null;
        }
        try {
            return NationSavedData.get(level);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void removeRoadAt(ServerLevel level, BlockPos pos) {
        if (isRoadSurface(level.getBlockState(pos))) {
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    public static PreviewRoadHint estimatePreviewRoad(Level level, BlockPos origin, StructureType type, int rotation) {
        if (level == null || origin == null || type == null) {
            return new PreviewRoadHint(List.of());
        }

        List<RoadAnchor> anchors = getPreviewRoadAnchors(origin, type, rotation);
        if (anchors.isEmpty()) {
            return new PreviewRoadHint(List.of());
        }

        List<PreviewRoadTarget> targets = collectPreviewRoadTargets(level, origin, type, rotation);
        if (targets.isEmpty()) {
            return new PreviewRoadHint(List.of());
        }

        record ScoredConnection(PreviewRoadConnection connection, double score) {}
        List<ScoredConnection> scored = new ArrayList<>();

        for (RoadAnchor anchor : anchors) {
            for (PreviewRoadTarget target : targets) {
                if (anchor.pos().distSqr(target.pos()) > ROAD_CONNECT_RANGE_SQR) {
                    continue;
                }
                List<BlockPos> path = resolvePreviewRoadPath(level, anchor.pos(), target.pos());
                if (path.size() < 2) {
                    continue;
                }
                PreviewRoadConnection connection = new PreviewRoadConnection(path, target.kind(), target.pos());
                double score = previewConnectionScore(connection, anchor.pos().distSqr(target.pos()) * 0.02D, anchor.side() == primaryRoadSide(rotation));
                scored.add(new ScoredConnection(connection, score));
            }
        }

        if (scored.isEmpty()) {
            return new PreviewRoadHint(List.of());
        }

        List<PreviewRoadConnection> chosen = new ArrayList<>();
        Set<Long> usedTargets = new HashSet<>();
        scored.stream()
                .sorted(Comparator.comparingDouble(ScoredConnection::score))
                .forEach(candidate -> {
                    if (chosen.size() >= PREVIEW_ROAD_CONNECTION_LIMIT) {
                        return;
                    }
                    BlockPos targetPos = candidate.connection().targetPos();
                    long key = targetPos == null ? Long.MIN_VALUE : targetPos.asLong();
                    if (!usedTargets.add(key)) {
                        return;
                    }
                    chosen.add(candidate.connection());
                });

        return new PreviewRoadHint(chosen);
    }

    private static List<BlockPos> resolvePreviewRoadPath(Level level, BlockPos start, BlockPos end) {
        if (!(level instanceof ServerLevel serverLevel) || start == null || end == null) {
            return findPathWithSnapshot(level, start, end, false);
        }

        List<RoadNetworkRecord> roads = List.copyOf(NationSavedData.get(serverLevel).getRoadNetworks());
        Set<BlockPos> networkNodes = RoadHybridRouteResolver.collectNetworkNodes(roads);
        Map<BlockPos, Set<BlockPos>> adjacency = RoadHybridRouteResolver.collectNetworkAdjacency(roads);
        SegmentedRoadPathOrchestrator.OrchestratedPath orchestrated = SegmentedRoadPathOrchestrator.plan(
                start,
                end,
                collectSegmentAnchors(serverLevel, start, end, networkNodes),
                request -> new SegmentedRoadPathOrchestrator.SegmentPlan(
                        resolveHybridRoadSegment(serverLevel, request.from(), request.to(), networkNodes, adjacency),
                        SegmentedRoadPathOrchestrator.FailureReason.SEARCH_EXHAUSTED
                ),
                request -> shouldSubdivideSegment(request.from(), request.to())
        );
        return orchestrated.success() ? orchestrated.path() : findPathWithSnapshot(level, start, end, false);
    }

    private static List<BlockPos> resolveHybridRoadSegment(ServerLevel level,
                                                           BlockPos start,
                                                           BlockPos end,
                                                           Set<BlockPos> networkNodes,
                                                           Map<BlockPos, Set<BlockPos>> adjacency) {
        RoadHybridRouteResolver.HybridRoute route = RoadHybridRouteResolver.resolveCandidates(
                List.of(start),
                List.of(end),
                networkNodes,
                adjacency,
                (from, to, allowWaterFallback) -> {
                    List<BlockPos> path = findPathWithSnapshot(level, from, to, allowWaterFallback);
                    return RoadHybridRouteResolver.summarizePath(level, path, allowWaterFallback);
                }
        );
        return route.fullPath().size() >= 2 ? route.fullPath() : List.of();
    }

    private static List<BlockPos> collectSegmentAnchors(ServerLevel level,
                                                        BlockPos start,
                                                        BlockPos end,
                                                        Set<BlockPos> networkNodes) {
        LinkedHashSet<BlockPos> merged = new LinkedHashSet<>();
        merged.addAll(SegmentedRoadPathOrchestrator.collectIntermediateAnchors(
                start,
                end,
                networkNodes == null ? List.of() : List.copyOf(networkNodes),
                MAX_SEGMENT_INTERMEDIATE_ANCHORS,
                NETWORK_ANCHOR_CORRIDOR_DISTANCE
        ));
        // Keep bridge structure decisions downstream of routing so preview segmentation does not force bridge-deck anchors.
        return List.copyOf(merged);
    }

    private static boolean shouldSubdivideSegment(BlockPos from, BlockPos to) {
        return from != null && to != null && from.distManhattan(to) > SEGMENT_SUBDIVIDE_MANHATTAN;
    }

    private static List<BlockPos> findPathWithSnapshot(Level level,
                                                       BlockPos start,
                                                       BlockPos end,
                                                       boolean allowWaterFallback) {
        if (level == null || start == null || end == null) {
            return List.of();
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return RoadPathfinder.findPath(level, start, end, Set.of(), allowWaterFallback);
        }
        RoadPlanningSnapshot snapshot = RoadPlanningSnapshotBuilder.build(serverLevel, start, end, Set.of(), Set.of());
        return RoadPathfinder.findPath(serverLevel, start, end, Set.of(), Set.of(), allowWaterFallback, snapshot);
    }

    private static double previewConnectionScore(PreviewRoadConnection connection, double distancePenalty, boolean primarySide) {
        if (connection == null) {
            return Double.MAX_VALUE;
        }
        double score = connection.path().size() + distancePenalty;
        if (connection.targetKind() == PreviewRoadTargetKind.ROAD) {
            score -= 2.5D;
        }
        if (primarySide) {
            score -= 1.0D;
        }
        return score;
    }

    static PreviewRoadConnection choosePreviewConnectionForTest(List<PreviewRoadConnection> connections, int rotation) {
        return (connections == null ? List.<PreviewRoadConnection>of() : connections).stream()
                .min(Comparator.comparingDouble(connection -> previewConnectionScore(
                        connection,
                        0.0D,
                        false
                )))
                .orElseThrow();
    }

    static boolean usesAsyncRoadPlanningForTest() {
        return true;
    }

    private static List<PreviewRoadTarget> collectPreviewRoadTargets(Level level, BlockPos origin, StructureType type, int rotation) {
        Map<Long, PreviewRoadTarget> targets = new LinkedHashMap<>();
        int half = PREVIEW_ROAD_SEARCH_RADIUS;
        BlockPos min = previewMin(origin, type, rotation);
        BlockPos max = previewMax(origin, type, rotation);

        for (int x = origin.getX() - half; x <= origin.getX() + half; x++) {
            for (int z = origin.getZ() - half; z <= origin.getZ() + half; z++) {
                BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z)).below();
                if (isInsidePreviewFootprint(surface, min, max)) {
                    continue;
                }
                if (level.getBlockState(surface.above()).is(net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB)) {
                    targets.putIfAbsent(surface.asLong(), new PreviewRoadTarget(surface, PreviewRoadTargetKind.ROAD));
                }
                for (int yOffset = 0; yOffset <= 3; yOffset++) {
                    BlockPos checkPos = surface.above(yOffset);
                    if (isFunctionalRoadTarget(level.getBlockState(checkPos))) {
                        targets.putIfAbsent(surface.asLong(), new PreviewRoadTarget(surface, PreviewRoadTargetKind.STRUCTURE));
                        break;
                    }
                }
            }
        }

        return targets.values().stream()
                .sorted(Comparator.comparingDouble(target -> target.pos().distSqr(origin)))
                .limit(PREVIEW_ROAD_CANDIDATE_LIMIT)
                .toList();
    }

    private static boolean isFunctionalRoadTarget(BlockState state) {
        return state.is(ModBlocks.BANK_BLOCK.get())
                || state.is(ModBlocks.TOWN_CORE_BLOCK.get())
                || state.is(ModBlocks.NATION_CORE_BLOCK.get())
                || state.is(ModBlocks.MARKET_BLOCK.get())
                || state.is(ModBlocks.DOCK_BLOCK.get())
                || state.is(ModBlocks.COTTAGE_BLOCK.get())
                || state.is(ModBlocks.BAR_BLOCK.get())
                || state.is(ModBlocks.SCHOOL_BLOCK.get());
    }

    private static boolean isInsidePreviewFootprint(BlockPos pos, BlockPos min, BlockPos max) {
        return pos.getX() >= min.getX() - 1
                && pos.getX() <= max.getX() + 1
                && pos.getZ() >= min.getZ() - 1
                && pos.getZ() <= max.getZ() + 1;
    }

    private static List<RoadAnchor> getPreviewRoadAnchors(BlockPos origin, StructureType type, int rotation) {
        List<RoadAnchor> anchors = new ArrayList<>();
        Direction front = primaryRoadSide(rotation);
        addPreviewRoadAnchors(anchors, origin, rotatedWidth(type, rotation), rotatedDepth(type, rotation), front);
        addPreviewRoadAnchors(anchors, origin, rotatedWidth(type, rotation), rotatedDepth(type, rotation), front.getClockWise());
        addPreviewRoadAnchors(anchors, origin, rotatedWidth(type, rotation), rotatedDepth(type, rotation), front.getCounterClockWise());
        return anchors;
    }

    private static void addPreviewRoadAnchors(List<RoadAnchor> anchors,
                                              BlockPos origin,
                                              int width,
                                              int depth,
                                              Direction side) {
        int[] offsets = side == Direction.NORTH || side == Direction.SOUTH
                ? buildSideOffsets(width)
                : buildSideOffsets(depth);
        for (int offset : offsets) {
            BlockPos pos = switch (side) {
                case NORTH -> origin.offset(clampRoadOffset(width / 2 + offset, width), 0, -1);
                case SOUTH -> origin.offset(clampRoadOffset(width / 2 + offset, width), 0, depth);
                case EAST -> origin.offset(width, 0, clampRoadOffset(depth / 2 + offset, depth));
                case WEST -> origin.offset(-1, 0, clampRoadOffset(depth / 2 + offset, depth));
                default -> origin;
            };
            if (anchors.stream().noneMatch(existing -> existing.pos().equals(pos))) {
                anchors.add(new RoadAnchor(pos, side));
            }
        }
    }

    private static int rotatedWidth(StructureType type, int rotation) {
        return (Math.floorMod(rotation, 4) == 1 || Math.floorMod(rotation, 4) == 3) ? type.d() : type.w();
    }

    private static int rotatedDepth(StructureType type, int rotation) {
        return (Math.floorMod(rotation, 4) == 1 || Math.floorMod(rotation, 4) == 3) ? type.w() : type.d();
    }

    private static BlockPos previewMin(BlockPos origin, StructureType type, int rotation) {
        return origin;
    }

    private static BlockPos previewMax(BlockPos origin, StructureType type, int rotation) {
        return origin.offset(rotatedWidth(type, rotation) - 1, type.h() - 1, rotatedDepth(type, rotation) - 1);
    }

    private static boolean isConnected(DisjointSet set, String left, String right) {
        return findRoot(set, left).equals(findRoot(set, right));
    }

    private static void union(DisjointSet set, String left, String right) {
        String leftRoot = findRoot(set, left);
        String rightRoot = findRoot(set, right);
        if (!leftRoot.equals(rightRoot)) {
            set.parent().put(leftRoot, rightRoot);
        }
    }

    private static String findRoot(DisjointSet set, String id) {
        String current = set.parent().get(id);
        if (current == null) {
            set.parent().put(id, id);
            return id;
        }
        if (current.equals(id)) {
            return current;
        }
        String root = findRoot(set, current);
        set.parent().put(id, root);
        return root;
    }

    private static boolean isAreaClear(ServerLevel level, List<StructureTemplate.StructureBlockInfo> blocks) {
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            BlockState current = level.getBlockState(info.pos());
            if (current.equals(info.state())) {
                continue;
            }
            if (!StructurePlacementValidationService.canAutoClear(level, info.pos(), current)) {
                return false;
            }
        }
        return true;
    }

    private static void prepareConstructionSite(ServerLevel level, BlueprintService.PlacementBounds bounds) {
        if (level == null || bounds == null) {
            return;
        }
        int baseY = bounds.min().getY();
        for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++) {
            for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++) {
                stabilizeFoundation(level, new BlockPos(x, baseY - 1, z));
                for (int y = baseY; y <= bounds.max().getY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (StructurePlacementValidationService.canAutoClear(level, pos, state) && !state.isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    private static void stabilizeFoundation(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        int minBuildHeight = level.getMinBuildHeight();
        int placed = 0;
        for (int y = pos.getY(); y >= minBuildHeight && placed < 8; y--) {
            BlockPos currentPos = new BlockPos(pos.getX(), y, pos.getZ());
            BlockState currentState = level.getBlockState(currentPos);
            if (!StructurePlacementValidationService.canAutoClear(level, currentPos, currentState)) {
                break;
            }
            level.setBlock(currentPos, (y == pos.getY() ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), Block.UPDATE_ALL);
            placed++;
        }
    }

    private static String createProjectId(BlockPos origin, StructureType type, int rotation) {
        return type.nbtName() + "|" + origin.asLong() + "|" + Math.floorMod(rotation, 4) + "|" + System.currentTimeMillis();
    }

    private static void syncConstructionProgress(ServerLevel level, Set<ServerPlayer> players) {
        if (players.isEmpty()) {
            return;
        }

        for (ServerPlayer player : players) {
            List<SyncConstructionProgressPacket.Entry> entries = new ArrayList<>();
            for (Map.Entry<String, ConstructionJob> activeEntry : ACTIVE_CONSTRUCTIONS.entrySet()) {
                ConstructionJob job = activeEntry.getValue();
                if (job.level != level || !player.getUUID().equals(job.ownerUuid)) {
                    continue;
                }
                entries.add(new SyncConstructionProgressPacket.Entry(
                        job.site.origin(),
                        job.type.nbtName(),
                        job.site.progressPercent(),
                        getActiveWorkerCount(level, activeEntry.getKey())
                ));
            }
            ModNetwork.CHANNEL.sendTo(
                    new SyncConstructionProgressPacket(entries),
                    player.connection.connection,
                    NetworkDirection.PLAY_TO_CLIENT
            );
        }
    }

    private static void syncRoadConstructionProgress(ServerLevel level, Set<ServerPlayer> players) {
        if (players.isEmpty()) {
            return;
        }

        for (ServerPlayer player : players) {
            List<SyncRoadConstructionProgressPacket.Entry> entries = new ArrayList<>();
            for (Map.Entry<String, RoadConstructionJob> activeEntry : ACTIVE_ROAD_CONSTRUCTIONS.entrySet()) {
                RoadConstructionJob job = activeEntry.getValue();
                if (job.level != level || job.ownerUuid == null || !player.getUUID().equals(job.ownerUuid)) {
                    continue;
                }
                entries.add(new SyncRoadConstructionProgressPacket.Entry(
                        job.roadId,
                        job.sourceTownName == null ? "" : job.sourceTownName,
                        job.targetTownName == null ? "" : job.targetTownName,
                        getRoadFocusPos(level, job),
                        roadProgressPercent(level, job),
                        getActiveRoadWorkerCount(level, activeEntry.getKey())
                ));
            }
            ModNetwork.CHANNEL.sendTo(
                    new SyncRoadConstructionProgressPacket(entries),
                    player.connection.connection,
                    NetworkDirection.PLAY_TO_CLIENT
            );
        }
    }

    private static void syncRuntimeGhostPreviews(ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return;
        }
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player == null || player.serverLevel() != level || !isHoldingConstructionPreviewTool(player)) {
                continue;
            }
            List<SyncConstructionGhostPreviewPacket.BuildingEntry> buildingEntries = collectBuildingGhostEntries(level, player);
            List<SyncConstructionGhostPreviewPacket.RoadEntry> roadEntries = collectRoadGhostEntries(level, player);
            ModNetwork.CHANNEL.sendTo(
                    new SyncConstructionGhostPreviewPacket(buildingEntries, roadEntries),
                    player.connection.connection,
                    NetworkDirection.PLAY_TO_CLIENT
            );
        }
    }

    private static List<SyncConstructionGhostPreviewPacket.BuildingEntry> collectBuildingGhostEntries(ServerLevel level, ServerPlayer player) {
        List<SyncConstructionGhostPreviewPacket.BuildingEntry> entries = new ArrayList<>();
        for (Map.Entry<String, ConstructionJob> activeEntry : ACTIVE_CONSTRUCTIONS.entrySet()) {
            ConstructionJob job = activeEntry.getValue();
            if (job.level != level || player.blockPosition().distSqr(job.site.anchorPos()) > GHOST_PREVIEW_RADIUS_SQR) {
                continue;
            }
            List<SyncConstructionGhostPreviewPacket.GhostBlock> blocks = job.site.remainingBlocks().stream()
                    .map(block -> new SyncConstructionGhostPreviewPacket.GhostBlock(block.relativePos(), block.state()))
                    .toList();
            if (blocks.isEmpty()) {
                continue;
            }
            BlueprintService.BlueprintBlock targetBlock = job.site.currentTargetBlock();
            entries.add(new SyncConstructionGhostPreviewPacket.BuildingEntry(
                    activeEntry.getKey(),
                    job.type.nbtName(),
                    job.site.origin(),
                    blocks,
                    targetBlock == null ? null : targetBlock.relativePos(),
                    job.site.progressPercent(),
                    getActiveWorkerCount(level, activeEntry.getKey())
            ));
        }
        return entries;
    }

    private static List<SyncConstructionGhostPreviewPacket.RoadEntry> collectRoadGhostEntries(ServerLevel level, ServerPlayer player) {
        List<SyncConstructionGhostPreviewPacket.RoadEntry> entries = new ArrayList<>();
        for (Map.Entry<String, RoadConstructionJob> activeEntry : ACTIVE_ROAD_CONSTRUCTIONS.entrySet()) {
            RoadConstructionJob job = activeEntry.getValue();
            if (job.level != level || player.blockPosition().distSqr(getRoadFocusPos(level, job)) > GHOST_PREVIEW_RADIUS_SQR) {
                continue;
            }
            List<RoadGeometryPlanner.GhostRoadBlock> remainingBlocks = remainingRoadGhostBlocks(level, job);
            if (remainingBlocks.isEmpty()) {
                continue;
            }
            List<BlockPos> clippedPositions = RuntimeRoadGhostWindow.clip(
                    remainingBlocks.stream().map(RoadGeometryPlanner.GhostRoadBlock::pos).toList(),
                    player.blockPosition(),
                    ROAD_GHOST_WINDOW_RADIUS
            );
            if (clippedPositions.isEmpty()) {
                continue;
            }
            Map<Long, RoadGeometryPlanner.GhostRoadBlock> clippedBlockIndex = new LinkedHashMap<>();
            for (RoadGeometryPlanner.GhostRoadBlock block : remainingBlocks) {
                clippedBlockIndex.put(block.pos().asLong(), block);
            }
            List<RoadGeometryPlanner.GhostRoadBlock> filteredBlocks = clippedPositions.stream()
                    .map(pos -> clippedBlockIndex.get(pos.asLong()))
                    .filter(Objects::nonNull)
                    .toList();
            List<SyncConstructionGhostPreviewPacket.GhostBlock> blocks = filteredBlocks.stream()
                    .map(block -> new SyncConstructionGhostPreviewPacket.GhostBlock(block.pos(), block.state()))
                    .toList();
            entries.add(new SyncConstructionGhostPreviewPacket.RoadEntry(
                    activeEntry.getKey(),
                    job.roadId,
                    job.sourceTownName,
                    job.targetTownName,
                    blocks,
                    selectRoadGhostTargetPos(filteredBlocks),
                    roadProgressPercent(level, job),
                    getActiveRoadWorkerCount(level, activeEntry.getKey())
            ));
        }
        return entries;
    }

    private static Set<BlockPos> remainingRoadGhostPositions(ServerLevel level, RoadConstructionJob job) {
        Set<BlockPos> remaining = new LinkedHashSet<>();
        if (level == null || job == null) {
            return remaining;
        }
        for (RoadGeometryPlanner.GhostRoadBlock block : remainingRoadGhostBlocks(level, job)) {
            remaining.add(block.pos());
        }
        return remaining;
    }

    private static int getActiveWorkerCount(ServerLevel level, String jobId) {
        Map<String, ActiveWorker> workers = ACTIVE_SITE_WORKERS.get(jobId);
        if (workers == null || workers.isEmpty()) {
            return 0;
        }

        long currentTick = level.getGameTime();
        int count = 0;
        Iterator<Map.Entry<String, ActiveWorker>> iterator = workers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ActiveWorker> entry = iterator.next();
            ActiveWorker worker = entry.getValue();
            if (worker == null || currentTick - worker.lastSeenTick > ACTIVE_WORKER_TIMEOUT_TICKS) {
                iterator.remove();
                continue;
            }
            count += worker.specialist ? 2 : 1;
        }

        if (workers.isEmpty()) {
            ACTIVE_SITE_WORKERS.remove(jobId);
        }
        return count;
    }

    private static boolean isHoldingConstructionPreviewTool(ServerPlayer player) {
        return player != null && (
                player.getMainHandItem().is(ModItems.BUILDER_HAMMER_ITEM.get())
                        || player.getOffhandItem().is(ModItems.BUILDER_HAMMER_ITEM.get())
                        || player.getMainHandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get())
                        || player.getOffhandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get())
                        || player.getMainHandItem().is(ModItems.ROAD_PLANNER_ITEM.get())
                        || player.getOffhandItem().is(ModItems.ROAD_PLANNER_ITEM.get())
        );
    }

    private static boolean isHoldingBuilderHammer(ServerPlayer player) {
        return player != null && (
                player.getMainHandItem().is(ModItems.BUILDER_HAMMER_ITEM.get())
                        || player.getOffhandItem().is(ModItems.BUILDER_HAMMER_ITEM.get())
        );
    }

    private static boolean canManageConstruction(ServerLevel level,
                                                 ServerPlayer player,
                                                 String townId,
                                                 String nationId,
                                                 UUID ownerUuid) {
        if (level == null || player == null) {
            return false;
        }
        NationSavedData data = NationSavedData.get(level);
        if (townId != null && !townId.isBlank()) {
            TownRecord town = data.getTown(townId);
            if (town != null) {
                return TownService.canManageTown(player, data, town);
            }
        }
        if (nationId != null && !nationId.isBlank()) {
            NationRecord nation = data.getNation(nationId);
            if (nation != null && player.getUUID().equals(nation.leaderUuid())) {
                return true;
            }
        }
        return ownerUuid != null && ownerUuid.equals(player.getUUID());
    }

    private static boolean canManageRoad(ServerLevel level, ServerPlayer player, RoadConstructionJob job) {
        if (level == null || player == null || job == null) {
            return false;
        }
        NationSavedData data = NationSavedData.get(level);
        RoadNetworkRecord road = data.getRoadNetwork(job.roadId);
        if (road != null) {
            for (String townId : resolveRoadTownIds(road)) {
                TownRecord town = data.getTown(townId);
                if (town != null && TownService.canManageTown(player, data, town)) {
                    return true;
                }
            }
            if (!road.nationId().isBlank()) {
                NationRecord nation = data.getNation(road.nationId());
                if (nation != null && player.getUUID().equals(nation.leaderUuid())) {
                    return true;
                }
            }
        }
        return canManageConstruction(level, player, job.townId, job.nationId, job.ownerUuid);
    }

    private static List<String> resolveRoadTownIds(RoadNetworkRecord road) {
        if (road == null) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (!road.townId().isBlank()) {
            ids.add(road.townId());
        }
        if (road.structureAId().startsWith("town:")) {
            ids.add(road.structureAId().substring(5));
        }
        if (road.structureBId().startsWith("town:")) {
            ids.add(road.structureBId().substring(5));
        }
        return List.copyOf(ids);
    }

    private static HammerChargeResult chargeBuilderHammer(ServerLevel level,
                                                          ServerPlayer player,
                                                          String nationId,
                                                          long cost) {
        if (player == null || player.getAbilities().instabuild || cost <= 0L) {
            return new HammerChargeResult(true, 0L, 0L, Component.empty());
        }
        NationSavedData data = NationSavedData.get(level);
        NationTreasuryRecord treasury = nationId == null || nationId.isBlank() ? null : data.getTreasury(nationId);
        long walletBalance = GoldStandardEconomy.getBalance(player);
        long treasuryBalance = treasury == null ? 0L : Math.max(0L, treasury.currencyBalance());
        BuilderHammerChargePlan plan = BuilderHammerChargePlan.allocate(cost, walletBalance, treasuryBalance);
        if (!plan.success()) {
            return new HammerChargeResult(false, 0L, 0L, Component.translatable("message.sailboatmod.builder_hammer.insufficient_funds"));
        }

        if (plan.walletSpent() > 0L && !Boolean.TRUE.equals(GoldStandardEconomy.tryWithdraw(player, plan.walletSpent()))) {
            return new HammerChargeResult(false, 0L, 0L, Component.translatable("message.sailboatmod.builder_hammer.insufficient_funds"));
        }

        if (plan.treasurySpent() > 0L) {
            NationTreasuryRecord latestTreasury = data.getOrCreateTreasury(nationId);
            if (latestTreasury.currencyBalance() < plan.treasurySpent()) {
                if (plan.walletSpent() > 0L) {
                    GoldStandardEconomy.tryDeposit(player, plan.walletSpent());
                }
                return new HammerChargeResult(false, 0L, 0L, Component.translatable("message.sailboatmod.builder_hammer.insufficient_funds"));
            }
            data.putTreasury(latestTreasury.withBalance(latestTreasury.currencyBalance() - plan.treasurySpent()));
        }

        return new HammerChargeResult(true, plan.walletSpent(), plan.treasurySpent(), Component.empty());
    }

    private static void damageBuilderHammer(ServerPlayer player) {
        if (player == null || player.getAbilities().instabuild) {
            return;
        }
        if (player.getMainHandItem().is(ModItems.BUILDER_HAMMER_ITEM.get())) {
            player.getMainHandItem().hurtAndBreak(1, player, p -> p.broadcastBreakEvent(net.minecraft.world.InteractionHand.MAIN_HAND));
        } else if (player.getOffhandItem().is(ModItems.BUILDER_HAMMER_ITEM.get())) {
            player.getOffhandItem().hurtAndBreak(1, player, p -> p.broadcastBreakEvent(net.minecraft.world.InteractionHand.OFF_HAND));
        }
    }

    private static Block requiredFunctionalBlock(StructureType type) {
        return switch (type) {
            case VICTORIAN_BANK -> ModBlocks.BANK_BLOCK.get();
            case VICTORIAN_TOWN_HALL -> ModBlocks.TOWN_CORE_BLOCK.get();
            case NATION_CAPITOL -> ModBlocks.NATION_CORE_BLOCK.get();
            case OPEN_AIR_MARKETPLACE -> ModBlocks.MARKET_BLOCK.get();
            case WATERFRONT_DOCK -> ModBlocks.DOCK_BLOCK.get();
            case COTTAGE -> ModBlocks.COTTAGE_BLOCK.get();
            case TAVERN -> ModBlocks.BAR_BLOCK.get();
            case SCHOOL -> ModBlocks.SCHOOL_BLOCK.get();
        };
    }

    private static boolean containsBlock(ServerLevel level, BlueprintService.PlacementBounds bounds, Block targetBlock) {
        for (int y = bounds.min().getY(); y <= bounds.max().getY(); y++) {
            for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++) {
                for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++) {
                    if (level.getBlockState(new BlockPos(x, y, z)).is(targetBlock)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static void clearRuntimeState() {
        ACTIVE_CONSTRUCTIONS.clear();
        ACTIVE_ROAD_CONSTRUCTIONS.clear();
        ACTIVE_ASSIST_SITES.clear();
        ACTIVE_SITE_WORKERS.clear();
        ACTIVE_ROAD_WORKERS.clear();
        ACTIVE_BUILDING_HAMMER_CREDITS.clear();
        ACTIVE_ROAD_HAMMER_CREDITS.clear();
        PLAYER_HAMMER_COOLDOWNS.clear();
        RESTORED_DIMENSIONS.clear();
    }

    private static void ensureRuntimeRestored(ServerLevel level) {
        if (level == null) {
            return;
        }
        String dimensionId = level.dimension().location().toString();
        if (!RESTORED_DIMENSIONS.add(dimensionId)) {
            return;
        }

        ConstructionRuntimeSavedData runtimeData = ConstructionRuntimeSavedData.get(level);

        List<String> staleStructureJobs = new ArrayList<>();
        for (ConstructionRuntimeSavedData.StructureJobState state : runtimeData.getStructureJobs()) {
            if (!dimensionId.equals(state.dimensionId()) || ACTIVE_CONSTRUCTIONS.containsKey(state.jobId())) {
                continue;
            }
            UUID ownerUuid = parseUuid(state.ownerUuid());
            StructureType type = findStructureType(state.typeId());
            if (ownerUuid == null || type == null) {
                staleStructureJobs.add(state.jobId());
                continue;
            }

            BlockPos origin = BlockPos.of(state.origin());
            BlueprintService.BlueprintPlacement placement = BlueprintService.preparePlacement(level, type.nbtName(), origin, state.rotation());
            if (placement == null) {
                staleStructureJobs.add(state.jobId());
                continue;
            }

            List<BlockPos> scaffolds = toBlockPosList(state.scaffoldPositions());
            StructureConstructionSite site = StructureConstructionSite.create(level, origin, placement, scaffolds, true);
            if (site.isComplete()) {
                completeStructure(level, ownerUuid, type, site.bounds(), site.rotation(), scaffolds);
                staleStructureJobs.add(state.jobId());
                continue;
            }

            NationSavedData data = NationSavedData.get(level);
            TownRecord constructionTown = TownService.getTownAt(level, origin);
            NationMemberRecord member = data.getMember(ownerUuid);
            if (constructionTown == null && member != null) {
                constructionTown = TownService.getTownForMember(data, member);
            }
            ACTIVE_CONSTRUCTIONS.put(state.jobId(), new ConstructionJob(
                    level,
                    ownerUuid,
                    type,
                    state.projectId(),
                    constructionTown == null ? "" : constructionTown.townId(),
                    member == null ? constructionTown == null ? "" : constructionTown.nationId() : member.nationId(),
                    site
            ));
        }
        staleStructureJobs.forEach(runtimeData::removeStructureJob);

        List<String> staleRoadJobs = new ArrayList<>();
        NationSavedData nationData = NationSavedData.get(level);
        for (ConstructionRuntimeSavedData.RoadJobState state : runtimeData.getRoadJobs()) {
            if (!dimensionId.equals(state.dimensionId()) || ACTIVE_ROAD_CONSTRUCTIONS.containsKey(state.roadId())) {
                continue;
            }

            RoadNetworkRecord road = nationData.getRoadNetwork(state.roadId());
            String validationFailure = validatePersistedRoadJobState(state, road);
            if (!validationFailure.isBlank()) {
                LOGGER.warn("Dropping persisted road job {} in {}: {}", state.roadId(), dimensionId, validationFailure);
                staleRoadJobs.add(state.roadId());
                continue;
            }
            RestoredRoadRuntime restoredRoad = restorePersistedRoadRuntime(level, state);
            if (!restoredRoad.success()) {
                LOGGER.warn("Dropping persisted road job {} in {}: {}", state.roadId(), dimensionId, restoredRoad.failureReason());
                staleRoadJobs.add(state.roadId());
                continue;
            }

            if (!state.rollbackActive() && restoredRoad.placedStepCount() >= restoredRoad.plan().buildSteps().size()) {
                continue;
            }

            UUID ownerUuid = parseUuid(state.ownerUuid());
            String[] resolvedNames = road == null
                    ? new String[] { "-", "-" }
                    : resolveRoadTownNames(level, road);
            ACTIVE_ROAD_CONSTRUCTIONS.put(state.roadId(), new RoadConstructionJob(
                    level,
                    state.roadId(),
                    ownerUuid,
                    road == null ? "" : road.townId(),
                    road == null ? "" : road.nationId(),
                    resolvedNames[0],
                    resolvedNames[1],
                    restoredRoad.plan(),
                    state.rollbackStates(),
                    restoredRoad.placedStepCount(),
                    state.rollbackActive() ? Math.max(restoredRoad.progressSteps(), state.rollbackActionIndex()) : restoredRoad.progressSteps(),
                    state.rollbackActive(),
                    Math.max(state.rollbackActionIndex(), 0),
                    state.removeRoadNetworkOnComplete(),
                    restoredRoad.attemptedStepKeys()
            ));
            persistRoadConstruction(level, state.roadId(), ownerUuid, restoredRoad.plan(), state.rollbackStates(), restoredRoad.placedStepCount(), restoredRoad.progressSteps(), state.rollbackActive(), state.rollbackActionIndex(), state.removeRoadNetworkOnComplete(), restoredRoad.attemptedStepKeys());
        }
        staleRoadJobs.forEach(runtimeData::removeRoadJob);
    }

    private static void persistConstructionJob(String jobId, ConstructionJob job) {
        if (job == null || job.level == null || jobId == null || jobId.isBlank()) {
            return;
        }
        ConstructionRuntimeSavedData.get(job.level).putStructureJob(
                new ConstructionRuntimeSavedData.StructureJobState(
                        jobId,
                        job.level.dimension().location().toString(),
                        job.ownerUuid.toString(),
                        job.type.nbtName(),
                        job.site.origin().asLong(),
                        job.site.rotation(),
                        job.projectId,
                        toLongList(job.site.scaffoldPositions())
                )
        );
    }

    private static void removePersistedConstructionJob(ServerLevel level, String jobId) {
        ConstructionRuntimeSavedData.get(level).removeStructureJob(jobId);
    }

    private static void persistRoadConstruction(ServerLevel level,
                                                String roadId,
                                                UUID ownerUuid,
                                                RoadPlacementPlan plan,
                                                List<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> rollbackStates,
                                                int placedStepCount,
                                                double progressSteps,
                                                boolean rollbackActive,
                                                int rollbackActionIndex,
                                                boolean removeRoadNetworkOnComplete,
                                                Set<Long> attemptedStepKeys) {
        if (level == null || roadId == null || roadId.isBlank() || plan == null) {
            return;
        }
        ConstructionRuntimeSavedData.get(level).putRoadJob(
                new ConstructionRuntimeSavedData.RoadJobState(
                        roadId,
                        safeDimensionId(level),
                        ownerUuid == null ? "" : ownerUuid.toString(),
                        toLongList(plan.centerPath()),
                        serializeRoadGhostBlocks(plan.ghostBlocks()),
                        serializeRoadBuildSteps(plan.buildSteps()),
                        rollbackStates == null ? List.of() : List.copyOf(rollbackStates),
                        toLongList(plan.ownedBlocks()),
                        placedStepCount,
                        progressSteps,
                        false,
                        rollbackActive,
                        rollbackActionIndex,
                        removeRoadNetworkOnComplete,
                        attemptedStepKeys == null ? List.of() : attemptedStepKeys.stream().sorted().toList()
                )
        );
    }

    private static void removePersistedRoadJob(ServerLevel level, String roadId) {
        ConstructionRuntimeSavedData.get(level).removeRoadJob(roadId);
    }

    private static ConstructionRuntimeSavedData.RoadJobState findPersistedRoadJob(ServerLevel level, String roadId) {
        if (level == null || roadId == null || roadId.isBlank()) {
            return null;
        }
        for (ConstructionRuntimeSavedData.RoadJobState state : ConstructionRuntimeSavedData.get(level).getRoadJobs()) {
            if (state != null && roadId.equals(state.roadId())) {
                return state;
            }
        }
        return null;
    }

    private static RestoredRoadRuntime restorePersistedRoadRuntime(ServerLevel level,
                                                                  ConstructionRuntimeSavedData.RoadJobState state) {
        if (level == null || state == null) {
            return RestoredRoadRuntime.failure("Missing persisted road runtime state.");
        }

        if (state.isLegacyPathOnly()) {
            RoadLegacyJobRebuilder.RebuildResult rebuilt = RoadLegacyJobRebuilder.rebuild(
                    state,
                    path -> {
                        if (path == null || path.size() < 2) {
                            return null;
                        }
                        BlockPos start = path.get(0);
                        BlockPos end = path.get(path.size() - 1);
                        return createRoadPlacementPlan(level, path, start, start, end, end);
                    },
                    plan -> validateLegacyRoadPlanResumable(level, plan),
                    step -> isRoadBuildStepPlaced(level, step)
            );
            if (!rebuilt.success()) {
                return RestoredRoadRuntime.failure(rebuilt.failureReason());
            }
            return new RestoredRoadRuntime(
                    rebuilt.plan(),
                    Math.min(rebuilt.plan().buildSteps().size(), Math.max(rebuilt.placedStepCount(), countCompletedRoadBuildSteps(rebuilt.plan(), completedRoadBuildStepKeys(level, rebuilt.plan())))),
                    Math.min(rebuilt.plan().buildSteps().size(), Math.max(rebuilt.placedStepCount(), countCompletedRoadBuildSteps(rebuilt.plan(), completedRoadBuildStepKeys(level, rebuilt.plan())))),
                    completedRoadBuildStepKeys(level, rebuilt.plan()),
                    ""
            );
        }

        RoadPlacementPlan restoredPlan = restoreRoadPlacementPlan(level, state);
        if (restoredPlan == null) {
            return RestoredRoadRuntime.failure("Persisted road runtime state is missing a usable plan.");
        }
        Set<Long> attemptedStepKeys = consumedRoadBuildStepKeys(
                restoredPlan,
                completedRoadBuildStepKeys(level, restoredPlan),
                state.attemptedStepPositions() == null ? Set.of() : new LinkedHashSet<>(state.attemptedStepPositions())
        );
        int placedStepCount = countCompletedRoadBuildSteps(restoredPlan, attemptedStepKeys);
        double restoredProgress = state.rollbackActive()
                ? Math.max(state.progressSteps(), state.rollbackActionIndex())
                : Math.max(state.progressSteps(), placedStepCount);
        return new RestoredRoadRuntime(
                restoredPlan,
                placedStepCount,
                restoredProgress,
                attemptedStepKeys,
                ""
        );
    }

    private static String validatePersistedRoadJobState(ConstructionRuntimeSavedData.RoadJobState state,
                                                        RoadNetworkRecord road) {
        if (state == null) {
            return "missing persisted road runtime state";
        }
        if (state.roadId() == null || state.roadId().isBlank()) {
            return "missing road id";
        }
        if (road == null) {
            return "missing road network record";
        }
        if (!state.roadId().equals(road.roadId())) {
            return "persisted road id does not match road network record";
        }

        List<BlockPos> centerPath = toBlockPosList(state.centerPath());
        if (centerPath.size() < 2) {
            return "centerPath has fewer than two points";
        }
        if (!road.path().equals(centerPath)) {
            return "persisted centerPath no longer matches road network";
        }
        if (state.dimensionId() == null || state.dimensionId().isBlank()) {
            return "missing dimension id";
        }
        if (!road.dimensionId().isBlank() && !road.dimensionId().equals(state.dimensionId())) {
            return "persisted dimension does not match road network";
        }
        if (state.isLegacyPathOnly()) {
            return "";
        }
        if (state.ghostBlocks().isEmpty()) {
            return "ghostBlocks are missing";
        }
        if (state.buildSteps().isEmpty()) {
            return "buildSteps are missing";
        }
        if (state.placedStepCount() < 0 || state.placedStepCount() > state.buildSteps().size()) {
            return "placedStepCount is out of range";
        }
        if (state.rollbackActionIndex() < 0) {
            return "rollbackActionIndex is out of range";
        }

        LinkedHashSet<Long> ghostPositions = new LinkedHashSet<>();
        for (ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState ghostBlock : state.ghostBlocks()) {
            if (ghostBlock == null) {
                return "ghostBlocks contain a null entry";
            }
            if (ghostBlock.statePayload() == null || ghostBlock.statePayload().isEmpty() || !ghostBlock.statePayload().contains("Name", net.minecraft.nbt.Tag.TAG_STRING)) {
                return "ghostBlocks contain an invalid state payload";
            }
            if (!ghostPositions.add(ghostBlock.pos())) {
                return "ghostBlocks contain duplicate positions";
            }
        }

        LinkedHashSet<Long> buildPositions = new LinkedHashSet<>();
        int expectedOrder = 0;
        for (ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState buildStep : state.buildSteps()) {
            if (buildStep == null) {
                return "buildSteps contain a null entry";
            }
            if (buildStep.order() != expectedOrder) {
                return "buildSteps are not contiguous";
            }
            if (buildStep.statePayload() == null || buildStep.statePayload().isEmpty() || !buildStep.statePayload().contains("Name", net.minecraft.nbt.Tag.TAG_STRING)) {
                return "buildSteps contain an invalid state payload";
            }
            if (!buildPositions.add(buildStep.pos())) {
                return "buildSteps contain duplicate positions";
            }
            expectedOrder++;
        }
        if (!ghostPositions.equals(buildPositions)) {
            return "ghostBlocks do not match buildSteps";
        }
        return "";
    }

    private static String validatePersistedRoadJobMatchesPlan(ConstructionRuntimeSavedData.RoadJobState state,
                                                              RoadPlacementPlan expectedPlan) {
        if (state == null) {
            return "missing persisted road runtime state";
        }
        if (state.isLegacyPathOnly()) {
            return "";
        }
        // Non-legacy jobs already persist the full runtime road geometry. Restores should trust
        // that saved plan after internal validation instead of rejecting it because live terrain
        // or water changed and a freshly derived plan no longer matches.
        return "";
    }

    private static String validateLegacyRoadPlanResumable(ServerLevel level, RoadPlacementPlan plan) {
        if (level == null) {
            return "missing level";
        }
        if (plan == null) {
            return "missing runtime road plan";
        }
        if (plan.centerPath().size() < 2) {
            return "centerPath has fewer than two points";
        }
        if (plan.buildSteps().isEmpty()) {
            return "buildSteps are missing";
        }
        for (RoadGeometryPlanner.RoadBuildStep step : plan.buildSteps()) {
            if (step == null) {
                return "runtime road plan contains a null build step";
            }
            String stepFailure = validateLegacyRoadBuildStepResumable(level, step);
            if (!stepFailure.isBlank()) {
                return stepFailure;
            }
        }
        return "";
    }

    private static String validateLegacyRoadBuildStepResumable(ServerLevel level, RoadGeometryPlanner.RoadBuildStep step) {
        if (level == null) {
            return "missing level";
        }
        if (step == null) {
            return "missing build step";
        }
        if (isRoadBuildStepPlaced(level, step)) {
            return "";
        }
        BlockState currentState = level.getBlockState(step.pos());
        if (currentState == null) {
            return "missing block state at " + step.pos().toShortString();
        }
        if (isRoadSurface(currentState) && !currentState.equals(step.state())) {
            return "conflicting road block at " + step.pos().toShortString();
        }
        if (!currentState.isAir() && !currentState.canBeReplaced() && !currentState.liquid()) {
            return "blocked build step at " + step.pos().toShortString();
        }
        return "";
    }

    private static RoadPlacementPlan restoreRoadPlacementPlan(ServerLevel level,
                                                              ConstructionRuntimeSavedData.RoadJobState state) {
        List<BlockPos> centerPath = toBlockPosList(state.centerPath());
        if (centerPath.size() < 2) {
            return null;
        }

        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = deserializeRoadGhostBlocks(level, state.ghostBlocks());
        List<RoadGeometryPlanner.RoadBuildStep> buildSteps = deserializeRoadBuildSteps(level, state.buildSteps());
        if (buildSteps.isEmpty()) {
            return null;
        }

        BlockPos start = centerPath.get(0);
        BlockPos end = centerPath.get(centerPath.size() - 1);
        List<RoadPlacementPlan.BridgeRange> bridgeRanges = detectBridgeRanges(level, centerPath);
        List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges = detectNavigableWaterBridgeRanges(level, centerPath, bridgeRanges);
        List<RoadPlacementPlan.BridgeRange> constructionBridgeRanges = expandBridgeConstructionRanges(level, centerPath, bridgeRanges, navigableWaterBridgeRanges);
        RoadCorridorPlan corridorPlan = createRoadCorridorPlan(level, centerPath, constructionBridgeRanges, navigableWaterBridgeRanges);
        if (!isUsableCorridorPlan(corridorPlan)) {
            return null;
        }
        return new RoadPlacementPlan(
                centerPath,
                start,
                start,
                end,
                end,
                ghostBlocks,
                buildSteps,
                constructionBridgeRanges,
                navigableWaterBridgeRanges,
                toBlockPosList(state.ownedBlocks()),
                highlightPos(start, centerPath, true),
                highlightPos(end, centerPath, false),
                resolveRoadPlanFocusPos(centerPath, ghostBlocks),
                corridorPlan
        );
    }

    private static List<ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState> serializeRoadGhostBlocks(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        if (ghostBlocks == null || ghostBlocks.isEmpty()) {
            return List.of();
        }
        return ghostBlocks.stream()
                .filter(Objects::nonNull)
                .map(block -> new ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState(
                        block.pos().asLong(),
                        NbtUtils.writeBlockState(block.state())
                ))
                .toList();
    }

    private static List<ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState> serializeRoadBuildSteps(List<RoadGeometryPlanner.RoadBuildStep> buildSteps) {
        if (buildSteps == null || buildSteps.isEmpty()) {
            return List.of();
        }
        return buildSteps.stream()
                .filter(Objects::nonNull)
                .map(step -> new ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState(
                        step.order(),
                        step.pos().asLong(),
                        NbtUtils.writeBlockState(step.state())
                ))
                .toList();
    }

    private static List<RoadGeometryPlanner.GhostRoadBlock> deserializeRoadGhostBlocks(ServerLevel level,
                                                                                       List<ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState> ghostBlocks) {
        if (level == null || ghostBlocks == null || ghostBlocks.isEmpty()) {
            return List.of();
        }
        List<RoadGeometryPlanner.GhostRoadBlock> restored = new ArrayList<>(ghostBlocks.size());
        for (ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState block : ghostBlocks) {
            if (block == null) {
                continue;
            }
            BlockState state = deserializeBlockState(level, block.statePayload());
            if (state == null) {
                return List.of();
            }
            restored.add(new RoadGeometryPlanner.GhostRoadBlock(BlockPos.of(block.pos()), state));
        }
        return List.copyOf(restored);
    }

    private static List<RoadGeometryPlanner.RoadBuildStep> deserializeRoadBuildSteps(ServerLevel level,
                                                                                     List<ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState> buildSteps) {
        if (level == null || buildSteps == null || buildSteps.isEmpty()) {
            return List.of();
        }
        List<ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState> orderedSteps = buildSteps.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState::order))
                .toList();
        List<RoadGeometryPlanner.RoadBuildStep> restored = new ArrayList<>(orderedSteps.size());
        for (ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState step : orderedSteps) {
            BlockState state = deserializeBlockState(level, step.statePayload());
            if (state == null) {
                return List.of();
            }
            restored.add(new RoadGeometryPlanner.RoadBuildStep(step.order(), BlockPos.of(step.pos()), state));
        }
        return List.copyOf(restored);
    }

    private static BlockState deserializeBlockState(ServerLevel level, CompoundTag statePayload) {
        if (level == null || statePayload == null || statePayload.isEmpty()) {
            return null;
        }
        try {
            return NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), statePayload);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static BlockState deserializeBlockStatePayload(CompoundTag statePayload) {
        if (statePayload == null || statePayload.isEmpty()) {
            return null;
        }
        try {
            return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), statePayload);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<Long> toLongList(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        return positions.stream()
                .filter(Objects::nonNull)
                .map(BlockPos::asLong)
                .toList();
    }

    private static List<BlockPos> toBlockPosList(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<BlockPos> positions = new ArrayList<>(values.size());
        for (Long value : values) {
            if (value != null) {
                positions.add(BlockPos.of(value));
            }
        }
        return List.copyOf(positions);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static StructureType findStructureType(String typeId) {
        if (typeId == null || typeId.isBlank()) {
            return null;
        }
        for (StructureType type : StructureType.ALL) {
            if (type.nbtName().equals(typeId) || type.name().equalsIgnoreCase(typeId)) {
                return type;
            }
        }
        return null;
    }

    private static ServerPlayer ownerPlayer(ServerLevel level, UUID ownerUuid) {
        return level == null || ownerUuid == null || level.getServer() == null
                ? null
                : level.getServer().getPlayerList().getPlayer(ownerUuid);
    }

    private static String[] resolveRoadTownNames(ServerLevel level, RoadNetworkRecord road) {
        NationSavedData data = NationSavedData.get(level);
        TownRecord leftTown = road == null ? null : data.getTown(road.townId());
        TownRecord rightTown = null;
        if (road != null && road.structureAId() != null && road.structureBId() != null) {
            String leftId = road.structureAId().startsWith("town:") ? road.structureAId().substring(5) : "";
            String rightId = road.structureBId().startsWith("town:") ? road.structureBId().substring(5) : "";
            if (!leftId.isBlank() && leftTown == null) {
                leftTown = data.getTown(leftId);
            }
            if (!rightId.isBlank()) {
                rightTown = data.getTown(rightId);
            }
            if (rightTown == null && !leftId.isBlank() && !leftId.equalsIgnoreCase(road.townId())) {
                rightTown = data.getTown(leftId);
            }
        }
        String leftName = leftTown == null ? road == null ? "" : road.structureAId() : leftTown.name();
        String rightName = rightTown == null ? road == null ? "" : road.structureBId() : rightTown.name();
        return new String[] {
                leftName == null || leftName.isBlank() ? "-" : leftName,
                rightName == null || rightName.isBlank() ? "-" : rightName
        };
    }

    private static void placeNationCoreForConstruction(NationSavedData data,
                                                       ServerLevel level,
                                                       NationRecord nation,
                                                       TownRecord capitalTown,
                                                       BlockPos pos) {
        NationRecord updated = new NationRecord(
                nation.nationId(),
                nation.name(),
                nation.shortName(),
                nation.primaryColorRgb(),
                nation.secondaryColorRgb(),
                nation.leaderUuid(),
                nation.createdAt(),
                nation.capitalTownId(),
                level.dimension().location().toString(),
                pos.asLong(),
                nation.flagId()
        );
        data.putNation(updated);

        ChunkPos chunkPos = new ChunkPos(pos);
        com.monpai.sailboatmod.nation.model.NationClaimRecord existingClaim = data.getClaim(level, chunkPos);
        if (existingClaim == null) {
            data.putClaim(new com.monpai.sailboatmod.nation.model.NationClaimRecord(
                    level.dimension().location().toString(),
                    chunkPos.x,
                    chunkPos.z,
                    nation.nationId(),
                    capitalTown.townId(),
                    com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                    com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                    com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                    com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                    com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                    com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                    com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                    System.currentTimeMillis()
            ));
            return;
        }

        data.putClaim(new com.monpai.sailboatmod.nation.model.NationClaimRecord(
                existingClaim.dimensionId(),
                existingClaim.chunkX(),
                existingClaim.chunkZ(),
                nation.nationId(),
                capitalTown.townId(),
                existingClaim.breakAccessLevel(),
                existingClaim.placeAccessLevel(),
                existingClaim.useAccessLevel(),
                existingClaim.containerAccessLevel(),
                existingClaim.redstoneAccessLevel(),
                existingClaim.entityUseAccessLevel(),
                existingClaim.entityDamageAccessLevel(),
                existingClaim.claimedAt()
        ));
    }
}
