package com.monpai.sailboatmod.compat.flightcontrol;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Real native default-capture contract; never substitutes a Pose or capture implementation. */
public final class NativeDefaultTrimEnvironmentJarProbe {
    private static final String SHA = "86691e6965bb9c3e54ef71001a4ebea798e1ccc9b30ccdb731538933f1379208";
    private static final String P = "ace.flight.physics.";
    private static final java.util.UUID BODY_ID=java.util.UUID.fromString("0bf1d134-7a6b-45d9-825e-4a569c9addce");
    private final ClassLoader loader;
    private int checks;
    private int ordinaryPressureCalls;
    private boolean externalRed;
    private String passiveExpected="";
    private record Fixture(Object model, Object pose, double seaLevel, Object computer, Object body) {
        Fixture(Object model,Object pose,double seaLevel) { this(model,pose,seaLevel,null,null); }
    }
    private NativeDefaultTrimEnvironmentJarProbe(ClassLoader loader) { this.loader = loader; }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("Usage: NativeDefaultTrimEnvironmentJarProbe nativeJar classpathFile [--wrong-config-red|--context-only|--passive-only]");
        run(Path.of(args[0]), Path.of(args[1]), args.length == 3 ? args[2] : "");
    }
    public static void run(Path nativeJar, Path classpathFile) throws Exception {
        run(nativeJar, classpathFile, "");
    }
    private static void run(Path nativeJar, Path classpathFile, String mode) throws Exception {
        if (!Files.isRegularFile(nativeJar)) throw new AssertionError("Explicit native jar missing: " + nativeJar);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(nativeJar)));
        if (!SHA.equals(sha)) throw new AssertionError("Native SHA mismatch: " + sha);
        List<java.net.URL> urls = new ArrayList<>();
        urls.add(nativeJar.toUri().toURL());
        for (String line : Files.readAllLines(classpathFile)) if (!line.isBlank()) {
            Path entry = Path.of(line);
            if (!Files.isRegularFile(entry)) throw new AssertionError("Native fixture dependency missing: " + entry);
            urls.add(entry.toUri().toURL());
        }
        try (var loader = new PassiveFixtureLoader(urls.toArray(java.net.URL[]::new), mode.equals("--passive-override"), mode.equals("--passive-abi"))) {
            var probe = new NativeDefaultTrimEnvironmentJarProbe(loader);
            System.out.println("Native SHA=" + sha + "; actual Sable Pose3d + actual IntegratedFlapEnvironment.capture; isolated JVM");
            if (mode.equals("--wrong-config-red")) probe.ordinaryContract(true);
            else if (mode.equals("--context-only")) probe.contextContract();
            else if (mode.equals("--passive-only")) probe.passiveContract();
            else if (mode.startsWith("--live-")) probe.liveFrameContract(mode);
            else if (mode.equals("--passive-override")) { probe.passiveExpected="default_passive_provider_override"; probe.passiveContract(); }
            else if (mode.equals("--passive-abi")) { probe.passiveExpected="default_passive_abi_unproven"; probe.passiveContract(); }
            else if (mode.equals("--unknown-external-red")) { probe.externalRed=true; probe.ordinaryContract(false); }
            else if (mode.isEmpty()) { probe.contract(); probe.contextContract(); probe.ordinaryContract(false); probe.passiveContract(); probe.liveFrameContract("--live-all"); }
            else throw new IllegalArgumentException("Unknown native probe mode: " + mode);
            if (mode.isEmpty()) {
                for (String reason : List.of("default_passive_provider_override","default_passive_abi_unproven"))
                    try (var alternate = new PassiveFixtureLoader(urls.toArray(java.net.URL[]::new),reason.contains("override"),reason.contains("abi"))) {
                        var rejected = new NativeDefaultTrimEnvironmentJarProbe(alternate);rejected.passiveExpected=reason;
                        rejected.passiveContract();probe.checks+=rejected.checks;
                    }
            }
            if(mode.isEmpty()) for(String resource:List.of("ace/flight/physics/AttitudeTargetUtil.class","ace/flight/navigation/NavigationVehicleStateSnapshot.class"))
                try(var alternate=new PassiveFixtureLoader(urls.toArray(java.net.URL[]::new),false,false)) {
                    alternate.corruptedResource=resource;
                    var rejected=new NativeDefaultTrimEnvironmentJarProbe(alternate);rejected.passiveExpected="default_model_unproven";
                    rejected.passiveContract();probe.checks+=rejected.checks;
                }
            System.out.println("NativeDefaultTrimEnvironmentJarProbe GREEN: " + probe.checks + " assertions");
        }
    }


    /** Copies real provider bytecode plus Sable's real Create mixin. Only unrelated initialization is suppressed.
     * No replacement functional method is used. Resources still resolve to the original input jars. */
    @SuppressWarnings("unchecked")
    private static final class PassiveFixtureLoader extends URLClassLoader {
        final boolean overrideCreate, mismatchedAbi;
        String corruptedResource;
        PassiveFixtureLoader(java.net.URL[] urls, boolean overrideCreate, boolean mismatchedAbi) {
            super(urls, ClassLoader.getPlatformClassLoader()); this.overrideCreate = overrideCreate; this.mismatchedAbi=mismatchedAbi;
        }
        @Override public java.io.InputStream getResourceAsStream(String name) {
            java.io.InputStream original=super.getResourceAsStream(name);
            if ((!mismatchedAbi && corruptedResource == null) || !name.equals(corruptedResource == null ? "ace/flight/physics/CachedPassiveSailPredictor.class" : corruptedResource) || original==null) return original;
            try (original) { byte[] bytes=original.readAllBytes();bytes[bytes.length-1]^=1;return new java.io.ByteArrayInputStream(bytes); }
            catch (java.io.IOException unavailable) { return null; }
        }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals("fixture.UnknownSail")) {
                try {
                    Object node=node(PassiveProviderFixture.CREATE.replace('.','/')+".class");
                    ((List<?>)field(node,"methods")).clear(); ((List<?>)field(node,"fields")).clear();
                    PassiveProviderFixture.put(node,"name","fixture/UnknownSail");
                    PassiveProviderFixture.put(node,"superName",PassiveProviderFixture.CREATE.replace('.','/'));
                    Object writer=Class.forName("org.objectweb.asm.ClassWriter",true,this).getConstructor(int.class).newInstance(0);
                    call(node,"accept",writer);byte[] bytes=(byte[])call(writer,"toByteArray");return defineClass(name,bytes,0,bytes.length);
                } catch(Exception failure) { throw new ClassNotFoundException(name,failure); }
            }
            if (!name.equals(PassiveProviderFixture.CREATE) && !name.equals(PassiveProviderFixture.SIMULATED)
                    && !name.equals("net.minecraft.world.level.block.state.properties.BlockStateProperties")
                    && !name.equals("net.minecraft.world.level.block.state.BlockState")
                    && !name.equals("net.minecraft.server.level.ServerLevel")
                    && !name.equals("dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot")) return super.findClass(name);
            try {
                Object node = node(name.replace('.', '/') + ".class");
                var methods = (java.util.List<Object>)field(node, "methods");
                if (name.equals(PassiveProviderFixture.CREATE)) {
                    Object mixin = node(PassiveProviderFixture.MIXIN);
                    var interfaces = (java.util.List<Object>)field(node, "interfaces");
                    for (Object iface : (java.util.List<?>)field(mixin, "interfaces")) if (!interfaces.contains(iface)) interfaces.add(iface);
                    for (Object method : (java.util.List<?>)field(mixin, "methods"))
                        if (!String.valueOf(field(method, "name")).startsWith("<")) methods.add(method);
                    if (overrideCreate) for (Object method : (java.util.List<?>)field(node(PassiveProviderFixture.PROVIDER.replace('.', '/')+".class"), "methods"))
                        if (field(method, "name").equals("sable$getLiftScalar")) methods.add(method);
                }
                methods.removeIf(method -> { try { return field(method, "name").equals("<clinit>"); } catch (Exception failure) { throw new IllegalStateException(failure); } });
                Object writer = Class.forName("org.objectweb.asm.ClassWriter", true, this).getConstructor(int.class).newInstance(0);
                call(node, "accept", writer);
                byte[] bytes = (byte[])call(writer, "toByteArray");
                System.out.println("FIXTURE transformation: "+name+"; unrelated static initializer suppressed"+
                        (name.equals(PassiveProviderFixture.CREATE) ? "; exact Sable mixin copied" : ""));
                return defineClass(name, bytes, 0, bytes.length);
            } catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
        }
        private Object node(String resource) throws Exception {
            byte[] bytes;
            try (var in = findResource(resource).openStream()) { bytes = in.readAllBytes(); }
            Object node = Class.forName("org.objectweb.asm.tree.ClassNode", true, this).getConstructor().newInstance();
            Object reader = Class.forName("org.objectweb.asm.ClassReader", true, this).getConstructor(byte[].class).newInstance((Object)bytes);
            call(reader, "accept", node, 0); return node;
        }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final class PassiveProviderFixture {
        static final String CREATE = "com.simibubi.create.content.contraptions.bearing.SailBlock";
        static final String SIMULATED = "dev.simulated_team.simulated.content.blocks.symmetric_sail.SymmetricSailBlock";
        static final String PROVIDER = "dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider";
        static final String MIXIN = "dev/ryanhcode/sable/neoforge/mixin/compatibility/create/sails_providing_lift/SailBlockMixin.class";
        private static Object unsafe() throws Exception {
            var f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            if (!f.trySetAccessible()) throw new IllegalAccessException("metadata fixture allocation"); return f.get(null);
        }
  public static Object nativeComputer(ClassLoader loader,Object level) throws Exception {
    Object computer=allocate(Class.forName("ace.flight.block.FlightControlComputerBlockEntity",true,loader));put(computer,"level",level);return computer;
  }
  public static Object defaultEnvironment(ClassLoader loader,Object computer,Object body) throws Exception {
    var method=computer.getClass().getDeclaredMethod("fixedWingEnvironment",Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel",false,loader));
    method.setAccessible(true);return method.invoke(computer,body);
  }
  public static Object allocate(Class<?> type) throws Exception { return call(unsafe(), "allocateInstance", type); }
  public static void put(Object owner,String name,Object value) throws Exception {
    Class<?> type=owner.getClass();java.lang.reflect.Field f=null;
    while(type!=null){try{f=type.getDeclaredField(name);break;}catch(NoSuchFieldException e){type=type.getSuperclass();}}
    if(f==null)throw new NoSuchFieldException(name);f.setAccessible(true);f.set(owner,value);
  }
  public static Object vector(ClassLoader loader,double x,double y,double z) throws Exception {
    return Class.forName("org.joml.Vector3d",true,loader).getConstructor(double.class,double.class,double.class).newInstance(x,y,z);
  }
  public static void putStatic(Class<?> type,String name,Object value) throws Exception {
    var f=type.getDeclaredField(name);
    Object unsafe=unsafe();
    call(unsafe,"putObject",call(unsafe,"staticFieldBase",f),call(unsafe,"staticFieldOffset",f),value);
  }
  public static void properties(ClassLoader loader) throws Exception {
    Class<?> props=Class.forName("net.minecraft.world.level.block.state.properties.BlockStateProperties",true,loader);
    if(props.getField("FACING").get(null)!=null)return;
    Class<?> direction=Class.forName("net.minecraft.core.Direction",true,loader);
    Class<?> directionProperty=Class.forName("net.minecraft.world.level.block.state.properties.DirectionProperty",true,loader);
    Object facing=directionProperty.getMethod("create",String.class).invoke(null,"facing");
    putStatic(props,"FACING",facing);
    Class<?> enumProperty=Class.forName("net.minecraft.world.level.block.state.properties.EnumProperty",true,loader);
    Object axis=enumProperty.getMethod("create",String.class,Class.class).invoke(null,"axis",Class.forName("net.minecraft.core.Direction$Axis",true,loader));
    putStatic(props,"AXIS",axis);
  }
  public static Object provider(ClassLoader loader,boolean symmetric) throws Exception {
    properties(loader);
    return allocate(Class.forName(symmetric?SIMULATED:CREATE,true,loader));
  }
  public static Object context(ClassLoader loader,boolean symmetric,int x,int y,int z) throws Exception {
    Object provider=provider(loader,symmetric);
    Class<?> state=Class.forName("net.minecraft.world.level.block.state.BlockState",true,loader);
    Object blockState=allocate(state);put(blockState,"owner",provider);
    Class<?> props=Class.forName("net.minecraft.world.level.block.state.properties.BlockStateProperties",true,loader);
    Object values=Class.forName("it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap",true,loader).getConstructor().newInstance();
    Object property=props.getField(symmetric?"AXIS":"FACING").get(null);
    Class<?> enumType=Class.forName(symmetric?"net.minecraft.core.Direction$Axis":"net.minecraft.core.Direction",true,loader);
    Object value=Enum.valueOf((Class)enumType,symmetric?"Z":"UP");
    ((java.util.Map)values).put(property,value);put(blockState,"values",values);
    Object pos=Class.forName("net.minecraft.core.BlockPos$MutableBlockPos",true,loader).getConstructor(int.class,int.class,int.class).newInstance(x,y,z);
    Object dir=Class.forName("net.minecraft.world.phys.Vec3",true,loader).getConstructor(double.class,double.class,double.class).newInstance(0d,symmetric?0d:-1d,symmetric?1d:0d);
    Class<?> ctx=Class.forName(PROVIDER+"$LiftProviderContext",true,loader);
    return ctx.getConstructors()[0].newInstance(pos,blockState,dir);
  }
  public static Object body(ClassLoader loader,Object pose) throws Exception {
    Object body=allocate(Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel",true,loader));put(body,"pose",pose);put(body,"uniqueId",BODY_ID);
    Object mass=allocate(Class.forName("dev.ryanhcode.sable.api.physics.mass.MergedMassTracker",true,loader));put(mass,"centerOfMass",vector(loader,0,0,0));put(body,"massTracker",mass);
    plot(loader,body,java.util.List.of());return body;
  }
  public static Object plot(ClassLoader loader,Object body,java.util.List<?> contexts) throws Exception {
    Object plot=allocate(Class.forName("dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot",true,loader));
    Object map=Class.forName("it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap",true,loader).getConstructor().newInstance();
    for(Object context:contexts){Object pos=context.getClass().getMethod("pos").invoke(context);long key=(Long)pos.getClass().getMethod("asLong").invoke(pos);((java.util.Map)map).put(key,context);}
    put(plot,"liftProviders",map);put(body,"plot",plot);return plot;
  }
  public static Object level(ClassLoader loader,Object dimension) throws Exception {
    Object level=allocate(Class.forName("net.minecraft.server.level.ServerLevel",true,loader));put(level,"dimension",dimension);return level;
  }
    }

    @SuppressWarnings("unchecked")
    private void passiveContract() throws Exception {
        int initialPressureCalls = ordinaryPressureCalls;
        Class<?> fixture = PassiveProviderFixture.class;
        Object pose = make("dev.ryanhcode.sable.companion.math.Pose3d", vector(0, 41, 0),
                make("org.joml.Quaterniond"), vector(0, 0, 0), vector(1, 1, 1));
        Object body = fixture.getMethod("body", ClassLoader.class, Object.class).invoke(null, loader, pose);
        Object mass = fixture.getMethod("allocate", Class.class).invoke(null, type("dev.ryanhcode.sable.api.physics.mass.MergedMassTracker"));
        fixture.getMethod("put", Object.class, String.class, Object.class).invoke(null, mass, "centerOfMass", vector(0,0,0));
        fixture.getMethod("put", Object.class, String.class, Object.class).invoke(null, body, "massTracker", mass);
        Object ordinary = fixture.getMethod("context", ClassLoader.class, boolean.class, int.class, int.class, int.class)
                .invoke(null, loader, false, 10, 20, 30);
        Object symmetric = fixture.getMethod("context", ClassLoader.class, boolean.class, int.class, int.class, int.class)
                .invoke(null, loader, true, 11, 20, 30);
        List<Object> contexts = List.of(ordinary, symmetric);
        fixture.getMethod("plot", ClassLoader.class, Object.class, List.class).invoke(null, loader, body, contexts);
        Object predictor = passivePredictor(body, contexts, .025);
        Object source = type(P + "IntegratedFlapEnvironment").getMethod("capture", type(P + "SymmetricSailFlapAllocation$PressureSampler"),
                type("dev.ryanhcode.sable.companion.math.Pose3dc"), double.class).invoke(null, pressureSampler(), pose, 63d);
        Object model = model(source, true, predictor);
        Object dimensionName = callStatic(type("net.minecraft.resources.ResourceLocation"), "parse", "minecraft:overworld");
        Object registryName = callStatic(type("net.minecraft.resources.ResourceLocation"), "parse", "minecraft:dimension");
        Object registry = callStatic(type("net.minecraft.resources.ResourceKey"), "createRegistryKey", registryName);
        Object dimension = callStatic(type("net.minecraft.resources.ResourceKey"), "create", registry, dimensionName);
        String s = "dev.ryanhcode.sable.physics.config.dimension_physics.";
        java.util.Map<Object,Object> configured = (java.util.Map<Object,Object>)staticField(type(s+"DimensionPhysicsData"), "DIMENSION_PHYSICS_DATA");
        java.util.Map<Object,Object> defaults = (java.util.Map<Object,Object>)staticField(type(s+"DimensionPhysicsData"), "DEFAULT_DIMENSION_PHYSICS_DATA");
        var oldConfigured = new java.util.HashMap<>(configured); var oldDefaults = new java.util.HashMap<>(defaults);
        try {
            configured.clear(); defaults.clear();
            Object curve = make(s+"BezierResourceFunction", List.of(make(s+"BezierResourceFunction$BezierPoint", 0d, 1d, 0d)));
            defaults.put(dimension, make(s+"DimensionPhysics", dimensionName, 0, java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Optional.of(1.25), java.util.Optional.of(curve), java.util.Optional.empty(), false));
            Object level = fixture.getMethod("level", ClassLoader.class, Object.class).invoke(null, loader, dimension);
            fixture.getMethod("put", Object.class, String.class, Object.class).invoke(null, body, "level", level);
            Object computer = new FixtureComputer(level);
            var context = DefaultTrimEnvironment.context(computer, body, model, loader);
            if (!passiveExpected.isEmpty()) {
                check(context.reason().equals(passiveExpected),"unknown provider override/version rejected: expected="+passiveExpected+"; actual="+context.reason());
                return;
            }
            check(context.reason().isEmpty() && context.pressureRequired(),
                    "RED passive regression: known exact Create and Simulated captured providers admit default metadata and require ordinary pressure; actual="+context.reason());
            Object original = make("ace.flight.navigation.FixedWingAxialBudget$Limits", false, 3.25, 2.5, 1.2);
            var access = new NativeFixedWingTrimEnvironment.ReflectionAccess(loader);
            Object result = passiveSupplement(access, original, model, pose, context, context);
            check(Boolean.TRUE.equals(call(call(result,"environment"),"available")), "known passive path publishes observed environment");
            check(!Boolean.TRUE.equals(call(result,"available")) && number(result,"acceleration")==3.25 && number(result,"braking")==2.5,
                    "passive environment preserves unavailable axial authority and native budget values");
            Object key = call(call(result,"environment"),"settingsKey");
            Object recapturedModel = model(source, true, passivePredictor(body, contexts, .025));
            var recaptured = DefaultTrimEnvironment.context(computer, body, recapturedModel, loader);
            Object recapturedResult = passiveSupplement(access, original, recapturedModel, pose, recaptured, recaptured);
            check(key.equals(call(call(recapturedResult,"environment"),"settingsKey")), "passive key excludes native wrapper and capture object identities");
            Object state=call(ordinary,"state");Object knownBlock=call(state,"getBlock");
            Object unknownBlock=PassiveProviderFixture.allocate(type("fixture.UnknownSail"));
            PassiveProviderFixture.put(state,"owner",unknownBlock);
            check(DefaultTrimEnvironment.context(computer,body,model,loader).reason().equals("default_passive_provider_unproven"),
                    "unknown provider subclass is rejected without inheriting known-provider admission");
            PassiveProviderFixture.put(state,"owner",knownBlock);
            java.util.Map<Object,Object> stateValues=(java.util.Map<Object,Object>)call(state,"getValues");
            Object nativeProperty=stateValues.keySet().iterator().next(),nativeValue=stateValues.values().iterator().next();
            Object alternateProperty=callStatic(type("net.minecraft.world.level.block.state.properties.DirectionProperty"),"create","facing");
            stateValues.clear();stateValues.put(alternateProperty,nativeValue);
            check(DefaultTrimEnvironment.context(computer,body,model,loader).reason().equals("default_passive_state_unproven"),
                    "same-named foreign state property must not impersonate the provider's native FACING configuration");
            stateValues.clear();stateValues.put(nativeProperty,nativeValue);
            List<?> originalBodies=(List<?>)field(model,"bodies");Object originalBody=originalBodies.getFirst();
            Object wrongAggregate=make(P+"NativeBodyFlapModel$Body","wrong-aggregate-body",call(originalBody,"velocity"),call(originalBody,"omega"),
                    call(originalBody,"rotationPoint"),call(originalBody,"center"),call(originalBody,"inertia"),call(originalBody,"mass"));
            PassiveProviderFixture.put(model,"bodies",List.of(wrongAggregate));
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_passive_ownership_unproven");
            PassiveProviderFixture.put(model,"bodies",originalBodies);
            int[] flapBodies=(int[])field(model,"flapBodies");int originalOwner=flapBodies[0];flapBodies[0]=1;
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_passive_ownership_unproven");
            flapBodies[0]=originalOwner;
            Object nativeGroup=((List<?>)field(model,"groups")).getFirst();int[] indices=(int[])field(nativeGroup,"indices");int originalIndex=indices[0];indices[0]=7;
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_passive_ownership_unproven");
            indices[0]=originalIndex;
            Object grouped=field(nativeGroup,"model");Object groupModels=field(grouped,"models");PassiveProviderFixture.put(grouped,"models",List.of());
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_passive_ownership_unproven");
            PassiveProviderFixture.put(grouped,"models",groupModels);
            Object topEffector=((List<?>)field(model,"models")).getFirst();
            Object differentEffector=make(P+"SymmetricSailFlapAllocation$Effector",0d,-45d,45d,vector(0,0,1),vector(0,0,0),call(topEffector,"surfaces"));
            PassiveProviderFixture.put(grouped,"models",List.of(differentEffector));
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_passive_ownership_unproven");
            PassiveProviderFixture.put(grouped,"models",groupModels);
            Object nativeEnvironment=field(model,"environment");
            PassiveProviderFixture.put(grouped,"environment",pressureSampler());
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_environment_graph_unproven");
            PassiveProviderFixture.put(grouped,"environment",nativeEnvironment);
            Object delegate=field(nativeEnvironment,"val$environment");
            PassiveProviderFixture.put(nativeEnvironment,"val$environment",pressureSampler());
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_environment_graph_unproven");
            PassiveProviderFixture.put(nativeEnvironment,"val$environment",delegate);
            PassiveProviderFixture.put(nativeEnvironment,"this$0",null);
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_environment_graph_unproven");
            PassiveProviderFixture.put(nativeEnvironment,"this$0",model);
            PassiveProviderFixture.put(model,"environment",field(recapturedModel,"environment"));
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_environment_graph_unproven");
            PassiveProviderFixture.put(model,"environment",nativeEnvironment);
            Object nativePredictor = field(predictor,"nativePredictor");
            Object capturedBody = field(nativePredictor,"val$body");
            PassiveProviderFixture.put(nativePredictor,"val$body",null);
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_passive_body_unproven");
            PassiveProviderFixture.put(nativePredictor,"val$body",capturedBody);
            PassiveProviderFixture.put(nativePredictor,"val$dt",.05);
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_passive_timestep_unproven");
            PassiveProviderFixture.put(nativePredictor,"val$dt",.025);
            Object orientation = field(nativePredictor,"val$orientation");
            call(orientation,"rotateX",.2);
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_passive_pose_unproven");
            call(orientation,"identity");
            Object offset = field(nativePredictor,"val$offset"); call(offset,"set",1d,0d,0d);
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_passive_offset_unproven");
            call(offset,"zero");
            var wrongPublished = stateAt(model,pose,context,.025);
            wrongPublished = new TrimEnvironmentFrame.State(wrongPublished.snapshot(),model,BODY_ID,BODY_ID,.025,.025,
                    0,Math.sin(.1),0,Math.cos(.1),1,1,1,wrongPublished.executionVehicle(),true,true,true,true,context);
            check(access.supplement(original,model,List.of(),wrongPublished,stateAt(model,pose,context,0))==original
                    && access.reason().equals("default_passive_pose_unproven"),"published State quaternion inconsistent with native captured/live pose fails closed");
            var wrongClock = stateAt(model,pose,context,.05);
            check(access.supplement(original,model,List.of(),wrongClock,stateAt(model,pose,context,0))==original
                    && access.reason().equals("default_passive_timestep_unproven"),"captured timestep must match current State interval");
            // Same addition as native clock avoids rejecting correct accumulated times due to subtraction rounding.
            check(access.supplement(original,model,List.of(),stateAt(model,pose,context,177d+.025),stateAt(model,pose,context,177d))!=original,
                    "passive timestep guard accepts accumulated native clocks using before+dt");
            defaults.clear();
            var noPressure = DefaultTrimEnvironment.context(computer,body,model,loader);
            check(noPressure.reason().equals("default_pressure_cache_missing"),"passive models require ordinary pressure even with all controlled surfaces integrated");
            defaults.put(dimension, make(s+"DimensionPhysics", dimensionName, 0, java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Optional.of(1.25), java.util.Optional.of(curve), java.util.Optional.empty(), false));
            var unknownPredictor = Proxy.newProxyInstance(loader,new Class<?>[]{type(P+"PassiveSailPredictor")},
                    (proxy,method,args)->{throw new AssertionError("unknown predictor callback invoked");});
            Object unknownModel = model(source,true,unknownPredictor);
            check(DefaultTrimEnvironment.context(computer,body,unknownModel,loader).reason().equals("default_passive_predictor_unproven"),
                    "unknown passive predictor is rejected without invoking callbacks");
            Object nested = make(P+"CachedPassiveSailPredictor",predictor);
            check(DefaultTrimEnvironment.context(computer,body,model(source,true,nested),loader).reason().equals("default_passive_predictor_unproven"),
                    "unproven nested cached predictor wrapper fails closed");
            Object pos = call(ordinary,"pos"); call(pos,"set",12,20,30);
            var movedGeometry = DefaultTrimEnvironment.context(computer,body,model,loader);
            check(!context.modelEvidence().equals(movedGeometry.modelEvidence()), "copied mutable BlockPos geometry cannot mutate earlier context evidence");
            passiveReject(access,original,model,pose,context,movedGeometry,"default_passive_geometry_changed");
            Object geometryResult = passiveSupplement(access,original,model,pose,movedGeometry,movedGeometry);
            check(geometryResult!=original && !key.equals(call(call(geometryResult,"environment"),"settingsKey")), "coherent changed passive geometry produces a different stable key");
            call(pos,"set",10,20,30);
            Object livePosition = call(pose,"position"); call(livePosition,"set",5d,41d,0d);
            passiveReject(access,original,model,pose,context,DefaultTrimEnvironment.context(computer,body,model,loader),"default_passive_body_changed");
            // A different complete same-frame pose is valid on the next invocation and must not reset environment settings.
            Object movedSource = type(P+"IntegratedFlapEnvironment").getMethod("capture",type(P+"SymmetricSailFlapAllocation$PressureSampler"),
                    type("dev.ryanhcode.sable.companion.math.Pose3dc"),double.class).invoke(null,pressureSampler(),pose,63d);
            Object movedModel = model(movedSource,true,passivePredictor(body,contexts,.025));
            var moved = DefaultTrimEnvironment.context(computer,body,movedModel,loader);
            Object movedResult = passiveSupplement(access,original,movedModel,pose,moved,moved);
            check(movedResult!=original && key.equals(call(call(movedResult,"environment"),"settingsKey")), "passive key excludes coherent changing live pose and world position");
            call(livePosition,"set",0d,41d,0d);
            check(ordinaryPressureCalls==initialPressureCalls && ((Number)call(predictor,"evaluations")).intValue()==0,
                    "passive observer never evaluates ordinary pressure, native predictor, contribution or solver");
            Object nativeComputer=PassiveProviderFixture.nativeComputer(loader,level);
            Object genuine=PassiveProviderFixture.defaultEnvironment(loader,nativeComputer,body);
            Object combinedModel=model(source,false,predictor);
            PassiveProviderFixture.put(source,"val$external",field(genuine,"val$external"));
            var combined=DefaultTrimEnvironment.context(nativeComputer,body,combinedModel,loader);
            check(passiveSupplement(access,original,combinedModel,pose,combined,combined)!=original,
                    "actual airframe shape ordinary controlled surfaces plus known passive providers admits native default environment");
            call(livePosition,"set",0d,4628d,0d);call(call(pose,"orientation"),"rotateY",.4);
            Object highSource=type(P+"IntegratedFlapEnvironment").getMethod("capture",type(P+"SymmetricSailFlapAllocation$PressureSampler"),
                    type("dev.ryanhcode.sable.companion.math.Pose3dc"),double.class).invoke(null,pressureSampler(),pose,63d);
            Object highModel=model(highSource,true,passivePredictor(body,contexts,.025));
            var high=DefaultTrimEnvironment.context(computer,body,highModel,loader);
            check(key.equals(call(call(passiveSupplement(access,original,highModel,pose,high,high),"environment"),"settingsKey")),
                    "passive stable key excludes coherent changing world altitude and orientation");
            call(livePosition,"set",0d,41d,0d);call(call(pose,"orientation"),"identity");
            // Actual copied 52-context inventory from fix52 cached-native-predictor52-01.jsonl, SHA256 3ffa250d4886383d7143cd0fd7b261495820265ed8af093ebe306671954d27cb.
            int[][] inventory={{20481035,130,20483080,0},
                    {20481045,132,20483087,1},
                    {20481044,130,20483083,0},
                    {20481045,130,20483091,0},
                    {20481035,130,20483084,0},
                    {20481035,130,20483094,0},
                    {20481045,130,20483086,0},
                    {20481035,130,20483090,0},
                    {20481047,131,20483087,1},
                    {20481045,130,20483082,0},
                    {20481035,130,20483089,0},
                    {20481034,130,20483091,0},
                    {20481044,130,20483088,0},
                    {20481035,130,20483085,0},
                    {20481035,130,20483093,0},
                    {20481034,130,20483084,0},
                    {20481035,130,20483088,0},
                    {20481034,130,20483092,0},
                    {20481046,132,20483087,1},
                    {20481035,130,20483083,0},
                    {20481046,131,20483087,1},
                    {20481045,130,20483084,0},
                    {20481046,133,20483087,1},
                    {20481034,130,20483089,0},
                    {20481044,130,20483090,0},
                    {20481045,130,20483092,0},
                    {20481034,130,20483086,0},
                    {20481045,130,20483081,0},
                    {20481035,130,20483091,0},
                    {20481034,130,20483088,0},
                    {20481035,130,20483082,0},
                    {20481044,130,20483085,0},
                    {20481035,130,20483092,0},
                    {20481034,130,20483083,0},
                    {20481045,130,20483089,0},
                    {20481044,130,20483084,0},
                    {20481035,130,20483086,0},
                    {20481047,130,20483087,1},
                    {20481045,130,20483090,0},
                    {20481036,130,20483080,0},
                    {20481045,130,20483085,0},
                    {20481044,130,20483089,0},
                    {20481034,130,20483082,0},
                    {20481045,130,20483083,0},
                    {20481035,130,20483081,0},
                    {20481045,130,20483093,0},
                    {20481044,130,20483091,0},
                    {20481036,130,20483094,0},
                    {20481034,130,20483090,0},
                    {20481044,130,20483086,0},
                    {20481034,130,20483085,0},
                    {20481045,130,20483088,0}};
            var capturedInventory=new ArrayList<Object>();int createCount=0,symmetricCount=0;
            for (int[] item:inventory) {
                boolean sym=item[3]==1;capturedInventory.add(PassiveProviderFixture.context(loader,sym,item[0],item[1],item[2]));
                if(sym)symmetricCount++;else createCount++;
            }
            PassiveProviderFixture.plot(loader,body,capturedInventory);
            Object inventoryModel=model(source,true,passivePredictor(body,capturedInventory,.025));
            var inventoryContext=DefaultTrimEnvironment.context(computer,body,inventoryModel,loader);
            Object inventoryResult=passiveSupplement(access,original,inventoryModel,pose,inventoryContext,inventoryContext);
            check(inventoryResult!=original && createCount==46 && symmetricCount==6,"actual recorded 52 passive contexts (46 ordinary, 6 symmetric) pass bounded native metadata admission");
            Object inventoryKey=call(call(inventoryResult,"environment"),"settingsKey");
            java.util.Collections.reverse(capturedInventory);PassiveProviderFixture.plot(loader,body,capturedInventory);
            Object reversedModel=model(source,true,passivePredictor(body,capturedInventory,.025));
            var reversed=DefaultTrimEnvironment.context(computer,body,reversedModel,loader);
            check(inventoryKey.equals(call(call(passiveSupplement(access,original,reversedModel,pose,reversed,reversed),"environment"),"settingsKey")),
                    "passive geometry key is independent of provider plot iteration order");
            PassiveProviderFixture.plot(loader,body,contexts);
            Object down=PassiveProviderFixture.context(loader,false,10,20,30);Object downState=call(down,"state");
            java.util.Map<Object,Object> downValues=(java.util.Map<Object,Object>)call(downState,"getValues");
            Object facing=downValues.keySet().iterator().next();downValues.put(facing,Enum.valueOf((Class)type("net.minecraft.core.Direction"),"DOWN"));
            down=make(PassiveProviderFixture.PROVIDER+"$LiftProviderContext",call(down,"pos"),downState,make("net.minecraft.world.phys.Vec3",0d,1d,0d));
            List<Object> changedState=List.of(down,symmetric);PassiveProviderFixture.plot(loader,body,changedState);
            Object changedStateModel=model(source,true,passivePredictor(body,changedState,.025));
            var changedStateContext=DefaultTrimEnvironment.context(computer,body,changedStateModel,loader);
            check(!key.equals(call(call(passiveSupplement(access,original,changedStateModel,pose,changedStateContext,changedStateContext),"environment"),"settingsKey")),
                    "copied passive block-state facing and matching direction change the geometry key");
            PassiveProviderFixture.plot(loader,body,contexts);
            Object handle=new Object();
            var beforeState=stateAt(model,pose,context,0);var afterState=stateAt(model,pose,context,.025);
            var entry=new TrimEnvironmentFrame.Entry(computer,body,handle,beforeState,.025);
            check(!TrimEnvironmentFrame.permits(entry,computer,body,handle,afterState,afterState.snapshot(),1,1,1),
                    "unchanged cached NativeBody model remains ineligible under strict same-invocation frame guard");
            var refreshedState=stateAt(recapturedModel,pose,recaptured,.025);
            check(TrimEnvironmentFrame.permits(entry,computer,body,handle,refreshedState,refreshedState.snapshot(),1,1,1),
                    "new same-frame model with coherent passive capture satisfies existing frame guard");
        } finally {
            configured.clear(); configured.putAll(oldConfigured); defaults.clear(); defaults.putAll(oldDefaults);
        }
    }

    /** Actual 8 Pre tuples from pose-model53-02; raw logical q and native normalized publication are distinct. */
    @SuppressWarnings("unchecked")
    private void liveFrameContract(String mode) throws Exception {
        double[][] tuples={{-0.0017493730410933495d,0.19497109949588776d,-0.004379666410386562d,0.9807977080345154d,-18206.37890625d,-20.044525146484375d,6525.384765625d,20481039.402923815d,129.91600069821266d,20483087.500575643d,20481039.402923815d,129.91600069821266d,20483087.500575643d,-0.001749372940059808d,0.1949710882354989d,-0.00437966615744268d,0.9807976513893838d},
                {-0.0019121092045679688d,0.19497092068195343d,-0.004704453982412815d,0.9807959198951721d,-18206.94140625d,-20.0455379486084d,6525.6220703125d,20481039.402923815d,129.91600069821266d,20483087.500575643d,20481039.402923815d,129.91600069821266d,20483087.500575643d,-0.0019121091238354076d,0.19497091244994358d,-0.004704453783782622d,0.9807958784842702d},
                {-0.002065202919766307d,0.19497020542621613d,-0.005022586323320866d,0.9807941317558289d,-18207.50390625d,-20.046802520751953d,6525.8505859375d,20481039.402923815d,129.91600069821266d,20483087.500575643d,20481039.402923815d,129.91600069821266d,20483087.500575643d,-0.002065202918395956d,0.194970205296845d,-0.0050225863199881635d,0.9807941311050298d},
                {-0.0022088969126343727d,0.19496898353099823d,-0.005333611276000738d,0.9807924628257751d,-18208.06640625d,-20.0483341217041d,6526.0791015625d,20481039.402923815d,129.91600069821266d,20483087.500575643d,20481039.402923815d,129.91600069821266d,20483087.500575643d,-0.002208896817309682d,0.19496897511713426d,-0.0053336110458293605d,0.9807924204997898d},
                {-0.0023434211034327745d,0.19496722519397736d,-0.0056371260434389114d,0.9807907938957214d,-18208.62890625d,-20.050140380859375d,6526.3076171875d,20481039.402923815d,129.91600069821266d,20483087.500575643d,20481039.402923815d,129.91600069821266d,20483087.500575643d,-0.0023434210224641395d,0.19496721845757387d,-0.005637125848667941d,0.9807907600079614d},
                {-0.0024690101854503155d,0.1949649304151535d,-0.005932727362960577d,0.9807891249656677d,-18209.19140625d,-20.052234649658203d,6526.5361328125d,20481039.402923815d,129.91600069821266d,20483087.500575643d,20481039.402923815d,129.91600069821266d,20483087.500575643d,-0.00246901027802837d,0.1949649377255624d,-0.005932727585414239d,0.9807891617413561d},
                {-0.0025858867447823286d,0.19496215879917145d,-0.006220064591616392d,0.9807876944541931d,-18209.75390625d,-20.054624557495117d,6526.7646484375d,20481039.402923815d,129.91600069821266d,20483087.500575643d,20481039.402923815d,129.91600069821266d,20483087.500575643d,-0.002585886588374743d,0.19496214700686823d,-0.0062200642153952415d,0.9807876351311637d},
                {-0.002694268012419343d,0.19495882093906403d,-0.006498775910586119d,0.980786144733429d,-18210.31640625d,-20.05731964111328d,6526.9931640625d,20481039.402923815d,129.91600069821266d,20483087.500575643d,20481039.402923815d,129.91600069821266d,20483087.500575643d,-0.0026942681515359007d,0.19495883100562011d,-0.006498776246145658d,0.980786195375604d}};
        Object dimensionName=callStatic(type("net.minecraft.resources.ResourceLocation"),"parse","minecraft:overworld");
        Object registryName=callStatic(type("net.minecraft.resources.ResourceLocation"),"parse","minecraft:dimension");
        Object registry=callStatic(type("net.minecraft.resources.ResourceKey"),"createRegistryKey",registryName);
        Object dimension=callStatic(type("net.minecraft.resources.ResourceKey"),"create",registry,dimensionName);
        String pressure="dev.ryanhcode.sable.physics.config.dimension_physics.";
        java.util.Map<Object,Object> configured=(java.util.Map<Object,Object>)staticField(type(pressure+"DimensionPhysicsData"),"DIMENSION_PHYSICS_DATA");
        java.util.Map<Object,Object> defaults=(java.util.Map<Object,Object>)staticField(type(pressure+"DimensionPhysicsData"),"DEFAULT_DIMENSION_PHYSICS_DATA");
        var oldConfigured=new java.util.HashMap<>(configured);var oldDefaults=new java.util.HashMap<>(defaults);
        try {
            configured.clear();defaults.clear();
            Object curve=make(pressure+"BezierResourceFunction",List.of(make(pressure+"BezierResourceFunction$BezierPoint",0d,1d,0d)));
            defaults.put(dimension,make(pressure+"DimensionPhysics",dimensionName,0,java.util.Optional.empty(),java.util.Optional.empty(),
                    java.util.Optional.of(1.25),java.util.Optional.of(curve),java.util.Optional.empty(),false));
            Object level=PassiveProviderFixture.level(loader,dimension);
            Object original=make("ace.flight.navigation.FixedWingAxialBudget$Limits",false,3.25,2.5,1.2);
            var access=new NativeFixedWingTrimEnvironment.ReflectionAccess(loader);
            for(int index=0;index<tuples.length;index++) {
                double[] a=tuples[index];
                Object pose=make("dev.ryanhcode.sable.companion.math.Pose3d",vector(a[4],a[5],a[6]),
                        make("org.joml.Quaterniond",a[0],a[1],a[2],a[3]),vector(a[7],a[8],a[9]),vector(1,1,1));
                Object body=PassiveProviderFixture.body(loader,pose);PassiveProviderFixture.put(body,"level",level);
                Object mass=call(body,"getMassTracker");PassiveProviderFixture.put(mass,"centerOfMass",vector(a[10],a[11],a[12]));
                PassiveProviderFixture.put(mass,"mass",95d);PassiveProviderFixture.put(mass,"inertiaTensor",make("org.joml.Matrix3d"));
                List<Object> contexts=List.of(PassiveProviderFixture.context(loader,false,20481035,130,20483080),
                        PassiveProviderFixture.context(loader,true,20481045,132,20483087));
                PassiveProviderFixture.plot(loader,body,contexts);
                Object computer=PassiveProviderFixture.nativeComputer(loader,level);
                Object published=nativeVehicle(body,177d);
                check(number(published,"quaternionX")==a[13] && number(published,"quaternionY")==a[14] && number(published,"quaternionZ")==a[15] && number(published,"quaternionW")==a[16],
                        "actual Pre tuple "+index+" full native two-stage normalization exactly reproduces published quaternion");
                Object raw=call(pose,"orientation");
                check(number(raw,"x")!=number(published,"quaternionX") || number(raw,"y")!=number(published,"quaternionY")
                        || number(raw,"z")!=number(published,"quaternionZ") || number(raw,"w")!=number(published,"quaternionW"),
                        "actual Pre tuple "+index+" preserves raw non-unit quaternion separately from publication");
                Object capturedBody=captureNativeBody(body);
                check(call(capturedBody,"center").equals(vector(a[10],a[11],a[12]))
                        && call(capturedBody,"rotationPoint").equals(vector(a[7],a[8],a[9])),
                        "actual Pre tuple "+index+" real FlapAirflowCapture reproduces current native COM/pivot roundtrip");
                Object source=PassiveProviderFixture.defaultEnvironment(loader,computer,body);
                Object predictor=passivePredictor(body,contexts,.025,vector(a[10],a[11],a[12]));
                check(field(field(predictor,"nativePredictor"),"val$orientation").equals(raw),
                        "actual Pre tuple "+index+" passive native capture retains exact raw logical quaternion");
                if(!mode.equals("--live-evidence") && !mode.equals("--live-com")) {
                    Object firstModel=model(source,true,predictor,capturedBody,vector(a[10],a[11],a[12]));
                    var context=DefaultTrimEnvironment.context(computer,body,firstModel,loader);
                    Object admitted=access.supplement(original,firstModel,List.of(),nativeStateAt(firstModel,body,context,177d+.025),nativeStateAt(firstModel,body,context,177d));
                    check(admitted!=original,"RED actual raw/normalized Pre tuple "+index+" admits environment; actual="+access.reason());
                    check(!Boolean.TRUE.equals(call(admitted,"available")),"actual Pre tuple keeps axial authority unavailable");
                    var correct=nativeStateAt(firstModel,body,context,177d+.025);
                    var rawPublication=new TrimEnvironmentFrame.State(correct.snapshot(),firstModel,BODY_ID,BODY_ID,correct.physicsTime(),correct.clock(),
                            a[0],a[1],a[2],a[3],1,1,1,correct.snapshot(),true,true,true,true,context);
                    check(access.supplement(original,firstModel,List.of(),rawPublication,nativeStateAt(firstModel,body,context,177d))==original
                            && access.reason().equals("default_passive_pose_unproven"),"actual Pre tuple "+index+" raw quaternion cannot impersonate native normalized publication");
                }
            }
            if(mode.equals("--live-com") || mode.equals("--live-all")) {
                double[] a=tuples[0];Object center0=vector(20481039.401947886,129.92079804994552,20483087.500575643);
                Object center1=vector(a[10],a[11],a[12]);
                Object pose=make("dev.ryanhcode.sable.companion.math.Pose3d",vector(a[4],a[5],a[6]),make("org.joml.Quaterniond"),center0,vector(1,1,1));
                Object body=PassiveProviderFixture.body(loader,pose);PassiveProviderFixture.put(body,"level",level);
                Object mass=call(body,"getMassTracker");PassiveProviderFixture.put(mass,"centerOfMass",center0);
                PassiveProviderFixture.put(mass,"mass",95d);PassiveProviderFixture.put(mass,"inertiaTensor",make("org.joml.Matrix3d"));
                List<Object> contexts=List.of(PassiveProviderFixture.context(loader,false,20481035,130,20483080),
                        PassiveProviderFixture.context(loader,true,20481045,132,20483087));
                PassiveProviderFixture.plot(loader,body,contexts);Object computer=PassiveProviderFixture.nativeComputer(loader,level);
                Object source0=PassiveProviderFixture.defaultEnvironment(loader,computer,body);
                Object oldModel=model(source0,true,passivePredictor(body,contexts,.025,center0),captureNativeBody(body),center0);
                var oldContext=DefaultTrimEnvironment.context(computer,body,oldModel,loader);
                System.out.println("COM_C0 exact tuple: raw="+oldContext.bodyFrame().center()+"; rawPivot="+oldContext.bodyFrame().pose().rotationPoint()
                        +"; native="+oldContext.modelEvidence().passive().getFirst().nativeCenter()+"; nativePivot="+oldContext.modelEvidence().passive().getFirst().rotationPoint()
                        +"; solver="+oldContext.modelEvidence().passive().getFirst().solverCenter()+"; offset="+oldContext.modelEvidence().passive().getFirst().offset());
                Object oldResult=access.supplement(original,oldModel,List.of(),nativeStateAt(oldModel,body,oldContext,177d+.025),nativeStateAt(oldModel,body,oldContext,177d));
                check(oldResult!=original,"old cached model starts coherent at observed COM C0; context="+oldContext.reason()+"; actual="+access.reason());
                Object stableKey=call(call(oldResult,"environment"),"settingsKey");
                call(call(pose,"rotationPoint"),"set",a[7],a[8],a[9]);PassiveProviderFixture.put(mass,"centerOfMass",center1);
                // Sable updates COM before FCC scope entry; before.model still holds previous substep C0.
                var entry=DefaultTrimEnvironment.context(computer,body,oldModel,loader);
                Object newSource=PassiveProviderFixture.defaultEnvironment(loader,computer,body);
                Object newPredictor=passivePredictor(body,contexts,.025,vector(a[10],a[11],a[12]));
                Object currentModel=model(newSource,true,newPredictor,captureNativeBody(body),center1);
                var current=DefaultTrimEnvironment.context(computer,body,currentModel,loader);
                var before=nativeStateAt(oldModel,body,entry,177d);var after=nativeStateAt(currentModel,body,current,177d+.025);
                check(TrimEnvironmentFrame.permits(new TrimEnvironmentFrame.Entry(computer,body,computer,before,.025),computer,body,computer,after,after.snapshot(),1,1,1),
                        "actual old-model/new-model COM lifecycle keeps strict same-frame guard");
                Object result=access.supplement(original,currentModel,List.of(),after,before);
                check(result!=original,"RED actual dynamic COM C0→C1 with unchanged immutable passive contexts admits current model; actual="+access.reason());
                check(stableKey.equals(call(call(result,"environment"),"settingsKey")),"dynamic COM and solver centers do not reset immutable environment settings key");
                Object offset=field(field(newPredictor,"nativePredictor"),"val$offset");call(offset,"set",.125d,0d,0d);
                var wrong=DefaultTrimEnvironment.context(computer,body,currentModel,loader);
                check(access.supplement(original,currentModel,List.of(),nativeStateAt(currentModel,body,wrong,177d+.025),before)==original
                        && access.reason().equals("default_passive_offset_unproven"),"current-model wrong COM offset still fails closed");
                call(offset,"zero");
                Object grouped=field(((List<?>)field(currentModel,"groups")).getFirst(),"model");
                Object groupedSolver=field(grouped,"solverCenter");call(groupedSolver,"add",.125d,0d,0d);
                var wrongSolver=DefaultTrimEnvironment.context(computer,body,currentModel,loader);
                check(access.supplement(original,currentModel,List.of(),nativeStateAt(currentModel,body,wrongSolver,177d+.025),before)==original
                        && access.reason().equals("default_passive_offset_unproven"),"current grouped solver center must still match the current aggregate solver");
                call(groupedSolver,"set",a[10],a[11],a[12]);
                Object currentBody=((List<?>)field(currentModel,"bodies")).getFirst();Object recordCenter=field(currentBody,"center");
                Object groupedCenter=field(grouped,"nativeCenter");call(recordCenter,"add",.125d,0d,0d);call(groupedCenter,"add",.125d,0d,0d);
                var wrongCom=DefaultTrimEnvironment.context(computer,body,currentModel,loader);
                check(access.supplement(original,currentModel,List.of(),nativeStateAt(currentModel,body,wrongCom,177d+.025),before)==original
                        && access.reason().equals("default_passive_offset_unproven"),"current native COM must still match the exact live COM/pivot roundtrip");
            }
        } finally {configured.clear();configured.putAll(oldConfigured);defaults.clear();defaults.putAll(oldDefaults);}
    }
    private TrimEnvironmentFrame.State nativeStateAt(Object model,Object body,Object context,double clock) throws Exception {
        Object snapshot=nativeVehicle(body,clock);
        return new TrimEnvironmentFrame.State(snapshot,model,BODY_ID,BODY_ID,clock,clock,
                number(snapshot,"quaternionX"),number(snapshot,"quaternionY"),number(snapshot,"quaternionZ"),number(snapshot,"quaternionW"),
                1,1,1,snapshot,true,true,true,true,context);
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private Object nativeVehicle(Object body,double clock) throws Exception {
        Object q=callStatic(type(P+"AttitudeTargetUtil"),"getOrientation",body);
        return make("ace.flight.navigation.NavigationVehicleStateSnapshot",Enum.valueOf((Class)type("ace.flight.navigation.NavigationVehicleStateSnapshot$Status"),"AVAILABLE"),
                "","minecraft:overworld",BODY_ID,0d,0d,0d,number(q,"x"),number(q,"y"),number(q,"z"),number(q,"w"),
                0d,0d,0d,0d,0d,0d,0d,0d,0d,0L,clock);
    }
    private Object captureNativeBody(Object body) throws Exception {
        Class<?> pipelineType=type("dev.ryanhcode.sable.api.physics.PhysicsPipeline");
        Object pipeline=Proxy.newProxyInstance(loader,new Class<?>[]{pipelineType},(proxy,method,args)->{
            if(method.getName().equals("getLinearVelocity") || method.getName().equals("getAngularVelocity")) return call(args[1],"zero");
            throw new AssertionError("Unexpected native physics fixture call: "+method.getName());
        });
        Object system=PassiveProviderFixture.allocate(type("dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem"));
        PassiveProviderFixture.put(system,"pipeline",pipeline);
        Object handle=make("dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle",body,system);
        return callStatic(type("ace.flight.compat.weld.FlapAirflowCapture"),"capture",body,body,handle);
    }

    private TrimEnvironmentFrame.State stateAt(Object model,Object pose,Object context,double clock) throws Exception {
        Object q=call(pose,"orientation");Object snapshot=new Object();
        return new TrimEnvironmentFrame.State(snapshot,model,BODY_ID,BODY_ID,clock,clock,number(q,"x"),number(q,"y"),number(q,"z"),number(q,"w"),
                1,1,1,snapshot,true,true,true,true,context);
    }
    private Object passiveSupplement(NativeFixedWingTrimEnvironment.ReflectionAccess access,Object original,Object model,Object pose,
                                     Object before,Object after) throws Exception {
        return access.supplement(original,model,List.of(),stateAt(model,pose,after,.025),stateAt(model,pose,before,0));
    }
    private void passiveReject(NativeFixedWingTrimEnvironment.ReflectionAccess access,Object original,Object model,Object pose,
                               Object before,Object after,String reason) throws Exception {
        check(passiveSupplement(access,original,model,pose,before,after)==original && access.reason().equals(reason),
                "passive fail-closed reason "+reason+"; actual="+access.reason());
    }

    private Object passivePredictor(Object body, List<Object> contexts, double dt) throws Exception {
        return passivePredictor(body,contexts,dt,vector(0,0,0));
    }
    private Object passivePredictor(Object body, List<Object> contexts, double dt,Object solverCenter) throws Exception {
        var capture = type("ace.flight.compat.PassiveCreateSails").getDeclaredMethod("captureContexts", type("dev.ryanhcode.sable.sublevel.ServerSubLevel"),
                List.class, type("org.joml.Vector3d"), double.class);
        if (!capture.trySetAccessible()) throw new IllegalAccessException("native captureContexts");
        return call(capture.invoke(null, body, contexts, solverCenter, dt), "predictor");
    }

    private void contextContract() throws Exception {
        Object dimensionName = callStatic(type("net.minecraft.resources.ResourceLocation"), "parse", "minecraft:overworld");
        Object registryName = callStatic(type("net.minecraft.resources.ResourceLocation"), "parse", "minecraft:dimension");
        Object registry = callStatic(type("net.minecraft.resources.ResourceKey"), "createRegistryKey", registryName);
        Object dimension = callStatic(type("net.minecraft.resources.ResourceKey"), "create", registry, dimensionName);
        Fixture moved = capturedModel(41, 63, .4, 0);
        Object expectedOrigin = call(moved.pose(), "transformPosition", vector(0, 0, 0));
        var context = DefaultTrimEnvironment.context(new FixtureComputer(new FixtureLevel(dimension)), new FixtureBody(moved.pose()), moved.model(), loader);
        check(context.reason().isEmpty(), "production context reader accepts real nonzero translated/rotated native Pose3d");
        check(number(expectedOrigin, "y") != 0 && Double.compare(context.originY(), number(expectedOrigin, "y")) == 0,
                "production context uses actual transformPosition returned origin, including nonzero Y and rotation");
        Object original = make("ace.flight.navigation.FixedWingAxialBudget$Limits", false, 0.0, 0.0, 0.0);
        var state = state(moved).withEnvironmentContext(context);
        Object result = new NativeFixedWingTrimEnvironment.ReflectionAccess(loader).supplement(original, moved.model(), List.of(), state, state);
        check(Boolean.TRUE.equals(call(call(result, "environment"), "available")),
                "actual production context and actual default capture admit the same native pose metadata");
    }

    /** The deliberately small level/body fixtures supply geometry only; cache values are actual Sable records. */
    public static final class FixtureComputer {
        final Object level;
        FixtureComputer(Object level) { this.level = level; }
    }
    public static final class FixtureLevel {
        final Object dimension;
        final int seaLevel;
        FixtureLevel(Object dimension) { this(dimension,63); }
        FixtureLevel(Object dimension,int seaLevel) { this.dimension = dimension; this.seaLevel=seaLevel; }
        public Object dimension() { return dimension; }
        public int getSeaLevel() { return seaLevel; }
    }
    public static final class FixtureBody {
        final Object pose;
        FixtureBody(Object pose) { this.pose = pose; }
        public Object logicalPose() { return pose; }
    }
    /** Wrong runtime value used only for negative type validation, never for a positive configuration key. */
    public record WrongConfig(java.util.Optional<Double> basePressure, java.util.Optional<Object> pressureFunction) { }

    @SuppressWarnings("unchecked")
    private void ordinaryContract(boolean onlyWrongType) throws Exception {
        String s = "dev.ryanhcode.sable.physics.config.dimension_physics.";
        Class<?> data = type(s + "DimensionPhysicsData");
        java.util.Map<Object,Object> configured = (java.util.Map<Object,Object>)staticField(data, "DIMENSION_PHYSICS_DATA");
        java.util.Map<Object,Object> defaults = (java.util.Map<Object,Object>)staticField(data, "DEFAULT_DIMENSION_PHYSICS_DATA");
        var oldConfigured = new java.util.HashMap<>(configured);
        var oldDefaults = new java.util.HashMap<>(defaults);
        try {
            Object dimensionName = callStatic(type("net.minecraft.resources.ResourceLocation"), "parse", "minecraft:overworld");
            Object registryName = callStatic(type("net.minecraft.resources.ResourceLocation"), "parse", "minecraft:dimension");
            Object registry = callStatic(type("net.minecraft.resources.ResourceKey"), "createRegistryKey", registryName);
            Object dimension = callStatic(type("net.minecraft.resources.ResourceKey"), "create", registry, dimensionName);
            Fixture integrated = capturedModel(0, 63, 0, 0);
            Fixture ordinary = externalRed ? capturedModel(0, 63, 0, 0, false) : nativeOrdinaryFixture(dimension);
            Object computer = ordinary.computer() == null ? new FixtureComputer(new FixtureLevel(dimension)) : ordinary.computer();
            Object body = ordinary.body() == null ? new FixtureBody(ordinary.pose()) : ordinary.body();
            var points = new ArrayList<Object>();
            points.add(make(s + "BezierResourceFunction$BezierPoint", 0.0, 1.0, 0.0));
            points.add(make(s + "BezierResourceFunction$BezierPoint", 100.0, .5, 0.0));
            Object curve = make(s + "BezierResourceFunction", points);
            Object actualConfig = make(s + "DimensionPhysics", dimensionName, 0,
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(1.25),
                    java.util.Optional.of(curve), java.util.Optional.empty(), false);
            configured.clear(); defaults.clear();
            defaults.put(dimension, actualConfig);
            configured.put(dimension, new WrongConfig(java.util.Optional.of(1.25), java.util.Optional.of(curve)));
            var wrong = DefaultTrimEnvironment.context(computer, body, ordinary.model(), loader);
            check(!wrong.reason().isEmpty(), "RED regression: wrong non-DimensionPhysics value in actual Sable cache must be rejected");
            if (onlyWrongType) return;
            configured.clear(); defaults.clear();
            var unused = DefaultTrimEnvironment.context(computer, new FixtureBody(integrated.pose()), integrated.model(), loader);
            check(unused.reason().isEmpty() && !unused.pressureRequired() && unused.pressureKey() == null,
                    "all-integrated context needs no ordinary cache key");
            check(configured.isEmpty() && defaults.isEmpty(), "all-integrated observer never creates native default cache entries");
            var missing = DefaultTrimEnvironment.context(computer, body, ordinary.model(), loader);
            check(!missing.reason().isEmpty(), "ordinary context rejects missing actual Sable cache");
            check(configured.isEmpty() && defaults.isEmpty(), "missing-cache observer does not invoke lazy of/getDefault");
            Object original = make("ace.flight.navigation.FixedWingAxialBudget$Limits", false, 3.25, 2.5, 1.2);
            var access = new NativeFixedWingTrimEnvironment.ReflectionAccess(loader);
            var missingState = state(ordinary.model()).withEnvironmentContext(missing);
            check(access.supplement(original, ordinary.model(), List.of(), missingState, missingState) == original,
                    "missing native pressure cache retains original limits and UNKNOWN environment");
            defaults.put(dimension, actualConfig);
            var before = DefaultTrimEnvironment.context(computer, body, ordinary.model(), loader);
            check(before.reason().isEmpty() && before.pressureRequired() && before.pressureKey() != null,
                    "ordinary context reads actual existing native DimensionPhysics and Bezier points");
            var beforeState = state(ordinary.model()).withEnvironmentContext(before);
            int pressureCalls = ordinaryPressureCalls;
            Object first = access.supplement(original, ordinary.model(), List.of(), beforeState, beforeState);
            if (externalRed) {
                check(first==original,"RED external regression: arbitrary ordinary pressure callback must remain UNKNOWN; actual="+access.reason());
                return;
            }
            check(Boolean.TRUE.equals(call(call(first, "environment"), "available")), "ordinary native cached configuration supplies only environment");
            check(!Boolean.TRUE.equals(call(first, "available")), "ordinary metadata leaves axial unavailable");
            Object source=field(ordinary.model(),"sourceEnvironment");Object genuine=field(source,"val$external");
            PassiveProviderFixture.put(source,"val$external",pressureSampler());
            check(access.supplement(original,ordinary.model(),List.of(),beforeState,beforeState)==original
                    && access.reason().equals("default_external_pressure_unproven"),"arbitrary ordinary pressure callback rejects native default label");
            PassiveProviderFixture.put(source,"val$external",null);
            check(access.supplement(original,ordinary.model(),List.of(),beforeState,beforeState)==original
                    && access.reason().equals("default_external_pressure_unproven"),"null ordinary callback has no native pressure fallback and fails closed");
            PassiveProviderFixture.put(source,"val$external",genuine);
            Object otherBody=PassiveProviderFixture.body(loader,ordinary.pose());PassiveProviderFixture.put(otherBody,"level",call(body,"getLevel"));
            var wrongBodyContext=DefaultTrimEnvironment.context(computer,otherBody,ordinary.model(),loader);
            var wrongBodyState=state(ordinary.model()).withEnvironmentContext(wrongBodyContext);
            check(access.supplement(original,ordinary.model(),List.of(),wrongBodyState,wrongBodyState)==original
                    && access.reason().equals("default_external_pressure_unproven"),"native ordinary lambda captured body must be the current context body");
            Object otherComputer=PassiveProviderFixture.nativeComputer(loader,call(body,"getLevel"));
            var wrongComputerContext=DefaultTrimEnvironment.context(otherComputer,body,ordinary.model(),loader);
            var wrongComputerState=state(ordinary.model()).withEnvironmentContext(wrongComputerContext);
            check(access.supplement(original,ordinary.model(),List.of(),wrongComputerState,wrongComputerState)==original
                    && access.reason().equals("default_external_pressure_unproven"),"native ordinary lambda captured computer must be the current context computer");
            check(ordinaryPressureCalls == pressureCalls, "ordinary observer reads configuration without evaluating getPressure or force");
            var equalContext = DefaultTrimEnvironment.context(computer, body, ordinary.model(), loader);
            check(before.pressureKey().equals(equalContext.pressureKey()), "ordinary key is stable while actual cached values are unchanged");
            points.add(make(s + "BezierResourceFunction$BezierPoint", 200.0, .25, 0.0));
            var after = DefaultTrimEnvironment.context(computer, body, ordinary.model(), loader);
            check(!before.pressureKey().equals(after.pressureKey()), "in-place mutation of native curve creates a different copied configuration key");
            Object changed = access.supplement(original, ordinary.model(), List.of(), state(ordinary.model()).withEnvironmentContext(after), beforeState);
            check(changed == original, "entry/post ordinary curve change fails closed");
            var restoredPoints = new ArrayList<Object>(points.subList(0, 2));
            Object restoredCurve = make(s + "BezierResourceFunction", restoredPoints);
            defaults.put(dimension, make(s + "DimensionPhysics", dimensionName, 0,
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(1.5),
                    java.util.Optional.of(restoredCurve), java.util.Optional.empty(), false));
            var baseChanged = DefaultTrimEnvironment.context(computer, body, ordinary.model(), loader);
            check(!before.pressureKey().equals(baseChanged.pressureKey()), "actual native basePressure changes the ordinary key");
            defaults.put(dimension, new WrongConfig(java.util.Optional.of(1.25), java.util.Optional.of(restoredCurve)));
            configured.put(dimension, actualConfig);
            check(!DefaultTrimEnvironment.context(computer, body, ordinary.model(), loader).reason().isEmpty(),
                    "wrong runtime fallback value is rejected even with actual configured record");
            configured.clear();
            var oversizedPoints = new ArrayList<Object>();
            for (int i = 0; i < 4097; i++) oversizedPoints.add(make(s + "BezierResourceFunction$BezierPoint", (double)i, 1.0, 0.0));
            Object oversizedCurve = make(s + "BezierResourceFunction", oversizedPoints);
            defaults.put(dimension, make(s + "DimensionPhysics", dimensionName, 0,
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(1.25),
                    java.util.Optional.of(oversizedCurve), java.util.Optional.empty(), false));
            check(!DefaultTrimEnvironment.context(computer, body, ordinary.model(), loader).reason().isEmpty(),
                    "actual native curve with more than 4096 points is rejected within observer bounds");
        } finally {
            configured.clear(); configured.putAll(oldConfigured);
            defaults.clear(); defaults.putAll(oldDefaults);
        }
    }

    private void contract() throws Exception {
        Fixture first = capturedModel(0, 63, 0, 0);
        Object original = make("ace.flight.navigation.FixedWingAxialBudget$Limits", false, 3.25, 2.5, 1.2);
        var access = new NativeFixedWingTrimEnvironment.ReflectionAccess(loader);
        Object axis = make(P + "FixedWingVelocityResponse$Axis", true, 2.0, .5);
        Object budget = make(P + "FixedWingVelocityResponse$Budget", axis, axis, axis);
        List<Object> budgets = List.of(budget);
        Object result = access.supplement(original, first.model(), budgets, state(first));
        Object environment = call(result, "environment");
        check(Boolean.TRUE.equals(call(environment, "available")),
                "RED regression: actual default capture must supply observed native environment without WindTunnel");
        check(!Boolean.TRUE.equals(call(result, "available")), "environment does not certify axial authority");
        check(call(result, "acceleration").equals(3.25) && call(result, "braking").equals(2.5)
                && call(result, "responseSeconds").equals(1.2), "native axial budgets remain unchanged");
        check(number(environment, "windX") == 0 && number(environment, "windY") == 0 && number(environment, "windZ") == 0,
                "default native relativeVelocity observation is calm air");
        Fixture moved = capturedModel(41, 63, .4, 0);
        Object second = access.supplement(original, moved.model(), budgets, state(moved));
        check(call(environment, "settingsKey").equals(call(call(second, "environment"), "settingsKey")),
                "default key is stable across capture objects and changing pose/altitude");
        Fixture raisedSea = capturedModel(41, 64, .4, 0);
        Object otherSeaLevel = access.supplement(original, raisedSea.model(), budgets, state(raisedSea));
        check(!call(environment, "settingsKey").equals(call(call(otherSeaLevel, "environment"), "settingsKey")),
                "captured sea level changes the density configuration key");
        Object changedAxis = make(P + "FixedWingVelocityResponse$Axis", true, 3.0, .5);
        Object changedBudget = make(P + "FixedWingVelocityResponse$Budget", changedAxis, axis, axis);
        Object changedConfiguration = access.supplement(original, first.model(), List.of(changedBudget), state(first));
        check(!call(environment, "settingsKey").equals(call(call(changedConfiguration, "environment"), "settingsKey")),
                "actual native velocity-response configuration remains part of default environment key");
        check(ordinaryPressureCalls == 0, "all-integrated metadata does not sample unused ordinary pressure");
        Object firstSampler=field(first.model(),"sourceEnvironment");Object oldX=field(firstSampler,"val$x");
        PassiveProviderFixture.put(firstSampler,"val$x",.125);
        check(access.supplement(original,first.model(),budgets,state(first))==original && access.reason().equals("default_altitude_basis_unproven"),
                "captured altitude basis from a different orientation/scale is rejected even at the same origin height");
        PassiveProviderFixture.put(firstSampler,"val$x",oldX);
        Object unusedExternal=field(firstSampler,"val$external");PassiveProviderFixture.put(firstSampler,"val$external",null);
        check(access.supplement(original,first.model(),budgets,state(first))!=original,"all-integrated path ignores unused null ordinary callback");
        PassiveProviderFixture.put(firstSampler,"val$external",unusedExternal);
        check(access.supplement(original, first.model(), budgets, state(first.model())) == original,
                "missing actual-body environment context fails closed");
        check(access.supplement(original, first.model(), budgets, state(first, 1, 63)) == original,
                "source altitude inconsistent with actual pose origin fails closed");
        check(access.supplement(original, first.model(), budgets, state(first, 0, 64)) == original,
                "source sea level inconsistent with actual context fails closed");
        Fixture offset = capturedModel(0, 63, 0, 1);
        check(access.supplement(original, offset.model(), budgets, state(offset)) == original,
                "actual four-argument capture with nonzero heightOffset is not admitted as supported default capture");
        Object unknown = model(pressureSampler());
        check(access.supplement(original, unknown, budgets, state(unknown, first, 0, 63)) == original,
                "unknown sampler is rejected even though its interface default relativeVelocity is zero");
        Object settings = make(P + "SailAerodynamicEnvironment$Settings", false, false, 1.0, 1.0, 1.0);
        Object windEnvironment = make(P + "SailAerodynamicEnvironment", settings, pressureSampler(),
                (java.util.function.Function<Object,Object>)(point -> {
                    try { return vector(1, 2, 3); } catch (Exception failure) { throw new IllegalStateException(failure); }
                }));
        Object windModel = model(windEnvironment);
        var windState = new TrimEnvironmentFrame.State(new Object(), windModel, "body", "body", 1, 1,
                0, Math.sqrt(.5), 0, Math.sqrt(.5), 1, 1, 1, new Object(), true, true, true, true);
        Object retainedWind = call(access.supplement(original, windModel, budgets, windState), "environment");
        check(Boolean.TRUE.equals(call(retainedWind, "available")) && Math.abs(number(retainedWind, "windX") - 3) < 1e-12
                && Math.abs(number(retainedWind, "windY") - 2) < 1e-12 && Math.abs(number(retainedWind, "windZ") + 1) < 1e-12,
                "existing native SailAerodynamicEnvironment keeps nonzero quaternion-rotated wind");
    }

    private Fixture nativeOrdinaryFixture(Object dimension) throws Exception {
        Fixture fixture=capturedModel(0,63,0,0,false);
        Object body=PassiveProviderFixture.body(loader,fixture.pose());
        Object level=PassiveProviderFixture.level(loader,dimension);
        PassiveProviderFixture.put(body,"level",level);
        Object computer=PassiveProviderFixture.nativeComputer(loader,level);
        Object nativeCapture=PassiveProviderFixture.defaultEnvironment(loader,computer,body);
        // Install genuine factory callback as metadata after ordinary NONE fixture construction; no native callback is evaluated.
        PassiveProviderFixture.put(field(fixture.model(),"sourceEnvironment"),"val$external",field(nativeCapture,"val$external"));
        return new Fixture(fixture.model(),fixture.pose(),63,computer,body);
    }

    private Fixture capturedModel(double altitude, double seaLevel, double roll, double heightOffset) throws Exception {
        return capturedModel(altitude, seaLevel, roll, heightOffset, true);
    }
    private Fixture capturedModel(double altitude, double seaLevel, double roll, double heightOffset, boolean integrated) throws Exception {
        Object pose = make("dev.ryanhcode.sable.companion.math.Pose3d", vector(0, altitude, 0),
                make("org.joml.Quaterniond", 0.0, 0.0, Math.sin(roll/2), Math.cos(roll/2)), vector(0, 0, 0), vector(1, 1, 1));
        Class<?> sampler = type(P + "SymmetricSailFlapAllocation$PressureSampler");
        Class<?> poseType = type("dev.ryanhcode.sable.companion.math.Pose3dc");
        Object captured = heightOffset == 0
                ? type(P + "IntegratedFlapEnvironment").getMethod("capture", sampler, poseType, double.class)
                    .invoke(null, pressureSampler(), pose, seaLevel)
                : type(P + "IntegratedFlapEnvironment").getMethod("capture", sampler, poseType, double.class, double.class)
                    .invoke(null, pressureSampler(), pose, seaLevel, heightOffset);
        check(captured.getClass().getName().equals(P + "IntegratedFlapEnvironment$1"), "actual capture returns native default $1");
        Object observed = call(captured, "relativeVelocity", vector(9, 2, -4), vector(0, 0, 0));
        check(number(observed, "x") == 0 && number(observed, "y") == 0 && number(observed, "z") == 0,
                "actual inherited native relativeVelocity returns zero for zero input");
        return new Fixture(model(captured, integrated), pose, seaLevel);
    }

    private Object pressureSampler() throws Exception {
        return Proxy.newProxyInstance(loader, new Class<?>[]{type(P + "SymmetricSailFlapAllocation$PressureSampler")}, (proxy, method, args) -> {
            if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
            if (method.getName().equals("getPressure")) { ordinaryPressureCalls++; return 1.0; }
            throw new AssertionError("Unexpected external-pressure fixture call: " + method.getName());
        });
    }

    private Object model(Object environment) throws Exception {
        return model(environment, true);
    }
    private Object model(Object environment, boolean integratedSurface) throws Exception {
        return model(environment, integratedSurface, null);
    }
    private Object model(Object environment, boolean integratedSurface, Object passivePredictor) throws Exception {
        return model(environment,integratedSurface,passivePredictor,null,null);
    }
    private Object model(Object environment, boolean integratedSurface, Object passivePredictor,Object nativeBody,Object solverCenter) throws Exception {
        // A nonempty real integrated Surface/Effector exercises the branch used by integrated flaps.
        Object integrated = integratedSurface ? make(P + "IntegratedFlapAerodynamics", 1) : null;
        Object surface = null;
        for (Constructor<?> ctor : type(P + "SymmetricSailFlapAllocation$Surface").getConstructors()) {
            if (ctor.getParameterCount() == 8) {
                surface = ctor.newInstance(vector(0, 0, 0), vector(0, 1, 0), 1.75, .068882026,
                        "ace.flight.block.IntegratedSailBlock", null, integrated, null);
                break;
            }
        }
        if (surface == null) throw new AssertionError("Exact native integrated Surface constructor unavailable");
        Object effector = make(P + "SymmetricSailFlapAllocation$Effector", 0.0, -45.0, 45.0,
                vector(0, 0, 1), vector(0, 0, 0), List.of(surface));
        Object body = nativeBody == null ? make(P + "NativeBodyFlapModel$Body", BODY_ID.toString(), vector(-25, 0, 0), vector(0, 0, 0),
                vector(0, 0, 0), vector(0, 0, 0), make("org.joml.Matrix3d"), 95.0) : nativeBody;
        Object result = make(P + "NativeBodyFlapModel", List.of(effector), List.of(body), new int[]{0}, new int[0],
                solverCenter == null ? vector(0,0,0) : solverCenter, .025, new double[0][3], new boolean[0], new boolean[0], environment);
        // Native construction precomputes force. Install captured passive metadata only after NONE construction.
        if (passivePredictor != null) {
            Object group = ((List<?>)field(result, "groups")).getFirst();
            Object groupedModel = field(group, "model");
            var passive = groupedModel.getClass().getDeclaredField("passiveSails");
            if (!passive.trySetAccessible()) throw new IllegalAccessException("passive fixture metadata");
            passive.set(groupedModel, passivePredictor);
        }
        return result;
    }

    private TrimEnvironmentFrame.State state(Object model) {
        return new TrimEnvironmentFrame.State(new Object(), model, "body", "body", 1, 1,
                0, 0, 0, 1, 1, 1, 1, new Object(), true, true, true, true);
    }
    private TrimEnvironmentFrame.State state(Fixture fixture) throws Exception {
        Object origin = call(fixture.pose(), "transformPosition", vector(0, 0, 0));
        return state(fixture, number(origin, "y"), fixture.seaLevel());
    }
    private TrimEnvironmentFrame.State state(Fixture fixture, double originY, double seaLevel) throws Exception {
        return state(fixture.model(), fixture, originY, seaLevel);
    }
    private TrimEnvironmentFrame.State state(Object model, Fixture fixture, double originY, double seaLevel) throws Exception {
        Object dimensionName = callStatic(type("net.minecraft.resources.ResourceLocation"), "parse", "minecraft:overworld");
        Object registryName = callStatic(type("net.minecraft.resources.ResourceLocation"), "parse", "minecraft:dimension");
        Object registry = callStatic(type("net.minecraft.resources.ResourceKey"), "createRegistryKey", registryName);
        Object dimension = callStatic(type("net.minecraft.resources.ResourceKey"), "create", registry, dimensionName);
        Object computer = fixture.computer() == null ? new FixtureComputer(new FixtureLevel(dimension,(int)fixture.seaLevel())) : fixture.computer();
        Object body = fixture.body() == null ? new FixtureBody(fixture.pose()) : fixture.body();
        Object context = DefaultTrimEnvironment.context(computer,body,model,loader);
        Object actualOrigin = call(fixture.pose(),"transformPosition",vector(0,0,0));
        if (Double.compare(originY,number(actualOrigin,"y"))!=0 || Double.compare(seaLevel,fixture.seaLevel())!=0)
            context=new DefaultTrimEnvironment.Context("minecraft:overworld",seaLevel,originY,false,null,"");
        Object orientation = call(fixture.pose(), "orientation");
        var state = new TrimEnvironmentFrame.State(new Object(), model, "body", "body", 1, 1,
                number(orientation,"x"), number(orientation,"y"), number(orientation,"z"), number(orientation,"w"),
                1, 1, 1, new Object(), true, true, true, true);
        Method withContext = state.getClass().getDeclaredMethod("withEnvironmentContext", Object.class);
        if (!withContext.trySetAccessible()) throw new IllegalAccessException("withEnvironmentContext");
        return (TrimEnvironmentFrame.State)withContext.invoke(state, context);
    }
    private Object vector(double x, double y, double z) throws Exception { return make("org.joml.Vector3d", x, y, z); }
    private Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }
    private Object make(String name, Object... args) throws Exception {
        for (Constructor<?> ctor : type(name).getConstructors()) if (matches(ctor.getParameterTypes(), args)) return ctor.newInstance(args);
        throw new NoSuchMethodException(name + " constructor with " + args.length + " arguments");
    }
    private static Object call(Object owner, String name, Object... args) throws Exception {
        return invoke(owner.getClass(), owner, name, args);
    }
    private static Object callStatic(Class<?> type, String name, Object... args) throws Exception {
        return invoke(type, null, name, args);
    }
    private static Object field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name);
        if (!f.trySetAccessible()) throw new IllegalAccessException(name);
        return f.get(owner);
    }
    private static Object staticField(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException(name);
        return field.get(null);
    }
    private static Object invoke(Class<?> type, Object owner, String name, Object... args) throws Exception {
        for (Method method : type.getMethods()) if (method.getName().equals(name) && matches(method.getParameterTypes(), args)) {
            if (!method.canAccess(owner) && !method.trySetAccessible()) throw new IllegalAccessException(name);
            return method.invoke(owner, args);
        }
        throw new NoSuchMethodException(name);
    }
    private static boolean matches(Class<?>[] parameters, Object[] values) {
        if (parameters.length != values.length) return false;
        for (int i = 0; i < values.length; i++) {
            Class<?> p = parameters[i], boxed = p == int.class ? Integer.class : p == double.class ? Double.class : p == boolean.class ? Boolean.class : p == long.class ? Long.class : p;
            if (values[i] == null ? p.isPrimitive() : !boxed.isInstance(values[i])) return false;
        }
        return true;
    }
    private static double number(Object owner, String name) throws Exception { return ((Number)call(owner, name)).doubleValue(); }
    private void check(boolean value, String description) { if (!value) throw new AssertionError(description); checks++; System.out.println("PASS " + description); }
}
