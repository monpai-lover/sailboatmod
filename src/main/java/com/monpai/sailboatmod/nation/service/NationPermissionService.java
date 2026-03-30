package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimAccessLevel;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyStatus;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.UUID;

public final class NationPermissionService {
    public static AccessResult evaluateBreak(Level level, UUID playerUuid, BlockPos pos) {
        return evaluate(level, playerUuid, pos, LandAction.BREAK);
    }

    public static AccessResult evaluatePlace(Level level, UUID playerUuid, BlockPos pos) {
        return evaluate(level, playerUuid, pos, LandAction.PLACE);
    }

    public static AccessResult evaluateUse(Level level, UUID playerUuid, BlockPos pos) {
        return evaluate(level, playerUuid, pos, LandAction.USE);
    }

    public static AccessResult evaluateContainer(Level level, UUID playerUuid, BlockPos pos) {
        return evaluate(level, playerUuid, pos, LandAction.CONTAINER);
    }

    public static AccessResult evaluateRedstone(Level level, UUID playerUuid, BlockPos pos) {
        return evaluate(level, playerUuid, pos, LandAction.REDSTONE);
    }

    public static AccessResult evaluateEntityUse(Level level, UUID playerUuid, BlockPos pos) {
        return evaluate(level, playerUuid, pos, LandAction.ENTITY_USE);
    }

    public static AccessResult evaluateEntityDamage(Level level, UUID playerUuid, BlockPos pos) {
        return evaluate(level, playerUuid, pos, LandAction.ENTITY_DAMAGE);
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
        NationRecord nation = data.getNation(member.nationId());
        return nation != null
                && NationClaimService.isNationCore(level, pos, nation)
                && NationService.hasPermission(level, playerUuid, NationPermission.PLACE_CORE);
    }

    public static NationClaimRecord getClaim(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        return NationSavedData.get(level).getClaim(level, new ChunkPos(pos));
    }

    public static NationRecord getNationAt(Level level, BlockPos pos) {
        NationClaimRecord claim = getClaim(level, pos);
        return claim == null ? null : NationSavedData.get(level).getNation(claim.nationId());
    }

    public static boolean isClaimed(Level level, BlockPos pos) {
        return getClaim(level, pos) != null;
    }

    public static ChunkRole resolvePlayerRole(Level level, UUID playerUuid, BlockPos pos) {
        return evaluate(level, playerUuid, pos, LandAction.USE).role();
    }

    public static NationDiplomacyStatus resolveDiplomacy(Level level, UUID playerUuid, BlockPos pos) {
        NationClaimRecord claim = getClaim(level, pos);
        if (claim == null) {
            return NationDiplomacyStatus.NEUTRAL;
        }
        NationMemberRecord member = level == null || playerUuid == null ? null : NationSavedData.get(level).getMember(playerUuid);
        return resolveDiplomacy(level, member == null ? "" : member.nationId(), claim.nationId());
    }

    public static NationDiplomacyStatus resolveDiplomacy(Level level, String nationId, String otherNationId) {
        if (level == null) {
            return NationDiplomacyStatus.NEUTRAL;
        }
        return resolveDiplomacy(NationSavedData.get(level), nationId, otherNationId);
    }

    private static AccessResult evaluate(Level level, UUID playerUuid, BlockPos pos, LandAction action) {
        if (level == null || pos == null) {
            return AccessResult.unclaimed();
        }

        NationSavedData data = NationSavedData.get(level);
        NationClaimRecord claim = data.getClaim(level, new ChunkPos(pos));
        if (claim == null) {
            return AccessResult.unclaimed();
        }

        NationRecord ownerNation = data.getNation(claim.nationId());
        NationMemberRecord actorMember = playerUuid == null ? null : data.getMember(playerUuid);
        NationOfficeRecord actorOffice = actorMember == null ? null : data.getOffice(actorMember.nationId(), actorMember.officeId());
        ChunkRole role = resolveRole(data, playerUuid, actorMember, actorOffice, claim);
        NationDiplomacyStatus diplomacy = resolveDiplomacy(data, actorMember == null ? "" : actorMember.nationId(), claim.nationId());
        NationClaimAccessLevel requiredLevel = requiredLevel(claim, action);

        boolean allowed = switch (role) {
            case UNCLAIMED, LEADER -> true;
            case OFFICER, MEMBER -> requiredLevel != null && actorOffice != null && actorOffice.priority() <= requiredLevel.maxPriority();
            case ALLY -> requiredLevel != null && requiredLevel.maxPriority() >= NationClaimAccessLevel.ALLY.maxPriority();
            case TRADE -> requiredLevel != null && requiredLevel.maxPriority() >= NationClaimAccessLevel.NEUTRAL.maxPriority();
            case NEUTRAL -> requiredLevel != null && requiredLevel.maxPriority() >= NationClaimAccessLevel.NEUTRAL.maxPriority();
            case ENEMY -> false;
            case OUTSIDER -> requiredLevel != null && requiredLevel.maxPriority() >= NationClaimAccessLevel.ANYONE.maxPriority();
        };

        return new AccessResult(allowed, claim, ownerNation, actorMember, actorOffice, role, diplomacy, requiredLevel, action);
    }

