package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.economy.VaultEconomyBridge;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimAccessLevel;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TownClaimService {
    private static final int SPAWN_PROTECTED_RADIUS_CHUNKS = 4;
    private static final int CLAIM_BATCH_WIDTH = 1;
    private static final int CLAIM_BATCH_HEIGHT = 1;
    private static final Set<String> BLOCKED_CLAIM_DIMENSIONS = Set.of(
            Level.NETHER.location().toString(),
            Level.END.location().toString()
    );

    public static int batchClaimCost() {
        return NationClaimService.claimCost() * CLAIM_BATCH_WIDTH * CLAIM_BATCH_HEIGHT;
    }

    public static NationResult claimChunk(ServerPlayer player, String townId, ChunkPos chunkPos) {
        NationSavedData data = NationSavedData.get(player.level());
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", townId));
        }
        if (!TownService.canManageTown(player, data, town)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.no_permission"));
        }
        if (!town.hasCore()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.claim.need_core", town.name()));
        }

        List<ChunkPos> claimArea = getClaimArea(chunkPos);
        boolean anyOwnedByTown = false;
        for (ChunkPos targetChunk : claimArea) {
            Component restrictionMessage = getClaimRestrictionMessage(player.level(), targetChunk, false);
            if (restrictionMessage != null) {
                return NationResult.failure(restrictionMessage);
            }
            NationClaimRecord existing = data.getClaim(player.level(), targetChunk);
            if (existing == null) {
                continue;
            }
            if (TownService.isClaimManagedByTown(data, town, existing)) {
                anyOwnedByTown = true;
                continue;
            }
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.occupied"));
        }
        if (anyOwnedByTown) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.claim.already_owned"));
        }
        if (!isClaimAreaAdjacentToTown(data, player.level(), claimArea, town)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.not_adjacent"));
        }

        int claimCost = batchClaimCost();
        if (claimCost > 0) {
            if (!chargePlayer(player, claimCost)) {
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.claim.not_enough_money", claimCost));
            }
        }

        long claimedAt = System.currentTimeMillis();
        for (ChunkPos targetChunk : claimArea) {
            data.putClaim(new NationClaimRecord(
                    player.level().dimension().location().toString(),
                    targetChunk.x,
                    targetChunk.z,
                    town.nationId(),
                    town.townId(),
                    NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(),
                    NationClaimAccessLevel.MEMBER.id(),
                    claimedAt
            ));
        }
        return NationResult.success(claimCost > 0
                ? Component.translatable("command.sailboatmod.nation.town.claim.success_paid", chunkPos.x, chunkPos.z, claimCost)
                : Component.translatable("command.sailboatmod.nation.town.claim.success", chunkPos.x, chunkPos.z));
    }

    public static NationResult unclaimChunk(ServerPlayer player, String townId, ChunkPos chunkPos) {
        NationSavedData data = NationSavedData.get(player.level());
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", townId));
        }
        if (!TownService.canManageTown(player, data, town)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.no_permission"));
        }

        List<ChunkPos> claimArea = getClaimArea(chunkPos);
        for (ChunkPos targetChunk : claimArea) {
            NationClaimRecord existing = data.getClaim(player.level(), targetChunk);
            if (!TownService.isClaimManagedByTown(data, town, existing)) {
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.claim.not_owned"));
            }
            if (isTownCoreChunk(player.level(), targetChunk, town)) {
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.claim.core_chunk"));
            }
        }

        for (ChunkPos targetChunk : claimArea) {
            data.removeClaim(player.level().dimension().location().toString(), targetChunk.x, targetChunk.z);
        }
        return NationResult.success(Component.translatable("command.sailboatmod.nation.town.unclaim.success", chunkPos.x, chunkPos.z));
    }

    public static NationResult setChunkPermission(ServerPlayer player, String townId, ChunkPos chunkPos, String actionId, String levelId) {
        NationSavedData data = NationSavedData.get(player.level());
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", townId));
        }
        if (!TownService.canManageTown(player, data, town)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.no_permission"));
        }

        NationClaimAccessLevel accessLevel = NationClaimAccessLevel.fromId(levelId);
        if (accessLevel == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.claimperm.level_invalid", levelId));
        }

        String action = actionId == null ? "" : actionId.trim().toLowerCase(Locale.ROOT);
        List<ChunkPos> claimArea = getClaimArea(chunkPos);
        List<NationClaimRecord> ownedClaims = new ArrayList<>();
        for (ChunkPos targetChunk : claimArea) {
            NationClaimRecord claim = data.getClaim(player.level(), targetChunk);
            if (!TownService.isClaimManagedByTown(data, town, claim)) {
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.claim.not_owned"));
            }
            ownedClaims.add(claim);
        }

        for (NationClaimRecord claim : ownedClaims) {
            NationClaimRecord updated = claim.withPermission(action, accessLevel);
            if (updated == claim) {
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.claimperm.action_invalid", actionId));
            }
            data.putClaim(updated);
        }
        return NationResult.success(Component.translatable(
                "command.sailboatmod.nation.claimperm.success",
                Component.translatable("command.sailboatmod.nation.claimperm.action." + action),
                Component.translatable("command.sailboatmod.nation.claimperm.level." + accessLevel.id())
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

    private static boolean isClaimAreaAdjacentToTown(NationSavedData data, Level level, List<ChunkPos> targets, TownRecord town) {
        List<NationClaimRecord> townClaims = TownService.getManagedClaims(data, town);
        if (townClaims.isEmpty()) {
            for (ChunkPos target : targets) {
                if (isTownCoreChunk(level, target, town)) {
                    return true;
                }
            }
            return false;
        }
        for (NationClaimRecord claim : townClaims) {
            if (!level.dimension().location().toString().equalsIgnoreCase(claim.dimensionId())) {
                continue;
            }
            for (ChunkPos target : targets) {
                int dx = Math.abs(claim.chunkX() - target.x);
                int dz = Math.abs(claim.chunkZ() - target.z);
                if (dx + dz == 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<ChunkPos> getClaimArea(ChunkPos anchor) {
        int baseX = Math.floorDiv(anchor.x, CLAIM_BATCH_WIDTH) * CLAIM_BATCH_WIDTH;
        int baseZ = Math.floorDiv(anchor.z, CLAIM_BATCH_HEIGHT) * CLAIM_BATCH_HEIGHT;
        List<ChunkPos> result = new ArrayList<>(CLAIM_BATCH_WIDTH * CLAIM_BATCH_HEIGHT);
        for (int dz = 0; dz < CLAIM_BATCH_HEIGHT; dz++) {
            for (int dx = 0; dx < CLAIM_BATCH_WIDTH; dx++) {
                result.add(new ChunkPos(baseX + dx, baseZ + dz));
            }
        }
        return result;
    }

    private static boolean isTownCoreChunk(Level level, ChunkPos chunkPos, TownRecord town) {
        if (level == null || chunkPos == null || town == null || !town.hasCore()) {
            return false;
        }
        if (!level.dimension().location().toString().equalsIgnoreCase(town.coreDimension())) {
            return false;
        }
        ChunkPos coreChunk = new ChunkPos(BlockPos.of(town.corePos()));
        return coreChunk.x == chunkPos.x && coreChunk.z == chunkPos.z;
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

    private static boolean chargePlayer(ServerPlayer player, int amount) {
        if (player == null || amount <= 0 || player.getAbilities().instabuild) {
            return true;
        }
        Boolean vaultResult = VaultEconomyBridge.tryWithdraw(player, amount);
        if (vaultResult != null) {
            return vaultResult;
        }
        Inventory inventory = player.getInventory();
        int remaining = amount;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.is(Items.EMERALD)) {
                continue;
            }
            remaining -= stack.getCount();
            if (remaining <= 0) {
                break;
            }
        }
        if (remaining > 0) {
            return false;
        }
        remaining = amount;
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.is(Items.EMERALD)) {
                continue;
            }
            int consume = Math.min(stack.getCount(), remaining);
            stack.shrink(consume);
            remaining -= consume;
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
        return true;
    }

    private TownClaimService() {
    }
}