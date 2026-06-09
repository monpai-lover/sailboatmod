package com.monpai.sailboatmod.registry;

import com.monpai.sailboatmod.SailboatMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, SailboatMod.MODID);

    public static final RegistryObject<SoundEvent> CARRIAGE_PLACE = register("entity.carriage.place");
    public static final RegistryObject<SoundEvent> CARRIAGE_ATTACH = register("entity.carriage.attach");
    public static final RegistryObject<SoundEvent> CARRIAGE_DETACH = register("entity.carriage.detach");
    public static final RegistryObject<SoundEvent> CARRIAGE_MOVE = register("entity.carriage.move");
    public static final RegistryObject<SoundEvent> CARRIAGE_MOVE_STONE = register("entity.carriage.move.stone");
    public static final RegistryObject<SoundEvent> CARRIAGE_MOVE_GRASS = register("entity.carriage.move.grass");
    public static final RegistryObject<SoundEvent> CARRIAGE_MOVE_SAND = register("entity.carriage.move.sand");
    public static final RegistryObject<SoundEvent> CARRIAGE_MOVE_SNOW = register("entity.carriage.move.snow");
    public static final RegistryObject<SoundEvent> CARRIAGE_MOVE_WOOD = register("entity.carriage.move.wood");
    public static final RegistryObject<SoundEvent> CARRIAGE_MOVE_GROUND = register("entity.carriage.move.ground");

    private ModSounds() {
    }

    private static RegistryObject<SoundEvent> register(String path) {
        return SOUND_EVENTS.register(path, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(SailboatMod.MODID, path)));
    }
}
