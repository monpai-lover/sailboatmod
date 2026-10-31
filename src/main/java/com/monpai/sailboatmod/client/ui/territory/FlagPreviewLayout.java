package com.monpai.sailboatmod.client.ui.territory;

/** Pure geometry for the flag preview; usable without a client or an OpenGL context. */
final class FlagPreviewLayout {
    private FlagPreviewLayout() {
    }

    record Bounds(int x, int y, int width, int height) {
    }

    static Bounds fit(int x, int y, int width, int height, int textureWidth, int textureHeight) {
        if (width < 2 || height < 2) {
            return null;
        }
        int sourceWidth = textureWidth > 0 ? textureWidth : 64;
        int sourceHeight = textureHeight > 0 ? textureHeight : 32;
        double scale = Math.min(1.0D,
                Math.min(width / (double) sourceWidth, height / (double) sourceHeight));
        int drawWidth = Math.min(width, Math.max(1, (int) Math.round(sourceWidth * scale)));
        int drawHeight = Math.min(height, Math.max(1, (int) Math.round(sourceHeight * scale)));
        return new Bounds(x + (width - drawWidth) / 2, y + (height - drawHeight) / 2,
                drawWidth, drawHeight);
    }
}
