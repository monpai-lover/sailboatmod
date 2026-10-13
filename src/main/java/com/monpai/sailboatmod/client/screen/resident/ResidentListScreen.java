package com.monpai.sailboatmod.client.screen.resident;

import com.monpai.sailboatmod.client.ui.kit.PickListScreen;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** A town's residents; clicking one opens its card. */
public class ResidentListScreen extends PickListScreen<ResidentRecord> {
    private final String townId;

    public ResidentListScreen(String townId, List<ResidentRecord> residents) {
        super(Component.translatable("sailboatmod.gui.resident.title"),
                Component.translatable("screen.sailboatmod.ui.resident.count", residents == null ? 0 : residents.size()),
                Component.translatable("screen.sailboatmod.ui.resident.none"), residents);
        this.townId = townId;
    }

    public String townId() {
        return townId;
    }

    @Override
    protected Row describe(ResidentRecord resident) {
        return new Row(new ItemStack(Items.PLAYER_HEAD), Component.literal(resident.name()),
                Component.literal(resident.profession().displayName()), Component.literal(resident.happiness() + "%"));
    }

    @Override
    protected void pick(ResidentRecord resident) {
        Minecraft.getInstance().setScreen(new ResidentDetailScreen(resident));
    }
}
