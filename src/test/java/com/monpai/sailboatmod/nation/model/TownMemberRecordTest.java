package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TownMemberRecordTest {
    @Test
    void saveLoadRoundTrip() {
        UUID player = UUID.randomUUID();
        TownMemberRecord record = new TownMemberRecord(player, "Crimea", TownMemberRecord.OFFICE_MEMBER, 123L);

        TownMemberRecord loaded = TownMemberRecord.load(record.save());

        assertEquals(player, loaded.playerUuid());
        assertEquals("crimea", loaded.townId());
        assertEquals("member", loaded.officeId());
        assertEquals(123L, loaded.joinedAt());
    }

    @Test
    void loadOldTagWithMissingFieldsDefaultsBlank() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlayerUuid", new UUID(0L, 0L));

        TownMemberRecord loaded = TownMemberRecord.load(tag);

        assertEquals("", loaded.townId());
        assertEquals("", loaded.officeId());
        assertEquals(0L, loaded.joinedAt());
    }
}
