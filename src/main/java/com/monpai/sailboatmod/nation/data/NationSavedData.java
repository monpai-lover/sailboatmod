package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRequestRecord;
import com.monpai.sailboatmod.nation.model.NationFlagRecord;
import com.monpai.sailboatmod.nation.model.NationInviteRecord;
import com.monpai.sailboatmod.nation.model.NationJoinRequestRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.model.TownNationRequestRecord;
import com.monpai.sailboatmod.nation.model.NationWarRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class NationSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_nations";

    private final Map<String, NationRecord> nations = new LinkedHashMap<>();
    private final Map<String, TownRecord> towns = new LinkedHashMap<>();
    private final Map<UUID, NationMemberRecord> members = new LinkedHashMap<>();
    private final Map<String, NationOfficeRecord> offices = new LinkedHashMap<>();
    private final Map<String, NationInviteRecord> invites = new LinkedHashMap<>();
    private final Map<String, NationJoinRequestRecord> joinRequests = new LinkedHashMap<>();
    private final Map<String, NationClaimRecord> claims = new LinkedHashMap<>();
    private final Map<String, NationWarRecord> wars = new LinkedHashMap<>();
    private final Map<String, NationFlagRecord> flags = new LinkedHashMap<>();
    private final Map<String, NationDiplomacyRecord> diplomacy = new LinkedHashMap<>();
    private final Map<String, NationDiplomacyRequestRecord> diplomacyRequests = new LinkedHashMap<>();
    private final Map<String, TownNationRequestRecord> townNationRequests = new LinkedHashMap<>();
    private final Map<String, NationTreasuryRecord> treasuries = new LinkedHashMap<>();
    private final Map<String, com.monpai.sailboatmod.nation.model.PeaceProposalRecord> peaceProposals = new LinkedHashMap<>();
    private final Map<String, com.monpai.sailboatmod.nation.model.PlacedStructureRecord> placedStructures = new LinkedHashMap<>();
    private final Map<String, com.monpai.sailboatmod.nation.model.RoadNetworkRecord> roadNetworks = new LinkedHashMap<>();
    private final Map<String, com.monpai.sailboatmod.nation.model.TradeProposalRecord> tradeProposals = new LinkedHashMap<>();

    public static NationSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new NationSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(NationSavedData::load, NationSavedData::new, DATA_NAME);
    }

    public static NationSavedData load(CompoundTag tag) {
        NationSavedData data = new NationSavedData();

        ListTag nationTag = tag.getList("Nations", Tag.TAG_COMPOUND);
        for (Tag raw : nationTag) {
            if (raw instanceof CompoundTag compound) {
                NationRecord nation = NationRecord.load(compound);
                if (!nation.nationId().isBlank()) {
                    data.nations.put(nation.nationId(), nation);
                }
            }
        }

        ListTag townTag = tag.getList("Towns", Tag.TAG_COMPOUND);
        for (Tag raw : townTag) {
            if (raw instanceof CompoundTag compound) {
                TownRecord town = TownRecord.load(compound);
                if (!town.townId().isBlank()) {
                    data.towns.put(town.townId(), town);
                }
            }
        }

        ListTag memberTag = tag.getList("Members", Tag.TAG_COMPOUND);
        for (Tag raw : memberTag) {
            if (raw instanceof CompoundTag compound) {
                NationMemberRecord member = NationMemberRecord.load(compound);
                data.members.put(member.playerUuid(), member);
            }
        }

        ListTag officeTag = tag.getList("Offices", Tag.TAG_COMPOUND);
        for (Tag raw : officeTag) {
            if (raw instanceof CompoundTag compound) {
                String nationId = compound.getString("NationId").trim().toLowerCase(Locale.ROOT);
                NationOfficeRecord office = NationOfficeRecord.load(compound.getCompound("Office"));
                if (!nationId.isBlank() && !office.officeId().isBlank()) {
                    data.offices.put(officeKey(nationId, office.officeId()), office);
                }
            }
        }

        ListTag inviteTag = tag.getList("Invites", Tag.TAG_COMPOUND);
        for (Tag raw : inviteTag) {
            if (raw instanceof CompoundTag compound) {
                NationInviteRecord invite = NationInviteRecord.load(compound);
                data.invites.put(inviteKey(invite.nationId(), invite.playerUuid()), invite);
            }
        }

        ListTag joinRequestTag = tag.getList("JoinRequests", Tag.TAG_COMPOUND);
        for (Tag raw : joinRequestTag) {
            if (raw instanceof CompoundTag compound) {
                NationJoinRequestRecord request = NationJoinRequestRecord.load(compound);
                data.joinRequests.put(joinRequestKey(request.nationId(), request.applicantUuid()), request);
            }
        }

        ListTag claimTag = tag.getList("Claims", Tag.TAG_COMPOUND);
        for (Tag raw : claimTag) {
            if (raw instanceof CompoundTag compound) {
                NationClaimRecord claim = NationClaimRecord.load(compound);
                String key = claimKey(claim.dimensionId(), claim.chunkX(), claim.chunkZ());
                if (!key.isBlank()) {
                    data.claims.put(key, claim);
                }
            }
        }

        ListTag warTag = tag.getList("Wars", Tag.TAG_COMPOUND);
        for (Tag raw : warTag) {
            if (raw instanceof CompoundTag compound) {
                NationWarRecord war = NationWarRecord.load(compound);
                if (!war.warId().isBlank()) {
                    data.wars.put(war.warId(), war);
                }
            }
        }

        ListTag proposalLoadTag = tag.getList("PeaceProposals", Tag.TAG_COMPOUND);
        for (Tag raw : proposalLoadTag) {
            if (raw instanceof CompoundTag compound) {
                com.monpai.sailboatmod.nation.model.PeaceProposalRecord proposal = com.monpai.sailboatmod.nation.model.PeaceProposalRecord.load(compound);
                if (!proposal.warId().isBlank()) {
                    data.peaceProposals.put(proposal.warId(), proposal);
                }
            }
        }

        ListTag structuresLoadTag = tag.getList("PlacedStructures", Tag.TAG_COMPOUND);
        for (Tag raw : structuresLoadTag) {
            if (raw instanceof CompoundTag compound) {
                com.monpai.sailboatmod.nation.model.PlacedStructureRecord s = com.monpai.sailboatmod.nation.model.PlacedStructureRecord.load(compound);
                if (!s.structureId().isBlank()) {
                    data.placedStructures.put(s.structureId(), s);
                }
            }
        }

        ListTag roadsLoadTag = tag.getList("RoadNetworks", Tag.TAG_COMPOUND);
        for (Tag raw : roadsLoadTag) {
            if (raw instanceof CompoundTag compound) {
                com.monpai.sailboatmod.nation.model.RoadNetworkRecord road = com.monpai.sailboatmod.nation.model.RoadNetworkRecord.load(compound);
                if (!road.roadId().isBlank()) {
                    data.roadNetworks.put(road.roadId(), road);
                }
            }
        }

        ListTag tradeLoadTag = tag.getList("TradeProposals", Tag.TAG_COMPOUND);
        for (Tag raw : tradeLoadTag) {
            if (raw instanceof CompoundTag compound) {
                com.monpai.sailboatmod.nation.model.TradeProposalRecord t = com.monpai.sailboatmod.nation.model.TradeProposalRecord.load(compound);
                if (!t.proposalId().isBlank()) {
                    data.tradeProposals.put(t.proposalId(), t);
                }
            }
        }

        ListTag flagTag = tag.getList("Flags", Tag.TAG_COMPOUND);
        for (Tag raw : flagTag) {
            if (raw instanceof CompoundTag compound) {
                NationFlagRecord flag = NationFlagRecord.load(compound);
                if (!flag.flagId().isBlank()) {
                    data.flags.put(flag.flagId(), flag);
                }
            }
        }

        ListTag diplomacyTag = tag.getList("Diplomacy", Tag.TAG_COMPOUND);
        for (Tag raw : diplomacyTag) {
            if (raw instanceof CompoundTag compound) {
                NationDiplomacyRecord record = NationDiplomacyRecord.load(compound);
                String key = diplomacyKey(record.nationAId(), record.nationBId());
                if (!key.isBlank()) {
                    data.diplomacy.put(key, record);
                }
            }
        }

        ListTag diplomacyRequestTag = tag.getList("DiplomacyRequests", Tag.TAG_COMPOUND);
        for (Tag raw : diplomacyRequestTag) {
            if (raw instanceof CompoundTag compound) {
                NationDiplomacyRequestRecord request = NationDiplomacyRequestRecord.load(compound);
                String key = diplomacyRequestKey(request.fromNationId(), request.toNationId(), request.statusId());
                if (!key.isBlank()) {
                    data.diplomacyRequests.put(key, request);
                }
            }
        }

        ListTag townNationRequestTag = tag.getList("TownNationRequests", Tag.TAG_COMPOUND);
        for (Tag raw : townNationRequestTag) {
            if (raw instanceof CompoundTag compound) {
                TownNationRequestRecord request = TownNationRequestRecord.load(compound);
                String key = townNationRequestKey(request.townId(), request.nationId());
                if (!key.isBlank()) {
                    data.townNationRequests.put(key, request);
                }
            }
        }

        ListTag treasuryTag = tag.getList("Treasuries", Tag.TAG_COMPOUND);
        for (Tag raw : treasuryTag) {
            if (raw instanceof CompoundTag compound) {
                NationTreasuryRecord treasury = NationTreasuryRecord.load(compound);
                if (!treasury.nationId().isBlank()) {
                    data.treasuries.put(treasury.nationId(), treasury);
                }
            }
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag nationTag = new ListTag();
        for (NationRecord nation : nations.values()) {
            nationTag.add(nation.save());
        }
        tag.put("Nations", nationTag);

        ListTag townTag = new ListTag();
        for (TownRecord town : towns.values()) {
            townTag.add(town.save());
        }
        tag.put("Towns", townTag);

        ListTag memberTag = new ListTag();
        for (NationMemberRecord member : members.values()) {
            memberTag.add(member.save());
        }
        tag.put("Members", memberTag);

        ListTag officeTag = new ListTag();
        for (Map.Entry<String, NationOfficeRecord> entry : offices.entrySet()) {
            String nationId = entry.getKey().split("\\|", 2)[0];
            CompoundTag officeEntry = new CompoundTag();
            officeEntry.putString("NationId", nationId);
            officeEntry.put("Office", entry.getValue().save());
            officeTag.add(officeEntry);
        }
        tag.put("Offices", officeTag);

        ListTag inviteTag = new ListTag();
        for (NationInviteRecord invite : invites.values()) {
            inviteTag.add(invite.save());
        }
        tag.put("Invites", inviteTag);

        ListTag joinRequestTag = new ListTag();
        for (NationJoinRequestRecord request : joinRequests.values()) {
            joinRequestTag.add(request.save());
        }
        tag.put("JoinRequests", joinRequestTag);

        ListTag claimTag = new ListTag();
        for (NationClaimRecord claim : claims.values()) {
            claimTag.add(claim.save());
        }
        tag.put("Claims", claimTag);

        ListTag warTag = new ListTag();
        for (NationWarRecord war : wars.values()) {
            warTag.add(war.save());
        }
        tag.put("Wars", warTag);

        ListTag proposalTag = new ListTag();
        for (com.monpai.sailboatmod.nation.model.PeaceProposalRecord proposal : peaceProposals.values()) {
            proposalTag.add(proposal.save());
        }
        tag.put("PeaceProposals", proposalTag);

        ListTag structuresTag = new ListTag();
        for (com.monpai.sailboatmod.nation.model.PlacedStructureRecord s : placedStructures.values()) {
            structuresTag.add(s.save());
        }
        tag.put("PlacedStructures", structuresTag);

        ListTag roadsTag = new ListTag();
        for (com.monpai.sailboatmod.nation.model.RoadNetworkRecord road : roadNetworks.values()) {
            roadsTag.add(road.save());
        }
        tag.put("RoadNetworks", roadsTag);

        ListTag tradeTag = new ListTag();
        for (com.monpai.sailboatmod.nation.model.TradeProposalRecord t : tradeProposals.values()) {
            tradeTag.add(t.save());
        }
        tag.put("TradeProposals", tradeTag);

        ListTag flagTag = new ListTag();
        for (NationFlagRecord flag : flags.values()) {
            flagTag.add(flag.save());
        }
        tag.put("Flags", flagTag);

        ListTag diplomacyTag = new ListTag();
        for (NationDiplomacyRecord record : diplomacy.values()) {
            diplomacyTag.add(record.save());
        }
        tag.put("Diplomacy", diplomacyTag);

        ListTag diplomacyRequestTag = new ListTag();
        for (NationDiplomacyRequestRecord request : diplomacyRequests.values()) {
            diplomacyRequestTag.add(request.save());
        }
        tag.put("DiplomacyRequests", diplomacyRequestTag);

        ListTag townNationRequestTag = new ListTag();
        for (TownNationRequestRecord request : townNationRequests.values()) {
            townNationRequestTag.add(request.save());
        }
        tag.put("TownNationRequests", townNationRequestTag);

        ListTag treasuryTag = new ListTag();
        for (NationTreasuryRecord treasury : treasuries.values()) {
            treasuryTag.add(treasury.save());
        }
        tag.put("Treasuries", treasuryTag);
        return tag;
    }

    public Collection<NationRecord> getNations() {
        return List.copyOf(nations.values());
    }

    public NationRecord getNation(String nationId) {
        if (nationId == null || nationId.isBlank()) {
            return null;
        }
        return nations.get(normalizeId(nationId));
    }

    public NationRecord findNationByName(String nationName) {
        if (nationName == null || nationName.isBlank()) {
            return null;
        }
        String normalized = nationName.trim();
        for (NationRecord nation : nations.values()) {
            if (nation.name().equalsIgnoreCase(normalized) || nation.shortName().equalsIgnoreCase(normalized)) {
                return nation;
            }
        }
        return null;
    }

    public void putNation(NationRecord nation) {
        nations.put(nation.nationId(), nation);
        setDirty();
    }

    public void removeNation(String nationId) {
        String normalized = normalizeId(nationId);
        if (normalized.isBlank()) {
            return;
        }
        boolean changed = nations.remove(normalized) != null;

        List<String> townIds = new ArrayList<>();
        for (TownRecord town : towns.values()) {
            if (normalized.equals(town.nationId())) {
                townIds.add(town.townId());
            }
        }
        for (String townId : townIds) {
            towns.remove(townId);
            changed = true;
        }

        List<UUID> memberIds = new ArrayList<>();
        for (NationMemberRecord member : members.values()) {
            if (normalized.equals(member.nationId())) {
                memberIds.add(member.playerUuid());
            }
        }
        for (UUID memberId : memberIds) {
            members.remove(memberId);
            changed = true;
        }

        List<String> officeKeys = new ArrayList<>();
        for (String key : offices.keySet()) {
            if (key.startsWith(normalized + "|")) {
                officeKeys.add(key);
            }
        }
        for (String key : officeKeys) {
            offices.remove(key);
            changed = true;
        }

        List<String> inviteKeys = new ArrayList<>();
        for (String key : invites.keySet()) {
            if (key.startsWith(normalized + "|")) {
                inviteKeys.add(key);
            }
        }
        for (String key : inviteKeys) {
            invites.remove(key);
            changed = true;
        }

        List<String> joinRequestKeys = new ArrayList<>();
        for (String key : joinRequests.keySet()) {
            if (key.startsWith(normalized + "|")) {
                joinRequestKeys.add(key);
            }
        }
        for (String key : joinRequestKeys) {
            joinRequests.remove(key);
            changed = true;
        }

        List<String> claimKeys = new ArrayList<>();
        for (Map.Entry<String, NationClaimRecord> entry : claims.entrySet()) {
            if (normalized.equals(entry.getValue().nationId())) {
                claimKeys.add(entry.getKey());
            }
        }
        for (String key : claimKeys) {
            claims.remove(key);
            changed = true;
        }

        List<String> warIds = new ArrayList<>();
        for (NationWarRecord war : wars.values()) {
            if (normalized.equals(war.attackerNationId()) || normalized.equals(war.defenderNationId())) {
                warIds.add(war.warId());
            }
        }
        for (String warId : warIds) {
            wars.remove(warId);
            changed = true;
        }

        List<String> flagIds = new ArrayList<>();
        for (NationFlagRecord flag : flags.values()) {
            if (normalized.equals(flag.nationId())) {
                flagIds.add(flag.flagId());
            }
        }
        for (String flagId : flagIds) {
            flags.remove(flagId);
            changed = true;
        }

        List<String> diplomacyKeys = new ArrayList<>();
        for (Map.Entry<String, NationDiplomacyRecord> entry : diplomacy.entrySet()) {
            if (entry.getValue().includes(normalized)) {
                diplomacyKeys.add(entry.getKey());
            }
        }
        for (String key : diplomacyKeys) {
            diplomacy.remove(key);
            changed = true;
        }

        List<String> diplomacyRequestKeys = new ArrayList<>();
        for (Map.Entry<String, NationDiplomacyRequestRecord> entry : diplomacyRequests.entrySet()) {
            NationDiplomacyRequestRecord request = entry.getValue();
            if (normalized.equals(request.fromNationId()) || normalized.equals(request.toNationId())) {
                diplomacyRequestKeys.add(entry.getKey());
            }
        }
        for (String key : diplomacyRequestKeys) {
            diplomacyRequests.remove(key);
            changed = true;
        }

        List<String> townNationRequestKeys = new ArrayList<>();
        for (Map.Entry<String, TownNationRequestRecord> entry : townNationRequests.entrySet()) {
            if (normalized.equals(entry.getValue().nationId())) {
                townNationRequestKeys.add(entry.getKey());
            }
        }
        for (String key : townNationRequestKeys) {
            townNationRequests.remove(key);
            changed = true;
        }

        if (treasuries.remove(normalized) != null) {
            changed = true;
        }

        if (changed) {
            setDirty();
        }
    }

    public List<TownRecord> getTowns() {
        return List.copyOf(towns.values());
    }

    public TownRecord getTown(String townId) {
        if (townId == null || townId.isBlank()) {
            return null;
        }
        return towns.get(normalizeId(townId));
    }

    public void putTown(TownRecord town) {
        if (town == null || town.townId().isBlank()) {
            return;
        }
        towns.put(town.townId(), town);
        setDirty();
    }

    public void removeTown(String townId) {
        String normalized = normalizeId(townId);
        if (normalized.isBlank()) {
            return;
        }
        boolean changed = towns.remove(normalized) != null;
        List<String> claimKeys = new ArrayList<>();
        for (Map.Entry<String, NationClaimRecord> entry : claims.entrySet()) {
            if (normalized.equals(entry.getValue().townId())) {
                claimKeys.add(entry.getKey());
            }
        }
        for (String key : claimKeys) {
            claims.remove(key);
            changed = true;
        }
        List<String> requestKeys = new ArrayList<>();
        for (Map.Entry<String, TownNationRequestRecord> entry : townNationRequests.entrySet()) {
            if (normalized.equals(entry.getValue().townId())) {
                requestKeys.add(entry.getKey());
            }
        }
        for (String key : requestKeys) {
            townNationRequests.remove(key);
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public List<TownRecord> getTownsForNation(String nationId) {
        String normalized = normalizeId(nationId);
        List<TownRecord> result = new ArrayList<>();
        for (TownRecord town : towns.values()) {
            if (normalized.equals(town.nationId())) {
                result.add(town);
            }
        }
        return result;
    }

    public List<TownRecord> getTownsForMayor(UUID mayorUuid) {
        List<TownRecord> result = new ArrayList<>();
        if (mayorUuid == null) {
            return result;
        }
        for (TownRecord town : towns.values()) {
            if (mayorUuid.equals(town.mayorUuid())) {
                result.add(town);
            }
        }
        return result;
    }

    public TownRecord findTownByName(String rawTownName) {
        if (rawTownName == null || rawTownName.isBlank()) {
            return null;
        }
        String normalized = rawTownName.trim();
        for (TownRecord town : towns.values()) {
            if (town.name().equalsIgnoreCase(normalized)) {
                return town;
            }
        }
        return null;
    }

    public NationMemberRecord getMember(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        return members.get(playerUuid);
    }

    public void putMember(NationMemberRecord member) {
        members.put(member.playerUuid(), member);
        setDirty();
    }

    public void removeMember(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        if (members.remove(playerUuid) != null) {
            setDirty();
        }
    }

    public List<NationMemberRecord> getMembersForNation(String nationId) {
        String normalized = normalizeId(nationId);
        List<NationMemberRecord> result = new ArrayList<>();
        for (NationMemberRecord member : members.values()) {
            if (normalized.equals(member.nationId())) {
                result.add(member);
            }
        }
        return result;
    }

    public NationOfficeRecord getOffice(String nationId, String officeId) {
        String key = officeKey(nationId, officeId);
        return key.isBlank() ? null : offices.get(key);
    }

    public void putOffice(String nationId, NationOfficeRecord office) {
        String key = officeKey(nationId, office.officeId());
        if (key.isBlank()) {
            return;
        }
        offices.put(key, office);
        setDirty();
    }

    public void removeOffice(String nationId, String officeId) {
        String key = officeKey(nationId, officeId);
        if (!key.isBlank() && offices.remove(key) != null) {
            setDirty();
        }
    }

    public List<NationOfficeRecord> getOfficesForNation(String nationId) {
        String normalized = normalizeId(nationId);
        List<NationOfficeRecord> result = new ArrayList<>();
        for (Map.Entry<String, NationOfficeRecord> entry : offices.entrySet()) {
            if (entry.getKey().startsWith(normalized + "|")) {
                result.add(entry.getValue());
            }
        }
        result.sort((left, right) -> Integer.compare(left.priority(), right.priority()));
        return result;
    }

    public NationInviteRecord getInvite(String nationId, UUID playerUuid) {
        String key = inviteKey(nationId, playerUuid);
        return key.isBlank() ? null : invites.get(key);
    }

    public void putInvite(NationInviteRecord invite) {
        String key = inviteKey(invite.nationId(), invite.playerUuid());
        if (key.isBlank()) {
            return;
        }
        invites.put(key, invite);
        setDirty();
    }

    public void removeInvite(String nationId, UUID playerUuid) {
        String key = inviteKey(nationId, playerUuid);
        if (!key.isBlank() && invites.remove(key) != null) {
            setDirty();
        }
    }

    public void clearInvitesForPlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        List<String> inviteKeys = new ArrayList<>();
        for (Map.Entry<String, NationInviteRecord> entry : invites.entrySet()) {
            if (playerUuid.equals(entry.getValue().playerUuid())) {
                inviteKeys.add(entry.getKey());
            }
        }
        if (inviteKeys.isEmpty()) {
            return;
        }
        for (String key : inviteKeys) {
            invites.remove(key);
        }
        setDirty();
    }

    public NationJoinRequestRecord getJoinRequest(String nationId, UUID applicantUuid) {
        String key = joinRequestKey(nationId, applicantUuid);
        return key.isBlank() ? null : joinRequests.get(key);
    }

    public void putJoinRequest(NationJoinRequestRecord request) {
        String key = joinRequestKey(request.nationId(), request.applicantUuid());
        if (key.isBlank()) {
            return;
        }
        joinRequests.put(key, request);
        setDirty();
    }

    public void removeJoinRequest(String nationId, UUID applicantUuid) {
        String key = joinRequestKey(nationId, applicantUuid);
        if (!key.isBlank() && joinRequests.remove(key) != null) {
            setDirty();
        }
    }

    public void clearJoinRequestsForPlayer(UUID applicantUuid) {
        if (applicantUuid == null) {
            return;
        }
        List<String> requestKeys = new ArrayList<>();
        for (Map.Entry<String, NationJoinRequestRecord> entry : joinRequests.entrySet()) {
            if (applicantUuid.equals(entry.getValue().applicantUuid())) {
                requestKeys.add(entry.getKey());
            }
        }
        if (requestKeys.isEmpty()) {
            return;
        }
        for (String key : requestKeys) {
            joinRequests.remove(key);
        }
        setDirty();
    }

    public List<NationJoinRequestRecord> getJoinRequestsForNation(String nationId) {
        String normalized = normalizeId(nationId);
        List<NationJoinRequestRecord> result = new ArrayList<>();
        for (NationJoinRequestRecord request : joinRequests.values()) {
            if (normalized.equals(request.nationId())) {
                result.add(request);
            }
        }
        return result;
    }

    public TownNationRequestRecord getTownNationRequest(String townId, String nationId) {
        String key = townNationRequestKey(townId, nationId);
        return key.isBlank() ? null : townNationRequests.get(key);
    }

    public void putTownNationRequest(TownNationRequestRecord request) {
        String key = townNationRequestKey(request.townId(), request.nationId());
        if (key.isBlank()) {
            return;
        }
        townNationRequests.put(key, request);
        setDirty();
    }

    public void removeTownNationRequest(String townId, String nationId) {
        String key = townNationRequestKey(townId, nationId);
        if (!key.isBlank() && townNationRequests.remove(key) != null) {
            setDirty();
        }
    }

    public void clearTownNationRequestsForTown(String townId) {
        String normalized = normalizeId(townId);
        if (normalized.isBlank()) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, TownNationRequestRecord> entry : townNationRequests.entrySet()) {
            if (normalized.equals(entry.getValue().townId())) {
                keys.add(entry.getKey());
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            townNationRequests.remove(key);
        }
        setDirty();
    }

    public void clearTownNationRequestsForNation(String nationId) {
        String normalized = normalizeId(nationId);
        if (normalized.isBlank()) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, TownNationRequestRecord> entry : townNationRequests.entrySet()) {
            if (normalized.equals(entry.getValue().nationId())) {
                keys.add(entry.getKey());
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            townNationRequests.remove(key);
        }
        setDirty();
    }

    public List<TownNationRequestRecord> getTownNationRequestsForNation(String nationId) {
        String normalized = normalizeId(nationId);
        List<TownNationRequestRecord> result = new ArrayList<>();
        for (TownNationRequestRecord request : townNationRequests.values()) {
            if (normalized.equals(request.nationId())) {
                result.add(request);
            }
        }
        return result;
    }

    public NationTreasuryRecord getTreasury(String nationId) {
        String normalized = normalizeId(nationId);
        return normalized.isBlank() ? null : treasuries.get(normalized);
    }

    public NationTreasuryRecord getOrCreateTreasury(String nationId) {
        String normalized = normalizeId(nationId);
        if (normalized.isBlank()) return NationTreasuryRecord.empty("");
        NationTreasuryRecord existing = treasuries.get(normalized);
        if (existing != null) return existing;
        NationTreasuryRecord newTreasury = NationTreasuryRecord.empty(normalized);
        treasuries.put(normalized, newTreasury);
        setDirty();
        return newTreasury;
    }

    public void putTreasury(NationTreasuryRecord treasury) {
        if (treasury == null || treasury.nationId().isBlank()) return;
        treasuries.put(treasury.nationId(), treasury);
        setDirty();
    }

    public void removeTreasury(String nationId) {
        String normalized = normalizeId(nationId);
        if (!normalized.isBlank() && treasuries.remove(normalized) != null) {
            setDirty();
        }
    }

    public NationClaimRecord getClaim(String dimensionId, int chunkX, int chunkZ) {
        String key = claimKey(dimensionId, chunkX, chunkZ);
        return key.isBlank() ? null : claims.get(key);
    }

    public NationClaimRecord getClaim(Level level, ChunkPos chunkPos) {
        if (level == null || chunkPos == null) {
            return null;
        }
        return getClaim(level.dimension().location().toString(), chunkPos.x, chunkPos.z);
    }

    public void putClaim(NationClaimRecord claim) {
        String key = claimKey(claim.dimensionId(), claim.chunkX(), claim.chunkZ());
        if (key.isBlank()) {
            return;
        }
        claims.put(key, claim);
        setDirty();
    }

    public void removeClaim(String dimensionId, int chunkX, int chunkZ) {
        String key = claimKey(dimensionId, chunkX, chunkZ);
        if (!key.isBlank() && claims.remove(key) != null) {
            setDirty();
        }
    }

    public void clearClaimsForNation(String nationId) {
        String normalized = normalizeId(nationId);
        if (normalized.isBlank()) {
            return;
        }
        List<String> claimKeys = new ArrayList<>();
        for (Map.Entry<String, NationClaimRecord> entry : claims.entrySet()) {
            if (normalized.equals(entry.getValue().nationId())) {
                claimKeys.add(entry.getKey());
            }
        }
        if (claimKeys.isEmpty()) {
            return;
        }
        for (String key : claimKeys) {
            claims.remove(key);
        }
        setDirty();
    }

    public void clearClaimsForTown(String townId) {
        String normalized = normalizeId(townId);
        if (normalized.isBlank()) {
            return;
        }
        List<String> claimKeys = new ArrayList<>();
        for (Map.Entry<String, NationClaimRecord> entry : claims.entrySet()) {
            if (normalized.equals(entry.getValue().townId())) {
                claimKeys.add(entry.getKey());
            }
        }
        if (claimKeys.isEmpty()) {
            return;
        }
        for (String key : claimKeys) {
            claims.remove(key);
        }
        setDirty();
    }

    public java.util.Collection<NationClaimRecord> getAllClaims() {
        return List.copyOf(claims.values());
    }

    public List<NationClaimRecord> getClaimsForNation(String nationId) {
        String normalized = normalizeId(nationId);
        List<NationClaimRecord> result = new ArrayList<>();
        for (NationClaimRecord claim : claims.values()) {
            if (normalized.equals(claim.nationId())) {
                result.add(claim);
            }
        }
        return result;
    }

    public List<NationClaimRecord> getClaimsForTown(String townId) {
        String normalized = normalizeId(townId);
        List<NationClaimRecord> result = new ArrayList<>();
        for (NationClaimRecord claim : claims.values()) {
            if (normalized.equals(claim.townId())) {
                result.add(claim);
            }
        }
        return result;
    }

    public List<NationClaimRecord> getClaimsInArea(String dimensionId, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        String normalizedDimensionId = normalizeId(dimensionId);
        List<NationClaimRecord> result = new ArrayList<>();
        if (normalizedDimensionId.isBlank()) {
            return result;
        }
        for (NationClaimRecord claim : claims.values()) {
            if (!normalizedDimensionId.equals(normalizeId(claim.dimensionId()))) {
                continue;
            }
            if (claim.chunkX() < minChunkX || claim.chunkX() > maxChunkX || claim.chunkZ() < minChunkZ || claim.chunkZ() > maxChunkZ) {
                continue;
            }
            result.add(claim);
        }
        return result;
    }

    public List<NationWarRecord> getWars() {
        return List.copyOf(wars.values());
    }

    public NationWarRecord getWar(String warId) {
        if (warId == null || warId.isBlank()) {
            return null;
        }
        return wars.get(normalizeId(warId));
    }

    public void putWar(NationWarRecord war) {
        if (war == null || war.warId().isBlank()) {
            return;
        }
        wars.put(war.warId(), war);
        setDirty();
    }

    public void removeWar(String warId) {
        String normalized = normalizeId(warId);
        if (!normalized.isBlank() && wars.remove(normalized) != null) {
            setDirty();
        }
    }

    public com.monpai.sailboatmod.nation.model.PeaceProposalRecord getPeaceProposal(String warId) {
        if (warId == null || warId.isBlank()) return null;
        return peaceProposals.get(normalizeId(warId));
    }

    public void putPeaceProposal(com.monpai.sailboatmod.nation.model.PeaceProposalRecord proposal) {
        if (proposal == null || proposal.warId().isBlank()) return;
        peaceProposals.put(proposal.warId(), proposal);
        setDirty();
    }

    public void removePeaceProposal(String warId) {
        String normalized = normalizeId(warId);
        if (!normalized.isBlank() && peaceProposals.remove(normalized) != null) {
            setDirty();
        }
    }

    public java.util.Collection<com.monpai.sailboatmod.nation.model.PeaceProposalRecord> getPeaceProposals() {
        return List.copyOf(peaceProposals.values());
    }

    public com.monpai.sailboatmod.nation.model.PlacedStructureRecord getPlacedStructure(String structureId) {
        if (structureId == null || structureId.isBlank()) return null;
        return placedStructures.get(structureId);
    }

    public void putPlacedStructure(com.monpai.sailboatmod.nation.model.PlacedStructureRecord record) {
        if (record == null || record.structureId().isBlank()) return;
        placedStructures.put(record.structureId(), record);
        setDirty();
    }

    public void removePlacedStructure(String structureId) {
        if (structureId != null && !structureId.isBlank() && placedStructures.remove(structureId) != null) setDirty();
    }

    public java.util.Collection<com.monpai.sailboatmod.nation.model.PlacedStructureRecord> getPlacedStructures() {
        return List.copyOf(placedStructures.values());
    }

    public java.util.List<com.monpai.sailboatmod.nation.model.PlacedStructureRecord> getPlacedStructuresForTown(String townId) {
        if (townId == null || townId.isBlank()) return List.of();
        return placedStructures.values().stream().filter(s -> townId.equals(s.townId())).toList();
    }

    public com.monpai.sailboatmod.nation.model.RoadNetworkRecord getRoadNetwork(String roadId) {
        if (roadId == null || roadId.isBlank()) return null;
        return roadNetworks.get(roadId);
    }

    public void putRoadNetwork(com.monpai.sailboatmod.nation.model.RoadNetworkRecord record) {
        if (record == null || record.roadId().isBlank()) return;
        roadNetworks.put(record.roadId(), record);
        setDirty();
    }

    public void removeRoadNetwork(String roadId) {
        if (roadId != null && !roadId.isBlank() && roadNetworks.remove(roadId) != null) setDirty();
    }

    public java.util.Collection<com.monpai.sailboatmod.nation.model.RoadNetworkRecord> getRoadNetworks() {
        return List.copyOf(roadNetworks.values());
    }

    public java.util.List<com.monpai.sailboatmod.nation.model.RoadNetworkRecord> getRoadNetworksForStructure(String structureId) {
        if (structureId == null || structureId.isBlank()) return List.of();
        return roadNetworks.values().stream().filter(road -> road.connects(structureId)).toList();
    }

    /**
     * Alias for {@link #getPlacedStructure(String)} used by BuildingUpgradeService.
     */
    public com.monpai.sailboatmod.nation.model.PlacedStructureRecord getStructure(String structureId) {
        return getPlacedStructure(structureId);
    }

    /**
     * Alias for {@link #putPlacedStructure(com.monpai.sailboatmod.nation.model.PlacedStructureRecord)} used by BuildingUpgradeService.
     */
    public void putStructure(com.monpai.sailboatmod.nation.model.PlacedStructureRecord record) {
        putPlacedStructure(record);
    }

    public void putTradeProposal(com.monpai.sailboatmod.nation.model.TradeProposalRecord proposal) {
        if (proposal == null || proposal.proposalId().isBlank()) return;
        tradeProposals.put(proposal.proposalId(), proposal);
        setDirty();
    }

    public void removeTradeProposal(String proposalId) {
        if (proposalId != null && !proposalId.isBlank() && tradeProposals.remove(proposalId) != null) setDirty();
    }

    public java.util.Collection<com.monpai.sailboatmod.nation.model.TradeProposalRecord> getTradeProposals() {
        return List.copyOf(tradeProposals.values());
    }

    public com.monpai.sailboatmod.nation.model.TradeProposalRecord getTradeProposalForNation(String nationId) {
        if (nationId == null || nationId.isBlank()) return null;
        for (com.monpai.sailboatmod.nation.model.TradeProposalRecord p : tradeProposals.values()) {
            if (nationId.equals(p.targetNationId()) || nationId.equals(p.proposerNationId())) return p;
        }
        return null;
    }

    public NationFlagRecord getFlag(String flagId) {
        if (flagId == null || flagId.isBlank()) {
            return null;
        }
        return flags.get(normalizeId(flagId));
    }

    public List<NationFlagRecord> getFlags() {
        return List.copyOf(flags.values());
    }

    public void putFlag(NationFlagRecord flag) {
        if (flag == null || flag.flagId().isBlank()) {
            return;
        }
        flags.put(flag.flagId(), flag);
        setDirty();
    }

    public void removeFlag(String flagId) {
        String normalized = normalizeId(flagId);
        if (!normalized.isBlank() && flags.remove(normalized) != null) {
            setDirty();
        }
    }

    public List<NationFlagRecord> getFlagsForNation(String nationId) {
        String normalized = normalizeId(nationId);
        List<NationFlagRecord> result = new ArrayList<>();
        for (NationFlagRecord flag : flags.values()) {
            if (normalized.equals(flag.nationId())) {
                result.add(flag);
            }
        }
        return result;
    }

    public NationDiplomacyRecord getDiplomacy(String nationAId, String nationBId) {
        String key = diplomacyKey(nationAId, nationBId);
        return key.isBlank() ? null : diplomacy.get(key);
    }

    public void putDiplomacy(NationDiplomacyRecord record) {
        String key = diplomacyKey(record.nationAId(), record.nationBId());
        if (key.isBlank()) {
            return;
        }
        diplomacy.put(key, record);
        setDirty();
    }

    public void removeDiplomacy(String nationAId, String nationBId) {
        String key = diplomacyKey(nationAId, nationBId);
        if (!key.isBlank() && diplomacy.remove(key) != null) {
            setDirty();
        }
    }

    public List<NationDiplomacyRecord> getDiplomacyForNation(String nationId) {
        String normalized = normalizeId(nationId);
        List<NationDiplomacyRecord> result = new ArrayList<>();
        for (NationDiplomacyRecord record : diplomacy.values()) {
            if (record.includes(normalized)) {
                result.add(record);
            }
        }
        return result;
    }

    public NationDiplomacyRequestRecord getDiplomacyRequest(String fromNationId, String toNationId, String statusId) {
        String key = diplomacyRequestKey(fromNationId, toNationId, statusId);
        return key.isBlank() ? null : diplomacyRequests.get(key);
    }

    public void putDiplomacyRequest(NationDiplomacyRequestRecord request) {
        String key = diplomacyRequestKey(request.fromNationId(), request.toNationId(), request.statusId());
        if (key.isBlank()) {
            return;
        }
        diplomacyRequests.put(key, request);
        setDirty();
    }

    public void removeDiplomacyRequest(String fromNationId, String toNationId, String statusId) {
        String key = diplomacyRequestKey(fromNationId, toNationId, statusId);
        if (!key.isBlank() && diplomacyRequests.remove(key) != null) {
            setDirty();
        }
    }

    public List<NationDiplomacyRequestRecord> getIncomingDiplomacyRequests(String nationId) {
        String normalized = normalizeId(nationId);
        List<NationDiplomacyRequestRecord> result = new ArrayList<>();
        for (NationDiplomacyRequestRecord request : diplomacyRequests.values()) {
            if (normalized.equals(request.toNationId())) {
                result.add(request);
            }
        }
        return result;
    }

    private static String officeKey(String nationId, String officeId) {
        String normalizedNationId = normalizeId(nationId);
        String normalizedOfficeId = normalizeId(officeId);
        if (normalizedNationId.isBlank() || normalizedOfficeId.isBlank()) {
            return "";
        }
        return normalizedNationId + "|" + normalizedOfficeId;
    }

    private static String inviteKey(String nationId, UUID playerUuid) {
        String normalizedNationId = normalizeId(nationId);
        if (normalizedNationId.isBlank() || playerUuid == null) {
            return "";
        }
        return normalizedNationId + "|" + playerUuid;
    }

    private static String joinRequestKey(String nationId, UUID applicantUuid) {
        String normalizedNationId = normalizeId(nationId);
        if (normalizedNationId.isBlank() || applicantUuid == null) {
            return "";
        }
        return normalizedNationId + "|" + applicantUuid;
    }

    private static String claimKey(String dimensionId, int chunkX, int chunkZ) {
        String normalizedDimensionId = normalizeId(dimensionId);
        if (normalizedDimensionId.isBlank()) {
            return "";
        }
        return normalizedDimensionId + "|" + chunkX + "|" + chunkZ;
    }

    private static String diplomacyKey(String nationAId, String nationBId) {
        String left = normalizeId(nationAId);
        String right = normalizeId(nationBId);
        if (left.isBlank() || right.isBlank() || left.equals(right)) {
            return "";
        }
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }

    private static String diplomacyRequestKey(String fromNationId, String toNationId, String statusId) {
        String from = normalizeId(fromNationId);
        String to = normalizeId(toNationId);
        String status = normalizeId(statusId);
        if (from.isBlank() || to.isBlank() || status.isBlank()) {
            return "";
        }
        return from + "|" + to + "|" + status;
    }

    private static String townNationRequestKey(String townId, String nationId) {
        String normalizedTownId = normalizeId(townId);
        String normalizedNationId = normalizeId(nationId);
        if (normalizedTownId.isBlank() || normalizedNationId.isBlank()) {
            return "";
        }
        return normalizedTownId + "|" + normalizedNationId;
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
