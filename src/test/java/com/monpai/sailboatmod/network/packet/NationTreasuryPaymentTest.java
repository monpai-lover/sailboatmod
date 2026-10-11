package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.service.NationTreasuryService;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NationTreasuryPaymentTest {
    @TempDir Path dataDirectory;

    @BeforeAll static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void rejectedCurrencyPayoutLeavesTreasuryBalanceUnchanged() throws Exception {
        var player = PacketServerTestSupport.player(dataDirectory, "alpha");
        var data = NationSavedData.get(player.level());
        long amount = (long) Integer.MAX_VALUE + 1;
        data.putTreasury(NationTreasuryRecord.empty("alpha").withBalance(amount));

        assertFalse(NationTreasuryService.withdrawCurrency(player, amount).success());
        assertEquals(amount, data.getOrCreateTreasury("alpha").currencyBalance());
    }
}
