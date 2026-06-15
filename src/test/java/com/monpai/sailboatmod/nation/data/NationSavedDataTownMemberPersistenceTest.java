package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.TownMemberInviteRecord;
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationSavedDataTownMemberPersistenceTest {
    @Test
    void townMembersAndInvitesSurviveSaveLoad() {
        NationSavedData data = new NationSavedData();
        UUID player = UUID.randomUUID();
        data.putTownMember(new TownMemberRecord(player, "crimea", TownMemberRecord.OFFICE_MEMBER, 7L));
        data.putTownMemberInvite(new TownMemberInviteRecord(
                "crimea", player, TownMemberInviteRecord.DIRECTION_APPLY, player, 9L));
        data.markTownMembersMigratedForTest();

        NationSavedData loaded = NationSavedData.load(data.save(new CompoundTag()));

        assertNotNull(loaded.getTownMember("crimea", player));
        assertEquals(7L, loaded.getTownMember("crimea", player).joinedAt());
        assertNotNull(loaded.getTownMemberInvite("crimea", player));
        assertTrue(loaded.getTownMemberInvite("crimea", player).isApply());
        assertTrue(loaded.isTownMembersMigratedForTest());
    }

    @Test
    void oldSaveWithoutTownMemberTagsLoadsEmptyThenMigrates() {
        NationSavedData loaded = NationSavedData.load(new CompoundTag());

        // 空档无 town/成员可补，迁移仍跑过一次并置标记，townMembers 保持空
        assertTrue(loaded.getTownMembersForTown("crimea").isEmpty());
        assertTrue(loaded.isTownMembersMigratedForTest());
    }
}
