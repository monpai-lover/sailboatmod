package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns only route motion state; no native classes, wall clock or flight-control dependency. */
final class RailAircraftMotionState {
    static final double SEGMENT_SECONDS = .05;
    static final double MAX_TARGET_STEP = 16;
    static final int MAX_SESSIONS = 128;

    record Token(UUID owner, UUID physicalId, long generation) { }
    record Acquisition(boolean accepted, String reason, Token token) {
        static Acquisition rejected(String reason) { return new Acquisition(false, reason, null); }
    }
    record Frame(Vec3 anchor, Quaterniond orientation) {
        Frame { orientation = orientation == null ? null : new Quaterniond(orientation); }
        @Override public Quaterniond orientation() { return orientation == null ? null : new Quaterniond(orientation); }
    }
    static final class Session {
        final Object controller, world, body;
        final Direction forward;
        final Token token;
        Frame current, from, target;
        double elapsed;
        long targetTick = Long.MIN_VALUE;
        String reason = "holding";
        boolean detached;
        Object nativePipeline;

        Session(Object controller, Object world, Object body, Direction forward, Token token, Frame frame) {
            this.controller = controller; this.world = world; this.body = body;
            this.forward = forward; this.token = token;
            current = from = target = frame;
        }
    }

    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final IdentityHashMap<Object, Session> bodies = new IdentityHashMap<>();
    private long generation;

    synchronized Acquisition acquire(Object controller, Object world, Object body, UUID owner, UUID physicalId,
                                     Direction forward, Frame actual, boolean predecessorLive) {
        return acquire(controller,world,body,owner,physicalId,forward,actual,predecessorLive,"");
    }

    synchronized Acquisition acquire(Object controller, Object world, Object body, UUID owner, UUID physicalId,
                                     Direction forward, Frame actual, boolean predecessorLive,String foreignReason) {
        if (controller == null || world == null || body == null || owner == null || physicalId == null
                || forward == null || !forward.getAxis().isHorizontal() || !valid(actual))
            return Acquisition.rejected("air_rail_invalid_binding");
        Session existing = sessions.get(physicalId);
        if (existing != null) {
            if (!owner.equals(existing.token.owner()) || existing.world != world)
                return Acquisition.rejected("air_rail_body_owned");
            if (existing.controller == controller && existing.body == body && !existing.detached) {
                return existing.forward == forward ? new Acquisition(true, "", existing.token)
                        : Acquisition.rejected("air_rail_forward_changed");
            }
        }
        // A conflict cannot evict an already accepted hold, nor authorize a fresh/rebound owner.
        if(foreignReason==null||!foreignReason.isEmpty())
            return Acquisition.rejected(foreignReason==null||foreignReason.isBlank()?"flight_control_state_unavailable":foreignReason);
        if(existing!=null) {
            if (predecessorLive && !(existing.detached && existing.controller == controller && existing.body == body))
                return Acquisition.rejected("air_rail_controller_still_loaded");
        } else if (sessions.size() >= MAX_SESSIONS) {
            return Acquisition.rejected("air_rail_capacity");
        }
        if (generation == Long.MAX_VALUE) return Acquisition.rejected("air_rail_generation_exhausted");
        if (existing != null) bodies.remove(existing.body);
        Token token = new Token(owner, physicalId, ++generation);
        Session session = new Session(controller, world, body, forward, token, actual);
        sessions.put(physicalId, session); bodies.put(body, session);
        return new Acquisition(true, "", token);
    }

    synchronized boolean owns(Object controller, Object world, Object body, Token token) {
        Session s = session(token);
        return s != null && s.controller == controller && s.world == world && s.body == body;
    }
    synchronized NativeControlResult status(Object controller, Object world, Token token) {
        Session s = session(token);
        if (s == null || s.controller != controller || s.world != world)
            return NativeControlResult.failure("air_rail_stale_lease");
        return failed(s) ? NativeControlResult.failure(s.reason) : NativeControlResult.applied();
    }

    synchronized Session session(Token token) {
        if (token == null || token.owner() == null || token.physicalId() == null || token.generation() <= 0) return null;
        Session s = sessions.get(token.physicalId());
        return s != null && s.token.equals(token) ? s : null;
    }

    synchronized Session existing(UUID physicalId) { return sessions.get(physicalId); }
    synchronized Session controlled(Object body) { return bodies.get(body); }
    synchronized void pipelineDisposed(Object pipeline) {
        for (var iterator = sessions.values().iterator(); iterator.hasNext();) {
            Session s = iterator.next();
            if (s.nativePipeline == pipeline) { bodies.remove(s.body); iterator.remove(); }
        }
    }
    synchronized void detached(Session s) { if (s != null && session(s.token) == s) s.detached = true; }
    synchronized boolean controls(Object body) { return bodies.containsKey(body); }
    synchronized List<Session> in(Object world) {
        List<Session> out = new ArrayList<>();
        for (Session session : sessions.values()) if (session.world == world) out.add(session);
        return out;
    }

