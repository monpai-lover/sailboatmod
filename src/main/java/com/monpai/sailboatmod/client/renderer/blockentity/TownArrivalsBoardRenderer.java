package com.monpai.sailboatmod.client.renderer.blockentity;

import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;
import com.monpai.sailboatmod.block.TownArrivalsBoardBlock;
import com.monpai.sailboatmod.block.entity.TownArrivalsBoardBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;

/** Native wall-fixed font rendering, bounded to the physical face of a single block. */
public final class TownArrivalsBoardRenderer implements BlockEntityRenderer<TownArrivalsBoardBlockEntity> {
    private static final int WIDTH = 280, TEXT = 0xFFEAF2FA, MUTED = 0xFF9FB1C6, ACCENT = 0xFF6DE2D4;
    private final Font font;
    public TownArrivalsBoardRenderer(BlockEntityRendererProvider.Context context) { font = context.getFont(); }

    @Override public void render(TownArrivalsBoardBlockEntity board, float partialTick, PoseStack pose,
                                 MultiBufferSource buffer, int packedLight, int packedOverlay) {
        var panel = board.getClientPanel();
        BoardView view = panel.view();
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-board.getBlockState().getValue(TownArrivalsBoardBlock.FACING).toYRot()));
        pose.translate(0, .364, -.3115);
        pose.scale(.0028F, -.0028F, .0028F);
        pose.translate(-WIDTH / 2.0, 0, 0);
        if (panel.bound()) {
            draw(panel.townName(), 0, 0, 112, ACCENT, pose, buffer);
            draw(tr("title"), 118, 0, 84, TEXT, pose, buffer);
        } else draw(tr("title"), 0, 0, 202, ACCENT, pose, buffer);
        draw(TownArrivalsBoardBlock.filterName(view.filter()).getString(), 210, 0, 70, TEXT, pose, buffer);
        if (!panel.bound()) {
            draw(tr("unbound"), 0, 71, WIDTH, TEXT, pose, buffer);
            draw(tr("unbound_help"), 0, 90, WIDTH, MUTED, pose, buffer);
            pose.popPose();
            return;
        }
        draw(tr("column.mode"), 0, 22, 30, MUTED, pose, buffer);
        draw(tr("column.route"), 33, 22, 109, MUTED, pose, buffer);
        draw(tr("column.state"), 146, 22, 52, MUTED, pose, buffer);
        draw(tr("column.eta"), 202, 22, 78, MUTED, pose, buffer);
        if (view.rows().isEmpty()) draw(tr(view.syncing() ? "syncing" : "empty"), 0, 97, WIDTH, MUTED, pose, buffer);
        for (int i = 0; i < view.rows().size(); i++) {
            PublicRow row = view.rows().get(i);
            int y = 42 + i * 22;
            String relation = switch (row.relation()) { case INBOUND -> "↓"; case OUTBOUND -> "↑"; case LOCAL -> "↔"; };
            String mode = row.publicPhase().startsWith("RAIL_") ? "rail" : row.kind().name().toLowerCase(Locale.ROOT);
            draw(tr("mode." + mode) + relation, 0, y, 30, ACCENT, pose, buffer);
            draw(row.routeName(), 33, y, 109, TEXT, pose, buffer);
            draw(row.sourceName() + " → " + row.targetName(), 33, y + 10, 109, MUTED, pose, buffer);
            draw(status(row, view.syncing()), 146, y, 52, stateColor(row, view.syncing()), pose, buffer);
            draw(eta(row, view.syncing()), 202, y, 78, TEXT, pose, buffer);
            if (row.etaTicks().isPresent() && row.publicPhase().equals("RAIL_FREIGHT"))
                draw(tr("eta.final_stop"), 202, y + 10, 78, MUTED, pose, buffer);
        }
        draw(tr("page", view.page() + 1, view.totalPages()), 0, 226, 104, MUTED, pose, buffer);
        if (view.overflow() > 0) draw(tr("overflow", view.overflow()), 107, 226, 173, MUTED, pose, buffer);
        draw(tr(view.syncing() ? "syncing" : "auto_page"), 0, 243, WIDTH, MUTED, pose, buffer);
        pose.popPose();
    }
    private String status(PublicRow row, boolean syncing) {
        if (syncing) return tr("syncing");
        if (row.state() == State.WAITING_RESOURCE && row.publicPhase().equals("RAIL_BLOCKED")) return tr("phase.rail_blocked");
        if (row.state() == State.WAITING_TERRAIN) return tr("state.waiting_terrain_" + row.kind().name().toLowerCase(Locale.ROOT));
        if (row.state() == State.MOVING) {
            return switch (row.publicPhase()) {
                case "TAXI_OUT", "TAXI_IN" -> tr("phase.taxi");
                case "HOLD_SHORT", "LINE_UP" -> tr("phase.hold");
                case "TAKEOFF", "CLIMB", "VERTICAL_TAKEOFF" -> tr("phase.departing");
                case "APPROACH", "LANDING_ROLL", "STAND_STABILIZING", "VERTICAL_LANDING" -> tr("phase.arriving");
                default -> tr("state.moving");
            };
        }
        return tr("state." + row.state().name().toLowerCase(Locale.ROOT));
    }
    private String eta(PublicRow row, boolean syncing) {
        if (syncing || row.etaTicks().isEmpty()) return "—";
        long seconds = (row.etaTicks().getAsLong() + 19) / 20;
        return seconds < 60 ? tr("eta.seconds", seconds) : tr("eta.minutes", seconds / 60, seconds % 60);
    }
    private int stateColor(PublicRow row, boolean syncing) {
        if (syncing || row.state() == State.STALE || row.state() == State.UNLOADED) return MUTED;
        return row.state() == State.FUEL_BLOCKED || row.state() == State.PAUSED ? 0xFFFFD18B : TEXT;
    }
    private void draw(String text, int x, int y, int width, int color, PoseStack pose, MultiBufferSource buffer) {
        String clipped = font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
        font.drawInBatch(clipped, x, y, color, false, pose.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
    }
    private static String tr(String suffix, Object... args) { return Component.translatable("board.sailboatmod." + suffix, args).getString(); }
    @Override public int getViewDistance() { return 24; }
}
