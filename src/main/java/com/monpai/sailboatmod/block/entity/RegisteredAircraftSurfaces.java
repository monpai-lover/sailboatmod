package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.compat.aeronautics.RailAircraftSurfaceBridge;
import com.monpai.sailboatmod.transport.air.surface.*;
import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Explicit surface configuration and transient native leases. The caller gates edits/clear and owns body motion. */
final class RegisteredAircraftSurfaces {
    record Context(BlockEntity controller,UUID owner,UUID bodyId,Direction localForward) {
        Context {if(controller==null||owner==null||bodyId==null||localForward==null||!localForward.getAxis().isHorizontal())throw new IllegalArgumentException("surface_invalid_context");}
        BlockPos absolute(BlockPos offset){return controller.getBlockPos().offset(offset);}
    }
    interface NativeAccess {
        Direction.Axis axis(Context c,BlockPos p);
        RailAircraftSurfaceBridge.Result claim(Context c,BlockPos p,double neutral);
        Optional<RailAircraftSurfaceBridge.Readback> read(Context c,RailAircraftSurfaceBridge.Lease l);
        NativeControlResult target(Context c,RailAircraftSurfaceBridge.Lease l,double angle,double rate);
        NativeControlResult hold(Context c,RailAircraftSurfaceBridge.Lease l);
        NativeControlResult neutral(Context c,RailAircraftSurfaceBridge.Lease l);
        NativeControlResult clear(Context c,RailAircraftSurfaceBridge.Lease l);
    }
    private final NativeAccess api;
    private final Map<BlockPos,RailAircraftSurfaceBridge.Lease> leases=new HashMap<>();
    private final Map<BlockPos,String> errors=new HashMap<>();
    private AircraftSurfaceProfile profile;
    private AircraftSurfaceController.State state=AircraftSurfaceController.State.initial();
    private boolean invalid;
    private CompoundTag invalidState;
    private BlockPos lastOrigin;
    private String reason="";
    private long lastTick=-1;
    private NativeControlResult lastResult=NativeControlResult.applied();

    RegisteredAircraftSurfaces(){this(new Native());}
    RegisteredAircraftSurfaces(NativeAccess api){this.api=Objects.requireNonNull(api);}
    Optional<AircraftSurfaceProfile> profile(){return Optional.ofNullable(profile);}
    List<AircraftSurfaceBinding> bindings(){return profile==null?List.of():profile.bindings();}
    boolean invalid(){return invalid;}
    String reason(){return reason;}

    CompoundTag save() {
        if(invalid){var t=invalidState==null?new CompoundTag():invalidState.copy();t.putBoolean("Invalid",true);return t;}
        var t=new CompoundTag();if(profile!=null)t.put("Profile",profile.save());return t;
    }
    static RegisteredAircraftSurfaces load(CompoundTag t){return load(t,new Native());}
    static RegisteredAircraftSurfaces load(CompoundTag t,NativeAccess api) {
        var result=new RegisteredAircraftSurfaces(api);
        try {
            if(t==null||t.contains("Invalid"))throw new IllegalArgumentException("surface_invalid_saved_profile");
            if(t.contains("Profile")) {
                if(!t.contains("Profile",Tag.TAG_COMPOUND))throw new IllegalArgumentException("surface_invalid_saved_profile");
                result.profile=AircraftSurfaceProfile.load(t.getCompound("Profile"));
            }
        } catch(IllegalArgumentException malformed) {
            result.invalid=true;result.invalidState=t==null?new CompoundTag():t.copy();result.reason="surface_invalid_saved_profile";
        }
        return result;
    }

