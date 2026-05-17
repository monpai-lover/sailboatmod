package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NationServiceTest {
    @Test
    void nationLeaderUuidGrantsAllPermissionsEvenWhenMemberOfficeIsStale() throws ReflectiveOperationException {
        NationSavedData data = new NationSavedData();
        UUID leaderUuid = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha Nation", "ALP", 0x123456, 0x654321,
                leaderUuid, 1L, "", "", NationRecord.noCorePos(), ""));
        NationMemberRecord staleMember = new NationMemberRecord(leaderUuid, "Leader", "alpha", "member", 1L);

        assertTrue(hasPermissionForTest(data, staleMember, NationPermission.MANAGE_TREASURY));
    }

    private static boolean hasPermissionForTest(NationSavedData data,
                                                NationMemberRecord member,
                                                NationPermission permission) throws ReflectiveOperationException {
        Method method = NationService.class.getDeclaredMethod(
                "hasPermission",
                NationSavedData.class,
                NationMemberRecord.class,
                NationPermission.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(null, data, member, permission);
    }
}
