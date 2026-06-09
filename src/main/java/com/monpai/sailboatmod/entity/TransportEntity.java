package com.monpai.sailboatmod.entity;

import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.route.LandTransportNetworkService;
import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public interface TransportEntity {
    default Entity asEntity() {
        return (Entity) this;
    }

    default int getTransportId() {
        return asEntity().getId();
    }

    default UUID getTransportUuid() {
        return asEntity().getUUID();
    }

    default Component getTransportName() {
        return asEntity().getName();
    }

    default Vec3 transportPosition() {
        return asEntity().position();
    }

    default boolean isTransportAlive() {
        return asEntity().isAlive();
    }

    default double distanceToTransportSqr(Entity entity) {
        return entity == null ? Double.MAX_VALUE : asEntity().distanceToSqr(entity);
    }

    void openStorage(Player player);

    boolean requestSeat(Player player, int requestedSeat);

    int getSeatFor(Entity passenger);

    boolean isCaptain(Player player);

    boolean isAutopilotActive();

    boolean isAutopilotPaused();

    String getSelectedRouteName();

    String getAutopilotRouteName();

    String getOwnerName();

    String getOwnerUuid();

    int getRentalPrice();

    void setRentalPrice(int newPrice);

    boolean isAvailableForRent();

    boolean isOwnedBy(Player player);

    void setPendingShipper(@Nullable String shipperName);

    void setPendingMarketDelivery(@Nullable String recipientName,
                                  @Nullable String recipientUuid,
                                  @Nullable String purchaseOrderId,
                                  @Nullable String shippingOrderId);

    void clearPendingMarketDelivery();

    void setPendingShipmentManifest(List<ShipmentManifestEntry> manifest);

    List<ShipmentManifestEntry> getPendingShipmentManifest();

    void setAllowNonOrderAutoReturn(boolean allow);

    void setAllowNonOrderAutoUnload(boolean allow);

    boolean hasCargo();

    boolean canLoadCargo(List<ItemStack> cargo);

    boolean loadCargo(List<ItemStack> cargo);

    List<ItemStack> unloadAllCargo();

    void setRouteCatalog(List<RouteDefinition> routes, int preferredIndex, @Nullable BlockPos dockPos);

    default void setLandTransportTask(LandTransportNetworkService.LandRoutePlan plan,
                                      boolean autoReturn,
                                      CarriageEntity.TransportTaskKind kind) {
    }

    boolean startAutopilotFromRouteStart();

    void controlAutopilot(Player player, SailboatEntity.AutopilotControlAction action);
}
