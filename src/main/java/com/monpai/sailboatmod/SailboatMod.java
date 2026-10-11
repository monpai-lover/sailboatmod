package com.monpai.sailboatmod;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.monpai.sailboatmod.registry.ModBlocks;
import com.monpai.sailboatmod.registry.ModCreativeTabs;
import com.monpai.sailboatmod.registry.ModEntities;
import com.monpai.sailboatmod.registry.ModItems;
import com.monpai.sailboatmod.registry.ModMenus;
import com.monpai.sailboatmod.registry.ModSounds;
import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;

@Mod(SailboatMod.MODID)
public class SailboatMod {
    public static final String MODID = "sailboatmod";

    public SailboatMod(IEventBus modEventBus, ModContainer container) {
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModSounds.SOUND_EVENTS.register(modEventBus);
        ModNetwork.register(modEventBus);

        modEventBus.addListener(this::onEntityAttributeCreation);

        container.registerConfig(Type.COMMON, com.monpai.sailboatmod.ModConfig.COMMON_SPEC);

        // 注册 Forge 强加载票据校验回调:否则退档重进时 autopilot 载具的票据被 Forge 全丢弃→载具区块不加载→实体不 tick
        // →永不重申票据=死锁消失。回调保留票据让 Forge 重新加载载具区块。[[autopilot_chunk_ticket_persist]]
        com.monpai.sailboatmod.util.AutopilotChunkLoader.register(modEventBus);
    }

    private void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.RESIDENT.get(), ResidentEntity.createAttributes().build());
        event.put(ModEntities.SOLDIER.get(), com.monpai.sailboatmod.resident.entity.SoldierEntity.createSoldierAttributes().build());
    }
}
