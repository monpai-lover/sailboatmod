package com.monpai.sailboatmod.menu;

import com.monpai.sailboatmod.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;

public class PostStationMenu extends DockMenu {
    public PostStationMenu(int containerId, Inventory inventory, net.minecraft.network.FriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBlockPos());
    }

    public PostStationMenu(int containerId, Inventory inventory, BlockPos dockPos) {
        super(ModMenus.POST_STATION_MENU.get(), containerId, inventory, dockPos);
    }
}
