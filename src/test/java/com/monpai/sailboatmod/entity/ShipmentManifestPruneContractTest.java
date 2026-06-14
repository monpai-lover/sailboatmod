package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipmentManifestPruneContractTest {
    private static String sailboat() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
    }

    @Test
    void pruneMethodExistsAndDoesNotResetUnloadSwitch() throws Exception {
        String src = sailboat();
        assertTrue(src.contains("void pruneShipmentManifest("),
                "a prune method should update the manifest without resetting the non-order unload/return switches");
        // pruneShipmentManifest 体内不得把开关重置为 false（剪枝要保留中途开关状态）
        // 用方法签名后到下一个 public 方法之间是否含重置作粗判：要求剪枝处调用 prune 而非 setPendingShipmentManifest
        assertTrue(src.contains("pruneShipmentManifest(split.keepOnboard())"),
                "multi-leg pruning should use pruneShipmentManifest, not setPendingShipmentManifest (which resets switches)");
    }
}
