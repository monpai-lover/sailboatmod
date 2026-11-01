package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Immutable navigation graph made ONLY from saved water-route geometry.
 * No terrain sampler, chunk loader, missing-edge fallback, or route generator belongs here.
 * Dock IDs are labels on real berth vertices, not permission to bridge different berths.
 * Junctions are coincident vertices or a saved endpoint exactly on another saved segment.
 * Merely nearby/parallel routes and different water heights never acquire invented edges.
 */
public final class KnownWaterRouteGraph {
    private static final double EPS = 1.0e-6;
    public static final double MAX_ENTRY_DISTANCE = 256;
    public record Itinerary(List<Vec3> points, double length, Set<String> routeIds) {
        public Itinerary { points=List.copyOf(points); routeIds=Set.copyOf(routeIds); }
    }
    public record Entry(Vec3 point, double connectorDistance, Itinerary suffix) { }
    private record Key(long x,long y,long z) {
        static Key of(Vec3 p) { return new Key(Math.round(p.x*1e6),Math.round(p.y*1e6),Math.round(p.z*1e6)); }
    }
    private record Edge(int from,int to,double length,String route) { }
    private record Cut(double t,Vec3 point) { }
    private record Q(int node,double cost) implements Comparable<Q> {
        public int compareTo(Q o) { int c=Double.compare(cost,o.cost); return c!=0?c:Integer.compare(node,o.node); }
    }
    private final List<Vec3> points=new ArrayList<>();
    private final List<List<Edge>> outgoing=new ArrayList<>(), incoming=new ArrayList<>();
    private final Map<Key,Integer> ids=new HashMap<>();
    private final Map<BlockPos,Set<Integer>> docks=new LinkedHashMap<>();
    private final List<Edge> edges=new ArrayList<>();
    private KnownWaterRouteGraph() { }

