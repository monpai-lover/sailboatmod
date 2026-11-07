package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.transport.air.AircraftClass;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Reads existing native body/chunk/network state. No assembly, source creation, fuel insertion or force APIs. */
final class AircraftFuelReflectionAccess {
    @FunctionalInterface interface Lookup { Class<?> find(String name)throws ClassNotFoundException; }
    static final String ENGINE="dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity";
    static final String PROP="dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity";
    static final String PART="dev.eriksonn.aeronautics.content.blocks.propeller.bearing.contraption.PropellerBearingContraptionEntity";
    static final String BODY="dev.ryanhcode.sable.sublevel.ServerSubLevel";
    static final String KINETIC="com.simibubi.create.content.kinetics.base.KineticBlockEntity";
    static final String TRANSMISSION="dev.simulated_team.simulated.content.blocks.analog_transmission.AnalogTransmissionBlockEntity";
    static final String EXTRA_WHEEL=TRANSMISSION+"$AnalogTransmissionCogwheel";
    static final Map<String,String> PINS=Map.ofEntries(
            Map.entry("com.simibubi.create.content.kinetics.base.KineticBlockEntity","3bce80f18d09ccff5e6c1b5cdbeeb2793f0a507006879b267148cde0df443572"),
            Map.entry("com.simibubi.create.content.kinetics.KineticNetwork","bab6c531d5b916cbc231fb53083283cc049a0d58884cd669a8000adc474127f9"),
            Map.entry("com.simibubi.create.content.kinetics.TorquePropagator","2c3e376f0ebe57589f9bd4b238156b3016f272a91a2710a3d6fe152b25eceee9"),
            Map.entry("com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity","6c6e5bdabd3ecbd7003213e8b597469553c916c7dc0900abef4f21975c69bd00"),
            Map.entry("com.simibubi.create.content.contraptions.ControlledContraptionEntity","71af0939585326ba57ce7064b6b399750416f0814ada128d28da24e107de77ee"),
            Map.entry("com.simibubi.create.content.contraptions.bearing.BearingContraption","2bede509573b8aa8a43ebf721003de1071a8975b47559b15f6d9702ed81e26a0"),
            Map.entry("dev.ryanhcode.sable.ActiveSableCompanion","17b2b226a5b1c953a6070670a7d34bc150485a6f68b2ed459eb15c9b4add309a"),
            Map.entry("dev.ryanhcode.sable.sublevel.ServerSubLevel","033eff21106f748c94b4dc51426a450398392a2e896a9ba7095a7ce4270b9119"),
            Map.entry("dev.ryanhcode.sable.sublevel.plot.LevelPlot","a9c45a8d468ab91af1bd15f8696dbc66a89c1af7b0ceedec2b8f34484aafe310"),
            Map.entry("dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder","1172fb62c398068b2015b67af316dc78d064e6ae045e1992b7af5cf2cb40435e"),
            Map.entry("dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity","36f5a323621157f900d7732ab7461d95f4a85ad2a25bc82adafa8d05239d26b1"),
            Map.entry("dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineInventory","ae363f6aaf743b773918172584adc127d0055911fbac29f3cbb1de9b231a3780"),
            Map.entry("dev.simulated_team.simulated.multiloader.inventory.SingleSlotContainer","7127ec5cfcb8c52f32ea7254958ff38588e2c72002478e526b5cc459058e0637"),
            Map.entry("dev.simulated_team.simulated.multiloader.inventory.ContainerSlot","5603550a2a938a7415ae455272e5b0d157e5e2edc1211ec87585b8dc14c6c6fe"),
            Map.entry("dev.simulated_team.simulated.service.SimItemService","acf9658495cdab4d67c728dc897c1984ff70baa5c3593434149c38e12a7f22c4"),
            Map.entry("dev.simulated_team.simulated.neoforge.service.NeoForgeSimItemService","695c052facc10759ab802519cb634f3b990f59c8e52fae45c30caf1ea318be2f"),
            Map.entry(TRANSMISSION,"41270d1855772db750493d4e1fca907f6b4af23b32c110510c1b74708fdddc02"),
            Map.entry(EXTRA_WHEEL,"5aefc6ada10e130de4e10aa71e90ed40fc7cca63a084de546b29b885bd68d951"),
            Map.entry("dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity","136d8891da5d0ef01f279f5e6737719200fc5f44163ea13b83df2121a61837ab"),
            Map.entry("dev.eriksonn.aeronautics.content.blocks.propeller.bearing.contraption.PropellerBearingContraptionEntity","fc9c7cec035d377907c62a428a261de65eb7858aa6e40f3ab59362240b9e9d30"));
    static final String GYRO="dev.eriksonn.aeronautics.content.blocks.propeller.bearing.gyroscopic_propeller_bearing.GyroscopicPropellerBearingBlockEntity";
    static final String SMALL="dev.eriksonn.aeronautics.content.blocks.propeller.small.";
    static final String SMALL_BASE=SMALL+"BasePropellerBlockEntity";
    private static final String ANDESITE=SMALL+"andesite.AndesitePropellerBlockEntity";
    private static final String WOODEN=SMALL+"wooden.WoodenPropellerBlockEntity";
    private static final String SMART=SMALL+"smart_propeller.SmartPropellerBlockEntity";
    static final Map<String,String> VERTICAL_PINS=Map.of(
            GYRO,"e061eaf4b908318a179cad44c3c0ed2e7768e9116c47b32c20f9c14377bb938b",
            SMALL_BASE,"462f2efc71a31c42c19242e702b4ab2102c92f459ae7d2a4446861df6e6f4220",
            ANDESITE,"cc30d5246e71790e49773cb0da38dd6a10fc5389483ae9ef9952a3e29fe06793",
            WOODEN,"4bbba09dd281b0da2858f901022598ba00dd050d801d33c4a54a6d8a9cd3e973",
            SMART,"391305a1b4c387bd05423f4858429a230c7df0885bc2de107283ba868fc2f3fd");
    private final VerticalPropellers verticalPropellers;
    private final Class<?> bodyType,engineType,propType,partType,kineticType,plotType,holderType,transmissionType,extraWheelType;
    private final Object helper,fuelService;
    private final Method containingBE,containingEntity,bodyId,bodyLevel,bodyRemoved,bodyPlot,loadedChunks,holderChunk,plotBounds;
    private final Method propRunning,propPart,propRate,partAngle,partStalled,partContraption,engineGenerated,kineticSpeed,unitBurn;
    private final Method extraParent,parentExtra;
    private final Field networks,burnTime,engineInventory,inventorySlot,slotStack,kineticNetwork,kineticOverstressed,bearingAngle,bearingPrevious;
    private final Field networkId,networkInitialized,networkSources,networkMembers,networkCapacity,networkStress,unloadedCapacity,unloadedStress,unloadedMembers;
    private final int infiniteThreshold;

