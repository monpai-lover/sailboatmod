package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationSavedDataMigrationHookTest {
    @Test
    void loadRunsMigrationForOldSaveWithTownButNoTownMembers() {
        // 构造一个"旧档"：有 town、无 TownMembers 标签、无迁移标记
        NationSavedData seed = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        seed.putTown(new TownRecord("crimea", "", "Crimea", mayor, 5L, "", TownRecord.noCorePos(), "", "european"));
        CompoundTag saved = seed.save(new CompoundTag());
        // 模拟旧档：移除 TownMembers 相关标签与迁移标记
        saved.remove("TownMembers");
        saved.remove("TownMemberRequests");
        saved.remove("townMembersMigrated");

        NationSavedData loaded = NationSavedData.load(saved);

        assertTrue(loaded.isTownMembersMigrated(), "load should trigger migration");
        assertNotNull(loaded.getTownMember("crimea", mayor), "mayor should be backfilled as town member");
    }
}
