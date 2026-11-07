package com.monpai.sailboatmod.transport.air.fuel;

import java.util.*;
import net.minecraft.core.BlockPos;

/** Pure admission budget. Available ticks and actual power readiness must come from independently verified native engines. */
public final class AircraftFuelBudget {
    public static final int DEFAULT_RESERVE_PERCENT=20;
    private static final long ARRIVAL_DWELL_TICKS=40,MINIMUM_RESERVE_TICKS=1200;
    private AircraftFuelBudget() { }

    /** A negative availableBurnTicks represents unknown fuel, never an unlimited source. Reason is diagnostic detail. */
    public record Engine(BlockPos offset,long availableBurnTicks,boolean powerReady,String reason) {
        public Engine {if(offset!=null)offset=offset.immutable();reason=reason==null?"":reason;}
    }
    /** shortfallTicks is -1 for an unknown amount, or 0 when sufficient fuel awaits actual power readiness. */
    public record Deficit(BlockPos offset,long availableTicks,long shortfallTicks,String reason) {
        public Deficit {if(offset!=null)offset=offset.immutable();reason=reason==null?"":reason;}
    }
    /** Unknown budget/coverage fields are -1. Zero availableTicks with no engines is not an admitted aircraft. */
    public record Result(boolean ready,long requiredTicks,long reserveTicks,long availableTicks,String reason,List<Deficit> deficits) {
        public Result {deficits=List.copyOf(deficits);}
    }

    /** returnTicks=0 means no return leg. A positive return uses its supplied actual path duration and its own dwell. */
    public static Result evaluate(List<Engine> engines,long outboundTicks,long returnTicks,int reservePercent) {
        if(outboundTicks<0||returnTicks<0||reservePercent<0)return unavailable("fuel_budget_invalid");
        final long required,reserve;
        try {
            long movement=Math.addExact(outboundTicks,returnTicks);
            long base=Math.addExact(movement,returnTicks>0?2*ARRIVAL_DWELL_TICKS:ARRIVAL_DWELL_TICKS);
            long scaled=Math.multiplyExact(base,(long)reservePercent);
            long percentage=Math.addExact(scaled/100,scaled%100==0?0:1);
            reserve=Math.max(MINIMUM_RESERVE_TICKS,percentage);required=Math.addExact(base,reserve);
        } catch(ArithmeticException overflow) {return unavailable("fuel_budget_overflow");}
        if(engines==null||engines.isEmpty())return new Result(false,required,reserve,0,"fuel_no_engines",List.of());
        var seen=new HashSet<BlockPos>();var deficits=new ArrayList<Deficit>();long minimum=Long.MAX_VALUE;
        boolean invalid=false,unknown=false,shortFuel=false,waitingPower=false;
        for(var engine:engines) {
            if(engine==null||engine.offset()==null||!seen.add(engine.offset())) {
                invalid=true;deficits.add(new Deficit(engine==null?null:engine.offset(),-1,-1,"fuel_engine_identity_invalid"));continue;
            }
            long available=engine.availableBurnTicks();
            if(available<0) {
                unknown=true;deficits.add(new Deficit(engine.offset(),available,-1,detail(engine,"fuel_unknown")));continue;
            }
            minimum=Math.min(minimum,available);
            if(available<required) {
                shortFuel=true;deficits.add(new Deficit(engine.offset(),available,required-available,detail(engine,"fuel_insufficient")));
            } else if(!engine.powerReady()) {
                waitingPower=true;deficits.add(new Deficit(engine.offset(),available,0,detail(engine,"fuel_power_not_ready")));
            }
        }
        if(invalid)return new Result(false,required,reserve,-1,"fuel_budget_invalid",deficits);
        if(unknown)return new Result(false,required,reserve,-1,"fuel_unknown",deficits);
        if(shortFuel)return new Result(false,required,reserve,minimum,"fuel_insufficient",deficits);
        if(waitingPower)return new Result(false,required,reserve,minimum,"fuel_power_not_ready",deficits);
        return new Result(true,required,reserve,minimum,"",List.of());
    }
    private static String detail(Engine engine,String fallback){return engine.reason().isBlank()?fallback:engine.reason();}
    private static Result unavailable(String reason){return new Result(false,-1,-1,-1,reason,List.of());}
}
