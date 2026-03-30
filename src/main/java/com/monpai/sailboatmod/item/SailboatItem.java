package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.registry.ModEntities;
import com.monpai.sailboatmod.client.renderer.SailboatItemRenderer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.function.Consumer;

public class SailboatItem extends Item implements GeoItem {
    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);

    public SailboatItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);
        HitResult hitResult = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);

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
            SailboatEntity sailboat = new SailboatEntity(ModEntities.SAILBOAT.get(), level);
            Vec3 hitPos = blockHitResult.getLocation();
            sailboat.setPos(hitPos.x, hitPos.y + 0.2D, hitPos.z);
            sailboat.setYRot(player.getYRot());

            if (!level.noCollision(sailboat, sailboat.getBoundingBox())) {
                return InteractionResultHolder.fail(itemstack);
            }

            if (!level.isClientSide) {
                level.addFreshEntity(sailboat);
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
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animatableCache;
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private SailboatItemRenderer renderer;

            @Override
            public @Nullable net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new SailboatItemRenderer();
                }
                return renderer;
            }
        });
    }
}
