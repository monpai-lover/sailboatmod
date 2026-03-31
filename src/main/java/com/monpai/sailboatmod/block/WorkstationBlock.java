package com.monpai.sailboatmod.block;

import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.service.BuildingConstructionService;
import com.monpai.sailboatmod.resident.service.StructureBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class WorkstationBlock extends Block {
    private final Profession profession;
    private final int capacity;

    public WorkstationBlock(Properties properties, Profession profession, int capacity) {
        super(properties);
        this.profession = profession;
        this.capacity = capacity;
    }

    public Profession getProfession() { return profession; }
    public int getCapacity() { return capacity; }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            int layers = StructureBuilder.getTotalLayers("workstation");
            // Workstation core is at (2,1,2) in NBT, adjust origin so core aligns with placed block
            BlockPos origin = pos.offset(-2, -1, -2);
            BuildingConstructionService.startConstruction(level, "", "workstation", origin, layers);
        }
    }
}
