package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.List;

public final class TownMemberMigration {
    private static final Logger LOGGER = LogUtils.getLogger();

    private TownMemberMigration() {
    }

    public static void runOnce(NationSavedData data) {
        if (data == null || data.isTownMembersMigrated()) {
            return;
        }

        // 1. 镇长补录
        for (TownRecord town : data.getAllTowns()) {
            if (town.mayorUuid() == null) {
                continue;
            }
            if (data.getTownMember(town.townId(), town.mayorUuid()) == null) {
                data.putTownMember(new TownMemberRecord(
                        town.mayorUuid(), town.townId(), TownMemberRecord.OFFICE_MEMBER, town.createdAt()));
            }
        }

        // 2. nation 孤儿归并
        for (NationMemberRecord member : data.getAllMembers()) {
            if (!data.getTownsForPlayer(member.playerUuid()).isEmpty()) {
                continue;  // 已有 town（含上一步补的镇长）
            }
            NationRecord nation = data.getNation(member.nationId());
            if (nation == null) {
                continue;
            }
            TownRecord target = resolveCapitalTown(data, nation);
            if (target == null) {
                LOGGER.warn("Nation {} has member {} but no town; leaving nation membership as-is",
                        nation.nationId(), member.playerUuid());
                continue;
            }
            data.putTownMember(new TownMemberRecord(
                    member.playerUuid(), target.townId(), TownMemberRecord.OFFICE_MEMBER, member.joinedAt()));
        }

        data.setTownMembersMigrated(true);
    }

    private static TownRecord resolveCapitalTown(NationSavedData data, NationRecord nation) {
        if (!nation.capitalTownId().isBlank()) {
            TownRecord capital = data.getTown(nation.capitalTownId());
            if (capital != null) {
                return capital;
            }
        }
        List<TownRecord> towns = data.getTownsForNation(nation.nationId());
        return towns.isEmpty() ? null : towns.get(0);
    }
}
