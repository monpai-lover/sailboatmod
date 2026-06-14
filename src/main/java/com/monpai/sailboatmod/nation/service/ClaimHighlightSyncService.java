package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.integration.xaero.SailboatClaimHighlightEntry;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SyncClaimHighlightsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public final class ClaimHighlightSyncService {
    private static final int FALLBACK_PRIMARY_COLOR = 0x8A8A8A;
    private static final int FALLBACK_SECONDARY_COLOR = 0xF2C14E;

    public static void syncTo(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return;
        }
        SyncClaimHighlightsPacket packet = buildPacket(player.getServer());
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void syncAll(MinecraftServer server) {
        if (server == null || server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        SyncClaimHighlightsPacket packet = buildPacket(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    public static SyncClaimHighlightsPacket buildPacket(MinecraftServer server) {
        if (server == null) {
            return new SyncClaimHighlightsPacket(List.of());
        }
        NationSavedData data = NationSavedData.get(server.overworld());
        List<SailboatClaimHighlightEntry> entries = new ArrayList<>();
        for (NationClaimRecord claim : data.getAllClaims()) {
            TownRecord town = data.getTown(claim.townId());
            NationRecord claimNation = data.getNation(claim.nationId());
            NationRecord townNation = town == null || town.nationId().isBlank() ? null : data.getNation(town.nationId());
            entries.add(entryFor(claim, claimNation, town, townNation));
        }
        return new SyncClaimHighlightsPacket(entries);
    }

    static SailboatClaimHighlightEntry entryForTest(NationClaimRecord claim,
                                                    NationRecord claimNation,
                                                    TownRecord town,
                                                    NationRecord townNation) {
        return entryFor(claim, claimNation, town, townNation);
    }

    private static SailboatClaimHighlightEntry entryFor(NationClaimRecord claim,
                                                        NationRecord claimNation,
                                                        TownRecord town,
                                                        NationRecord townNation) {
        NationRecord ownerNation = townNation != null ? townNation : claimNation;
        String nationId = ownerNation == null
                ? town != null && !town.nationId().isBlank() ? town.nationId() : claim.nationId()
                : ownerNation.nationId();
        String nationName = ownerNation == null ? nationId : ownerNation.name();
        return new SailboatClaimHighlightEntry(
                claim.dimensionId(),
                claim.chunkX(),
                claim.chunkZ(),
                nationId,
                nationName,
                claim.townId(),
                town == null ? claim.townId() : town.name(),
                ownerNation == null ? FALLBACK_PRIMARY_COLOR : ownerNation.primaryColorRgb(),
                ownerNation == null ? FALLBACK_SECONDARY_COLOR : ownerNation.secondaryColorRgb()
        );
    }

    private ClaimHighlightSyncService() {
    }
}
