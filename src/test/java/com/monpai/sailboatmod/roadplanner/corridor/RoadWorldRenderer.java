package com.monpai.sailboatmod.roadplanner.corridor;

import com.monpai.sailboatmod.roadplanner.structure.RoadCenterlinePoint;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** Draws a {@link SyntheticRoadWorld} so generated roads can be inspected as images. */
final class RoadWorldRenderer {
    private RoadWorldRenderer() {
    }

    private static Color color(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.GRASS_BLOCK) return new Color(0x5E9D3B);
        if (block == Blocks.DIRT) return new Color(0x8A6242);
        if (block == Blocks.STONE) return new Color(0x808080);
        if (block == Blocks.SAND) return new Color(0xDCCF9A);
        if (block == Blocks.WATER) return new Color(0x3F76E4);
        if (block == Blocks.SMOOTH_STONE || block == Blocks.SMOOTH_STONE_SLAB) return new Color(0xC8C8C8);
        if (block == Blocks.STONE_BRICKS || block == Blocks.STONE_BRICK_SLAB) return new Color(0x6F6F78);
        if (block == Blocks.STONE_BRICK_WALL || block == Blocks.COBBLESTONE_WALL) return new Color(0x5A5A64);
        if (block == Blocks.COBBLESTONE || block == Blocks.COBBLESTONE_SLAB) return new Color(0x77736D);
        if (block == Blocks.LANTERN) return new Color(0xFFD24A);
        if (block == Blocks.OAK_PLANKS || block == Blocks.OAK_SLAB || block == Blocks.OAK_FENCE) return new Color(0xB8945F);
        if (block == Blocks.OAK_LOG) return new Color(0x6B5333);
        if (block == Blocks.SPRUCE_PLANKS || block == Blocks.SPRUCE_SLAB || block == Blocks.SPRUCE_FENCE) return new Color(0x7A5A34);
        if (block == Blocks.SPRUCE_LOG) return new Color(0x3B2A16);
        return new Color(0xFF00FF);
    }

    private static boolean solid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    /** Looking straight down, with relief shading. One pixel block is {@code scale} pixels. */
    static void topDown(SyntheticRoadWorld world, List<RoadCenterlinePoint> centerline,
                        int minX, int minZ, int maxX, int maxZ, int scale, File file) throws IOException {
        BufferedImage image = new BufferedImage((maxX - minX + 1) * scale, (maxZ - minZ + 1) * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int top = topY(world, x, z);
                BlockState state = world.block(x, top, z);
                int west = topY(world, x - 1, z);
                int north = topY(world, x, z - 1);
                double shade = 1.0 + 0.06 * Math.max(-3, Math.min(3, (top - west) + (top - north)));
                g.setColor(scaled(color(state), shade));
                g.fillRect((x - minX) * scale, (z - minZ) * scale, scale, scale);
            }
        }
        g.dispose();
        file.getParentFile().mkdirs();
        ImageIO.write(image, "png", file);
    }

    /** The top-down view with routes drawn over it, each as {@code {rgb, x0, z0, x1, z1, ...}}. */
    static void topDownWithRoutes(SyntheticRoadWorld world, int minX, int minZ, int maxX, int maxZ, int scale,
                                  File file, int[]... routes) throws IOException {
        topDown(world, List.of(), minX, minZ, maxX, maxZ, scale, file);
        BufferedImage image = ImageIO.read(file);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int[] route : routes) {
            g.setColor(new Color(route[0]));
            g.setStroke(new java.awt.BasicStroke(Math.max(2f, scale * 0.9f)));
            for (int i = 3; i + 1 < route.length; i += 2) {
                g.drawLine((route[i - 2] - minX) * scale + scale / 2, (route[i - 1] - minZ) * scale + scale / 2,
                        (route[i] - minX) * scale + scale / 2, (route[i + 1] - minZ) * scale + scale / 2);
            }
            for (int i = 1; i + 1 < route.length; i += 2) {
                g.fillOval((route[i] - minX) * scale - scale / 2, (route[i + 1] - minZ) * scale - scale / 2, scale * 2, scale * 2);
            }
        }
        g.dispose();
        ImageIO.write(image, "png", file);
    }

    private static int topY(SyntheticRoadWorld world, int x, int z) {
        return world.highestBlock(x, z);
    }

    private static Color scaled(Color color, double factor) {
        return new Color(clamp(color.getRed() * factor), clamp(color.getGreen() * factor), clamp(color.getBlue() * factor));
    }

    private static int clamp(double value) {
        return (int) Math.max(0, Math.min(255, value));
    }

    /** Side elevation along the centreline: natural ground, water, and the built road surface. */
    static void profile(SyntheticRoadWorld world, List<RoadCenterlinePoint> centerline, int scale, File file) throws IOException {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (RoadCenterlinePoint point : centerline) {
            minY = Math.min(minY, Math.min(point.targetY(), world.solidTop(point.pos().getX(), point.pos().getZ())));
            maxY = Math.max(maxY, Math.max(point.targetY(), world.solidTop(point.pos().getX(), point.pos().getZ())));
        }
        minY -= 4;
        maxY += 8;
        BufferedImage image = new BufferedImage(centerline.size() * scale, (maxY - minY + 1) * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0xBFDFFF));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        for (int i = 0; i < centerline.size(); i++) {
            int x = centerline.get(i).pos().getX();
            int z = centerline.get(i).pos().getZ();
            for (int y = minY; y <= maxY; y++) {
                BlockState state = world.block(x, y, z);
                if (state.isAir()) {
                    continue;
                }
                g.setColor(color(state));
                int py = (maxY - y) * scale;
                boolean slab = state.getBlock() instanceof SlabBlock;
                boolean thin = state.getBlock() instanceof WallBlock || state.getBlock() instanceof FenceBlock
                        || state.getBlock() instanceof LanternBlock;
                if (slab) {
                    g.fillRect(i * scale, py + scale / 2, scale, scale - scale / 2);
                } else if (thin) {
                    g.fillRect(i * scale + scale / 3, py, Math.max(1, scale / 3), scale);
                } else {
                    g.fillRect(i * scale, py, scale, scale);
                }
            }
        }
        g.dispose();
        file.getParentFile().mkdirs();
        ImageIO.write(image, "png", file);
    }

    /** A 2:1 isometric view from the south-east, drawn back to front. */
    static void isometric(SyntheticRoadWorld world, int minX, int minZ, int maxX, int maxZ,
                          int minY, int maxY, int tile, File file) throws IOException {
        isometric(world::block, minX, minZ, maxX, maxZ, minY, maxY, tile, file);
    }

    /** The same view turned a quarter turn, so the camera looks from the south-west. */
    static void isometricFromSouthWest(SyntheticRoadWorld world, int minX, int minZ, int maxX, int maxZ,
                                       int minY, int maxY, int tile, File file) throws IOException {
        isometric((i, y, j) -> world.block(maxX - j, y, minZ + i), 0, 0, maxZ - minZ, maxX - minX, minY, maxY, tile, file);
    }

    @FunctionalInterface
    interface Blocks3 {
        BlockState at(int x, int y, int z);
    }

    private static void isometric(Blocks3 world, int minX, int minZ, int maxX, int maxZ,
                                  int minY, int maxY, int tile, File file) throws IOException {
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        int width = (spanX + spanZ) * tile + 4 * tile;
        int height = (spanX + spanZ) * tile / 2 + (maxY - minY + 2) * tile + 4 * tile;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(new Color(0xCFE6FF));
        g.fillRect(0, 0, width, height);
        int originX = spanZ * tile + 2 * tile;
        int originY = (maxY - minY + 1) * tile + 2 * tile;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockState state = world.at(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }
                    boolean water = !state.getFluidState().isEmpty();
                    BlockState above = world.at(x, y + 1, z);
                    BlockState south = world.at(x, y, z + 1);
                    BlockState east = world.at(x + 1, y, z);
                    boolean slab = state.getBlock() instanceof SlabBlock;
                    boolean thin = state.getBlock() instanceof WallBlock || state.getBlock() instanceof FenceBlock;
                    boolean lantern = state.getBlock() instanceof LanternBlock;
                    boolean full = !slab && !thin && !lantern;
                    boolean showTop = !full || !(solid(above) && fullCube(above)) && !(water && !above.getFluidState().isEmpty());
                    boolean showSouth = z == maxZ || !fullCube(south) && !(water && !south.getFluidState().isEmpty());
                    boolean showEast = x == maxX || !fullCube(east) && !(water && !east.getFluidState().isEmpty());
                    if (!showTop && !showSouth && !showEast) {
                        continue;
                    }
                    int cx = originX + ((x - minX) - (z - minZ)) * tile;
                    int cy = originY + ((x - minX) + (z - minZ)) * tile / 2 - (y - minY) * tile;
                    double inset = thin ? 0.3 : lantern ? 0.35 : 0.0;
                    double low = 0.0;
                    double high = slab ? 0.5 : lantern ? 0.6 : water ? 0.88 : 1.0;
                    cube(g, cx, cy, tile, inset, low, high, color(state), showTop, showSouth || !full, showEast || !full);
                }
            }
        }
        g.dispose();
        file.getParentFile().mkdirs();
        ImageIO.write(image, "png", file);
    }

    private static boolean fullCube(BlockState state) {
        return solid(state) && !(state.getBlock() instanceof SlabBlock) && !(state.getBlock() instanceof WallBlock)
                && !(state.getBlock() instanceof FenceBlock) && !(state.getBlock() instanceof LanternBlock);
    }

    /** Draws one block whose footprint is shrunk by {@code inset} and which spans {@code low..high} of its cell. */
    private static void cube(Graphics2D g, int cx, int cy, int tile, double inset, double low, double high,
                             Color color, boolean top, boolean south, boolean east) {
        double a = inset;
        double b = 1.0 - inset;
        int[] nw = project(cx, cy, tile, a, a, high);
        int[] ne = project(cx, cy, tile, b, a, high);
        int[] se = project(cx, cy, tile, b, b, high);
        int[] sw = project(cx, cy, tile, a, b, high);
        int[] seLow = project(cx, cy, tile, b, b, low);
        int[] swLow = project(cx, cy, tile, a, b, low);
        int[] neLow = project(cx, cy, tile, b, a, low);
        if (south) {
            g.setColor(scaled(color, 0.78));
            g.fillPolygon(new Polygon(new int[]{sw[0], se[0], seLow[0], swLow[0]}, new int[]{sw[1], se[1], seLow[1], swLow[1]}, 4));
        }
        if (east) {
            g.setColor(scaled(color, 0.62));
            g.fillPolygon(new Polygon(new int[]{se[0], ne[0], neLow[0], seLow[0]}, new int[]{se[1], ne[1], neLow[1], seLow[1]}, 4));
        }
        if (top) {
            g.setColor(color);
            g.fillPolygon(new Polygon(new int[]{nw[0], ne[0], se[0], sw[0]}, new int[]{nw[1], ne[1], se[1], sw[1]}, 4));
        }
    }

    /** Projects a point inside a block cell (fractions of the cell) to the screen. */
    private static int[] project(int cx, int cy, int tile, double fx, double fz, double fy) {
        double sx = cx + (fx - fz) * tile;
        double sy = cy + (fx + fz) * tile / 2.0 - fy * tile;
        return new int[]{(int) Math.round(sx), (int) Math.round(sy)};
    }
}
