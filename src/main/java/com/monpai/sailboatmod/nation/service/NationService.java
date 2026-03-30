package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationFlagRecord;
import com.monpai.sailboatmod.nation.model.NationInviteRecord;
import com.monpai.sailboatmod.nation.model.NationJoinRequestRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeIds;
import com.monpai.sailboatmod.nation.model.NationOfficeRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.NationWarRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class NationService {
    private static final int MIN_OFFICE_ID_LENGTH = 2;
    private static final int MAX_OFFICE_ID_LENGTH = 24;
    private static final int MAX_OFFICE_NAME_LENGTH = 24;
    private static final int DEFAULT_PRIMARY_COLOR = 0x3A6EA5;
    private static final int DEFAULT_SECONDARY_COLOR = 0xF2C14E;

    public static NationResult createNation(ServerPlayer player, String rawName) {
        NationSavedData data = NationSavedData.get(player.level());
        updateKnownPlayer(player);

        if (data.getMember(player.getUUID()) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.already_member"));
        }

        String name = NationRecord.normalizeName(rawName);
        if (!NationRecord.isValidName(name)) {
            return NationResult.failure(Component.translatable(
                    "command.sailboatmod.nation.name_invalid",
                    NationRecord.MIN_NAME_LENGTH,
                    NationRecord.MAX_NAME_LENGTH
            ));
        }
        if (NationRecord.isReservedName(name)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.name_reserved", name));
        }
        if (data.findNationByName(name) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.name_taken", name));
        }

        String nationId = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        TownRecord capitalTown = TownService.bindStandaloneTownToNation(data, player.getUUID(), nationId);
        if (capitalTown == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.create.need_town"));
        }

        NationRecord nation = new NationRecord(
                nationId,
                name,
                NationRecord.buildShortName(name),
                DEFAULT_PRIMARY_COLOR,
                DEFAULT_SECONDARY_COLOR,
                player.getUUID(),
                now,
                capitalTown.townId(),
                "",
                NationRecord.noCorePos(),
                ""
        );
        data.putNation(nation);
        for (NationOfficeRecord office : defaultOffices()) {
            data.putOffice(nationId, office);
        }
        data.putMember(new NationMemberRecord(
                player.getUUID(),
                player.getGameProfile().getName(),
                nationId,
                NationOfficeIds.LEADER,
                now
        ));
        data.clearInvitesForPlayer(player.getUUID());
        data.clearJoinRequestsForPlayer(player.getUUID());
        refreshPlayerNames(player);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.create.success", nation.name()));
    }

    public static NationResult renameNation(ServerPlayer actor, String rawName) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!hasPermission(data, actorMember, NationPermission.MANAGE_INFO)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.rename.no_permission"));
        }

        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }

        String name = NationRecord.normalizeName(rawName);
        if (!NationRecord.isValidName(name)) {
            return NationResult.failure(Component.translatable(
                    "command.sailboatmod.nation.name_invalid",
                    NationRecord.MIN_NAME_LENGTH,
                    NationRecord.MAX_NAME_LENGTH
            ));
        }
        if (NationRecord.isReservedName(name)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.name_reserved", name));
        }
        NationRecord existing = data.findNationByName(name);
        if (existing != null && !existing.nationId().equals(nation.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.name_taken", name));
        }
        if (name.equals(nation.name())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.rename.unchanged"));
        }

        data.putNation(new NationRecord(
                nation.nationId(),
                name,
                NationRecord.buildShortName(name),
                nation.primaryColorRgb(),
                nation.secondaryColorRgb(),
                nation.leaderUuid(),
                nation.createdAt(),
                nation.capitalTownId(),
                nation.coreDimension(),
                nation.corePos(),
                nation.flagId()
        ));
        NationFlagBlockTracker.refreshNationFlags(actor.getServer(), nation.nationId());
        TownFlagBlockTracker.refreshNationFlags(actor.getServer(), nation.nationId());
        refreshNationPlayerNames(actor.getServer(), nation.nationId());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.rename.success", name));
    }

    public static NationResult setShortName(ServerPlayer actor, String rawShortName) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!hasPermission(data, actorMember, NationPermission.MANAGE_INFO)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.short_name.no_permission"));
        }

        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }

        String shortName = NationRecord.normalizeShortName(rawShortName);
        if (!NationRecord.isValidShortName(shortName)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.short_name.invalid", NationRecord.MAX_SHORT_NAME_LENGTH));
        }
        if (shortName.equals(nation.shortName())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.short_name.unchanged"));
        }

        data.putNation(new NationRecord(
                nation.nationId(),
                nation.name(),
                shortName,
                nation.primaryColorRgb(),
                nation.secondaryColorRgb(),
                nation.leaderUuid(),
                nation.createdAt(),
                nation.capitalTownId(),
                nation.coreDimension(),
                nation.corePos(),
                nation.flagId()
        ));
        NationFlagBlockTracker.refreshNationFlags(actor.getServer(), nation.nationId());
        TownFlagBlockTracker.refreshNationFlags(actor.getServer(), nation.nationId());
        refreshNationPlayerNames(actor.getServer(), nation.nationId());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.short_name.success", shortName));
    }
    public static NationResult disbandNation(ServerPlayer actor) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.leave.not_in_nation"));
        }
        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (!actor.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.disband.no_permission"));
        }
        if (!(actor.level() instanceof ServerLevel serverLevel)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }

        disbandNation(serverLevel, data, nation);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.disband.success", nation.name()));
    }

    public static NationResult invitePlayer(ServerPlayer actor, ServerPlayer target) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);
        updateKnownPlayer(target);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!hasPermission(data, actorMember, NationPermission.INVITE_MEMBERS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_permission"));
        }
        if (data.getMember(target.getUUID()) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.target_has_nation", target.getGameProfile().getName()));
        }

        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (data.getInvite(nation.nationId(), target.getUUID()) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.already_sent", target.getGameProfile().getName()));
        }
        if (data.getJoinRequest(nation.nationId(), target.getUUID()) != null) {
            data.removeJoinRequest(nation.nationId(), target.getUUID());
        }

        data.putInvite(new NationInviteRecord(nation.nationId(), target.getUUID(), actor.getUUID(), System.currentTimeMillis()));
        return NationResult.success(Component.translatable(
                "command.sailboatmod.nation.invite.success",
                target.getGameProfile().getName(),
                nation.name()
        ));
    }

    public static NationResult declineInvite(ServerPlayer player, String rawNationName) {
        NationSavedData data = NationSavedData.get(player.level());
        updateKnownPlayer(player);

        NationRecord nation = data.findNationByName(NationRecord.normalizeName(rawNationName));
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.join.not_found", rawNationName));
        }
        NationInviteRecord invite = data.getInvite(nation.nationId(), player.getUUID());
        if (invite == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.decline.missing", nation.name()));
        }
        data.removeInvite(nation.nationId(), player.getUUID());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.invite.decline.success", nation.name()));
    }

    public static NationResult applyToNation(ServerPlayer player, String rawNationName) {
        NationSavedData data = NationSavedData.get(player.level());
        updateKnownPlayer(player);

        if (data.getMember(player.getUUID()) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.already_member"));
        }

        NationRecord nation = data.findNationByName(NationRecord.normalizeName(rawNationName));
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.join.not_found", rawNationName));
        }
        if (data.getInvite(nation.nationId(), player.getUUID()) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.apply.already_invited", nation.name()));
        }
        if (data.getJoinRequest(nation.nationId(), player.getUUID()) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.apply.already_sent", nation.name()));
        }

        data.putJoinRequest(new NationJoinRequestRecord(nation.nationId(), player.getUUID(), System.currentTimeMillis()));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.apply.success", nation.name()));
    }

    public static NationResult acceptJoinRequest(ServerPlayer actor, ServerPlayer applicant) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);
        updateKnownPlayer(applicant);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!hasPermission(data, actorMember, NationPermission.INVITE_MEMBERS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_permission"));
        }
        if (data.getMember(applicant.getUUID()) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.target_has_nation", applicant.getGameProfile().getName()));
        }

        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        NationJoinRequestRecord request = data.getJoinRequest(nation.nationId(), applicant.getUUID());
        if (request == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.apply.missing", applicant.getGameProfile().getName()));
        }

        data.putMember(new NationMemberRecord(
                applicant.getUUID(),
                applicant.getGameProfile().getName(),
                nation.nationId(),
                NationOfficeIds.MEMBER,
                System.currentTimeMillis()
        ));
        data.removeJoinRequest(nation.nationId(), applicant.getUUID());
        data.clearInvitesForPlayer(applicant.getUUID());
        refreshPlayerNames(applicant);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.apply.accept.success", applicant.getGameProfile().getName()));
    }

    public static NationResult rejectJoinRequest(ServerPlayer actor, ServerPlayer applicant) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);
        updateKnownPlayer(applicant);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!hasPermission(data, actorMember, NationPermission.INVITE_MEMBERS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_permission"));
        }

        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        NationJoinRequestRecord request = data.getJoinRequest(nation.nationId(), applicant.getUUID());
        if (request == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.apply.missing", applicant.getGameProfile().getName()));
        }

        data.removeJoinRequest(nation.nationId(), applicant.getUUID());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.apply.reject.success", applicant.getGameProfile().getName()));
    }

    public static List<Component> describeJoinRequests(ServerPlayer actor) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return List.of(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        List<NationJoinRequestRecord> requests = data.getJoinRequestsForNation(actorMember.nationId());
        if (requests.isEmpty()) {
            return List.of(Component.translatable("command.sailboatmod.nation.apply.list.empty"));
        }

        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.nation.apply.list.header"));
        for (NationJoinRequestRecord request : requests) {
            lines.add(Component.translatable("command.sailboatmod.nation.apply.list.entry", playerName(data, actor.level(), request.applicantUuid())));
        }
        return lines;
    }

    public static NationResult joinNation(ServerPlayer player, String rawNationName) {
        NationSavedData data = NationSavedData.get(player.level());
        updateKnownPlayer(player);

        if (data.getMember(player.getUUID()) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.already_member"));
        }

        String nationName = NationRecord.normalizeName(rawNationName);
        NationRecord nation = data.findNationByName(nationName);
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.join.not_found", nationName));
        }

        NationInviteRecord invite = data.getInvite(nation.nationId(), player.getUUID());
        if (invite == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.join.not_invited", nation.name()));
        }

        data.putMember(new NationMemberRecord(
                player.getUUID(),
                player.getGameProfile().getName(),
                nation.nationId(),
                NationOfficeIds.MEMBER,
                System.currentTimeMillis()
        ));
        data.removeInvite(nation.nationId(), player.getUUID());
        data.clearJoinRequestsForPlayer(player.getUUID());
        refreshPlayerNames(player);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.join.success", nation.name()));
    }

    public static NationResult leaveNation(ServerPlayer player) {
        NationSavedData data = NationSavedData.get(player.level());
        updateKnownPlayer(player);

        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.leave.not_in_nation"));
        }

        NationRecord nation = data.getNation(member.nationId());
        if (nation == null) {
            data.removeMember(player.getUUID());
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }

        boolean leaderLeaving = player.getUUID().equals(nation.leaderUuid());
        List<NationMemberRecord> members = data.getMembersForNation(nation.nationId());
        if (leaderLeaving && members.size() > 1) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.leave.transfer_first"));
        }

        if (leaderLeaving) {
            if (player.level() instanceof ServerLevel serverLevel) {
                disbandNation(serverLevel, data, nation);
            }
            refreshPlayerNames(player);
            return NationResult.success(Component.translatable("command.sailboatmod.nation.leave.disbanded", nation.name()));
        }

        data.removeMember(player.getUUID());
        refreshPlayerNames(player);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.leave.success", nation.name()));
    }

    public static NationResult kickMember(ServerPlayer actor, ServerPlayer target) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);
        updateKnownPlayer(target);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!hasPermission(data, actorMember, NationPermission.KICK_MEMBERS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.kick.no_permission"));
        }
        if (actor.getUUID().equals(target.getUUID())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.kick.self"));
        }

        NationMemberRecord targetMember = data.getMember(target.getUUID());
        if (targetMember == null || !targetMember.nationId().equals(actorMember.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.kick.not_same_nation", target.getGameProfile().getName()));
        }

        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (target.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.kick.leader"));
        }

        data.removeMember(target.getUUID());
        refreshPlayerNames(target);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.kick.success", target.getGameProfile().getName()));
    }

    public static NationResult kickMemberByUuid(ServerPlayer actor, UUID targetUuid) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!hasPermission(data, actorMember, NationPermission.KICK_MEMBERS)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.kick.no_permission"));
        }
        if (actor.getUUID().equals(targetUuid)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.kick.self"));
        }

        NationMemberRecord targetMember = data.getMember(targetUuid);
        if (targetMember == null || !targetMember.nationId().equals(actorMember.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.kick.not_same_nation", targetMember == null ? targetUuid.toString() : targetMember.lastKnownName()));
        }

        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (targetUuid.equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.kick.leader"));
        }

        String targetName = targetMember.lastKnownName();
        data.removeMember(targetUuid);
        ServerPlayer onlineTarget = actor.server.getPlayerList().getPlayer(targetUuid);
        if (onlineTarget != null) {
            refreshPlayerNames(onlineTarget);
        }
        return NationResult.success(Component.translatable("command.sailboatmod.nation.kick.success", targetName));
    }

    public static NationResult promoteMember(ServerPlayer actor, ServerPlayer target) {
        updateKnownPlayer(target);
        return changeOffice(actor, target.getUUID(), NationOfficeIds.OFFICER, "command.sailboatmod.nation.promote");
    }

    public static NationResult demoteMember(ServerPlayer actor, ServerPlayer target) {
        updateKnownPlayer(target);
        return changeOffice(actor, target.getUUID(), NationOfficeIds.MEMBER, "command.sailboatmod.nation.demote");
    }

    public static NationResult assignOfficer(ServerPlayer actor, UUID targetUuid) {
        return changeOffice(actor, targetUuid, NationOfficeIds.OFFICER, "command.sailboatmod.nation.promote");
    }

    public static NationResult removeOfficer(ServerPlayer actor, UUID targetUuid) {
        return changeOffice(actor, targetUuid, NationOfficeIds.MEMBER, "command.sailboatmod.nation.demote");
    }

    public static NationResult assignOffice(ServerPlayer actor, ServerPlayer target, String rawOfficeId) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);
        updateKnownPlayer(target);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (!actor.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.assign.no_permission"));
        }

        NationMemberRecord targetMember = data.getMember(target.getUUID());
        if (targetMember == null || !nation.nationId().equals(targetMember.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.assign.not_same_nation", target.getGameProfile().getName()));
        }
        if (target.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.assign.leader"));
        }

        String officeId = normalizeOfficeId(rawOfficeId);
        NationOfficeRecord office = data.getOffice(nation.nationId(), officeId);
        if (office == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.missing", rawOfficeId));
        }
        if (office.officeId().equals(targetMember.officeId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.assign.already", target.getGameProfile().getName(), office.name()));
        }

        data.putMember(new NationMemberRecord(
                targetMember.playerUuid(),
                target.getGameProfile().getName(),
                targetMember.nationId(),
                office.officeId(),
                targetMember.joinedAt()
        ));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.office.assign.success", target.getGameProfile().getName(), office.name()));
    }

    public static NationResult createOffice(ServerPlayer actor, String rawOfficeId, String rawName) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (!actor.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.manage.no_permission"));
        }

        String officeId = normalizeOfficeId(rawOfficeId);
        if (!isValidOfficeId(officeId)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.id.invalid", MIN_OFFICE_ID_LENGTH, MAX_OFFICE_ID_LENGTH));
        }
        if (data.getOffice(nation.nationId(), officeId) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.id.taken", officeId));
        }
        String officeName = normalizeOfficeName(rawName);
        if (!isValidOfficeName(officeName)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.name.invalid", MAX_OFFICE_NAME_LENGTH));
        }

        data.putOffice(nation.nationId(), new NationOfficeRecord(
                officeId,
                officeName,
                nextCustomOfficePriority(data, nation.nationId()),
                EnumSet.noneOf(NationPermission.class)
        ));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.office.create.success", officeName, officeId));
    }

    public static NationResult renameOffice(ServerPlayer actor, String rawOfficeId, String rawName) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (!actor.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.manage.no_permission"));
        }

        String officeId = normalizeOfficeId(rawOfficeId);
        if (NationOfficeIds.LEADER.equals(officeId) || NationOfficeIds.MEMBER.equals(officeId)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.rename.reserved"));
        }
        NationOfficeRecord office = data.getOffice(nation.nationId(), officeId);
        if (office == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.missing", rawOfficeId));
        }
        String officeName = normalizeOfficeName(rawName);
        if (!isValidOfficeName(officeName)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.name.invalid", MAX_OFFICE_NAME_LENGTH));
        }
        if (officeName.equals(office.name())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.rename.unchanged"));
        }

        data.putOffice(nation.nationId(), new NationOfficeRecord(office.officeId(), officeName, office.priority(), office.permissions()));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.office.rename.success_generic", officeName));
    }

    public static NationResult deleteOffice(ServerPlayer actor, String rawOfficeId) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (!actor.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.manage.no_permission"));
        }

        String officeId = normalizeOfficeId(rawOfficeId);
        if (NationOfficeIds.LEADER.equals(officeId) || NationOfficeIds.OFFICER.equals(officeId) || NationOfficeIds.MEMBER.equals(officeId)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.delete.reserved"));
        }
        NationOfficeRecord office = data.getOffice(nation.nationId(), officeId);
        if (office == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.missing", rawOfficeId));
        }

        for (NationMemberRecord member : data.getMembersForNation(nation.nationId())) {
            if (!office.officeId().equals(member.officeId())) {
                continue;
            }
            data.putMember(new NationMemberRecord(
                    member.playerUuid(),
                    member.lastKnownName(),
                    member.nationId(),
                    NationOfficeIds.MEMBER,
                    member.joinedAt()
            ));
        }
        data.removeOffice(nation.nationId(), office.officeId());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.office.delete.success", office.name()));
    }

    public static NationResult setOfficePermission(ServerPlayer actor, String rawOfficeId, String rawPermissionId, boolean value) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (!actor.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.manage.no_permission"));
        }

        String officeId = normalizeOfficeId(rawOfficeId);
        NationOfficeRecord office = data.getOffice(nation.nationId(), officeId);
        if (office == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.missing", rawOfficeId));
        }
        if (NationOfficeIds.LEADER.equals(officeId)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.permission.leader_locked"));
        }

        NationPermission permission = parsePermission(rawPermissionId);
        if (permission == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.permission.invalid", rawPermissionId));
        }

        EnumSet<NationPermission> permissions = office.permissions().isEmpty()
                ? EnumSet.noneOf(NationPermission.class)
                : EnumSet.copyOf(office.permissions());
        if (value) {
            permissions.add(permission);
        } else {
            permissions.remove(permission);
        }

        data.putOffice(nation.nationId(), new NationOfficeRecord(office.officeId(), office.name(), office.priority(), permissions));
        return NationResult.success(Component.translatable(
                "command.sailboatmod.nation.office.permission.success",
                office.name(),
                permission.name().toLowerCase(Locale.ROOT),
                value ? "true" : "false"
        ));
    }

    public static List<Component> describeOffices(ServerPlayer actor) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return List.of(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }

        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.nation.office.info.header"));
        for (NationOfficeRecord office : data.getOfficesForNation(actorMember.nationId())) {
            lines.add(Component.translatable(
                    "command.sailboatmod.nation.office.info.entry",
                    office.name(),
                    office.officeId(),
                    office.priority(),
                    permissionSummary(office.permissions())
            ));
        }
        return lines;
    }

    public static NationResult renameOfficerTitle(ServerPlayer actor, String rawName) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (!actor.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.rename.no_permission"));
        }

        String officeName = normalizeOfficeName(rawName);
        if (!isValidOfficeName(officeName)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.rename.invalid", MAX_OFFICE_NAME_LENGTH));
        }

        NationOfficeRecord office = data.getOffice(nation.nationId(), NationOfficeIds.OFFICER);
        if (office == null) {
            office = new NationOfficeRecord(
                    NationOfficeIds.OFFICER,
                    officeName,
                    10,
                    EnumSet.of(
                            NationPermission.INVITE_MEMBERS,
                            NationPermission.KICK_MEMBERS,
                            NationPermission.MANAGE_CLAIMS,
                            NationPermission.DECLARE_WAR,
                            NationPermission.UPLOAD_FLAG
                    )
            );
        } else if (officeName.equals(office.name())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.office.rename.unchanged"));
        } else {
            office = new NationOfficeRecord(office.officeId(), officeName, office.priority(), office.permissions());
        }

        data.putOffice(nation.nationId(), office);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.office.rename.success", office.name()));
    }

    public static NationResult transferLeadership(ServerPlayer actor, ServerPlayer target) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);
        updateKnownPlayer(target);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (!actor.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.leader.no_permission"));
        }
        if (actor.getUUID().equals(target.getUUID())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.leader.self"));
        }

        NationMemberRecord targetMember = data.getMember(target.getUUID());
        if (targetMember == null || !nation.nationId().equals(targetMember.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.leader.not_same_nation", target.getGameProfile().getName()));
        }

        data.putNation(new NationRecord(
                nation.nationId(),
                nation.name(),
                nation.shortName(),
                nation.primaryColorRgb(),
                nation.secondaryColorRgb(),
                target.getUUID(),
                nation.createdAt(),
                nation.capitalTownId(),
                nation.coreDimension(),
                nation.corePos(),
                nation.flagId()
        ));
        data.putMember(new NationMemberRecord(targetMember.playerUuid(), target.getGameProfile().getName(), targetMember.nationId(), NationOfficeIds.LEADER, targetMember.joinedAt()));
        data.putMember(new NationMemberRecord(actorMember.playerUuid(), actor.getGameProfile().getName(), actorMember.nationId(), NationOfficeIds.OFFICER, actorMember.joinedAt()));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.leader.success", target.getGameProfile().getName()));
    }

    public static NationResult setPrimaryColor(ServerPlayer actor, String rawHex) {
        return setNationColor(actor, rawHex, true);
    }

    public static NationResult setSecondaryColor(ServerPlayer actor, String rawHex) {
        return setNationColor(actor, rawHex, false);
    }

    public static NationRecord getPlayerNation(Level level, UUID playerUuid) {
        NationSavedData data = NationSavedData.get(level);
        NationMemberRecord member = data.getMember(playerUuid);
        return member == null ? null : data.getNation(member.nationId());
    }

    public static NationRecord findNation(Level level, String rawNationName) {
        if (level == null || rawNationName == null || rawNationName.isBlank()) {
            return null;
        }
        return NationSavedData.get(level).findNationByName(NationRecord.normalizeName(rawNationName));
    }

    public static List<Component> describeNation(Level level, NationRecord nation) {
        if (nation == null) {
            return List.of(Component.translatable("command.sailboatmod.nation.info.none"));
        }

        NationSavedData data = NationSavedData.get(level);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.nation.info.header", nation.name(), nation.shortName()));
        lines.add(Component.translatable("command.sailboatmod.nation.info.leader", playerName(data, level, nation.leaderUuid())));
        lines.add(Component.translatable("command.sailboatmod.nation.info.members", data.getMembersForNation(nation.nationId()).size()));
        lines.add(Component.translatable("command.sailboatmod.nation.info.colors", formatColor(nation.primaryColorRgb()), formatColor(nation.secondaryColorRgb())));
        lines.add(nation.hasCore()
                ? Component.translatable("command.sailboatmod.nation.info.core.set")
                : Component.translatable("command.sailboatmod.nation.info.core.none"));
        lines.add(Component.translatable("command.sailboatmod.nation.info.claims", data.getClaimsForNation(nation.nationId()).size()));
        NationWarRecord activeWar = NationWarService.getActiveWarForNation(data, nation.nationId());
        if (activeWar != null) {
            NationRecord attacker = data.getNation(activeWar.attackerNationId());
            NationRecord defender = data.getNation(activeWar.defenderNationId());
            long now = System.currentTimeMillis();
            lines.add(Component.translatable("command.sailboatmod.nation.war.info.header", nameOf(attacker), nameOf(defender)));
            lines.add(Component.translatable("command.sailboatmod.nation.war.info.score", activeWar.attackerScore(), activeWar.defenderScore()));
            lines.add(Component.translatable("command.sailboatmod.nation.war.info.status", Component.translatable("command.sailboatmod.nation.war.status." + safeStatus(activeWar.captureState()))));
            lines.add(Component.translatable("command.sailboatmod.nation.war.info.timer", formatWarDuration(Math.max(0L, (activeWar.startedAt() + NationWarService.warDurationMillis()) - now))));
        }
        NationFlagRecord flag = nation.flagId().isBlank() ? null : data.getFlag(nation.flagId());
        lines.add(flag == null
                ? Component.translatable("command.sailboatmod.nation.info.flag.none")
                : Component.translatable("command.sailboatmod.nation.info.flag.set", flag.width(), flag.height()));
        return lines;
    }

    public static Component buildNamePrefix(Level level, UUID playerUuid) {
        NationRecord nation = getPlayerNation(level, playerUuid);
        if (nation == null) {
            return Component.empty();
        }
        String shortName = nation.shortName().isBlank() ? NationRecord.buildShortName(nation.name()) : nation.shortName();
        MutableComponent prefix = Component.literal("[" + shortName + "] ");
        return prefix.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(nation.primaryColorRgb())));
    }

    public static void updateKnownPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return;
        }
        String currentName = player.getGameProfile().getName();
        if (!currentName.equals(member.lastKnownName())) {
            data.putMember(member.withLastKnownName(currentName));
        }
    }

    public static boolean hasPermission(Level level, UUID playerUuid, NationPermission permission) {
        NationSavedData data = NationSavedData.get(level);
        return hasPermission(data, data.getMember(playerUuid), permission);
    }
    private static NationResult changeOffice(ServerPlayer actor, UUID targetUuid, String officeId, String keyPrefix) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        if (!actor.getUUID().equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable(keyPrefix + ".no_permission"));
        }

        NationMemberRecord targetMember = data.getMember(targetUuid);
        if (targetMember == null || !targetMember.nationId().equals(actorMember.nationId())) {
            return NationResult.failure(Component.translatable(keyPrefix + ".not_same_nation", playerName(data, actor.level(), targetUuid)));
        }
        if (targetUuid.equals(nation.leaderUuid())) {
            return NationResult.failure(Component.translatable(keyPrefix + ".leader"));
        }
        if (officeId.equals(targetMember.officeId())) {
            return NationResult.failure(Component.translatable(keyPrefix + ".already", playerName(data, actor.level(), targetUuid)));
        }

        data.putMember(new NationMemberRecord(
                targetMember.playerUuid(),
                targetMember.lastKnownName(),
                targetMember.nationId(),
                officeId,
                targetMember.joinedAt()
        ));
        return NationResult.success(Component.translatable(keyPrefix + ".success", playerName(data, actor.level(), targetUuid)));
    }

    private static NationResult setNationColor(ServerPlayer actor, String rawHex, boolean primary) {
        NationSavedData data = NationSavedData.get(actor.level());
        updateKnownPlayer(actor);

        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!hasPermission(data, actorMember, NationPermission.MANAGE_INFO)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.color.no_permission"));
        }

        NationRecord nation = data.getNation(actorMember.nationId());
        if (nation == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }

        Integer parsed = NationRecord.parseHexColor(rawHex);
        if (parsed == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.color.invalid"));
        }

        int primaryColor = primary ? parsed : nation.primaryColorRgb();
        int secondaryColor = primary ? nation.secondaryColorRgb() : parsed;
        if (primaryColor == nation.primaryColorRgb() && secondaryColor == nation.secondaryColorRgb()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.color.unchanged"));
        }

        data.putNation(new NationRecord(
                nation.nationId(),
                nation.name(),
                nation.shortName(),
                primaryColor,
                secondaryColor,
                nation.leaderUuid(),
                nation.createdAt(),
                nation.capitalTownId(),
                nation.coreDimension(),
                nation.corePos(),
                nation.flagId()
        ));
        NationFlagBlockTracker.refreshNationFlags(actor.getServer(), nation.nationId());
        TownFlagBlockTracker.refreshNationFlags(actor.getServer(), nation.nationId());
        refreshNationPlayerNames(actor.getServer(), nation.nationId());
        return NationResult.success(Component.translatable(
                primary ? "command.sailboatmod.nation.color.primary_success" : "command.sailboatmod.nation.color.secondary_success",
                formatColor(parsed)
        ));
    }

    private static boolean hasPermission(NationSavedData data, NationMemberRecord member, NationPermission permission) {
        if (data == null || member == null || permission == null) {
            return false;
        }
        NationOfficeRecord office = data.getOffice(member.nationId(), member.officeId());
        return office != null && office.hasPermission(permission);
    }

    private static void refreshPlayerNames(ServerPlayer player) {
        if (player == null) {
            return;
        }
        invokeRefreshName(player, "refreshDisplayName");
        invokeRefreshName(player, "refreshTabListName");
    }

    private static void refreshNationPlayerNames(MinecraftServer server, String nationId) {
        if (server == null || nationId == null || nationId.isBlank()) {
            return;
        }
        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            NationRecord playerNation = getPlayerNation(onlinePlayer.level(), onlinePlayer.getUUID());
            if (playerNation == null || !nationId.equals(playerNation.nationId())) {
                continue;
            }
            invokeRefreshName(onlinePlayer, "refreshDisplayName");
            invokeRefreshName(onlinePlayer, "refreshTabListName");
        }
    }

    private static void invokeRefreshName(ServerPlayer player, String methodName) {
        try {
            player.getClass().getMethod(methodName).invoke(player);
        } catch (ReflectiveOperationException ignored) {
        }
    }
    private static List<NationOfficeRecord> defaultOffices() {
        return List.of(
                new NationOfficeRecord(
                        NationOfficeIds.LEADER,
                        "Leader",
                        0,
                        EnumSet.allOf(NationPermission.class)
                ),
                new NationOfficeRecord(
                        NationOfficeIds.OFFICER,
                        "Officer",
                        10,
                        EnumSet.of(
                                NationPermission.INVITE_MEMBERS,
                                NationPermission.KICK_MEMBERS,
                                NationPermission.MANAGE_CLAIMS,
                                NationPermission.DECLARE_WAR,
                                NationPermission.UPLOAD_FLAG
                        )
                ),
                new NationOfficeRecord(
                        NationOfficeIds.MEMBER,
                        "Member",
                        20,
                        EnumSet.noneOf(NationPermission.class)
                )
        );
    }

    private static void disbandNation(ServerLevel level, NationSavedData data, NationRecord nation) {
        for (NationFlagRecord flag : new ArrayList<>(data.getFlagsForNation(nation.nationId()))) {
            try {
                NationFlagStorage.deleteFlag(level, data, flag.flagId());
            } catch (IOException ignored) {
            }
        }
        data.removeNation(nation.nationId());
        NationFlagBlockTracker.refreshNationFlags(level.getServer(), nation.nationId());
        TownFlagBlockTracker.refreshNationFlags(level.getServer(), nation.nationId());
    }

    private static String permissionSummary(Set<NationPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        for (NationPermission permission : permissions) {
            values.add(permission.name().toLowerCase(Locale.ROOT));
        }
        values.sort(String::compareTo);
        return String.join(", ", values);
    }

    private static int nextCustomOfficePriority(NationSavedData data, String nationId) {
        int priority = 11;
        for (NationOfficeRecord office : data.getOfficesForNation(nationId)) {
            if (office.priority() >= priority && office.priority() < 20) {
                priority = office.priority() + 1;
            }
        }
        return Math.min(priority, 19);
    }

    private static NationPermission parsePermission(String rawPermissionId) {
        if (rawPermissionId == null || rawPermissionId.isBlank()) {
            return null;
        }
        String normalized = rawPermissionId.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return NationPermission.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isValidOfficeId(String officeId) {
        return officeId != null
                && officeId.length() >= MIN_OFFICE_ID_LENGTH
                && officeId.length() <= MAX_OFFICE_ID_LENGTH
                && officeId.matches("[a-z0-9_-]+");
    }

    private static String normalizeOfficeId(String rawOfficeId) {
        return rawOfficeId == null ? "" : rawOfficeId.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isValidOfficeName(String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_OFFICE_NAME_LENGTH;
    }

    private static String normalizeOfficeName(String rawName) {
        if (rawName == null) {
            return "";
        }
        return rawName.trim().replaceAll("\\s+", " ");
    }

    private static String formatWarDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%d:%02d", minutes, seconds);
    }

    private static String safeStatus(String value) {
        return value == null || value.isBlank() ? "idle" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String nameOf(NationRecord nation) {
        return nation == null || nation.name().isBlank() ? "-" : nation.name();
    }

    private static String playerName(NationSavedData data, Level level, UUID playerUuid) {
        if (playerUuid == null) {
            return "-";
        }
        if (level != null && level.getServer() != null) {
            ServerPlayer onlinePlayer = level.getServer().getPlayerList().getPlayer(playerUuid);
            if (onlinePlayer != null) {
                return onlinePlayer.getGameProfile().getName();
            }
        }
        NationMemberRecord member = data.getMember(playerUuid);
        if (member != null && !member.lastKnownName().isBlank()) {
            return member.lastKnownName();
        }
        return playerUuid.toString();
    }

    private static String formatColor(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0x00FFFFFF);
    }

    private NationService() {
    }
}