    /** Caller checks management permission and stopped/paused state. Claiming requires its existing body motion lease. */
    NativeControlResult bind(Context c,AircraftSurfaceBinding supplied) {
        remember(c);String check=configuration(c);if(!check.isEmpty())return reject(check,null);
        if(supplied==null)return reject("surface_invalid_binding",null);
        var old=find(supplied.localOffset());
        if(old!=null&&old.role()!=supplied.role())return reject("surface_position_already_bound",supplied.localOffset());
        if(old!=null&&Double.compare(old.neutralAngleDeg(),supplied.neutralAngleDeg())!=0)
            return reject("surface_clear_before_neutral_change",supplied.localOffset());
        var binding=new AircraftSurfaceBinding(supplied.role(),supplied.localOffset(),supplied.neutralAngleDeg(),supplied.polarity(),supplied.maxDeflectionDeg(),supplied.rateDegPerSecond(),AircraftSurfaceBinding.Mode.AUTO);
        var candidate=new ArrayList<>(bindings());if(old!=null)candidate.set(candidate.indexOf(old),binding);else candidate.add(binding);
        AircraftSurfaceProfile next;
        try{next=new AircraftSurfaceProfile(c.bodyId(),c.localForward(),candidate);}catch(IllegalArgumentException bad){return reject("surface_invalid_binding",binding.localOffset());}
        if(!ensure(c,binding))return fail(c,reason,binding.localOffset());
        profile=next;reset();errors.remove(binding.localOffset());reason="";return NativeControlResult.applied();
    }
    /** REMOVE means return to neutral and retain the saved explicit binding as HOLD. */
    NativeControlResult hold(Context c,AircraftSurfaceBinding.Role role,BlockPos offset) {
        var binding=selected(c,role,offset);if(binding==null)return fail(c,reason,offset);
        if(!ensure(c,binding))return fail(c,reason,offset);
        var result=requestNeutral(c,binding,leases.get(offset));if(!result.success())return fail(c,result.reason(),offset);
        replace(new AircraftSurfaceBinding(binding.role(),offset,binding.neutralAngleDeg(),binding.polarity(),binding.maxDeflectionDeg(),binding.rateDegPerSecond(),AircraftSurfaceBinding.Mode.HOLD));
        errors.remove(offset);reason="";reset();return NativeControlResult.applied();
    }
    /** Caller alone proves stopped/authorized. Native clear succeeds before saved configuration is removed. */
    NativeControlResult clear(Context c,AircraftSurfaceBinding.Role role,BlockPos offset) {
        var binding=selected(c,role,offset);if(binding==null)return fail(c,reason,offset);
        if(!ensure(c,binding))return fail(c,reason,offset);
        var result=api.clear(c,leases.get(offset));if(!result.success())return fail(c,result.reason(),offset);
        var remaining=new ArrayList<>(bindings());remaining.remove(binding);
        profile=remaining.isEmpty()?null:new AircraftSurfaceProfile(profile.bodyId(),profile.localForward(),remaining);
        leases.remove(offset);errors.remove(offset);reason="";reset();return NativeControlResult.applied();
    }

    /** Once per fresh game tick, after caller verifies motion authority. Paused input may be null. */
    NativeControlResult tick(Context c,long tick,AircraftSurfaceController.Input input,boolean paused) {
        remember(c);if(!invalid&&bindings().isEmpty()){reason="";return NativeControlResult.applied();}
        String check=configuration(c);if(!check.isEmpty())return fail(c,check,null);
        if(tick<0||lastTick>tick)return fail(c,"surface_tick_changed",null);
        if(tick==lastTick)return lastResult;
        if(lastTick>=0&&tick-lastTick>1)state=AircraftSurfaceController.State.initial();
        lastTick=tick;
        if(bindings().isEmpty())return lastResult=NativeControlResult.applied();
        if(paused)return lastResult=pause(c);
        if(input==null)return lastResult=fail(c,"surface_actual_attitude_unavailable",null);
        if(!ensureAll(c))return lastResult=fail(c,reason,null);
        AircraftSurfaceController.Result desired;
        try{desired=AircraftSurfaceController.step(profile,state,input);}catch(IllegalArgumentException bad){return lastResult=fail(c,"surface_invalid_sample",null);}
        for(var command:desired.commands()) {
            var result=api.target(c,leases.get(command.binding().localOffset()),command.targetAngleDeg(),command.rateDegPerSecond());
            if(!result.success())return lastResult=fail(c,result.reason(),command.binding().localOffset());
        }
        state=desired.state();errors.clear();reason="";return lastResult=NativeControlResult.applied();
    }
    NativeControlResult pause(Context c){return stationary(c,false);}
    /** Call before releasing body authority. Native retains its finite neutral lock after the owner stops. */
    NativeControlResult neutral(Context c){return stationary(c,true);}
    private NativeControlResult stationary(Context c,boolean neutral) {
        remember(c);state=AircraftSurfaceController.State.initial();if(!invalid&&bindings().isEmpty()){reason="";return NativeControlResult.applied();}
        String check=configuration(c);if(!check.isEmpty())return fail(c,check,null);
        if(!ensureAll(c))return fail(c,reason,null);
        for(var b:bindings()) {
            var result=neutral||b.mode()==AircraftSurfaceBinding.Mode.HOLD?requestNeutral(c,b,leases.get(b.localOffset())):api.hold(c,leases.get(b.localOffset()));
            if(!result.success())return fail(c,result.reason(),b.localOffset());
        }
        errors.clear();reason="";return NativeControlResult.applied();
    }

