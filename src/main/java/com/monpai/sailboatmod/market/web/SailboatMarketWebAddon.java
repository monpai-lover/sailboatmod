package com.monpai.sailboatmod.market.web;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import com.monpai.sailboatmod.market.web.map.MarketWebMapSettings;

@Mod(SailboatMarketWebAddon.MODID)
public final class SailboatMarketWebAddon {
    public static final String MODID = "sailboatmarketweb";

    public SailboatMarketWebAddon(IEventBus eventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, MarketWebMapSettings.COMMON_SPEC,
                "sailboatmod-marketweb-common.toml");
        eventBus.addListener((ModConfigEvent.Loading event) -> refreshSettings(event.getConfig()));
        eventBus.addListener((ModConfigEvent.Reloading event) -> refreshSettings(event.getConfig()));
    }

    private static void refreshSettings(ModConfig config) {
        if (config.getSpec() == MarketWebMapSettings.COMMON_SPEC) MarketWebMapSettings.reloadFromConfig();
    }
}