    static AircraftFuelReflectionAccess bind(Lookup lookup)throws ReflectiveOperationException{return new AircraftFuelReflectionAccess(lookup);}
    private AircraftFuelReflectionAccess(Lookup lookup)throws ReflectiveOperationException{
        for(var pin:PINS.entrySet())hash(lookup.find(pin.getKey()),pin.getValue());
        bodyType=lookup.find(BODY);engineType=lookup.find(ENGINE);propType=lookup.find(PROP);partType=lookup.find(PART);kineticType=lookup.find(KINETIC);
        transmissionType=lookup.find(TRANSMISSION);extraWheelType=lookup.find(EXTRA_WHEEL);
        extraParent=extraWheelType.getMethod("getParentBlockEntity");parentExtra=transmissionType.getMethod("getExtraKinetics");
        plotType=lookup.find("dev.ryanhcode.sable.sublevel.plot.LevelPlot");holderType=lookup.find("dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder");
        Class<?> companion=lookup.find("dev.ryanhcode.sable.ActiveSableCompanion"),network=lookup.find("com.simibubi.create.content.kinetics.KineticNetwork");
        helper=lookup.find("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);
        containingBE=companion.getMethod("getContaining",BlockEntity.class);containingEntity=companion.getMethod("getContaining",Entity.class);
        bodyId=bodyType.getMethod("getUniqueId");bodyLevel=bodyType.getMethod("getLevel");bodyRemoved=bodyType.getMethod("isRemoved");bodyPlot=bodyType.getMethod("getPlot");
        loadedChunks=plotType.getMethod("getLoadedChunks");holderChunk=holderType.getMethod("getChunk");plotBounds=plotType.getMethod("getBoundingBox");
        propRunning=propType.getMethod("isRunning");propPart=propType.getMethod("getMovedContraption");propRate=propType.getMethod("getRotationSpeed");
        partAngle=partType.getMethod("getAngle",float.class);partStalled=partType.getMethod("isStalled");partContraption=partType.getMethod("getContraption");
        engineGenerated=engineType.getMethod("getGeneratedSpeed");kineticSpeed=kineticType.getMethod("getSpeed");
        Class<?> inventory=lookup.find("dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineInventory");
        Class<?> slot=lookup.find("dev.simulated_team.simulated.multiloader.inventory.ContainerSlot");
        burnTime=field(engineType,"burnTime");engineInventory=field(engineType,"inventory");inventorySlot=field(inventory,"slot");slotStack=field(slot,"stack");
        infiniteThreshold=engineType.getField("INFINITE_THRESHOLD").getInt(null);
        if(infiniteThreshold!=51_840_000)throw fail("fuel_native_abi_unavailable");
        kineticNetwork=field(kineticType,"network");kineticOverstressed=field(kineticType,"overStressed");
        bearingAngle=field(propType,"angle");bearingPrevious=field(propType,"prevAngle");
        networks=field(lookup.find("com.simibubi.create.content.kinetics.TorquePropagator"),"networks");
        networkId=field(network,"id");networkInitialized=field(network,"initialized");networkSources=field(network,"sources");networkMembers=field(network,"members");
        networkCapacity=field(network,"currentCapacity");networkStress=field(network,"currentStress");unloadedCapacity=field(network,"unloadedCapacity");unloadedStress=field(network,"unloadedStress");unloadedMembers=field(network,"unloadedMembers");
        Class<?> service=lookup.find("dev.simulated_team.simulated.service.SimItemService");fuelService=service.getField("INSTANCE").get(null);
        if(fuelService==null||!fuelService.getClass().getName().equals("dev.simulated_team.simulated.neoforge.service.NeoForgeSimItemService"))throw fail("fuel_native_abi_unavailable");
        unitBurn=service.getMethod("getBurnTime",ItemStack.class);
        ItemStack.class.getMethod("getBurnTime",lookup.find("net.minecraft.world.item.crafting.RecipeType"));
        verticalPropellers=VerticalPropellers.tryBind(lookup,propType,kineticType);
    }
    AircraftFuelBridge.Observation inspect(BlockEntity controller,AircraftClass airframe)throws ReflectiveOperationException{
        boolean vertical=airframe!=AircraftClass.FIXED_WING;
        if(vertical&&verticalPropellers==null)throw fail("fuel_native_abi_unavailable");
        ServerLevel world=(ServerLevel)controller.getLevel();Object body=body(controller);if(body==null)throw fail("fuel_body_unavailable");UUID id=(UUID)call(bodyId,body);
        List<BlockEntity> entities=bodyEntities(body,world);if(entities.stream().noneMatch(be->be==controller))throw fail("fuel_body_not_loaded");Map<BlockEntity,AircraftFuelBridge.Engine> allEngines=new IdentityHashMap<>();List<BlockEntity> props=new ArrayList<>();
        for(BlockEntity entity:entities){
            if(engineType.isInstance(entity)){if(entity.getClass()!=engineType)throw fail("fuel_unsupported_engine");allEngines.put(entity,engine(entity));}
            Class<?> type=entity.getClass();
            if(type==propType||vertical&&verticalPropellers.supports(type))props.add(entity);
            else if(propType.isInstance(entity)||type.getName().startsWith("dev.eriksonn.aeronautics.content.blocks.propeller."))
                throw fail("fuel_unsupported_propeller");
        }
        if(allEngines.size()>32||props.size()>32)throw fail("fuel_observation_limit");
        props.sort(Comparator.comparingLong(e->e.getBlockPos().asLong()));
        List<AircraftFuelBridge.Propeller> observedProps=new ArrayList<>();Set<BlockEntity> required=Collections.newSetFromMap(new IdentityHashMap<>());
        String blocker="";boolean unresolved=false;
        Object worldNetworks=map(networks.get(null),4096,"fuel_network_unavailable").get(world);
        Map<?,?> existing=worldNetworks==null?Map.of():map(worldNetworks,4096,"fuel_network_unavailable");
        for(BlockEntity prop:props){
            observedProps.add(propType.isInstance(prop)?propeller(prop,body):smallPropeller(prop));
            Object key=kineticNetwork.get(prop);Object network=key==null?null:existing.get(key);
            if(network==null){unresolved=true;continue;}
            if(!Objects.equals(key,networkId.get(network)))throw fail("fuel_network_unavailable");
            String problem=AircraftFuelBridge.networkProblem(networkInitialized.getBoolean(network),networkCapacity.getFloat(network),networkStress.getFloat(network),
                    unloadedCapacity.getFloat(network),unloadedStress.getFloat(network),unloadedMembers.getInt(network),kineticOverstressed.getBoolean(prop));
            if(!problem.isEmpty()&&!problem.equals("fuel_overstressed"))throw fail(problem);
            if(blocker.isEmpty())blocker=problem;
            Map<?,?> members=map(networkMembers.get(network),512,"fuel_observation_limit"),sources=map(networkSources.get(network),64,"fuel_observation_limit");
            if(!members.containsKey(prop))throw fail("fuel_network_unavailable");
            for(var member:members.entrySet()){
                BlockEntity be=kinetic(member.getKey(),body,world,entities,false);if(number(member.getValue())<0)throw fail("fuel_native_state_unavailable");
                if(!Objects.equals(kineticNetwork.get(be),key))throw fail("fuel_network_unavailable");
            }
            if(sources.isEmpty()){unresolved=true;continue;}
            for(var source:sources.entrySet()){
                BlockEntity be=kinetic(source.getKey(),body,world,entities,true);double perRpm=number(source.getValue());
                if(be.getClass()!=engineType)throw fail("fuel_unsupported_source");
                if(!Objects.equals(kineticNetwork.get(be),key)||!allEngines.containsKey(be))throw fail("fuel_foreign_source");
                if(perRpm<=0)throw fail("fuel_network_unavailable");required.add(be);
            }
        }
        List<AircraftFuelBridge.Engine> selected;
        if(unresolved){
            selected=sorted(allEngines.values());
            if(selected.isEmpty())blocker="fuel_engine_missing";
            else if(selected.stream().allMatch(e->e.availableBurnTicks()==0))blocker="fuel_empty";
            else if(selected.stream().noneMatch(AircraftFuelBridge.Engine::powered))blocker="fuel_waiting_power";
            else throw fail("fuel_network_unavailable");
        }else selected=sorted(required.stream().map(allEngines::get).toList());
        if(world.tickRateManager().isFrozen())blocker="fuel_world_frozen";
        var controllerChunk=world.getChunkSource().getChunkNow(controller.getBlockPos().getX()>>4,controller.getBlockPos().getZ()>>4);
        if(controller.isRemoved()||controller.getLevel()!=world||controllerChunk==null
                ||controllerChunk.getBlockEntities().get(controller.getBlockPos())!=controller||body(controller)!=body)throw fail("fuel_body_changed");
        return AircraftFuelBridge.finish(id,selected,observedProps,blocker);
    }
    private AircraftFuelBridge.Engine engine(BlockEntity engine)throws ReflectiveOperationException{
        int current=burnTime.getInt(engine);Object inventory=engineInventory.get(engine),slot=inventorySlot.get(inventory);Object raw=slotStack.get(slot);
        if(!(raw instanceof ItemStack live))throw fail("fuel_native_state_unavailable");
        ItemStack copy=live.copy();int count=copy.getCount();int unit=count==0?0:((Number)call(unitBurn,fuelService,copy)).intValue();
        if(engineInventory.get(engine)!=inventory||inventorySlot.get(inventory)!=slot||slotStack.get(slot)!=live
                ||!ItemStack.matches(live,copy)||burnTime.getInt(engine)!=current)throw fail("fuel_inventory_changed");
        if(current>=infiniteThreshold||unit>=infiniteThreshold)throw fail("fuel_unsupported_fuel");
        long remaining=AircraftFuelBridge.remainingTicks(current,count,unit);double generated=number(call(engineGenerated,engine)),speed=number(call(kineticSpeed,engine));
        boolean powered=current>0&&remaining>0&&Math.abs(generated)>1e-5&&Math.abs(speed)>1e-5&&!kineticOverstressed.getBoolean(engine);
        return new AircraftFuelBridge.Engine(engine.getBlockPos(),remaining,powered,remaining==0?"fuel_empty":powered?"":"fuel_waiting_power");
    }
    private AircraftFuelBridge.Propeller propeller(BlockEntity prop,Object body)throws ReflectiveOperationException{
        double rate=number(call(propRate,prop)),rpm=rate/.3;Object part=call(propPart,prop);boolean assembled=Boolean.TRUE.equals(call(propRunning,prop))&&part!=null;
        if(assembled){
            if(part.getClass()!=partType||!(part instanceof Entity entity)||entity.isRemoved()||!entity.isAlive()||entity.level()!=prop.getLevel()
                    ||call(containingEntity,helper,part)!=body)throw fail("fuel_foreign_propeller_part");
            Object contraption=call(partContraption,part);if(contraption==null||!contraption.getClass().getName().equals("com.simibubi.create.content.contraptions.bearing.BearingContraption"))throw fail("fuel_unsupported_propeller");
        }
        boolean moving=assembled&&AircraftFuelBridge.actualMotion(rpm,rate,bearingAngle.getFloat(prop),bearingPrevious.getFloat(prop),
                number(call(partAngle,part,1f)),Boolean.TRUE.equals(call(partStalled,part)))&&Math.abs(number(call(kineticSpeed,prop)))>1e-5&&!kineticOverstressed.getBoolean(prop);
        return new AircraftFuelBridge.Propeller(prop.getBlockPos(),rpm,assembled,moving,!assembled?"fuel_propeller_unassembled":moving?"":"fuel_propeller_stopped");
    }
    private AircraftFuelBridge.Propeller smallPropeller(BlockEntity prop)throws ReflectiveOperationException {
        double rate=number(verticalPropellers.rate().get(prop));
        double angle=number(call(verticalPropellers.angle(),prop));
        double previous=number(call(verticalPropellers.previous(),prop));
        double rpm=rate/.3;
        boolean active=Boolean.TRUE.equals(call(verticalPropellers.active(),prop));
        boolean moving=active&&Math.abs(number(call(kineticSpeed,prop)))>1e-5&&!kineticOverstressed.getBoolean(prop)
                &&AircraftFuelBridge.actualMotion(rpm,rate,angle,previous,angle,false);
        return new AircraftFuelBridge.Propeller(prop.getBlockPos(),rpm,true,moving,moving?"":"fuel_propeller_stopped");
    }

    /** The fixed-wing contract remains usable if these additional native classes are unavailable. */
    private record VerticalPropellers(Set<Class<?>> types,Field rate,Method angle,Method previous,Method active) {
        boolean supports(Class<?> type){return types.contains(type);}
        static VerticalPropellers tryBind(Lookup lookup,Class<?> bearing,Class<?> kinetic) {
            try {
                for(var pin:VERTICAL_PINS.entrySet())hash(lookup.find(pin.getKey()),pin.getValue());
                Class<?> gyro=lookup.find(GYRO),base=lookup.find(SMALL_BASE);
                var small=Set.of(lookup.find(ANDESITE),lookup.find(WOODEN),lookup.find(SMART));
                if(gyro.getSuperclass()!=bearing||!kinetic.isAssignableFrom(base)
                        ||small.stream().anyMatch(type->type.getSuperclass()!=base))return null;
                Set<Class<?>> types=new HashSet<>(small);types.add(gyro);
                return new VerticalPropellers(Set.copyOf(types),field(base,"rotationSpeed"),base.getMethod("getAngle"),
                        base.getMethod("getPreviousAngle"),base.getMethod("isActive"));
            } catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable) {return null;}
        }
    }

