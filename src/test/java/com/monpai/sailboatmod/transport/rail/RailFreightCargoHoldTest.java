package com.monpai.sailboatmod.transport.rail;

import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailFreightCargoHoldTest {
    // JUnit does not load a world's FURNACE_FUELS data map. Supply its inputs through the native hook.
    private final Consumer<FurnaceFuelBurnTimeEvent> fuels = event -> {
        if (event.getItemStack().is(Items.COAL)) event.setBurnTime(1600);
        if (event.getItemStack().is(Items.OAK_PLANKS)) event.setBurnTime(300);
    };

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void installFuelFixture() { NeoForge.EVENT_BUS.addListener(fuels); }

    @AfterEach
    void removeFuelFixture() { NeoForge.EVENT_BUS.unregister(fuels); }

    @Test
    void fuelAccessibleStorageRejectsCombustibleFreightInSimulationAndInsertion() {
        ItemStackHandler physical = new ItemStackHandler(2);
        RailFreightCargoHold hold = new RailFreightCargoHold(physical, true);
        ItemStack coal = new ItemStack(Items.COAL, 4);
        ItemStack wood = new ItemStack(Items.OAK_PLANKS, 4);

        assertFalse(hold.isItemValid(0, coal));
        assertEquals(4, hold.insertItem(0, coal, true).getCount());
        assertEquals(4, hold.insertItem(0, wood, false).getCount());
        assertTrue(physical.getStackInSlot(0).isEmpty());
        assertEquals(4, coal.getCount());
    }

    @Test
    void nativeFuelExcludedStorageAcceptsTheSameCombustibleFreight() {
        ItemStackHandler physical = new ItemStackHandler(1);
        RailFreightCargoHold hold = new RailFreightCargoHold(physical, false);
        ItemStack coal = new ItemStack(Items.COAL, 4);

        assertTrue(hold.isItemValid(0, coal));
        assertTrue(hold.insertItem(0, coal, false).isEmpty());
        assertEquals(4, physical.getStackInSlot(0).getCount());
        assertEquals(4, hold.extractItem(0, 4, false).getCount());
        assertTrue(physical.getStackInSlot(0).isEmpty());
    }

    @Test
    void ordinaryWheatUsesTheRealChestAndRollbackCanRestoreExistingFuel() {
        ItemStackHandler physical = new ItemStackHandler(1);
        RailFreightCargoHold hold = new RailFreightCargoHold(physical, true);
        ItemStack wheat = new ItemStack(Items.WHEAT, 4);
        assertTrue(hold.isItemValid(0, wheat));
        assertTrue(hold.insertItem(0, wheat, true).isEmpty());
        assertTrue(physical.getStackInSlot(0).isEmpty());
        assertTrue(hold.insertItem(0, wheat, false).isEmpty());
        assertEquals(4, physical.getStackInSlot(0).getCount());
        hold.setStackInSlot(0, new ItemStack(Items.COAL, 2));
        assertEquals(2, physical.getStackInSlot(0).getCount());
    }
}
