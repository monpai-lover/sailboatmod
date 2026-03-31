package com.monpai.sailboatmod.block;

import com.monpai.sailboatmod.resident.service.BuildingConstructionService;
import com.monpai.sailboatmod.resident.service.StructureBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class SchoolBlock extends Block {
    private static final int STUDENT_CAPACITY = 10;
    private static final int DAYS_TO_GRADUATE = 7;

    public SchoolBlock(Properties properties) {
        super(properties);
    }

    public int getStudentCapacity() { return STUDENT_CAPACITY; }
    public int getDaysToGraduate() { return DAYS_TO_GRADUATE; }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            int layers = StructureBuilder.getTotalLayers("school");
            BuildingConstructionService.startConstruction(level, "", "school", pos, layers);
        }
    }
}
