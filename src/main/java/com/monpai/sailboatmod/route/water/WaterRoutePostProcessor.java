package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Final, whole-route optimization. Run on a route worker, after all detailed segments.
 * Every replacement is transactional and tests its complete cell supercover against
 * the same real-first, strict hull sampler. Unknown water is never clearance. There is
 * no unconditional spline/thinning step and no modification of actual dock endpoints.
 */
public final class WaterRoutePostProcessor {
    @FunctionalInterface public interface HullSampler { WaterColumn sample(int x, int z); }
    @FunctionalInterface public interface AwaitTerrain { void await(long millis); }
    @FunctionalInterface public interface Progress { void update(int percent, String stage); }
    public record Options(int offshoreTarget, int maxShift, long maxMillis, int maxSamples) {
        public Options {
            if (offshoreTarget < 0 || offshoreTarget > 16 || maxShift < offshoreTarget || maxShift > 64
                    || maxMillis < 1 || maxSamples < 1) throw new IllegalArgumentException("Invalid postprocessing budget");
        }
        public static Options defaults() { return new Options(8, 24, 30_000, 2_000_000); }
    }
    public record Metrics(int points, double length, double turnDegrees, double maxTurnDegrees, int hairpins) { }
    public record Result(List<BlockPos> path, Metrics before, Metrics after, int shortcuts,
                         int loopsRemoved, int moved, int rounded, int samples, int unknown,
                         boolean budgetLimited) { }

