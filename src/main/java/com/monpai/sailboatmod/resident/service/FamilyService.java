package com.monpai.sailboatmod.resident.service;

import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.event.ResidentEvent;
import com.monpai.sailboatmod.resident.model.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages family relationships: pairing, children, inheritance
 */
public class FamilyService {
    /** Per-resident family data, keyed by residentId */
    private static final Map<String, FamilyData> familyMap = new HashMap<>();

    public static FamilyData getFamily(String residentId) {
        return familyMap.computeIfAbsent(residentId, k -> new FamilyData());
    }

    /**
     * Try to pair two single residents in the same town.
     */
    public static boolean tryPair(ServerLevel level, String residentA, String residentB) {
        ResidentSavedData data = ResidentSavedData.get(level);
        ResidentRecord a = data.getResident(residentA);
        ResidentRecord b = data.getResident(residentB);
        if (a == null || b == null) return false;
        if (!a.townId().equals(b.townId())) return false;

        FamilyData famA = getFamily(residentA);
        FamilyData famB = getFamily(residentB);
        if (famA.hasPartner() || famB.hasPartner()) return false;

        famA.setPartner(residentB);
        famB.setPartner(residentA);
        return true;
    }

    /**
     * Produce a child from two paired residents.
     * Child inherits culture from parents, gets a generated name.
     */
    public static ResidentRecord produceChild(ServerLevel level, String parentAId, String parentBId) {
        ResidentSavedData data = ResidentSavedData.get(level);
        ResidentRecord parentA = data.getResident(parentAId);
        ResidentRecord parentB = data.getResident(parentBId);
        if (parentA == null || parentB == null) return null;

        ThreadLocalRandom r = ThreadLocalRandom.current();
        Gender childGender = Gender.random();
        Culture childCulture = r.nextBoolean() ? parentA.culture() : parentB.culture();
        String childName = ResidentNames.random(childCulture, childGender);

        ResidentRecord child = ResidentRecord.create(parentA.townId(), childName, "", childCulture);
        data.putResident(child);

        // Set up family links
        FamilyData childFam = getFamily(child.residentId());
        childFam.setParents(parentA.name(), parentB.name());
        getFamily(parentAId).addChild(child.residentId());
        getFamily(parentBId).addChild(child.residentId());

        // Siblings
        for (String existingChildId : getFamily(parentAId).getChildren()) {
            if (!existingChildId.equals(child.residentId())) {
                getFamily(existingChildId).addSibling(child.residentId());
                childFam.addSibling(existingChildId);
            }
        }

        MinecraftForge.EVENT_BUS.post(new ResidentEvent.Born(child));
        return child;
    }

    /**
     * Auto-pair eligible single adults in a town (called periodically).
     */
    public static void processAutoPairing(ServerLevel level, String townId) {
        ResidentSavedData data = ResidentSavedData.get(level);
        List<ResidentRecord> singles = new ArrayList<>();
        for (ResidentRecord r : data.getResidentsForTown(townId)) {
            if (r.age() >= 18 && !getFamily(r.residentId()).hasPartner()) {
                singles.add(r);
            }
        }
        if (singles.size() < 2) return;

        Collections.shuffle(singles);
        // Pair first two eligible singles
        tryPair(level, singles.get(0).residentId(), singles.get(1).residentId());
    }
}
