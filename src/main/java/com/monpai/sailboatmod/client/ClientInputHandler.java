package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.screen.CarriageInfoScreen;
import com.monpai.sailboatmod.client.screen.SailboatInfoScreen;
import com.monpai.sailboatmod.entity.CarriageDriveInput;
import com.monpai.sailboatmod.entity.CarriageLandDriveModel;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.client.texture.NationFlagTextureCache;
import com.monpai.sailboatmod.entity.SailboatControlInput;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.entity.TransportEntity;
import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.registry.ModItems;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CarriageControlInputPacket;
import com.monpai.sailboatmod.network.packet.OpenNationMenuPacket;
import com.monpai.sailboatmod.network.packet.OpenSailboatStoragePacket;
import com.monpai.sailboatmod.network.packet.SailboatControlInputPacket;
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
    private static float carriageTurnAngle = 0.0F;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        syncCarriageControls(minecraft, player);
        syncSailboatControls(minecraft, player);
        if (minecraft.screen != null) {
            return;
        }

        if (ClientKeyMappings.OPEN_NATION_MENU.consumeClick()) {
            NationClientHooks.openCachedOrEmpty();
            ModNetwork.CHANNEL.sendToServer(new OpenNationMenuPacket());
            return;
        }

        if (player.getVehicle() instanceof TransportEntity && minecraft.options.keyInventory.consumeClick()) {
            ModNetwork.CHANNEL.sendToServer(new OpenSailboatStoragePacket());
        }

        if (ClientKeyMappings.OPEN_SAILBOAT_INFO.consumeClick()) {
            if (player.getVehicle() instanceof CarriageEntity carriage) {
                minecraft.setScreen(new CarriageInfoScreen(carriage));
            } else if (player.getVehicle() instanceof SailboatEntity sailboat) {
                minecraft.setScreen(new SailboatInfoScreen(sailboat));
            }
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
            ConstructorClientHooks.syncHeldSettings(held);
            var type = BankConstructorItem.getSelectedType(held);
            player.displayClientMessage(Component.translatable("item.sailboatmod.structure.selected", Component.translatable(type.translationKey())), true);
            event.setCanceled(true);
        } else if (alt) {
            // Alt+scroll: cycle adjust mode
            BankConstructorItem.cycleAdjustMode(held, delta);
            ConstructorClientHooks.syncHeldSettings(held);
            var mode = BankConstructorItem.getAdjustMode(held);
            player.displayClientMessage(Component.translatable("item.sailboatmod.constructor.mode_changed", Component.translatable(mode.translationKey())), true);
            event.setCanceled(true);
        } else {
            // Normal scroll in non-BUILD mode: adjust value
            var mode = BankConstructorItem.getAdjustMode(held);
            if (mode != BankConstructorItem.AdjustMode.BUILD) {
                BankConstructorItem.adjustValue(held, delta);
                ConstructorClientHooks.syncHeldSettings(held);
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

    private static void syncCarriageControls(Minecraft minecraft, LocalPlayer player) {
        if (!(player.getVehicle() instanceof CarriageEntity carriage)) {
            carriageTurnAngle = 0.0F;
            return;
        }
        boolean controlsEnabled = minecraft.screen == null;
        double yawRad = carriage.getYRot() * (Math.PI / 180.0D);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);
        float currentSpeed = (float) ((carriage.getDeltaMovement().x * dirX + carriage.getDeltaMovement().z * dirZ) * 20.0D);
        CarriageDriveInput input = createCarriageControlInput(
                controlsEnabled,
                minecraft.options.keyUp.isDown(),
                minecraft.options.keyDown.isDown(),
                minecraft.options.keyLeft.isDown(),
                minecraft.options.keyRight.isDown(),
                carriageTurnAngle,
                currentSpeed
        );
        carriageTurnAngle = input.targetTurnAngle();
        carriage.applyClientControlInput(input);
        ModNetwork.CHANNEL.sendToServer(new CarriageControlInputPacket(
                input.acceleration(),
                input.turn(),
                input.targetTurnAngle(),
                input.power()
        ));
    }

    private static void syncSailboatControls(Minecraft minecraft, LocalPlayer player) {
        if (!(player.getVehicle() instanceof SailboatEntity sailboat)) {
            return;
        }
        boolean controlsEnabled = minecraft.screen == null;
        SailboatControlInput input = createSailboatControlInput(
                controlsEnabled,
                minecraft.options.keyUp.isDown(),
                minecraft.options.keyDown.isDown(),
                minecraft.options.keyLeft.isDown(),
                minecraft.options.keyRight.isDown()
        );
        // 客户端本地应用：喂进 manualInputState 供本地预测物理读取（两端跑同一份）。照抄 carriage 143。
        sailboat.applyClientControlInput(input);
        ModNetwork.CHANNEL.sendToServer(new SailboatControlInputPacket(input));
    }

    private static CarriageDriveInput createCarriageControlInput(boolean controlsEnabled,
                                                                boolean forwardDown,
                                                                boolean backDown,
                                                                boolean leftDown,
                                                                boolean rightDown,
                                                                float previousTurnAngle,
                                                                float currentSpeed) {
        CarriageDriveInput.AccelerationDirection acceleration = CarriageDriveInput.AccelerationDirection.NONE;
        if (controlsEnabled && forwardDown && backDown) {
            acceleration = CarriageDriveInput.AccelerationDirection.CHARGING;
        } else if (controlsEnabled && forwardDown) {
            acceleration = CarriageDriveInput.AccelerationDirection.FORWARD;
        } else if (controlsEnabled && backDown) {
            acceleration = CarriageDriveInput.AccelerationDirection.REVERSE;
        }

        CarriageDriveInput.TurnDirection turn = CarriageDriveInput.TurnDirection.FORWARD;
        if (controlsEnabled && leftDown && !rightDown) {
            turn = CarriageDriveInput.TurnDirection.LEFT;
        } else if (controlsEnabled && rightDown && !leftDown) {
            turn = CarriageDriveInput.TurnDirection.RIGHT;
        }

        float targetTurnAngle = CarriageLandDriveModel.targetTurnAngle(previousTurnAngle, turn, currentSpeed, false);
        float power = controlsEnabled && acceleration != CarriageDriveInput.AccelerationDirection.NONE ? 1.0F : 0.0F;
        return new CarriageDriveInput(acceleration, turn, targetTurnAngle, power);
    }

    static CarriageDriveInput carriageControlInputForTest(boolean controlsEnabled,
                                                         boolean forwardDown,
                                                         boolean backDown,
                                                         boolean leftDown,
                                                         boolean rightDown,
                                                         float previousTurnAngle,
                                                         float currentSpeed) {
        return createCarriageControlInput(
                controlsEnabled,
                forwardDown,
                backDown,
                leftDown,
                rightDown,
                previousTurnAngle,
                currentSpeed
        );
    }

    static SailboatControlInput sailboatControlInputForTest(boolean controlsEnabled,
                                                           boolean forwardDown,
                                                           boolean backDown,
                                                           boolean leftDown,
                                                           boolean rightDown) {
        return createSailboatControlInput(
                controlsEnabled,
                forwardDown,
                backDown,
                leftDown,
                rightDown
        );
    }

    private static SailboatControlInput createSailboatControlInput(boolean controlsEnabled,
                                                                  boolean forwardDown,
                                                                  boolean backDown,
                                                                  boolean leftDown,
                                                                  boolean rightDown) {
        return SailboatControlInput.fromKeys(controlsEnabled, forwardDown, backDown, leftDown, rightDown);
    }
}
