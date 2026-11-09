package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.block.entity.AirportBlockEntity;
import com.monpai.sailboatmod.nation.service.NationClaimService;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.roadplanner.service.SharedMapTerrainService;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Builds the airport structure and its inlaid runway details. */
public final class AirportPrefab {
    static final int MAX_BLOCKS = 1792;
    private static final int PLACEMENT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    public enum Part {
        STRUCTURE, MARKING, RUNWAY_LIGHT, ROOF_LIGHT;

        boolean inlaid() { return this == MARKING || this == RUNWAY_LIGHT; }

        private Block block() {
            return switch (this) {
                case STRUCTURE -> Blocks.SMOOTH_STONE;
                case MARKING -> Blocks.WHITE_CONCRETE;
                case RUNWAY_LIGHT, ROOF_LIGHT -> Blocks.SEA_LANTERN;
            };
        }
    }

    public record Piece(BlockPos pos, Part part) {
        public Piece { pos = pos.immutable(); }
    }

    public record Materials(int stone, int markings, int lights) {}

    public static Materials materials(List<Piece> pieces) {
        int stone = 0, markings = 0, lights = 0;
        for (var piece : pieces) {
            switch (piece.part()) {
                case STRUCTURE -> stone++;
                case MARKING -> markings++;
                case RUNWAY_LIGHT, ROOF_LIGHT -> lights++;
            }
        }
        return new Materials(stone, markings, lights);
    }

    private AirportPrefab() {}

