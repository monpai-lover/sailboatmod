package com.monpai.sailboatmod.route.water.local;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** Continuous swept, centred vessel footprint. Contains no world/entity references.
 * A waterlogged state supplies flotation, NOT collision clearance. A shape entirely
 * below the hull is harmless; a sea pickle/slab intersecting it is an obstacle.
 */
public final class BoatCollisionField {
    public enum Check { CLEAR, BLOCKED, UNKNOWN }
    public record Hull(double halfX, double halfZ, double minY, double maxY, int waterY) {
        public Hull {
            if (halfX <= 0 || halfZ <= 0 || maxY <= minY) throw new IllegalArgumentException("Invalid hull");
        }
        public static Hull at(AABB actual, double interfaceY) {
            // Include downward bobbing/draft, not the entire water block as in the old probe.
            double bottom = Math.min(actual.minY - .03, interfaceY - .22);
            return new Hull(actual.getXsize()/2, actual.getZsize()/2, bottom,
                    Math.max(actual.maxY, interfaceY + actual.getYsize()), (int)Math.floor(interfaceY + 1e-6)-1);
        }
    }
    public record Cell(boolean known, boolean water, List<AABB> shapes) {
        public Cell { shapes = List.copyOf(shapes); }
        public static Cell unknown() { return new Cell(false, false, List.of()); }
    }
    @FunctionalInterface public interface Columns { Cell get(int x, int z); }
    private final Hull hull;
    private final Columns columns;
    private final List<AABB> entities;
    public BoatCollisionField(Hull hull, Columns columns, List<AABB> entities) {
        this.hull=hull; this.columns=columns; this.entities=List.copyOf(entities);
    }
    public Hull hull() { return hull; }
    public List<AABB> entities() { return entities; }
    public static long key(int x,int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }

    public Check segment(Vec3 a, Vec3 b) {
        if (!finite(a) || !finite(b) || Math.abs(a.y-b.y)>1e-6) return Check.BLOCKED;
        boolean unknown=false;
        // One-cell halo includes shapes projecting outside their owning block (fences, etc.).
        int minX=(int)Math.floor(Math.min(a.x,b.x)-hull.halfX)-1;
        int maxX=(int)Math.floor(Math.max(a.x,b.x)+hull.halfX)+1;
        int minZ=(int)Math.floor(Math.min(a.z,b.z)-hull.halfZ)-1;
        int maxZ=(int)Math.floor(Math.max(a.z,b.z)+hull.halfZ)+1;
        if ((long)(maxX-minX+1)*(maxZ-minZ+1)>20_000) return Check.UNKNOWN;
        for (int z=minZ;z<=maxZ;z++) for (int x=minX;x<=maxX;x++) {
            AABB columnBox=new AABB(x,hull.minY,z,x+1,hull.maxY,z+1);
            // Do not request every column inside the bounding rectangle of a diagonal edge.
            // The one-cell halo is still relevant: an unread neighbour may own a protruding
            // collision shape, so it cannot be silently accepted as air/water.
            if(!hit(a,b,columnBox,hull.halfX+1,hull.halfZ+1))continue;
            Cell c=columns.get(x,z);
            // Dry support columns are checked with the SAME centred hull, not +X/+Z offsets.
            boolean touched=hit(a,b,columnBox,hull.halfX-1e-6,hull.halfZ-1e-6);
            if (c==null || !c.known) { unknown=true; continue; }
            if(touched&&!c.water)return Check.BLOCKED;
            for(AABB obstacle:c.shapes) if(obstacle.maxY>hull.minY+1e-6&&obstacle.minY<hull.maxY-1e-6
                    &&hit(a,b,obstacle,hull.halfX-1e-6,hull.halfZ-1e-6)
                    &&!separatingOverlap(a,b,obstacle,hull.halfX-1e-6,hull.halfZ-1e-6))return Check.BLOCKED;
        }
        for(AABB obstacle:entities) {
            if(obstacle.maxY<=hull.minY || obstacle.minY>=hull.maxY)continue;
            // A small traffic clearance; permit moving AWAY from an already touched margin,
            // never crossing the real other vessel, deleting it, or pushing it off its berth.
            if(hit(a,b,obstacle,hull.halfX-1e-6,hull.halfZ-1e-6)
                    &&!separatingOverlap(a,b,obstacle,hull.halfX-1e-6,hull.halfZ-1e-6))return Check.BLOCKED;
            if(hit(a,b,obstacle,hull.halfX+.35,hull.halfZ+.35)
                    && !escapingMargin(a,b,obstacle))return Check.BLOCKED;
        }
        return unknown?Check.UNKNOWN:Check.CLEAR;
    }
    private boolean escapingMargin(Vec3 a,Vec3 b,AABB obstacle) {
        return separatingOverlap(a,b,obstacle,hull.halfX+.35,hull.halfZ+.35);
    }
    /** A stranded hull can already touch a shape/draft margin. Permit only monotonic separation
     * from that initial overlap, never crossing the obstacle centre or increasing penetration on
     * either axis. Vanilla collision still controls actual movement; this is not noclip/teleport.
     */
    private static boolean separatingOverlap(Vec3 a,Vec3 b,AABB obstacle,double hx,double hz) {
        if(!hit(a,a,obstacle,hx,hz))return false;
        double cx=(obstacle.minX+obstacle.maxX)/2,cz=(obstacle.minZ+obstacle.maxZ)/2;
        double ax=a.x-cx,az=a.z-cz,bx=b.x-cx,bz=b.z-cz;
        return away(ax,bx)&&away(az,bz)
                &&(Math.abs(bx)>Math.abs(ax)+1e-8||Math.abs(bz)>Math.abs(az)+1e-8);
    }
    private static boolean away(double before,double after) {
        return Math.abs(before)<1e-9 || before*after>=0&&Math.abs(after)+1e-9>=Math.abs(before);
    }
    /** Exact horizontal swept-AABB intersection, rather than an overlarge diagonal bounding rectangle. */
    static boolean hit(Vec3 a,Vec3 b,AABB obstacle,double hx,double hz) {
        double[] interval={0,1};
        return clip(a.x,b.x-a.x,obstacle.minX-hx,obstacle.maxX+hx,interval)
                &&clip(a.z,b.z-a.z,obstacle.minZ-hz,obstacle.maxZ+hz,interval);
    }
    private static boolean clip(double p,double d,double min,double max,double[] t) {
        if(Math.abs(d)<1e-12)return p>min+1e-9&&p<max-1e-9;
        double a=(min-p)/d,b=(max-p)/d;
        t[0]=Math.max(t[0],Math.min(a,b));t[1]=Math.min(t[1],Math.max(a,b));
        return t[0]<=t[1]+1e-10;
    }
    private static boolean finite(Vec3 p){return p!=null&&Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z);}
}
