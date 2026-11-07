package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.transport.air.AircraftClass;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only real fuel observation; never refuels, generates power or assembles a part. */
public final class AircraftFuelBridge {
    public record Engine(BlockPos absolutePosition,long availableBurnTicks,boolean powered,String reason) {
        public Engine { absolutePosition=absolutePosition.immutable(); }
    }
    public record Propeller(BlockPos absolutePosition,double actualRpm,boolean assembled,boolean moving,String reason) {
        public Propeller { absolutePosition=absolutePosition.immutable(); }
    }
    public record Observation(boolean available,String reason,UUID bodyId,List<Engine> engines,List<Propeller> propellers,boolean powerReady) {
        public Observation { engines=List.copyOf(engines);propellers=List.copyOf(propellers); }
    }
    private static volatile AircraftFuelReflectionAccess access;
    private static volatile boolean attempted;
    private AircraftFuelBridge(){}
    public static Observation inspect(BlockEntity controller){
        return inspect(controller,AircraftClass.FIXED_WING);
    }
    public static Observation inspect(BlockEntity controller,AircraftClass airframe){
        if(airframe==null||!loaded(controller))return unavailable("fuel_controller_not_loaded");
        var api=api();if(api==null)return unavailable("fuel_native_abi_unavailable");
        try{return api.inspect(controller,airframe);}
        catch(ReflectiveOperationException|RuntimeException|LinkageError failure){
            String reason=failure.getMessage();return unavailable(reason!=null&&reason.startsWith("fuel_")?reason:"fuel_native_state_unavailable");
        }
    }
    private static Observation unavailable(String reason){return new Observation(false,reason,null,List.of(),List.of(),false);}
    private static boolean loaded(BlockEntity c){
        if(c==null||c.isRemoved()||!(c.getLevel() instanceof ServerLevel world)||world.getServer()==null||!world.getServer().isSameThread())return false;
        var chunk=world.getChunkSource().getChunkNow(c.getBlockPos().getX()>>4,c.getBlockPos().getZ()>>4);
        return chunk!=null&&chunk.getBlockEntities().get(c.getBlockPos())==c;
    }
    private static synchronized AircraftFuelReflectionAccess api(){
        if(access!=null)return access;if(attempted)return null;
        var mods=ModList.get();
        if(mods==null||!mods.isLoaded("create")||!mods.isLoaded("sable")||!mods.isLoaded("aeronautics")||!mods.isLoaded("simulated"))return null;
        attempted=true;
        try{access=AircraftFuelReflectionAccess.bind(name->Class.forName(name,false,AircraftFuelBridge.class.getClassLoader()));}
        catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){access=null;}
        return access;
    }
    static long remainingTicks(int current,int count,int unit){
        if(current<0||count<0||count>64||unit<0||count>0&&unit==0)throw new IllegalArgumentException("fuel_invalid_inventory");
        return Math.addExact((long)current,Math.multiplyExact((long)count,unit));
    }
    static Observation finish(UUID body,List<Engine> engines,List<Propeller> props,String reason){
        if(body==null)return new Observation(false,"fuel_body_unavailable",null,engines,props,false);
        if(engines.stream().anyMatch(e->e.availableBurnTicks()<0)||props.stream().anyMatch(p->!Double.isFinite(p.actualRpm())))
            return new Observation(false,"fuel_native_state_unavailable",body,engines,props,false);
        boolean known=Set.of("","fuel_empty","fuel_waiting_power","fuel_overstressed","fuel_propeller_stopped",
                "fuel_propeller_unassembled","fuel_engine_missing","fuel_propeller_missing","fuel_world_frozen").contains(reason);
        if(!known)return new Observation(false,reason,body,engines,props,false);
        if(engines.isEmpty())reason="fuel_engine_missing";
        else if(props.isEmpty())reason="fuel_propeller_missing";
        else if(reason.isEmpty()){
            if(engines.stream().anyMatch(e->e.availableBurnTicks()==0))reason="fuel_empty";
            else if(engines.stream().anyMatch(e->!e.powered()))reason="fuel_waiting_power";
            else if(props.stream().anyMatch(p->!p.assembled()))reason="fuel_propeller_unassembled";
            else if(props.stream().anyMatch(p->!p.moving()||Math.abs(p.actualRpm())<1e-5))reason="fuel_propeller_stopped";
        }
        return new Observation(true,reason,body,engines,props,reason.isEmpty());
    }
    static String networkProblem(boolean initialized,double capacity,double stress,double unloadedCapacity,double unloadedStress,int unloadedMembers,boolean overstressed){
        if(!Double.isFinite(capacity)||!Double.isFinite(stress)||!Double.isFinite(unloadedCapacity)||!Double.isFinite(unloadedStress)
                ||capacity<0||stress<0||unloadedCapacity<0||unloadedStress<0||unloadedMembers<0)return "fuel_native_state_unavailable";
        if(!initialized)return "fuel_network_unavailable";
        if(unloadedCapacity!=0||unloadedStress!=0||unloadedMembers!=0)return "fuel_network_unloaded";
        if(overstressed||capacity<stress)return "fuel_overstressed";
        return "";
    }
    static boolean actualMotion(double rpm,double rate,double angle,double previous,double partAngle,boolean stalled){
        return !stalled&&Double.isFinite(rpm)&&Double.isFinite(rate)&&Double.isFinite(angle)&&Double.isFinite(previous)
                &&Double.isFinite(partAngle)&&Math.abs(rpm)>1e-5&&Math.abs(rate)>1e-5
                &&Math.abs(wrap(angle-previous))>1e-5&&Math.abs(wrap(angle-partAngle))<.001;
    }
    private static double wrap(double v){return v-360*Math.floor((v+180)/360);}
}
