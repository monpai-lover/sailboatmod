package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimAccessLevel;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class NationClaimService {
    private static final int CLAIM_COST = 100;
    private static final int SPAWN_PROTECTED_RADIUS_CHUNKS = 4;
    private static final Set<String> BLOCKED_CLAIM_DIMENSIONS = Set.of(
            Level.NETHER.location().toString(),
            Level.END.location().toString()
    );
    private static final Set<String> SUPPRESSED_CORE_REMOVALS = new HashSet<>();

    public static int claimCost() {
        return CLAIM_COST;
    }

    public static NationResult placeCore(ServerPlayer player, BlockPos pos) {
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            NationResult autoCreate = NationService.ensureNationForCorePlacement(player);
            if (!autoCreate.success()) {
                return autoCreate;
            }
            data = NationSavedData.get(player.level());
            member = data.getMember(player.getUUID());
            if (member == null) {
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.core.no_nation"));
            }
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.PLACE_CORE)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.core.no_permission"));
        }

        NationRecord nation = data.getNation(member.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }

        TownRecord capitalTown = TownService.getCapitalTown(data, nation);
        if (capitalTown == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.need_town"));
        }
        if (!capitalTown.hasCore()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.core.need_capital_town_core", capitalTown.name()));
        }
        TownRecord occupiedTown = TownService.getTownAt(player.level(), pos);
        if (occupiedTown == null || !capitalTown.townId().equals(occupiedTown.townId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.core.must_place_in_capital_town", capitalTown.name()));
        }

        ChunkPos chunkPos = new ChunkPos(pos);
        Component restrictionMessage = getClaimRestrictionMessage(player.level(), chunkPos, true);
        if (restrictionMessage != null) {
            return NationResult.failure(restrictionMessage);
        }

        NationClaimRecord existingClaim = data.getClaim(player.level(), chunkPos);
        if (existingClaim != null
                && !nation.nationId().equals(existingClaim.nationId())
                && !capitalTown.townId().equals(existingClaim.townId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.occupied"));
        }

        boolean relocating = nation.hasCore()
                && (!player.level().dimension().location().toString().equalsIgnoreCase(nation.coreDimension())
                || nation.corePos() != pos.asLong());
        String previousCoreDimension = nation.coreDimension();
        long previousCorePos = nation.corePos();

        NationRecord updated = new NationRecord(
                nation.nationId(),
                nation.name(),
                nation.shortName(),
                nation.primaryColorRgb(),
                nation.secondaryColorRgb(),
                nation.leaderUuid(),
                nation.createdAt(),
                nation.capitalTownId(),
                player.level().dimension().location().toString(),
                pos.asLong(),
                nation.flagId()
        );
        data.putNation(updated);

        if (existingClaim == null) {
            data.putClaim(new NationClaimRecord(
                    player.level().dimension().location().toString(),
                    chunkPos.x,
                    chunkPos.z,
                    nation.nationId(),
                    capitalTown.townId(),
                    NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(),
                    System.currentTimeMillis()
            ));
        } else {
            data.putClaim(new NationClaimRecord(
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
        if (relocating) {
            removePreviousNationCoreBlock(player, previousCoreDimension, previousCorePos);
            refundRelocationCore(player);
        }
        return NationResult.success(Component.translatable(
                relocating ? "command.sailboatmod.nation.core.moved" : "command.sailboatmod.nation.core.placed",
                nation.name()));
    }

    public static NationResult pickupCore(ServerPlayer player, BlockPos pos) {
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.core.no_nation"));
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.PLACE_CORE)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.core.no_permission"));
        }

        NationRecord nation = data.getNation(member.nationId());
        if (nation == null || !nation.hasCore()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.core.not_placed"));
        }
        if (!isNationCore(player.level(), pos, nation)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.core.not_active"));
        }

        NationRecord updated = new NationRecord(
                nation.nationId(),
                nation.name(),
                nation.shortName(),
                nation.primaryColorRgb(),
                nation.secondaryColorRgb(),
                nation.leaderUuid(),
                nation.createdAt(),
                nation.capitalTownId(),
                "",
                NationRecord.noCorePos(),
                nation.flagId()
        );
        data.putNation(updated);
        suppressCoreRemoval(player.level(), pos);
        player.level().removeBlock(pos, false);

        ItemStack relocatedCore = new ItemStack(ModBlocks.NATION_CORE_BLOCK.get());
        CompoundTag tag = relocatedCore.getOrCreateTag();
        tag.putString("RelocatingNationId", nation.nationId());
        tag.putString("RelocatingNationName", nation.name());
        if (!player.getInventory().add(relocatedCore)) {
            player.drop(relocatedCore, false);
        } else {
            player.containerMenu.broadcastChanges();
        }
        return NationResult.success(Component.translatable("command.sailboatmod.nation.core.picked_up"));
    }

    public static NationResult removeCoreBlock(ServerPlayer player) {
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.core.no_nation"));
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.PLACE_CORE)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.core.no_permission"));
        }
        NationRecord nation = data.getNation(member.nationId());
        if (nation == null || !nation.hasCore()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.core.not_placed"));
        }
        if (!player.level().dimension().location().toString().equalsIgnoreCase(nation.coreDimension())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.core.not_active"));
        }
        BlockPos corePos = BlockPos.of(nation.corePos());
        if (!player.level().hasChunkAt(corePos)
                || !player.level().getBlockState(corePos).is(ModBlocks.NATION_CORE_BLOCK.get())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.core.expected_missing"));
        }
        player.level().removeBlock(corePos, false);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.core.removed"));
    }

    public static void onCoreRemoved(Level level, BlockPos pos) {
        if (level == null || level.isClientSide || pos == null) {
            return;
        }
        if (consumeSuppressedCoreRemoval(level, pos)) {
            return;
        }
        NationSavedData data = NationSavedData.get(level);
        for (NationRecord nation : data.getNations()) {
            if (!isNationCore(level, pos, nation)) {
                continue;
            }
            NationRecord updated = new NationRecord(
                    nation.nationId(),
                    nation.name(),
                    nation.shortName(),
                    nation.primaryColorRgb(),
                    nation.secondaryColorRgb(),
                    nation.leaderUuid(),
                    nation.createdAt(),
                    nation.capitalTownId(),
                    "",
                    NationRecord.noCorePos(),
                    nation.flagId()
            );
            data.putNation(updated);
            data.clearClaimsForNation(nation.nationId());
            return;
        }
    }

    public static NationResult claimChunk(ServerPlayer player, ChunkPos chunkPos) {
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.no_nation"));
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_CLAIMS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.no_permission"));
        }

        NationRecord nation = data.getNation(member.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (!nation.hasCore()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.need_core"));
        }

        TownRecord claimTown = TownService.getCapitalTown(data, nation);
        if (claimTown == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.need_town"));
        }

        Component restrictionMessage = getClaimRestrictionMessage(player.level(), chunkPos, false);
        if (restrictionMessage != null) {
            return NationResult.failure(restrictionMessage);
        }

        NationClaimRecord existing = data.getClaim(player.level(), chunkPos);
        if (existing != null) {
            if (nation.nationId().equals(existing.nationId())) {
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.already_owned"));
            }
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.occupied"));
        }

        if (!isClaimAdjacentToTown(data, player.level(), chunkPos, nation, claimTown)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.not_adjacent"));
        }

        if (CLAIM_COST > 0) {
            if (!chargePlayer(player, CLAIM_COST)) {
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.not_enough_money", GoldStandardEconomy.formatBalance(CLAIM_COST)));
            }
        }

        data.putClaim(new NationClaimRecord(
                player.level().dimension().location().toString(),
                chunkPos.x,
                chunkPos.z,
                nation.nationId(),
                claimTown.townId(),
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                NationClaimAccessLevel.MEMBER.id(),
                System.currentTimeMillis()
        ));
        return NationResult.success(CLAIM_COST > 0
                ? Component.translatable("command.sailboatmod.nation.claim.success_paid", chunkPos.x, chunkPos.z, CLAIM_COST)
                : Component.translatable("command.sailboatmod.nation.claim.success", chunkPos.x, chunkPos.z));
    }

    public static NationResult claimArea(ServerPlayer player, int minX, int maxX, int minZ, int maxZ) {
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.no_nation"));
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_CLAIMS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.no_permission"));
        }
        NationRecord nation = data.getNation(member.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (!nation.hasCore()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.need_core"));
        }
        TownRecord claimTown = TownService.getCapitalTown(data, nation);
        if (claimTown == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.need_town"));
        }

        List<ChunkPos> pending = new java.util.ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                pending.add(new ChunkPos(x, z));
            }
        }

        int claimed = 0;
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            List<ChunkPos> remaining = new java.util.ArrayList<>();
            for (ChunkPos target : pending) {
                Component restriction = getClaimRestrictionMessage(player.level(), target, false);
                if (restriction != null) continue;
                NationClaimRecord existing = data.getClaim(player.level(), target);
                if (existing != null) continue;
                if (!isClaimAdjacentToTown(data, player.level(), target, nation, claimTown)) {
                    remaining.add(target);
                    continue;
                }
                if (CLAIM_COST > 0 && !chargePlayer(player, CLAIM_COST)) {
                    return NationResult.success(Component.translatable("command.sailboatmod.nation.claim.batch_partial", claimed));
                }
                data.putClaim(new NationClaimRecord(
                        player.level().dimension().location().toString(), target.x, target.z,
                        nation.nationId(), claimTown.townId(),
                        NationClaimAccessLevel.MEMBER.id(), NationClaimAccessLevel.MEMBER.id(),
                        NationClaimAccessLevel.MEMBER.id(), NationClaimAccessLevel.MEMBER.id(),
                        NationClaimAccessLevel.MEMBER.id(), NationClaimAccessLevel.MEMBER.id(),
                        NationClaimAccessLevel.MEMBER.id(), System.currentTimeMillis()));
                claimed++;
                progress = true;
            }
            pending = remaining;
        }
        if (claimed == 0) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.not_adjacent"));
        }
        return NationResult.success(Component.translatable("command.sailboatmod.nation.claim.batch_success", claimed));
    }

    public static NationResult unclaimArea(ServerPlayer player, int minX, int maxX, int minZ, int maxZ) {
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.no_nation"));
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_CLAIMS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.no_permission"));
        }
        NationRecord nation = data.getNation(member.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        int unclaimed = 0;
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                ChunkPos cp = new ChunkPos(x, z);
                NationClaimRecord existing = data.getClaim(player.level(), cp);
                if (existing == null || !nation.nationId().equals(existing.nationId())) continue;
                if (isCoreChunk(player.level(), cp, nation)) continue;
                data.removeClaim(player.level().dimension().location().toString(), x, z);
                unclaimed++;
            }
        }
        if (unclaimed == 0) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.not_owned"));
        }
        return NationResult.success(Component.translatable("command.sailboatmod.nation.unclaim.batch_success", unclaimed));
    }

    public static NationResult unclaimChunk(ServerPlayer player, ChunkPos chunkPos) {
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.no_nation"));
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_CLAIMS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.no_permission"));
        }

        NationRecord nation = data.getNation(member.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }

        NationClaimRecord existing = data.getClaim(player.level(), chunkPos);
        if (existing == null || !nation.nationId().equals(existing.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.not_owned"));
        }
        if (isCoreChunk(player.level(), chunkPos, nation)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.core_chunk"));
        }

        data.removeClaim(player.level().dimension().location().toString(), chunkPos.x, chunkPos.z);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.unclaim.success", chunkPos.x, chunkPos.z));
    }

    public static NationResult setChunkPermission(ServerPlayer player, ChunkPos chunkPos, String actionId, String levelId) {
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.no_nation"));
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_CLAIMS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.no_permission"));
        }

        NationClaimRecord claim = data.getClaim(player.level(), chunkPos);
        if (claim == null || !member.nationId().equals(claim.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claimperm.not_owned"));
        }

        NationClaimAccessLevel accessLevel = NationClaimAccessLevel.fromId(levelId);
        if (accessLevel == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claimperm.level_invalid", levelId));
        }

        String action = actionId == null ? "" : actionId.trim().toLowerCase(Locale.ROOT);
        NationClaimRecord updated = claim.withPermission(action, accessLevel);
        if (updated == claim) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claimperm.action_invalid", actionId));
        }

        data.putClaim(updated);
        return NationResult.success(Component.translatable(
                "command.sailboatmod.nation.claimperm.success",
                formatAction(action),
                formatLevel(accessLevel)
        ));
    }

    private static Component getClaimRestrictionMessage(Level level, ChunkPos chunkPos, boolean corePlacement) {
        if (isBlockedClaimDimension(level)) {
            return Component.translatable(
                    corePlacement
                            ? "command.sailboatmod.nation.core.blocked_dimension"
                            : "command.sailboatmod.nation.claim.blocked_dimension",
                    level.dimension().location()
            );
        }
        if (isProtectedSpawnChunk(level, chunkPos)) {
            return Component.translatable(
                    corePlacement
                            ? "command.sailboatmod.nation.core.protected_spawn"
                            : "command.sailboatmod.nation.claim.protected_spawn",
                    SPAWN_PROTECTED_RADIUS_CHUNKS
            );
        }
        return null;
    }

    public static List<Component> describeChunkPermissions(Level level, ChunkPos chunkPos) {
        NationSavedData data = NationSavedData.get(level);
        NationClaimRecord claim = data.getClaim(level, chunkPos);
        if (claim == null) {
            return List.of(Component.translatable("command.sailboatmod.nation.claimperm.not_claimed"));
        }
        return List.of(
                Component.translatable("command.sailboatmod.nation.claimperm.info.header", chunkPos.x, chunkPos.z),
                Component.translatable("command.sailboatmod.nation.claimperm.info.entry", formatAction("break"), formatLevel(NationClaimAccessLevel.fromId(claim.breakAccessLevel()))),
                Component.translatable("command.sailboatmod.nation.claimperm.info.entry", formatAction("place"), formatLevel(NationClaimAccessLevel.fromId(claim.placeAccessLevel()))),
                Component.translatable("command.sailboatmod.nation.claimperm.info.entry", formatAction("use"), formatLevel(NationClaimAccessLevel.fromId(claim.useAccessLevel()))),
                Component.translatable("command.sailboatmod.nation.claimperm.info.entry", formatAction("container"), formatLevel(NationClaimAccessLevel.fromId(claim.containerAccessLevel()))),
                Component.translatable("command.sailboatmod.nation.claimperm.info.entry", formatAction("redstone"), formatLevel(NationClaimAccessLevel.fromId(claim.redstoneAccessLevel()))),
                Component.translatable("command.sailboatmod.nation.claimperm.info.entry", formatAction("entity_use"), formatLevel(NationClaimAccessLevel.fromId(claim.entityUseAccessLevel()))),
                Component.translatable("command.sailboatmod.nation.claimperm.info.entry", formatAction("entity_damage"), formatLevel(NationClaimAccessLevel.fromId(claim.entityDamageAccessLevel())))
        );
    }

    public static boolean canBreak(Level level, UUID playerUuid, BlockPos pos) {
        return NationPermissionService.evaluateBreak(level, playerUuid, pos).allowed();
    }

    public static boolean canPlace(Level level, UUID playerUuid, BlockPos pos) {
        return NationPermissionService.evaluatePlace(level, playerUuid, pos).allowed();
    }

    public static boolean canUseBlock(Level level, UUID playerUuid, BlockPos pos) {
        return NationPermissionService.evaluateUse(level, playerUuid, pos).allowed();
    }

    public static boolean canOpenContainer(Level level, UUID playerUuid, BlockPos pos) {
        return NationPermissionService.evaluateContainer(level, playerUuid, pos).allowed();
    }

    public static boolean canBreakCore(Level level, UUID playerUuid, BlockPos pos) {
        if (level == null || playerUuid == null || pos == null) {
            return false;
        }
        NationSavedData data = NationSavedData.get(level);
        NationMemberRecord member = data.getMember(playerUuid);
        if (member == null) {
            return false;
        }
        NationRecord playerNation = data.getNation(member.nationId());
        if (playerNation == null || !NationService.hasPermission(level, playerUuid, NationPermission.PLACE_CORE)) {
            return false;
        }
        if (isNationCore(level, pos, playerNation)) {
            return true;
        }
        NationRecord territoryNation = getNationAt(level, pos);
        return territoryNation != null && territoryNation.nationId().equals(playerNation.nationId());
    }

    public static NationRecord getNationAt(Level level, BlockPos pos) {
        return NationPermissionService.getNationAt(level, pos);
    }

    public static boolean isClaimed(Level level, BlockPos pos) {
        return NationPermissionService.isClaimed(level, pos);
    }

    public static boolean isNationCore(Level level, BlockPos pos, NationRecord nation) {
        if (level == null || pos == null || nation == null || !nation.hasCore()) {
            return false;
        }
        return level.dimension().location().toString().equalsIgnoreCase(nation.coreDimension())
                && pos.asLong() == nation.corePos();
    }

    private static void suppressCoreRemoval(Level level, BlockPos pos) {
        SUPPRESSED_CORE_REMOVALS.add(coreRemovalKey(level, pos));
    }

    private static boolean consumeSuppressedCoreRemoval(Level level, BlockPos pos) {
        return SUPPRESSED_CORE_REMOVALS.remove(coreRemovalKey(level, pos));
    }

    private static String coreRemovalKey(Level level, BlockPos pos) {
        return level.dimension().location() + "|" + pos.asLong();
    }

    private static boolean isSameChunk(ChunkPos left, ChunkPos right) {
        return left != null && right != null && left.x == right.x && left.z == right.z;
    }

    private static void removePreviousNationCoreBlock(ServerPlayer player, String dimensionId, long corePos) {
        if (player == null || player.getServer() == null || dimensionId == null || dimensionId.isBlank() || corePos == NationRecord.noCorePos()) {
            return;
        }
        ServerLevel coreLevel = resolveDimension(player.getServer(), dimensionId);
        if (coreLevel == null) {
            return;
        }
        BlockPos oldPos = BlockPos.of(corePos);
        if (!coreLevel.hasChunkAt(oldPos)) {
            return;
        }
        if (coreLevel.getBlockState(oldPos).is(ModBlocks.NATION_CORE_BLOCK.get())) {
            coreLevel.removeBlock(oldPos, false);
        }
    }

    private static void refundRelocationCore(ServerPlayer player) {
        if (player == null || player.getAbilities().instabuild) {
            return;
        }
        player.getInventory().placeItemBackInInventory(new ItemStack(ModBlocks.NATION_CORE_BLOCK.get()));
        player.getInventory().setChanged();
    }

    private static ServerLevel resolveDimension(net.minecraft.server.MinecraftServer server, String dimensionId) {
        if (server == null || dimensionId == null || dimensionId.isBlank()) {
            return null;
        }
        net.minecraft.resources.ResourceLocation dimLoc = net.minecraft.resources.ResourceLocation.tryParse(dimensionId);
        if (dimLoc == null) {
            return null;
        }
        return server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimLoc));
    }

    private static boolean isCoreChunk(Level level, ChunkPos chunkPos, NationRecord nation) {
        if (level == null || chunkPos == null || nation == null || !nation.hasCore()) {
            return false;
        }
        if (!level.dimension().location().toString().equalsIgnoreCase(nation.coreDimension())) {
            return false;
        }
        ChunkPos coreChunk = new ChunkPos(BlockPos.of(nation.corePos()));
        return coreChunk.x == chunkPos.x && coreChunk.z == chunkPos.z;
    }

    private static boolean isClaimAdjacentToTown(NationSavedData data, Level level, ChunkPos target, NationRecord nation, TownRecord town) {
        List<NationClaimRecord> townClaims = town == null ? List.of() : TownService.getManagedClaims(data, town);
        if (townClaims.isEmpty()) {
            return isCoreChunk(level, target, nation);
        }
        for (NationClaimRecord claim : townClaims) {
            if (!level.dimension().location().toString().equalsIgnoreCase(claim.dimensionId())) {
                continue;
            }
            int dx = Math.abs(claim.chunkX() - target.x);
            int dz = Math.abs(claim.chunkZ() - target.z);
            if (dx + dz == 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockedClaimDimension(Level level) {
        return level != null && BLOCKED_CLAIM_DIMENSIONS.contains(level.dimension().location().toString());
    }

    private static boolean isProtectedSpawnChunk(Level level, ChunkPos targetChunk) {
        if (!(level instanceof ServerLevel serverLevel) || targetChunk == null || !Level.OVERWORLD.equals(level.dimension())) {
            return false;
        }
        ChunkPos spawnChunk = new ChunkPos(serverLevel.getSharedSpawnPos());
        return Math.abs(targetChunk.x - spawnChunk.x) <= SPAWN_PROTECTED_RADIUS_CHUNKS
                && Math.abs(targetChunk.z - spawnChunk.z) <= SPAWN_PROTECTED_RADIUS_CHUNKS;
    }

    private static Component formatAction(String action) {
        return Component.translatable("command.sailboatmod.nation.claimperm.action." + action);
    }

    private static Component formatLevel(NationClaimAccessLevel accessLevel) {
        NationClaimAccessLevel safeLevel = accessLevel == null ? NationClaimAccessLevel.MEMBER : accessLevel;
        return Component.translatable("command.sailboatmod.nation.claimperm.level." + safeLevel.id());
    }

    private static boolean chargePlayer(ServerPlayer player, int amount) {
        if (player == null || amount <= 0 || player.getAbilities().instabuild) {
            return true;
        }
        return Boolean.TRUE.equals(GoldStandardEconomy.tryWithdraw(player, amount));
    }

    private NationClaimService() {
    }
}
