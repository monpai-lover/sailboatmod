package com.monpai.sailboatmod.route.water.local;

import java.util.*;
import net.minecraft.world.phys.*;

/** Extra route-planning clearance, never a replacement for the physical hull sweep.
 * minHalfWidth is distance from the vessel centre to a bank, not two blocks beyond its hull.
 * Existing overlaps with the EXTRA margin may be left monotonically. Physical collisions,
 * missing data and dry support under the actual hull cannot be ignored.
 */
public final class CoastalClearance {
    public static final double MIN_CENTRE_CLEARANCE = 2.00;
    private CoastalClearance() {}
    public static BoatCollisionField.Check check(BoatCollisionField.Hull hull,
            BoatCollisionField.Columns columns,List<AABB> entities,Vec3 a,Vec3 b,double minimumHalfWidth) {
        var physical = new BoatCollisionField(hull,columns,entities);
        var hit=physical.segment(a,b);
        if(hit!=BoatCollisionField.Check.CLEAR || minimumHalfWidth<=0) return hit;
        var enlarged=new BoatCollisionField.Hull(Math.max(hull.halfX()+.15,minimumHalfWidth),
                Math.max(hull.halfZ()+.15,minimumHalfWidth),hull.minY(),hull.maxY(),hull.waterY());
        BoatCollisionField.Columns shoreline=(x,z)->{
            var cell=columns.get(x,z);
            if(cell==null||!cell.known()||cell.water())return cell;
            var shapes=new ArrayList<AABB>(cell.shapes());
            shapes.add(new AABB(x,hull.minY(),z,x+1,hull.maxY(),z+1));
            return new BoatCollisionField.Cell(true,true,shapes);
        };
        var padded=new BoatCollisionField(enlarged,shoreline,entities).segment(a,b);
        if(padded!=BoatCollisionField.Check.BLOCKED)return padded;
        return escapingReserve(enlarged,columns,entities,a,b);
    }
    private record Obstacle(AABB box,double hx,double hz){}
    /** For an initially close berth, improve clearance to the WHOLE bank.
     * The swept line must never enter the starting-clearance contour and must end farther
     * from the bank. Exact physical hull checking remains authoritative.
     */
    private static BoatCollisionField.Check escapingReserve(BoatCollisionField.Hull h,
            BoatCollisionField.Columns columns,List<AABB> entities,Vec3 a,Vec3 b){
        int minX=(int)Math.floor(Math.min(a.x,b.x)-h.halfX())-1,maxX=(int)Math.floor(Math.max(a.x,b.x)+h.halfX())+1;
        int minZ=(int)Math.floor(Math.min(a.z,b.z)-h.halfZ())-1,maxZ=(int)Math.floor(Math.max(a.z,b.z)+h.halfZ())+1;
        if((long)(maxX-minX+1)*(maxZ-minZ+1)>20_000)return BoatCollisionField.Check.UNKNOWN;
        List<Obstacle> obstacles=new ArrayList<>();
        for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++){
            AABB column=new AABB(x,h.minY(),z,x+1,h.maxY(),z+1);
            if(!BoatCollisionField.hit(a,b,column,h.halfX()+1,h.halfZ()+1))continue;
            var cell=columns.get(x,z);if(cell==null||!cell.known())return BoatCollisionField.Check.UNKNOWN;
            if(!cell.water())obstacles.add(new Obstacle(column,h.halfX(),h.halfZ()));
            for(var box:cell.shapes())if(box.maxY>h.minY()&&box.minY<h.maxY())obstacles.add(new Obstacle(box,h.halfX(),h.halfZ()));
        }
        for(var box:entities)if(box.maxY>h.minY()&&box.minY<h.maxY())obstacles.add(new Obstacle(box,h.halfX()+.35,h.halfZ()+.35));
        double before=0,after=0;
        for(var o:obstacles){before=Math.max(before,penetration(a,o));after=Math.max(after,penetration(b,o));}
        if(before<=1e-6||after>=before-1e-7)return BoatCollisionField.Check.BLOCKED;
        for(var o:obstacles)if(BoatCollisionField.hit(a,b,o.box,Math.max(0,o.hx-before-1e-5),Math.max(0,o.hz-before-1e-5)))
            return BoatCollisionField.Check.BLOCKED;
        return BoatCollisionField.Check.CLEAR;
    }
    private static double penetration(Vec3 p,Obstacle o){
        double x=Math.min(p.x-(o.box.minX-o.hx),(o.box.maxX+o.hx)-p.x);
        double z=Math.min(p.z-(o.box.minZ-o.hz),(o.box.maxZ+o.hz)-p.z);
        return Math.max(0,Math.min(x,z));
    }
}
