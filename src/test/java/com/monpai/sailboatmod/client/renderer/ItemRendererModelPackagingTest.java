package com.monpai.sailboatmod.client.renderer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ItemRendererModelPackagingTest {
    @Test
    void itemRenderersKeepGeoModelsInRendererPackage() throws IOException {
        assertModelIsNotExternalTopLevelClass(Path.of("src/main/java/com/monpai/sailboatmod/client/renderer/SailboatItemRenderer.java"));
        assertModelIsNotExternalTopLevelClass(Path.of("src/main/java/com/monpai/sailboatmod/client/renderer/CarriageItemRenderer.java"));
    }

    private static void assertModelIsNotExternalTopLevelClass(Path rendererSource) throws IOException {
        String source = Files.readString(rendererSource);

        assertFalse(source.contains("com.monpai.sailboatmod.client.model."),
                rendererSource + " should keep the item GeoModel next to the item renderer");
    }
}