    public static KnownWaterRouteGraph build(List<WaterRouteNetwork.Route> routes) {
        Objects.requireNonNull(routes,"routes");
        if(routes.size()>512)throw new IllegalArgumentException("Known graph route budget");
        KnownWaterRouteGraph g=new KnownWaterRouteGraph();
        Map<Key,Vec3> endpointIndex=new LinkedHashMap<>();
        int count=0;
        for(var r:routes) {
            if(r.sourceDock()==null||r.targetDock()==null||r.points().size()<2)continue;
            count=Math.addExact(count,r.points().size());
            if(count>WaterRouteNetwork.MAX_GRAPH_POINTS)throw new IllegalArgumentException("Known graph point budget");
            for(Vec3 p:r.points())if(!finite(p))throw new IllegalArgumentException("Invalid known route point");
            endpointIndex.put(Key.of(r.points().getFirst()),r.points().getFirst());
            endpointIndex.put(Key.of(r.points().getLast()),r.points().getLast());
        }
        List<Vec3> endpoints=List.copyOf(endpointIndex.values());
        for(var r:routes) {
            WaterRouteCancellation.check();
            if(r.sourceDock()==null||r.targetDock()==null||r.points().size()<2)continue;
            g.docks.computeIfAbsent(r.sourceDock(), k->new LinkedHashSet<>()).add(g.node(r.points().getFirst()));
            g.docks.computeIfAbsent(r.targetDock(), k->new LinkedHashSet<>()).add(g.node(r.points().getLast()));
            for(int i=1;i<r.points().size();i++) {
                if((i&255)==0)WaterRouteCancellation.check();
                Vec3 a=r.points().get(i-1), b=r.points().get(i);
                List<Cut> cuts=new ArrayList<>(); cuts.add(new Cut(0,a)); cuts.add(new Cut(1,b));
                // Exact T junctions support entering/exiting a saved long route in its middle.
                for(Vec3 p:endpoints) {
                    if(Math.abs(p.y-a.y)>EPS || Math.abs(a.y-b.y)>EPS
                            ||p.x<Math.min(a.x,b.x)-EPS||p.x>Math.max(a.x,b.x)+EPS
                            ||p.z<Math.min(a.z,b.z)-EPS||p.z>Math.max(a.z,b.z)+EPS)continue;
                    double t=projection(p,a,b); Vec3 at=interpolate(a,b,t);
                    if(t>EPS&&t<1-EPS&&horizontal(p,at)<=EPS)cuts.add(new Cut(t,p));
                }
                cuts.sort(Comparator.comparingDouble(Cut::t));
                for(int j=1;j<cuts.size();j++)g.add(cuts.get(j-1).point(),cuts.get(j).point(),r.id(),r.reversible());
            }
        }
        return g;
    }
    public Set<BlockPos> docks() { return Set.copyOf(docks.keySet()); }
    public int nodeCount() { return points.size(); }
    public int edgeCount() { return edges.size(); }
    private int node(Vec3 p) {
        return ids.computeIfAbsent(Key.of(p), k->{
            if(points.size()>=WaterRouteNetwork.MAX_GRAPH_POINTS)throw new IllegalArgumentException("Known graph node budget");
            points.add(p); outgoing.add(new ArrayList<>()); incoming.add(new ArrayList<>());return points.size()-1;
        });
    }
    private void add(Vec3 a,Vec3 b,String route,boolean both) {
        int from=node(a),to=node(b);if(from==to)return;
        if(edges.size()>=1_048_574)throw new IllegalArgumentException("Known graph edge budget");
        Edge e=new Edge(from,to,a.distanceTo(b),route);outgoing.get(from).add(e);incoming.get(to).add(e);edges.add(e);
        if(both){Edge reverse=new Edge(to,from,e.length,route);outgoing.get(to).add(reverse);incoming.get(from).add(reverse);edges.add(reverse);}
    }
    private record Search(double[] costs, Edge[] parent) { }
    private Search search(Map<Integer,Double> roots, boolean reverse) {
        double[] costs=new double[points.size()];Arrays.fill(costs,Double.POSITIVE_INFINITY);
        Edge[] parent=new Edge[points.size()];PriorityQueue<Q> queue=new PriorityQueue<>();
        roots.forEach((v,c)->{costs[v]=c;queue.add(new Q(v,c));});
        int visited=0;
        while(!queue.isEmpty()) {
            if((visited++&255)==0)WaterRouteCancellation.check();
            Q q=queue.remove();if(q.cost!=costs[q.node])continue;
            for(Edge e:(reverse?incoming:outgoing).get(q.node)) {
                int next=reverse?e.from:e.to;double candidate=q.cost+e.length;
                if(candidate+1e-9<costs[next]){costs[next]=candidate;parent[next]=e;queue.add(new Q(next,candidate));}
            }
        }
        return new Search(costs,parent);
    }
    private static Map<Integer,Double> roots(Set<Integer> nodes) {
        Map<Integer,Double> out=new LinkedHashMap<>();for(int v:nodes)out.put(v,0.0);return out;
    }
    public Map<BlockPos,Itinerary> fromDock(BlockPos source) {
        Set<Integer> starts=docks.get(source);if(starts==null)return Map.of();
        Search found=search(roots(starts),false);Map<BlockPos,Itinerary> result=new LinkedHashMap<>();
        for(var port:docks.entrySet()) {
            if(port.getKey().equals(source))continue;
            int end=bestNode(port.getValue(),found.costs);
            if(end>=0) {
                Itinerary p=backtrack(found,end);
                if(p.points.size()>1&&!p.routeIds.isEmpty())result.put(port.getKey(),p);
            }
        }
        return Map.copyOf(result);
    }
    public Optional<Itinerary> between(BlockPos source,BlockPos destination) {
        return Optional.ofNullable(fromDock(source).get(destination));
    }
    private static int bestNode(Set<Integer> nodes,double[] cost) {
        int best=-1;double min=Double.POSITIVE_INFINITY;
        for(int v:nodes)if(cost[v]<min){min=cost[v];best=v;}return best;
    }
    private Itinerary backtrack(Search s,int finish) {
        List<Vec3> path=new ArrayList<>();Set<String> used=new LinkedHashSet<>();int v=finish;
        for(int guard=0;;guard++){
            if(guard>points.size())throw new IllegalStateException("Known graph parent cycle");
            path.add(points.get(v));Edge e=s.parent[v];if(e==null)break;used.add(e.route);v=e.from;
        }
        Collections.reverse(path);return new Itinerary(compact(path),length(path),used);
    }
    /** Topological destinations reachable from at least one near entry; no terrain claim is made here.
     * Actual ship dispatch validates just its short connector before installing a voyage.
     */
    public Set<BlockPos> reachableNear(Vec3 boat) {
        if(!finite(boat))return Set.of();Map<Integer,Double> seeds=new HashMap<>();
        for(Edge e:edges) {
            Vec3 a=points.get(e.from),b=points.get(e.to);double t=projection(boat,a,b);Vec3 p=interpolate(a,b,t);
            if(horizontal(boat,p)<=MAX_ENTRY_DISTANCE&&Math.abs(boat.y-p.y)<=2)
                seeds.merge(e.to,(1-t)*e.length,Math::min);
        }
        if(seeds.isEmpty())return Set.of();Search found=search(seeds,false);Set<BlockPos> result=new LinkedHashSet<>();
        for(var d:docks.entrySet())if(bestNode(d.getValue(),found.costs)>=0)result.add(d.getKey());
        return Set.copyOf(result);
    }
    /** Nearest entries on lanes which REALLY lead to this destination, not the nearest disconnected lane. */
    public List<Entry> entries(Vec3 boat,BlockPos destination,int limit) {
        if(!finite(boat)||limit<1||!docks.containsKey(destination))return List.of();
        Search toGoal=search(roots(docks.get(destination)),true);
        Map<Key,Entry> unique=new HashMap<>();
        for(Edge e:edges) {
            if(!Double.isFinite(toGoal.costs[e.to]))continue;
            Vec3 a=points.get(e.from),b=points.get(e.to);double t=projection(boat,a,b);Vec3 p=interpolate(a,b,t);
            double distance=horizontal(boat,p);
            if(distance>MAX_ENTRY_DISTANCE||Math.abs(boat.y-p.y)>2)continue;
            List<Vec3> suffix=new ArrayList<>();suffix.add(p);suffix.add(b);Set<String> routes=new LinkedHashSet<>();routes.add(e.route);
            int at=e.to;
            for(int guard=0;toGoal.parent[at]!=null;guard++) {
                if(guard>points.size())throw new IllegalStateException("Known reverse parent cycle");
                Edge next=toGoal.parent[at];suffix.add(points.get(next.to));routes.add(next.route);at=next.to;
            }
            var itinerary=new Itinerary(compact(suffix),length(suffix),routes);
            Entry entry=new Entry(p,distance,itinerary);Key key=Key.of(p);
            Entry old=unique.get(key);if(old==null||itinerary.length<old.suffix.length)unique.put(key,entry);
        }
        return unique.values().stream().sorted(Comparator.comparingDouble(Entry::connectorDistance)
                .thenComparingDouble(e->e.suffix.length)).limit(Math.min(16,limit)).toList();
    }
    public static double length(List<Vec3> points) {
        double out=0;for(int i=1;i<points.size();i++)out+=points.get(i-1).distanceTo(points.get(i));return out;
    }
    private static List<Vec3> compact(List<Vec3> input) {
        List<Vec3> out=new ArrayList<>();for(Vec3 p:input)if(out.isEmpty()||out.getLast().distanceToSqr(p)>EPS*EPS)out.add(p);return List.copyOf(out);
    }
    private static double projection(Vec3 p,Vec3 a,Vec3 b) {
        double dx=b.x-a.x,dz=b.z-a.z,den=dx*dx+dz*dz;
        return den<EPS*EPS?0:Math.clamp(((p.x-a.x)*dx+(p.z-a.z)*dz)/den,0,1);
    }
    private static Vec3 interpolate(Vec3 a,Vec3 b,double t){return new Vec3(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t);}
    private static double horizontal(Vec3 a,Vec3 b){return Math.hypot(a.x-b.x,a.z-b.z);}
    private static boolean finite(Vec3 p){return p!=null&&Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z)
            &&Math.abs(p.x)<=30_000_000&&Math.abs(p.z)<=30_000_000&&Math.abs(p.y)<=65_536;}
}
