package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.menu.DockMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class StandardDockScreen extends DockScreen<DockMenu> {
    public StandardDockScreen(DockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
