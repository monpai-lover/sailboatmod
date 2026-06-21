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
                    // 2026-06: 船模型窄长,占地从 3x3 缩到 2x2(vanilla 实体 hitbox 只能方形)。[[sailboat_hitbox_narrow_long]]
                    .sized(2.0F, 1.6F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("sailboat")
    );

    public static final RegistryObject<EntityType<CarriageEntity>> CARRIAGE = ENTITY_TYPES.register(
            "carriage",
            () -> EntityType.Builder.<CarriageEntity>of(CarriageEntity::new, MobCategory.MISC)
                    // 2026-06 新模型:碰撞箱是正方底面(width×width),取罩住车身主体的值(轮子/辕杆超出部分不计碰撞,正常)。
                    // 原 3.0 太大(截图白框超模型一圈)。1.8 罩主体;游戏内觉得大/小再调此值与高度 1.6。
                    .sized(1.8F, 1.6F)
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