    synchronized NativeControlResult move(Object controller, Object world, Object body, Token token,
                                          Vec3 anchor, Vec3 tangent, double bank, long tick) {
        if (!owns(controller, world, body, token)) return NativeControlResult.failure("air_rail_stale_lease");
        Session s = session(token);
        if (failed(s)) return NativeControlResult.failure(s.reason);
        if (!finite(anchor) || anchor.distanceToSqr(s.current.anchor()) > MAX_TARGET_STEP * MAX_TARGET_STEP)
            return NativeControlResult.failure("air_rail_invalid_target");
        final Quaterniond q;
        try { q = orientation(s.forward, tangent, bank); }
        catch (IllegalArgumentException invalid) { return NativeControlResult.failure("air_rail_invalid_attitude"); }
        if (tick < s.targetTick) return NativeControlResult.failure("air_rail_clock_reversed");
        if (tick == s.targetTick) {
            boolean same = anchor.equals(s.target.anchor()) && Math.abs(q.dot(s.target.orientation)) >= 1 - 1e-12;
            return same ? NativeControlResult.applied() : NativeControlResult.failure("air_rail_duplicate_tick_target");
        }
        s.from = s.current; s.target = new Frame(anchor, q); s.elapsed = 0;
        s.targetTick = tick; s.reason = "moving";
        return NativeControlResult.applied();
    }

    synchronized NativeControlResult hold(Object controller, Object world, Object body, Token token) {
        if (!owns(controller, world, body, token)) return NativeControlResult.failure("air_rail_stale_lease");
        Session s = session(token);
        s.from = s.target = s.current; s.elapsed = 0;
        if (!failed(s)) s.reason = "holding";
        return NativeControlResult.applied();
    }

    synchronized Frame advance(Session s, double dt) {
        if (s == null || session(s.token) != s || !Double.isFinite(dt) || dt <= 0 || dt > SEGMENT_SECONDS + 1e-9)
            throw new IllegalArgumentException("air_rail_invalid_substep");
        s.elapsed = Math.min(SEGMENT_SECONDS, s.elapsed + dt);
        double alpha = s.elapsed / SEGMENT_SECONDS;
        Quaterniond q = new Quaterniond(s.from.orientation).slerp(s.target.orientation, alpha).normalize();
        s.current = new Frame(s.from.anchor().lerp(s.target.anchor(), alpha), q);
        return s.current;
    }

    synchronized void failHolding(Session s, String reason) {
        if (s != null && session(s.token) == s) {
            s.from = s.target = s.current; s.elapsed = 0; s.reason = reason;
        }
    }
    static boolean failed(Session s) {
        return s != null && !s.reason.equals("moving") && !s.reason.equals("holding");
    }

    synchronized boolean release(Object controller, Token token) {
        Session s = session(token);
        if (s == null || s.controller != controller) return false;
        sessions.remove(token.physicalId()); bodies.remove(s.body);
        return true;
    }

    synchronized void clear() { sessions.clear(); bodies.clear(); }

    /** Inverse of the guided pose basis, read from the actual quaternion rather than a planned sample. */
    static double[] attitude(Direction localForward,Quaterniond orientation) {
        if(localForward==null||!localForward.getAxis().isHorizontal()||orientation==null
                ||!Double.isFinite(orientation.x)||!Double.isFinite(orientation.y)
                ||!Double.isFinite(orientation.z)||!Double.isFinite(orientation.w)
                ||orientation.lengthSquared()<1e-16)throw new IllegalArgumentException("air_rail_invalid_attitude");
        var q=new Quaterniond(orientation).normalize();
        var forward=q.transform(new Vector3d(localForward.getStepX(),0,localForward.getStepZ()));
        var right=new Vector3d(forward).cross(0,1,0);
        if(right.lengthSquared()<1e-8)throw new IllegalArgumentException("air_rail_vertical_heading");
        right.normalize();var levelUp=new Vector3d(right).cross(forward).normalize();
        var actualUp=q.transform(new Vector3d(0,1,0));
        return new double[]{Math.asin(Math.max(-1,Math.min(1,forward.y))),
                Math.atan2(actualUp.dot(right),actualUp.dot(levelUp)),Math.atan2(forward.z,forward.x)};
    }

    static Quaterniond orientation(Direction localForward, Vec3 tangent, double bank) {
        if (localForward == null || !localForward.getAxis().isHorizontal() || !finite(tangent)
                || tangent.lengthSqr() < 1e-12 || !Double.isFinite(bank) || Math.abs(bank) > Math.PI / 3)
            throw new IllegalArgumentException("air_rail_invalid_attitude");
        Vector3d f = new Vector3d(tangent.x, tangent.y, tangent.z).normalize();
        Vector3d right = new Vector3d(f).cross(0, 1, 0);
        if (right.lengthSquared() < 1e-8) throw new IllegalArgumentException("air_rail_vertical_heading");
        right.normalize();
        Vector3d up = new Vector3d(right).cross(f).normalize();
        // Positive bank is a right-handed rotation around the desired forward vector.
        Vector3d bankRight = new Vector3d(right).mul(Math.cos(bank)).fma(-Math.sin(bank), up);
        Vector3d bankUp = new Vector3d(up).mul(Math.cos(bank)).fma(Math.sin(bank), right);
        Vector3d localF = new Vector3d(localForward.getStepX(), 0, localForward.getStepZ());
        Vector3d localR = new Vector3d(localF).cross(0, 1, 0);
        Vector3d x = new Vector3d(bankRight).mul(localR.x).fma(localF.x, f);
        Vector3d z = new Vector3d(bankRight).mul(localR.z).fma(localF.z, f);
        return new Matrix3d().setColumn(0, x).setColumn(1, bankUp).setColumn(2, z)
                .getNormalizedRotation(new Quaterniond()).normalize();
    }

