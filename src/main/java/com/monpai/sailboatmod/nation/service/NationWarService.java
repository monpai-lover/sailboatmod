package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyStatus;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.NationWarRecord;
import com.monpai.sailboatmod.network.packet.NationToastPacket;
import com.monpai.sailboatmod.network.packet.OpenNationScreenPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NationWarService {
    private static final double CORE_CAPTURE_RADIUS = 8.0D;
    private static final double CAPTURE_RATE_PER_TICK = 1.0D;
    private static final double CAPTURE_DECAY_PER_TICK = 0.75D;
    private static final double CAPTURE_IDLE_DECAY_PER_TICK = 0.35D;
    private static final double CAPTURE_THRESHOLD = 100.0D;
    private static final int SCORE_PER_CAPTURE = 20;
    private static final int OCCUPATION_SCORE_PER_TICK = 1;
    private static final long OCCUPATION_SCORE_INTERVAL_TICKS = 200L;
    private static final int SCORE_TO_WIN = 100;
    private static final long WAR_DURATION_MILLIS = 20L * 60L * 1000L;
    private static final long WAR_COOLDOWN_MILLIS = 10L * 60L * 1000L;
    private static final long ENDED_WAR_RETENTION_MILLIS = 30L * 60L * 1000L;
    private static final Map<String, WarSyncSnapshot> WAR_SYNC_CACHE = new HashMap<>();

    public static int scoreToWin() {
        return SCORE_TO_WIN;
    }

    public static long warDurationMillis() {
        return WAR_DURATION_MILLIS;
    }

    public static long warCooldownMillis() {
        return WAR_COOLDOWN_MILLIS;
    }

    public static void clearRuntimeState() {
        WAR_SYNC_CACHE.clear();
    }

    public static int remainingWarSeconds(NationWarRecord war, long nowMillis) {
        if (war == null || !war.isActive()) {
            return 0;
        }
        long remaining = Math.max(0L, (war.startedAt() + WAR_DURATION_MILLIS) - nowMillis);
        return (int) Math.ceil(remaining / 1000.0D);
    }

    public static long cooldownRemainingMillis(NationSavedData data, String nationId, long nowMillis) {
        if (data == null || nationId == null || nationId.isBlank()) {
            return 0L;
        }
        long remaining = 0L;
        for (NationWarRecord war : data.getWars()) {
            if (!war.isEnded() || war.endedAt() <= 0L) {
                continue;
            }
            if (!nationId.equals(war.attackerNationId()) && !nationId.equals(war.defenderNationId())) {
                continue;
            }
            long warRemaining = Math.max(0L, (war.endedAt() + WAR_COOLDOWN_MILLIS) - nowMillis);
            if (warRemaining > remaining) {
                remaining = warRemaining;
            }
        }
        return remaining;
    }

    public static NationResult declareWar(ServerPlayer actor, String rawTargetNation) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationMemberRecord actorMember = data.getMember(actor.getUUID());
        if (actorMember == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        }
        if (!NationService.hasPermission(actor.level(), actor.getUUID(), NationPermission.DECLARE_WAR)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.war.no_permission"));
        }

        NationRecord attacker = data.getNation(actorMember.nationId());
        if (attacker == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        }
        NationRecord defender = NationService.findNation(actor.level(), rawTargetNation);
        if (defender == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.war.target_not_found", rawTargetNation));
        }
        if (attacker.nationId().equals(defender.nationId())) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.war.self"));
        }
        if (!attacker.hasCore() || !defender.hasCore()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.war.need_core"));
        }
        if (getActiveWarForNation(data, attacker.nationId()) != null || getActiveWarForNation(data, defender.nationId()) != null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.war.already_active"));
        }

        long now = System.currentTimeMillis();
        long attackerCooldown = cooldownRemainingMillis(data, attacker.nationId(), now);
        if (attackerCooldown > 0L) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.war.cooldown", formatDurationSeconds(attackerCooldown)));
        }
        long defenderCooldown = cooldownRemainingMillis(data, defender.nationId(), now);
        if (defenderCooldown > 0L) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.war.target_cooldown", defender.name(), formatDurationSeconds(defenderCooldown)));
        }

        NationWarRecord war = new NationWarRecord(
                UUID.randomUUID().toString().replace("-", ""),
                attacker.nationId(),
                defender.nationId(),
                "active",
                0,
                0,
                0.0D,
                now,
                0L,
                "",
                0L,
                "idle"
        );
                data.putWar(war);
        clearDiplomacyOnWar(data, attacker.nationId(), defender.nationId());
        Component declareMessage = Component.translatable("command.sailboatmod.nation.war.declare.broadcast", attacker.name(), defender.name());
        Component timerMessage = Component.translatable("command.sailboatmod.nation.war.declare.timer", attacker.name(), defender.name(), formatDurationMinutes(WAR_DURATION_MILLIS));
        broadcast(declareMessage);
        broadcast(timerMessage);
        notifyWarParticipants(actor.getServer(), data, attacker.nationId(), defender.nationId(), Component.translatable("toast.sailboatmod.nation.war.title"), declareMessage);
        syncWarOverviewParticipants(actor.getServer(), data, war, now, true);
        return NationResult.success(Component.translatable("command.sailboatmod.nation.war.declare.success", defender.name()));
    }

    public static void tickWars(MinecraftServer server) {
        if (server == null) {
            return;
        }
        NationSavedData data = NationSavedData.get(server.overworld());
        long now = System.currentTimeMillis();
        purgeExpiredWars(data, now);
        List<NationWarRecord> wars = new ArrayList<>(data.getWars());
        long tickCount = server.getTickCount();
        for (NationWarRecord war : wars) {
            if (!war.isActive()) {
                continue;
            }
            tickWar(server, data, war, tickCount, now);
        }
    }

    public static List<Component> describeWarStatus(ServerPlayer player) {
        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return List.of(Component.translatable("command.sailboatmod.nation.war.none"));
        }

        long now = System.currentTimeMillis();
        NationWarRecord war = getActiveWarForNation(data, member.nationId());
        if (war == null) {
            long cooldownRemaining = cooldownRemainingMillis(data, member.nationId(), now);
            if (cooldownRemaining > 0L) {
                return List.of(Component.translatable("command.sailboatmod.nation.war.cooldown", formatDurationSeconds(cooldownRemaining)));
            }
            return List.of(Component.translatable("command.sailboatmod.nation.war.none"));
        }

        NationRecord attacker = data.getNation(war.attackerNationId());
        NationRecord defender = data.getNation(war.defenderNationId());
        return List.of(
                Component.translatable("command.sailboatmod.nation.war.info.header", nameOf(attacker), nameOf(defender)),
                Component.translatable("command.sailboatmod.nation.war.info.score", war.attackerScore(), war.defenderScore()),
                Component.translatable("command.sailboatmod.nation.war.info.capture", Math.round(war.captureProgress()), SCORE_TO_WIN),
                Component.translatable("command.sailboatmod.nation.war.info.status", Component.translatable("command.sailboatmod.nation.war.status." + safeStatus(war.captureState()))),
                Component.translatable("command.sailboatmod.nation.war.info.timer", formatDurationSeconds(Math.max(0L, (war.startedAt() + WAR_DURATION_MILLIS) - now)))
        );
    }


    public static NationResult adminEndWar(ServerLevel level, String warId) {
        if (level == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nationadmin.endwar.not_found", warId));
        }
        NationSavedData data = NationSavedData.get(level);
        NationWarRecord war = data.getWar(warId);
        if (war == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nationadmin.endwar.not_found", warId));
        }
        if (!war.isActive()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nationadmin.endwar.already_ended", war.warId()));
        }
        endWar(
                data,
                war,
                "",
                "ended",
                System.currentTimeMillis(),
                Component.translatable("command.sailboatmod.nationadmin.endwar.success", war.warId(), war.attackerScore(), war.defenderScore())
        );
        return NationResult.success(Component.translatable("command.sailboatmod.nationadmin.endwar.confirm", war.warId()));
    }

    public static NationWarRecord getActiveWarForNation(NationSavedData data, String nationId) {
        if (data == null || nationId == null || nationId.isBlank()) {
            return null;
        }
        for (NationWarRecord war : data.getWars()) {
            if (!war.isActive()) {
                continue;
            }
            if (nationId.equals(war.attackerNationId()) || nationId.equals(war.defenderNationId())) {
                return war;
            }
        }
        return null;
    }

    public static boolean areAtWar(NationSavedData data, String nationIdA, String nationIdB) {
        if (data == null || nationIdA == null || nationIdB == null) {
            return false;
        }
        for (NationWarRecord war : data.getWars()) {
            if (!war.isActive()) {
                continue;
            }
            if ((nationIdA.equals(war.attackerNationId()) && nationIdB.equals(war.defenderNationId()))
                    || (nationIdA.equals(war.defenderNationId()) && nationIdB.equals(war.attackerNationId()))) {
                return true;
            }
        }
        return false;
    }

    private static void clearDiplomacyOnWar(NationSavedData data, String attackerNationId, String defenderNationId) {
        NationDiplomacyRecord relation = data.getDiplomacy(attackerNationId, defenderNationId);
        if (relation != null && !NationDiplomacyStatus.ENEMY.id().equals(relation.statusId())) {
            data.putDiplomacy(new NationDiplomacyRecord(
                    attackerNationId,
                    defenderNationId,
                    NationDiplomacyStatus.ENEMY.id(),
                    System.currentTimeMillis()
            ));
        }
        data.removeDiplomacyRequest(attackerNationId, defenderNationId, NationDiplomacyStatus.ALLIED.id());
        data.removeDiplomacyRequest(defenderNationId, attackerNationId, NationDiplomacyStatus.ALLIED.id());
    }

    private static void tickWar(MinecraftServer server, NationSavedData data, NationWarRecord war, long tickCount, long nowMillis) {
        NationRecord attacker = data.getNation(war.attackerNationId());
        NationRecord defender = data.getNation(war.defenderNationId());
        if (attacker == null || defender == null || !attacker.hasCore() || !defender.hasCore()) {
            endWar(data, war, "", "ended", nowMillis, Component.translatable("command.sailboatmod.nation.war.ended.invalid"));
            return;
        }

        if (nowMillis - war.startedAt() >= WAR_DURATION_MILLIS) {
            endWarOnTimeout(data, war, attacker, defender, nowMillis);
            return;
        }

        BlockPos defenderCore = BlockPos.of(defender.corePos());
        net.minecraft.server.level.ServerLevel defenderLevel = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                new net.minecraft.resources.ResourceLocation(defender.coreDimension())
        ));
        if (defenderLevel == null) {
            return;
        }

        AABB area = new AABB(defenderCore).inflate(CORE_CAPTURE_RADIUS);
        int attackerCount = 0;
        int defenderCount = 0;
        for (ServerPlayer player : defenderLevel.getEntitiesOfClass(ServerPlayer.class, area)) {
            NationMemberRecord member = data.getMember(player.getUUID());
            if (member == null) {
                continue;
            }
            if (war.attackerNationId().equals(member.nationId())) {
                attackerCount++;
            } else if (war.defenderNationId().equals(member.nationId())) {
                defenderCount++;
            }
        }

        double captureProgress = war.captureProgress();
        int attackerScore = war.attackerScore();
        int defenderScore = war.defenderScore();
        String captureState = determineCaptureState(attackerCount, defenderCount);

        if (!captureState.equals(war.captureState())) {
            broadcastWarStateChange(captureState, attacker, defender);
        }

        if ("attacking".equals(captureState)) {
            captureProgress += CAPTURE_RATE_PER_TICK;
            attackerScore = awardOccupationScore(attackerScore, tickCount);
            if (captureProgress >= CAPTURE_THRESHOLD) {
                captureProgress = 0.0D;
                attackerScore = Math.min(SCORE_TO_WIN, attackerScore + SCORE_PER_CAPTURE);
                broadcast(Component.translatable("command.sailboatmod.nation.war.capture", attacker.name(), defender.name(), attackerScore, defenderScore));
            }
        } else if ("contested".equals(captureState)) {
            // No progress change while both sides are present.
        } else if ("defending".equals(captureState)) {
            captureProgress = Math.max(0.0D, captureProgress - CAPTURE_DECAY_PER_TICK);
            defenderScore = awardOccupationScore(defenderScore, tickCount);
        } else {
            captureProgress = Math.max(0.0D, captureProgress - CAPTURE_IDLE_DECAY_PER_TICK);
        }

        if (attackerScore >= SCORE_TO_WIN) {
            endWar(data, war, attacker.nationId(), captureState, nowMillis,
                    Component.translatable("command.sailboatmod.nation.war.ended.winner", nameOf(attacker), nameOf(defender), attackerScore, defenderScore));
            return;
        }
        if (defenderScore >= SCORE_TO_WIN) {
            endWar(data, war, defender.nationId(), captureState, nowMillis,
                    Component.translatable("command.sailboatmod.nation.war.ended.winner", nameOf(defender), nameOf(attacker), defenderScore, attackerScore));
            return;
        }

        NationWarRecord updatedWar = new NationWarRecord(
                war.warId(),
                war.attackerNationId(),
                war.defenderNationId(),
                "active",
                attackerScore,
                defenderScore,
                captureProgress,
                war.startedAt(),
                tickCount,
                "",
                0L,
                captureState
        );
        data.putWar(updatedWar);
        syncWarOverviewParticipants(server, data, updatedWar, nowMillis, false);
    }

    private static int awardOccupationScore(int currentScore, long tickCount) {
        if ((tickCount % OCCUPATION_SCORE_INTERVAL_TICKS) != 0L) {
            return currentScore;
        }
        return Math.min(SCORE_TO_WIN, currentScore + OCCUPATION_SCORE_PER_TICK);
    }

    private static void endWarOnTimeout(NationSavedData data, NationWarRecord war, NationRecord attacker, NationRecord defender, long nowMillis) {
        if (war.attackerScore() > war.defenderScore()) {
            endWar(data, war, attacker.nationId(), "ended", nowMillis,
                    Component.translatable("command.sailboatmod.nation.war.ended.timeout_winner", nameOf(attacker), nameOf(defender), war.attackerScore(), war.defenderScore()));
            return;
        }
        if (war.defenderScore() > war.attackerScore()) {
            endWar(data, war, defender.nationId(), "ended", nowMillis,
                    Component.translatable("command.sailboatmod.nation.war.ended.timeout_winner", nameOf(defender), nameOf(attacker), war.defenderScore(), war.attackerScore()));
            return;
        }
        endWar(data, war, "", "ended", nowMillis,
                Component.translatable("command.sailboatmod.nation.war.ended.draw", nameOf(attacker), nameOf(defender), war.attackerScore(), war.defenderScore()));
    }

    private static void endWar(NationSavedData data, NationWarRecord war, String winnerNationId, String captureState, long endedAt, Component message) {
        NationWarRecord endedWar = new NationWarRecord(
                war.warId(),
                war.attackerNationId(),
                war.defenderNationId(),
                "ended",
                war.attackerScore(),
                war.defenderScore(),
                0.0D,
                war.startedAt(),
                war.lastCaptureTick(),
                winnerNationId,
                endedAt,
                captureState
        );
        data.putWar(endedWar);
        broadcast(message);
        notifyWarParticipants(ServerLifecycleHooks.getCurrentServer(), data, war.attackerNationId(), war.defenderNationId(), Component.translatable("toast.sailboatmod.nation.war.title"), message);
        syncWarOverviewParticipants(ServerLifecycleHooks.getCurrentServer(), data, endedWar, endedAt, true);
        WAR_SYNC_CACHE.remove(war.warId());
        broadcast(Component.translatable("command.sailboatmod.nation.war.cooldown_start", formatDurationMinutes(WAR_COOLDOWN_MILLIS)));
    }

    private static void notifyWarParticipants(MinecraftServer server, NationSavedData data, String attackerNationId, String defenderNationId, Component title, Component message) {
        if (server == null || data == null || title == null || message == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NationMemberRecord member = data.getMember(player.getUUID());
            if (member == null) {
                continue;
            }
            String nationId = member.nationId();
            if (nationId.equals(attackerNationId) || nationId.equals(defenderNationId)) {
                NationToastPacket.send(player, title, message);
            }
        }
    }

    private static void purgeExpiredWars(NationSavedData data, long nowMillis) {
        List<String> toRemove = new ArrayList<>();
        for (NationWarRecord war : data.getWars()) {
            if (!war.isEnded() || war.endedAt() <= 0L) {
                continue;
            }
            if (nowMillis - war.endedAt() > ENDED_WAR_RETENTION_MILLIS) {
                toRemove.add(war.warId());
            }
        }
        for (String warId : toRemove) {
            data.removeWar(warId);
            WAR_SYNC_CACHE.remove(warId);
        }
    }

    private static void syncWarOverviewParticipants(MinecraftServer server, NationSavedData data, NationWarRecord war, long nowMillis, boolean force) {
        if (server == null || data == null || war == null) {
            return;
        }
        WarSyncSnapshot nextSnapshot = new WarSyncSnapshot(
                war.state(),
                war.attackerScore(),
                war.defenderScore(),
                (int) Math.round(war.captureProgress()),
                safeStatus(war.captureState()),
                remainingWarSeconds(war, nowMillis),
                warCooldownSeconds(data, war, nowMillis)
        );
        WarSyncSnapshot previous = WAR_SYNC_CACHE.get(war.warId());
        if (!force && nextSnapshot.equals(previous)) {
            return;
        }
        WAR_SYNC_CACHE.put(war.warId(), nextSnapshot);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NationMemberRecord member = data.getMember(player.getUUID());
            if (member == null) {
                continue;
            }
            String nationId = member.nationId();
            if (!nationId.equals(war.attackerNationId()) && !nationId.equals(war.defenderNationId())) {
                continue;
            }
            NationOverviewData overview = NationOverviewService.buildFor(player);
            com.monpai.sailboatmod.network.ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenNationScreenPacket(overview, !force));
        }
    }

    private static int warCooldownSeconds(NationSavedData data, NationWarRecord war, long nowMillis) {
        if (data == null || war == null) {
            return 0;
        }
        long remaining = Math.max(
                cooldownRemainingMillis(data, war.attackerNationId(), nowMillis),
                cooldownRemainingMillis(data, war.defenderNationId(), nowMillis)
        );
        return (int) Math.ceil(remaining / 1000.0D);
    }

    private static String determineCaptureState(int attackerCount, int defenderCount) {
        if (attackerCount > 0 && defenderCount == 0) {
            return "attacking";
        }
        if (attackerCount > 0) {
            return "contested";
        }
        if (defenderCount > 0) {
            return "defending";
        }
        return "idle";
    }

    private static void broadcastWarStateChange(String captureState, NationRecord attacker, NationRecord defender) {
        switch (captureState) {
            case "attacking" -> broadcast(Component.translatable("command.sailboatmod.nation.war.state.attacking", attacker.name(), defender.name()));
            case "contested" -> broadcast(Component.translatable("command.sailboatmod.nation.war.state.contested", attacker.name(), defender.name()));
            case "defending" -> broadcast(Component.translatable("command.sailboatmod.nation.war.state.defending", defender.name()));
            case "idle" -> broadcast(Component.translatable("command.sailboatmod.nation.war.state.idle", defender.name()));
            default -> {
            }
        }
    }

    private static void broadcast(Component message) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    private static String nameOf(NationRecord nation) {
        return nation == null || nation.name().isBlank() ? "-" : nation.name();
    }

    private static String safeStatus(String value) {
        return value == null || value.isBlank() ? "idle" : value;
    }

    private static String formatDurationSeconds(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%d:%02d", minutes, seconds);
    }

    private static String formatDurationMinutes(long millis) {
        long totalMinutes = Math.max(1L, millis / 60000L);
        return totalMinutes + "m";
    }

    private record WarSyncSnapshot(
            String state,
            int attackerScore,
            int defenderScore,
            int captureProgress,
            String captureState,
            int remainingSeconds,
            int cooldownSeconds
    ) {
    }

    private NationWarService() {
    }
}

