package com.monpai.sailboatmod.transport.air.rail;

import net.minecraft.world.phys.Vec3;

/** Position eligibility only. The caller still proves orientation, resources and swept collision before any move. */
public final class RailReloadAlignment {
    private static final double MAX_HORIZONTAL_DISTANCE=.05,MAX_DOWNWARD_DROP=.25,BOUNDARY_TOLERANCE=1e-9;
    private RailReloadAlignment() { }

    /** Accepts a small downward settling offset from the saved exact sample; upward motion is never eligible. */
    public static boolean permits(Vec3 savedAnchor,Vec3 actualAnchor) {
        if(!finite(savedAnchor)||!finite(actualAnchor))return false;
        double drop=savedAnchor.y-actualAnchor.y;
        return drop>=0&&drop<=MAX_DOWNWARD_DROP+BOUNDARY_TOLERANCE
                &&Math.hypot(savedAnchor.x-actualAnchor.x,savedAnchor.z-actualAnchor.z)<=MAX_HORIZONTAL_DISTANCE+BOUNDARY_TOLERANCE;
    }
    private static boolean finite(Vec3 v){return v!=null&&Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
}
