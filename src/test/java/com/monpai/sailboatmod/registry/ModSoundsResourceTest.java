package com.monpai.sailboatmod.registry;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvents;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModSoundsResourceTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void carriageMovementSoundUsesBundledHorseGallopClips() throws Exception {
        Path soundsJson = Path.of("src/main/resources/assets/sailboatmod/sounds.json");
        String json = Files.readString(soundsJson);

        assertTrue(json.contains("sailboatmod:entity/carriage/horse_gallop_blocky"));
        assertTrue(json.contains("sailboatmod:entity/carriage/horse_gallop_clicky"));
        assertTrue(json.contains("sailboatmod:entity/carriage/horse_gallop_grass"));
        assertTrue(json.contains("sailboatmod:entity/carriage/horse_gallop_ground"));
        assertTrue(json.contains("sailboatmod:entity/carriage/horse_gallop_hall"));
        assertTrue(json.contains("sailboatmod:entity/carriage/horse_gallop_muffled"));
        assertTrue(json.contains("\"entity.carriage.move.stone\""));
        assertTrue(json.contains("\"entity.carriage.move.grass\""));
        assertTrue(json.contains("\"entity.carriage.move.sand\""));
        assertTrue(json.contains("\"entity.carriage.move.snow\""));
        assertTrue(json.contains("\"entity.carriage.move.wood\""));
        assertTrue(json.contains("\"entity.carriage.move.ground\""));
        assertFalse(json.contains("minecraft:block.wood.step"));
        assertFalse(json.contains("minecraft:entity.horse.step_wood"));
        assertFalse(json.contains("minecraft:block.wood.place"));
        assertFalse(json.contains("minecraft:entity.horse.saddle"));
        assertFalse(json.contains("minecraft:entity.leash_knot.break"));
        assertTrue(json.contains("minecraft:step/wood1"));
        assertTrue(json.contains("minecraft:mob/horse/leather"));
        assertTrue(json.contains("minecraft:random/break"));

        Path soundDir = Path.of("src/main/resources/assets/sailboatmod/sounds/entity/carriage");
        assertTrue(Files.exists(soundDir.resolve("horse_gallop_blocky.ogg")));
        assertTrue(Files.exists(soundDir.resolve("horse_gallop_clicky.ogg")));
        assertTrue(Files.exists(soundDir.resolve("horse_gallop_grass.ogg")));
        assertTrue(Files.exists(soundDir.resolve("horse_gallop_ground.ogg")));
        assertTrue(Files.exists(soundDir.resolve("horse_gallop_hall.ogg")));
        assertTrue(Files.exists(soundDir.resolve("horse_gallop_muffled.ogg")));
        assertFalse(Files.exists(Path.of("src/main/resources/assets/sailboatmod/sounds/THIRD_PARTY_NOTICES.txt")));
        assertTrue(Files.exists(Path.of("src/main/resources/assets/sailboatmod/third_party_notices.txt")));
    }

    @Test
    void carriageArrivalUsesVanillaPlayerLevelupSound() {
        assertEquals(SoundEvents.PLAYER_LEVELUP, com.monpai.sailboatmod.entity.CarriageEntity.arrivalSoundEventForTest());
    }

    @Test
    void carriageArrivalHologramTextIsLocalized() throws Exception {
        String zh = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/zh_cn.json"));
        String en = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/en_us.json"));

        assertTrue(zh.contains("\"entity.sailboatmod.carriage.arrived\""));
        assertTrue(zh.contains("\"已到站\""));
        assertTrue(en.contains("\"entity.sailboatmod.carriage.arrived\""));
        assertTrue(en.contains("\"Arrived\""));
    }
}
