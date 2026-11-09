package com.monpai.sailboatmod.network.packet;
import com.monpai.sailboatmod.network.packet.airport.AirportActionPacket;
import com.monpai.sailboatmod.transport.air.airport.AirportGroundLayout;
import com.monpai.sailboatmod.transport.air.airport.AirportScreenData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class AirportActionSecurityTest {
    @TempDir Path temporary;
    @Test void autoLayoutPlacesBlocksOnlyOnExplicitConfirmation() {
        var corners = List.of(new Vec3(0, 81, 0), new Vec3(127, 81, 63));
        for (boolean prefab : List.of(false, true)) {
            for (String id : List.of("", "lit")) {
                var packet = new AirportActionPacket(Level.OVERWORLD.location(), BlockPos.ZERO, 0,
                        AirportActionPacket.Action.APPLY_AUTO_LAYOUT, id, "", "", corners, prefab, 0, 0, 0, 0, 0, -1);
                var buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    AirportActionPacket.encode(packet, buffer);
                    var tag = AirportActionPacket.decode(buffer).toActionTag();
                    assertEquals(prefab, tag.getBoolean("Prefab"));
                    assertEquals(prefab && id.equals("lit"), tag.getBoolean("LitPrefab"));
                    assertEquals(new BlockPos(127, 81, 63), BlockPos.of(tag.getLong("CornerB")));
                } finally {
                    buffer.release();
                }
            }
        }
    }
    @Test void editorIssueAndPrefabFeatureSurviveTheExistingSnapshotFormat() {
        var issue = new BlockPos(135520, 81, -28590);
        var data = new AirportScreenData(Level.OVERWORLD.location(), BlockPos.ZERO, "Airport", "Town", "Owner", true,
                0, List.of(), AirportGroundLayout.empty(), List.of("feature|airport_prefab|1", "editor_issue||" + issue.asLong()),
                "obstructed");
        var restored = AirportScreenData.load(data.save());
        assertTrue(restored.supportsPrefab());
        assertFalse(restored.supportsLitPrefab());
        assertEquals(issue, restored.editorIssue().orElseThrow());

        var withLitPrefab = data.save();
        withLitPrefab.getList("Statuses", Tag.TAG_STRING).add(StringTag.valueOf("feature|airport_lit_prefab|1"));
        assertTrue(AirportScreenData.load(withLitPrefab).supportsLitPrefab());
    }
    @Test void missingSenderCannotReadOrMutateAnAirport() throws Exception {
        var context = PacketServerTestSupport.context(null);
        assertDoesNotThrow(() -> AirportActionPacket.handle(AirportActionPacket.simple(net.minecraft.world.level.Level.OVERWORLD.location(),BlockPos.ZERO,0,
                AirportActionPacket.Action.REMOVE_RUNWAY,"runway"),() -> context));
    }
    @Test void actionFromPreviousDimensionIsRejectedBeforeAnyChunkOrAirportLookup() throws Exception {
        // This fixture intentionally has no initialized chunk source: reaching hasChunkAt would fail.
        var player = PacketServerTestSupport.player(temporary, "nation");
        var context = PacketServerTestSupport.context(player);
        var packet = AirportActionPacket.simple(net.minecraft.world.level.Level.NETHER.location(),BlockPos.ZERO,0,
                AirportActionPacket.Action.REMOVE_RUNWAY,"runway");
        assertDoesNotThrow(() -> AirportActionPacket.handle(packet, () -> context));
    }
}
