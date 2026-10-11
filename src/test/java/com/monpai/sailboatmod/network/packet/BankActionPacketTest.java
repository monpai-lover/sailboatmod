package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankActionPacketTest {
    @TempDir Path dataDirectory;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void fullInventoryKeepsAllRequestedItemsInTreasury() throws ReflectiveOperationException {
        assertConservedWithdrawal(0, 129, 0, 129);
    }

    @Test
    void fullSecondStackDoesNotRestoreItemsAlreadyGivenToPlayer() throws ReflectiveOperationException {
        assertConservedWithdrawal(64, 65, 64, 1);
    }

    @Test
    void partiallyFilledStackRestoresOnlyItsUninsertedRemainder() throws ReflectiveOperationException {
        assertConservedWithdrawal(1, 10, 1, 9);
    }

    @Test
    void failedCurrencyPaymentKeepsTreasuryBalance() throws ReflectiveOperationException {
        assertRejectedCashWithdrawal(BankActionPacket.Action.WITHDRAW_CURRENCY);
    }

    @Test
    void failedGoldPaymentKeepsTreasuryBalance() throws ReflectiveOperationException {
        assertRejectedCashWithdrawal(BankActionPacket.Action.WITHDRAW_AS_GOLD);
    }

    private void assertRejectedCashWithdrawal(BankActionPacket.Action action) throws ReflectiveOperationException {
        var player = PacketServerTestSupport.player(dataDirectory, "alpha");
        PacketServerTestSupport.town(player, "alpha", BlockPos.ZERO);
        long amount = (long) Integer.MAX_VALUE + 1L;
        NationSavedData data = NationSavedData.get(player.level());
        data.putTreasury(NationTreasuryRecord.empty("alpha").withBalance(amount));
        player.onSystemMessage = message -> assertEquals(amount,
                data.getOrCreateTreasury("alpha").currencyBalance(),
                "failed payment must retain the treasury balance before replying");

        var context = PacketServerTestSupport.context(player);
        BankActionPacket.handle(new BankActionPacket(action, BlockPos.ZERO, amount, 0), () -> context);

        assertEquals(amount, data.getOrCreateTreasury("alpha").currencyBalance());
    }

    private void assertConservedWithdrawal(int availableSpace, int requested, int given, int returned)
            throws ReflectiveOperationException {
        var player = PacketServerTestSupport.player(dataDirectory, "alpha");
        BlockPos bank = BlockPos.ZERO;
        PacketServerTestSupport.town(player, "alpha", bank);
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            player.getInventory().items.set(i, new ItemStack(Items.STONE, 64));
        }
        player.getInventory().items.set(0, availableSpace == 64 ? ItemStack.EMPTY
                : availableSpace > 0 ? new ItemStack(Items.DIAMOND, 64 - availableSpace)
                : new ItemStack(Items.STONE, 64));
        int startingDiamonds = availableSpace > 0 && availableSpace < 64 ? 64 - availableSpace : 0;
        NationSavedData data = NationSavedData.get(player.level());
        NationTreasuryRecord treasury = NationTreasuryRecord.empty("alpha");
        treasury.addItem(new ItemStack(Items.DIAMOND, requested), "Depositor");
        data.putTreasury(treasury);
        data.setDirty(false);

        var context = PacketServerTestSupport.context(player);
        BankActionPacket.handle(new BankActionPacket(BankActionPacket.Action.WITHDRAW_ITEM,
                bank, requested, 0, new ItemStack(Items.DIAMOND)), () -> context);

        assertEquals(startingDiamonds + given, player.getInventory().countItem(Items.DIAMOND));
        assertEquals(returned, data.getOrCreateTreasury("alpha").totalItemUnits());
        assertEquals(startingDiamonds + requested,
                player.getInventory().countItem(Items.DIAMOND) + data.getOrCreateTreasury("alpha").totalItemUnits());
        assertTrue(data.isDirty(), "restored remainder must be saved even when inventory is full");
    }
}