    public static Optional<List<BlockPos>> plan(AirportEditorSession.Proposal proposal, BlockPos cornerA, BlockPos cornerB) {
        if (proposal == null || proposal.changedRunway() == null || cornerA == null || cornerB == null
                || cornerA.getY() != cornerB.getY()) return Optional.empty();
        var layout = proposal.ground();
        String runway = proposal.changedRunway().id();
        var route = layout.taxiRoutes().stream().filter(candidate -> candidate.runwayId().equals(runway)
                && candidate.direction() == AirportTaxiRoute.Direction.OUTBOUND).findFirst().orElse(null);
        if (route == null) return Optional.empty();
        var stand = layout.stands().stream().filter(candidate -> candidate.id().equals(route.standId()))
                .findFirst().orElse(null);
        if (stand == null || !stand.validGeometry() || stand.position().y != cornerA.getY()) return Optional.empty();

        double yaw = Math.toRadians(stand.yawDegrees());
        boolean alongX = Math.abs(Math.sin(yaw)) > 0.999;
        if (!alongX && Math.abs(Math.cos(yaw)) <= 0.999) return Optional.empty();
        double along = alongX ? stand.position().x : stand.position().z;
        double across = alongX ? stand.position().z : stand.position().x;
        int firstAlong = (int) Math.floor(along - Math.min(32, stand.length()) / 2);
        int lastAlong = (int) Math.ceil(along + Math.min(32, stand.length()) / 2) - 1;
        int firstPost = (int) Math.floor(across - stand.width() / 2) - 1;
        int lastPost = (int) Math.ceil(across + stand.width() / 2);
        boolean wide = stand.width() > 32;
        int firstRoof = wide ? (int) Math.floor(across - 16) : firstPost;
        int lastRoof = wide ? (int) Math.ceil(across + 16) - 1 : lastPost;
        int bottom = cornerA.getY(), roof = (int) Math.ceil(stand.bounds().maxY);
        long size = (long) (lastAlong - firstAlong + 1) * (lastRoof - firstRoof + 1)
                + 2L * (firstRoof - firstPost + lastPost - lastRoof) + 4L * (roof - bottom);
        if (size > MAX_BLOCKS) return Optional.empty();

        var blocks = new ArrayList<BlockPos>((int) size);
        for (int y = bottom; y < roof; y++) {
            blocks.add(block(alongX, firstAlong, y, firstPost));
            blocks.add(block(alongX, firstAlong, y, lastPost));
            blocks.add(block(alongX, lastAlong, y, firstPost));
            blocks.add(block(alongX, lastAlong, y, lastPost));
        }
        for (int a = firstAlong; a <= lastAlong; a++) {
            boolean beam = a == firstAlong || a == lastAlong;
            for (int b = beam ? firstPost : firstRoof; b <= (beam ? lastPost : lastRoof); b++) {
                blocks.add(block(alongX, a, roof, b));
            }
        }
        if (blocks.stream().anyMatch(pos -> !inSelection(pos, cornerA, cornerB)) || obstruction(proposal, blocks) != null) {
            return Optional.empty();
        }
        var markers = new LinkedHashSet<BlockPos>();
        AirportFacility facility = proposal.changedRunway();
        int runwayStart = alongX ? facility.start().getX() : facility.start().getZ();
        int runwayEnd = alongX ? facility.end().getX() : facility.end().getZ();
        int runwayAcross = alongX ? facility.start().getZ() : facility.start().getX();
        int runwayShoulder = facility.width() / 2 + 2;
        for (int quarter = 1; quarter <= 3; quarter++) {
            int a = runwayStart + (runwayEnd - runwayStart) * quarter / 4;
            markers.add(block(alongX, a, bottom, runwayAcross - runwayShoulder));
            markers.add(block(alongX, a, bottom, runwayAcross + runwayShoulder));
        }
        for (var pad : proposal.additionalChecks()) {
            if (pad.type() != AirportFacility.Type.HELIPAD) continue;
            int shoulder = pad.width() / 2 + 2;
            for (int x : new int[]{-shoulder, shoulder}) {
                for (int z : new int[]{-shoulder, shoulder}) markers.add(pad.start().offset(x, 0, z));
                for (int side : new int[]{-2, 0, 2}) {
                    markers.add(pad.start().offset(x, 0, side));
                    markers.add(pad.start().offset(side, 0, x));
                }
            }
        }
        int taxiShoulder = (int) Math.ceil(route.width() / 2) + 2;
        for (int i = 1; i < route.points().size(); i++) {
            var a = route.points().get(i - 1);
            var b = route.points().get(i);
            boolean parallelToX = a.z == b.z;
            if (!parallelToX && a.x != b.x) continue;
            int axis = (int) Math.floor(parallelToX ? (a.x + b.x) / 2 : (a.z + b.z) / 2);
            int side = (int) Math.floor(parallelToX ? a.z : a.x);
            markers.add(block(parallelToX, axis, bottom, side - taxiShoulder));
            markers.add(block(parallelToX, axis, bottom, side + taxiShoulder));
        }
        for (BlockPos marker : markers) {
            if (blocks.size() >= MAX_BLOCKS) break;
            if (inSelection(marker, cornerA, cornerB) && !blocks.contains(marker)
                    && obstruction(proposal, List.of(marker)) == null) blocks.add(marker);
        }
        return Optional.of(List.copyOf(blocks));
    }

    public static Optional<List<Piece>> planDetailed(AirportEditorSession.Proposal proposal, BlockPos cornerA, BlockPos cornerB) {
        var shell = plan(proposal, cornerA, cornerB);
        if (shell.isEmpty()) return Optional.empty();
        var runway = proposal.changedRunway();
        var details = runwayDetails(runway);
        if (details.isEmpty()) return Optional.empty();

        var blocks = shell.orElseThrow();
        int roof = blocks.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        var roofBlocks = blocks.stream().filter(pos -> pos.getY() == roof).toList();
        int minX = roofBlocks.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = roofBlocks.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minZ = roofBlocks.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ = roofBlocks.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        var pieces = new ArrayList<Piece>();
        for (BlockPos pos : blocks) {
            boolean corner = pos.getY() == roof && (pos.getX() == minX || pos.getX() == maxX)
                    && (pos.getZ() == minZ || pos.getZ() == maxZ);
            pieces.add(new Piece(pos, corner ? Part.ROOF_LIGHT : Part.STRUCTURE));
        }
        pieces.addAll(details);
        if (pieces.size() > MAX_BLOCKS || pieces.stream().anyMatch(piece -> !inSelection(piece.pos(), cornerA, cornerB)))
            return Optional.empty();
        return Optional.of(List.copyOf(pieces));
    }

