package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.nation.NationEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationEventsPrivateCargoProtectionTest {
    @TempDir Path dataDirectory;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void breakingDockKeepsPrivateRecoveryCargoUntilClaimed(boolean operator) throws Exception {
        var player = PacketServerTestSupport.player(dataDirectory, "alpha");
        player.operator = operator;
        var level = (PacketServerTestSupport.TestLevel) player.level();
        PrivateCargoDock dock = PacketServerTestSupport.allocate(PrivateCargoDock.class);
        dock.privateCargo = true;
        level.blockEntities = Map.of(BlockPos.ZERO, dock);
        var event = new BlockEvent.BreakEvent(level, BlockPos.ZERO, Blocks.STONE.defaultBlockState(), player);

        NationEvents.onBlockBreak(event);

        assertTrue(event.isCanceled(), "ordinary breaking must not erase seller-owned recovery cargo");
        assertEquals("block.sailboatmod.dock.private_cargo_break_denied",
                ((net.minecraft.network.chat.contents.TranslatableContents) player.lastMessage.getContents()).getKey());
    }

    @Test
    void dockCanBeBrokenAfterPrivateRecoveryCargoIsClaimed() throws Exception {
        var player = PacketServerTestSupport.player(dataDirectory, "alpha");
        player.operator = true;
        var level = (PacketServerTestSupport.TestLevel) player.level();
        PrivateCargoDock dock = PacketServerTestSupport.allocate(PrivateCargoDock.class);
        level.blockEntities = Map.of(BlockPos.ZERO, dock);
        var event = new BlockEvent.BreakEvent(level, BlockPos.ZERO, Blocks.STONE.defaultBlockState(), player);

        NationEvents.onBlockBreak(event);

        assertFalse(event.isCanceled(), "empty recovery custody should preserve the existing break permissions");
    }

    @Test
    void explosionProtectsOnlyDockWithPrivateRecoveryCargo() throws Exception {
        var player = PacketServerTestSupport.player(dataDirectory, "alpha");
        var level = (PacketServerTestSupport.TestLevel) player.level();
        BlockPos emptyDockPos = new BlockPos(1, 0, 0);
        BlockPos ordinaryPos = new BlockPos(2, 0, 0);
        PrivateCargoDock privateDock = PacketServerTestSupport.allocate(PrivateCargoDock.class);
        privateDock.privateCargo = true;
        PrivateCargoDock emptyDock = PacketServerTestSupport.allocate(PrivateCargoDock.class);
        level.blockEntities = Map.of(BlockPos.ZERO, privateDock, emptyDockPos, emptyDock);
        List<BlockPos> affected = new ArrayList<>(List.of(BlockPos.ZERO, emptyDockPos, ordinaryPos));
        var event = new ExplosionEvent.Detonate(level, null, List.of()) {
            @Override public List<BlockPos> getAffectedBlocks() { return affected; }
        };

        NationEvents.onExplosionDetonate(event);

        assertEquals(List.of(emptyDockPos, ordinaryPos), affected);
    }

    private static final class PrivateCargoDock extends DockBlockEntity {
        private boolean privateCargo;

        private PrivateCargoDock() { super(BlockPos.ZERO, Blocks.STONE.defaultBlockState()); }

        @Override public boolean hasPrivateRecoveryCargo() { return privateCargo; }
    }
}
