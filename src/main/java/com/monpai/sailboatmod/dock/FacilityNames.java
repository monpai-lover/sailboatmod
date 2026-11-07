package com.monpai.sailboatmod.dock;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/** Default names are resolved on read; this refreshes only already-loaded endpoint projections. */
public final class FacilityNames {
    private FacilityNames() { }
    public static boolean validOverride(String value) {
        return value != null && value.trim().length() <= 64
                && value.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT);
    }
    public static void refreshTown(MinecraftServer server, String townId) {
        if (server == null || townId == null || townId.isBlank()) return;
        for (var endpoint : TerminalNetworkSavedData.get(server.overworld()).all()) {
            if (!townId.equals(endpoint.townId())) continue;
            var dimension = ResourceLocation.tryParse(endpoint.dimId());
            if (dimension == null) continue;
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            if (level == null || !level.hasChunkAt(endpoint.pos())) continue;
            if (level.getBlockEntity(endpoint.pos()) instanceof DockBlockEntity terminal
                    && terminal.followsTownName() && terminal.getCustomDockName().isBlank()
                    && townId.equals(terminal.getTownId())) {
                terminal.setChanged();
                level.sendBlockUpdated(endpoint.pos(), terminal.getBlockState(), terminal.getBlockState(), 3);
            }
        }
    }
}
