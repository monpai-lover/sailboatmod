package com.example.examplemod.registry;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.menu.DockMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, SailboatMod.MODID);

    public static final RegistryObject<MenuType<DockMenu>> DOCK_MENU = MENUS.register(
            "dock_menu",
            () -> IForgeMenuType.create(DockMenu::new)
    );

    private ModMenus() {
    }
}
