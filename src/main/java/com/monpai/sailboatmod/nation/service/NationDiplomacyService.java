package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRequestRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyStatus;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public final class NationDiplomacyService {
    public static NationResult requestAlliance(ServerPlayer actor, String rawNationName) {
        Context context = context(actor);
        if (context.error() != null) {
            return context.error();
        }

        NationRecord target = NationService.findNation(actor.level(), rawNationName);
        if (target == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.target_not_found", rawNationName));
        }
        if (context.nation().nationId().equals(target.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.self"));
        }
        if (NationWarService.areAtWar(context.data(), context.nation().nationId(), target.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.at_war", target.name()));
        }

        NationDiplomacyRecord relation = context.data().getDiplomacy(context.nation().nationId(), target.nationId());
        if (relation != null && NationDiplomacyStatus.ALLIED.id().equals(relation.statusId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.ally.already", target.name()));
        }

        NationDiplomacyRequestRecord reverse = context.data().getDiplomacyRequest(target.nationId(), context.nation().nationId(), NationDiplomacyStatus.ALLIED.id());
        if (reverse != null) {
            return acceptAlliance(actor, rawNationName);
        }
        if (context.data().getDiplomacyRequest(context.nation().nationId(), target.nationId(), NationDiplomacyStatus.ALLIED.id()) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.ally.request.pending", target.name()));
        }

        context.data().putDiplomacyRequest(new NationDiplomacyRequestRecord(
                context.nation().nationId(),
                target.nationId(),
                NationDiplomacyStatus.ALLIED.id(),
                System.currentTimeMillis()
        ));
        return NationResult.success(Component.translatable("command.sailboatmod.nation.diplomacy.ally.request.sent", target.name()));
    }

    public static NationResult acceptAlliance(ServerPlayer actor, String rawNationName) {
        Context context = context(actor);
        if (context.error() != null) {
            return context.error();
        }

        NationRecord target = NationService.findNation(actor.level(), rawNationName);
        if (target == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.target_not_found", rawNationName));
        }
        if (NationWarService.areAtWar(context.data(), context.nation().nationId(), target.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.at_war", target.name()));
        }
        NationDiplomacyRequestRecord request = context.data().getDiplomacyRequest(target.nationId(), context.nation().nationId(), NationDiplomacyStatus.ALLIED.id());
        if (request == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.ally.request.missing", target.name()));
        }

        context.data().putDiplomacy(new NationDiplomacyRecord(
                context.nation().nationId(),
                target.nationId(),
                NationDiplomacyStatus.ALLIED.id(),
                System.currentTimeMillis()
        ));
        context.data().removeDiplomacyRequest(target.nationId(), context.nation().nationId(), NationDiplomacyStatus.ALLIED.id());
        context.data().removeDiplomacyRequest(context.nation().nationId(), target.nationId(), NationDiplomacyStatus.ALLIED.id());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.diplomacy.ally.accept.success", target.name()));
    }

    public static NationResult rejectAlliance(ServerPlayer actor, String rawNationName) {
        Context context = context(actor);
        if (context.error() != null) {
            return context.error();
        }

        NationRecord target = NationService.findNation(actor.level(), rawNationName);
        if (target == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.target_not_found", rawNationName));
        }
        NationDiplomacyRequestRecord request = context.data().getDiplomacyRequest(target.nationId(), context.nation().nationId(), NationDiplomacyStatus.ALLIED.id());
        if (request == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.ally.request.missing", target.name()));
        }

        context.data().removeDiplomacyRequest(target.nationId(), context.nation().nationId(), NationDiplomacyStatus.ALLIED.id());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.diplomacy.ally.reject.success", target.name()));
    }

    public static NationResult setTrade(ServerPlayer actor, String rawNationName) {
        return setRelation(actor, rawNationName, NationDiplomacyStatus.TRADE, "command.sailboatmod.nation.diplomacy.trade.already", "command.sailboatmod.nation.diplomacy.trade.success");
    }

    public static NationResult setEnemy(ServerPlayer actor, String rawNationName) {
        return setRelation(actor, rawNationName, NationDiplomacyStatus.ENEMY, "command.sailboatmod.nation.diplomacy.enemy.already", "command.sailboatmod.nation.diplomacy.enemy.success");
    }

    public static NationResult setNeutral(ServerPlayer actor, String rawNationName) {
        Context context = context(actor);
        if (context.error() != null) {
            return context.error();
        }

        NationRecord target = NationService.findNation(actor.level(), rawNationName);
        if (target == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.target_not_found", rawNationName));
        }
        NationDiplomacyRecord relation = context.data().getDiplomacy(context.nation().nationId(), target.nationId());
        if (relation == null || NationDiplomacyStatus.NEUTRAL.id().equals(relation.statusId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.neutral.already", target.name()));
        }

        context.data().putDiplomacy(new NationDiplomacyRecord(
                context.nation().nationId(),
                target.nationId(),
                NationDiplomacyStatus.NEUTRAL.id(),
                System.currentTimeMillis()
        ));
        clearAllianceRequests(context.data(), context.nation().nationId(), target.nationId());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.diplomacy.neutral.success", target.name()));
    }

    public static List<Component> describe(ServerPlayer actor) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationMemberRecord member = data.getMember(actor.getUUID());
        if (member == null) {
            return List.of(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }

        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("command.sailboatmod.nation.diplomacy.info.header"));
        List<NationDiplomacyRecord> relations = data.getDiplomacyForNation(member.nationId());
        if (relations.isEmpty()) {
            lines.add(Component.translatable("command.sailboatmod.nation.diplomacy.info.none"));
        } else {
            for (NationDiplomacyRecord relation : relations) {
                NationRecord other = data.getNation(relation.otherNationId(member.nationId()));
                lines.add(Component.translatable(
                        "command.sailboatmod.nation.diplomacy.info.entry",
                        other == null ? relation.otherNationId(member.nationId()) : other.name(),
                        Component.translatable("command.sailboatmod.nation.diplomacy.status." + relation.statusId())
                ));
            }
        }

        List<NationDiplomacyRequestRecord> requests = data.getIncomingDiplomacyRequests(member.nationId());
        if (!requests.isEmpty()) {
            lines.add(Component.translatable("command.sailboatmod.nation.diplomacy.request.header"));
            for (NationDiplomacyRequestRecord request : requests) {
                if (!NationDiplomacyStatus.ALLIED.id().equals(request.statusId())) {
                    continue;
                }
                NationRecord other = data.getNation(request.fromNationId());
                lines.add(Component.translatable(
                        "command.sailboatmod.nation.diplomacy.request.entry",
                        other == null ? request.fromNationId() : other.name(),
                        Component.translatable("command.sailboatmod.nation.diplomacy.status." + request.statusId())
                ));
            }
        }
        return lines;
    }

    private static NationResult setRelation(ServerPlayer actor, String rawNationName, NationDiplomacyStatus status, String alreadyKey, String successKey) {
        Context context = context(actor);
        if (context.error() != null) {
            return context.error();
        }

        NationRecord target = NationService.findNation(actor.level(), rawNationName);
        if (target == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.target_not_found", rawNationName));
        }
        if (context.nation().nationId().equals(target.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.self"));
        }
        if (status != NationDiplomacyStatus.ENEMY && status != NationDiplomacyStatus.NEUTRAL
                && NationWarService.areAtWar(context.data(), context.nation().nationId(), target.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.at_war", target.name()));
        }

        NationDiplomacyRecord relation = context.data().getDiplomacy(context.nation().nationId(), target.nationId());
        if (relation != null && status.id().equals(relation.statusId())) {
            return NationResult.failure(Component.translatable(alreadyKey, target.name()));
        }

        context.data().putDiplomacy(new NationDiplomacyRecord(
                context.nation().nationId(),
                target.nationId(),
                status.id(),
                System.currentTimeMillis()
        ));
        clearAllianceRequests(context.data(), context.nation().nationId(), target.nationId());
        return NationResult.success(Component.translatable(successKey, target.name()));
    }

    private static void clearAllianceRequests(NationSavedData data, String nationId, String targetNationId) {
        data.removeDiplomacyRequest(nationId, targetNationId, NationDiplomacyStatus.ALLIED.id());
        data.removeDiplomacyRequest(targetNationId, nationId, NationDiplomacyStatus.ALLIED.id());
    }

    private static Context context(ServerPlayer actor) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationMemberRecord member = data.getMember(actor.getUUID());
        if (member == null) {
            return new Context(data, null, NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation")));
        }
        if (!NationService.hasPermission(actor.level(), actor.getUUID(), NationPermission.DECLARE_WAR)) {
            return new Context(data, null, NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.no_permission")));
        }
        NationRecord nation = data.getNation(member.nationId());
        if (nation == null) {
            return new Context(data, null, NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing")));
        }
        return new Context(data, nation, null);
    }

    private record Context(NationSavedData data, NationRecord nation, NationResult error) {
    }

    private NationDiplomacyService() {
    }
}