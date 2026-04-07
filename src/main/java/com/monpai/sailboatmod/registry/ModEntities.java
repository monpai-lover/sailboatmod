package com.monpai.sailboatmod.registry;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.entity.SoldierEntity;
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

    public static final RegistryObject<EntityType<CarriageEntity>> CARRIAGE = ENTITY_TYPES.register(
            "carriage",
            () -> EntityType.Builder.<CarriageEntity>of(CarriageEntity::new, MobCategory.MISC)
                    .sized(3.0F, 1.6F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("carriage")
    );

    public static final RegistryObject<EntityType<ResidentEntity>> RESIDENT = ENTITY_TYPES.register(
            "resident",
            () -> EntityType.Builder.<ResidentEntity>of(ResidentEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(10)
                    .updateInterval(3)
                    .build("resident")
    );

    public static final RegistryObject<EntityType<SoldierEntity>> SOLDIER = ENTITY_TYPES.register(
            "soldier",
            () -> EntityType.Builder.<SoldierEntity>of(SoldierEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(10)
                    .updateInterval(3)
                    .build("soldier")
    );

    private ModEntities() {
    }
}
