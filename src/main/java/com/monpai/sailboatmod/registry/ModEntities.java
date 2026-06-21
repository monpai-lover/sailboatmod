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
                    // 2026-06 碰撞箱:MC 实体 AABB 引擎硬限制只能正方(width×width),罩不住长方形马车(世界长~5.16×宽~2.12)。
                    // width 2.2≈真实车宽 2.12:贴车宽消除「左右假挡墙」(长期痛点);height 2.9 罩到车篷顶防站顶穿模。
                    // 长方向 2.2<5.16 罩不住,辕杆/车尾突出段靠 CarriageEntity.pushIntersectingEntities 软推补挡;
                    // 配合 CarriageEntity.canBeCollidedWith()=true 让此正方框对其他实体硬挡。点击靠子框 raytrace
                    // (见 CarriageHitboxModel + ClientInputHandler),不依赖此框。
                    .sized(2.2F, 2.9F)
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
