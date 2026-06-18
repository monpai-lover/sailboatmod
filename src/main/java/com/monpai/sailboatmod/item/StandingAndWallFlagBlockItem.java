package com.monpai.sailboatmod.item;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class StandingAndWallFlagBlockItem extends StandingAndWallBlockItem {
    private final Block standingBlock;
    private final Block wallBlock;
    private final String descriptionKey;

    public StandingAndWallFlagBlockItem(Block standingBlock, Block wallBlock, Properties properties, String descriptionKey) {
        super(standingBlock, wallBlock, properties, Direction.DOWN);
        this.standingBlock = standingBlock;
        this.wallBlock = wallBlock;
        this.descriptionKey = descriptionKey == null ? "" : descriptionKey.trim();
    }

    /**
     * 显式选择放置变体,不依赖父类 attachmentDirection 语义:
     * 点击水平面(墙的侧面)→ 优先墙挂变体;墙挂放不下或点的是上/下面 → 站立变体。
     * 修复「贴墙放却得到站立形态」。
     */
    @Override
    @Nullable
    protected BlockState getPlacementState(BlockPlaceContext context) {
        Direction clickedFace = context.getClickedFace();
        if (clickedFace.getAxis().isHorizontal()) {
            BlockState wallState = wallBlock.getStateForPlacement(context);
            if (wallState != null && wallState.canSurvive(context.getLevel(), context.getClickedPos())) {
                return wallState;
            }
        }
        BlockState standingState = standingBlock.getStateForPlacement(context);
        if (standingState != null && standingState.canSurvive(context.getLevel(), context.getClickedPos())) {
            return standingState;
        }
        return null;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        if (!descriptionKey.isBlank()) {
            tooltip.add(Component.translatable(descriptionKey));
        }
    }
}
