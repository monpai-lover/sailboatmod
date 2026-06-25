package com.monpai.sailboatmod.command;

import com.monpai.sailboatmod.command.GhostCleanupCommands.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link GhostCleanupCommands#classify} 判定真值表(纯内存,不起服)。
 * 最重要保证:chunkLoaded=false 永远不能是 GHOST(回档后未加载区块绝不误删)。
 */
class GhostCleanupClassifyTest {

    @Test
    void noReference_alwaysOk() {
        // 无世界引用的记录不在职责内,无论后续如何都 OK,绝不动。
        assertEquals(Verdict.OK, GhostCleanupCommands.classify(false, false, false, false));
        assertEquals(Verdict.OK, GhostCleanupCommands.classify(false, true, true, true));
        assertEquals(Verdict.OK, GhostCleanupCommands.classify(false, true, true, false));
    }

    @Test
    void dimUnresolved_skipped() {
        assertEquals(Verdict.SKIPPED_DIM, GhostCleanupCommands.classify(true, false, false, false));
        assertEquals(Verdict.SKIPPED_DIM, GhostCleanupCommands.classify(true, false, true, true));
    }

    @Test
    void chunkUnloaded_neverGhost() {
        // 铁律:有引用、维度解析成功,但区块未加载 → SKIPPED_UNLOADED,绝不 GHOST。
        assertEquals(Verdict.SKIPPED_UNLOADED, GhostCleanupCommands.classify(true, true, false, false));
        assertEquals(Verdict.SKIPPED_UNLOADED, GhostCleanupCommands.classify(true, true, false, true));
    }

    @Test
    void loadedAndPresent_ok() {
        assertEquals(Verdict.OK, GhostCleanupCommands.classify(true, true, true, true));
    }

    @Test
    void loadedAndAbsent_ghost() {
        // 唯一会判 GHOST 的组合:有引用 + 维度OK + 区块已加载 + 方块确认不在。
        assertEquals(Verdict.GHOST, GhostCleanupCommands.classify(true, true, true, false));
    }

    @Test
    void ghostRequiresAllFourConditions_exhaustive() {
        // 穷举 4 个布尔的全部 16 种组合,断言只有 (true,true,true,false) 才是 GHOST。
        for (int mask = 0; mask < 16; mask++) {
            boolean hasRef = (mask & 8) != 0;
            boolean dimOk = (mask & 4) != 0;
            boolean loaded = (mask & 2) != 0;
            boolean present = (mask & 1) != 0;
            Verdict v = GhostCleanupCommands.classify(hasRef, dimOk, loaded, present);
            boolean shouldBeGhost = hasRef && dimOk && loaded && !present;
            if (shouldBeGhost) {
                assertEquals(Verdict.GHOST, v, "mask=" + mask);
            } else {
                assertNotEquals(Verdict.GHOST, v, "mask=" + mask + " must not be GHOST");
            }
            // 额外铁律:未加载绝不 GHOST。
            if (!loaded) {
                assertNotEquals(Verdict.GHOST, v, "unloaded must never be GHOST, mask=" + mask);
            }
        }
    }
}
