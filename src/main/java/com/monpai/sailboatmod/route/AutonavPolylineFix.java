package com.monpai.sailboatmod.route;

import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.function.BiPredicate;

/** Bounded, adjacent-leg repairs for fix_20's boat controller. All candidate connectors
 * still go through the original live hull sweep; this class never reads or loads a world.
 * It deliberately does not search arbitrary later route legs at self-intersections.
 */
public final class AutonavPolylineFix {
    private AutonavPolylineFix() {}
    private static final double EPS = 1.0e-8;

    public static int advance(List<Vec3> route, int current, Vec3 last, Vec3 boat,
                              double capture, double corridor, BiPredicate<Vec3,Vec3> clear) {
        if (route == null || route.isEmpty() || !finite(boat)) return 0;
        int index=Math.clamp(current,0,route.size()-1);
        double motion=last==null?0:Math.min(16,horizontal(last,boat));
        double width=Math.max(corridor,Math.min(12,corridor+motion+1));
        for(int checked=0;checked<128 && index<route.size()-1;checked++) {
            Vec3 b=route.get(index), v=route.get(index+1);
            if(!finite(b)||!finite(v))break;
            if(horizontal(b,v)<EPS && Math.abs(b.y-v.y)<EPS){index++;continue;}
            int prior=index-1;while(prior>=0&&horizontal(route.get(prior),b)<EPS)prior--;
            Vec3 a=prior>=0?route.get(prior):boat;
            boolean reached=horizontal(boat,b)<=Math.min(capture,.20);
            if(last!=null && horizontal(last,boat)>EPS && horizontal(last,boat)<=32){
                var sweep=WaterPolylineFollower.project(b,last,boat);
                reached|=sweep.t()>=0&&sweep.t()<=1&&sweep.lateral()<=.20;
            }
            double incoming=horizontal(a,b),outgoing=horizontal(b,v);
            var n=WaterPolylineFollower.project(boat,b,v);
            boolean passed=false;
            if(prior>=0&&incoming>EPS&&outgoing>EPS){
                var p=WaterPolylineFollower.project(boat,a,b);
                double cosine=((b.x-a.x)*(v.x-b.x)+(b.z-a.z)*(v.z-b.z))/(incoming*outgoing);
                passed=p.t()>=1 && p.lateral()<=Math.min(corridor,.75+motion*.25);
                passed|=cosine>-.70 && p.t()>=.5 && n.t()>0&&n.t()<=1
                    && n.t()*outgoing>=Math.max(capture,.75) && n.lateral()<=width
                    && n.lateral()+.25<p.lateral() && horizontal(boat,b)<=12;
            }
            if(!reached&&!passed)break;
            boolean safe=false;
            for(double lead:new double[]{.5,1,2,4}){
                Vec3 next=lerp(b,v,Math.clamp(n.t()+lead/outgoing,0,1));
                if(horizontal(boat,next)<=16 && clear.test(boat,next)){safe=true;break;}
            }
            if(!safe)break;
            index++;
        }
        return index;
    }

    public static WaterPolylineFollower.Aim aim(List<Vec3> route, int targetIndex, Vec3 boat,
                                                double lookahead, BiPredicate<Vec3,Vec3> clear) {
        if (route==null||route.isEmpty()||!finite(boat)||!Double.isFinite(lookahead))
            return new WaterPolylineFollower.Aim(boat,true,0,0);
        int i=Math.clamp(targetIndex,0,route.size()-1);
        Vec3 end=route.get(i), begin=i>0?route.get(i-1):boat;
        if (!finite(end)||!finite(begin)) return new WaterPolylineFollower.Aim(boat,true,0,0);
        var projection=WaterPolylineFollower.project(boat,begin,end);
        Vec3 on=lerp(begin,end,Math.clamp(projection.t(),0,1));
        double remaining=horizontal(on,end);
        double corner=i+1<route.size()?turn(begin,end,route.get(i+1)):0;
        double lead=Math.max(1,Math.min(16,lookahead));
        for(double distance:new double[]{lead,lead*.5,lead*.25,1,.5,.25,.125}) {
            double left=distance;Vec3 at=on;int cursor=i;
            for(int steps=0;steps<64;steps++) {
                Vec3 to=route.get(cursor);
                if(!finite(to))break;
                double length=horizontal(at,to);
                Vec3 prior=cursor>0?route.get(cursor-1):boat;
                // Do not cut across a real corner until it has been safely passed.
                boolean cornerStop=cursor+1<route.size() && turn(prior,to,route.get(cursor+1))>25;
                if(left<=length || cursor==route.size()-1 || cornerStop) {
                    Vec3 candidate=length<EPS?to:lerp(at,to,Math.min(1,left/length));
                    double away=horizontal(boat,candidate);
                    boolean endpoint=cursor==route.size()-1 && horizontal(candidate,to)<EPS;
                    if((away>=.04 || endpoint) && away<=32 && clear.test(boat,candidate))
                        return new WaterPolylineFollower.Aim(candidate,false,remaining,corner);
                    break;
                }
                left-=length;at=to;cursor++;
            }
        }
        double vertexDistance=horizontal(boat,end);
        if(vertexDistance>=.02 && vertexDistance<=16 && clear.test(boat,end))
            return new WaterPolylineFollower.Aim(end,false,remaining,corner);
        return new WaterPolylineFollower.Aim(boat,true,remaining,corner);
    }
    static double horizontal(Vec3 a,Vec3 b){return Math.hypot(a.x-b.x,a.z-b.z);}
    static Vec3 lerp(Vec3 a,Vec3 b,double t){return new Vec3(a.x+(b.x-a.x)*t,b.y,a.z+(b.z-a.z)*t);}
    static boolean finite(Vec3 a){return a!=null&&Double.isFinite(a.x)&&Double.isFinite(a.y)&&Double.isFinite(a.z);}
    static double turn(Vec3 a,Vec3 b,Vec3 c) {
        if(!finite(a)||!finite(b)||!finite(c))return 180;
        double ax=b.x-a.x,az=b.z-a.z,bx=c.x-b.x,bz=c.z-b.z,l=Math.hypot(ax,az)*Math.hypot(bx,bz);
        return l<EPS?0:Math.toDegrees(Math.acos(Math.clamp((ax*bx+az*bz)/l,-1,1)));
    }
}
