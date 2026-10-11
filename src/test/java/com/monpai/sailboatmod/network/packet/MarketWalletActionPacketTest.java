package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.block.entity.TownWarehouseBlockEntity;
import com.monpai.sailboatmod.market.wallet.MarketWalletService;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWalletActionPacketTest {
    @TempDir Path dataDirectory;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void treasuryManagerCannotWithdrawAnotherNationsTreasury() throws ReflectiveOperationException {
        var player = PacketServerTestSupport.player(dataDirectory, "alpha");
        PacketServerTestSupport.town(player, "beta", BlockPos.ZERO);
        NationSavedData data = NationSavedData.get(player.level());
        data.putTreasury(data.getOrCreateTreasury("beta").withBalance(100L));

        assertFalse(withdrawToWallet(player, 60L));

        assertEquals(100L, data.getOrCreateTreasury("beta").currencyBalance());
        assertEquals(0L, MarketWalletService.availableBalance(player.level(), player.getUUID().toString(), "Treasurer"));
    }

    @Test
    void treasuryManagerCanWithdrawOwnNationsTreasury() throws ReflectiveOperationException {
        var player = PacketServerTestSupport.player(dataDirectory, "alpha");
        PacketServerTestSupport.town(player, "alpha", BlockPos.ZERO);
        NationSavedData data = NationSavedData.get(player.level());
        data.putTreasury(data.getOrCreateTreasury("alpha").withBalance(100L));

        assertTrue(withdrawToWallet(player, 60L));

        assertEquals(40L, data.getOrCreateTreasury("alpha").currencyBalance());
        assertEquals(60L, MarketWalletService.availableBalance(player.level(), player.getUUID().toString(), "Treasurer"));
    }

    private static boolean withdrawToWallet(ServerPlayer player, long amount) throws ReflectiveOperationException {
        var market = PacketServerTestSupport.allocate(TestMarket.class);
        var packet = new MarketWalletActionPacket(BlockPos.ZERO, MarketWalletActionPacket.Action.TREASURY_TO_WALLET, amount);
        var method = MarketWalletActionPacket.class.getDeclaredMethod("treasuryToWallet",
                ServerPlayer.class, MarketBlockEntity.class, String.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(packet, player, market, player.getUUID().toString(), "Treasurer");
    }

    private static final class TestMarket extends MarketBlockEntity {
        private TestMarket() { super(BlockPos.ZERO, Blocks.AIR.defaultBlockState()); }
        @Override public BlockPos getBlockPos() { return BlockPos.ZERO; }
        @Override public TownWarehouseBlockEntity getLinkedWarehouse() { return null; }
    }
}