    /** Cached leases are inspected only; a screen read never claims or advances a servo. Missing angles remain absent. */
    ListTag screenData(Context c){return screenData(c==null?lastOrigin:c.controller().getBlockPos(),c);}
    ListTag screenData(BlockPos origin,Context c) {
        if(origin!=null)lastOrigin=origin.immutable();
        var rows=new ListTag();
        for(var b:bindings()) {
            var row=new CompoundTag();row.putString("Role",b.role().name());if(origin!=null)row.putLong("Position",origin.offset(b.localOffset()).asLong());
            row.putDouble("Neutral",b.neutralAngleDeg());row.putInt("Polarity",b.polarity());row.putDouble("Max",b.maxDeflectionDeg());row.putDouble("Rate",b.rateDegPerSecond());row.putString("Mode",b.mode().name());
            var lease=leases.get(b.localOffset());var read=lease==null||c==null?Optional.<RailAircraftSurfaceBridge.Readback>empty():api.read(c,lease);
            boolean valid=c!=null&&lease!=null&&read.isPresent()&&valid(c,lease,read.get());
            String why=errors.getOrDefault(b.localOffset(),valid?read.get().reason():"surface_awaiting_rebind");
            if(!configuration(c).isEmpty()){valid=false;why=configuration(c);}
            boolean healthy=valid&&why!=null&&(why.isEmpty()||why.equals("surface_tracking")||why.equals("surface_holding")||why.equals("surface_neutral_hold"));
            row.putBoolean("Available",healthy);row.putString("Reason",why==null?"surface_native_state_unavailable":why);
            if(valid){row.putDouble("ActualAngle",read.get().actualAngleDegrees());row.putDouble("TargetAngle",read.get().targetAngleDegrees());}
            rows.add(row);
        }
        return rows;
    }
    private boolean ensureAll(Context c){for(var b:bindings())if(!ensure(c,b))return false;return true;}
    private boolean ensure(Context c,AircraftSurfaceBinding b) {
        BlockPos position=c.absolute(b.localOffset());
        if(!b.axisCompatible(c.localForward(),api.axis(c,position))){reason="surface_hinge_axis_mismatch";errors.put(b.localOffset(),reason);return false;}
        var lease=leases.get(b.localOffset());
        if(lease!=null) {
            var read=api.read(c,lease);if(read.isPresent()&&valid(c,lease,read.get()))return true;
            leases.remove(b.localOffset());
        }
        var claimed=api.claim(c,position,b.neutralAngleDeg());
        if(!claimed.accepted()||claimed.lease()==null){reason=safeReason(claimed.reason());errors.put(b.localOffset(),reason);return false;}
        lease=claimed.lease();leases.put(b.localOffset(),lease);
        var read=api.read(c,lease);
        if(read.isEmpty()||!valid(c,lease,read.get())){reason="surface_native_readback_unavailable";errors.put(b.localOffset(),reason);return false;}
        return true;
    }
    private static boolean valid(Context c,RailAircraftSurfaceBridge.Lease lease,RailAircraftSurfaceBridge.Readback v) {
        return lease.owner().equals(c.owner())&&lease.physicalId().equals(c.bodyId())&&v.active()&&c.owner().equals(v.owner())&&c.bodyId().equals(v.physicalId())
                &&Double.isFinite(v.actualAngleDegrees())&&Double.isFinite(v.targetAngleDegrees())&&Double.isFinite(v.rateDegreesPerSecond())&&v.rateDegreesPerSecond()>0&&v.rateDegreesPerSecond()<=180;
    }
    private String configuration(Context c) {
        if(invalid)return "surface_invalid_saved_profile";
        if(c==null)return "surface_invalid_context";
        if(profile!=null&&!profile.bodyId().equals(c.bodyId()))return "surface_body_changed";
        if(profile!=null&&profile.localForward()!=c.localForward())return "surface_forward_changed";
        return "";
    }
    private AircraftSurfaceBinding selected(Context c,AircraftSurfaceBinding.Role role,BlockPos offset) {
        remember(c);String check=configuration(c);if(!check.isEmpty()){reason=check;return null;}
        var binding=find(offset);if(binding==null||binding.role()!=role){reason="surface_binding_not_found";return null;}return binding;
    }
    private AircraftSurfaceBinding find(BlockPos offset){return bindings().stream().filter(b->b.localOffset().equals(offset)).findFirst().orElse(null);}
    private void replace(AircraftSurfaceBinding b){var list=new ArrayList<>(bindings());list.set(list.indexOf(find(b.localOffset())),b);profile=new AircraftSurfaceProfile(profile.bodyId(),profile.localForward(),list);}
    private NativeControlResult fail(Context c,String why,BlockPos offset) {
        reject(why,offset);
        if(c!=null)for(var entry:leases.entrySet()) {
            var binding=find(entry.getKey());
            if(binding!=null&&binding.mode()==AircraftSurfaceBinding.Mode.HOLD) {
                if(!requestNeutral(c,binding,entry.getValue()).success())api.hold(c,entry.getValue());
            } else api.hold(c,entry.getValue());
        }
        return NativeControlResult.failure(reason);
    }
    private NativeControlResult reject(String why,BlockPos offset){reason=safeReason(why);if(offset!=null)errors.put(offset,reason);state=AircraftSurfaceController.State.initial();return NativeControlResult.failure(reason);}
    private void remember(Context c){if(c!=null)lastOrigin=c.controller().getBlockPos().immutable();}
    private NativeControlResult requestNeutral(Context c,AircraftSurfaceBinding binding,RailAircraftSurfaceBridge.Lease lease) {
        var read=api.read(c,lease);
        if(read.isEmpty()||!valid(c,lease,read.get()))return NativeControlResult.failure("surface_native_readback_unavailable");
        // Native claim starts with its own default rate. Prime the configured limit once before neutral can move.
        if(Double.compare(read.get().rateDegreesPerSecond(),binding.rateDegPerSecond())!=0) {
            double angle=binding.neutralAngleDeg()%360;if(angle>=180)angle-=360;else if(angle< -180)angle+=360;
            var primed=api.target(c,lease,angle,binding.rateDegPerSecond());if(!primed.success())return primed;
        }
        return api.neutral(c,lease);
    }
    private static String safeReason(String value){return value==null||value.isBlank()?"surface_native_state_unavailable":value;}
    private void reset(){state=AircraftSurfaceController.State.initial();lastTick=-1;lastResult=NativeControlResult.applied();}

