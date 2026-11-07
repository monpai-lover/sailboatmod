package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.RegisteredVehicleControllerScreen;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.transport.control.ControllerScreenData;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid=SailboatMod.MODID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.GAME)
public final class RegisteredVehicleControllerClientHooks {
    private static AirSurfaceClientSelection.Pending pendingSurface;
    private static RegisteredVehicleControllerScreen pickingScreen;
    private static Level levelIdentity;
    private static int suppressUseTick=-1;
    private RegisteredVehicleControllerClientHooks() {}

    public static void openOrUpdate(ControllerScreenData data, boolean repaint) {
        var client = Minecraft.getInstance();
        observeLevel(client.level);
        if (client.screen instanceof RegisteredVehicleControllerScreen screen
                && screen.registrationId().equals(data.registrationId())&&screen.controllerPos().equals(data.controllerPos()))
            screen.update(data, repaint);
        else if(pendingSurface!=null&&pickingScreen!=null&&pendingSurface.registrationId().equals(data.registrationId())
                &&pendingSurface.controllerPos().equals(data.controllerPos()))pickingScreen.update(data,false);
        else if (repaint) client.setScreen(new RegisteredVehicleControllerScreen(data));
    }

    public static void beginSurfacePick(RegisteredVehicleControllerScreen screen,AirSurfaceClientSelection.Config config) {
        var client=Minecraft.getInstance();observeLevel(client.level);
        if(client.level==null||client.player==null||client.screen!=screen)return;
        pendingSurface=new AirSurfaceClientSelection.Pending(screen.controllerPos(),screen.registrationId(),client.level.dimension().location().toString(),config);
        pickingScreen=screen;client.setScreen(null);
        client.player.displayClientMessage(Component.translatable("screen.sailboatmod.controller.air_surface_pick_hint"),true);
    }

    /** Cancel before useItemOn prediction: a late RightClickBlock cancellation still sends vanilla's use packet. */
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void onSurfacePick(InputEvent.InteractionKeyMappingTriggered event) {
        if(!event.isUseItem())return;
        var client=Minecraft.getInstance();observeLevel(client.level);
        if(client.player!=null&&client.player.tickCount==suppressUseTick){event.setCanceled(true);event.setSwingHand(false);return;}
        if(pendingSurface==null||client.screen!=null)return;
        event.setCanceled(true);event.setSwingHand(false);
        if(client.level==null||client.player==null){clear();return;}
        if(!(client.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return;
        // Sable's clip returns the original plot BlockHitResult; use its BlockPos without a world-space conversion.
        var packet=AirSurfaceClientSelection.select(pendingSurface,client.level.dimension().location().toString(),hit.getBlockPos());
        if(packet.isEmpty()){clear();return;}
        var screen=pickingScreen;pendingSurface=null;pickingScreen=null;suppressUseTick=client.player.tickCount;
        ModNetwork.CHANNEL.sendToServer(packet.orElseThrow());
        client.setScreen(screen);
    }

    @SubscribeEvent public static void onScreenOpening(ScreenEvent.Opening event) {
        if(pendingSurface==null||event.getNewScreen()==null)return;
        var screen=pickingScreen;pendingSurface=null;pickingScreen=null;
        if(event.getNewScreen() instanceof PauseScreen&&event.getCurrentScreen()==null)event.setNewScreen(screen);
    }
    @SubscribeEvent public static void onLevelUnload(LevelEvent.Unload event){if(event.getLevel()==levelIdentity)clear();}
    @SubscribeEvent public static void onClientTick(ClientTickEvent.Post event){observeLevel(Minecraft.getInstance().level);}
    @SubscribeEvent public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event){clear();}
    private static void observeLevel(Level level){if(levelIdentity!=level){clear();levelIdentity=level;}}
    private static void clear(){pendingSurface=null;pickingScreen=null;levelIdentity=null;suppressUseTick=-1;}
}
