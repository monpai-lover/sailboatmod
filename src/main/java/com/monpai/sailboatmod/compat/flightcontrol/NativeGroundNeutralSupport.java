package com.monpai.sailboatmod.compat.flightcontrol;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Only existing bounded native actors with audited output implementations. No solver, input, or chunk request. */
public final class NativeGroundNeutralSupport {
    static final int MAX_LINKS=256;
    private static final String FCC="ace.flight.block.FlightControlComputerBlockEntity";
    private static final String SVT="ace.flight.block.SmartVectorThrusterBlockEntity";
    private static final String PROP="dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity";
    private static final String FLAP="com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity";
    private static final Map<String,String> HASHES=Map.of(
            SVT,"9663b1cf1ddfe8af42592b96498224267f0b682ee9cf1674eb7c71e562b0fb34",
            PROP,"136d8891da5d0ef01f279f5e6737719200fc5f44163ea13b83df2121a61837ab",
            FLAP,"6c6e5bdabd3ecbd7003213e8b597469553c916c7dc0900abef4f21975c69bd00");
    private static final ClassValue<Boolean> AUDITED=new ClassValue<>() {
        protected Boolean computeValue(Class<?> type) {
            String expected=HASHES.get(type.getName());if(expected==null)return false;
            try(InputStream in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                return in!=null&&expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
            }catch(Exception|LinkageError unavailable){return false;}
        }
    };
    private static final ClassValue<Fields> FIELDS=new ClassValue<>() {
        protected Fields computeValue(Class<?> type){return new Fields(type);}
    };
    private NativeGroundNeutralSupport() { }
    public static boolean supports(Object computer) {
        try{return inspect(new RuntimeView(computer));}
        catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){return false;}
    }
    interface View {
        List<?> thrusters(); List<?> flaps(); boolean unsupportedChildren();
        Object existing(Object entry); boolean knownThruster(Object entity); boolean knownFlap(Object entity);
    }
    static boolean inspect(View view) {
        if(view==null)return false;
        try {
            List<?> thrust=view.thrusters(),flaps=view.flaps();
            if(thrust==null||flaps==null||thrust.size()>MAX_LINKS||flaps.size()>MAX_LINKS||view.unsupportedChildren())return false;
            for(Object entry:thrust){Object entity=view.existing(entry);if(entity==null||!view.knownThruster(entity))return false;}
            for(Object entry:flaps){Object entity=view.existing(entry);if(entity==null||!view.knownFlap(entity))return false;}
            return true;
        }catch(RuntimeException|LinkageError unavailable){return false;}
    }
    private static final class Fields {
        final Class<?> type;
        final java.util.concurrent.ConcurrentMap<String,Field> fields=new java.util.concurrent.ConcurrentHashMap<>();
        Fields(Class<?> type){this.type=type;}
        Object get(Object target,String name) {
            try {
                Field field=fields.computeIfAbsent(name,n->{
                    for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(n);if(!f.trySetAccessible())throw new IllegalStateException("field inaccessible");return f;}catch(NoSuchFieldException missing){ }
                    throw new IllegalStateException("unknown native field "+n);
                });return field.get(target);
            }catch(IllegalAccessException unavailable){throw new IllegalStateException(unavailable);}
        }
    }
    private static Object raw(Object target,String name){if(target==null)throw new IllegalStateException("missing native object");return FIELDS.get(target.getClass()).get(target,name);}
    private static List<?> list(Object value){if(!(value instanceof List<?> values)||values.size()>MAX_LINKS)throw new IllegalStateException("unbounded native links");return List.copyOf(values);}
    private static final class RuntimeView implements View {
        final BlockEntity fcc;final ServerLevel level;final Object body;final MethodHandle containing;
        final List<?> thrust,flaps;final boolean children;
        RuntimeView(Object computer)throws ReflectiveOperationException {
            if(!(computer instanceof BlockEntity be)||!FCC.equals(computer.getClass().getName())||be.isRemoved()||!(be.getLevel() instanceof ServerLevel server))throw new IllegalStateException("unsupported computer");
            fcc=be;level=server;
            thrust=list(raw(fcc,"linkedThrusters"));flaps=list(raw(fcc,"linkedFlapBearingEntries"));
            Object weld=raw(fcc,"weldedEngines"),weldFlaps=raw(weld,"flaps");
            children=!list(raw(fcc,"linkedMountEntries")).isEmpty()||!list(raw(weld,"links")).isEmpty()
                    ||!list(raw(weld,"engines")).isEmpty()||!list(raw(weld,"lastZeroTargets")).isEmpty()
                    ||!list(raw(weldFlaps,"links")).isEmpty()||!list(raw(weldFlaps,"captured")).isEmpty();
            ClassLoader loader=computer.getClass().getClassLoader();
            Class<?> sable=Class.forName("dev.ryanhcode.sable.Sable",false,loader);
            Object helper=sable.getField("HELPER").get(null);
            Class<?> helperType=Class.forName("dev.ryanhcode.sable.ActiveSableCompanion",false,loader);
            Class<?> sublevel=Class.forName("dev.ryanhcode.sable.sublevel.SubLevel",false,loader);
            containing=MethodHandles.publicLookup().findVirtual(helperType,"getContaining",MethodType.methodType(sublevel,BlockEntity.class)).bindTo(helper);
            body=containing(fcc);if(body==null)throw new IllegalStateException("missing body");
        }
        private Object containing(BlockEntity entity){try{return containing.invoke(entity);}catch(Throwable unavailable){throw new IllegalStateException(unavailable);}}
        public List<?> thrusters(){return thrust;}public List<?> flaps(){return flaps;}public boolean unsupportedChildren(){return children;}
        public Object existing(Object entry) {
            Object relative=raw(entry,"relativePos");if(!(relative instanceof BlockPos offset))return null;
            BlockPos pos=fcc.getBlockPos().offset(offset);
            LevelChunk chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            BlockEntity entity=chunk==null?null:chunk.getBlockEntities().get(pos);
            return entity==null||entity.isRemoved()||entity.getLevel()!=level||!pos.equals(entity.getBlockPos())||containing(entity)!=body?null:entity;
        }
        public boolean knownThruster(Object value) {
            if(!(value instanceof BlockEntity entity)||!AUDITED.get(entity.getClass()))return false;
            String name=entity.getClass().getName();Object offset;
            if(SVT.equals(name))offset=raw(entity,"offsetToComputer");
            else if(PROP.equals(name)) {
                try {
                    Class<?> support=Class.forName("ace.flight.neoforge.compat.AeronauticsPropellerFCCSupport",false,fcc.getClass().getClassLoader());
                    Object valueMap=FIELDS.get(support).get(null,"STATES");if(!(valueMap instanceof Map<?,?> states))return false;
                    Object state; synchronized(states){state=states.get(entity);}if(state==null)return false;
                    offset=raw(state,"offsetToComputer");
                }catch(ClassNotFoundException|LinkageError unavailable){return false;}
            }else return false;
            return offset instanceof BlockPos position&&entity.getBlockPos().offset(position).equals(fcc.getBlockPos());
        }
        public boolean knownFlap(Object value) {
            if(!(value instanceof BlockEntity entity)||!FLAP.equals(entity.getClass().getName())||!AUDITED.get(entity.getClass()))return false;
            Object offset=raw(entity,"cfc$offsetToComputer");
            return offset instanceof BlockPos position&&entity.getBlockPos().offset(position).equals(fcc.getBlockPos());
        }
    }
}
