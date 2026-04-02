package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SyncConstructionProgressPacket;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.registry.ModBlocks;
import com.monpai.sailboatmod.resident.service.ConstructionScaffoldingService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
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

    private record ConstructionJob(ServerLevel level, ServerPlayer player, StructureType type,
                                   String projectId, StructureConstructionSite site) {}

    public record AssistPlacementResult(boolean success, boolean completed, Component message) {}
    public record WorkerSiteAssignment(String jobId, BlockPos anchorPos, BlockPos approachPos, BlockPos focusPos,
                                       int progressPercent, int activeWorkers) {}

    private record RoadConstructionJob(ServerLevel level, List<BlockPos> path, int currentIndex, long startTick) {}
    private record ActiveWorker(BlockPos position, long lastSeenTick, boolean specialist) {}

    private static final Map<String, ConstructionJob> ACTIVE_CONSTRUCTIONS = new ConcurrentHashMap<>();
    private static final Map<String, RoadConstructionJob> ACTIVE_ROAD_CONSTRUCTIONS = new ConcurrentHashMap<>();
    private static final Map<String, List<BlockPos>> ACTIVE_ASSIST_SITES = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, ActiveWorker>> ACTIVE_SITE_WORKERS = new ConcurrentHashMap<>();
    private static final int BUILD_DURATION_TICKS = 600; // ~30 seconds for better visibility
    private static final int ROAD_BUILD_DURATION_TICKS = 200; // ~10 seconds for roads
    private static final long ACTIVE_WORKER_TIMEOUT_TICKS = 40L;

    private StructureConstructionManager() {}

    public static boolean placeStructureAnimated(ServerLevel level, BlockPos origin, ServerPlayer player, StructureType type, int rotation) {
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

        List<BlockPos> scaffoldPositions = ConstructionScaffoldingService.placeScaffolding(level, placement.bounds());
        StructureConstructionSite site = StructureConstructionSite.create(level, origin, placement, scaffoldPositions, true);

        String jobId = origin.toShortString() + "_" + System.currentTimeMillis();
        ACTIVE_CONSTRUCTIONS.put(jobId, new ConstructionJob(level, player, type, projectId, site));

        player.sendSystemMessage(Component.translatable(
                "command.sailboatmod.nation.structure.started",
                Component.translatable(type.translationKey())
        ));
        return true;
    }

    public static AssistPlacementResult assistPlaceNextBlock(ServerLevel level, BlockPos origin, ServerPlayer player, StructureType type, int rotation) {
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
                completeStructure(level, player, type, placement.bounds(), placement.rotation(),
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
            completeStructure(level, player, type, placement.bounds(), placement.rotation(),
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
        if (!ACTIVE_CONSTRUCTIONS.isEmpty()) {
            tickConstructions(level);
        }
        if (!ACTIVE_ROAD_CONSTRUCTIONS.isEmpty()) {
            tickRoadConstructions(level);
        }
    }

    private static void tickConstructions(ServerLevel level) {
        List<String> completed = new ArrayList<>();
        Set<ServerPlayer> playersToSync = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<String, ConstructionJob> entry : ACTIVE_CONSTRUCTIONS.entrySet()) {
            ConstructionJob job = entry.getValue();
            if (job.level != level) continue;
            playersToSync.add(job.player);

            int activeWorkers = getActiveWorkerCount(level, entry.getKey());
            job.site.tick(activeWorkers, false);
            if (job.site.consumeProgressUpdate()) {
                job.player.displayClientMessage(Component.translatable(
                        "message.sailboatmod.constructor.progress",
                        Component.translatable(job.type.translationKey()),
                        job.site.progressPercent()
                ), true);
            }

            if (job.site.isComplete()) {
                completeStructure(level, job.player, job.type, job.site.bounds(), job.site.rotation(), job.site.scaffoldPositions());
                job.player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.structure.placed", Component.translatable(job.type.translationKey())));
                completed.add(entry.getKey());
            }
        }

        completed.forEach(jobId -> {
            ACTIVE_CONSTRUCTIONS.remove(jobId);
            ACTIVE_SITE_WORKERS.remove(jobId);
        });
        syncConstructionProgress(level, playersToSync);
    }

    public static WorkerSiteAssignment findNearestSite(ServerLevel level, BlockPos workerPos, int maxDistance) {
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

        return best;
    }

    public static WorkerSiteAssignment getSiteAssignment(ServerLevel level, String jobId, BlockPos workerPos) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        ConstructionJob job = ACTIVE_CONSTRUCTIONS.get(jobId);
        if (job == null || job.level != level || job.site.isComplete()) {
            return null;
        }
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

    public static void reportWorkerActivity(ServerLevel level, String jobId, String workerId, BlockPos position, boolean specialist) {
        if (workerId == null || workerId.isBlank() || position == null) {
            return;
        }
        ConstructionJob job = ACTIVE_CONSTRUCTIONS.get(jobId);
        if (job == null || job.level != level || job.site.isComplete()) {
            return;
        }
        ACTIVE_SITE_WORKERS
                .computeIfAbsent(jobId, ignored -> new ConcurrentHashMap<>())
                .put(workerId, new ActiveWorker(position.immutable(), level.getGameTime(), specialist));
    }

    public static void releaseWorker(String jobId, String workerId) {
        if (jobId == null || jobId.isBlank() || workerId == null || workerId.isBlank()) {
            return;
        }
        Map<String, ActiveWorker> workers = ACTIVE_SITE_WORKERS.get(jobId);
        if (workers == null) {
            return;
        }
        workers.remove(workerId);
        if (workers.isEmpty()) {
            ACTIVE_SITE_WORKERS.remove(jobId);
        }
    }

    public static boolean hasActiveConstruction(ServerLevel level, String jobId) {
        ConstructionJob job = ACTIVE_CONSTRUCTIONS.get(jobId);
        return job != null && job.level == level && !job.site.isComplete();
    }

    public static void tickRoadConstructions(ServerLevel level) {
        BlockState roadBlock = net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState();
        List<String> completed = new ArrayList<>();

        for (Map.Entry<String, RoadConstructionJob> entry : ACTIVE_ROAD_CONSTRUCTIONS.entrySet()) {
            RoadConstructionJob job = entry.getValue();
            if (job.level != level) continue;

            long elapsed = level.getGameTime() - job.startTick;
            int targetIndex = (int) (elapsed * job.path.size() / ROAD_BUILD_DURATION_TICKS);

            for (int i = job.currentIndex; i <= Math.min(targetIndex, job.path.size() - 1); i++) {
                BlockPos pos = job.path.get(i);
                BlockPos roadPos = pos.above();
                BlockState atRoad = level.getBlockState(roadPos);
                if (atRoad.isAir() || atRoad.liquid()) {
                    level.setBlock(roadPos, roadBlock, Block.UPDATE_ALL);
                    tryPlaceRoad(level, roadPos.north(), roadBlock);
                    tryPlaceRoad(level, roadPos.south(), roadBlock);
                }
            }

            int newIndex = Math.min(targetIndex + 1, job.path.size());
            if (newIndex >= job.path.size()) {
                completed.add(entry.getKey());
            } else {
                ACTIVE_ROAD_CONSTRUCTIONS.put(entry.getKey(), new RoadConstructionJob(job.level, job.path, newIndex, job.startTick));
            }
        }

        completed.forEach(ACTIVE_ROAD_CONSTRUCTIONS::remove);
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

        // Remove roads connected to this structure
        removeRoadsForStructure(level, data, target);
        data.removePlacedStructure(target.structureId());
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
        removeRoadsForStructure(level, data, target);
        data.removePlacedStructure(target.structureId());

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

    private static void completeStructure(ServerLevel level, ServerPlayer player, StructureType type,
                                          BlueprintService.PlacementBounds bounds, int rotation,
                                          List<BlockPos> scaffoldPositions) {
        placeCoreBlock(level, bounds, type);
        fixCoreOwnership(level, bounds, player);
        if (scaffoldPositions != null && !scaffoldPositions.isEmpty()) {
            ConstructionScaffoldingService.removeScaffolding(level, scaffoldPositions);
        }

        NationSavedData data = NationSavedData.get(level);
        if (findStructureByOrigin(data, level, bounds.min(), type.nbtName()) != null) {
            return;
        }

        NationMemberRecord member = data.getMember(player.getUUID());
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
        generateRoadToNearest(level, data, record);
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

    private static void fixCoreOwnership(ServerLevel level, BlueprintService.PlacementBounds bounds, ServerPlayer player) {
        NationSavedData data = NationSavedData.get(level);
        NationMemberRecord member = data.getMember(player.getUUID());
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
                        if (nation != null && !nation.hasCore()) {
                            NationClaimService.placeCore(player, pos);
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

    private static void generateRoadToNearest(ServerLevel level, NationSavedData data, com.monpai.sailboatmod.nation.model.PlacedStructureRecord placed) {
        BlockPos entrance = getEntrancePos(placed);
        String dim = placed.dimensionId();
        com.monpai.sailboatmod.nation.model.PlacedStructureRecord nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (com.monpai.sailboatmod.nation.model.PlacedStructureRecord s : data.getPlacedStructures()) {
            if (s.structureId().equals(placed.structureId())) continue;
            if (!dim.equals(s.dimensionId())) continue;
            if (!placed.townId().equals(s.townId()) && !placed.nationId().equals(s.nationId())) continue;
            double dist = entrance.distSqr(getEntrancePos(s));
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = s;
            }
        }
        if (nearest == null || nearestDist > 10000) return;
        buildRoad(level, entrance, getEntrancePos(nearest));
    }

    private static BlockPos getEntrancePos(com.monpai.sailboatmod.nation.model.PlacedStructureRecord structure) {
        BlockPos origin = structure.origin();
        // Return front-center position (entrance is typically at front)
        return origin.offset(structure.sizeW() / 2, 0, 0);
    }

    private static void buildRoad(ServerLevel level, BlockPos from, BlockPos to) {
        List<BlockPos> path = RoadPathfinder.findPath(level, from, to);
        if (path.isEmpty()) return;

        String jobId = UUID.randomUUID().toString();
        ACTIVE_ROAD_CONSTRUCTIONS.put(jobId, new RoadConstructionJob(level, path, 0, level.getGameTime()));
    }

    private static void tryPlaceRoad(ServerLevel level, BlockPos pos, BlockState roadBlock) {
        BlockState at = level.getBlockState(pos);
        if (at.isAir() || at.liquid()) {
            BlockState below = level.getBlockState(pos.below());
            if (!below.isAir() && !below.liquid()) {
                level.setBlock(pos, roadBlock, Block.UPDATE_ALL);
            }
        }
    }

    private static void removeRoadsForStructure(ServerLevel level, NationSavedData data, com.monpai.sailboatmod.nation.model.PlacedStructureRecord removed) {
        BlockPos center = removed.center();
        String dim = removed.dimensionId();
        BlockState roadBlock = net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState();
        for (com.monpai.sailboatmod.nation.model.PlacedStructureRecord s : data.getPlacedStructures()) {
            if (s.structureId().equals(removed.structureId())) continue;
            if (!dim.equals(s.dimensionId())) continue;
            clearRoadBetween(level, center, s.center(), roadBlock);
        }
    }

    private static void clearRoadBetween(ServerLevel level, BlockPos from, BlockPos to, BlockState roadBlock) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) return;
        for (int i = 0; i <= steps; i++) {
            int x = from.getX() + Math.round((float) dx * i / steps);
            int z = from.getZ() + Math.round((float) dz * i / steps);
            for (int dy = -2; dy <= 2; dy++) {
                BlockPos surface = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z)).above(dy);
                removeRoadAt(level, surface, roadBlock);
                if (Math.abs(dx) >= Math.abs(dz)) {
                    removeRoadAt(level, surface.north(), roadBlock);
                    removeRoadAt(level, surface.south(), roadBlock);
                } else {
                    removeRoadAt(level, surface.east(), roadBlock);
                    removeRoadAt(level, surface.west(), roadBlock);
                }
            }
        }
    }

    private static void removeRoadAt(ServerLevel level, BlockPos pos, BlockState roadBlock) {
        if (level.getBlockState(pos).is(roadBlock.getBlock())) {
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static boolean isAreaClear(ServerLevel level, List<StructureTemplate.StructureBlockInfo> blocks) {
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            BlockState current = level.getBlockState(info.pos());
            if (current.equals(info.state())) {
                continue;
            }
            if (!current.isAir() && !current.canBeReplaced()) {
                return false;
            }
        }
        return true;
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
                if (job.level != level || job.player != player) {
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
}
