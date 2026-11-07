package com.monpai.sailboatmod.transport.board;

import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.Endpoint;

class TownVoyageBindingTest {
    static Endpoint home() { return new Endpoint("minecraft:overworld", 7, "town-a", "Home"); }
    static Endpoint away() { return new Endpoint("minecraft:the_nether", 7, "town-b", "Away"); }
    static CompoundTag saved() {
        TownVoyageBinding binding = new TownVoyageBinding();
        binding.startSuccessfulVoyage(home(), away());
        CompoundTag root = new CompoundTag(); root.putString("AutopilotRoute", "Existing navigation"); binding.writeTo(root);
        return root;
    }
    @Test void actualStartCreatesLegEndpointObservationsPreserveItAndActualReturnGetsFreshId() {
        TownVoyageBinding binding = new TownVoyageBinding();
        assertTrue(binding.current().isEmpty());
        assertFalse(binding.observeEndpoints(home(), away()));
        var leg = binding.startSuccessfulVoyage(home(), Endpoint.unknown());
        assertEquals(leg, binding.current().orElseThrow());
        assertTrue(binding.observeEndpoints(home(), away()));
        assertEquals(leg.legId(), binding.current().orElseThrow().legId());
        assertEquals(away(), binding.current().orElseThrow().target());
        binding.finish(); assertTrue(binding.current().isEmpty());
        var returning = binding.startSuccessfulVoyage(away(), home());
        assertNotEquals(leg.legId(), returning.legId());
        assertEquals(away(), returning.source());
    }
    @Test void persistenceRoundTripContainsOnlyBoardIdentityAndPreservesNavigation() {
        CompoundTag root = saved(); CompoundTag before = root.copy();
        var loaded = TownVoyageBinding.readFrom(root);
        assertEquals(home(), loaded.current().orElseThrow().source());
        assertEquals(away(), loaded.current().orElseThrow().target());
        assertEquals(Set.of("Version", "LegId", "Source", "Target"), root.getCompound(TownVoyageBinding.NBT_KEY).getAllKeys());
        assertEquals(before, root);
        CompoundTag roundTrip = new CompoundTag(); loaded.writeTo(roundTrip);
        assertEquals(root.getCompound(TownVoyageBinding.NBT_KEY), roundTrip.getCompound(TownVoyageBinding.NBT_KEY));
        loaded.finish(); loaded.writeTo(root);
        assertFalse(root.contains(TownVoyageBinding.NBT_KEY));
        assertEquals("Existing navigation", root.getString("AutopilotRoute"));
    }
    @Test void unknownVersionMalformedTypesAndOversizedMetadataDropOnlyBoardBinding() {
        List<CompoundTag> damaged = new ArrayList<>();
        CompoundTag version = saved(); version.getCompound(TownVoyageBinding.NBT_KEY).putInt("Version", 99); damaged.add(version);
        CompoundTag uuid = saved(); uuid.getCompound(TownVoyageBinding.NBT_KEY).putString("LegId", "not-a-uuid"); damaged.add(uuid);
        CompoundTag position = saved(); position.getCompound(TownVoyageBinding.NBT_KEY).getCompound("Source").putString("TerminalPos", "7"); damaged.add(position);
        CompoundTag town = saved(); town.getCompound(TownVoyageBinding.NBT_KEY).getCompound("Source").putString("TownId", "t".repeat(129)); damaged.add(town);
        CompoundTag dimension = saved(); dimension.getCompound(TownVoyageBinding.NBT_KEY).getCompound("Source").putString("DimensionId", "not a dimension"); damaged.add(dimension);
        CompoundTag name = saved(); name.getCompound(TownVoyageBinding.NBT_KEY).getCompound("Target").putString("DisplayName", "n".repeat(65)); damaged.add(name);
        CompoundTag extra = saved(); extra.getCompound(TownVoyageBinding.NBT_KEY).putString("Cargo", "private"); damaged.add(extra);
        CompoundTag wrongRootType = saved(); wrongRootType.putString(TownVoyageBinding.NBT_KEY, "broken"); damaged.add(wrongRootType);
        for (CompoundTag root : damaged) {
            CompoundTag before = root.copy();
            assertTrue(TownVoyageBinding.readFrom(root).current().isEmpty());
            assertEquals(before, root);
            assertEquals("Existing navigation", root.getString("AutopilotRoute"));
        }
    }
    @Test void partialEndpointsRoundTripAndInvalidLegCannotBeConstructed() {
        var binding = new TownVoyageBinding(); binding.startSuccessfulVoyage(Endpoint.unknown(), home());
        var root = new CompoundTag(); binding.writeTo(root);
        assertEquals(Endpoint.unknown(), TownVoyageBinding.readFrom(root).current().orElseThrow().source());
        assertThrows(IllegalArgumentException.class, () -> new TownVoyageBinding.Leg(null, home(), away()));
        assertThrows(IllegalArgumentException.class, () -> binding.startSuccessfulVoyage(null, away()));
    }
}
