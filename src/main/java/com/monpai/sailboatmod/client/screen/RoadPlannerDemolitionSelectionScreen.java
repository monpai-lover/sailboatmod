package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.client.ui.kit.PickListScreen;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadDemolitionSelectionPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerSelectDemolitionRoadPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Picks a built road to demolish; sends {@link RoadPlannerSelectDemolitionRoadPacket} as before. */
public class RoadPlannerDemolitionSelectionScreen extends PickListScreen<OpenRoadDemolitionSelectionPacket.Entry> {
    public RoadPlannerDemolitionSelectionScreen(List<OpenRoadDemolitionSelectionPacket.Entry> roads) {
        super(Component.translatable("screen.sailboatmod.ui.road.demolish_title"),
                Component.translatable("screen.sailboatmod.ui.road.demolish_pick"),
                Component.translatable("screen.sailboatmod.ui.road.no_built_roads"), roads);
    }

    @Override
    protected Row describe(OpenRoadDemolitionSelectionPacket.Entry entry) {
        return new Row(new ItemStack(Items.IRON_PICKAXE),
                Component.literal(entry.sourceName() + " → " + entry.targetName()),
                Component.literal(entry.sourceType()),
                Component.literal(entry.lengthBlocks() + "m"));
    }

    @Override
    protected void pick(OpenRoadDemolitionSelectionPacket.Entry entry) {
        if (entry == null || entry.roadId().isBlank()) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new RoadPlannerSelectDemolitionRoadPacket(entry.roadId()));
        onClose();
    }
}
