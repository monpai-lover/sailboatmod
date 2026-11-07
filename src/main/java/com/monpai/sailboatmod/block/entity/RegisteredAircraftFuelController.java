package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.compat.aeronautics.AircraftFuelBridge;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.fuel.*;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Transient real-fuel admission and UI cache. No saved observations, fuel mutations or movement actions. */
final class RegisteredAircraftFuelController {
    record Context(BlockEntity controller,UUID expectedBodyId,AircraftClass airframe) {
        Context(BlockEntity controller,UUID expectedBodyId){this(controller,expectedBodyId,AircraftClass.FIXED_WING);}
    }
    interface NativeAccess {long gameTick(Context c);AircraftFuelBridge.Observation inspect(Context c);}
    private record DurationKey(String pathHash,long fromBits) { }
    private record Plan(Context context,long outboundTicks,long returnTicks,boolean returning) { }
    private static final int MAX_DURATION_CACHE=8;
    private final NativeAccess api;
    private final Map<DurationKey,Long> durationTicks=new LinkedHashMap<>(16,.75f,true);
    private Plan plan;
    private String planFailure="";
    private Context observedContext;
    private long observedAt=-1;
    private AircraftFuelBridge.Observation observation;
    private CompoundTag screen=blank();
    private NativeControlResult admission=NativeControlResult.failure("fuel_unknown");

    RegisteredAircraftFuelController(){this(new Native());}
    RegisteredAircraftFuelController(NativeAccess api){this.api=Objects.requireNonNull(api);}

    /** Explicit start/resume admission always observes fresh fuel, even when called twice in one game tick. */
    NativeControlResult check(Context c,RailAircraftPath path,double from,RailAircraftPath returning) {
        if(!valid(c)){unavailable("fuel_controller_not_loaded");return contextUnavailable();}
        plan=null;planFailure="";
        try {
            long outbound=duration(path,from),back=returning==null?0:duration(returning,0);
            if(returning!=null&&back<=0)throw new IllegalArgumentException("fuel_duration_invalid");
            plan=new Plan(c,outbound,back,returning!=null);
        } catch(IllegalArgumentException unknown) {planFailure=safe(unknown.getMessage(),"fuel_unknown");}
        refresh(c);return admission;
    }

    /** Polling refreshes actual hardware at most once per20 game ticks and never invents a missing plan. */
    NativeControlResult poll(Context c) {
        if(!valid(c))return contextUnavailable();
        if(plan!=null&&!same(plan.context(),c))unavailable("fuel_body_changed");
        long now;
        try{now=api.gameTick(c);}catch(RuntimeException|LinkageError unavailable){return contextUnavailable();}
        if(now<0)return contextUnavailable();
        if(same(observedContext,c)&&observedAt>=0&&now>=observedAt&&now-observedAt<20)return admission;
        refresh(c);return admission;
    }

    /** Runtime health only: real empty/unknown fuel or lost actual power stops; reserve shortage alone does not. */
    NativeControlResult exhausted(Context c) {
        if(!valid(c))return contextUnavailable();
        if(plan!=null&&!same(plan.context(),c))unavailable("fuel_body_changed");
        refresh(c);return health(c,observation);
    }

    /** Records a plan/return-geometry failure without reading any world or native state. */
    NativeControlResult unavailable(String reason) {
        plan=null;planFailure=safe(reason,"fuel_unknown");screen.putBoolean("Evaluated",true);screen.putBoolean("Ready",false);screen.putString("Reason",planFailure);
        for(String key:List.of("PlannedTicks","ReturnTicks","ReserveTicks","RequiredTicks","AvailableTicks"))screen.putLong(key,-1);
        markEngineRowsUnknown(planFailure);return admission=NativeControlResult.failure(planFailure);
    }
    void invalidate() {
        plan=null;planFailure="";observedContext=null;observedAt=-1;observation=null;durationTicks.clear();screen=blank();admission=NativeControlResult.failure("fuel_unknown");
    }
    /** Getter is strictly a defensive copy; it never refreshes a fuel API, saved-data row or duration estimate. */
    CompoundTag cachedScreenData(){return screen.copy();}

