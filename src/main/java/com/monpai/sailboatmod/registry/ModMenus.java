package com.monpai.sailboatmod.registry;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.menu.BankMenu;
import com.monpai.sailboatmod.menu.DockMenu;
import com.monpai.sailboatmod.menu.MarketMenu;
import com.monpai.sailboatmod.menu.PostStationMenu;
import com.monpai.sailboatmod.menu.WarehouseMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, SailboatMod.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<DockMenu>> DOCK_MENU = MENUS.register(
            "dock_menu",
            () -> IMenuTypeExtension.create(DockMenu::new)
    );

    public static final DeferredHolder<MenuType<?>, MenuType<PostStationMenu>> POST_STATION_MENU = MENUS.register(
            "post_station_menu",
            () -> IMenuTypeExtension.create(PostStationMenu::new)
    );

    public static final DeferredHolder<MenuType<?>, MenuType<MarketMenu>> MARKET_MENU = MENUS.register(
            "market_menu",
            () -> IMenuTypeExtension.create(MarketMenu::new)
    );

    public static final DeferredHolder<MenuType<?>, MenuType<BankMenu>> BANK_MENU = MENUS.register(
            "bank_menu",
            () -> IMenuTypeExtension.create(BankMenu::new)
    );

    public static final DeferredHolder<MenuType<?>, MenuType<WarehouseMenu>> WAREHOUSE_MENU = MENUS.register(
            "warehouse_menu",
            () -> IMenuTypeExtension.create(WarehouseMenu::new)
    );

    private ModMenus() {
    }
}
