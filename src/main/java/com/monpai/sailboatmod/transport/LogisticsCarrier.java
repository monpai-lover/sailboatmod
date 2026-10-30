package com.monpai.sailboatmod.transport;

import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.route.LandTransportNetworkService;
import com.monpai.sailboatmod.route.RouteDefinition;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Logistics capabilities shared by entity vehicles and registered physical structures. */
public interface LogisticsCarrier {
    enum Kind {
        WATER,
        LAND,
        AIR
    }

    default Kind transportKind() {
        return Kind.WATER;
    }

    /** Stable shipment mode, with airframes overriding this to keep their subtype on the map. */
    default String transportMode() {
        return switch (transportKind()) {
            case WATER -> "PORT";
            case LAND -> "LAND";
            case AIR -> "AIRPLANE";
        };
    }

    default String providerId() {
        return "entity";
    }

    default String dimensionId() {
        return "minecraft:overworld";
    }

    default TransportRef getTransportRef() {
        return new TransportRef(providerId(), dimensionId(), getTransportUuid());
    }

    int getTransportId();

    UUID getTransportUuid();

    Component getTransportName();

    Vec3 transportPosition();

    default AABB transportBounds() {
        return new AABB(transportPosition(), transportPosition()).inflate(0.5D);
    }

    boolean isTransportAlive();

    default double distanceToTransportSqr(Entity entity) {
        return entity == null
                ? Double.MAX_VALUE
                : transportPosition().distanceToSqr(entity.position());
    }

    void openStorage(Player player);

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

    void setPendingMarketDelivery(
            @Nullable String recipientName,
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

    @Nullable
    CargoLoadReceipt loadCargoForDispatch(List<ItemStack> cargo);

    void setRouteCatalog(
            List<RouteDefinition> routes, int preferredIndex, @Nullable BlockPos dockPos);

    /** Dispatch supplies its already-selected terminal before order commit and route start. */
    default void setDispatchDestination(@Nullable BlockPos terminal) { }

    default void setLandTransportTask(
            LandTransportNetworkService.LandRoutePlan plan,
            boolean autoReturn,
            CarriageEntity.TransportTaskKind kind) {}

    boolean startAutopilotFromRouteStart();

    void controlAutopilot(Player player, SailboatEntity.AutopilotControlAction action);
}
