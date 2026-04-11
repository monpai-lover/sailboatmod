package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.client.renderer.CarriageItemRenderer;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.entity.CarriageWoodType;
import com.monpai.sailboatmod.registry.ModEntities;
import com.monpai.sailboatmod.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

public class CarriageItem extends SailboatItem {
    public static final String TAG_WOOD_TYPE = "WoodType";

    public CarriageItem(Properties properties) {
        super(properties);
    }

    public static CarriageWoodType getWoodType(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return CarriageWoodType.fromSerialized(tag == null ? null : tag.getString(TAG_WOOD_TYPE));
    }

    public static void setWoodType(ItemStack stack, CarriageWoodType woodType) {
        stack.getOrCreateTag().putString(TAG_WOOD_TYPE, woodType.serializedName());
    }

    public static CarriageWoodType cycleWoodType(ItemStack stack) {
        CarriageWoodType next = getWoodType(stack).next();
        setWoodType(stack, next);
        return next;
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private CarriageItemRenderer renderer;

            @Override
            public @Nullable net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new CarriageItemRenderer();
                }
                return renderer;
            }
        });
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive()) {
            CarriageWoodType next = cycleWoodType(itemstack);
            if (!level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("item.sailboatmod.carriage.wood.selected", Component.translatable(next.translationKey())),
                        true
                );
            }
            return InteractionResultHolder.sidedSuccess(itemstack, level.isClientSide());
        }
        HitResult hitResult = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);

        if (hitResult.getType() == HitResult.Type.MISS) {
            return InteractionResultHolder.pass(itemstack);
        }

        Vec3 lookVec = player.getViewVector(1.0F);
        List<Entity> entities = level.getEntities(player,
                player.getBoundingBox().expandTowards(lookVec.scale(5.0D)).inflate(1.0D),
                entity -> entity.isPickable() && !entity.isSpectator());

        Vec3 eyePosition = player.getEyePosition();
        for (Entity entity : entities) {
            AABB inflated = entity.getBoundingBox().inflate(entity.getPickRadius());
            if (inflated.contains(eyePosition)) {
                return InteractionResultHolder.pass(itemstack);
            }
        }

        if (hitResult.getType() == HitResult.Type.BLOCK && hitResult instanceof BlockHitResult blockHitResult) {
            BlockPos placePos = blockHitResult.getBlockPos().above();
            if (!level.getFluidState(placePos).isEmpty()) {
                return InteractionResultHolder.fail(itemstack);
            }

            CarriageEntity carriage = new CarriageEntity(ModEntities.CARRIAGE.get(), level);
            carriage.setPos(placePos.getX() + 0.5D, placePos.getY() + 0.05D, placePos.getZ() + 0.5D);
            carriage.setYRot(player.getYRot());

            if (!level.noCollision(carriage, carriage.getBoundingBox())) {
                return InteractionResultHolder.fail(itemstack);
            }

            if (!level.isClientSide) {
                carriage.initializeOwnerIfAbsent(player);
                carriage.setWoodType(getWoodType(itemstack));
                if (itemstack.hasCustomHoverName()) {
                    carriage.setCustomName(itemstack.getHoverName());
                }
                level.addFreshEntity(carriage);
                level.playSound(null, carriage.blockPosition(), ModSounds.CARRIAGE_PLACE.get(), SoundSource.NEUTRAL, 0.9F, 1.0F);
                if (!player.getAbilities().instabuild) {
                    itemstack.shrink(1);
                }
            }

            player.awardStat(Stats.ITEM_USED.get(this));
            return InteractionResultHolder.sidedSuccess(itemstack, level.isClientSide());
        }

        return InteractionResultHolder.pass(itemstack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.sailboatmod.carriage.wood", Component.translatable(getWoodType(stack).translationKey())));
        tooltip.add(Component.translatable("item.sailboatmod.carriage.tip.cycle"));
    }
}