    /** Axis-aligned presets use full blocks in the ground layer so the aircraft clearance stays open. */
    static List<Piece> runwayDetails(AirportFacility runway) {
        if (runway.type() != AirportFacility.Type.RUNWAY || !runway.validGeometry()) return List.of();
        boolean alongX = runway.start().getZ() == runway.end().getZ();
        if (!alongX && runway.start().getX() != runway.end().getX()) return List.of();
        int first = alongX ? runway.start().getX() : runway.start().getZ();
        int last = alongX ? runway.end().getX() : runway.end().getZ();
        int across = alongX ? runway.start().getZ() : runway.start().getX();
        int start = Math.min(first, last), end = Math.max(first, last), floor = runway.start().getY() - 1;
        int edge = runway.width() / 2 - 1;

        var details = new ArrayList<Piece>();
        for (int threshold : new int[]{start + 2, end - 2}) {
            for (int offset = 1 - edge; offset < edge; offset++)
                details.add(new Piece(block(alongX, threshold, floor, across + offset), Part.MARKING));
        }
        for (int along = start + 7; along <= end - 7; along++) {
            if ((along - start - 7) % 12 < 5)
                details.add(new Piece(block(alongX, along, floor, across), Part.MARKING));
        }
        for (int along = start; along < end; along += 8) addEdgeLights(details, alongX, along, floor, across, edge);
        addEdgeLights(details, alongX, end, floor, across, edge);
        return List.copyOf(details);
    }

    private static void addEdgeLights(List<Piece> details, boolean alongX, int along, int floor, int across, int edge) {
        details.add(new Piece(block(alongX, along, floor, across - edge), Part.RUNWAY_LIGHT));
        details.add(new Piece(block(alongX, along, floor, across + edge), Part.RUNWAY_LIGHT));
    }

    private static BlockPos block(boolean alongX, int along, int y, int across) {
        return new BlockPos(alongX ? along : across, y, alongX ? across : along);
    }

    private static boolean inSelection(BlockPos pos, BlockPos a, BlockPos b) {
        return pos.getX() >= Math.min(a.getX(), b.getX()) && pos.getX() <= Math.max(a.getX(), b.getX())
                && pos.getZ() >= Math.min(a.getZ(), b.getZ()) && pos.getZ() <= Math.max(a.getZ(), b.getZ());
    }

    private static BlockPos obstruction(AirportEditorSession.Proposal proposal, List<BlockPos> blocks) {
        var standBounds = proposal.ground().stands().stream().map(AirportParkingStand::bounds).toList();
        var facilityBounds = proposal.facilities().stream().map(AirportFacility::clearanceBounds).toList();
        var routes = proposal.ground().taxiRoutes();
        var routeBounds = routes.stream().map(AirportTaxiRoute::bounds).toList();
        for (BlockPos pos : blocks) {
            AABB cell = new AABB(pos);
            for (var stand : standBounds) if (stand.intersects(cell)) return pos;
            for (int i = 0; i < facilityBounds.size(); i++) {
                if (!facilityBounds.get(i).intersects(cell)) continue;
                var facility = proposal.facilities().get(i);
                int base = facility.clearanceBaseY(pos.getX(), pos.getZ());
                if (base != Integer.MIN_VALUE && pos.getY() >= base && pos.getY() < base + facility.clearanceHeight()) return pos;
            }
            for (int i = 0; i < routes.size(); i++) {
                if (!routeBounds.get(i).intersects(cell)) continue;
                var route = routes.get(i);
                for (int j = 1; j < route.points().size(); j++) {
                    if (AirportGroundValidation.segmentDistance(pos.getX() + .5, pos.getZ() + .5,
                            route.points().get(j - 1), route.points().get(j)) <= route.width() / 2 + Math.sqrt(.5)) return pos;
                }
            }
        }
        return null;
    }

