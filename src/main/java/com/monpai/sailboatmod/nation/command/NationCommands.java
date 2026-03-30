package com.monpai.sailboatmod.nation.command;

import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.service.NationAdminService;
import com.monpai.sailboatmod.nation.service.NationClaimService;
import com.monpai.sailboatmod.nation.service.NationDiplomacyService;
import com.monpai.sailboatmod.nation.service.NationFlagService;
import com.monpai.sailboatmod.nation.service.NationResult;
import com.monpai.sailboatmod.nation.service.NationService;
import com.monpai.sailboatmod.nation.service.NationWarService;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.network.packet.NationToastPacket;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

public final class NationCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> nation = Commands.literal("nation");

        nation.then(Commands.literal("help")
                .executes(context -> sendLines(context.getSource(), helpLines())));

        nation.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(context -> sendResult(context.getSource(), NationService.createNation(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "name"))))));

        nation.then(Commands.literal("town")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), TownService.createTown(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "name"))))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("town", StringArgumentType.word())
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> sendResult(context.getSource(), TownService.renameTown(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "town"), StringArgumentType.getString(context, "name")))))))
                .then(Commands.literal("info")
                        .then(Commands.argument("town", StringArgumentType.greedyString())
                                .executes(context -> sendLines(context.getSource(), TownService.describeTown(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "town"))))))
                .then(Commands.literal("mayor")
                        .then(Commands.argument("town", StringArgumentType.word())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> sendResult(context.getSource(), TownService.assignMayor(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "town"), EntityArgument.getPlayer(context, "player"))))))));

        nation.then(Commands.literal("rename")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(context -> sendResult(context.getSource(), NationService.renameNation(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "name"))))));

        nation.then(Commands.literal("shortname")
                .then(Commands.argument("short", StringArgumentType.word())
                        .executes(context -> sendResult(context.getSource(), NationService.setShortName(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "short"))))));

        nation.then(Commands.literal("invite")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            ServerPlayer target = EntityArgument.getPlayer(context, "player");
                            NationResult result = NationService.invitePlayer(player, target);
                            int commandResult = sendResult(context.getSource(), result);
                            if (result.success()) {
                                NationRecord nationRecord = NationService.getPlayerNation(player.level(), player.getUUID());
                                if (nationRecord != null) {
                                    Component inviteMessage = Component.translatable(
                                            "command.sailboatmod.nation.invite.target",
                                            player.getGameProfile().getName(),
                                            nationRecord.name(),
                                            nationRecord.name()
                                    );
                                    target.sendSystemMessage(inviteMessage);
                                    NationToastPacket.send(target, Component.translatable("toast.sailboatmod.nation.invite.title"), inviteMessage);
                                }
                            }
                            return commandResult;
                        })));

        nation.then(Commands.literal("decline")
                .then(Commands.argument("nation", StringArgumentType.greedyString())
                        .executes(context -> sendResult(context.getSource(), NationService.declineInvite(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "nation"))))));

        nation.then(Commands.literal("apply")
                .then(Commands.literal("list")
                        .executes(context -> sendLines(context.getSource(), NationService.describeJoinRequests(context.getSource().getPlayerOrException()))))
                .then(Commands.literal("accept")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> sendResult(context.getSource(), NationService.acceptJoinRequest(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"))))))
                .then(Commands.literal("reject")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> sendResult(context.getSource(), NationService.rejectJoinRequest(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"))))))
                .then(Commands.argument("nation", StringArgumentType.greedyString())
                        .executes(context -> sendResult(context.getSource(), NationService.applyToNation(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "nation"))))));

        nation.then(Commands.literal("join")
                .then(Commands.argument("nation", StringArgumentType.greedyString())
                        .executes(context -> sendResult(context.getSource(), NationService.joinNation(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "nation"))))));

        nation.then(Commands.literal("leave")
                .executes(context -> sendResult(context.getSource(), NationService.leaveNation(context.getSource().getPlayerOrException()))));

        nation.then(Commands.literal("disband")
                .executes(context -> sendResult(context.getSource(), NationService.disbandNation(context.getSource().getPlayerOrException()))));

        nation.then(Commands.literal("kick")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> sendResult(context.getSource(), NationService.kickMember(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"))))));

        nation.then(Commands.literal("promote")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> sendResult(context.getSource(), NationService.promoteMember(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"))))));

        nation.then(Commands.literal("demote")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> sendResult(context.getSource(), NationService.demoteMember(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"))))));

        nation.then(Commands.literal("leader")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> sendResult(context.getSource(), NationService.transferLeadership(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"))))));

        nation.then(Commands.literal("office")
                .then(Commands.literal("info")
                        .executes(context -> sendLines(context.getSource(), NationService.describeOffices(context.getSource().getPlayerOrException()))))
                .then(Commands.literal("create")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> sendResult(context.getSource(), NationService.createOffice(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "id"), StringArgumentType.getString(context, "name")))))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("office", StringArgumentType.word())
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> sendResult(context.getSource(), NationService.renameOffice(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "office"), StringArgumentType.getString(context, "name")))))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("office", StringArgumentType.word())
                                .executes(context -> sendResult(context.getSource(), NationService.deleteOffice(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "office"))))))
                .then(Commands.literal("assign")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("office", StringArgumentType.word())
                                        .executes(context -> sendResult(context.getSource(), NationService.assignOffice(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"), StringArgumentType.getString(context, "office")))))))
                .then(Commands.literal("permission")
                        .then(Commands.argument("office", StringArgumentType.word())
                                .then(Commands.argument("permission", StringArgumentType.word())
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(context -> sendResult(context.getSource(), NationService.setOfficePermission(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "office"), StringArgumentType.getString(context, "permission"), BoolArgumentType.getBool(context, "value")))))))));

        nation.then(Commands.literal("claim")
                .executes(context -> sendResult(context.getSource(), NationClaimService.claimChunk(context.getSource().getPlayerOrException(), new ChunkPos(context.getSource().getPlayerOrException().blockPosition())))));

        nation.then(Commands.literal("unclaim")
                .executes(context -> sendResult(context.getSource(), NationClaimService.unclaimChunk(context.getSource().getPlayerOrException(), new ChunkPos(context.getSource().getPlayerOrException().blockPosition())))));

        nation.then(Commands.literal("claimperm")
                .then(Commands.literal("info")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            for (Component line : NationClaimService.describeChunkPermissions(player.level(), new ChunkPos(player.blockPosition()))) {
                                context.getSource().sendSuccess(() -> line, false);
                            }
                            return 1;
                        }))
                .then(Commands.argument("action", StringArgumentType.word())
                        .then(Commands.argument("level", StringArgumentType.word())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    return sendResult(context.getSource(), NationClaimService.setChunkPermission(player, new ChunkPos(player.blockPosition()), StringArgumentType.getString(context, "action"), StringArgumentType.getString(context, "level")));
                                }))));

        nation.then(Commands.literal("color")
                .then(Commands.argument("hex", StringArgumentType.greedyString())
                        .executes(context -> sendResult(context.getSource(), NationService.setPrimaryColor(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "hex")))))
                .then(Commands.literal("primary")
                        .then(Commands.argument("hex", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), NationService.setPrimaryColor(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "hex"))))))
                .then(Commands.literal("secondary")
                        .then(Commands.argument("hex", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), NationService.setSecondaryColor(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "hex")))))));

        nation.then(Commands.literal("flag")
                .then(Commands.literal("import")
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), NationFlagService.importFlag(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "path"))))))
                .then(Commands.literal("mirror")
                        .executes(context -> sendResult(context.getSource(), NationFlagService.setMirrored(context.getSource().getPlayerOrException(), null)))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> sendResult(context.getSource(), NationFlagService.setMirrored(context.getSource().getPlayerOrException(), BoolArgumentType.getBool(context, "enabled"))))))
                .then(Commands.literal("info")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            NationRecord nationRecord = NationService.getPlayerNation(player.level(), player.getUUID());
                            if (nationRecord == null) {
                                context.getSource().sendFailure(Component.translatable("command.sailboatmod.nation.info.none"));
                                return 0;
                            }
                            return sendLines(context.getSource(), NationFlagService.describeFlag(player.level(), nationRecord.nationId()));
                        })));

        nation.then(Commands.literal("war")
                .then(Commands.literal("declare")
                        .then(Commands.argument("nation", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), NationWarService.declareWar(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "nation"))))))
                .then(Commands.literal("info")
                        .executes(context -> sendLines(context.getSource(), NationWarService.describeWarStatus(context.getSource().getPlayerOrException())))));

        nation.then(Commands.literal("diplomacy")
                .then(Commands.literal("info")
                        .executes(context -> sendLines(context.getSource(), NationDiplomacyService.describe(context.getSource().getPlayerOrException()))))
                .then(Commands.literal("ally")
                        .then(Commands.argument("nation", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), NationDiplomacyService.requestAlliance(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "nation"))))))
                .then(Commands.literal("accept")
                        .then(Commands.argument("nation", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), NationDiplomacyService.acceptAlliance(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "nation"))))))
                .then(Commands.literal("reject")
                        .then(Commands.argument("nation", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), NationDiplomacyService.rejectAlliance(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "nation"))))))
                .then(Commands.literal("trade")
                        .then(Commands.argument("nation", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), NationDiplomacyService.setTrade(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "nation"))))))
                .then(Commands.literal("enemy")
                        .then(Commands.argument("nation", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), NationDiplomacyService.setEnemy(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "nation"))))))
                .then(Commands.literal("neutral")
                        .then(Commands.argument("nation", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), NationDiplomacyService.setNeutral(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "nation")))))));

        nation.then(Commands.literal("info")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    return sendNationInfo(context.getSource(), NationService.getPlayerNation(player.level(), player.getUUID()));
                })
                .then(Commands.argument("nation", StringArgumentType.greedyString())
                        .executes(context -> sendNationInfo(context.getSource(), NationService.findNation(context.getSource().getLevel(), StringArgumentType.getString(context, "nation"))))));

        dispatcher.register(nation);
        dispatcher.register(Commands.literal("nationadmin")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("disband")
                        .then(Commands.argument("nation", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), NationAdminService.disbandNation(context.getSource().getLevel(), StringArgumentType.getString(context, "nation"))))))
                .then(Commands.literal("clearflag")
                        .then(Commands.argument("nation", StringArgumentType.greedyString())
                                .executes(context -> sendResult(context.getSource(), NationAdminService.clearFlag(context.getSource().getLevel(), StringArgumentType.getString(context, "nation"))))))
                .then(Commands.literal("endwar")
                        .then(Commands.argument("warId", StringArgumentType.word())
                                .executes(context -> sendResult(context.getSource(), NationAdminService.endWar(context.getSource().getLevel(), StringArgumentType.getString(context, "warId"))))))
                .then(Commands.literal("setclaim")
                        .then(Commands.argument("nation", StringArgumentType.string())
                                .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                                .executes(context -> sendResult(context.getSource(), NationAdminService.setClaim(context.getSource().getLevel(), StringArgumentType.getString(context, "nation"), IntegerArgumentType.getInteger(context, "chunkX"), IntegerArgumentType.getInteger(context, "chunkZ"))))))))
                .then(Commands.literal("debug")
                        .then(Commands.literal("dump")
                                .executes(context -> sendLines(context.getSource(), NationAdminService.debugDump(context.getSource().getLevel(), "")))
                                .then(Commands.argument("nation", StringArgumentType.greedyString())
                                        .executes(context -> sendLines(context.getSource(), NationAdminService.debugDump(context.getSource().getLevel(), StringArgumentType.getString(context, "nation"))))))));
    }

    private static List<Component> helpLines() {
        return List.of(
                Component.translatable("command.sailboatmod.nation.help.header"),
                Component.translatable("command.sailboatmod.nation.help.create"),
                Component.translatable("command.sailboatmod.nation.help.join"),
                Component.translatable("command.sailboatmod.nation.help.town"),
                Component.translatable("command.sailboatmod.nation.help.manage"),
                Component.translatable("command.sailboatmod.nation.help.claim"),
                Component.translatable("command.sailboatmod.nation.help.flag"),
                Component.translatable("command.sailboatmod.nation.help.diplomacy"),
                Component.translatable("command.sailboatmod.nation.help.office")
        );
    }

    private static int sendNationInfo(CommandSourceStack source, NationRecord nation) {
        if (nation == null) {
            source.sendFailure(Component.translatable("command.sailboatmod.nation.info.none"));
            return 0;
        }
        return sendLines(source, NationService.describeNation(source.getLevel(), nation));
    }

    private static int sendResult(CommandSourceStack source, NationResult result) {
        if (result.success()) {
            source.sendSuccess(result::message, false);
            return 1;
        }
        source.sendFailure(result.message());
        return 0;
    }

    private static int sendLines(CommandSourceStack source, List<Component> lines) {
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
        return lines.isEmpty() ? 0 : 1;
    }

    private NationCommands() {
    }
}
