package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.block.entity.TownWarehouseBlockEntity;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.wallet.MarketWalletGoldSource;
import com.monpai.sailboatmod.market.wallet.MarketWalletService;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.NationService;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

public class MarketWalletActionPacket {
    public enum Action {
        CASH_TO_WALLET,
        WALLET_TO_CASH,
        WALLET_TO_TREASURY,
        TREASURY_TO_WALLET,
        CLAIM_CREDITS_TO_WALLET
    }

    private final BlockPos marketPos;
    private final Action action;
    private final long amount;

    public MarketWalletActionPacket(BlockPos marketPos, Action action, long amount) {
        this.marketPos = marketPos;
        this.action = action == null ? Action.CASH_TO_WALLET : action;
        this.amount = Math.max(0L, amount);
    }

    public static void encode(MarketWalletActionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        buffer.writeEnum(packet.action);
        buffer.writeLong(packet.amount);
    }

    public static MarketWalletActionPacket decode(FriendlyByteBuf buffer) {
        return new MarketWalletActionPacket(buffer.readBlockPos(), buffer.readEnum(Action.class), buffer.readLong());
    }

    public static void handle(MarketWalletActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            boolean ok = packet.apply(player, market);
            player.sendSystemMessage(Component.translatable(ok
                    ? "screen.sailboatmod.market.wallet.transfer_success"
                    : "screen.sailboatmod.market.wallet.transfer_failed"));
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }

    private boolean apply(ServerPlayer player, MarketBlockEntity market) {
        String playerUuid = player.getUUID().toString();
        String playerName = player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
        return switch (action) {
            case CASH_TO_WALLET -> cashToWallet(player, market, playerUuid, playerName);
            case WALLET_TO_CASH -> walletToCash(player, playerUuid, playerName);
            case WALLET_TO_TREASURY -> walletToTreasury(player, market, playerUuid, playerName);
            case TREASURY_TO_WALLET -> treasuryToWallet(player, market, playerUuid, playerName);
            case CLAIM_CREDITS_TO_WALLET -> claimCreditsToWallet(player, playerUuid, playerName);
        };
    }

    private boolean cashToWallet(ServerPlayer player, MarketBlockEntity market, String playerUuid, String playerName) {
        if (amount <= 0L) {
            return false;
        }
        Boolean withdrawn = GoldStandardEconomy.tryWithdraw(player, amount);
        if (!Boolean.TRUE.equals(withdrawn)
                && !MarketWalletGoldSource.withdrawFromLinkedWarehouse(market, player.getUUID(), amount)) {
            return false;
        }
        MarketWalletService.deposit(player.level(), playerUuid, playerName, amount);
        return true;
    }

    private boolean walletToCash(ServerPlayer player, String playerUuid, String playerName) {
        if (amount <= 0L) {
            return false;
        }
        MarketWalletService.AccountResult withdrawn = MarketWalletService.withdraw(player.level(), playerUuid, playerName, amount);
        if (!withdrawn.success()) {
            return false;
        }
        Boolean deposited = GoldStandardEconomy.tryDeposit(player, amount);
        if (Boolean.TRUE.equals(deposited)) {
            return true;
        }
        // rollback wallet: 实物金给付未成功，把已扣的钱包额精确退回
        MarketWalletService.deposit(player.level(), playerUuid, playerName, amount);
        return false;
    }

    private boolean walletToTreasury(ServerPlayer player, MarketBlockEntity market, String playerUuid, String playerName) {
        if (amount <= 0L) {
            return false;
        }
        String nationId = resolveNationId(player, market);
        if (nationId.isBlank()) {
            return false;
        }
        MarketWalletService.AccountResult withdrawn = MarketWalletService.withdraw(player.level(), playerUuid, playerName, amount);
        if (!withdrawn.success()) {
            return false;
        }
        NationSavedData data = NationSavedData.get(player.level());
        NationTreasuryRecord treasury = data.getOrCreateTreasury(nationId);
        data.putTreasury(treasury.withBalance(saturatedAdd(treasury.currencyBalance(), amount)));
        return true;
    }

    private boolean treasuryToWallet(ServerPlayer player, MarketBlockEntity market, String playerUuid, String playerName) {
        if (amount <= 0L) {
            return false;
        }
        String nationId = resolveNationId(player, market);
        if (nationId.isBlank()) {
            return false;
        }
        UUID uuid = player.getUUID();
        if (!NationService.hasPermission(player.level(), uuid, NationPermission.MANAGE_TREASURY)) {
            return false;
        }
        NationSavedData data = NationSavedData.get(player.level());
        NationTreasuryRecord treasury = data.getOrCreateTreasury(nationId);
        if (treasury.currencyBalance() < amount) {
            return false;
        }
        data.putTreasury(treasury.withBalance(treasury.currencyBalance() - amount));
        MarketWalletService.deposit(player.level(), playerUuid, playerName, amount);
        return true;
    }

    private boolean claimCreditsToWallet(ServerPlayer player, String playerUuid, String playerName) {
        MarketSavedData marketData = MarketSavedData.get(player.level());
        int pending = marketData.clearPendingCredits(playerUuid);
        if (pending <= 0) {
            return false;
        }
        MarketWalletService.deposit(player.level(), playerUuid, playerName, pending);
        return true;
    }

    private static String resolveNationId(ServerPlayer player, MarketBlockEntity market) {
        if (player == null || market == null) {
            return "";
        }
        TownWarehouseBlockEntity warehouse = market.getLinkedWarehouse();
        TownRecord town = null;
        if (warehouse != null) {
            String townId = warehouse.getTownId();
            if (townId != null && !townId.isBlank()) {
                town = NationSavedData.get(player.level()).getTown(townId);
            }
            if (town == null) {
                town = TownService.getTownAt(player.level(), warehouse.getBlockPos());
            }
        }
        if (town == null) {
            town = TownService.getTownAt(player.level(), market.getBlockPos());
        }
        return town == null ? "" : town.nationId();
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
