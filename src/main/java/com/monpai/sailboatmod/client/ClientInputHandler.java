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
import com.monpai.sailboatmod.entity.CarriageHitboxModel;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CarriageControlInputPacket;
import com.monpai.sailboatmod.network.packet.InteractCarriagePacket;
import com.monpai.sailboatmod.network.packet.OpenNationMenuPacket;
import com.monpai.sailboatmod.network.packet.OpenSailboatStoragePacket;
import com.monpai.sailboatmod.network.packet.SailboatControlInputPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

    /**
     * 整模型点击上车:vanilla 实体拾取只用单一正方 AABB,点不到车头辕杆/车尾/车篷(露在实体 AABB 外)。
     * 这里对马车「整模型子框」(CarriageHitboxModel)做客户端 raytrace——右键时若射线命中子框且没有更近的
     * 阻挡(方块/别的实体),就掐断 vanilla 默认行为 + 发包请求服务端 interact。命中正方框内时 vanilla 自己已能
     * 命中该马车 → 放行老路(不接管),避免双触发(见互斥不变量)。
     *
     * <p>用 InteractionKeyMappingTriggered(而非 PlayerInteractEvent.EntityInteract):后者只在 vanilla 已命中
     * 实体 AABB 后才 fire,对框外部位根本不触发。
     */
    @SubscribeEvent
    public static void onInteractionKeyTriggered(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return; // 只接管右键(use)。攻击/拾取键不处理。
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null) {
            return;
        }
        if (player.getVehicle() instanceof CarriageEntity) {
            return; // 已坐在马车上,右键不重新触发上车 raytrace。
        }

        float partialTick = mc.getFrameTime();
        Vec3 eye = player.getEyePosition(partialTick);
        double reach = player.getEntityReach();
        Vec3 end = eye.add(player.getViewVector(partialTick).scale(reach));
        AABB searchBox = new AABB(eye, end).inflate(1.0D);

        CarriageEntity hitCarriage = null;
        double bestSqr = Double.MAX_VALUE;
        for (CarriageEntity carriage : mc.level.getEntitiesOfClass(CarriageEntity.class, searchBox, CarriageEntity::isAlive)) {
            for (AABB box : CarriageHitboxModel.getInteractionBoxesWorld(carriage, partialTick)) {
                var clip = box.clip(eye, end);
                if (clip.isPresent()) {
                    double sqr = eye.distanceToSqr(clip.get());
                    if (sqr < bestSqr) {
                        bestSqr = sqr;
                        hitCarriage = carriage;
                    }
                }
            }
        }
        if (hitCarriage == null) {
            return; // 没命中任何马车子框,放行 vanilla。
        }

        // vanilla 当前瞄准结果:若它已命中本马车(站正方框内)→ 放行老路,不接管,防双触发。
        HitResult vanillaHit = mc.hitResult;
        if (vanillaHit != null && vanillaHit.getType() == HitResult.Type.ENTITY
                && vanillaHit instanceof net.minecraft.world.phys.EntityHitResult ehr
                && ehr.getEntity() == hitCarriage) {
            return;
        }
        // 若有更近的阻挡(方块/别的实体)挡在子框命中点之前 → 玩家瞄的是那个,不接管。
        if (vanillaHit != null && vanillaHit.getType() != HitResult.Type.MISS) {
            double vanillaSqr = eye.distanceToSqr(vanillaHit.getLocation());
            if (vanillaSqr < bestSqr) {
                Entity vanillaEntity = vanillaHit instanceof net.minecraft.world.phys.EntityHitResult e2 ? e2.getEntity() : null;
                if (vanillaEntity != hitCarriage) {
                    return;
                }
            }
        }

        // 接管:掐断 vanilla 默认右键,发包请求服务端 interact(服务端再做距离/owner 校验)。
        event.setSwingHand(true);
        event.setCanceled(true);
        ModNetwork.CHANNEL.sendToServer(new InteractCarriagePacket(hitCarriage.getId(), event.getHand()));
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
        ModNetwork.CHANNEL.sendToServer(new SailboatControlInputPacket(input, sailboat.getEngineGear().id));
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