    private BlockEntity kinetic(Object value,Object body,ServerLevel world,List<BlockEntity> entities,boolean source)throws ReflectiveOperationException{
        if(!(value instanceof BlockEntity be)||!kineticType.isInstance(be)||be.isRemoved()||be.getLevel()!=world)throw fail("fuel_foreign_source");
        if(entities.stream().anyMatch(existing->existing==be)){
            if(body(be)!=body)throw fail("fuel_foreign_source");
            return be;
        }
        // Simulated's real network also contains its parent's synthetic cogwheel. It is a
        // member only, and its two native references must agree with the current loaded parent.
        if(source||be.getClass()!=extraWheelType)throw fail("fuel_foreign_source");
        var position=be.getBlockPos();var chunk=world.getChunkSource().getChunkNow(position.getX()>>4,position.getZ()>>4);
        BlockEntity actualParent=chunk==null?null:chunk.getBlockEntities().get(position);
        if(actualParent==null||actualParent.getClass()!=transmissionType||actualParent.isRemoved()||actualParent.getLevel()!=world
                ||entities.stream().noneMatch(existing->existing==actualParent))throw fail("fuel_foreign_source");
        if(!safeExtraMember(be,call(extraParent,be),actualParent,call(parentExtra,actualParent),body(actualParent)==body,source))throw fail("fuel_foreign_source");
        return be;
    }
    static boolean safeExtraMember(Object member,Object declaredParent,Object actualParent,Object declaredChild,boolean sameBody,boolean source){
        return !source&&member!=null&&actualParent!=null&&declaredParent==actualParent&&declaredChild==member&&sameBody;
    }
    private Object body(BlockEntity be)throws ReflectiveOperationException{
        Object body=call(containingBE,helper,be);return body!=null&&body.getClass()==bodyType&&!Boolean.TRUE.equals(call(bodyRemoved,body))&&call(bodyLevel,body)==be.getLevel()?body:null;
    }
    private List<BlockEntity> bodyEntities(Object body,ServerLevel world)throws ReflectiveOperationException{
        Object plot=call(bodyPlot,body);Object raw=call(loadedChunks,plot);if(!(raw instanceof Collection<?> holders)||holders.isEmpty()||holders.size()>256)throw fail("fuel_body_not_loaded");
        List<BlockEntity> out=new ArrayList<>();Set<Long> chunkPositions=new HashSet<>();
        for(Object holder:holders){
            if(holder==null||holder.getClass()!=holderType)throw fail("fuel_native_abi_unavailable");Object value=call(holderChunk,holder);
            if(!(value instanceof LevelChunk chunk))throw fail("fuel_body_not_loaded");chunkPositions.add(chunk.getPos().toLong());
            if(chunk.getBlockEntities().size()>512||out.size()+chunk.getBlockEntities().size()>512)throw fail("fuel_observation_limit");
            for(BlockEntity be:chunk.getBlockEntities().values()){if(be.isRemoved()||be.getLevel()!=world||body(be)!=body)throw fail("fuel_body_not_loaded");out.add(be);}
        }
        Object bounds=call(plotBounds,plot);Class<?> type=plotBounds.getReturnType();
        int minX=integer(call(type.getMethod("minX"),bounds)),maxX=integer(call(type.getMethod("maxX"),bounds));
        int minZ=integer(call(type.getMethod("minZ"),bounds)),maxZ=integer(call(type.getMethod("maxZ"),bounds));
        String coverage=loadedBoundsProblem(minX,maxX,minZ,maxZ,chunkPositions);if(!coverage.isEmpty())throw fail(coverage);
        return out;
    }
    /** Native integer bounds include their maxima; even a single occupied column requires its chunk. */
    static String loadedBoundsProblem(int minX,int maxX,int minZ,int maxZ,Set<Long> chunkPositions){
        if(maxX<minX||maxZ<minZ||(long)(maxX>>4)-(minX>>4)>32||(long)(maxZ>>4)-(minZ>>4)>32)return "fuel_observation_limit";
        for(int x=minX>>4;x<=(maxX>>4);x++)for(int z=minZ>>4;z<=(maxZ>>4);z++)
            if(!chunkPositions.contains(net.minecraft.world.level.ChunkPos.asLong(x,z)))return "fuel_body_not_loaded";
        return "";
    }
    private static List<AircraftFuelBridge.Engine> sorted(Collection<AircraftFuelBridge.Engine> values){return values.stream().sorted(Comparator.comparingLong(e->e.absolutePosition().asLong())).toList();}
    private static Map<?,?> map(Object value,int cap,String reason)throws ReflectiveOperationException{if(!(value instanceof Map<?,?> map)||map.size()>cap)throw fail(reason);return map;}
    private static int integer(Object value)throws ReflectiveOperationException{if(!(value instanceof Integer number))throw fail("fuel_native_state_unavailable");return number;}
    private static double number(Object value)throws ReflectiveOperationException{if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue()))throw fail("fuel_native_state_unavailable");return n.doubleValue();}
    private static Field field(Class<?> type,String name)throws ReflectiveOperationException{
        for(Class<?> t=type;t!=null;t=t.getSuperclass())try{Field f=t.getDeclaredField(name);if(!f.trySetAccessible())throw fail("fuel_native_abi_unavailable");return f;}catch(NoSuchFieldException absent){}
        throw new NoSuchFieldException(type.getName()+"."+name);
    }
    private static Object call(Method method,Object target,Object...args)throws ReflectiveOperationException{
        try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw new ReflectiveOperationException("fuel_native_state_unavailable",failure.getCause());}
    }
    private static ReflectiveOperationException fail(String reason){return new ReflectiveOperationException(reason);}
    static boolean supports(Map<String,byte[]> resources){
        try{for(var pin:PINS.entrySet()){byte[] bytes=resources.get(pin.getKey());if(bytes==null||bytes.length==0||bytes.length>1024*1024||!pin.getValue().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))return false;}return !PINS.isEmpty();}
        catch(java.security.NoSuchAlgorithmException impossible){return false;}
    }
    private static void hash(Class<?> type,String pin)throws ReflectiveOperationException{
        try(var stream=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
            byte[] bytes=stream==null?null:stream.readNBytes(1024*1024+1);if(bytes==null||bytes.length>1024*1024||!pin.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw fail("fuel_native_abi_unavailable");
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException failed){throw new ReflectiveOperationException("fuel_native_abi_unavailable",failed);}
    }
}
