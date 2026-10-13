package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.monpai.sailboatmod.client.ui.kit.ThemedContainerScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.menu.WarehouseMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Warehouse chest: 6×9 storage above the player inventory, drawn with the shared theme. */
public class WarehouseScreen extends ThemedContainerScreen<WarehouseMenu> {
    private static final int STORAGE_SLOTS = 54;

    public WarehouseScreen(WarehouseMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        UIElement body = Ui.column("sm-pane");
        body.addChild(slotGrid(menu.slots.subList(0, STORAGE_SLOTS), 9));
        body.addChild(Ui.divider());
        UIElement inventoryGrid = playerInventory(STORAGE_SLOTS);
        inventoryGrid.layout(l -> l.gapAll(Ui.GAP_XS));
        body.addChild(inventoryGrid);
        UIElement frame = window(title, body);
        // Ten fixed slot rows still fit the minimum 240-high Minecraft GUI.
        body.layout(l -> l.paddingAll(Ui.GAP_S).gapAll(Ui.GAP_XS));
        install(frame);
        bindAllSlots();
    }
}
