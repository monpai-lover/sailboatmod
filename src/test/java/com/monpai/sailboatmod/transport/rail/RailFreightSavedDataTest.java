package com.monpai.sailboatmod.transport.rail;

import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.monpai.sailboatmod.transport.rail.RailFreightSavedData.*;
import static org.junit.jupiter.api.Assertions.*;

class RailFreightSavedDataTest {
    private static final UUID TRAIN = new UUID(0, 1);
    private static final UUID OTHER_TRAIN = new UUID(0, 2);
    private static final UUID OWNER = new UUID(0, 3);
    private static final UUID BUYER = new UUID(0, 4);
    private static final UUID JOB = new UUID(0, 5);
    private static final UUID OTHER_JOB = new UUID(0, 6);
    private static final RailStationRef SOURCE = new RailStationRef("minecraft:overworld", new BlockPos(4, 64, 8), new UUID(0, 7));
    private static final RailStationRef TARGET = new RailStationRef("minecraft:overworld", new BlockPos(80, 64, 8), new UUID(0, 8));
    private static final RailRoute ROUTE = new RailRoute(SOURCE, TARGET, new BlockPos(4, 64, 10),
            new BlockPos(80, 64, 10), new BlockPos(81, 64, 12), 76, "Freight test");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void registrationCannotBeOverwrittenOrRemovedWhileCustodyExists() {
        RailFreightSavedData data = new RailFreightSavedData();
        Registration original = registration(TRAIN);
        assertTrue(data.register(original));
        assertFalse(data.register(new Registration(TRAIN, BUYER, TARGET, "Untrusted replacement", true)));
        assertEquals(original, data.registration(TRAIN));
        assertTrue(data.setEnabled(TRAIN, false));
        assertFalse(data.registration(TRAIN).enabled());
        assertFalse(data.begin(job(JOB, TRAIN, "purchase-a", "shipping-a", Phase.PREPARED)));
        assertTrue(data.setEnabled(TRAIN, true));
        assertTrue(data.begin(job(JOB, TRAIN, "purchase-a", "shipping-a", Phase.PREPARED)));
        assertFalse(data.unregister(TRAIN));
        assertFalse(data.complete(TRAIN, OTHER_JOB));
        assertTrue(data.complete(TRAIN, JOB));
        assertTrue(data.unregister(TRAIN));
        assertNull(data.registration(TRAIN));
    }

    @Test
    void purchaseAndShippingIdsRemainExclusiveAcrossTrainsUntilCompletion() {
        RailFreightSavedData data = new RailFreightSavedData();
        assertTrue(data.register(registration(TRAIN)));
        assertTrue(data.register(registration(OTHER_TRAIN)));
        assertTrue(data.begin(job(JOB, TRAIN, "purchase-a", "shipping-a", Phase.PREPARED)));
        assertTrue(data.claimsPurchase("purchase-a"));
        assertFalse(data.claimsPurchase("purchase-b"));
        assertFalse(data.begin(job(OTHER_JOB, TRAIN, "purchase-b", "shipping-b", Phase.PREPARED)));
        assertFalse(data.begin(job(OTHER_JOB, OTHER_TRAIN, "purchase-a", "shipping-b", Phase.PREPARED)));
        assertFalse(data.begin(job(OTHER_JOB, OTHER_TRAIN, "purchase-b", "shipping-a", Phase.PREPARED)));
        assertFalse(data.begin(job(JOB, OTHER_TRAIN, "purchase-b", "shipping-b", Phase.PREPARED)));
        assertFalse(data.replace(job(OTHER_JOB, TRAIN, "purchase-a", "shipping-a", Phase.OUTBOUND)));
        assertTrue(data.replace(job(JOB, TRAIN, "purchase-a", "shipping-a", Phase.OUTBOUND)));
        assertTrue(data.replace(job(JOB, TRAIN, "purchase-a", "shipping-a", Phase.RETURN)));
        assertFalse(data.begin(job(OTHER_JOB, OTHER_TRAIN, "purchase-a", "shipping-b", Phase.PREPARED)));
        assertTrue(data.complete(TRAIN, JOB));
        assertFalse(data.claimsPurchase("purchase-a"));
        assertTrue(data.begin(job(OTHER_JOB, OTHER_TRAIN, "purchase-a", "shipping-a", Phase.PREPARED)));
    }

