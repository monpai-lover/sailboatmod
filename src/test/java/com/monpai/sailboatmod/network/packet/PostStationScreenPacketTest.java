package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.dock.PostStationScreenData;
import com.monpai.sailboatmod.route.LandTransportNetworkService;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostStationScreenPacketTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void openPostStationPacketRoundTripsLandFields() {
        PostStationScreenData data = sampleData();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        OpenPostStationScreenPacket.encode(new OpenPostStationScreenPacket(data), buffer);
        OpenPostStationScreenPacket decoded = OpenPostStationScreenPacket.decode(buffer);

        assertEquals(data.stationPos(), decoded.data().stationPos());
        assertEquals("town-c", decoded.data().reachableTowns().get(0).townId());
        assertEquals(42, decoded.data().vehicles().get(0).entityId());
        assertTrue(decoded.data().autoReturnOnDispatch());
        assertEquals(2, decoded.data().selectedRouteWaypoints().size());
    }

    private static PostStationScreenData sampleData() {
        DockScreenData advanced = new DockScreenData(
                new BlockPos(1, 64, 1), "Station", "Owner", "owner-uuid",
                true, false, false, ItemStack.EMPTY, List.of("Legacy"), List.of("Len 10m"),
                0, List.of(new Vec3(1, 65, 1), new Vec3(3, 65, 1)),
                -12, 12, -8, 8, List.of(42), List.of("Carriage"), List.of(Vec3.ZERO),
                0, List.of(), 0, List.of(), 0, List.of(), List.of());
        return new PostStationScreenData(
                new BlockPos(1, 64, 1), "Station", "town-a", "Alpha", true,
                List.of(new PostStationScreenData.ReachableTownEntry(
                        "town-c", "Cedar", new BlockPos(100, 64, 0), "Cedar Station",
                        100, 20, LandTransportNetworkService.RouteSource.ROAD_GRAPH.name(),
                        List.of("Bridge"), "Alpha -> Cedar")),
                0,
                List.of(new PostStationScreenData.VehicleEntry(42, "Carriage", Vec3.ZERO, "IDLE", true, true, "")),
                0,
                true,
                true,
                new PostStationScreenData.RouteSummary("Alpha -> Cedar", 100, 20, List.of("Bridge")),
                List.of(new Vec3(1, 65, 1), new Vec3(100, 65, 0)),
                advanced
        );
    }
}