    private static final class Native implements NativeAccess {
        public Direction.Axis axis(Context c,BlockPos p) {
            if(!(c.controller().getLevel() instanceof ServerLevel world))return null;
            var chunk=world.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);if(chunk==null)return null;
            var entity=chunk.getBlockEntities().get(p);if(entity==null||!entity.getBlockState().hasProperty(BlockStateProperties.FACING))return null;
            return entity.getBlockState().getValue(BlockStateProperties.FACING).getAxis();
        }
        public RailAircraftSurfaceBridge.Result claim(Context c,BlockPos p,double neutral){return RailAircraftSurfaceBridge.claim(c.controller(),c.owner(),c.bodyId(),p,neutral);}
        public Optional<RailAircraftSurfaceBridge.Readback> read(Context c,RailAircraftSurfaceBridge.Lease l){return RailAircraftSurfaceBridge.readback(c.controller(),l);}
        public NativeControlResult target(Context c,RailAircraftSurfaceBridge.Lease l,double angle,double rate){return RailAircraftSurfaceBridge.setTarget(c.controller(),l,angle,rate);}
        public NativeControlResult hold(Context c,RailAircraftSurfaceBridge.Lease l){return RailAircraftSurfaceBridge.hold(c.controller(),l);}
        public NativeControlResult neutral(Context c,RailAircraftSurfaceBridge.Lease l){return RailAircraftSurfaceBridge.release(c.controller(),l);}
        public NativeControlResult clear(Context c,RailAircraftSurfaceBridge.Lease l){return RailAircraftSurfaceBridge.clearBinding(c.controller(),l);}
    }
}