    public static String install(ServerPlayer player, AirportBlockEntity airport, AirportEditorSession.Proposal proposal) {
        return install(player, airport, proposal, ignored -> {});
    }

    public static String install(ServerPlayer player, AirportBlockEntity airport, AirportEditorSession.Proposal proposal,
                                 Consumer<BlockPos> issue) {
        if (!AirportManagementService.canManage(player, airport)) return "forbidden";
        var pieces = proposal.prefabBlocks();
        var blocks = pieces.stream().map(Piece::pos).toList();
        if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS || new HashSet<>(blocks).size() != blocks.size()
                || proposal.changedRunway() == null) return "invalid_geometry";
        BlockPos obstruction = obstruction(proposal, blocks);
        if (obstruction != null) return reject(issue, obstruction, "obstructed");
        if (airport.configurationOccupied(proposal.facilities(), proposal.ground())) return "occupied";
        ServerLevel level = player.serverLevel();
        int deckY = proposal.changedRunway().start().getY();
        var previous = new LinkedHashMap<BlockPos, BlockState>();
        var replacements = new LinkedHashMap<BlockPos, BlockState>();
        var changedPieces = new ArrayList<Piece>();
        AABB area = new AABB(blocks.getFirst());
        for (Piece piece : pieces) {
            BlockPos pos = piece.pos();
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) return reject(issue, pos, "invalid_geometry");
            if (!level.hasChunkAt(pos)) return reject(issue, pos, "unloaded");
            var town = TownService.getManagedTownAt(player, pos);
            if (town == null || !town.townId().equals(airport.getTownId()) || !level.mayInteract(player, pos)
                    || !NationClaimService.canPlace(level, player.getUUID(), pos)
                    || piece.part().inlaid() && !NationClaimService.canBreak(level, player.getUUID(), pos))
                return reject(issue, pos, "foreign_town");

