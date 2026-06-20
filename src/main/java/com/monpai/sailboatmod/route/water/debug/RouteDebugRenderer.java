package com.monpai.sailboatmod.route.water.debug;

import com.monpai.sailboatmod.roadplanner.map.RoadMapColorizer;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSample;
import com.monpai.sailboatmod.roadplanner.map.RoadMapServerColumnSampler;
import com.monpai.sailboatmod.route.water.debug.RouteDebugDtos.StageResult;
import com.monpai.sailboatmod.route.water.debug.RouteDebugDtos.ViewTransform;
import com.monpai.sailboatmod.route.water.RealBlockWaterMap;
import com.monpai.sailboatmod.route.water.WaterColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/** Pure rendering: terrain base + water overlay + path lines -> BufferedImage. Self-contained world->pixel transform. */
public final class RouteDebugRenderer {
    private RouteDebugRenderer() {
    }

    /**
     * Bounding box = AABB of a..b expanded by margin. Rendered at <b>exact 1:1</b> (one pixel = one block, scale=1).
     * {@code maxPixels} is only a safety clamp: if a span exceeds it, the view is centered on the route midpoint and
     * cropped to maxPixels on that axis (avoids OOM on extreme routes), still 1:1.
     */
    public static ViewTransform computeView(BlockPos a, BlockPos b, int margin, int maxPixels) {
        int minX = Math.min(a.getX(), b.getX()) - margin;
        int maxX = Math.max(a.getX(), b.getX()) + margin;
        int minZ = Math.min(a.getZ(), b.getZ()) - margin;
        int maxZ = Math.max(a.getZ(), b.getZ()) + margin;
        int spanX = Math.max(1, maxX - minX);
        int spanZ = Math.max(1, maxZ - minZ);
        int cap = Math.max(1, maxPixels);
        if (spanX > cap) {
            int cx = (minX + maxX) / 2;
            minX = cx - cap / 2;
            spanX = cap;
        }
        if (spanZ > cap) {
            int cz = (minZ + maxZ) / 2;
            minZ = cz - cap / 2;
            spanZ = cap;
        }
        // scale fixed at 1: exact pixel-per-block.
        return new ViewTransform(minX, minZ, spanX, spanZ, 1, spanX, spanZ);
    }

    /** Main-thread: sample one real-terrain column per pixel (unloaded chunks -> transparent). */
    public static BufferedImage renderTerrain(ServerLevel level, ViewTransform v) {
        BufferedImage img = new BufferedImage(v.width(), v.height(), BufferedImage.TYPE_INT_ARGB);
        RoadMapServerColumnSampler sampler = new RoadMapServerColumnSampler(level);
        RoadMapColorizer colorizer = new RoadMapColorizer();
        for (int py = 0; py < v.height(); py++) {
            int worldZ = v.minZ() + py * v.scale();
            for (int px = 0; px < v.width(); px++) {
                int worldX = v.minX() + px * v.scale();
                RoadMapColumnSample sample = sampler.sample(worldX, worldZ);
                img.setRGB(px, py, colorizer.color(sample));
            }
        }
        return img;
    }

    /** Worker-thread (map already enableOnDemand): navigable cells get a translucent blue overlay. */
    public static void overlayWater(BufferedImage img, RealBlockWaterMap map, ViewTransform v) {
        for (int py = 0; py < v.height(); py++) {
            int worldZ = v.minZ() + py * v.scale();
            for (int px = 0; px < v.width(); px++) {
                int worldX = v.minX() + px * v.scale();
                WaterColumn col = map.sample(worldX, worldZ);
                if (col != null && col.passable()) {
                    img.setRGB(px, py, blendOver(img.getRGB(px, py), 0x4D3AA0FF)); // ~30% blue
                }
            }
        }
    }

