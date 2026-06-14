package com.monpai.sailboatmod.client.renderer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerPreviewRendererSourceTest {
    private static final Path SOURCE = Path.of("src/main/java/com/monpai/sailboatmod/client/renderer/RoadPlannerPreviewRenderer.java");

    @Test
    void roadPlannerHudRendersAfterFullGuiFrame() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("RenderGuiEvent.Post"),
                "road planner HUD should render after the full GUI frame so Xaero overlays cannot cover it");
        assertTrue(source.contains("EventPriority.LOWEST"),
                "road planner HUD should subscribe at LOWEST priority to draw after normal overlay handlers");
        assertFalse(source.contains("RenderGuiOverlayEvent.Post event"),
                "road planner HUD should not render inside individual GUI overlay passes");
    }
}
