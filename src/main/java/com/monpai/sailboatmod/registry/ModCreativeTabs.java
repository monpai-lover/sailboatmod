package com.monpai.sailboatmod.registry;

import com.monpai.sailboatmod.SailboatMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SailboatMod.MODID);

    public static final RegistryObject<CreativeModeTab> SAILBOAT_TAB = CREATIVE_MODE_TABS.register(
            "sailboat_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.sailboatmod.sailboat_tab"))
                    .icon(() -> ModItems.SAILBOAT_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.SAILBOAT_ITEM.get());
                        output.accept(ModItems.ROUTE_BOOK_ITEM.get());
                        output.accept(ModItems.DOCK_ITEM.get());
                        output.accept(ModItems.MARKET_ITEM.get());
                        output.accept(ModItems.TOWN_CORE_ITEM.get());
                        output.accept(ModItems.NATION_CORE_ITEM.get());
                        output.accept(ModItems.NATION_FLAG_ITEM.get());
                        output.accept(ModItems.TOWN_FLAG_ITEM.get());
                        output.accept(ModItems.BANK_ITEM.get());
                        output.accept(ModItems.BANK_CONSTRUCTOR_ITEM.get());
                    })
                    .build()
    );

    private ModCreativeTabs() {
    }
}