    private long duration(RailAircraftPath path,double from) {
        if(path==null||!Double.isFinite(from)||from<0||from>path.length())throw new IllegalArgumentException("fuel_duration_invalid");
        var key=new DurationKey(path.fingerprint(),Double.doubleToLongBits(from));Long cached=durationTicks.get(key);if(cached!=null)return cached;
        long ticks=RailFlightDuration.estimateTicks(path,from);durationTicks.put(key,ticks);
        if(durationTicks.size()>MAX_DURATION_CACHE)durationTicks.remove(durationTicks.keySet().iterator().next());return ticks;
    }
    private void refresh(Context c) {
        long tick=-1;
        try {
            tick=api.gameTick(c);observation=api.inspect(c);
            if(tick<0||observation==null)observation=unknown("fuel_controller_not_loaded");
        } catch(RuntimeException|LinkageError unavailable) {observation=unknown("fuel_native_state_unavailable");}
        observedContext=c;observedAt=tick;buildScreen(c,observation);
    }
    private void buildScreen(Context c,AircraftFuelBridge.Observation actual) {
        var health=health(c,actual);boolean known=actual.available()&&c.expectedBodyId().equals(actual.bodyId());
        List<AircraftFuelBudget.Engine> engines=new ArrayList<>();
        for(var e:actual.engines())engines.add(new AircraftFuelBudget.Engine(e.absolutePosition().subtract(c.controller().getBlockPos()),e.availableBurnTicks(),e.powered(),e.reason()));
        AircraftFuelBudget.Result budget=plan==null?null:AircraftFuelBudget.evaluate(engines,plan.outboundTicks(),plan.returnTicks(),AircraftFuelBudget.DEFAULT_RESERVE_PERCENT);
        String why;
        if(!planFailure.isEmpty())why=planFailure;
        else if(plan==null)why=health.success()?"fuel_unknown":health.reason();
        else if(!known)why=health.reason();
        else if(!budget.ready()&&!budget.reason().equals("fuel_power_not_ready"))why=budget.reason();
        else if(!health.success())why=health.reason();
        else why=budget.ready()?"":budget.reason();
        boolean ready=plan!=null&&why.isEmpty();var next=blank();next.putBoolean("Evaluated",plan!=null||!planFailure.isEmpty());next.putBoolean("Ready",ready);next.putString("Reason",why);
        if(plan!=null) {
            next.putLong("PlannedTicks",plan.outboundTicks()+40);next.putLong("ReturnTicks",plan.returning()?plan.returnTicks()+40:0);
            next.putLong("ReserveTicks",budget.reserveTicks());next.putLong("RequiredTicks",budget.requiredTicks());
        }
        next.putLong("AvailableTicks",known?minimum(actual.engines()):-1);long required=budget==null?-1:budget.requiredTicks();var rows=new ListTag();
        for(var e:actual.engines()) {
            var row=new CompoundTag();row.putLong("Position",e.absolutePosition().asLong());row.putLong("AvailableTicks",known?e.availableBurnTicks():-1);row.putLong("RequiredTicks",required);
            boolean engineReady=known&&required>=0&&e.availableBurnTicks()>=required&&e.powered();row.putBoolean("Ready",engineReady);
            String engineReason=engineReady?"":!known?health.reason():e.availableBurnTicks()<0?safe(e.reason(),"fuel_unknown"):required<0?safe(planFailure,"fuel_unknown")
                    :e.availableBurnTicks()<required?safe(e.reason(),"fuel_insufficient"):safe(e.reason(),"fuel_power_not_ready");
            row.putString("Reason",engineReason);rows.add(row);
        }
        next.put("Engines",rows);var propellers=new ListTag();
        for(var p:actual.propellers()) {
            var row=new CompoundTag();row.putLong("Position",p.absolutePosition().asLong());row.putBoolean("Assembled",p.assembled());row.putBoolean("Moving",known&&p.moving()&&Double.isFinite(p.actualRpm()));
            if(known&&Double.isFinite(p.actualRpm()))row.putDouble("Rpm",p.actualRpm());
            row.putString("Reason",!known?health.reason():safe(p.reason(),!Double.isFinite(p.actualRpm())?"fuel_native_state_unavailable":!p.assembled()?"fuel_propeller_unassembled":!p.moving()?"fuel_propeller_stopped":""));propellers.add(row);
        }
        next.put("Propellers",propellers);screen=next;admission=ready?NativeControlResult.applied():NativeControlResult.failure(why);
    }
    private static NativeControlResult health(Context c,AircraftFuelBridge.Observation actual) {
        if(!valid(c)||actual==null)return NativeControlResult.failure("fuel_controller_not_loaded");
        if(actual.bodyId()!=null&&!c.expectedBodyId().equals(actual.bodyId()))return NativeControlResult.failure("fuel_body_changed");
        if(!actual.available())return NativeControlResult.failure(safe(actual.reason(),"fuel_unknown"));
        if(actual.bodyId()==null)return NativeControlResult.failure("fuel_unknown");
        if(actual.engines().isEmpty())return NativeControlResult.failure("fuel_no_engines");
        for(var e:actual.engines()) {
            if(e.availableBurnTicks()<0)return NativeControlResult.failure(safe(e.reason(),"fuel_unknown"));
            if(e.availableBurnTicks()==0)return NativeControlResult.failure("fuel_empty");
            if(!e.powered())return NativeControlResult.failure(safe(actual.reason(),safe(e.reason(),"fuel_power_not_ready")));
        }
        if(actual.propellers().isEmpty())return NativeControlResult.failure("fuel_propeller_missing");
        for(var p:actual.propellers()) {
            if(!Double.isFinite(p.actualRpm()))return NativeControlResult.failure("fuel_native_state_unavailable");
            if(!p.assembled())return NativeControlResult.failure("fuel_propeller_unassembled");
            if(!p.moving()||Math.abs(p.actualRpm())<1e-5)return NativeControlResult.failure("fuel_propeller_stopped");
        }
        return actual.powerReady()?NativeControlResult.applied():NativeControlResult.failure(safe(actual.reason(),"fuel_power_not_ready"));
    }
    private NativeControlResult contextUnavailable() {
        observedContext=null;observedAt=-1;observation=null;screen.putBoolean("Ready",false);screen.putString("Reason","fuel_controller_not_loaded");screen.putLong("AvailableTicks",-1);
        markEngineRowsUnknown("fuel_controller_not_loaded");return admission=NativeControlResult.failure("fuel_controller_not_loaded");
    }
    private void markEngineRowsUnknown(String why) {
        for(Tag raw:screen.getList("Engines",Tag.TAG_COMPOUND)) {var row=(CompoundTag)raw;row.putLong("AvailableTicks",-1);row.putLong("RequiredTicks",-1);row.putBoolean("Ready",false);row.putString("Reason",why);}
    }
    private static CompoundTag blank() {
        var t=new CompoundTag();t.putBoolean("Evaluated",false);t.putBoolean("Ready",false);t.putString("Reason","fuel_unknown");
        for(String key:List.of("PlannedTicks","ReturnTicks","ReserveTicks","RequiredTicks","AvailableTicks"))t.putLong(key,-1);
        t.put("Engines",new ListTag());t.put("Propellers",new ListTag());return t;
    }
    private static long minimum(List<AircraftFuelBridge.Engine> engines){if(engines.isEmpty())return 0;long min=Long.MAX_VALUE;for(var e:engines){if(e.availableBurnTicks()<0)return -1;min=Math.min(min,e.availableBurnTicks());}return min;}
    private static AircraftFuelBridge.Observation unknown(String why){return new AircraftFuelBridge.Observation(false,why,null,List.of(),List.of(),false);}
    private static boolean valid(Context c){return c!=null&&c.controller()!=null&&c.expectedBodyId()!=null&&c.airframe()!=null;}
    private static boolean same(Context a,Context b){return valid(a)&&valid(b)&&a.controller()==b.controller()&&a.expectedBodyId().equals(b.expectedBodyId())&&a.airframe()==b.airframe();}
    private static String safe(String reason,String fallback){return reason==null||reason.isBlank()?fallback:reason;}
    private static final class Native implements NativeAccess {
        public long gameTick(Context c){return c.controller().getLevel()==null?-1:c.controller().getLevel().getGameTime();}
        public AircraftFuelBridge.Observation inspect(Context c){return AircraftFuelBridge.inspect(c.controller(),c.airframe());}
    }
}
