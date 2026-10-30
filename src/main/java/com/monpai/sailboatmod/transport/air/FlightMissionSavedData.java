package com.monpai.sailboatmod.transport.air;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Global mission journal; cargo exists exclusively in the structure/storage backend. */
public final class FlightMissionSavedData extends SavedData {
    private final Map<UUID,FlightMission> missions = new LinkedHashMap<>();
    private final ListTag quarantined = new ListTag();
    public static FlightMissionSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(FlightMissionSavedData::new,(tag,provider)->load(tag)), "sailboatmod_flight_missions");
    }
    public FlightMission get(UUID id) { return missions.get(id); }
    public List<FlightMission> all() { return List.copyOf(missions.values()); }
    public void put(FlightMission mission) {
        FlightMission previous = missions.get(mission.id());
        if (previous != null && previous != mission && previous.hasExclusiveStorage()) throw new IllegalStateException("aircraft_in_storage");
        missions.put(mission.id(),mission); setDirty();
    }
    public boolean hasActiveMissionForTrace(String id) {
        return missions.values().stream().anyMatch(m->m.hasExclusiveStorage() && m.ownsTrace(id));
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list=new ListTag(); missions.values().forEach(m->list.add(m.save()));
        quarantined.forEach(raw->list.add(raw.copy())); tag.put("Missions",list); return tag;
    }
    public static FlightMissionSavedData load(CompoundTag tag) {
        FlightMissionSavedData data=new FlightMissionSavedData();
        for(Tag raw:tag.getList("Missions",Tag.TAG_COMPOUND)) {
            try { FlightMission m=FlightMission.load((CompoundTag)raw); data.missions.put(m.id(),m); }
            catch(RuntimeException invalid) {
                data.quarantined.add(raw.copy());
                org.slf4j.LoggerFactory.getLogger(FlightMissionSavedData.class).error("Invalid air mission preserved in quarantine; refusing virtual execution",invalid);
            }
        }
        return data;
    }
}