    @Test
    void roundTripPreservesReturnCustodyAndItsExclusionLocks() {
        RailFreightSavedData data = prepared();
        assertTrue(data.register(registration(OTHER_TRAIN)));
        assertTrue(data.replace(job(JOB, TRAIN, "purchase-a", "shipping-a", Phase.OUTBOUND)));
        assertTrue(data.replace(job(JOB, TRAIN, "purchase-a", "shipping-a", Phase.RETURN)));
        CompoundTag serialized = data.save(new CompoundTag(), registries());
        CompoundTag original = serialized.copy();

        RailFreightSavedData restored = RailFreightSavedData.load(serialized, registries());
        assertEquals(original, serialized);
        assertEquals(registration(TRAIN), restored.registration(TRAIN));
        Job retained = restored.job(TRAIN);
        assertNotNull(retained);
        assertEquals(JOB, retained.jobId());
        assertEquals(ROUTE, retained.route());
        assertEquals(Phase.RETURN, retained.phase());
        assertTrue(restored.claimsPurchase("purchase-a"));
        assertEquals("return", retained.status());
        assertEquals(schedule(Phase.RETURN), retained.ownedSchedule());
        ShipmentManifestEntry entry = retained.manifest().getFirst();
        assertEquals("purchase-a", entry.purchaseOrderId());
        assertEquals("shipping-a", entry.shippingOrderId());
        assertEquals(4, entry.quantity());
        assertEquals(BUYER.toString(), entry.recipientUuid());
        assertTrue(ItemStack.isSameItemSameComponents(cargo(), entry.itemStack()));
        assertFalse(restored.begin(job(OTHER_JOB, OTHER_TRAIN, "purchase-a", "shipping-b", Phase.PREPARED)));
        assertFalse(restored.unregister(TRAIN));
    }

    @Test
    void activeStopoversSurviveRestartInOrderWithCargoClaimAndOwnedSchedule() {
        RailFreightSavedData data = prepared();
        assertTrue(data.replace(job(JOB, TRAIN, "purchase-a", "shipping-a", Phase.OUTBOUND)));
        CompoundTag serialized = data.save(new CompoundTag(), registries());
        CompoundTag savedJob = serialized.getList("Jobs", Tag.TAG_COMPOUND).getCompound(0);
        CompoundTag route = savedJob.getCompound("Route");
        var stops = new ListTag();
        for (int x : List.of(52, 28)) {
            CompoundTag stop = route.getCompound("Source").copy();
            stop.putLong("StationPos", new BlockPos(x, 64, 8).asLong());
            stop.putUUID("StationId", new UUID(0, x));
            stops.add(stop);
        }
        stops.getCompound(0).putInt("DwellSeconds", 45);
        route.put("Stopovers", stops);
        var owned = new CompoundTag();
        ListTag entries = stops.copy();
        entries.add(route.getCompound("Target").copy());
        owned.put("Entries", entries);
        savedJob.put("OwnedSchedule", owned);

        RailFreightSavedData restored = RailFreightSavedData.load(serialized, registries());
        assertTrue(restored.claimsPurchase("purchase-a"));
        Job active = restored.job(TRAIN);
        assertNotNull(active, "A valid ordered stopover list must retain the active job");
        assertEquals(Phase.OUTBOUND, active.phase());
        assertEquals(owned, active.ownedSchedule());
        assertEquals(List.of(new BlockPos(52, 64, 8), new BlockPos(28, 64, 8), TARGET.stationPos()),
                active.route().outboundStations().stream().map(RailStationRef::stationPos).toList());
        assertEquals(List.of(45, 1), active.route().stopoverSeconds());
        assertEquals(4, active.manifest().getFirst().quantity());
        assertEquals(serialized, restored.save(new CompoundTag(), registries()));
    }

