package com.monpai.sailboatmod.command;

import com.monpai.sailboatmod.command.GhostCleanupCommands.BlockProbe;
import com.monpai.sailboatmod.command.GhostCleanupCommands.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link GhostCleanupCommands#classify} 判定真值表(纯内存,不起服)。
 * 最重要保证:probe==UNVERIFIABLE 永远不能是 GHOST(磁盘读不到的绝不误删)。
 */
class GhostCleanupClassifyTest {

    @Test
    void noReference_alwaysOk() {
        // 无世界引用的记录不在职责内,无论后续如何都 OK。
        assertEquals(Verdict.OK, GhostCleanupCommands.classify(false, false, BlockProbe.UNVERIFIABLE));
        assertEquals(Verdict.OK, GhostCleanupCommands.classify(false, true, BlockProbe.ABSENT));
        assertEquals(Verdict.OK, GhostCleanupCommands.classify(false, true, BlockProbe.PRESENT));
    }

    @Test
    void dimUnresolved_skipped() {
        assertEquals(Verdict.SKIPPED_DIM, GhostCleanupCommands.classify(true, false, BlockProbe.ABSENT));
        assertEquals(Verdict.SKIPPED_DIM, GhostCleanupCommands.classify(true, false, BlockProbe.PRESENT));
    }

    @Test
    void present_ok() {
        assertEquals(Verdict.OK, GhostCleanupCommands.classify(true, true, BlockProbe.PRESENT));
    }

    @Test
    void absent_ghost() {
        // 确认不在(内存已加载读到别的 或 磁盘 NBT 已生成但该格不是)= 幽灵,可删。
        assertEquals(Verdict.GHOST, GhostCleanupCommands.classify(true, true, BlockProbe.ABSENT));
    }

    @Test
    void unverifiable_neverGhost() {
        // 铁律:磁盘读不到(从未生成/读失败)→ SKIPPED_UNLOADED,绝不 GHOST。
        assertEquals(Verdict.SKIPPED_UNLOADED, GhostCleanupCommands.classify(true, true, BlockProbe.UNVERIFIABLE));
    }

    @Test
    void ghostOnlyWhenRefDimAbsent_exhaustive() {
        // 穷举 hasRef × dimOk × probe(2×2×3=12),断言只有 (true,true,ABSENT) 是 GHOST,
        // 且 UNVERIFIABLE 任何情况都不是 GHOST。
        for (boolean hasRef : new boolean[] {false, true}) {
            for (boolean dimOk : new boolean[] {false, true}) {
                for (BlockProbe probe : BlockProbe.values()) {
                    Verdict v = GhostCleanupCommands.classify(hasRef, dimOk, probe);
                    boolean shouldBeGhost = hasRef && dimOk && probe == BlockProbe.ABSENT;
                    if (shouldBeGhost) {
                        assertEquals(Verdict.GHOST, v, hasRef + "," + dimOk + "," + probe);
                    } else {
                        assertNotEquals(Verdict.GHOST, v, hasRef + "," + dimOk + "," + probe);
                    }
                    if (probe == BlockProbe.UNVERIFIABLE) {
                        assertNotEquals(Verdict.GHOST, v, "UNVERIFIABLE must never be GHOST: " + hasRef + "," + dimOk);
                    }
                }
            }
        }
    }
}
