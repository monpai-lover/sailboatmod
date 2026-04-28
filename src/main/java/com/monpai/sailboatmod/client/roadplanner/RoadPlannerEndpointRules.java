package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class RoadPlannerEndpointRules {
    private RoadPlannerEndpointRules() {
    }

    public static boolean isInRoleClaim(RoadPlannerClaimOverlayRenderer renderer, BlockPos pos, RoadPlannerClaimOverlay.Role role) {
        if (renderer == null || pos == null || role == null) {
            return false;
        }
        return renderer.claimAtWorld(pos.getX(), pos.getZ())
                .map(overlay -> overlay.role() == role)
                .orElse(false);
    }

    public static Validation validate(List<BlockPos> nodes, RoadPlannerClaimOverlayRenderer renderer) {
        if (nodes == null || nodes.size() < 2) {
            return Validation.fail("至少需要设置起点和终点");
        }
        if (!isInRoleClaim(renderer, nodes.get(0), RoadPlannerClaimOverlay.Role.START)) {
            return Validation.fail("道路起点必须在起点 Town 领地内");
        }
        if (!isInRoleClaim(renderer, nodes.get(nodes.size() - 1), RoadPlannerClaimOverlay.Role.DESTINATION)) {
            return Validation.fail("道路终点必须在目标 Town 领地内");
        }
        return Validation.ok();
    }

    public record Validation(boolean valid, String message) {
        static Validation ok() {
            return new Validation(true, "");
        }

        static Validation fail(String message) {
            return new Validation(false, message == null ? "" : message);
        }
    }
}