    private static ChunkRole resolveRole(NationSavedData data, UUID playerUuid, NationMemberRecord actorMember, NationOfficeRecord actorOffice, NationClaimRecord claim) {
        if (claim == null) {
            return ChunkRole.UNCLAIMED;
        }
        if (!claim.townId().isBlank() && playerUuid != null) {
            TownRecord town = data.getTown(claim.townId());
            if (town != null && playerUuid.equals(town.mayorUuid())) {
                return ChunkRole.LEADER;
            }
        }
        if (actorMember == null || actorMember.nationId().isBlank()) {
            return ChunkRole.OUTSIDER;
        }
        if (claim.nationId().equals(actorMember.nationId())) {
            int priority = actorOffice == null ? Integer.MAX_VALUE : actorOffice.priority();
            if (priority <= NationClaimAccessLevel.LEADER.maxPriority()) {
                return ChunkRole.LEADER;
            }
            if (priority <= NationClaimAccessLevel.OFFICER.maxPriority()) {
                return ChunkRole.OFFICER;
            }
            return ChunkRole.MEMBER;
        }
        return switch (resolveDiplomacy(data, actorMember.nationId(), claim.nationId())) {
            case ALLIED -> ChunkRole.ALLY;
            case TRADE -> ChunkRole.TRADE;
            case ENEMY -> ChunkRole.ENEMY;
            case NEUTRAL -> ChunkRole.NEUTRAL;
        };
    }

    private static NationClaimAccessLevel requiredLevel(NationClaimRecord claim, LandAction action) {
        if (claim == null || action == null) {
            return null;
        }
        return switch (action) {
            case BREAK -> NationClaimAccessLevel.fromId(claim.breakAccessLevel());
            case PLACE -> NationClaimAccessLevel.fromId(claim.placeAccessLevel());
            case USE -> NationClaimAccessLevel.fromId(claim.useAccessLevel());
            case CONTAINER -> NationClaimAccessLevel.fromId(claim.containerAccessLevel());
            case REDSTONE -> NationClaimAccessLevel.fromId(claim.redstoneAccessLevel());
            case ENTITY_USE -> NationClaimAccessLevel.fromId(claim.entityUseAccessLevel());
            case ENTITY_DAMAGE -> NationClaimAccessLevel.fromId(claim.entityDamageAccessLevel());
        };
    }

    private static NationDiplomacyStatus resolveDiplomacy(NationSavedData data, String nationId, String otherNationId) {
        if (data == null || nationId == null || nationId.isBlank() || otherNationId == null || otherNationId.isBlank()) {
            return NationDiplomacyStatus.NEUTRAL;
        }
        if (nationId.equalsIgnoreCase(otherNationId)) {
            return NationDiplomacyStatus.ALLIED;
        }
        NationDiplomacyRecord record = data.getDiplomacy(nationId, otherNationId);
        return record == null ? NationDiplomacyStatus.NEUTRAL : NationDiplomacyStatus.fromId(record.statusId());
    }

    public enum LandAction {
        BREAK,
        PLACE,
        USE,
        CONTAINER,
        REDSTONE,
        ENTITY_USE,
        ENTITY_DAMAGE
    }

    public enum ChunkRole {
        UNCLAIMED,
        LEADER,
        OFFICER,
        MEMBER,
        ALLY,
        TRADE,
        NEUTRAL,
        ENEMY,
        OUTSIDER
    }

    public record AccessResult(
            boolean allowed,
            NationClaimRecord claim,
            NationRecord ownerNation,
            NationMemberRecord actorMember,
            NationOfficeRecord actorOffice,
            ChunkRole role,
            NationDiplomacyStatus diplomacy,
            NationClaimAccessLevel requiredLevel,
            LandAction action
    ) {
        private static AccessResult unclaimed() {
            return new AccessResult(
                    true,
                    null,
                    null,
                    null,
                    null,
                    ChunkRole.UNCLAIMED,
                    NationDiplomacyStatus.NEUTRAL,
                    null,
                    LandAction.USE
            );
        }
    }

    private NationPermissionService() {
    }
}
