package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Immutable, dimension-local infrastructure. A graph edge is not a voyage/order.
 * Only stored geometry is reused. Every gap is explicitly returned as a new-search leg;
 * callers must revalidate reused water and run the normal solver for all gaps.
 */
public final class WaterRouteNetwork {
    public static final int MAX_GRAPH_POINTS = 262_144;
    public static final double ATTACH_RADIUS = 256.0;
    public record Route(String id, BlockPos sourceDock, BlockPos targetDock,
                        List<Vec3> points, boolean reversible) {
        public Route { points = List.copyOf(points); }
    }
    public record Leg(List<Vec3> points, boolean reused) {
        public Leg { points = List.copyOf(points); }
    }
    public record Plan(List<Leg> legs, double reusedLength, double newLength, Set<String> routeIds) {
        public Plan { legs = List.copyOf(legs); routeIds = Set.copyOf(routeIds); }
    }
    private record PointKey(long x, long y, long z) {
        static PointKey of(Vec3 p) { return new PointKey(Math.round(p.x * 1e6), Math.round(p.y * 1e6), Math.round(p.z * 1e6)); }
    }
    private record Edge(int to, double length, boolean reused, String routeId) {}
    private record Segment(int route, int index, Vec3 a, Vec3 b) {}
    private record Anchor(Segment segment, double t, Vec3 point, double distance) {}
    private record QueueEntry(int node, double cost) implements Comparable<QueueEntry> {
        public int compareTo(QueueEntry other) { return Double.compare(cost, other.cost); }
    }
    private static final class Graph {
        final List<Vec3> points = new ArrayList<>();
        final List<List<Edge>> edges = new ArrayList<>();
        final Map<PointKey, Integer> ids = new HashMap<>();
        int node(Vec3 p) {
            return ids.computeIfAbsent(PointKey.of(p), k -> { points.add(p); edges.add(new ArrayList<>()); return points.size()-1; });
        }
        void edge(Vec3 a, Vec3 b, boolean reused, String route, boolean both) {
            int x=node(a), y=node(b); if(x==y)return;
            double d=a.distanceTo(b);
            edges.get(x).add(new Edge(y,d,reused,route));
            if(both) edges.get(y).add(new Edge(x,d,reused,route));
        }
    }
    private WaterRouteNetwork() {}
    public static Optional<Plan> plan(List<Route> routes, Vec3 source, Vec3 goal) {
        if(routes==null || routes.isEmpty() || !finite(source) || !finite(goal))return Optional.empty();
        Optional<Plan> forward=build(routes,source,goal,true);
        if(forward.isPresent())return forward;
        // Reverse partial extension is legal only for explicitly bidirectional infrastructure.
        List<Route> reversible=routes.stream().filter(Route::reversible).toList();
        Optional<Plan> reversed=build(reversible,goal,source,true);
        if(reversed.isEmpty())return Optional.empty();
        List<Leg> legs=new ArrayList<>();
        for(int i=reversed.get().legs.size()-1;i>=0;i--) {
            Leg l=reversed.get().legs.get(i); List<Vec3> points=new ArrayList<>(l.points); Collections.reverse(points);
            legs.add(new Leg(points,l.reused));
        }
        Plan r=reversed.get();return Optional.of(new Plan(legs,r.reusedLength,r.newLength,r.routeIds));
    }
    private static Optional<Plan> build(List<Route> routes,Vec3 source,Vec3 goal,boolean partial) {
        int total=0;List<Segment> segments=new ArrayList<>();
        for(int r=0;r<routes.size();r++) {
            Route route=routes.get(r);
            if((total+=route.points.size())>MAX_GRAPH_POINTS)return Optional.empty();
            for(int i=1;i<route.points.size();i++) {
                Vec3 a=route.points.get(i-1),b=route.points.get(i);
                if(!finite(a)||!finite(b))return Optional.empty();
                if(a.distanceToSqr(b)>1e-10)segments.add(new Segment(r,i-1,a,b));
            }
        }
        List<Anchor> starts=anchors(segments,source), ends=anchors(segments,goal);
        if(starts.isEmpty())return Optional.empty();
        Map<Segment,List<Anchor>> cuts=new HashMap<>();
        for(Anchor a:starts)cuts.computeIfAbsent(a.segment,k->new ArrayList<>()).add(a);
        for(Anchor a:ends)cuts.computeIfAbsent(a.segment,k->new ArrayList<>()).add(a);
        Graph graph=new Graph();
        for(Segment segment:segments) {
            Route route=routes.get(segment.route);
            List<Anchor> split=cuts.getOrDefault(segment,List.of()).stream().sorted(Comparator.comparingDouble(Anchor::t)).toList();
            Vec3 previous=segment.a;
            for(Anchor anchor:split) { graph.edge(previous,anchor.point,true,route.id,route.reversible);previous=anchor.point; }
            graph.edge(previous,segment.b,true,route.id,route.reversible);
        }
        // Different berths in the same dock are NOT identical nodes. Their connection
        // remains a gap requiring normal water planning before departure.
        Map<BlockPos,List<Vec3>> docks=new HashMap<>();
        for(Route route:routes)if(route.points.size()>=2) {
            docks.computeIfAbsent(route.sourceDock,k->new ArrayList<>()).add(route.points.getFirst());
            docks.computeIfAbsent(route.targetDock,k->new ArrayList<>()).add(route.points.getLast());
        }
        for(List<Vec3> berths:docks.values())for(int i=0;i<berths.size();i++)for(int j=i+1;j<berths.size();j++) {
            Vec3 a=berths.get(i),b=berths.get(j);
            if(a.distanceToSqr(b)<=ATTACH_RADIUS*ATTACH_RADIUS)graph.edge(a,b,false,"",true);
        }
        for(Anchor anchor:starts)graph.edge(source,anchor.point,false,"",false);
        for(Anchor anchor:ends)graph.edge(anchor.point,goal,false,"",false);
        int start=graph.node(source), target=graph.node(goal), n=graph.points.size();
        double[] best=new double[n];Arrays.fill(best,Double.POSITIVE_INFINITY);best[start]=0;
        int[] parent=new int[n];Arrays.fill(parent,-1);Edge[] incoming=new Edge[n];
        PriorityQueue<QueueEntry> queue=new PriorityQueue<>();queue.add(new QueueEntry(start,0));
        int visits=0;
        while(!queue.isEmpty()) {
            WaterRouteCancellation.check();
            QueueEntry q=queue.poll();if(q.cost!=best[q.node])continue;
            if(++visits>MAX_GRAPH_POINTS)return Optional.empty();
            if(q.node==target)break;
            for(Edge edge:graph.edges.get(q.node)) {
                double cost=q.cost+edge.length*(edge.reused?1.0:1.15);
                if(cost+1e-8<best[edge.to]) {best[edge.to]=cost;parent[edge.to]=q.node;incoming[edge.to]=edge;queue.add(new QueueEntry(edge.to,cost));}
            }
        }
        int finish=target;boolean append=false;
        if(!Double.isFinite(best[target])) {
            if(!partial)return Optional.empty();
            double direct=source.distanceTo(goal), score=direct*1.25;finish=-1;
            for(int i=0;i<n;i++) {
                if(!Double.isFinite(best[i])||i==start)continue;
                double remaining=graph.points.get(i).distanceTo(goal);
                // Useful prefix: significant progress, not an arbitrary detour into the network.
                double candidate=best[i]+remaining*1.15;
                if(direct-remaining>=256 && remaining<=direct*.85 && candidate<score) {score=candidate;finish=i;}
            }
            if(finish<0)return Optional.empty();append=true;
        }
        List<Integer> nodes=new ArrayList<>();List<Edge> links=new ArrayList<>();
        for(int at=finish;at!=start;at=parent[at]) {
            if(at<0||parent[at]<0||nodes.size()>n)return Optional.empty();
            nodes.add(at);links.add(incoming[at]);
        }
        nodes.add(start);Collections.reverse(nodes);Collections.reverse(links);
        List<Leg> legs=new ArrayList<>();Set<String> used=new LinkedHashSet<>();
        List<Vec3> current=new ArrayList<>();boolean reused=false;double reuse=0, fresh=0;
        for(int i=0;i<links.size();i++) {
            Edge e=links.get(i);Vec3 a=graph.points.get(nodes.get(i)),b=graph.points.get(nodes.get(i+1));
            if(current.isEmpty()||reused!=e.reused) {
                if(current.size()>=2)legs.add(new Leg(current,reused));
                current=new ArrayList<>();current.add(a);reused=e.reused;
            }
            current.add(b);
            if(e.reused){reuse+=e.length;used.add(e.routeId);}else fresh+=e.length;
        }
        if(current.size()>=2)legs.add(new Leg(current,reused));
        if(append) {Vec3 last=graph.points.get(finish);legs.add(new Leg(List.of(last,goal),false));fresh+=last.distanceTo(goal);}
        if(reuse<(append?256.0:1.0) || legs.size()>32)return Optional.empty();
        return Optional.of(new Plan(legs,reuse,fresh,used));
    }
    private static List<Anchor> anchors(List<Segment> segments,Vec3 p) {
        List<Anchor> result=new ArrayList<>();
        for(Segment s:segments) {
            Vec3 ab=s.b.subtract(s.a);double length=ab.lengthSqr();
            double t=length==0?0:Math.max(0,Math.min(1,p.subtract(s.a).dot(ab)/length));
            Vec3 at=s.a.add(ab.scale(t));double d=p.distanceTo(at);
            if(d<=ATTACH_RADIUS && Math.abs(p.y-at.y)<=2)result.add(new Anchor(s,t,at,d));
        }
        result.sort(Comparator.comparingDouble(Anchor::distance));
        List<Anchor> unique=new ArrayList<>();Set<PointKey> seen=new HashSet<>();
        // An endpoint already on the infrastructure must not jump to a nearby leg
        // merely to shave a corner. Such invented connectors defeat exact subset reuse.
        boolean onNetwork=!result.isEmpty()&&result.getFirst().distance<=1e-6;
        for(Anchor a:result)if((!onNetwork||a.distance<=1e-6)&&seen.add(PointKey.of(a.point))) {
            unique.add(a);if(unique.size()==8)break;
        }
        return unique;
    }
    private static boolean finite(Vec3 p) {return p!=null&&Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z)
            && Math.abs(p.x)<=30_000_000&&Math.abs(p.z)<=30_000_000;}
}
