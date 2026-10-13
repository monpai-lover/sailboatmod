package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.client.ui.kit.PickListScreen;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadEditSelectionPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerSelectEditRoadPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Picks a built road to edit; sends {@link RoadPlannerSelectEditRoadPacket} as before. */
public class RoadPlannerEditSelectionScreen extends PickListScreen<OpenRoadEditSelectionPacket.Entry> {
    public RoadPlannerEditSelectionScreen(List<OpenRoadEditSelectionPacket.Entry> roads) {
        super(Component.translatable("screen.sailboatmod.ui.road.edit_title"),
                Component.translatable("screen.sailboatmod.ui.road.edit_pick"),
                Component.translatable("screen.sailboatmod.ui.road.no_editable_roads"), roads);
    }

    @Override
    protected Row describe(OpenRoadEditSelectionPacket.Entry entry) {
        String detail = Component.translatable("screen.sailboatmod.ui.road.width_value", entry.width()).getString()
                + " · " + entry.status()
                + (entry.legacyMigrated() ? " · " + Component.translatable("screen.sailboatmod.ui.road.legacy").getString() : "");
        return new Row(new ItemStack(Items.DIRT_PATH),
                Component.literal(entry.sourceName() + " → " + entry.targetName()),
                Component.literal(detail),
                Component.literal(entry.lengthBlocks() + "m"));
    }

    @Override
    protected void pick(OpenRoadEditSelectionPacket.Entry entry) {
        if (entry == null || entry.roadId().isBlank()) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new RoadPlannerSelectEditRoadPacket(entry.roadId()));
        onClose();
    }
}
