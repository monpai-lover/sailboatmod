package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CarriageWoodTypeTest {
    @Test
    void defaultsUnknownIdsToOak() {
        assertEquals(CarriageWoodType.OAK, CarriageWoodType.fromSerialized(null));
        assertEquals(CarriageWoodType.OAK, CarriageWoodType.fromSerialized(""));
        assertEquals(CarriageWoodType.OAK, CarriageWoodType.fromSerialized("unknown"));
    }

    @Test
    void parsesKnownWoodIds() {
        assertEquals(CarriageWoodType.OAK, CarriageWoodType.fromSerialized("oak"));
        assertEquals(CarriageWoodType.SPRUCE, CarriageWoodType.fromSerialized("spruce"));
        assertEquals(CarriageWoodType.DARK_OAK, CarriageWoodType.fromSerialized("dark_oak"));
    }

    @Test
    void cyclesInStableOrder() {
        assertEquals(CarriageWoodType.SPRUCE, CarriageWoodType.OAK.next());
        assertEquals(CarriageWoodType.DARK_OAK, CarriageWoodType.SPRUCE.next());
        assertEquals(CarriageWoodType.OAK, CarriageWoodType.DARK_OAK.next());
    }

    @Test
    void exposesTranslationKeysAndTextureLocations() {
        assertEquals("item.sailboatmod.carriage.wood.oak", CarriageWoodType.OAK.translationKey());
        assertEquals("item.sailboatmod.carriage.wood.dark_oak", CarriageWoodType.DARK_OAK.translationKey());
        assertEquals("sailboatmod:textures/entity/carriage_oak.png", CarriageWoodType.OAK.textureLocation().toString());
        assertEquals("sailboatmod:textures/entity/carriage_spruce.png", CarriageWoodType.SPRUCE.textureLocation().toString());
        assertEquals("sailboatmod:textures/entity/carriage_dark_oak.png", CarriageWoodType.DARK_OAK.textureLocation().toString());
    }
}