            BlockState state = level.getBlockState(pos);
            BlockState replacement = piece.part().block().defaultBlockState();
            if (!state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null)
                return reject(issue, pos, "obstructed");
            if (piece.part().inlaid()) {
                if (pos.getY() != deckY - 1 || !state.isCollisionShapeFullBlock(level, pos)
                        || !state.isFaceSturdy(level, pos, Direction.UP)) return reject(issue, pos, "unsupported");
                if (state.equals(replacement)) continue;
                if (!replaceableSurface(state)) return reject(issue, pos, "prefab_surface");
            } else {
                if (!state.isAir()) return reject(issue, pos, "obstructed");
                if (pos.getY() == deckY) {
                    BlockPos support = pos.below();
                    if (level.isOutsideBuildHeight(support) || !level.getBlockState(support).isFaceSturdy(level, support, Direction.UP))
                        return reject(issue, support, "unsupported");
                }
            }
            previous.put(pos, state);
            replacements.put(pos, replacement);
            changedPieces.add(piece);
            area = area.minmax(new AABB(pos));
        }
        var entities = level.getEntities((Entity) null, area, entity -> entity.isAlive() && !entity.isSpectator());
        for (var entity : entities) {
            for (Piece piece : changedPieces) {
                if (!piece.part().inlaid() && entity.getBoundingBox().intersects(new AABB(piece.pos())))
                    return reject(issue, piece.pos(), "occupied");
            }
        }

        var inventory = player.getInventory();
        boolean creative = player.getAbilities().instabuild;
        var cost = materials(changedPieces);
        if (!creative && payment(inventory.items, cost).isEmpty())
            return pieces.stream().anyMatch(piece -> piece.part() != Part.STRUCTURE) ? "prefab_lit_materials" : "prefab_materials";
        var writes = new HashSet<BlockPos>();
        boolean placed = placeAtomically(List.copyOf(replacements.keySet()),
                pos -> {
                    if (!level.getBlockState(pos).equals(previous.get(pos))) {
                        issue.accept(pos);
                        return false;
                    }
                    writes.add(pos);
                    if (level.setBlock(pos, replacements.get(pos), PLACEMENT_FLAGS)) return true;
                    issue.accept(pos);
                    return false;
                }, pos -> {
                    if (writes.contains(pos) && level.getBlockState(pos).equals(replacements.get(pos)))
                        level.setBlock(pos, previous.get(pos), PLACEMENT_FLAGS);
                }, () -> {
                    for (Piece piece : pieces) {
                        if (!level.getBlockState(piece.pos()).is(piece.part().block())) {
                            issue.accept(piece.pos());
                            return false;
                        }
                    }
                    var debit = creative ? Optional.of(List.<Debit>of()) : payment(inventory.items, cost);
                    if (debit.isEmpty()) return false;
                    debit.get().forEach(entry -> entry.stack().shrink(entry.count()));
                    boolean committed = false;
                    try {
                        committed = airport.replaceGroundConfiguration(proposal.ground().revision() - 1,
                                proposal.facilities(), proposal.ground());
                        return committed;
                    } finally {
                        if (!committed) debit.get().forEach(entry -> entry.stack().grow(entry.count()));
                        if (!debit.get().isEmpty()) inventory.setChanged();
                    }
                });
        if (!placed) return "prefab_failed";
        var changed = new LinkedHashSet<ChunkPos>();
        for (BlockPos pos : replacements.keySet()) changed.add(new ChunkPos(pos));
        for (ChunkPos chunk : changed) SharedMapTerrainService.global().changedChunk(level, chunk.x, chunk.z);
        return "ok";
    }

    private static boolean replaceableSurface(BlockState state) {
        return state.is(Blocks.SMOOTH_STONE) || state.is(Blocks.STONE) || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.ANDESITE) || state.is(Blocks.POLISHED_ANDESITE)
                || state.is(Blocks.GRAY_CONCRETE) || state.is(Blocks.LIGHT_GRAY_CONCRETE) || state.is(Blocks.BLACK_CONCRETE)
                || state.is(Blocks.WHITE_CONCRETE) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT);
    }

    private static String reject(Consumer<BlockPos> issue, BlockPos pos, String code) {
        issue.accept(pos);
        return code;
    }

    private record Debit(ItemStack stack, int count) {}

    private static Optional<List<Debit>> payment(List<ItemStack> stacks, Materials cost) {
        var debits = new ArrayList<Debit>();
        if (!collectDebit(stacks, Blocks.SMOOTH_STONE, cost.stone(), debits)
                || !collectDebit(stacks, Blocks.WHITE_CONCRETE, cost.markings(), debits)
                || !collectDebit(stacks, Blocks.SEA_LANTERN, cost.lights(), debits)) return Optional.empty();
        return Optional.of(debits);
    }

    private static boolean collectDebit(List<ItemStack> stacks, Block block, int count, List<Debit> debits) {
        if (count == 0) return true;
        ItemStack plain = new ItemStack(block);
        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, plain)) continue;
            int amount = Math.min(count, stack.getCount());
            debits.add(new Debit(stack, amount));
            count -= amount;
            if (count == 0) return true;
        }
        return false;
    }

    static boolean placeAtomically(List<BlockPos> blocks, Predicate<BlockPos> place, Consumer<BlockPos> restore,
                                   BooleanSupplier commit) {
        var attempted = new ArrayList<BlockPos>();
        try {
            for (BlockPos pos : blocks) {
                attempted.add(pos);
                if (!place.test(pos)) return false;
            }
            if (commit.getAsBoolean()) {
                attempted.clear();
                return true;
            }
            return false;
        } catch (RuntimeException failure) {
            LogUtils.getLogger().warn("Airport prefab placement failed", failure);
            return false;
        } finally {
            for (BlockPos pos : attempted.reversed()) {
                try {
                    restore.accept(pos);
                } catch (RuntimeException failure) {
                    LogUtils.getLogger().error("Could not roll back airport prefab block {}", pos, failure);
                }
            }
        }
    }
}
