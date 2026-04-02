package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.modernui.ModernUiRuntimeBridge;
import com.monpai.sailboatmod.client.screen.SailboatInfoScreen;
import com.monpai.sailboatmod.client.texture.NationFlagTextureCache;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.registry.ModItems;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.OpenNationMenuPacket;
import com.monpai.sailboatmod.network.packet.OpenSailboatStoragePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientInputHandler {
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.screen != null) {
            return;
        }

        if (ClientKeyMappings.OPEN_NATION_MENU.consumeClick()) {
            NationClientHooks.openCachedOrEmpty();
            ModNetwork.CHANNEL.sendToServer(new OpenNationMenuPacket());
            return;
        }

        if (player.getVehicle() instanceof SailboatEntity && minecraft.options.keyInventory.consumeClick()) {
            ModNetwork.CHANNEL.sendToServer(new OpenSailboatStoragePacket());
        }

        if (player.getVehicle() instanceof SailboatEntity sailboat && ClientKeyMappings.OPEN_SAILBOAT_INFO.consumeClick()) {
            if (ModernUiCompat.isAvailable()) {
                ModernUiRuntimeBridge.openSailboatInfoScreen(sailboat);
                return;
            }
            minecraft.setScreen(new SailboatInfoScreen(sailboat));
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null) return;
        ItemStack held = player.getMainHandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get()) ? player.getMainHandItem()
                : player.getOffhandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get()) ? player.getOffhandItem() : ItemStack.EMPTY;
        if (held.isEmpty()) return;

        int delta = event.getScrollDelta() > 0 ? 1 : -1;
        boolean shift = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        boolean alt = net.minecraft.client.gui.screens.Screen.hasAltDown();

        if (shift) {
            // Shift+scroll: cycle structure type
            BankConstructorItem.cycleStructure(held, delta);
            var type = BankConstructorItem.getSelectedType(held);
            player.displayClientMessage(Component.translatable("item.sailboatmod.structure.selected", Component.translatable(type.translationKey())), true);
            event.setCanceled(true);
        } else if (alt) {
            // Alt+scroll: cycle adjust mode
            BankConstructorItem.cycleAdjustMode(held, delta);
            var mode = BankConstructorItem.getAdjustMode(held);
            player.displayClientMessage(Component.translatable("item.sailboatmod.constructor.mode_changed", Component.translatable(mode.translationKey())), true);
            event.setCanceled(true);
        } else {
            // Normal scroll in non-BUILD mode: adjust value
            var mode = BankConstructorItem.getAdjustMode(held);
            if (mode != BankConstructorItem.AdjustMode.BUILD) {
                BankConstructorItem.adjustValue(held, delta);
                int oY = BankConstructorItem.getOffsetY(held);
                int oX = BankConstructorItem.getOffsetX(held);
                int rot = BankConstructorItem.getRotation(held);
                player.displayClientMessage(Component.translatable("item.sailboatmod.constructor.adjusted", oX, oY, rot * 90), true);
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        NationClientHooks.clearCache();
        TownClientHooks.clearCache();
        NationFlagTextureCache.clearCache();
    }

    private ClientInputHandler() {
    }
}
