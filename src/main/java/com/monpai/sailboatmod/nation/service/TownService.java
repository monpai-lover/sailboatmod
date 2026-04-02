package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimAccessLevel;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.model.TownNationRequestRecord;
import com.monpai.sailboatmod.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class TownService {
    private static final Set<String> SUPPRESSED_CORE_REMOVALS = new HashSet<>();

    public static NationResult createTown(ServerPlayer actor, String rawName) {
        return tryCreateTown(actor, NationSavedData.get(actor.level()), rawName).result();
    }

    public static NationResult renameTown(ServerPlayer actor, String rawTownName, String rawNewName) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = data.findTownByName(rawTownName);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", rawTownName));
        }
        if (!canManageTown(actor, data, town)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.no_permission"));
        }
        NationResult result = renameTownInternal(data, town, rawNewName);
        if (result.success()) {
            TownFlagBlockTracker.refreshTownFlags(actor.getServer(), town.townId());
        }
        return result;
    }

    public static NationResult renameTownById(ServerPlayer actor, String townId, String rawNewName) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", townId));
        }
        if (!canManageTown(actor, data, town)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.no_permission"));
        }
        NationResult result = renameTownInternal(data, town, rawNewName);
        if (result.success()) {
            TownFlagBlockTracker.refreshTownFlags(actor.getServer(), town.townId());
        }
        return result;
    }

    private static NationResult renameTownInternal(NationSavedData data, TownRecord town, String rawNewName) {
        String name = TownRecord.normalizeName(rawNewName);
        if (!TownRecord.isValidName(name)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.name_invalid", TownRecord.MIN_NAME_LENGTH, TownRecord.MAX_NAME_LENGTH));
        }
        if (TownRecord.isReservedName(name)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.name_reserved", name));
        }
        TownRecord existing = data.findTownByName(name);
        if (existing != null && !existing.townId().equals(town.townId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.name_taken", name));
        }
        if (name.equals(town.name())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.rename.unchanged"));
        }

        data.putTown(new TownRecord(
                town.townId(),
                town.nationId(),
                name,
                town.mayorUuid(),
                town.createdAt(),
                town.coreDimension(),
                town.corePos(),
                town.flagId(),
                town.cultureId()
        ));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.rename.success", name));
    }

    public static NationResult assignMayor(ServerPlayer actor, String rawTownName, ServerPlayer target) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = data.findTownByName(rawTownName);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", rawTownName));
        }
        NationRecord nation = town.nationId().isBlank() ? null : data.getNation(town.nationId());
        if (nation == null || !actor.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.mayor.no_permission"));
        }
        NationMemberRecord targetMember = data.getMember(target.getUUID());
        if (targetMember == null || !town.nationId().equals(targetMember.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.mayor.invalid_target", target.getGameProfile().getName()));
        }

        data.putTown(new TownRecord(
                town.townId(),
                town.nationId(),
                town.name(),
                target.getUUID(),
                town.createdAt(),
                town.coreDimension(),
                town.corePos(),
                town.flagId(),
                town.cultureId()
        ));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.mayor.success", town.name(), target.getGameProfile().getName()));
    }

    public static NationResult abandonTown(ServerPlayer actor, String townId) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", townId));
        }
        if (!actor.getUUID().equals(town.mayorUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.abandon.no_permission"));
        }

        if (!town.nationId().isBlank()) {
            NationRecord nation = data.getNation(town.nationId());
            if (nation != null && town.townId().equals(nation.capitalTownId())) {
                data.putNation(new NationRecord(
                        nation.nationId(),
                        nation.name(),
                        nation.shortName(),
                        nation.primaryColorRgb(),
                        nation.secondaryColorRgb(),
                        nation.leaderUuid(),
                        nation.createdAt(),
                        "",
                        nation.coreDimension(),
                        nation.corePos(),
                        nation.flagId()
                ));
            }
        }

        for (NationClaimRecord claim : data.getClaimsForTown(town.townId())) {
            data.removeClaim(claim.dimensionId(), claim.chunkX(), claim.chunkZ());
        }

        if (town.hasCore()) {
            Level level = actor.level();
            if (level.dimension().location().toString().equalsIgnoreCase(town.coreDimension())) {
                BlockPos corePos = BlockPos.of(town.corePos());
                if (level.hasChunkAt(corePos) && level.getBlockState(corePos).is(ModBlocks.TOWN_CORE_BLOCK.get())) {
                    level.removeBlock(corePos, false);
                    if (level instanceof ServerLevel serverLevel) {
                        net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(
                                serverLevel, corePos.getX() + 0.5, corePos.getY() + 0.5, corePos.getZ() + 0.5,
                                new net.minecraft.world.item.ItemStack(ModBlocks.TOWN_CORE_BLOCK.get()));
                        item.setDefaultPickUpDelay();
                        serverLevel.addFreshEntity(item);
                    }
                }
            }
        }

        data.removeTown(town.townId());
        TownFlagBlockTracker.refreshTownFlags(actor.getServer(), town.townId());

        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.abandon.success", town.name()));
    }

    public static NationResult removeCoreBlock(ServerPlayer actor, String townId) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", townId));
        }
        if (!actor.getUUID().equals(town.mayorUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.no_permission"));
        }
        if (!town.hasCore()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.core.none"));
        }
        Level level = actor.level();
        if (level.dimension().location().toString().equalsIgnoreCase(town.coreDimension())) {
            BlockPos corePos = BlockPos.of(town.corePos());
            if (level.hasChunkAt(corePos) && level.getBlockState(corePos).is(com.monpai.sailboatmod.registry.ModBlocks.TOWN_CORE_BLOCK.get())) {
                level.removeBlock(corePos, false);
                return NationResult.success(Component.translatable("command.sailboatmod.nation.town.core.removed"));
            }
        }
        return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.core.expected_missing"));
    }

    public static NationResult assignMayorById(ServerPlayer actor, String townId, UUID targetUuid) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", townId));
        }
        NationRecord nation = town.nationId().isBlank() ? null : data.getNation(town.nationId());
        if (nation == null || !actor.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.mayor.no_permission"));
        }
        NationMemberRecord targetMember = targetUuid == null ? null : data.getMember(targetUuid);
        String targetName = resolvePlayerName(actor, data, targetUuid);
        if (targetMember == null || !town.nationId().equals(targetMember.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.mayor.invalid_target", targetName));
        }
        if (targetUuid.equals(town.mayorUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.mayor.unchanged", targetName));
        }

        data.putTown(new TownRecord(
                town.townId(),
                town.nationId(),
                town.name(),
                targetUuid,
                town.createdAt(),
                town.coreDimension(),
                town.corePos(),
                town.flagId(),
                town.cultureId()
        ));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.mayor.success", town.name(), targetName));
    }

    public static NationResult placeCore(ServerPlayer actor, BlockPos pos, ItemStack stack) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);

        PlacementPreparation preparation = prepareTownForPlacement(actor, data, stack);
        if (preparation.failure() != null) {
            return preparation.failure();
        }

        TownRecord selectedTown = preparation.town();
        if (selectedTown == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.core.no_town"));
        }

        TownRecord occupiedTown = getTownAt(actor.level(), pos);
        if (occupiedTown != null && !occupiedTown.townId().equals(selectedTown.townId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.core.occupied", occupiedTown.name()));
        }

        ChunkPos coreChunk = new ChunkPos(pos);
        NationClaimRecord existingClaim = data.getClaim(actor.level(), coreChunk);
        if (existingClaim != null) {
            if (selectedTown.nationId().isBlank() || !selectedTown.nationId().equals(existingClaim.nationId())) {
                String occupiedName = occupiedTown == null ? selectedTown.name() : occupiedTown.name();
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.core.occupied", occupiedName));
            }
        }

        boolean relocating = selectedTown.hasCore()
                && (!actor.level().dimension().location().toString().equalsIgnoreCase(selectedTown.coreDimension())
                || selectedTown.corePos() != pos.asLong());
        String previousCoreDimension = selectedTown.coreDimension();
        long previousCorePos = selectedTown.corePos();

        TownRecord updated = new TownRecord(
                selectedTown.townId(),
                selectedTown.nationId(),
                selectedTown.name(),
                selectedTown.mayorUuid(),
                selectedTown.createdAt(),
                actor.level().dimension().location().toString(),
                pos.asLong(),
                selectedTown.flagId(),
                selectedTown.cultureId()
        );
        data.putTown(updated);

        if (existingClaim == null) {
            data.putClaim(new NationClaimRecord(
                    actor.level().dimension().location().toString(),
                    coreChunk.x,
                    coreChunk.z,
                    updated.nationId(),
                    updated.townId(),
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
                    updated.nationId().isBlank() ? existingClaim.nationId() : updated.nationId(),
                    updated.townId(),
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
            removePreviousTownCoreBlock(actor, previousCoreDimension, previousCorePos);
            refundRelocationCore(actor, ModBlocks.TOWN_CORE_BLOCK.get());
        }

        return NationResult.success(Component.translatable(
                relocating
                        ? "command.sailboatmod.nation.town.core.moved"
                        : preparation.createdNewTown()
                        ? "command.sailboatmod.nation.town.core.created_and_placed"
                        : "command.sailboatmod.nation.town.core.placed",
                updated.name()
        ));
    }

    public static NationResult pickupCore(ServerPlayer actor, BlockPos pos) {
        if (actor == null || pos == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.core.pickup_failed"));
        }
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = getTownByCore(actor.level(), pos);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.core.not_active"));
        }
        if (!canManageTown(actor, data, town)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.no_permission"));
        }

        TownRecord updated = new TownRecord(
                town.townId(),
                town.nationId(),
                town.name(),
                town.mayorUuid(),
                town.createdAt(),
                "",
                TownRecord.noCorePos(),
                town.flagId(),
                town.cultureId()
        );
        data.putTown(updated);
        suppressCoreRemoval(actor.level(), pos);
        actor.level().removeBlock(pos, false);

        ItemStack relocatedCore = new ItemStack(ModBlocks.TOWN_CORE_BLOCK.get());
        CompoundTag tag = relocatedCore.getOrCreateTag();
        tag.putString("RelocatingTownId", town.townId());
        tag.putString("RelocatingTownName", town.name());
        if (!actor.getInventory().add(relocatedCore)) {
            actor.drop(relocatedCore, false);
        } else {
            actor.containerMenu.broadcastChanges();
        }
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.core.picked_up"));
    }

    public static void onCoreRemoved(Level level, BlockPos pos) {
        if (level == null || level.isClientSide || pos == null) {
            return;
        }
        if (consumeSuppressedCoreRemoval(level, pos)) {
            return;
        }
        NationSavedData data = NationSavedData.get(level);
        for (TownRecord town : data.getTowns()) {
            if (!isTownCore(level, pos, town)) {
                continue;
            }
            removeDependentNationCore(level, data, town);
            TownRecord updated = new TownRecord(
                    town.townId(),
                    town.nationId(),
                    town.name(),
                    town.mayorUuid(),
                    town.createdAt(),
                    "",
                    TownRecord.noCorePos(),
                    town.flagId(),
                    town.cultureId()
            );
            data.putTown(updated);
            return;
        }
    }

    public static List<Component> describeTown(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = data.findTownByName(rawTownName);
        if (town == null) {
            return List.of(Component.translatable("command.sailboatmod.nation.town.not_found", rawTownName));
        }
        return describeTown(actor, data, town);
    }

    public static List<Component> describeTown(ServerPlayer actor, NationSavedData data, TownRecord town) {
        if (town == null) {
            return List.of(Component.translatable("command.sailboatmod.nation.town.not_found", "?"));
        }
        NationRecord nation = town.nationId().isBlank() ? null : data.getNation(town.nationId());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.nation.town.info.header", town.name()));
        lines.add(Component.translatable("command.sailboatmod.nation.town.info.nation", nation == null ? "-" : nation.name()));
        lines.add(Component.translatable("command.sailboatmod.nation.town.info.mayor", resolvePlayerName(actor, data, town.mayorUuid())));
        lines.add(Component.translatable("command.sailboatmod.nation.town.info.claims", countManagedClaims(data, town)));
        lines.add(town.hasCore()
                ? Component.translatable("command.sailboatmod.nation.town.info.core.set", formatCoreLocation(town))
                : Component.translatable("command.sailboatmod.nation.town.info.core.none"));
        return lines;
    }

    public static TownRecord getCapitalTown(NationSavedData data, NationRecord nation) {
        if (data == null || nation == null || nation.capitalTownId().isBlank()) {
            return null;
        }
        TownRecord town = data.getTown(nation.capitalTownId());
        if (town == null) {
            return null;
        }
        return nation.nationId().equals(town.nationId()) ? town : null;
    }

    public static TownRecord bindStandaloneTownToNation(NationSavedData data, UUID mayorUuid, String nationId) {
        if (data == null || mayorUuid == null || nationId == null || nationId.isBlank()) {
            return null;
        }
        List<TownRecord> available = new ArrayList<>();
        for (TownRecord town : data.getTownsForMayor(mayorUuid)) {
            if (town.nationId().isBlank()) {
                available.add(town);
            }
        }
        available.sort(Comparator.comparingLong(TownRecord::createdAt));
        if (available.isEmpty()) {
            return null;
        }
        TownRecord chosen = available.get(0);
        TownRecord updated = new TownRecord(
                chosen.townId(),
                nationId,
                chosen.name(),
                chosen.mayorUuid(),
                chosen.createdAt(),
                chosen.coreDimension(),
                chosen.corePos(),
                chosen.flagId(),
                chosen.cultureId()
        );
        data.putTown(updated);
        return updated;
    }

    public static TownRecord getTownAt(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        NationSavedData data = NationSavedData.get(level);
        ChunkPos chunkPos = new ChunkPos(pos);
        NationClaimRecord claim = data.getClaim(level, chunkPos);
        if (claim != null) {
            if (!claim.townId().isBlank()) {
                TownRecord claimedTown = data.getTown(claim.townId());
                if (claimedTown != null) {
                    return claimedTown;
                }
            }
            if (!claim.nationId().isBlank()) {
                TownRecord capitalTown = getCapitalTown(data, data.getNation(claim.nationId()));
                if (capitalTown != null) {
                    return capitalTown;
                }
            }
        }
        for (TownRecord town : data.getTowns()) {
            if (!town.hasCore()) {
                continue;
            }
            if (!level.dimension().location().toString().equalsIgnoreCase(town.coreDimension())) {
                continue;
            }
            ChunkPos coreChunk = new ChunkPos(BlockPos.of(town.corePos()));
            if (coreChunk.x == chunkPos.x && coreChunk.z == chunkPos.z) {
                return town;
            }
        }
        return null;
    }

    public static TownRecord getTownByCore(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        for (TownRecord town : NationSavedData.get(level).getTowns()) {
            if (isTownCore(level, pos, town)) {
                return town;
            }
        }
        return null;
    }

    public static TownRecord getManagedTownAt(ServerPlayer actor, BlockPos pos) {
        if (actor == null || pos == null) {
            return null;
        }
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = getTownAt(actor.level(), pos);
        return canManageTown(actor, data, town) ? town : null;
    }

    public static boolean isTownCore(Level level, BlockPos pos, TownRecord town) {
        if (level == null || pos == null || town == null || !town.hasCore()) {
            return false;
        }
        return level.dimension().location().toString().equalsIgnoreCase(town.coreDimension())
                && pos.asLong() == town.corePos();
    }

    public static boolean canBreakCore(Level level, UUID playerUuid, BlockPos pos) {
        if (level == null || playerUuid == null || pos == null) {
            return false;
        }
        NationSavedData data = NationSavedData.get(level);
        TownRecord town = getTownByCore(level, pos);
        if (town != null) {
            if (playerUuid.equals(town.mayorUuid())) {
                return true;
            }
            if (town.nationId().isBlank()) {
                return false;
            }
            NationRecord nation = data.getNation(town.nationId());
            return nation != null && playerUuid.equals(nation.leaderUuid());
        }

        TownRecord managedTown = getTownAt(level, pos);
        if (managedTown == null) {
            return false;
        }
        if (playerUuid.equals(managedTown.mayorUuid())) {
            return true;
        }
        if (managedTown.nationId().isBlank()) {
            return false;
        }
        NationRecord nation = data.getNation(managedTown.nationId());
        return nation != null && playerUuid.equals(nation.leaderUuid());
    }

    public static boolean canManageTown(ServerPlayer actor, NationSavedData data, TownRecord town) {
        if (actor == null || data == null || town == null) {
            return false;
        }
        if (actor.getUUID().equals(town.mayorUuid())) {
            return true;
        }
        if (town.nationId().isBlank()) {
            return false;
        }
        NationRecord nation = data.getNation(town.nationId());
        return nation != null && actor.getUUID().equals(nation.leaderUuid());
    }

    public static boolean isClaimManagedByTown(NationSavedData data, TownRecord town, NationClaimRecord claim) {
        if (data == null || town == null || claim == null) {
            return false;
        }
        if (town.townId().equals(claim.townId())) {
            return true;
        }
        if (!claim.townId().isBlank() || town.nationId().isBlank()) {
            return false;
        }
        NationRecord nation = data.getNation(town.nationId());
        return nation != null
                && town.townId().equals(nation.capitalTownId())
                && nation.nationId().equals(claim.nationId());
    }

    public static List<NationClaimRecord> getManagedClaims(NationSavedData data, TownRecord town) {
        if (data == null || town == null) {
            return List.of();
        }
        if (town.nationId().isBlank()) {
            return List.copyOf(data.getClaimsForTown(town.townId()));
        }
        NationRecord nation = data.getNation(town.nationId());
        if (nation == null || !town.townId().equals(nation.capitalTownId())) {
            return List.copyOf(data.getClaimsForTown(town.townId()));
        }
        List<NationClaimRecord> claims = new ArrayList<>();
        for (NationClaimRecord claim : data.getClaimsForNation(nation.nationId())) {
            if (isClaimManagedByTown(data, town, claim)) {
                claims.add(claim);
            }
        }
        return List.copyOf(claims);
    }

    public static int countManagedClaims(NationSavedData data, TownRecord town) {
        return getManagedClaims(data, town).size();
    }

    private static TownCreationOutcome tryCreateTown(ServerPlayer actor, NationSavedData data, String rawName) {
        NationService.updateKnownPlayer(actor);

        String name = TownRecord.normalizeName(rawName);
        if (!TownRecord.isValidName(name)) {
            return new TownCreationOutcome(null, NationResult.failure(Component.translatable("command.sailboatmod.nation.town.name_invalid", TownRecord.MIN_NAME_LENGTH, TownRecord.MAX_NAME_LENGTH)));
        }
        if (TownRecord.isReservedName(name)) {
            return new TownCreationOutcome(null, NationResult.failure(Component.translatable("command.sailboatmod.nation.town.name_reserved", name)));
        }
        if (data.findTownByName(name) != null) {
            return new TownCreationOutcome(null, NationResult.failure(Component.translatable("command.sailboatmod.nation.town.name_taken", name)));
        }

        String nationId = "";
        NationMemberRecord member = data.getMember(actor.getUUID());
        if (member != null) {
            nationId = member.nationId();
            if (!NationService.hasPermission(actor.level(), actor.getUUID(), NationPermission.MANAGE_CLAIMS)) {
                return new TownCreationOutcome(null, NationResult.failure(Component.translatable("command.sailboatmod.nation.town.no_permission")));
            }
        }

        long now = System.currentTimeMillis();
        TownRecord town = new TownRecord(
                UUID.randomUUID().toString().replace("-", ""),
                nationId,
                name,
                actor.getUUID(),
                now,
                "",
                TownRecord.noCorePos(),
                "",
                "european"
        );
        data.putTown(town);

        if (!nationId.isBlank()) {
            NationRecord nation = data.getNation(nationId);
            if (nation != null && nation.capitalTownId().isBlank()) {
                data.putNation(new NationRecord(
                        nation.nationId(),
                        nation.name(),
                        nation.shortName(),
                        nation.primaryColorRgb(),
                        nation.secondaryColorRgb(),
                        nation.leaderUuid(),
                        nation.createdAt(),
                        town.townId(),
                        nation.coreDimension(),
                        nation.corePos(),
                        nation.flagId()
                ));
            }
        }

        return new TownCreationOutcome(town, NationResult.success(Component.translatable("command.sailboatmod.nation.town.create.success", town.name())));
    }

    private static PlacementPreparation prepareTownForPlacement(ServerPlayer actor, NationSavedData data, ItemStack stack) {
        TownRecord relocatingTown = relocatingTown(actor, data, stack);
        if (relocatingTown != null) {
            return new PlacementPreparation(relocatingTown, false, null);
        }

        List<TownRecord> availableTowns = getManageableTowns(actor, data).stream()
                .filter(town -> !town.hasCore())
                .sorted(Comparator.comparingLong(TownRecord::createdAt).thenComparing(TownRecord::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        String requestedName = requestedTownName(stack);
        if (!requestedName.isBlank()) {
            for (TownRecord town : availableTowns) {
                if (town.name().equalsIgnoreCase(requestedName)) {
                    return new PlacementPreparation(town, false, null);
                }
            }
            TownCreationOutcome outcome = tryCreateTown(actor, data, requestedName);
            return outcome.town() == null
                    ? new PlacementPreparation(null, false, outcome.result())
                    : new PlacementPreparation(outcome.town(), true, null);
        }

        if (!availableTowns.isEmpty()) {
            return new PlacementPreparation(availableTowns.get(0), false, null);
        }

        TownCreationOutcome outcome = tryCreateTown(actor, data, nextAutoTownName(data, actor));
        return outcome.town() == null
                ? new PlacementPreparation(null, false, outcome.result())
                : new PlacementPreparation(outcome.town(), true, null);
    }

    private static List<TownRecord> getManageableTowns(ServerPlayer actor, NationSavedData data) {
        List<TownRecord> result = new ArrayList<>();
        if (actor == null || data == null) {
            return result;
        }
        for (TownRecord town : data.getTowns()) {
            if (canManageTown(actor, data, town)) {
                result.add(town);
            }
        }
        return result;
    }

    private static TownRecord relocatingTown(ServerPlayer actor, NationSavedData data, ItemStack stack) {
        if (actor == null || data == null || stack == null || !stack.hasTag()) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("RelocatingTownId")) {
            return null;
        }
        TownRecord town = data.getTown(tag.getString("RelocatingTownId"));
        if (town == null || town.hasCore()) {
            return null;
        }
        return canManageTown(actor, data, town) ? town : null;
    }

    private static String requestedTownName(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasCustomHoverName()) {
            return "";
        }
        return TownRecord.normalizeName(stack.getHoverName().getString());
    }

    private static String nextAutoTownName(NationSavedData data, ServerPlayer actor) {
        String playerName = actor == null || actor.getGameProfile() == null ? "Town" : actor.getGameProfile().getName();
        String base = TownRecord.normalizeName(playerName + " Town");
        if (base.length() > TownRecord.MAX_NAME_LENGTH) {
            base = TownRecord.normalizeName(base.substring(0, TownRecord.MAX_NAME_LENGTH));
        }
        if (!TownRecord.isValidName(base) || TownRecord.isReservedName(base)) {
            base = "Town";
        }
        if (data.findTownByName(base) == null) {
            return base;
        }
        for (int index = 2; index <= 999; index++) {
            String suffix = " " + index;
            int maxBaseLength = Math.max(1, TownRecord.MAX_NAME_LENGTH - suffix.length());
            String candidateBase = base.length() > maxBaseLength ? TownRecord.normalizeName(base.substring(0, maxBaseLength)) : base;
            String candidate = TownRecord.normalizeName(candidateBase + suffix);
            if (!TownRecord.isValidName(candidate) || TownRecord.isReservedName(candidate)) {
                continue;
            }
            if (data.findTownByName(candidate) == null) {
                return candidate;
            }
        }
        return "Town " + Math.abs((int) (System.currentTimeMillis() % 1000L));
    }

    private static void removeDependentNationCore(Level level, NationSavedData data, TownRecord town) {
        if (level == null || data == null || town == null || town.nationId().isBlank()) {
            return;
        }
        NationRecord nation = data.getNation(town.nationId());
        if (nation == null || !town.townId().equals(nation.capitalTownId()) || !nation.hasCore() || !town.hasCore()) {
            return;
        }
        if (!level.dimension().location().toString().equalsIgnoreCase(nation.coreDimension())) {
            return;
        }
        ChunkPos townChunk = new ChunkPos(BlockPos.of(town.corePos()));
        ChunkPos nationChunk = new ChunkPos(BlockPos.of(nation.corePos()));
        if (townChunk.x != nationChunk.x || townChunk.z != nationChunk.z) {
            return;
        }
        BlockPos nationCorePos = BlockPos.of(nation.corePos());
        if (level.hasChunkAt(nationCorePos) && level.getBlockState(nationCorePos).is(ModBlocks.NATION_CORE_BLOCK.get())) {
            level.removeBlock(nationCorePos, false);
            return;
        }
        NationClaimService.onCoreRemoved(level, nationCorePos);
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

    private static String formatCoreLocation(TownRecord town) {
        if (town == null || !town.hasCore()) {
            return "-";
        }
        BlockPos pos = BlockPos.of(town.corePos());
        return town.coreDimension() + " @ " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String resolvePlayerName(ServerPlayer actor, NationSavedData data, UUID playerUuid) {
        if (playerUuid == null) {
            return "-";
        }
        if (actor != null && actor.getServer() != null) {
            ServerPlayer online = actor.getServer().getPlayerList().getPlayer(playerUuid);
            if (online != null) {
                return online.getGameProfile().getName();
            }
        }
        NationMemberRecord member = data.getMember(playerUuid);
        if (member != null && !member.lastKnownName().isBlank()) {
            return member.lastKnownName();
        }
        return playerUuid.toString().toLowerCase(Locale.ROOT);
    }

    // ---- Town-Nation join workflow ----

    public static NationResult inviteTownToNation(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.invite.no_permission"));
        }
        if (!NationService.hasPermission(actor.level(), actor.getUUID(), NationPermission.INVITE_MEMBERS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.invite.no_permission"));
        }

        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }

        TownRecord town = data.findTownByName(TownRecord.normalizeName(rawTownName));
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", rawTownName));
        }
        if (town.hasNation()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.invite.already_in_nation", town.name()));
        }

        TownNationRequestRecord existing = data.getTownNationRequest(town.townId(), nation.nationId());
        if (existing != null && existing.isInvite()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.invite.already_sent", town.name()));
        }

        if (existing != null && existing.isApply()) {
            data.removeTownNationRequest(town.townId(), nation.nationId());
            bindTownToNation(data, town, nation.nationId());
            return NationResult.success(Component.translatable("command.sailboatmod.nation.town.join.success", town.name(), nation.name()));
        }

        data.putTownNationRequest(new TownNationRequestRecord(
                town.townId(), nation.nationId(), TownNationRequestRecord.DIRECTION_INVITE, actor.getUUID(), System.currentTimeMillis()
        ));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.invite.success", town.name()));
    }

    public static NationResult applyTownToNation(ServerPlayer actor, String rawNationName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);

        TownRecord town = findStandaloneTownForMayor(data, actor.getUUID());
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.invite.not_mayor"));
        }

        NationRecord nation = data.findNationByName(NationRecord.normalizeName(rawNationName));
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.join.not_found", rawNationName));
        }

        TownNationRequestRecord existing = data.getTownNationRequest(town.townId(), nation.nationId());
        if (existing != null && existing.isApply()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.apply.already_sent", town.name(), nation.name()));
        }

        if (existing != null && existing.isInvite()) {
            data.removeTownNationRequest(town.townId(), nation.nationId());
            bindTownToNation(data, town, nation.nationId());
            return NationResult.success(Component.translatable("command.sailboatmod.nation.town.join.success", town.name(), nation.name()));
        }

        data.putTownNationRequest(new TownNationRequestRecord(
                town.townId(), nation.nationId(), TownNationRequestRecord.DIRECTION_APPLY, actor.getUUID(), System.currentTimeMillis()
        ));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.apply.success", town.name(), nation.name()));
    }

    public static NationResult townJoinNation(ServerPlayer actor, String rawNationName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);

        TownRecord town = findStandaloneTownForMayor(data, actor.getUUID());
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.invite.not_mayor"));
        }

        NationRecord nation = data.findNationByName(NationRecord.normalizeName(rawNationName));
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.join.not_found", rawNationName));
        }

        TownNationRequestRecord request = data.getTownNationRequest(town.townId(), nation.nationId());
        if (request == null || !request.isInvite()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.join.not_invited", town.name(), nation.name()));
        }

        data.removeTownNationRequest(town.townId(), nation.nationId());
        bindTownToNation(data, town, nation.nationId());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.join.success", town.name(), nation.name()));
    }

    public static NationResult declineTownInvite(ServerPlayer actor, String rawNationName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);

        TownRecord town = findStandaloneTownForMayor(data, actor.getUUID());
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.invite.not_mayor"));
        }

        NationRecord nation = data.findNationByName(NationRecord.normalizeName(rawNationName));
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.join.not_found", rawNationName));
        }

        TownNationRequestRecord request = data.getTownNationRequest(town.townId(), nation.nationId());
        if (request == null || !request.isInvite()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.decline.missing", nation.name()));
        }

        data.removeTownNationRequest(town.townId(), nation.nationId());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.decline.success", nation.name()));
    }

    public static NationResult acceptTownApply(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.invite.no_permission"));
        }
        if (!NationService.hasPermission(actor.level(), actor.getUUID(), NationPermission.INVITE_MEMBERS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.invite.no_permission"));
        }

        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }

        TownRecord town = data.findTownByName(TownRecord.normalizeName(rawTownName));
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", rawTownName));
        }

        TownNationRequestRecord request = data.getTownNationRequest(town.townId(), nation.nationId());
        if (request == null || !request.isApply()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.apply.missing", town.name()));
        }

        data.removeTownNationRequest(town.townId(), nation.nationId());
        bindTownToNation(data, town, nation.nationId());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.apply.accept.success", town.name()));
    }

    public static NationResult rejectTownApply(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.invite.no_permission"));
        }
        if (!NationService.hasPermission(actor.level(), actor.getUUID(), NationPermission.INVITE_MEMBERS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.invite.no_permission"));
        }

        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }

        TownRecord town = data.findTownByName(TownRecord.normalizeName(rawTownName));
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", rawTownName));
        }

        TownNationRequestRecord request = data.getTownNationRequest(town.townId(), nation.nationId());
        if (request == null || !request.isApply()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.apply.missing", town.name()));
        }

        data.removeTownNationRequest(town.townId(), nation.nationId());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.apply.reject.success", town.name()));
    }

    public static List<Component> listTownRequests(ServerPlayer actor) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return List.of(Component.translatable("command.sailboatmod.nation.town.invite.no_permission"));
        }
        List<TownNationRequestRecord> requests = data.getTownNationRequestsForNation(actorMember.nationId());
        if (requests.isEmpty()) {
            return List.of(Component.translatable("command.sailboatmod.nation.town.apply.list.empty"));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.nation.town.apply.list.header"));
        for (TownNationRequestRecord request : requests) {
            TownRecord town = data.getTown(request.townId());
            String townName = town == null ? request.townId() : town.name();
            lines.add(Component.translatable("command.sailboatmod.nation.town.apply.list.entry", townName, request.direction()));
        }
        return lines;
    }

    public static NationResult kickTownFromNation(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.kick.no_permission"));
        }
        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (!actor.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.kick.no_permission"));
        }

        TownRecord town = data.findTownByName(TownRecord.normalizeName(rawTownName));
        if (town == null || !nation.nationId().equals(town.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.kick.not_found", rawTownName));
        }
        if (town.townId().equals(nation.capitalTownId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.kick.is_capital"));
        }

        String nationName = nation.name();
        unbindTownFromNation(data, town);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.kick.success", town.name()));
    }

    public static NationResult leaveTownFromNation(ServerPlayer actor) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);

        List<TownRecord> mayorTowns = data.getTownsForMayor(actor.getUUID());
        TownRecord town = null;
        for (TownRecord t : mayorTowns) {
            if (t.hasNation()) {
                town = t;
                break;
            }
        }
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.leave.no_nation"));
        }

        NationRecord nation = data.getNation(town.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (town.townId().equals(nation.capitalTownId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.leave.is_capital"));
        }

        String nationName = nation.name();
        unbindTownFromNation(data, town);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.leave.success", town.name(), nationName));
    }

    private static void bindTownToNation(NationSavedData data, TownRecord town, String nationId) {
        TownRecord updated = new TownRecord(
                town.townId(), nationId, town.name(), town.mayorUuid(), town.createdAt(),
                town.coreDimension(), town.corePos(), town.flagId(), town.cultureId()
        );
        data.putTown(updated);

        for (NationClaimRecord claim : data.getClaimsForTown(town.townId())) {
            data.putClaim(new NationClaimRecord(
                    claim.dimensionId(), claim.chunkX(), claim.chunkZ(),
                    nationId, claim.townId(),
                    claim.breakAccessLevel(), claim.placeAccessLevel(), claim.useAccessLevel(),
                    claim.containerAccessLevel(), claim.redstoneAccessLevel(),
                    claim.entityUseAccessLevel(), claim.entityDamageAccessLevel(),
                    claim.claimedAt()
            ));
        }

        NationRecord nation = data.getNation(nationId);
        if (nation != null && nation.capitalTownId().isBlank()) {
            data.putNation(new NationRecord(
                    nation.nationId(), nation.name(), nation.shortName(),
                    nation.primaryColorRgb(), nation.secondaryColorRgb(),
                    nation.leaderUuid(), nation.createdAt(),
                    updated.townId(),
                    nation.coreDimension(), nation.corePos(), nation.flagId()
            ));
        }

        data.clearTownNationRequestsForTown(town.townId());
    }

    private static void unbindTownFromNation(NationSavedData data, TownRecord town) {
        TownRecord updated = new TownRecord(
                town.townId(), "", town.name(), town.mayorUuid(), town.createdAt(),
                town.coreDimension(), town.corePos(), town.flagId(), town.cultureId()
        );
        data.putTown(updated);

        for (NationClaimRecord claim : data.getClaimsForTown(town.townId())) {
            data.putClaim(new NationClaimRecord(
                    claim.dimensionId(), claim.chunkX(), claim.chunkZ(),
                    "", claim.townId(),
                    claim.breakAccessLevel(), claim.placeAccessLevel(), claim.useAccessLevel(),
                    claim.containerAccessLevel(), claim.redstoneAccessLevel(),
                    claim.entityUseAccessLevel(), claim.entityDamageAccessLevel(),
                    claim.claimedAt()
            ));
        }
    }

    private static TownRecord findStandaloneTownForMayor(NationSavedData data, UUID mayorUuid) {
        if (data == null || mayorUuid == null) {
            return null;
        }
        for (TownRecord town : data.getTownsForMayor(mayorUuid)) {
            if (!town.hasNation()) {
                return town;
            }
        }
        return null;
    }

    private record TownCreationOutcome(TownRecord town, NationResult result) {
    }

    private record PlacementPreparation(TownRecord town, boolean createdNewTown, NationResult failure) {
    }

    public static NationResult createTownAt(ServerPlayer actor, String rawName, BlockPos pos) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownCreationOutcome outcome = tryCreateTown(actor, data, rawName);
        if (outcome.town() == null) return outcome.result();
        TownRecord town = outcome.town();
        if (town == null) return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.core.no_town"));
        placeCoreAt(data, actor.level(), town, pos);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.create.success", town.name()));
    }

    public static TownRecord getTownForMember(NationSavedData data, NationMemberRecord member) {
        if (data == null || member == null) return null;
        for (TownRecord town : data.getTownsForNation(member.nationId())) {
            if (member.playerUuid().equals(town.mayorUuid())) return town;
        }
        List<TownRecord> towns = data.getTownsForNation(member.nationId());
        return towns.isEmpty() ? null : towns.get(0);
    }

    public static void placeCoreAt(NationSavedData data, Level level, TownRecord town, BlockPos pos) {
        if (data == null || level == null || town == null || pos == null) return;
        TownRecord updated = new TownRecord(
                town.townId(), town.nationId(), town.name(), town.mayorUuid(),
                town.createdAt(), level.dimension().location().toString(), pos.asLong(), town.flagId(), town.cultureId());
        data.putTown(updated);
        ChunkPos coreChunk = new ChunkPos(pos);
        if (data.getClaim(level, coreChunk) == null) {
            data.putClaim(new NationClaimRecord(
                    level.dimension().location().toString(), coreChunk.x, coreChunk.z,
                    town.nationId(), town.townId(),
                    NationClaimAccessLevel.MEMBER.id(), NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(), NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(), NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(), System.currentTimeMillis()));
        }
    }

    private static void removePreviousTownCoreBlock(ServerPlayer actor, String dimensionId, long corePos) {
        if (actor == null || actor.getServer() == null || dimensionId == null || dimensionId.isBlank() || corePos == TownRecord.noCorePos()) {
            return;
        }
        ServerLevel coreLevel = resolveDimension(actor.getServer(), dimensionId);
        if (coreLevel == null) {
            return;
        }
        BlockPos oldPos = BlockPos.of(corePos);
        if (!coreLevel.hasChunkAt(oldPos)) {
            return;
        }
        if (coreLevel.getBlockState(oldPos).is(ModBlocks.TOWN_CORE_BLOCK.get())) {
            coreLevel.removeBlock(oldPos, false);
        }
    }

    private static void refundRelocationCore(ServerPlayer actor, net.minecraft.world.level.block.Block block) {
        if (actor == null || actor.getAbilities().instabuild || block == null) {
            return;
        }
        actor.getInventory().placeItemBackInInventory(new ItemStack(block));
        actor.getInventory().setChanged();
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

    private TownService() {
    }
}
