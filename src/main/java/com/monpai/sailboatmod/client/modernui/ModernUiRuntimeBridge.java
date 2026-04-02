package com.monpai.sailboatmod.client.modernui;

import com.monpai.sailboatmod.client.BankOverviewConsumer;
import com.monpai.sailboatmod.client.DockOverviewConsumer;
import com.monpai.sailboatmod.client.MarketOverviewConsumer;
import com.monpai.sailboatmod.client.NationOverviewConsumer;
import com.monpai.sailboatmod.client.TownOverviewConsumer;
import com.monpai.sailboatmod.client.TradeOverviewConsumer;
import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import com.monpai.sailboatmod.registry.ModMenus;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.mc.MuiScreen;
import icyllis.modernui.mc.forge.MenuScreenFactory;
import icyllis.modernui.mc.forge.MuiForgeApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;

public final class ModernUiRuntimeBridge {
    private ModernUiRuntimeBridge() {
    }

    public static void registerMenuScreens() {
        MenuScreens.register(ModMenus.BANK_MENU.get(), MenuScreenFactory.create(BankModernFragment::new));
        MenuScreens.register(ModMenus.DOCK_MENU.get(), MenuScreenFactory.create(DockModernFragment::new));
        MenuScreens.register(ModMenus.MARKET_MENU.get(), MenuScreenFactory.create(MarketModernFragment::new));
    }

    public static void openNationScreen(NationOverviewData data) {
        MuiForgeApi.openScreen(new NationModernFragment(data));
    }

    public static void openTownScreen(TownOverviewData data) {
        MuiForgeApi.openScreen(new TownModernFragment(data));
    }

    public static void openTradeScreen(TradeScreenData data) {
        MuiForgeApi.openScreen(new TradeModernFragment(data));
    }

    public static void openAutoRouteDockSelectionScreen(BlockPos sourceDockPos, java.util.List<AvailableDockEntry> docks) {
        MuiForgeApi.openScreen(new AutoRouteDockSelectionModernFragment(sourceDockPos, docks));
    }

    public static void openRouteBookNameScreen(InteractionHand hand, String suggestedName) {
        MuiForgeApi.openScreen(new RouteBookNameModernFragment(hand, suggestedName));
    }

    public static void openSailboatInfoScreen(SailboatEntity sailboat) {
        MuiForgeApi.openScreen(new SailboatInfoModernFragment(sailboat));
    }

    public static void openStructureSelectionScreen(ItemStack stack) {
        MuiForgeApi.openScreen(new StructureSelectionModernFragment(stack));
    }

    public static boolean updateCurrentMarket(MarketOverviewData data) {
        Fragment fragment = currentFragment();
        if (fragment instanceof MarketOverviewConsumer consumer && consumer.isForMarket(data.marketPos())) {
            consumer.updateData(data);
            return true;
        }
        return false;
    }

    public static boolean updateCurrentDock(DockScreenData data) {
        Fragment fragment = currentFragment();
        if (fragment instanceof DockOverviewConsumer consumer && consumer.isForDock(data.dockPos())) {
            consumer.updateData(data);
            return true;
        }
        return false;
    }

    public static boolean updateCurrentBank() {
        Fragment fragment = currentFragment();
        if (fragment instanceof BankOverviewConsumer consumer) {
            consumer.refreshData();
            return true;
        }
        return false;
    }

    public static boolean updateCurrentTrade(TradeScreenData data) {
        Fragment fragment = currentFragment();
        if (fragment instanceof TradeOverviewConsumer consumer && consumer.isForTrade(data.targetNationId())) {
            consumer.updateData(data);
            return true;
        }
        return false;
    }

    public static boolean updateCurrentNation(NationOverviewData data) {
        Fragment fragment = currentFragment();
        if (fragment instanceof NationOverviewConsumer consumer) {
            consumer.updateData(data);
            return true;
        }
        return false;
    }

    public static boolean updateCurrentTown(TownOverviewData data) {
        Fragment fragment = currentFragment();
        if (fragment instanceof TownOverviewConsumer consumer) {
            consumer.updateData(data);
            return true;
        }
        return false;
    }

    private static Fragment currentFragment() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MuiScreen muiScreen) {
            return muiScreen.getFragment();
        }
        return null;
    }
}
