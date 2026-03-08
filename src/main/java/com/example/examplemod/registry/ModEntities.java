package com.example.examplemod.registry;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.entity.SailboatEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, SailboatMod.MODID);

    public static final RegistryObject<EntityType<SailboatEntity>> SAILBOAT = ENTITY_TYPES.register(
            "sailboat",
            () -> EntityType.Builder.<SailboatEntity>of(SailboatEntity::new, MobCategory.MISC)
                    .sized(3.0F, 1.6F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("sailboat")
    );

    private ModEntities() {
    }
}