    /**
     * One stage image: terrain base (copied) + water overlay + previous-stage path (faint) + this-stage path
     * (bright, optionally numbered) + start/goal markers.
     */
    public static BufferedImage renderStage(BufferedImage terrain, ViewTransform v, RealBlockWaterMap map,
                                            StageResult prev, StageResult cur, BlockPos start, BlockPos goal,
                                            java.util.List<RouteDebugDtos.NodeDiag> markLandNodes) {
        BufferedImage img = copy(terrain);
        overlayWater(img, map, v);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (prev != null) {
            drawPolyline(g, v, prev.path(), new Color(150, 150, 150), 1.0f, 0.4f);
        }
        if (cur != null) {
            drawPolyline(g, v, cur.path(), new Color(40, 230, 90), 2.0f, 0.65f);
        }
        g.setComposite(AlphaComposite.SrcOver);
        // Only mark nodes that verified as land (NBT=water but real=land) with their verified index; nothing else.
        if (markLandNodes != null) {
            drawLandNodes(g, v, markLandNodes);
        }
        if (start != null) {
            g.setColor(Color.CYAN);
            g.fillOval(v.px(start.getX()) - 3, v.pz(start.getZ()) - 3, 7, 7);
        }
        if (goal != null) {
            g.setColor(Color.MAGENTA);
            g.fillOval(v.px(goal.getX()) - 3, v.pz(goal.getZ()) - 3, 7, 7);
        }
        g.dispose();
        return img;
    }

    /** Overview image: all four path layers stacked on one terrain+water base. */
    public static BufferedImage renderOverview(BufferedImage terrain, ViewTransform v, RealBlockWaterMap map,
                                               StageResult coarse, StageResult fine, StageResult smooth,
                                               StageResult verified, BlockPos start, BlockPos goal) {
        BufferedImage img = copy(terrain);
        overlayWater(img, map, v);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (coarse != null) {
            drawPolyline(g, v, coarse.path(), new Color(160, 160, 160), 1.0f, 0.5f);
        }
        if (fine != null) {
            drawPolyline(g, v, fine.path(), new Color(255, 220, 40), 1.5f, 0.5f);
        }
        if (smooth != null) {
            drawPolyline(g, v, smooth.path(), new Color(255, 140, 0), 2.0f, 0.55f);
        }
        if (verified != null) {
            drawPolyline(g, v, verified.path(), new Color(40, 230, 90), 2.5f, 0.65f);
        }
        g.setComposite(AlphaComposite.SrcOver);
        if (start != null) {
            g.setColor(Color.CYAN);
            g.fillOval(v.px(start.getX()) - 3, v.pz(start.getZ()) - 3, 7, 7);
        }
        if (goal != null) {
            g.setColor(Color.MAGENTA);
            g.fillOval(v.px(goal.getX()) - 3, v.pz(goal.getZ()) - 3, 7, 7);
        }
        g.dispose();
        return img;
    }

    private static void drawPolyline(Graphics2D g, ViewTransform v, List<BlockPos> path, Color color,
                                     float width, float alpha) {
        if (path == null || path.size() < 2) {
            return;
        }
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g.setColor(color);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 1; i < path.size(); i++) {
            BlockPos p0 = path.get(i - 1);
            BlockPos p1 = path.get(i);
            g.drawLine(v.px(p0.getX()), v.pz(p0.getZ()), v.px(p1.getX()), v.pz(p1.getZ()));
        }
    }

    /** Mark only the nodes that verified as land (NBT=water but real=land) in red, labelled with their index. */
    private static void drawLandNodes(Graphics2D g, ViewTransform v, java.util.List<RouteDebugDtos.NodeDiag> nodes) {
        if (nodes == null) {
            return;
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        for (RouteDebugDtos.NodeDiag d : nodes) {
            if (!(d.nbtWater() && !d.realNavigable())) {
                continue; // only land-crossing nodes
            }
            int x = v.px(d.pos().getX());
            int z = v.pz(d.pos().getZ());
            g.setColor(Color.RED);
            g.fillOval(x - 3, z - 3, 7, 7);
            g.setColor(Color.WHITE);
            g.drawString("#" + d.index(), x + 4, z - 4);
        }
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(src, 0, 0, null);
        return out;
    }

    /** src-over-dst alpha composite (src carries alpha). */
    private static int blendOver(int dst, int src) {
        double sa = ((src >>> 24) & 0xFF) / 255.0;
        int sr = (src >> 16) & 0xFF;
        int sg = (src >> 8) & 0xFF;
        int sb = src & 0xFF;
        int dr = (dst >> 16) & 0xFF;
        int dg = (dst >> 8) & 0xFF;
        int db = dst & 0xFF;
        int da = (dst >>> 24) & 0xFF;
        int r = (int) Math.round(sr * sa + dr * (1 - sa));
        int g = (int) Math.round(sg * sa + dg * (1 - sa));
        int b = (int) Math.round(sb * sa + db * (1 - sa));
        int a = Math.max(da, (int) Math.round(sa * 255));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