    static Vector3d posePosition(Vec3 localAnchor, Vec3 rotationPoint, Vec3 scale,
                                 Quaterniondc orientation, Vec3 desiredAnchor) {
        if (!finite(localAnchor) || !finite(rotationPoint) || !finite(scale) || !finite(desiredAnchor)
                || scale.x <= 0 || scale.y <= 0 || scale.z <= 0 || orientation == null
                || !Double.isFinite(orientation.lengthSquared()) || Math.abs(orientation.lengthSquared() - 1) > 1e-5)
            throw new IllegalArgumentException("air_rail_invalid_pose");
        Vector3d offset = new Vector3d(localAnchor.x - rotationPoint.x, localAnchor.y - rotationPoint.y,
                localAnchor.z - rotationPoint.z).mul(scale.x, scale.y, scale.z);
        orientation.transform(offset);
        Vector3d result = new Vector3d(desiredAnchor.x, desiredAnchor.y, desiredAnchor.z).sub(offset);
        if (!Double.isFinite(result.x) || !Double.isFinite(result.y) || !Double.isFinite(result.z))
            throw new IllegalArgumentException("air_rail_invalid_pose");
        return result;
    }

    static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
    static boolean matches(Frame actual, Direction forward, Vec3 anchor, Vec3 tangent, double bank,
                           double positionTolerance, double angularTolerance) {
        if (!valid(actual) || !finite(anchor) || !Double.isFinite(positionTolerance)
                || positionTolerance < 0 || positionTolerance > 1000 || !Double.isFinite(angularTolerance)
                || angularTolerance < 0 || angularTolerance > Math.PI) return false;
        try {
            Quaterniond expected = orientation(forward, tangent, bank);
            double dot = Math.min(1, Math.abs(new Quaterniond(actual.orientation).normalize().dot(expected)));
            return actual.anchor().distanceToSqr(anchor) <= positionTolerance * positionTolerance
                    && 2 * Math.acos(dot) <= angularTolerance;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    /** Conservative envelope of every original world-box corner over shortest quaternion interpolation. */
    static AABB rotationSweep(Frame from,AABB box,Frame to) {
        if(!valid(from)||!valid(to)||box==null||!finite(new Vec3(box.minX,box.minY,box.minZ))
                ||!finite(new Vec3(box.maxX,box.maxY,box.maxZ))||box.getXsize()<=0||box.getYsize()<=0||box.getZsize()<=0
                ||box.getXsize()>1000||box.getYsize()>1000||box.getZsize()>1000
                ||from.anchor().distanceToSqr(to.anchor())>MAX_TARGET_STEP*MAX_TARGET_STEP)
            throw new IllegalArgumentException("air_rail_invalid_envelope");
        Quaterniond relative=new Quaterniond(to.orientation).mul(new Quaterniond(from.orientation).conjugate());
        double minX=box.minX,minY=box.minY,minZ=box.minZ,maxX=box.maxX,maxY=box.maxY,maxZ=box.maxZ,radius=0;
        for(int i=0;i<8;i++) {
            var corner=new Vector3d((i&1)==0?box.minX:box.maxX,(i&2)==0?box.minY:box.maxY,(i&4)==0?box.minZ:box.maxZ)
                    .sub(from.anchor().x,from.anchor().y,from.anchor().z);
            radius=Math.max(radius,corner.length());
            relative.transform(corner).add(to.anchor().x,to.anchor().y,to.anchor().z);
            minX=Math.min(minX,corner.x);minY=Math.min(minY,corner.y);minZ=Math.min(minZ,corner.z);
            maxX=Math.max(maxX,corner.x);maxY=Math.max(maxY,corner.y);maxZ=Math.max(maxZ,corner.z);
        }
        double angle=2*Math.acos(Math.min(1,Math.abs(from.orientation.dot(to.orientation))));
        double arcSlack=radius*(1-Math.cos(angle*.5));
        return new AABB(minX,minY,minZ,maxX,maxY,maxZ).inflate(arcSlack);
    }
    static boolean valid(Frame frame) {
        if (frame == null || !finite(frame.anchor()) || frame.orientation == null) return false;
        Quaterniond q = frame.orientation;
        double norm = q.lengthSquared();
        return Double.isFinite(norm) && Math.abs(norm - 1) <= 1e-5;
    }
}