    @Test
    void jobSnapshotsDefendNestedScheduleAndMutableItemStacks() {
        RailFreightSavedData data = prepared();
        CompoundTag nativeSchedule = schedule(Phase.OUTBOUND);
        ShipmentManifestEntry manifestEntry = entry("purchase-a", "shipping-a");
        List<ShipmentManifestEntry> callerManifest = new ArrayList<>(List.of(manifestEntry));
        Job outbound = new Job(JOB, TRAIN, ROUTE, callerManifest, nativeSchedule, Phase.OUTBOUND, "moving");
        assertTrue(data.replace(outbound));

        nativeSchedule.getCompound("Entry").putInt("Index", 7);
        manifestEntry.itemStack().setCount(12);
        callerManifest.clear();
        Job snapshot = data.job(TRAIN);
        snapshot.ownedSchedule().getCompound("Entry").putInt("Index", 8);
        snapshot.manifest().getFirst().itemStack().setCount(24);

        assertEquals(schedule(Phase.OUTBOUND), data.job(TRAIN).ownedSchedule());
        assertEquals(1, data.job(TRAIN).manifest().getFirst().itemStack().getCount());
        assertThrows(UnsupportedOperationException.class, () -> data.jobs().clear());
        assertThrows(UnsupportedOperationException.class, () -> data.registrations().clear());
    }

    @Test
    void damagedActiveJobFailsClosedForTheWholeStore() {
        CompoundTag serialized = prepared().save(new CompoundTag(), registries());
        serialized.getList("Jobs", Tag.TAG_COMPOUND).getCompound(0).putString("Phase", "invalid-phase");
        CompoundTag original = serialized.copy();

        RailFreightSavedData rejected = RailFreightSavedData.load(serialized, registries());
        assertEquals(original, serialized);
        assertTrue(rejected.jobs().isEmpty());
        assertTrue(rejected.registrations().isEmpty());
        assertFalse(rejected.isDirty());
        assertFalse(rejected.register(registration(OTHER_TRAIN)));
        assertFalse(rejected.begin(job(OTHER_JOB, OTHER_TRAIN, "purchase-a", "shipping-a", Phase.PREPARED)));
        assertThrows(IllegalStateException.class, () -> rejected.save(new CompoundTag(), registries()));
    }

    @Test
    void duplicatePersistedJobFailsClosedRatherThanDroppingCustody() {
        CompoundTag serialized = prepared().save(new CompoundTag(), registries());
        var jobs = serialized.getList("Jobs", Tag.TAG_COMPOUND);
        jobs.add(jobs.getCompound(0).copy());

        RailFreightSavedData rejected = RailFreightSavedData.load(serialized, registries());
        assertFalse(rejected.register(registration(OTHER_TRAIN)));
        assertTrue(rejected.jobs().isEmpty());
        assertFalse(rejected.isDirty());
    }

    private static RailFreightSavedData prepared() {
        RailFreightSavedData data = new RailFreightSavedData();
        assertTrue(data.register(registration(TRAIN)));
        assertTrue(data.begin(job(JOB, TRAIN, "purchase-a", "shipping-a", Phase.PREPARED)));
        return data;
    }

    private static Registration registration(UUID trainId) {
        return new Registration(trainId, OWNER, SOURCE, "Freight train", true);
    }

    private static Job job(UUID jobId, UUID trainId, String purchase, String shipping, Phase phase) {
        return new Job(jobId, trainId, ROUTE, List.of(entry(purchase, shipping)), schedule(phase), phase,
                phase.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static ShipmentManifestEntry entry(String purchase, String shipping) {
        return new ShipmentManifestEntry("listing-a", cargo(), purchase, shipping, BUYER.toString(), "Buyer", 4);
    }

    private static ItemStack cargo() {
        ItemStack stack = new ItemStack(Items.WHEAT);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Reserved cargo"));
        return stack;
    }

    private static CompoundTag schedule(Phase phase) {
        CompoundTag schedule = new CompoundTag();
        if (phase != Phase.PREPARED) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Index", phase.ordinal());
            schedule.put("Entry", entry);
        }
        return schedule;
    }

    private static HolderLookup.Provider registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }
}
