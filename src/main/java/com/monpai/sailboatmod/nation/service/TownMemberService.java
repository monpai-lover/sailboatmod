package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.TownMemberInviteRecord;
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TownMemberService {

    private TownMemberService() {
    }

    // ---- 玩家申请加入 town ----
    public static NationResult applyToTown(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        TownRecord town = data.findTownByName(rawTownName);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.not_found", rawTownName));
        }
        return applyToTownInternal(data, actor.getUUID(), town);
    }

    static NationResult applyToTownForTest(NationSavedData data, UUID playerUuid, String townId) {
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.not_found", townId));
        }
        return applyToTownInternal(data, playerUuid, town);
    }

    private static NationResult applyToTownInternal(NationSavedData data, UUID playerUuid, TownRecord town) {
        if (data.getTownMember(town.townId(), playerUuid) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.already_member", town.name()));
        }
        TownMemberInviteRecord existing = data.getTownMemberInvite(town.townId(), playerUuid);
        if (existing != null && existing.isInvite()) {
            data.removeTownMemberInvite(town.townId(), playerUuid);
            return completeJoin(data, playerUuid, town);
        }
        if (existing != null && existing.isApply()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.apply.already_sent", town.name()));
        }
        data.putTownMemberInvite(new TownMemberInviteRecord(
                town.townId(), playerUuid, TownMemberInviteRecord.DIRECTION_APPLY, playerUuid, System.currentTimeMillis()));
        return NationResult.success(Component.translatable("command.sailboatmod.town.apply.success", town.name()));
    }

    // ---- 镇长邀请玩家入 town ----
    public static NationResult invitePlayer(ServerPlayer actor, ServerPlayer target) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        NationService.updateKnownPlayer(target);
        TownRecord town = firstTownForMayor(data, actor.getUUID());
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        return inviteToTownInternal(data, town, target.getUUID());
    }

    static NationResult inviteToTownForTest(NationSavedData data, UUID mayorUuid, String townId, UUID targetUuid) {
        TownRecord town = data.getTown(townId);
        if (town == null || !mayorUuid.equals(town.mayorUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        return inviteToTownInternal(data, town, targetUuid);
    }

    private static NationResult inviteToTownInternal(NationSavedData data, TownRecord town, UUID targetUuid) {
        if (data.getTownMember(town.townId(), targetUuid) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.already_member", town.name()));
        }
        TownMemberInviteRecord existing = data.getTownMemberInvite(town.townId(), targetUuid);
        if (existing != null && existing.isApply()) {
            data.removeTownMemberInvite(town.townId(), targetUuid);
            return completeJoin(data, targetUuid, town);
        }
        if (existing != null && existing.isInvite()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.already_sent", town.name()));
        }
        data.putTownMemberInvite(new TownMemberInviteRecord(
                town.townId(), targetUuid, TownMemberInviteRecord.DIRECTION_INVITE, targetUuid, System.currentTimeMillis()));
        return NationResult.success(Component.translatable("command.sailboatmod.town.invite.success", town.name()));
    }

    // ---- 写入镇民 + 随 town 入 nation 联动 ----
    static NationResult completeJoin(NationSavedData data, UUID playerUuid, TownRecord town) {
        data.putTownMember(new TownMemberRecord(
                playerUuid, town.townId(), TownMemberRecord.OFFICE_MEMBER, System.currentTimeMillis()));
        TownService.syncPlayerNationWithTown(data, playerUuid, town);
        return NationResult.success(Component.translatable("command.sailboatmod.town.join.success", town.name()));
    }

    // ---- 退出 town ----
    public static NationResult leaveTown(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        TownRecord town = data.findTownByName(rawTownName);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.not_found", rawTownName));
        }
        return leaveTownInternal(data, actor.getUUID(), town);
    }

    static NationResult leaveTownForTest(NationSavedData data, UUID playerUuid, String townId) {
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.not_found", townId));
        }
        return leaveTownInternal(data, playerUuid, town);
    }

    private static NationResult leaveTownInternal(NationSavedData data, UUID playerUuid, TownRecord town) {
        if (data.getTownMember(town.townId(), playerUuid) == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.leave.not_member", town.name()));
        }
        data.removeTownMember(town.townId(), playerUuid);
        TownService.demotePlayerNationIfOrphaned(data, playerUuid, town);
        return NationResult.success(Component.translatable("command.sailboatmod.town.leave.success", town.name()));
    }

    // ---- 镇长踢出镇民 ----
    public static NationResult kickMember(ServerPlayer actor, ServerPlayer target) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        TownRecord town = firstTownForMayor(data, actor.getUUID());
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        if (data.getTownMember(town.townId(), target.getUUID()) == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.kick.not_member", town.name()));
        }
        if (target.getUUID().equals(town.mayorUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.kick.is_mayor"));
        }
        data.removeTownMember(town.townId(), target.getUUID());
        TownService.demotePlayerNationIfOrphaned(data, target.getUUID(), town);
        return NationResult.success(Component.translatable("command.sailboatmod.town.kick.success", town.name()));
    }

    // ---- 镇长接受/拒绝申请 ----
    public static NationResult acceptApply(ServerPlayer actor, ServerPlayer applicant) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        TownRecord town = firstTownForMayor(data, actor.getUUID());
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        return acceptApplyInternal(data, town, applicant.getUUID());
    }

    static NationResult acceptApplyForTest(NationSavedData data, UUID mayorUuid, String townId, UUID applicantUuid) {
        TownRecord town = data.getTown(townId);
        if (town == null || !mayorUuid.equals(town.mayorUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        return acceptApplyInternal(data, town, applicantUuid);
    }

    private static NationResult acceptApplyInternal(NationSavedData data, TownRecord town, UUID applicantUuid) {
        TownMemberInviteRecord req = data.getTownMemberInvite(town.townId(), applicantUuid);
        if (req == null || !req.isApply()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.apply.missing", town.name()));
        }
        data.removeTownMemberInvite(town.townId(), applicantUuid);
        return completeJoin(data, applicantUuid, town);
    }

    public static NationResult rejectApply(ServerPlayer actor, ServerPlayer applicant) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        TownRecord town = firstTownForMayor(data, actor.getUUID());
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        return rejectApplyInternal(data, town, applicant.getUUID());
    }

    static NationResult rejectApplyForTest(NationSavedData data, UUID mayorUuid, String townId, UUID applicantUuid) {
        TownRecord town = data.getTown(townId);
        if (town == null || !mayorUuid.equals(town.mayorUuid())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        return rejectApplyInternal(data, town, applicantUuid);
    }

    private static NationResult rejectApplyInternal(NationSavedData data, TownRecord town, UUID applicantUuid) {
        TownMemberInviteRecord req = data.getTownMemberInvite(town.townId(), applicantUuid);
        if (req == null || !req.isApply()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.apply.missing", town.name()));
        }
        data.removeTownMemberInvite(town.townId(), applicantUuid);
        return NationResult.success(Component.translatable("command.sailboatmod.town.apply.reject.success", town.name()));
    }

    // ---- 玩家接受/拒绝邀请 ----
    public static NationResult joinTown(ServerPlayer actor, String rawTownName) {
        // 玩家接受邀请 = 走 applyToTown（相反方向 invite 存在则成交）
        return applyToTown(actor, rawTownName);
    }

    public static NationResult declineInvite(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationService.updateKnownPlayer(actor);
        TownRecord town = data.findTownByName(rawTownName);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.not_found", rawTownName));
        }
        TownMemberInviteRecord req = data.getTownMemberInvite(town.townId(), actor.getUUID());
        if (req == null || !req.isInvite()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.town.decline.missing", town.name()));
        }
        data.removeTownMemberInvite(town.townId(), actor.getUUID());
        return NationResult.success(Component.translatable("command.sailboatmod.town.decline.success", town.name()));
    }

    // ---- 列表查询 ----
    public static List<Component> listMyTowns(ServerPlayer actor) {
        return listMyTownsForTest(NationSavedData.get(actor.level()), actor.getUUID());
    }

    static List<Component> listMyTownsForTest(NationSavedData data, UUID playerUuid) {
        List<String> townIds = data.getTownsForPlayer(playerUuid);
        if (townIds.isEmpty()) {
            return List.of(Component.translatable("command.sailboatmod.town.list.empty"));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.town.list.header"));
        for (String townId : townIds) {
            TownRecord town = data.getTown(townId);
            String name = town == null ? townId : town.name();
            lines.add(Component.translatable("command.sailboatmod.town.list.entry", name));
        }
        return lines;
    }

    public static List<Component> listMembers(ServerPlayer actor, String rawTownName) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = rawTownName == null || rawTownName.isBlank()
                ? firstTownForMayor(data, actor.getUUID())
                : data.findTownByName(rawTownName);
        if (town == null) {
            return List.of(Component.translatable("command.sailboatmod.town.not_found", rawTownName == null ? "" : rawTownName));
        }
        List<TownMemberRecord> members = data.getTownMembersForTown(town.townId());
        if (members.isEmpty()) {
            return List.of(Component.translatable("command.sailboatmod.town.members.empty", town.name()));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.town.members.header", town.name()));
        for (TownMemberRecord member : members) {
            NationMemberRecord nm = data.getMember(member.playerUuid());
            String name = nm == null || nm.lastKnownName().isBlank() ? member.playerUuid().toString() : nm.lastKnownName();
            lines.add(Component.translatable("command.sailboatmod.town.members.entry", name));
        }
        return lines;
    }

    public static List<Component> listRequests(ServerPlayer actor) {
        NationSavedData data = NationSavedData.get(actor.level());
        TownRecord town = firstTownForMayor(data, actor.getUUID());
        if (town == null) {
            return List.of(Component.translatable("command.sailboatmod.town.invite.not_mayor"));
        }
        List<TownMemberInviteRecord> requests = data.getTownMemberInvitesForTown(town.townId());
        if (requests.isEmpty()) {
            return List.of(Component.translatable("command.sailboatmod.town.requests.empty", town.name()));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.town.requests.header", town.name()));
        for (TownMemberInviteRecord req : requests) {
            NationMemberRecord nm = data.getMember(req.playerUuid());
            String name = nm == null || nm.lastKnownName().isBlank() ? req.playerUuid().toString() : nm.lastKnownName();
            lines.add(Component.translatable("command.sailboatmod.town.requests.entry", name, req.direction()));
        }
        return lines;
    }

    private static TownRecord firstTownForMayor(NationSavedData data, UUID mayorUuid) {
        for (TownRecord town : data.getTownsForMayor(mayorUuid)) {
            return town;
        }
        return null;
    }
}