    private final HullSampler sampler;
    private final AwaitTerrain await;
    private final Progress progress;
    private final Options options;
    private long deadline;
    private final Map<Long, WaterColumn> cells = new LinkedHashMap<>(1024, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long,WaterColumn> e) { return size() > 262_144; }
    };
    private final Map<BlockPos, Integer> margins = new HashMap<>();
    private int samples, unknown, shortcuts, loops, moved, rounded;
    private boolean limited;
    private BlockPos start, goal;
    private double originalLength;
    private List<BlockPos> safe;
    private static final class Budget extends RuntimeException {
        Budget() { super("Optional postprocessing budget", null, false, false); }
    }
    private WaterRoutePostProcessor(HullSampler sampler, AwaitTerrain await, Progress progress, Options options) {
        this.sampler = Objects.requireNonNull(sampler);
        this.await = await;
        this.progress = progress == null ? (p,s) -> {} : progress;
        this.options = Objects.requireNonNull(options);
        deadline = System.nanoTime() + options.maxMillis() * 1_000_000L;
    }
    public static Result optimize(HullSampler sampler, List<BlockPos> path, AwaitTerrain await,
                                  Progress progress, Options options) {
        return new WaterRoutePostProcessor(sampler, await, progress, options).run(path);
    }
    private Result run(List<BlockPos> input) {
        if (input == null || input.size() < 2 || input.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("A complete validated route is required");
        start = input.getFirst(); goal = input.getLast();
        Metrics before = metrics(input); originalLength = before.length();
        safe = List.copyOf(input);
        // Input validation is mandatory, unlike optional optimization. A failed or unknown
        // input cannot be silently returned as a 'safe fallback'. Cancellation propagates.
        progress.update(90, "航线后处理 · 验证输入");
        try {
            validateInput(safe);
        } catch (Budget e) { throw new IllegalArgumentException("Cannot verify input within postprocessing budget"); }
        // Mandatory reloading has its own bounded allowance. A long path can span
        // evicted chunks; do not spend the optional geometry budget serially warming it.
        deadline = System.nanoTime() + options.maxMillis() * 1_000_000L;
        try {
            safe = eraseRepeated(safe);
            safe = eraseCrossings(safe);
            progress.update(92, "航线后处理 · 全局裁剪/去回环");
            safe = pull(safe);
            List<BlockPos> reverse = new ArrayList<>(safe); Collections.reverse(reverse);
            reverse = pull(reverse); Collections.reverse(reverse);
            if (cost(reverse) < cost(safe)) safe = List.copyOf(reverse);
            progress.update(94, "航线后处理 · 离岸移位");
            safe = addControls(safe);
            for (int round=0; round<3; round++) safe = shift(safe);
            safe = eraseCrossings(safe);
            progress.update(97, "航线后处理 · 安全圆角");
            safe = round(safe);
            safe = eraseCrossings(safe);
            safe = compact(safe);
        } catch (Budget e) { limited = true; /* Only complete, checked edits were committed to safe. */ }
        // All edits kept original endpoints and each new edge was certified. Budget
        // exhaustion only stops quality work, not safety validation or cancellation.
        WaterRouteCancellation.check();
        if (!safe.getFirst().equals(start) || !safe.getLast().equals(goal))
            throw new IllegalStateException("Postprocessor changed actual endpoints");
        progress.update(99, limited ? "航线后处理 · 已保留安全优化(预算到达)" : "航线后处理 · 完成");
        return new Result(List.copyOf(safe), before, metrics(safe), shortcuts, loops, moved, rounded,
                samples, unknown, limited);
    }
    /** Visit ALL missing cells in a pass, so bounded terrain workers can run in
     * parallel. Short-circuiting at the first unknown serialized hundreds of evicted
     * chunks behind a fixed per-edge timeout and rejected otherwise valid long paths.
     * Each known cell is removed permanently from this worklist. No unknown is water.
     */
    private void validateInput(List<BlockPos> path) {
        Set<BlockPos> unique = new LinkedHashSet<>();
        for (int i=1; i<path.size(); i++) {
            BlockPos a=path.get(i-1), b=path.get(i);
            if(a.getY()!=b.getY())throw new IllegalArgumentException("Input changes water height");
            WaterGridTraversal.all(a.getX(),a.getZ(),b.getX(),b.getZ(),(x,z)-> {
                budget(); unique.add(new BlockPos(x,a.getY(),z));
                if(unique.size()>options.maxSamples())throw new Budget();
                return true;
            });
        }
        List<BlockPos> pending = new ArrayList<>(unique);
        while(!pending.isEmpty()) {
            budget();
            Set<Long> deferredChunks = new HashSet<>();
            for(var iterator=pending.iterator(); iterator.hasNext();) {
                BlockPos p=iterator.next();
                long chunk=((long)(p.getX()>>4)<<32)|((p.getZ()>>4)&0xffffffffL);
                if(deferredChunks.contains(chunk))continue;
                WaterColumn c=sample(p.getX(),p.getZ());
                if(c==null){deferredChunks.add(chunk);continue;}
                if(!c.passable() || c.surfacePos()==null || c.surfacePos().getY()!=p.getY())
                    throw new IllegalArgumentException("Input route blocked or wrong height at "+p);
                iterator.remove();
            }
            if(!pending.isEmpty()) {
                if(await==null)throw new IllegalArgumentException("Input terrain unavailable at "+pending.getFirst());
                await.await(10); // route worker only; underlying IO remains bounded/asynchronous
            }
        }
    }

    private void budget() {
        WaterRouteCancellation.check();
        if (System.nanoTime() >= deadline || samples >= options.maxSamples()) throw new Budget();
    }
    private WaterColumn sample(int x,int z) {
        budget();
        long key=((long)x<<32)|(z&0xffffffffL);
        WaterColumn c=cells.get(key);
        if(c==null) { samples++; c=sampler.sample(x,z); if(c!=null)cells.put(key,c); else unknown++; }
        return c;
    }
    private boolean water(int x,int z,int y) {
        WaterColumn c=sample(x,z);
        return c!=null && c.passable() && c.surfacePos()!=null && c.surfacePos().getY()==y;
    }
    /** Exact Chebyshev clearance outside an already-sized hull, capped at target.
     * An unknown ring is a lower bound, never cached as dry land or as confirmed clearance. */
    private int clearance(BlockPos p) {
        Integer cached=margins.get(p); if(cached!=null)return cached;
        if(!water(p.getX(),p.getZ(),p.getY()))return -1;
        for(int r=1;r<=options.offshoreTarget();r++) {
            int missing=unknown;
            for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++) {
                if(Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;
                if(!water(p.getX()+dx,p.getZ()+dz,p.getY())) {
                    if(unknown==missing && margins.size()<262_144)margins.put(p,r-1);
                    return r-1;
                }
            }
        }
        if(margins.size()<262_144)margins.put(p,options.offshoreTarget());
        return options.offshoreTarget();
    }
    private boolean edge(BlockPos a,BlockPos b,int margin) {
        if(a.getY()!=b.getY())return false;
        return WaterGridTraversal.all(a.getX(),a.getZ(),b.getX(),b.getZ(),(x,z)->
            water(x,z,a.getY()) && (margin<=0 || clearance(new BlockPos(x,a.getY(),z))>=margin));
    }
    /** Optional candidate waits are bounded; retain original route if unavailable.
     * Only a worker supplied by the caller may wait. No get/join or world mutation here. */
    private boolean settle(BooleanSupplier check,long millis) {
        long until=Math.min(deadline,System.nanoTime()+millis*1_000_000L);
        do {
            int previous=unknown;
            if(check.getAsBoolean())return true;
            if(await==null || previous==unknown || System.nanoTime()>=until)return false;
            await.await(10); budget();
        } while(true);
    }
    private List<BlockPos> eraseRepeated(List<BlockPos> input) {
        List<BlockPos> out=new ArrayList<>();Map<BlockPos,Integer> index=new HashMap<>();
        for(BlockPos p:input) {
            budget();Integer old=index.get(p);
            if(old!=null){if(old<out.size()-1)loops++;while(out.size()>old+1)index.remove(out.removeLast());}
            else {index.put(p,out.size());out.add(p);}
        }
        // A route returning to its start still has two endpoints for downstream consumers.
        if(out.size()==1)out.add(out.getFirst());
        return List.copyOf(out);
    }
    private List<BlockPos> eraseCrossings(List<BlockPos> input) {
        List<BlockPos> out=new ArrayList<>(input);
        for(int pass=0;pass<8;pass++) {
            boolean changed=false;
            outer:for(int i=0;i+1<out.size();i++)for(int j=i+2;j+1<out.size();j++) {
                budget();BlockPos a=out.get(i),b=out.get(i+1),c=out.get(j),d=out.get(j+1);
                if(a.getY()!=c.getY())continue;
                double ux=b.getX()-a.getX(),uz=b.getZ()-a.getZ(),vx=d.getX()-c.getX(),vz=d.getZ()-c.getZ();
                double den=ux*vz-uz*vx;if(Math.abs(den)<1e-9)continue;
                double wx=c.getX()-a.getX(),wz=c.getZ()-a.getZ();
                double t=(wx*vz-wz*vx)/den,s=(wx*uz-wz*ux)/den;
                if(t<0||t>1||s<0||s>1)continue;
                BlockPos q=new BlockPos((int)Math.round(a.getX()+t*ux),a.getY(),(int)Math.round(a.getZ()+t*uz));
                if(!settle(()->edge(a,q,0)&&edge(q,d,0),100))continue;
                List<BlockPos> next=new ArrayList<>(out.subList(0,i+1));next.add(q);next.addAll(out.subList(j+1,out.size()));
                next=compact(next);
                if(length(next)>=length(out)-1e-6)continue;
                out=next;loops++;changed=true;break outer;
            }
            if(!changed)break;
        }
        return List.copyOf(out);
    }
    /** Visibility pruning across the stitched route, not just within a fine-search segment. */
    private List<BlockPos> pull(List<BlockPos> input) {
        if(input.size()<3)return new ArrayList<>(input);
        List<BlockPos> out=new ArrayList<>();out.add(input.getFirst());
        for(int i=0;i<input.size()-1;) {
            budget();int chosen=i+1;
            // A bounded set of long and short skips: try the destination and progressively
            // nearer vertices, then a local window. No quadratic scan of thousands of edges.
            NavigableSet<Integer> candidates=new TreeSet<>(Comparator.reverseOrder());
            candidates.add(input.size()-1);
            for(int k=2;k<=256;k*=2)if(i+k<input.size())candidates.add(i+k);
            for(int k=2;k<=16 && i+k<input.size();k++)candidates.add(i+k);
            for(int j:candidates) {
                if(j<=i+1)continue;
                BlockPos a=input.get(i),b=input.get(j);
                double old=0;int margin=options.offshoreTarget();
                for(int k=i;k<j;k++){old+=dist(input.get(k),input.get(k+1));margin=Math.min(margin,Math.max(0,clearance(input.get(k))));}
                margin=Math.min(margin,Math.max(0,clearance(b)));
                if(dist(a,b)>old+1e-6)continue;
                int required=margin;
                if(settle(()->edge(a,b,required),100)) {chosen=j;shortcuts+=j-i-1;break;}
            }
            out.add(input.get(chosen));i=chosen;
        }
        return new ArrayList<>(compact(out));
    }
    /** Add sparse relocation handles to long chords, only if quantization remains safe. */
    private List<BlockPos> addControls(List<BlockPos> input) {
        List<BlockPos> out=new ArrayList<>();out.add(input.getFirst());
        for(int i=1;i<input.size();i++) {
            BlockPos a=input.get(i-1),b=input.get(i);int pieces=(int)Math.ceil(dist(a,b)/64.0);
            if(pieces<2 || out.size()+pieces+input.size()-i>512){out.add(b);continue;}
            List<BlockPos> proposed=new ArrayList<>();proposed.add(a);
            for(int k=1;k<pieces;k++)proposed.add(lerp(a,b,k/(double)pieces));proposed.add(b);
            if(checked(proposed,0))out.addAll(proposed.subList(1,proposed.size()));else out.add(b);
        }
        return List.copyOf(out);
    }
    private List<BlockPos> shift(List<BlockPos> input) {
        List<BlockPos> out=new ArrayList<>(input);
        double total=length(out);
        for(int i=1;i+1<out.size();i++) {
            budget();BlockPos a=out.get(i-1),p=out.get(i),b=out.get(i+1);
            int target=Math.min(options.offshoreTarget(),(int)(Math.min(dist(p,start),dist(p,goal))/4));
            int oldMargin=clearance(p);if(oldMargin>=target || oldMargin<0)continue;
            double tangentX=b.getX()-a.getX(),tangentZ=b.getZ()-a.getZ(),len=Math.hypot(tangentX,tangentZ);
            if(len<1e-6)continue;
            double nx=-tangentZ/len,nz=tangentX/len;
            BlockPos best=p;double oldLocal=dist(a,p)+dist(p,b),bestScore=oldLocal+6*(target-oldMargin);
            int required=Math.min(oldMargin,2);
            for(int d:new int[]{1,2,4,8,12,16,24}) {
                if(d>options.maxShift())continue;
                for(int sign:new int[]{-1,1}) {
                    BlockPos q=new BlockPos(p.getX()+(int)Math.round(nx*d*sign),p.getY(),p.getZ()+(int)Math.round(nz*d*sign));
                    int margin=clearance(q);if(margin<=oldMargin)continue;
                    double local=dist(a,q)+dist(q,b),score=local+6*Math.max(0,target-margin);
                    // Shore movement may spend some length, but cannot create a large detour.
                    if(score>=bestScore-1e-6 || total-oldLocal+local>originalLength*1.08+4)continue;
                    if(!settle(()->edge(a,q,required)&&edge(q,b,required),100))continue;
                    best=q;bestScore=score;
                }
            }
            if(!best.equals(p)){total+=dist(a,best)+dist(best,b)-oldLocal;out.set(i,best);moved++;}
        }
        return List.copyOf(out);
    }
    private List<BlockPos> round(List<BlockPos> input) {
        List<BlockPos> out=new ArrayList<>();out.add(input.getFirst());
        for(int i=1;i+1<input.size();i++) {
            budget();BlockPos a=out.getLast(),p=input.get(i),b=input.get(i+1);
            double angle=turn(a,p,b);List<BlockPos> best=null;double bestEnergy=angle*angle;
            if(angle>Math.toRadians(15) && angle<Math.toRadians(170) && a.getY()==p.getY() && p.getY()==b.getY()) {
                for(double wanted:new double[]{24,16,12,8,4}) {
                    double radius=Math.min(wanted,Math.min(dist(a,p),dist(p,b))*.3);if(radius<2)continue;
                    BlockPos entry=lerp(p,a,radius/dist(p,a)),exit=lerp(p,b,radius/dist(p,b));
                    List<BlockPos> curve=new ArrayList<>();curve.add(a);curve.add(entry);
                    int count=Math.max(3,(int)Math.ceil(radius/4));
                    for(int k=1;k<count;k++) {
                        double t=k/(double)count,u=1-t;
                        curve.add(new BlockPos((int)Math.round(u*u*entry.getX()+2*u*t*p.getX()+t*t*exit.getX()),p.getY(),
                                (int)Math.round(u*u*entry.getZ()+2*u*t*p.getZ()+t*t*exit.getZ())));
                    }
                    curve.add(exit);curve.add(b);curve=compact(curve);
                    if(out.size()+curve.size()+input.size()-i>512)continue;
                    double energy=turnEnergy(curve);
                    if(energy>=bestEnergy*.95 || length(curve)>dist(a,p)+dist(p,b)+.5)continue;
                    int margin=Math.min(Math.max(0,clearance(p)),Math.min(Math.max(0,clearance(entry)),Math.max(0,clearance(exit))));
                    if(!checked(curve,margin))continue;
                    best=curve;bestEnergy=energy;
                }
            }
            if(best==null)out.add(p);
            else {out.addAll(best.subList(1,best.size()-1));rounded++;}
        }
        out.add(input.getLast());return List.copyOf(out);
    }
    private boolean checked(List<BlockPos> path,int margin) {
        for(int i=1;i<path.size();i++) {
            BlockPos a=path.get(i-1),b=path.get(i);
            if(!settle(()->edge(a,b,margin),100))return false;
        }
        return true;
    }
    private static BlockPos lerp(BlockPos a,BlockPos b,double t) {
        return new BlockPos((int)Math.round(a.getX()+(b.getX()-(double)a.getX())*t),a.getY(),
                (int)Math.round(a.getZ()+(b.getZ()-(double)a.getZ())*t));
    }
    static List<BlockPos> compact(List<BlockPos> input) {
        List<BlockPos> out=new ArrayList<>();
        for(BlockPos p:input) {
            if(!out.isEmpty() && out.getLast().equals(p))continue;
            while(out.size()>1) {
                BlockPos a=out.get(out.size()-2),b=out.getLast();
                long ax=(long)b.getX()-a.getX(),az=(long)b.getZ()-a.getZ(),bx=(long)p.getX()-b.getX(),bz=(long)p.getZ()-b.getZ();
                if(a.getY()!=b.getY()||b.getY()!=p.getY()||ax*bz!=az*bx||ax*bx+az*bz<=0)break;
                out.removeLast();
            }
            out.add(p);
        }
        if(out.size()==1)out.add(out.getFirst());return out;
    }
    static double dist(BlockPos a,BlockPos b) {return Math.sqrt(a.distSqr(b));}
    static double length(List<BlockPos> p) {double v=0;for(int i=1;i<p.size();i++)v+=dist(p.get(i-1),p.get(i));return v;}
    private static double turn(BlockPos a,BlockPos b,BlockPos c) {
        double ux=b.getX()-a.getX(),uz=b.getZ()-a.getZ(),vx=c.getX()-b.getX(),vz=c.getZ()-b.getZ();
        double den=Math.hypot(ux,uz)*Math.hypot(vx,vz);if(den<1e-9)return 0;
        return Math.acos(Math.max(-1,Math.min(1,(ux*vx+uz*vz)/den)));
    }
    private static double turnEnergy(List<BlockPos> p){double e=0;for(int i=1;i+1<p.size();i++){double a=turn(p.get(i-1),p.get(i),p.get(i+1));e+=a*a;}return e;}
    private static double cost(List<BlockPos> p){return length(p)+8*turnEnergy(p);}
    public static Metrics metrics(List<BlockPos> p) {
        double turn=0,max=0;int hairpins=0;
        for(int i=1;i+1<p.size();i++){double d=Math.toDegrees(turn(p.get(i-1),p.get(i),p.get(i+1)));turn+=d;max=Math.max(max,d);if(d>120)hairpins++;}
        return new Metrics(p.size(),length(p),turn,max,hairpins);
    }
}
