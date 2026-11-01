package com.monpai.sailboatmod.entity;

import net.minecraft.world.phys.Vec3;

/** Model-calibrated helm seat, shared with the renderer so scale/height cannot silently diverge.
 * sailboat.geo.json: wheel bone10 centre near (-0.5,39.45,35.24), stern deck top Y34.
 * Gecko's X reflection followed by renderer rotation (180-yaw) leaves +modelX at +localX,
 * and +modelZ at -localZ when yaw=0. Minecraft yaw rotates +Z towards -X.
 */
public final class SailboatSeatLayout {
    public static final float MODEL_SCALE=0.68F;
    public static final float MODEL_LIFT=0.35F;
    static final double PIXEL_SCALE=0.68D/16;
    static final double HELM_MODEL_X=-0.5D,HELM_MODEL_Z=35.24001D,DECK_MODEL_Y=34D;
    static final double PLAYER_LEGACY_OFFSET=-0.35D,VEHICLE_RIDING_OFFSET=0.30D;
    public static final Vec3 CAPTAIN=new Vec3(HELM_MODEL_X*PIXEL_SCALE,
            MODEL_LIFT+DECK_MODEL_Y*PIXEL_SCALE+0.02-VEHICLE_RIDING_OFFSET-PLAYER_LEGACY_OFFSET,
            -HELM_MODEL_Z*PIXEL_SCALE-0.55D);
    private SailboatSeatLayout() { }
    public static Vec3 worldOffset(Vec3 local,float yaw) {
        double angle=Math.toRadians(yaw),s=Math.sin(angle),c=Math.cos(angle);
        return new Vec3(local.x*c-local.z*s,local.y,local.x*s+local.z*c);
    }
}
