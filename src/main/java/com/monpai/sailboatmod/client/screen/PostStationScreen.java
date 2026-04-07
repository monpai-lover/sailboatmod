package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.menu.PostStationMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class PostStationScreen extends DockScreen<PostStationMenu> {
    public PostStationScreen(PostStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    public static PostStationScreen create(PostStationMenu menu, Inventory inventory, Component title) {
        return new PostStationScreen(menu, inventory, title);
    }

    @Override
    protected String screenKeyPrefix() {
        return "screen.sailboatmod.post_station";
    }

    @Override
    protected String defaultFacilityNameKey() {
        return "block.sailboatmod.post_station";
    }

    @Override
    protected TransportTerminalKind terminalKind() {
        return TransportTerminalKind.POST_STATION;
    }

    @Override
    protected boolean useLandMinimapPalette() {
        return true;
    }
}
