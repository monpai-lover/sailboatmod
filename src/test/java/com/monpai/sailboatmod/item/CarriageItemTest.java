package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.entity.CarriageWoodType;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CarriageItemTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void missingWoodTagFallsBackToOak() {
        ItemStack stack = new ItemStack(Items.STICK);

        assertEquals(CarriageWoodType.OAK, CarriageItem.getWoodType(stack));
    }

    @Test
    void writesAndReadsWoodTypeFromStackTag() {
        ItemStack stack = new ItemStack(Items.STICK);

        CarriageItem.setWoodType(stack, CarriageWoodType.SPRUCE);
        assertEquals(CarriageWoodType.SPRUCE, CarriageItem.getWoodType(stack));

        CarriageItem.setWoodType(stack, CarriageWoodType.DARK_OAK);
        assertEquals(CarriageWoodType.DARK_OAK, CarriageItem.getWoodType(stack));
    }

    @Test
    void cycleWoodTypeAdvancesAndWraps() {
        ItemStack stack = new ItemStack(Items.STICK);

        assertEquals(CarriageWoodType.SPRUCE, CarriageItem.cycleWoodType(stack));
        assertEquals(CarriageWoodType.SPRUCE, CarriageItem.getWoodType(stack));
        assertEquals(CarriageWoodType.DARK_OAK, CarriageItem.cycleWoodType(stack));
        assertEquals(CarriageWoodType.OAK, CarriageItem.cycleWoodType(stack));
    }
}
