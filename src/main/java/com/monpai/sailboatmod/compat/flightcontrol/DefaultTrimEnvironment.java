package com.monpai.sailboatmod.compat.flightcontrol;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Observation of the exact default native air model; never calls pressure, force or solver getters. */
final class DefaultTrimEnvironment {
    private static final String PHYSICS = "ace.flight.physics.";
    private static final String DEFAULT = PHYSICS + "IntegratedFlapEnvironment$1";
    private static final String SAMPLER = PHYSICS + "SymmetricSailFlapAllocation$PressureSampler";
    private static final String SABLE = "dev.ryanhcode.sable.physics.config.dimension_physics.";
    private static final int MAX_OBSERVATIONS = 4096;
    private static final String CACHED = PHYSICS + "CachedPassiveSailPredictor";
    private static final String PASSIVE = "ace.flight.compat.PassiveCreateSails$1";
    private static final String PROVIDER = "dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider";
    private static final String CREATE = "com.simibubi.create.content.contraptions.bearing.SailBlock";
    private static final String SYMMETRIC = "dev.simulated_team.simulated.content.blocks.symmetric_sail.SymmetricSailBlock";
    private static final Map<String, String> PASSIVE_CLASSES = Map.of(
            CACHED, "20ee7c045f195d807f85ffc9c4aa0ae057f25e39a0bd9707008e6bd5eb90a8d3",
            "ace.flight.compat.PassiveCreateSails", "d6993e6d4bcf3de71ab638e681ca6a1fd6af2f1bdee36dfc908fa964c27c1bc5",
            PASSIVE, "47ccfcdd42d2e5a745763cdf29f3d79b4481531ce6d311acb21065edfc5d98e0",
            PROVIDER, "d4999d980820618b9d1471b2edae999af4604b83147b63249b5660aa58839f67",
            PROVIDER + "$LiftProviderContext", "8055d00c01d20bc5b1ec1e46a08fa2c377b9b16adab0af4d30ea385e3877be5e");
    private static final Map<String, String> BODY_CLASSES = Map.of(
            "dev.ryanhcode.sable.sublevel.ServerSubLevel", "033eff21106f748c94b4dc51426a450398392a2e896a9ba7095a7ce4270b9119",
            "dev.ryanhcode.sable.sublevel.SubLevel", "160aa58d14ae277470fc6b6afe85a6fc2a57899f97a2e37d9433d8522bbdf677",
            "dev.ryanhcode.sable.api.physics.mass.MergedMassTracker", "b1dc905689fb320921cbe6e38c74eaa6302d4084d3737d1e61739d5066f94fca",
            "dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot", "974465a85e34c975abb64a2aef842d759283b7dc027407a41edf12f33b3b6643",
            "dev.ryanhcode.sable.companion.math.Pose3d", "24e131357c78f42798579afdc73f76803c3ee3e2d7dce058fb0d5f7085db7ebe",
            "dev.ryanhcode.sable.companion.math.Pose3dc", "2a3f95cd68b483400ac95f7175ec7116ed7b9eb3f3550a216ec11254d45a86db");
    private static final Map<String, String> CREATE_CLASSES = Map.of(
            CREATE, "358888e3c0faac364b8df291696dacb1d8ef842a8cc16f0ba7e0b811d9dfaebc",
            "dev.ryanhcode.sable.neoforge.mixin.compatibility.create.sails_providing_lift.SailBlockMixin",
            "e8cfdbd44fa300410fccf64d18f2c5a74eabd0162fa241049800670aff4562d7");
    private static final Map<String, String> SYMMETRIC_CLASSES = Map.of(
            SYMMETRIC, "d8022b1af14b54bdc12fd38e61df81cb2ab61db1aa4f18ef19eda584cc2803d2");
    private static final Map<String, String> NATIVE_CLASSES = Map.ofEntries(
            Map.entry("ace.flight.compat.weld.FlapAirflowCapture", "d7f65742877f77b59916b037d77813be285b8889823bcb66e60c2b8eb08cccda"),
            Map.entry(PHYSICS + "AttitudeTargetUtil", "733c395fc4ed016e1dd08c7d5443439fc47e149b992e956aa51659ac4241b313"),
            Map.entry("ace.flight.navigation.NavigationVehicleStateSnapshot", "74c612e077867a8dc366adfa191a998bf44a21369f95254fc0a2b3d93affbf47"),
            Map.entry(TrimEnvironmentCompatibility.COMPUTER, "44487adde64fd7fa88b091fad99be96cfc0e26ec6d179b24334e3d186d9f5cd1"),
            Map.entry(PHYSICS + "IntegratedFlapEnvironment", "d65ef84d2b30e09e99ee4430f7deaad981e4ae635a9c111f79a33697c5d7af51"),
            Map.entry(DEFAULT, "4144b4273dc04242a98ad98783fd22a346cd4e0ca80bfe2ceede1e78412b8cfa"),
            Map.entry(SAMPLER, "834bc826380b27a5f89786c6f6af5761f24524bb2e0e271a346944cc18ca4212"),
            Map.entry(PHYSICS + "SymmetricSailFlapAllocation$Surface", "71550594bfe1c29d0f24942f2980c636027eedc709fd733ad4daecb551b33cb6"),
            Map.entry(PHYSICS + "SymmetricSailFlapAllocation$Effector", "f0cb2f810d63fd912f4c5ca6d60f7714f9da30c02f02067c8be90f5a418d5bdc"),
            Map.entry(PHYSICS + "IntegratedFlapAerodynamics", "ac02e6443d48a44e4cda2c70b81945c039e40f4aaf2b780b3d21e7a89ca420fc"),
            Map.entry(PHYSICS + "IntegratedSailAerodynamics", "db2327e540a9febff5e0d3695513be450596f0d84f6356e56eceec4f1f4caa54"),
            Map.entry(PHYSICS + "NativeBodyFlapModel", "1cd3bdfebc31e56b8066453c6b31eeb1a9b0cd9ce0d409a18f1ae1d22712ac11"),
            Map.entry(PHYSICS + "NativeBodyFlapModel$1", "85d78090a01d8bc32a460a0a6def7c274dbe0f1610f44b93b08c874988772f41"),
            Map.entry(PHYSICS + "NativeBodyFlapModel$Body", "1f3e5ea88717e4f14c9a77ebcb4b59af648a9d2aa3e327dc013379ba574dff96"),
            Map.entry(PHYSICS + "NativeBodyFlapModel$Group", "1384706de691cce10881b6b5d4117338515d493629b69985906aa1d7eb7de60c"),
            Map.entry(PHYSICS + "PostEngineFlapModel", "bad1845f093fe3816780d51081e1a312472acffd5bba5727de1e55f42e7b9fa0"),
            Map.entry(PHYSICS + "PassiveSailPredictor", "767f45a1adcaddf3981116325556f109ae13bf6e380ba1daac6d40e64b11e3b7"));
    private static final Map<String, String> PRESSURE_CLASSES = Map.of(
            SABLE + "DimensionPhysicsData", "5a5e89b2c7128681152f944405e89171c67606977b1c8abb17ad0c84b884ccde",
            SABLE + "DimensionPhysics", "bf4dda7210fa380dfa7747acc6125ed42703031dbd2f46cbb6e36efeef3d4ae5",
            SABLE + "BezierResourceFunction", "91177d26702f724ab0362b58da2600c83ab15fc02c337bea2954135956e34d56",
            SABLE + "BezierResourceFunction$BezierPoint", "cc45ef6890a130eb6d28c87053f8b7f7f9e336ac7f52738fa85ccb18170aefcf");
    private static final ClassValue<Boolean> ABI = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                ClassLoader loader = type.getClassLoader();
                if (!matches(loader, NATIVE_CLASSES)) return false;
                Class<?> sampler = Class.forName(SAMPLER, false, loader);
                Class<?> vector = Class.forName("org.joml.Vector3d", false, loader);
                Method relative = Class.forName(DEFAULT, false, loader).getMethod("relativeVelocity", vector, vector);
                return relative.isDefault() && relative.getDeclaringClass() == sampler && relative.getReturnType() == vector;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
        }
    };
    private static final ClassValue<Boolean> PRESSURE_ABI = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) { return matches(type.getClassLoader(), PRESSURE_CLASSES); }
    };
    private static final ClassValue<Boolean> PASSIVE_ABI = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) { return matches(type.getClassLoader(), PASSIVE_CLASSES) && matches(type.getClassLoader(), BODY_CLASSES); }
    };
    private static final ClassValue<String> PROVIDER_PROOF = new ClassValue<>() {
        @Override protected String computeValue(Class<?> type) {
            try {
                boolean create = type.getName().equals(CREATE);
                if (!matches(type.getClassLoader(), create ? CREATE_CLASSES : SYMMETRIC_CLASSES))
                    return "default_passive_provider_abi_unproven";
                providerMethods(type, create, type.getClassLoader());
                return "";
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return "default_passive_provider_override";
            }
        }
    };
    private static final ClassValue<Boolean> EXTERNAL_ABI = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return matches(type.getClassLoader(), BODY_CLASSES) && matches(type.getClassLoader(), Map.of(
                    "ace.flight.physics.AttitudeTargetUtil", "733c395fc4ed016e1dd08c7d5443439fc47e149b992e956aa51659ac4241b313"));
        }
    };
    record Context(String dimension, double seaLevel, double originY, boolean pressureRequired, Object pressureKey,
                   String reason, BodyFrame bodyFrame, ModelEvidence modelEvidence, List<PassiveSurface> liveGeometry,
                   Object computer, Object body, Object level, Vector altitudeBasis) {
        Context(String dimension, double seaLevel, double originY, boolean pressureRequired, Object pressureKey, String reason) {
            this(dimension, seaLevel, originY, pressureRequired, pressureKey, reason, null, null, List.of(), null, null, null, null);
        }
        Context { liveGeometry = List.copyOf(liveGeometry); }
    }
    record Vector(double x, double y, double z) { }
    record Quaternion(double x, double y, double z, double w) { }
    record Pose(Vector position, Quaternion orientation, Vector rotationPoint, Vector scale) { }
    record BodyFrame(Object body, java.util.UUID id, Pose pose, Vector center, Vector nativeCenter, Vector nativePivot) { }
    record PassiveSurface(int x, int y, int z, String provider, String property, String value, Vector direction) { }
    record PassiveGeometry(List<PassiveSurface> surfaces, Vector scale, double dt) {
        PassiveGeometry { surfaces = List.copyOf(surfaces); }
    }
    record PassiveGroup(Object body, Quaternion orientation, Vector offset, double dt, double modelDt,
                        Vector nativeCenter, Vector solverCenter, Vector rotationPoint, List<PassiveSurface> surfaces) {
        PassiveGroup { surfaces = List.copyOf(surfaces); }
    }
    record ModelEvidence(boolean pressure, boolean ordinary, List<PassiveGroup> passive, String bodyId) {
        ModelEvidence { passive = List.copyOf(passive); }
    }
    record Point(double altitude, double value, double slope) { }
    record Pressure(double basePressure, List<Point> points) {
        Pressure { points = List.copyOf(points); }
    }
    record Key(String model, String dimension, double seaLevel, double heightOffset, Object pressure, List<PassiveGeometry> passive) { }
    record Observation(Object wind, Object key) { }
    static final class Rejected extends ReflectiveOperationException {
        Rejected(String reason) { super(reason); }
    }
    private DefaultTrimEnvironment() { }

    static Context context(Object computer, Object body, Object model, ClassLoader loader) {
        try {
            Object level = inheritedField(computer, "level");
            Object dimension = call(level, "dimension");
            String dimensionName = String.valueOf(call(dimension, "location"));
            double seaLevel = number(call(level, "getSeaLevel"));
            ModelEvidence evidence = model == null ? null : inspect(model, loader);
            if (evidence != null && !evidence.passive().isEmpty()
                    && (body == null || body.getClass() != Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel", false, loader)))
                throw new Rejected("default_passive_body_unproven");
            Object pose = call(body, "logicalPose");
            Object origin = call(pose, "transformPosition", vector(loader));
            double originY = number(call(origin, "y"));
            if (dimensionName.isBlank() || !Double.isFinite(seaLevel) || !Double.isFinite(originY))
                throw new Rejected("default_pose_unavailable");
            boolean pressure = evidence != null && evidence.pressure();
            BodyFrame frame = null;
            List<PassiveSurface> live = List.of();
            if (evidence != null && !evidence.passive().isEmpty()) {
                if (body.getClass() != Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel", false, loader)
                        || pose.getClass() != Class.forName("dev.ryanhcode.sable.companion.math.Pose3d", false, loader))
                    throw new Rejected("default_passive_body_unproven");
                if (call(body, "getLevel") != level) throw new Rejected("default_passive_level_unproven");
                Object mass = call(body, "getMassTracker");
                if (mass == null || mass.getClass() != Class.forName("dev.ryanhcode.sable.api.physics.mass.MergedMassTracker", false, loader))
                    throw new Rejected("default_passive_mass_unproven");
                Object id = call(body, "getUniqueId");
                if (!(id instanceof java.util.UUID bodyId) || !bodyId.toString().equals(evidence.bodyId()))
                    throw new Rejected("default_passive_ownership_unproven");
                Vector center = vectorValue(call(mass, "getCenterOfMass"));
                Pose copy = pose(pose);
                // FlapAirflowCapture performs this root-to-root roundtrip even for the same body.
                frame = new BodyFrame(body, bodyId, copy, center, nativePoint(pose, center, loader),
                        nativePoint(pose, copy.rotationPoint(), loader));
                live = liveGeometry(body, loader);
            }
            // Passive native providers use ordinary pressure even when every controlled surface is integrated.
            Object key = pressure ? pressure(loader, dimension) : null;
            Vector basis = new Vector(altitude(pose, loader, 1, 0, 0) - originY,
                    altitude(pose, loader, 0, 1, 0) - originY, altitude(pose, loader, 0, 0, 1) - originY);
            if (!Double.isFinite(basis.x()) || !Double.isFinite(basis.y()) || !Double.isFinite(basis.z()))
                throw new Rejected("default_pose_unavailable");
            return new Context(dimensionName, seaLevel, originY, pressure, key, "", frame, evidence, live, computer, body, level, basis);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return new Context("", Double.NaN, Double.NaN, false, null,
                    unavailable instanceof Rejected ? unavailable.getMessage() : "default_context_unavailable");
        }
    }

    static Observation observe(Object model, TrimEnvironmentFrame.State beforeState, TrimEnvironmentFrame.State state, ClassLoader loader)
            throws ReflectiveOperationException {
        Object beforeValue = beforeState.environmentContext(), afterValue = state.environmentContext();
        if (!ABI.get(model.getClass())) throw new Rejected("default_abi_unproven");
        Object sampler = field(model, "sourceEnvironment");
        if (sampler == null || sampler.getClass() != Class.forName(DEFAULT, false, loader))
            throw new Rejected("default_sampler_unproven");
        if (!(beforeValue instanceof Context before) || !(afterValue instanceof Context after))
            throw new Rejected("default_context_missing");
        if (!before.reason().isEmpty() || !after.reason().isEmpty())
            throw new Rejected(!after.reason().isEmpty() ? after.reason() : before.reason());
        ModelEvidence evidence = inspect(model, loader);
        boolean pressure = evidence.pressure();
        if (before.dimension() == null || before.dimension().isBlank()
                || !before.dimension().equals(after.dimension())
                || Double.compare(before.seaLevel(), after.seaLevel()) != 0
                || Double.compare(before.originY(), after.originY()) != 0
                || before.pressureRequired() != pressure || after.pressureRequired() != pressure
                || pressure && (!(before.pressureKey() instanceof Pressure) || !Objects.equals(before.pressureKey(), after.pressureKey())))
            throw new Rejected("default_configuration_changed");
        double seaLevel = number(field(sampler, "val$seaLevel"));
        double altitude = number(field(sampler, "val$altitude"));
        // The pinned FCC invokes the 3-arg factory (heightOffset=0). Also prove its actual captured origin.
        if (!Double.isFinite(seaLevel) || !Double.isFinite(altitude) || !Double.isFinite(after.originY())
                || Double.compare(seaLevel, after.seaLevel()) != 0 || Double.compare(altitude, after.originY()) != 0)
            throw new Rejected("default_height_offset_unproven");
        if (before.altitudeBasis() == null || !before.altitudeBasis().equals(after.altitudeBasis())
                || Double.compare(number(field(sampler, "val$x")), after.altitudeBasis().x()) != 0
                || Double.compare(number(field(sampler, "val$y")), after.altitudeBasis().y()) != 0
                || Double.compare(number(field(sampler, "val$z")), after.altitudeBasis().z()) != 0)
            throw new Rejected("default_altitude_basis_unproven");
        if (evidence.ordinary()) external(sampler, before, after, loader);
        List<PassiveGeometry> passiveKey = passive(model, evidence, before, after, beforeState, state);
        Object center = field(model, "solverCenter");
        Object wind = call(sampler, "relativeVelocity", center, vector(loader));
        call(wind, "negate");
        return new Observation(wind, new Key("native-0.8.3-default-air", after.dimension(), seaLevel, 0, after.pressureKey(), passiveKey));
    }

    private static ModelEvidence inspect(Object model, ClassLoader loader) throws ReflectiveOperationException {
        if (model.getClass() != Class.forName(PHYSICS + "NativeBodyFlapModel", false, loader) || !ABI.get(model.getClass()))
            throw new Rejected("default_model_unproven");
        Object environment = field(model, "environment");
        if (environment == null || environment.getClass() != Class.forName(PHYSICS + "NativeBodyFlapModel$1", false, loader)
                || field(environment, "this$0") != model || field(environment, "val$environment") != field(model, "sourceEnvironment"))
            throw new Rejected("default_environment_graph_unproven");
        Object none = staticField(Class.forName(PHYSICS + "PassiveSailPredictor", false, loader), "NONE");
        var passive = new ArrayList<PassiveGroup>();
        for (Object group : list(field(model, "groups"))) {
            if (group.getClass() != Class.forName(PHYSICS + "NativeBodyFlapModel$Group", false, loader))
                throw new Rejected("default_group_unproven");
            Object groupedModel = field(group, "model");
            if (groupedModel.getClass() != Class.forName(PHYSICS + "PostEngineFlapModel", false, loader))
                throw new Rejected("default_group_model_unproven");
            if (field(groupedModel, "environment") != environment) throw new Rejected("default_environment_graph_unproven");
            Object predictor = field(groupedModel, "passiveSails");
            if (predictor == none) continue;
            if (!PASSIVE_ABI.get(model.getClass())) throw new Rejected("default_passive_abi_unproven");
            if (predictor == null || predictor.getClass() != Class.forName(CACHED, false, loader))
                throw new Rejected("default_passive_predictor_unproven");
            Object nativePredictor = field(predictor, "nativePredictor");
            if (nativePredictor == null || nativePredictor.getClass() != Class.forName(PASSIVE, false, loader))
                throw new Rejected("default_passive_predictor_unproven");
            List<PassiveSurface> surfaces = geometry(list(field(nativePredictor, "val$contexts")), loader);
            if (surfaces.isEmpty()) throw new Rejected("default_passive_contexts_missing");
            passive.add(new PassiveGroup(field(nativePredictor, "val$body"), quaternion(field(nativePredictor, "val$orientation")),
                    vectorValue(field(nativePredictor, "val$offset")), number(field(nativePredictor, "val$dt")),
                    number(field(groupedModel, "dt")), vectorValue(field(groupedModel, "nativeCenter")),
                    vectorValue(field(groupedModel, "solverCenter")), vectorValue(field(groupedModel, "rotationPoint")), surfaces));
        }
        // This observed default path proves the current root body only, not attached-body transforms.
        if (!passive.isEmpty() && (list(field(model, "groups")).size() != 1 || list(field(model, "bodies")).size() != 1))
            throw new Rejected("default_passive_group_unproven");
        boolean pressure = !passive.isEmpty();
        boolean ordinary = false;
        int surfaces = 0;
        for (Object effector : list(field(model, "models"))) {
            if (effector.getClass() != Class.forName(PHYSICS + "SymmetricSailFlapAllocation$Effector", false, loader))
                throw new Rejected("default_effector_unproven");
            for (Object surface : list(call(effector, "surfaces"))) {
                if (surface.getClass() != Class.forName(PHYSICS + "SymmetricSailFlapAllocation$Surface", false, loader))
                    throw new Rejected("default_surface_unproven");
                surfaces++;
                if (surfaces > MAX_OBSERVATIONS) throw new Rejected("default_observation_limit");
                Object integrated = call(surface, "integrated");
                if (integrated != null) {
                    if (integrated.getClass() != Class.forName(PHYSICS + "IntegratedFlapAerodynamics", false, loader))
                        throw new Rejected("default_integrated_model_unproven");
                } else {
                    if (call(surface, "synaxis") != null) throw new Rejected("default_synaxis_model_unproven");
                    pressure = true;
                    ordinary = true;
                }
            }
        }
        if (surfaces == 0) throw new Rejected("default_surfaces_missing");
        return new ModelEvidence(pressure, ordinary, passive, passive.isEmpty() ? null : ownership(model, loader));
    }

    private static String ownership(Object model, ClassLoader loader) throws ReflectiveOperationException {
        List<?> bodies = list(field(model, "bodies")), groups = list(field(model, "groups")), models = list(field(model, "models"));
        if (bodies.size() != 1 || groups.size() != 1 || bodies.getFirst() == null
                || bodies.getFirst().getClass() != Class.forName(PHYSICS + "NativeBodyFlapModel$Body", false, loader))
            throw new Rejected("default_passive_ownership_unproven");
        Object id = call(bodies.getFirst(), "id");
        Object ownerValues = field(model, "flapBodies"), indexValues = field(groups.getFirst(), "indices");
        if (!(id instanceof String bodyId) || bodyId.isBlank() || !(ownerValues instanceof int[] owners)
                || !(indexValues instanceof int[] indices) || owners.length != models.size() || indices.length != models.size())
            throw new Rejected("default_passive_ownership_unproven");
        Object grouped = field(groups.getFirst(), "model");
        List<?> groupedModels = list(field(grouped, "models"));
        if (groupedModels.size() != models.size()
                || !vectorValue(call(bodies.getFirst(), "center")).equals(vectorValue(field(grouped, "nativeCenter")))
                || !vectorValue(call(bodies.getFirst(), "rotationPoint")).equals(vectorValue(field(grouped, "rotationPoint"))))
            throw new Rejected("default_passive_ownership_unproven");
        for (int i = 0; i < models.size(); i++)
            if (owners[i] != 0 || indices[i] != i || groupedModels.get(i) != models.get(i))
                throw new Rejected("default_passive_ownership_unproven");
        return bodyId;
    }

    private static List<PassiveGeometry> passive(Object model, ModelEvidence evidence, Context before, Context after,
                                                  TrimEnvironmentFrame.State beforeState, TrimEnvironmentFrame.State state)
            throws ReflectiveOperationException {
        if (evidence.passive().isEmpty()) return List.of();
        BodyFrame frame = after.bodyFrame();
        if (frame == null || before.bodyFrame() == null || before.modelEvidence() == null || after.modelEvidence() == null)
            throw new Rejected("default_passive_context_missing");
        if (before.bodyFrame().body() != frame.body() || !before.bodyFrame().id().equals(frame.id()) || !before.bodyFrame().pose().equals(frame.pose())
                || !before.bodyFrame().center().equals(frame.center()))
            throw new Rejected("default_passive_body_changed");
        if (!evidence.equals(after.modelEvidence()) || !before.liveGeometry().equals(after.liveGeometry())
                || !geometryKey(before.modelEvidence(), before.bodyFrame()).equals(geometryKey(evidence, frame)))
            throw new Rejected("default_passive_geometry_changed");
        if (!frame.id().equals(state.bodyId()) || !frame.id().equals(state.structureId())
                || !frame.id().toString().equals(evidence.bodyId())) throw new Rejected("default_passive_ownership_unproven");
        Quaternion published = new Quaternion(state.qx(), state.qy(), state.qz(), state.qw());
        if (!publishedQuaternion(frame.pose().orientation(), model.getClass().getClassLoader()).equals(published))
            throw new Rejected("default_passive_pose_unproven");
        Vector center = vectorValue(field(model, "solverCenter"));
        for (PassiveGroup group : evidence.passive()) {
            if (group.body() != frame.body()) throw new Rejected("default_passive_body_unproven");
            if (!group.orientation().equals(frame.pose().orientation())) throw new Rejected("default_passive_pose_unproven");
            if (!Double.isFinite(group.dt()) || group.dt() <= 0 || group.dt() > .25
                    || Double.compare(group.dt(), group.modelDt()) != 0
                    || Double.compare(state.clock(), beforeState.clock() + group.dt()) != 0)
                throw new Rejected("default_passive_timestep_unproven");
            if (!group.solverCenter().equals(center) || !group.nativeCenter().equals(frame.nativeCenter())
                    || !group.rotationPoint().equals(frame.nativePivot())
                    || !group.offset().equals(subtract(frame.center(), center)))
                throw new Rejected("default_passive_offset_unproven");
            if (!group.surfaces().equals(after.liveGeometry())) throw new Rejected("default_passive_geometry_unproven");
        }
        return geometryKey(evidence, frame);
    }
    private static Quaternion publishedQuaternion(Quaternion raw, ClassLoader loader) throws ReflectiveOperationException {
        Object q = Class.forName("org.joml.Quaterniond", false, loader)
                .getConstructor(double.class, double.class, double.class, double.class)
                .newInstance(raw.x(), raw.y(), raw.z(), raw.w());
        // Exact native publication: AttitudeTargetUtil normalizes its copy, then the snapshot constructor divides again.
        call(q, "normalize");
        double x = number(call(q, "x")), y = number(call(q, "y")), z = number(call(q, "z")), w = number(call(q, "w"));
        double length = Math.sqrt(x*x + y*y + z*z + w*w);
        return new Quaternion(x/length, y/length, z/length, w/length);
    }
    private static Vector nativePoint(Object pose, Vector point, ClassLoader loader) throws ReflectiveOperationException {
        Object copy = vector(loader);
        call(copy, "set", point.x(), point.y(), point.z());
        return vectorValue(call(pose, "transformPositionInverse", call(pose, "transformPosition", copy)));
    }
    private static List<PassiveGeometry> geometryKey(ModelEvidence evidence, BodyFrame frame) {
        var result = new ArrayList<PassiveGeometry>();
        for (PassiveGroup group : evidence.passive())
            result.add(new PassiveGeometry(group.surfaces(), frame.pose().scale(), group.modelDt()));
        return List.copyOf(result);
    }
    private static List<PassiveSurface> liveGeometry(Object body, ClassLoader loader) throws ReflectiveOperationException {
        Object plot = call(body, "getPlot");
        if (plot == null || plot.getClass() != Class.forName("dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot", false, loader))
            throw new Rejected("default_passive_plot_unproven");
        Object values = call(plot, "getLiftProviders");
        if (!(values instanceof Iterable<?> iterable)) throw new Rejected("default_passive_contexts_unproven");
        Class<?> provider = Class.forName(PROVIDER, false, loader);
        var contexts = new ArrayList<Object>();
        int count = 0;
        for (Object context : iterable) {
            if (++count > MAX_OBSERVATIONS) throw new Rejected("default_observation_limit");
            if (context == null || context.getClass() != Class.forName(PROVIDER + "$LiftProviderContext", false, loader))
                throw new Rejected("default_passive_contexts_unproven");
            if (provider.isInstance(call(call(context, "state"), "getBlock"))) contexts.add(context);
        }
        return geometry(contexts, loader);
    }
    private static List<PassiveSurface> geometry(List<?> contexts, ClassLoader loader) throws ReflectiveOperationException {
        var result = new ArrayList<PassiveSurface>();
        for (Object context : contexts) {
            if (context == null || context.getClass() != Class.forName(PROVIDER + "$LiftProviderContext", false, loader))
                throw new Rejected("default_passive_contexts_unproven");
            Object state = call(context, "state");
            if (state == null || state.getClass() != Class.forName("net.minecraft.world.level.block.state.BlockState", false, loader))
                throw new Rejected("default_passive_state_unproven");
            Object block = call(state, "getBlock");
            boolean create = block != null && block.getClass() == Class.forName(CREATE, false, loader);
            boolean symmetric = block != null && block.getClass().getName().equals(SYMMETRIC)
                    && block.getClass() == Class.forName(SYMMETRIC, false, loader);
            if (!create && !symmetric) throw new Rejected("default_passive_provider_unproven");
            String proof = PROVIDER_PROOF.get(block.getClass());
            if (!proof.isEmpty()) throw new Rejected(proof);
            Map<?, ?> values = map(call(state, "getValues"));
            if (values.size() != 1) throw new Rejected("default_passive_state_unproven");
            var entry = values.entrySet().iterator().next();
            Object property = entry.getKey(), value = entry.getValue();
            String propertyName = create ? "facing" : "axis";
            String propertyClass = "net.minecraft.world.level.block.state.properties." + (create ? "DirectionProperty" : "EnumProperty");
            String valueClass = create ? "net.minecraft.core.Direction" : "net.minecraft.core.Direction$Axis";
            if (property == null || property.getClass() != Class.forName(propertyClass, false, loader)
                    || property != staticField(Class.forName("net.minecraft.world.level.block.state.properties.BlockStateProperties", false, loader),
                            create ? "FACING" : "AXIS")
                    || !propertyName.equals(call(property, "getName")) || value == null || !(value instanceof Enum<?>) || ((Enum<?>)value).getDeclaringClass() != Class.forName(valueClass, false, loader))
                throw new Rejected("default_passive_state_unproven");
            String valueName = ((Enum<?>)value).name();
            Vector expected;
            if (create) {
                expected = new Vector(-number(call(value, "getStepX")), -number(call(value, "getStepY")), -number(call(value, "getStepZ")));
            } else {
                expected = switch (valueName) { case "X" -> new Vector(1, 0, 0); case "Y" -> new Vector(0, 1, 0);
                    case "Z" -> new Vector(0, 0, 1); default -> throw new Rejected("default_passive_state_unproven"); };
            }
            Object nativeDirection = call(context, "dir");
            if (nativeDirection == null || nativeDirection.getClass() != Class.forName("net.minecraft.world.phys.Vec3", false, loader))
                throw new Rejected("default_passive_direction_unproven");
            Vector direction = vectorValue(nativeDirection);
            if (!sameVector(direction, expected)) throw new Rejected("default_passive_direction_unproven");
            Object pos = call(context, "pos");
            if (pos == null || pos.getClass() != Class.forName("net.minecraft.core.BlockPos", false, loader)
                    && pos.getClass() != Class.forName("net.minecraft.core.BlockPos$MutableBlockPos", false, loader))
                throw new Rejected("default_passive_position_unproven");
            result.add(new PassiveSurface(((Number)call(pos, "getX")).intValue(), ((Number)call(pos, "getY")).intValue(),
                    ((Number)call(pos, "getZ")).intValue(), block.getClass().getName(), propertyName, valueName, direction));
        }
        result.sort(Comparator.comparingInt(PassiveSurface::x).thenComparingInt(PassiveSurface::y).thenComparingInt(PassiveSurface::z));
        for (int i = 1; i < result.size(); i++) {
            PassiveSurface a = result.get(i-1), b = result.get(i);
            if (a.x() == b.x() && a.y() == b.y() && a.z() == b.z()) throw new Rejected("default_passive_positions_duplicate");
        }
        return List.copyOf(result);
    }
    private static void providerMethods(Class<?> block, boolean create, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> provider = Class.forName(PROVIDER, false, loader);
        for (Method method : provider.getMethods()) {
            if (!method.getName().startsWith("sable$")) continue;
            Method actual = block.getMethod(method.getName(), method.getParameterTypes());
            boolean own = method.getName().equals("sable$getNormal") || !create
                    && (method.getName().equals("sable$getLiftScalar") || method.getName().equals("sable$getParallelDragScalar"));
            if (actual.getDeclaringClass() != (own ? block : provider) || actual.getReturnType() != method.getReturnType()
                    || !own && !actual.isDefault()) throw new Rejected("default_passive_provider_override");
        }
    }
    private static Pose pose(Object pose) throws ReflectiveOperationException {
        return new Pose(vectorValue(call(pose, "position")), quaternion(call(pose, "orientation")),
                vectorValue(call(pose, "rotationPoint")), vectorValue(call(pose, "scale")));
    }
    private static Vector vectorValue(Object vector) throws ReflectiveOperationException {
        Vector value = new Vector(number(call(vector, "x")), number(call(vector, "y")), number(call(vector, "z")));
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.z()))
            throw new Rejected("default_passive_vector_unproven");
        // Canonicalize signed zero so normals derived from getStep and native Vec3 compare geometrically.
        return new Vector(value.x() == 0 ? 0 : value.x(), value.y() == 0 ? 0 : value.y(), value.z() == 0 ? 0 : value.z());
    }
    private static Quaternion quaternion(Object quaternion) throws ReflectiveOperationException {
        Quaternion value = new Quaternion(number(call(quaternion, "x")), number(call(quaternion, "y")),
                number(call(quaternion, "z")), number(call(quaternion, "w")));
        double norm = value.x()*value.x() + value.y()*value.y() + value.z()*value.z() + value.w()*value.w();
        if (!Double.isFinite(norm) || Math.abs(norm - 1) > 1e-6) throw new Rejected("default_passive_pose_unproven");
        return value;
    }
    private static Vector subtract(Vector a, Vector b) { return new Vector(a.x()-b.x(), a.y()-b.y(), a.z()-b.z()); }
    private static boolean sameVector(Vector a, Vector b) { return a.x() == b.x() && a.y() == b.y() && a.z() == b.z(); }

    private static void external(Object sampler, Context before, Context after, ClassLoader loader)
            throws ReflectiveOperationException {
        Object external = field(sampler, "val$external");
        if (external == null || after.computer() == null || after.body() == null || after.level() == null
                || before.computer() != after.computer() || before.body() != after.body() || before.level() != after.level())
            throw new Rejected("default_external_pressure_unproven");
        Class<?> computer = Class.forName(TrimEnvironmentCompatibility.COMPUTER, false, loader);
        Class<?> body = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel", false, loader);
        Class<?> pressure = Class.forName(SAMPLER, false, loader);
        if (after.computer().getClass() != computer || after.body().getClass() != body
                || call(after.body(), "getLevel") != after.level()
                || call(after.body(), "logicalPose").getClass() != Class.forName("dev.ryanhcode.sable.companion.math.Pose3d", false, loader)
                || !EXTERNAL_ABI.get(computer))
            throw new Rejected("default_external_pressure_unproven");
        Class<?> type = external.getClass();
        if (!type.isHidden() || !type.isSynthetic() || !Modifier.isFinal(type.getModifiers()) || type.getNestHost() != computer
                || type.getInterfaces().length != 1 || type.getInterfaces()[0] != pressure)
            throw new Rejected("default_external_pressure_unproven");
        Method[] methods = type.getDeclaredMethods();
        if (methods.length != 1 || !methods[0].getName().equals("getPressure") || methods[0].getReturnType() != double.class
                || Modifier.isStatic(methods[0].getModifiers())
                || !java.util.Arrays.equals(methods[0].getParameterTypes(), new Class<?>[]{double.class, double.class, double.class}))
            throw new Rejected("default_external_pressure_unproven");
        Field[] fields = type.getDeclaredFields();
        if (fields.length != 2) throw new Rejected("default_external_pressure_unproven");
        boolean capturedComputer = false, capturedBody = false;
        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (!Modifier.isPrivate(modifiers) || !Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers) || !field.trySetAccessible())
                throw new Rejected("default_external_pressure_unproven");
            Object captured = field.get(external);
            if (field.getType() == computer && captured == after.computer() && !capturedComputer) capturedComputer = true;
            else if (field.getType() == body && captured == after.body() && !capturedBody) capturedBody = true;
            else throw new Rejected("default_external_pressure_unproven");
        }
        if (!capturedComputer || !capturedBody) throw new Rejected("default_external_pressure_unproven");
    }
    private static double altitude(Object pose, ClassLoader loader, double x, double y, double z) throws ReflectiveOperationException {
        Object point = vector(loader);
        call(point, "set", x, y, z);
        return number(call(call(pose, "transformPosition", point), "y"));
    }

    private static Pressure pressure(ClassLoader loader, Object dimension) throws ReflectiveOperationException {
        Class<?> data = Class.forName(SABLE + "DimensionPhysicsData", false, loader);
        if (!PRESSURE_ABI.get(data)) throw new Rejected("default_pressure_abi_unproven");
        Map<?, ?> configured = map(staticField(data, "DIMENSION_PHYSICS_DATA"));
        Map<?, ?> defaults = map(staticField(data, "DEFAULT_DIMENSION_PHYSICS_DATA"));
        Object fallback = defaults.get(dimension), effective = configured.get(dimension);
        if (effective == null) effective = fallback;
        if (effective == null || fallback == null) throw new Rejected("default_pressure_cache_missing");
        Class<?> configuration = Class.forName(SABLE + "DimensionPhysics", false, loader);
        if (effective.getClass() != configuration || fallback.getClass() != configuration)
            throw new Rejected("default_pressure_configuration_unproven");
        Object base = optional(call(effective, "basePressure"));
        if (base == null) base = optional(call(fallback, "basePressure"));
        Object curve = optional(call(effective, "pressureFunction"));
        if (curve == null) curve = optional(call(fallback, "pressureFunction"));
        if (!(base instanceof Double pressure) || !Double.isFinite(pressure) || pressure < 0
                || curve == null || curve.getClass() != Class.forName(SABLE + "BezierResourceFunction", false, loader))
            throw new Rejected("default_pressure_configuration_unproven");
        var points = new ArrayList<Point>();
        for (Object point : list(call(curve, "getPoints"))) {
            if (point.getClass() != Class.forName(SABLE + "BezierResourceFunction$BezierPoint", false, loader))
                throw new Rejected("default_pressure_point_unproven");
            Point copy = new Point(number(call(point, "altitude")), number(call(point, "value")), number(call(point, "slope")));
            if (!Double.isFinite(copy.altitude()) || !Double.isFinite(copy.value()) || !Double.isFinite(copy.slope()) || copy.value() < 0)
                throw new Rejected("default_pressure_point_invalid");
            points.add(copy);
        }
        return new Pressure(pressure, points);
    }

    private static boolean matches(ClassLoader loader, Map<String, String> expected) {
        try {
            for (var entry : expected.entrySet()) {
                try (var input = loader.getResourceAsStream(entry.getKey().replace('.', '/') + ".class")) {
                    if (input == null || !entry.getValue().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))))
                        return false;
                }
            }
            return true;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException unavailable) { return false; }
    }
    private static Object vector(ClassLoader loader) throws ReflectiveOperationException {
        return Class.forName("org.joml.Vector3d", false, loader).getConstructor().newInstance();
    }
    private static Object inheritedField(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { return readField(type, owner, name); } catch (NoSuchFieldException next) { /* search superclass */ }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        return readField(owner.getClass(), owner, name);
    }
    private static Object staticField(Class<?> type, String name) throws ReflectiveOperationException { return readField(type, null, name); }
    private static Object readField(Class<?> type, Object owner, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException(name);
        return field.get(owner);
    }
    private static Object call(Object owner, String name, Object... args) throws ReflectiveOperationException {
        return NativeFixedWingTrimEnvironment.invoke(owner.getClass(), owner, name, args);
    }
    private static Object optional(Object value) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(call(value, "isPresent")) ? call(value, "get") : null;
    }
    private static List<?> list(Object value) throws Rejected {
        if (!(value instanceof List<?> values)) throw new Rejected("default_list_unproven");
        if (values.size() > MAX_OBSERVATIONS) throw new Rejected("default_observation_limit");
        return values;
    }
    private static Map<?, ?> map(Object value) throws Rejected {
        if (!(value instanceof Map<?, ?> values)) throw new Rejected("default_map_unproven");
        return values;
    }
    private static double number(Object value) { return ((Number)value).doubleValue(); }
}
