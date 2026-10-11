package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimAccessLevel;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NationPermissionServiceTest {
    @Test
    void nationLeaderRetainsLeaderLandRoleWhenOfficeIsStale() throws ReflectiveOperationException {
        NationSavedData data = new NationSavedData();
        UUID leader = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha", "ALP", 1, 2,
                leader, 1L, "", "", NationRecord.noCorePos(), ""));
        NationMemberRecord member = new NationMemberRecord(leader, "Leader", "alpha", "member", 1L);
        NationOfficeRecord office = new NationOfficeRecord("member", "Member", 20, Set.of());

        assertEquals(NationPermissionService.ChunkRole.LEADER, resolveRole(data, member, office));
    }

    @Test
    void ordinaryNationMemberKeepsMemberLandRole() throws ReflectiveOperationException {
        NationSavedData data = new NationSavedData();
        data.putNation(new NationRecord("alpha", "Alpha", "ALP", 1, 2,
                UUID.randomUUID(), 1L, "", "", NationRecord.noCorePos(), ""));
        NationMemberRecord member = new NationMemberRecord(UUID.randomUUID(), "Member", "alpha", "member", 1L);
        NationOfficeRecord office = new NationOfficeRecord("member", "Member", 20, Set.of());

        assertEquals(NationPermissionService.ChunkRole.MEMBER, resolveRole(data, member, office));
    }

    private static NationPermissionService.ChunkRole resolveRole(NationSavedData data,
                                                                NationMemberRecord member,
                                                                NationOfficeRecord office) throws ReflectiveOperationException {
        String access = NationClaimAccessLevel.LEADER.id();
        NationClaimRecord claim = new NationClaimRecord("minecraft:overworld", 0, 0,
                "alpha", "", access, access, access, access, access, access, access, 1L);
        Method method = NationPermissionService.class.getDeclaredMethod("resolveRole", NationSavedData.class,
                UUID.class, NationMemberRecord.class, NationOfficeRecord.class, NationClaimRecord.class);
        method.setAccessible(true);
        return (NationPermissionService.ChunkRole) method.invoke(null, data, member.playerUuid(), member, office, claim);
    }
}
