package com.monpai.sailboatmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.21.1 的 EntityRenderDispatcher.cameraOrientation() 与 LevelRenderer 的视图旋转互为共轭,所以浮字套用它之后,
 * 在视图空间里只剩缩放;原版名牌用 scale(0.025, -0.025, 0.025)。旧版的负 X 缩放会把文字镜像到背面而被剔除。
 */
class HologramBillboardTest {
    private static final List<Quaternionf> CAMERAS = List.of(
            new Quaternionf(),
            new Quaternionf().rotationYXZ((float) Math.PI, 0.0F, 0.0F),
            new Quaternionf().rotationYXZ(0.7F, -0.4F, 0.0F),
            new Quaternionf().rotationYXZ(-2.3F, 1.1F, 0.0F));

    @Test
    void textFacesTheCameraLikeVanillaNameTags() {
        for (Quaternionf camera : CAMERAS) {
            PoseStack poseStack = new PoseStack();
            HologramBillboard.faceCamera(poseStack, camera, 0.025F);
            Matrix4f view = new Matrix4f().rotation(new Quaternionf(camera).conjugate()).mul(poseStack.last().pose());

            Vector3f right = view.transformDirection(new Vector3f(1.0F, 0.0F, 0.0F));
            Vector3f down = view.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
            assertTrue(right.x > 0.0F, "text must read left to right on screen, camera " + camera);
            assertTrue(down.y < 0.0F, "text lines must go down the screen, camera " + camera);
            assertEquals(0.025F, right.length(), 1e-6F);
        }
    }

    @Test
    void noRendererKeepsTheOldMirroredBillboard() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/monpai/sailboatmod/client/renderer"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (source.contains("cameraOrientation()")) {
                    assertFalse(source.matches("(?s).*\\.scale\\(\\s*-[A-Z0-9_.]+F?\\s*,\\s*-.*"),
                            file + " still mirrors the billboard with a negative X scale");
                }
            }
        }
    }
}
