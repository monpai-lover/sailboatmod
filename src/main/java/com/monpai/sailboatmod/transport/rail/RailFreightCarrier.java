package com.monpai.sailboatmod.transport.rail;

import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.market.logistics.TransportModes;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.transport.CargoLoadReceipt;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.TransportRegistry;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Market candidate identity. Rail operations require the explicit freight receipt, never a generic road task. */
final class RailFreightCarrier implements LogisticsCarrier {
    private final ServerLevel level;
    private final UUID trainId;
    private final UUID owner;
    private final int runtimeId = TransportRegistry.allocateRuntimeId();
    private RailFreightNative.TrainView view;
    private @Nullable RailFreightSavedData.Job job;

    RailFreightCarrier(ServerLevel level, RailFreightNative.TrainView view, @Nullable RailFreightSavedData.Job job) {
        this.level = level;
        this.trainId = view.id();
        this.owner = view.owner();
        update(view, job);
    }

    void update(RailFreightNative.TrainView view, @Nullable RailFreightSavedData.Job job) {
        this.view = view;
        this.job = job;
    }

    ServerLevel level() { return level; }
    UUID ownerId() { return owner; }
    @Override public Kind transportKind() { return Kind.LAND; }
    @Override public String providerId() { return RailFreightService.PROVIDER_ID; }
    @Override public String transportMode() { return TransportModes.RAIL; }
    @Override public String dimensionId() { return level.dimension().location().toString(); }
    @Override public int getTransportId() { return runtimeId; }
    @Override public UUID getTransportUuid() { return trainId; }
    @Override public Component getTransportName() { return Component.literal(view.name()); }
    @Override public Vec3 transportPosition() { return view.position() == null ? Vec3.ZERO : view.position(); }
    @Override public AABB transportBounds() { return view.bounds() == null ? LogisticsCarrier.super.transportBounds() : view.bounds(); }
    @Override public boolean isTransportAlive() { return view.usable() && view.position() != null; }

    @Override
    public void openStorage(Player player) {
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.serverLevel() == level && isOwnedBy(player)) {
            RailFreightService.openStorage(serverPlayer, trainId);
        }
    }

    @Override public boolean isAutopilotActive() { return job != null || view.destination() != null || view.hasSchedule() && !view.completed(); }
    @Override public boolean isAutopilotPaused() { return view.paused() && !view.completed(); }
    @Override public String getSelectedRouteName() { return job == null ? "" : job.route().routeName(); }
    @Override public String getAutopilotRouteName() { return getSelectedRouteName(); }

    @Override
    public String getOwnerName() {
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(owner);
        return online == null ? "" : online.getGameProfile().getName();
    }

    @Override public String getOwnerUuid() { return owner.toString(); }
    @Override public boolean isOwnedBy(Player player) { return player != null && owner.equals(player.getUUID()); }
    @Override public int getRentalPrice() { return 0; }
    @Override public void setRentalPrice(int newPrice) {}
    @Override public boolean isAvailableForRent() { return false; }
    @Override public void setPendingShipper(@Nullable String name) {}
    @Override public void setPendingMarketDelivery(@Nullable String name, @Nullable String recipient,
                                                    @Nullable String purchase, @Nullable String shipment) {}
    @Override public void clearPendingMarketDelivery() {}
    @Override public void setPendingShipmentManifest(List<ShipmentManifestEntry> manifest) {}
    @Override public List<ShipmentManifestEntry> getPendingShipmentManifest() { return job == null ? List.of() : job.manifest(); }
    @Override public void setAllowNonOrderAutoReturn(boolean allow) {}
    @Override public void setAllowNonOrderAutoUnload(boolean allow) {}
    @Override public boolean hasCargo() { return view.cargoUsed() > 0; }
    @Override public boolean canLoadCargo(List<ItemStack> cargo) { return RailFreightService.canLoad(this, cargo); }
    @Override public boolean loadCargo(List<ItemStack> cargo) { return false; }
    @Override public List<ItemStack> unloadAllCargo() { return List.of(); }
    @Override public @Nullable CargoLoadReceipt loadCargoForDispatch(List<ItemStack> cargo) { return null; }
    @Override public void setRouteCatalog(List<RouteDefinition> routes, int preferredIndex, @Nullable BlockPos dockPos) {}
    @Override public boolean startAutopilotFromRouteStart() { return false; }
    @Override public void controlAutopilot(Player player, SailboatEntity.AutopilotControlAction action) {}
}
