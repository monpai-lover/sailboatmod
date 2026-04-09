package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.client.ConstructionGhostClientHooks;
import com.monpai.sailboatmod.construction.BuilderHammerChargePlan;
import com.monpai.sailboatmod.construction.BuilderHammerCreditState;
import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import com.monpai.sailboatmod.construction.RuntimeRoadGhostWindow;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.network.NetworkDirection;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class StructureConstructionManager {

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
                                       int placedStepCount,
                                       double progressSteps) {}
    private record ActiveWorker(BlockPos position, long lastSeenTick, boolean specialist) {}
    private record RoadCandidate(com.monpai.sailboatmod.nation.model.PlacedStructureRecord left,
                                 com.monpai.sailboatmod.nation.model.PlacedStructureRecord right,
                                 double distanceSqr) {}
    private record RoadPlan(RoadNetworkRecord road) {}
    private record RoadAnchor(BlockPos pos, Direction side) {}
    private record PreviewRoadTarget(BlockPos pos, PreviewRoadTargetKind kind) {}
    private record RoadPlacementStyle(BlockState surface, BlockState support, boolean bridge) {}
    private record HammerUseResult(boolean success, Component message) {
        private static HammerUseResult failure(String key) {
            return new HammerUseResult(false, Component.translatable(key));
        }
    }
    private record HammerChargeResult(boolean success, long walletSpent, long treasurySpent, Component message) {}
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
    private static final int ROAD_BUILD_DURATION_TICKS = 200; // ~10 seconds for roads
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
            BlockPos focusPos = getRoadFocusPos(job);
            BlockPos approachPos = getRoadApproachPos(level, focusPos);
            double distance = workerPos.distSqr(approachPos);
            if (distance > maxDistanceSqr) {
                continue;
            }

            int activeWorkers = getActiveRoadWorkerCount(level, entry.getKey());
            int progressPercent = roadProgressPercent(job);
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
        BlockPos focusPos = getRoadFocusPos(roadJob);
        BlockPos approachPos = getRoadApproachPos(level, focusPos);
        return new WorkerSiteAssignment(
                jobId,
                focusPos,
                approachPos,
                focusPos.above(),
                roadProgressPercent(roadJob),
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
        List<String> completed = new ArrayList<>();
        Set<ServerPlayer> playersToSync = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Map.Entry<String, RoadConstructionJob> entry : ACTIVE_ROAD_CONSTRUCTIONS.entrySet()) {
            RoadConstructionJob job = entry.getValue();
            if (job.level != level) continue;

            ServerPlayer owner = ownerPlayer(level, job.ownerUuid);
            if (owner != null) {
                playersToSync.add(owner);
            }

            int activeWorkers = getActiveRoadWorkerCount(level, entry.getKey());
            job = consumeRoadHammerCredit(level, entry.getKey(), job);
            double speedMultiplier = activeWorkers > 0 ? (activeWorkers + 2.0D) / 3.0D : 1.0D;
            int totalSteps = job.plan.buildSteps().size();
            if (totalSteps <= 0) {
                completed.add(entry.getKey());
                continue;
            }

            double progressPerTick = (totalSteps / (double) ROAD_BUILD_DURATION_TICKS) * speedMultiplier;
            double targetProgress = job.progressSteps + progressPerTick;
            int targetPlacedStepCount = Math.min(totalSteps, (int) targetProgress);
            RoadConstructionJob advancedJob = placeRoadBuildSteps(level, job, targetPlacedStepCount - job.placedStepCount);
            int newPlacedStepCount = advancedJob.placedStepCount;

            if (newPlacedStepCount >= totalSteps) {
                if (owner != null) {
                    owner.sendSystemMessage(Component.translatable(
                            "message.sailboatmod.road_planner.completed",
                            advancedJob.sourceTownName,
                            advancedJob.targetTownName
                    ));
                }
                completed.add(entry.getKey());
            } else {
                ACTIVE_ROAD_CONSTRUCTIONS.put(entry.getKey(), new RoadConstructionJob(
                        advancedJob.level,
                        advancedJob.roadId,
                        advancedJob.ownerUuid,
                        advancedJob.townId,
                        advancedJob.nationId,
                        advancedJob.sourceTownName,
                        advancedJob.targetTownName,
                        advancedJob.plan,
                        newPlacedStepCount,
                        Math.max(targetProgress, newPlacedStepCount)
                ));
            }
        }

        completed.forEach(jobId -> {
            ACTIVE_ROAD_CONSTRUCTIONS.remove(jobId);
            ACTIVE_ROAD_WORKERS.remove(jobId);
            ACTIVE_ROAD_HAMMER_CREDITS.remove(jobId);
            removePersistedRoadJob(level, jobId);
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
        if (queuedCredits == null || queuedCredits <= 0 || job == null || job.placedStepCount >= job.plan.buildSteps().size()) {
            return job;
        }
        int batchSize = nextRoadBuildBatchSize(job.plan, job.placedStepCount);
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
                updatedJob.placedStepCount,
                Math.max(updatedJob.progressSteps, updatedJob.placedStepCount)
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
        if (job == null || job.level != level || job.placedStepCount >= job.plan.buildSteps().size()) {
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

    private static BlockPos getRoadFocusPos(RoadConstructionJob job) {
        if (job == null || job.plan.centerPath().isEmpty()) {
            return BlockPos.ZERO;
        }
        int index = roadBuildBatchIndex(job.plan, job.placedStepCount);
        index = Math.max(0, Math.min(index, job.plan.centerPath().size() - 1));
        return job.plan.centerPath().get(index);
    }

    private static BlockPos getRoadGhostFocusPos(RoadConstructionJob job) {
        if (job == null) {
            return null;
        }
        List<RoadGeometryPlanner.GhostRoadBlock> remaining = remainingRoadGhostBlocks(job.plan, job.placedStepCount);
        if (!remaining.isEmpty()) {
            return remaining.get(0).pos();
        }
        return job.plan.focusPos();
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

    private static int roadProgressPercent(RoadConstructionJob job) {
        return roadProgressPercent(job == null ? null : job.plan, job == null ? 0 : job.placedStepCount);
    }

    private static int roadProgressPercent(RoadPlacementPlan plan, int placedStepCount) {
        if (plan == null || plan.buildSteps().isEmpty()) {
            return 100;
        }
        int clamped = Math.max(0, Math.min(placedStepCount, plan.buildSteps().size()));
        return Math.max(0, Math.min(100, (clamped * 100) / plan.buildSteps().size()));
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
        List<BlockPos> bestPath = List.of();
        double bestScore = Double.MAX_VALUE;

        for (RoadAnchor firstAnchor : firstAnchors) {
            for (RoadAnchor secondAnchor : secondAnchors) {
                double directDistance = firstAnchor.pos().distSqr(secondAnchor.pos());
                List<BlockPos> path = RoadPathfinder.findPath(level, firstAnchor.pos(), secondAnchor.pos());
                if (path.size() < 2) {
                    continue;
                }
                double score = path.size() + (directDistance * 0.05D);
                if (firstAnchor.side() == primaryRoadSide(first.rotation())) {
                    score -= 2.0D;
                }
                if (secondAnchor.side() == primaryRoadSide(second.rotation())) {
                    score -= 2.0D;
                }
                if (score < bestScore) {
                    bestScore = score;
                    bestPath = path;
                }
            }
        }

        if (!bestPath.isEmpty()) {
            return bestPath;
        }
        return RoadPathfinder.findPath(level, first.center(), second.center());
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
        ACTIVE_ROAD_CONSTRUCTIONS.put(road.roadId(), new RoadConstructionJob(
                level,
                road.roadId(),
                ownerUuid,
                road.townId(),
                road.nationId(),
                resolvedSource,
                resolvedTarget,
                runtimePlan,
                placedStepCount,
                placedStepCount
        ));
        persistRoadConstruction(level, road.roadId(), ownerUuid, road.path());
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
        if (centerPath == null || centerPath.isEmpty()) {
            return new RoadPlacementPlan(List.of(), sourceInternalAnchor, sourceBoundaryAnchor, targetBoundaryAnchor, targetInternalAnchor,
                    List.of(), List.of(), List.of(), null, null, null);
        }
        RoadGeometryPlanner.RoadGeometryPlan geometry = RoadGeometryPlanner.plan(
                centerPath,
                pos -> selectRoadPlacementStyle(level, pos).surface()
        );
        return new RoadPlacementPlan(
                centerPath,
                sourceInternalAnchor,
                sourceBoundaryAnchor,
                targetBoundaryAnchor,
                targetInternalAnchor,
                geometry.ghostBlocks(),
                geometry.buildSteps(),
                detectBridgeRanges(level, centerPath),
                highlightPos(sourceBoundaryAnchor, centerPath, true),
                highlightPos(targetBoundaryAnchor, centerPath, false),
                resolveRoadPlanFocusPos(centerPath, geometry.ghostBlocks())
        );
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

    private static RoadConstructionJob placeRoadBuildSteps(ServerLevel level, RoadConstructionJob job, int stepCount) {
        if (job == null || stepCount <= 0) {
            return job;
        }
        int totalSteps = job.plan.buildSteps().size();
        int startIndex = Math.max(0, Math.min(job.placedStepCount, totalSteps));
        int requestedEndIndex = Math.max(startIndex, Math.min(totalSteps, startIndex + stepCount));
        boolean placedAny = false;
        int completedIndex = startIndex;
        BlockPos effectPos = null;
        for (int i = startIndex; i < requestedEndIndex; i++) {
            RoadGeometryPlanner.RoadBuildStep step = job.plan.buildSteps().get(i);
            placedAny |= tryPlaceRoad(level, step.pos(), roadPlacementStyleForState(level, step.pos(), step.state()));
            if (!isRoadBuildStepPlaced(level, step)) {
                break;
            }
            completedIndex = i + 1;
            effectPos = step.pos();
        }
        if (placedAny) {
            BlockPos center = effectPos == null ? job.plan.buildSteps().get(startIndex).pos() : effectPos;
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
                completedIndex,
                Math.max(job.progressSteps, completedIndex)
        );
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
        Set<BlockPos> positions = new LinkedHashSet<>();
        BlockPos current = path.get(index).above();
        positions.add(current);

        BlockPos previous = index > 0 ? path.get(index - 1) : path.get(index);
        BlockPos next = index + 1 < path.size() ? path.get(index + 1) : path.get(index);
        int dx = Integer.compare(next.getX(), previous.getX());
        int dz = Integer.compare(next.getZ(), previous.getZ());
        if (dx != 0 && dz == 0) {
            positions.add(current.north());
            positions.add(current.south());
        } else if (dz != 0 && dx == 0) {
            positions.add(current.east());
            positions.add(current.west());
        } else if (dx != 0 && dz != 0) {
            positions.add(dx > 0 ? current.east() : current.west());
            positions.add(dz > 0 ? current.south() : current.north());
        }

        if (index > 0 && index + 1 < path.size()) {
            BlockPos surface = path.get(index);
            int prevDx = Integer.compare(surface.getX(), previous.getX());
            int prevDz = Integer.compare(surface.getZ(), previous.getZ());
            int nextDx = Integer.compare(next.getX(), surface.getX());
            int nextDz = Integer.compare(next.getZ(), surface.getZ());
            if (prevDx != nextDx || prevDz != nextDz) {
                positions.add(current.north());
                positions.add(current.south());
                positions.add(current.east());
                positions.add(current.west());
            }
        }
        return positions;
    }

    private static List<RoadPlacementPlan.BridgeRange> detectBridgeRanges(ServerLevel level, List<BlockPos> centerPath) {
        if (level == null || centerPath == null || centerPath.isEmpty()) {
            return List.of();
        }
        List<RoadPlacementPlan.BridgeRange> ranges = new ArrayList<>();
        int rangeStart = -1;
        for (int i = 0; i < centerPath.size(); i++) {
            boolean bridge = selectRoadPlacementStyle(level, centerPath.get(i).above()).bridge();
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
        return List.copyOf(ranges);
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
        if (level == null || plan == null || plan.buildSteps().isEmpty()) {
            return 0;
        }
        for (int i = 0; i < plan.buildSteps().size(); i++) {
            if (!isRoadBuildStepPlaced(level, plan.buildSteps().get(i))) {
                return i;
            }
        }
        return plan.buildSteps().size();
    }

    private static boolean isRoadBuildStepPlaced(ServerLevel level, RoadGeometryPlanner.RoadBuildStep step) {
        if (level == null || step == null) {
            return false;
        }
        BlockState current = level.getBlockState(step.pos());
        return current.equals(step.state()) || isRoadSurface(current);
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

    private static List<RoadGeometryPlanner.GhostRoadBlock> remainingRoadGhostBlocks(ServerLevel level, RoadConstructionJob job) {
        if (job == null) {
            return List.of();
        }
        return remainingRoadGhostBlocks(job.plan, job.placedStepCount).stream()
                .filter(block -> !isRoadSurface(level.getBlockState(block.pos())))
                .toList();
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
            for (BlockPos pos : collectRoadSlicePositions(plan.centerPath(), i)) {
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

    private static boolean tryPlaceRoad(ServerLevel level, BlockPos pos, RoadPlacementStyle style) {
        stabilizeRoadFoundation(level, pos, style);
        BlockState at = level.getBlockState(pos);
        if (at.equals(style.surface()) || isRoadSurface(at)) {
            return false;
        }
        if (!at.isAir() && !at.canBeReplaced() && !at.liquid()) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        if (below.isAir() || below.liquid()) {
            return false;
        }
        level.setBlock(pos, style.surface(), Block.UPDATE_ALL);
        return true;
    }

    private static RoadPlacementStyle selectRoadPlacementStyle(ServerLevel level, BlockPos pos) {
        if (isBridgeSegment(level, pos)) {
            return new RoadPlacementStyle(Blocks.SPRUCE_SLAB.defaultBlockState(), Blocks.SPRUCE_FENCE.defaultBlockState(), true);
        }

        String biomePath = level.getBiome(pos).unwrapKey()
                .map(key -> key.location().getPath())
                .orElse("");
        if (biomePath.contains("desert") || biomePath.contains("badlands") || biomePath.contains("beach")) {
            return new RoadPlacementStyle(Blocks.SMOOTH_SANDSTONE_SLAB.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false);
        }
        if (biomePath.contains("swamp") || biomePath.contains("mangrove") || biomePath.contains("jungle")) {
            return new RoadPlacementStyle(Blocks.MUD_BRICK_SLAB.defaultBlockState(), Blocks.MUD_BRICKS.defaultBlockState(), false);
        }
        return new RoadPlacementStyle(Blocks.STONE_BRICK_SLAB.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), false);
    }

    private static RoadPlacementStyle roadPlacementStyleForState(ServerLevel level, BlockPos pos, BlockState surfaceState) {
        if (surfaceState == null) {
            return selectRoadPlacementStyle(level, pos);
        }
        if (surfaceState.is(Blocks.SPRUCE_SLAB)) {
            return new RoadPlacementStyle(surfaceState, Blocks.SPRUCE_FENCE.defaultBlockState(), true);
        }
        if (surfaceState.is(Blocks.SMOOTH_SANDSTONE_SLAB)) {
            return new RoadPlacementStyle(surfaceState, Blocks.SANDSTONE.defaultBlockState(), false);
        }
        if (surfaceState.is(Blocks.MUD_BRICK_SLAB)) {
            return new RoadPlacementStyle(surfaceState, Blocks.MUD_BRICKS.defaultBlockState(), false);
        }
        return new RoadPlacementStyle(surfaceState, Blocks.COBBLESTONE.defaultBlockState(), false);
    }

    private static boolean isBridgeSegment(ServerLevel level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        if (below.isAir() || below.liquid()) {
            return true;
        }
        return level.getBlockState(pos.below().north()).liquid()
                || level.getBlockState(pos.below().south()).liquid()
                || level.getBlockState(pos.below().east()).liquid()
                || level.getBlockState(pos.below().west()).liquid();
    }

    private static void stabilizeRoadFoundation(ServerLevel level, BlockPos pos, RoadPlacementStyle style) {
        BlockPos cursor = pos.below();
        int depth = 0;
        while (depth < 8) {
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir() && !state.canBeReplaced() && !state.liquid()) {
                return;
            }
            BlockState fillState = depth == 0 || !style.bridge() ? style.support() : Blocks.SPRUCE_FENCE.defaultBlockState();
            level.setBlock(cursor, fillState, Block.UPDATE_ALL);
            cursor = cursor.below();
            depth++;
        }
    }

    private static boolean isRoadSurface(BlockState state) {
        return state.is(Blocks.STONE_BRICK_SLAB)
                || state.is(Blocks.SMOOTH_SANDSTONE_SLAB)
                || state.is(Blocks.MUD_BRICK_SLAB)
                || state.is(Blocks.SPRUCE_SLAB)
                || state.is(Blocks.SPRUCE_FENCE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.MUD_BRICKS);
    }

    private static void clearRoad(ServerLevel level, RoadNetworkRecord road, Set<BlockPos> preservedCoverage) {
        for (BlockPos pos : collectRoadPlacementPositions(road.path())) {
            if (!preservedCoverage.contains(pos)) {
                removeRoadAt(level, pos);
            }
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
                List<BlockPos> path = RoadPathfinder.findPath(level, anchor.pos(), target.pos());
                if (path.size() < 2) {
                    continue;
                }
                double score = path.size() + (anchor.pos().distSqr(target.pos()) * 0.02D);
                if (target.kind() == PreviewRoadTargetKind.ROAD) {
                    score -= 2.5D;
                }
                if (anchor.side() == primaryRoadSide(rotation)) {
                    score -= 1.0D;
                }
                scored.add(new ScoredConnection(new PreviewRoadConnection(path, target.kind(), target.pos()), score));
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
                        getRoadFocusPos(job),
                        roadProgressPercent(job),
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
            if (job.level != level || player.blockPosition().distSqr(getRoadFocusPos(job)) > GHOST_PREVIEW_RADIUS_SQR) {
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
            List<SyncConstructionGhostPreviewPacket.GhostBlock> blocks = clippedPositions.stream()
                    .map(pos -> clippedBlockIndex.get(pos.asLong()))
                    .filter(Objects::nonNull)
                    .map(block -> new SyncConstructionGhostPreviewPacket.GhostBlock(block.pos(), block.state()))
                    .toList();
            entries.add(new SyncConstructionGhostPreviewPacket.RoadEntry(
                    activeEntry.getKey(),
                    job.roadId,
                    job.sourceTownName,
                    job.targetTownName,
                    blocks,
                    getRoadGhostFocusPos(job),
                    roadProgressPercent(job),
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
        for (ConstructionRuntimeSavedData.RoadJobState state : runtimeData.getRoadJobs()) {
            if (!dimensionId.equals(state.dimensionId()) || ACTIVE_ROAD_CONSTRUCTIONS.containsKey(state.roadId())) {
                continue;
            }
            RoadNetworkRecord road = NationSavedData.get(level).getRoadNetwork(state.roadId());
            List<BlockPos> path = road == null ? toBlockPosList(state.path()) : road.path();
            if (path.size() < 2) {
                staleRoadJobs.add(state.roadId());
                continue;
            }
            RoadPlacementPlan runtimePlan = createRoadPlacementPlan(level, path, path.get(0), path.get(0), path.get(path.size() - 1), path.get(path.size() - 1));
            int placedStepCount = findRoadPlacedStepCount(level, runtimePlan);
            if (placedStepCount >= runtimePlan.buildSteps().size()) {
                staleRoadJobs.add(state.roadId());
                continue;
            }
            UUID ownerUuid = parseUuid(state.ownerUuid());
            String[] resolvedNames = resolveRoadTownNames(level, road);
            ACTIVE_ROAD_CONSTRUCTIONS.put(state.roadId(), new RoadConstructionJob(
                    level,
                    state.roadId(),
                    ownerUuid,
                    road == null ? "" : road.townId(),
                    road == null ? "" : road.nationId(),
                    resolvedNames[0],
                    resolvedNames[1],
                    runtimePlan,
                    placedStepCount,
                    placedStepCount
            ));
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

    private static void persistRoadConstruction(ServerLevel level, String roadId, UUID ownerUuid, List<BlockPos> path) {
        if (level == null || roadId == null || roadId.isBlank()) {
            return;
        }
        ConstructionRuntimeSavedData.get(level).putRoadJob(
                new ConstructionRuntimeSavedData.RoadJobState(
                        roadId,
                        level.dimension().location().toString(),
                        ownerUuid == null ? "" : ownerUuid.toString(),
                        toLongList(path)
                )
        );
    }

    private static void removePersistedRoadJob(ServerLevel level, String roadId) {
        ConstructionRuntimeSavedData.get(level).removeRoadJob(roadId);
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
