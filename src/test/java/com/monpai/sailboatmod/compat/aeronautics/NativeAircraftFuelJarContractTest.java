package com.monpai.sailboatmod.compat.aeronautics;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Reads exact original native ZIP bytes; no Create/Sable/Aero/Simulated classes enter the test JVM. */
class NativeAircraftFuelJarContractTest {
    private static final Set<String> BOUNDS_AUDIT=Set.of("dev.ryanhcode.sable.sublevel.SubLevel","dev.ryanhcode.sable.companion.math.BoundingBox3ic");
    @Test void nativePlotIntegerMaximumReallyIncludesTheLastCell()throws Exception {
        var bytes=originals();var update=method(node(bytes,"dev.ryanhcode.sable.sublevel.SubLevel"),"updateBoundingBox","()V");
        int inclusiveMaxima=0;
        for(var instruction:update.instructions)if(instruction instanceof MethodInsnNode invoke
                &&invoke.owner.equals("dev/ryanhcode/sable/companion/math/BoundingBox3ic")
                &&Set.of("maxX","maxY","maxZ").contains(invoke.name)){
            assertEquals(Opcodes.I2D,invoke.getNext().getOpcode());
            assertEquals(Opcodes.DCONST_1,invoke.getNext().getNext().getOpcode());
            assertEquals(Opcodes.DADD,invoke.getNext().getNext().getNext().getOpcode());inclusiveMaxima++;
        }
        assertEquals(3,inclusiveMaxima);
        var volume=method(node(bytes,"dev.ryanhcode.sable.companion.math.BoundingBox3ic"),"volume","()I");
        int plusOne=0;for(var instruction:volume.instructions)if(instruction.getOpcode()==Opcodes.IADD
                &&instruction.getPrevious().getOpcode()==Opcodes.ICONST_1)plusOne++;
        assertEquals(3,plusOne);
    }
    @Test void onlyPinnedTransmissionExtraWheelUsesMutualPureIdentityGetters()throws Exception {
        String parent="dev.simulated_team.simulated.content.blocks.analog_transmission.AnalogTransmissionBlockEntity";
        String child=parent+"$AnalogTransmissionCogwheel";
        assertTrue(AircraftFuelReflectionAccess.PINS.containsKey(parent));
        assertTrue(AircraftFuelReflectionAccess.PINS.containsKey(child));
        var bytes=originals();assertTrue(AircraftFuelReflectionAccess.supports(bytes));
        assertPureFieldGetter(method(node(bytes,parent),"getExtraKinetics","()Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;"));
        assertPureFieldGetter(method(node(bytes,child),"getParentBlockEntity","()Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;"));
        field(node(bytes,child),"parentBlockEntity","Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;");
        var changed=new HashMap<>(bytes);changed.remove(child);assertFalse(AircraftFuelReflectionAccess.supports(changed));
    }
    @Test void installedOriginalsMatchEveryFuelAndPowerReadbackFieldAndMethod()throws Exception{
        Map<String,byte[]> bytes=originals();assertTrue(AircraftFuelReflectionAccess.supports(bytes));
        var changed=new HashMap<>(bytes);byte[] altered=changed.get(AircraftFuelReflectionAccess.ENGINE).clone();altered[altered.length-1]^=1;
        changed.put(AircraftFuelReflectionAccess.ENGINE,altered);assertFalse(AircraftFuelReflectionAccess.supports(changed));
        changed=new HashMap<>(bytes);changed.remove(AircraftFuelReflectionAccess.PROP);assertFalse(AircraftFuelReflectionAccess.supports(changed));
        var engine=node(bytes,AircraftFuelReflectionAccess.ENGINE);field(engine,"burnTime","I");field(engine,"inventory","Ldev/simulated_team/simulated/content/blocks/portable_engine/PortableEngineInventory;");
        method(engine,"getCurrentBurnTime","()I");method(engine,"getGeneratedSpeed","()F");
        var kinetic=node(bytes,AircraftFuelReflectionAccess.KINETIC);field(kinetic,"network","Ljava/lang/Long;");field(kinetic,"overStressed","Z");method(kinetic,"getSpeed","()F");
        var network=node(bytes,"com.simibubi.create.content.kinetics.KineticNetwork");
        for(String name:List.of("currentCapacity","currentStress","unloadedCapacity","unloadedStress"))field(network,name,"F");
        field(network,"unloadedMembers","I");field(network,"initialized","Z");field(network,"id","Ljava/lang/Long;");field(network,"members","Ljava/util/Map;");field(network,"sources","Ljava/util/Map;");
        field(node(bytes,"com.simibubi.create.content.kinetics.TorquePropagator"),"networks","Ljava/util/Map;");
        field(node(bytes,"dev.simulated_team.simulated.multiloader.inventory.ContainerSlot"),"stack","Lnet/minecraft/world/item/ItemStack;");
        field(node(bytes,"dev.simulated_team.simulated.multiloader.inventory.SingleSlotContainer"),"slot","Ldev/simulated_team/simulated/multiloader/inventory/ContainerSlot;");
        field(node(bytes,"com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity"),"angle","F");
        var prop=node(bytes,AircraftFuelReflectionAccess.PROP);field(prop,"prevAngle","F");method(prop,"getRotationSpeed","()F");method(prop,"getMovedContraption","()Ldev/eriksonn/aeronautics/content/blocks/propeller/bearing/contraption/PropellerBearingContraptionEntity;");
        method(node(bytes,AircraftFuelReflectionAccess.PART),"getAngle","(F)F");
        method(node(bytes,AircraftFuelReflectionAccess.BODY),"getPlot","()Ldev/ryanhcode/sable/sublevel/plot/ServerLevelPlot;");
        assertPureFieldGetter(method(node(bytes,"dev.ryanhcode.sable.sublevel.plot.LevelPlot"),"getLoadedChunks","()Ljava/util/Collection;"));
        assertPureFieldGetter(method(node(bytes,"dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder"),"getChunk","()Lnet/minecraft/world/level/chunk/LevelChunk;"));
        assertPureFieldGetter(method(engine,"getCurrentBurnTime","()I"));
    }
    @Test void realUnitFuelLookupAndConsumptionLawAreTheAuditedNativeOnes()throws Exception{
        var bytes=originals();var engine=node(bytes,AircraftFuelReflectionAccess.ENGINE);var tick=method(engine,"tick","()V");
        boolean infiniteGuard=false;int counterDecrements=0;
        for(var instruction:tick.instructions){
            if(instruction instanceof MethodInsnNode invoke&&invoke.name.equals("isCurrentFuelInfinite"))infiniteGuard=true;
            if(instruction instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.name.equals("burnTime")&&f.getPrevious().getOpcode()==Opcodes.ISUB){
                assertEquals(Opcodes.ICONST_1,f.getPrevious().getPrevious().getOpcode());counterDecrements++;
            }
        }
        assertTrue(infiniteGuard);assertEquals(1,counterDecrements);
        var service=node(bytes,"dev.simulated_team.simulated.neoforge.service.NeoForgeSimItemService");
        var unit=method(service,"getBurnTime","(Lnet/minecraft/world/item/ItemStack;)I");boolean smelting=false,lookup=false;
        for(var instruction:unit.instructions){
            if(instruction instanceof FieldInsnNode f){assertEquals(Opcodes.GETSTATIC,f.getOpcode());smelting|=f.name.equals("SMELTING");}
            if(instruction instanceof MethodInsnNode invoke)lookup|=invoke.owner.equals("net/minecraft/world/item/ItemStack")&&invoke.name.equals("getBurnTime");
        }
        assertTrue(smelting&&lookup);
    }
    @Test void adapterCannotRequestAssemblyRefuelingNetworkCreationOrRawNativeWrites()throws Exception{
        Set<String> forbidden=Set.of("assemble","disassemble","setAngle","setSpeed","setCurrentBurnTime","setGeneratedSpeed","getOrCreateNetwork",
                "getOrCreateNetworkFor","calculateCapacity","calculateStress","calculateStressApplied","calculateAddedStressCapacity","insertSlot","setItem","setStack","shrink","applyForces");
        try(var stream=AircraftFuelReflectionAccess.class.getResourceAsStream("AircraftFuelReflectionAccess.class")){
            assertNotNull(stream);var node=new ClassNode();new ClassReader(stream).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            for(var method:node.methods)for(var instruction:method.instructions){
                if(instruction instanceof LdcInsnNode ldc&&ldc.cst instanceof String text)assertFalse(forbidden.contains(text),text);
                if(instruction instanceof MethodInsnNode invoke){assertFalse(forbidden.contains(invoke.name),invoke.name);assertFalse(invoke.owner.equals("java/lang/reflect/Field")&&invoke.name.startsWith("set"),invoke.name);}
            }
        }
    }
    private static void assertPureFieldGetter(MethodNode method){
        for(var instruction:method.instructions){
            assertFalse(instruction instanceof MethodInsnNode,method.name);
            if(instruction instanceof FieldInsnNode f)assertTrue(f.getOpcode()==Opcodes.GETFIELD||f.getOpcode()==Opcodes.GETSTATIC,method.name);
        }
    }
    private static ClassNode node(Map<String,byte[]> bytes,String name){var n=new ClassNode();new ClassReader(bytes.get(name)).accept(n,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return n;}
    private static void field(ClassNode n,String name,String desc){assertEquals(1,n.fields.stream().filter(f->f.name.equals(name)&&f.desc.equals(desc)).count(),n.name+"."+name);}
    private static MethodNode method(ClassNode n,String name,String desc){return n.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow(()->new AssertionError(n.name+"."+name+desc));}
    private static Map<String,byte[]> originals()throws Exception{
        Map<String,byte[]> resources=new HashMap<>();
        for(String[] input:new String[][]{{"SAILBOAT_NATIVE_CREATE_JAR","create-1.21.1-6.0.10.jar"},{"SAILBOAT_NATIVE_SABLE_JAR","sable-neoforge-1.21.1-2.0.6.jar"},{"SAILBOAT_NATIVE_AERO_JAR","create-aeronautics-bundled-1.21.1-1.3.2.jar"}}){
            String configured=System.getenv(input[0]);Path path=configured==null?Path.of("libs/compile-only",input[1]):Path.of(configured);
            assumeTrue(Files.isRegularFile(path),"Set "+input[0]);try(var stream=Files.newInputStream(path)){readZip(stream,resources,0);}
        }return resources;
    }
    private static void readZip(InputStream input,Map<String,byte[]> out,int depth)throws Exception{
        try(var zip=new ZipInputStream(input)){ZipEntry entry;int count=0;while((entry=zip.getNextEntry())!=null){assertTrue(++count<=100000);String name=entry.getName();
            String type=name.endsWith(".class")?name.substring(0,name.length()-6).replace('/','.'):"";
            if(AircraftFuelReflectionAccess.PINS.containsKey(type)||BOUNDS_AUDIT.contains(type)){byte[] bytes=zip.readNBytes(1024*1024+1);assertTrue(bytes.length<=1024*1024);out.put(type,bytes);}
            else if(depth<2&&name.startsWith("META-INF/jarjar/")&&name.endsWith(".jar")){byte[] bytes=zip.readNBytes(80*1024*1024+1);assertTrue(bytes.length<=80*1024*1024);readZip(new ByteArrayInputStream(bytes),out,depth+1);}
        }}
    }
}
