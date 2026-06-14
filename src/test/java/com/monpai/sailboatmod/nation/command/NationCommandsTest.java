package com.monpai.sailboatmod.nation.command;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationCommandsTest {
    @Test
    void registersTopLevelTownUnclaimShortcut() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        NationCommands.register(dispatcher);

        CommandNode<CommandSourceStack> townUnclaim = dispatcher.getRoot().getChild("townunclaim");
        assertNotNull(townUnclaim);
        assertNotNull(townUnclaim.getCommand());

        ParseResults<CommandSourceStack> parsed = dispatcher.parse("townunclaim", null);
        assertFalse(parsed.getReader().canRead());
    }

    @Test
    void townUnclaimShortcutResolvesCurrentClaimTown() {
        NationSavedData data = new NationSavedData();
        UUID mayorUuid = UUID.randomUUID();
        data.putTown(new TownRecord("crimea", "alpha", "Crimea Port", mayorUuid, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putClaim(claim(12, -4, "alpha", "crimea"));

        String townId = NationCommands.resolveTownUnclaimTownIdForTest(data, "minecraft:overworld", new ChunkPos(12, -4));

        assertEquals("crimea", townId);
    }

    @Test
    void townUnclaimShortcutFallsBackToCapitalTownForLegacyNationClaim() {
        NationSavedData data = new NationSavedData();
        UUID mayorUuid = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha", "ALP", 0x112233, 0x445566, mayorUuid, 1L, "capital", "", NationRecord.noCorePos(), ""));
        data.putTown(new TownRecord("capital", "alpha", "Capital", mayorUuid, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putClaim(claim(2, 3, "alpha", ""));

        String townId = NationCommands.resolveTownUnclaimTownIdForTest(data, "minecraft:overworld", new ChunkPos(2, 3));

        assertEquals("capital", townId);
    }

    @Test
    void claimChangingCommandsSyncClaimHighlights() {
        assertTrue(NationCommands.shouldSyncClaimHighlightsAfterCommandForTest("nation_claim"));
        assertTrue(NationCommands.shouldSyncClaimHighlightsAfterCommandForTest("nation_unclaim"));
        assertTrue(NationCommands.shouldSyncClaimHighlightsAfterCommandForTest("townunclaim"));
        assertTrue(NationCommands.shouldSyncClaimHighlightsAfterCommandForTest("nation_color"));
    }

    private static NationClaimRecord claim(int chunkX, int chunkZ, String nationId, String townId) {
        return new NationClaimRecord(
                "minecraft:overworld",
                chunkX,
                chunkZ,
                nationId,
                townId,
                "member",
                "member",
                "member",
                "member",
                "member",
                "member",
                "member",
                1L
        );
    }
}
