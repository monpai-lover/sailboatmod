package com.monpai.sailboatmod.compat.aeronautics;

import java.util.Map;

/** Exact optional ABI inspected for body-scoped route motion; independent of Flight Control. */
public final class RailAircraftMotionCompatibility {
    public static final String BODY = "dev.ryanhcode.sable.sublevel.ServerSubLevel";
    public static final String SYSTEM = "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem";
    public static final String PIPELINE = "dev.ryanhcode.sable.api.physics.PhysicsPipeline";
    public static final String PLOT = "dev.ryanhcode.sable.sublevel.plot.LevelPlot";
    public static final String RAPIER = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline";
    public static final String CONSTRAINT = "dev.ryanhcode.sable.physics.impl.rapier.constraint.RapierConstraintHandle";
    public static final String ADD_CONSTRAINT_METHOD = "(Ldev/ryanhcode/sable/api/physics/PhysicsPipelineBody;"
            + "Ldev/ryanhcode/sable/api/physics/PhysicsPipelineBody;"
            + "Ldev/ryanhcode/sable/api/physics/constraint/PhysicsConstraintConfiguration;)"
            + "Ldev/ryanhcode/sable/api/physics/constraint/PhysicsConstraintHandle;";
    public static final String REMOVE_BODY_METHOD = "(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V";
    public static final String PHYSICS_METHOD = "(Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;"
            + "Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V";
    public static final Map<String, String> PINNED_CLASSES = Map.of(
            BODY, "033eff21106f748c94b4dc51426a450398392a2e896a9ba7095a7ce4270b9119",
            SYSTEM, "02829b632495306538d5331a3369dc36d55b4d2d4fbec9f4949490d8ec9d48ac",
            PIPELINE, "080a1b2ca40f6a34e9e9b36a4850244a6dd1fa8f0199ad86820db1dabc988593",
            PLOT, "a9c45a8d468ab91af1bd15f8696dbc66a89c1af7b0ceedec2b8f34484aafe310",
            "dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle",
            "67e227caa04d704850c6a0c143391d2f892009dbea6f66a42ce93c14f7e43cf3");
    public static final Map<String, String> PINNED_RAPIER_CLASSES = Map.of(
            RAPIER, "70987f797d23a06798ab93f9334276a9673655c424452a4029a55ca365059178",
            CONSTRAINT, "663856aab740bc9d05e7c879b555fc055636e2949ed7817eb3bbf8d1ed6f29e6",
            "dev.ryanhcode.sable.physics.impl.rapier.constraint.fixed.RapierFixedConstraintHandle",
            "128e7770d3add8be01dfdd0f3491238d7dc6267ecb94296eb5ed2f180675470f",
            "dev.ryanhcode.sable.physics.impl.rapier.constraint.free.RapierFreeConstraintHandle",
            "7b6884c7902c96218115823e7849969cc746251f509c9860edd75833efdd1f3b",
            "dev.ryanhcode.sable.physics.impl.rapier.constraint.generic.RapierGenericConstraintHandle",
            "456d04f989bf42a230459bb5bb770031570a21f1494c9a16325a98d7284c6f3c",
            "dev.ryanhcode.sable.physics.impl.rapier.constraint.rotary.RapierRotaryConstraintHandle",
            "ec5767cbb7cef33c7325c999af9867b059cbba0a714d3f88663bf9b92aae5f42");
    private static volatile int appliedTargets;
    private RailAircraftMotionCompatibility() { }
    public static boolean supports(String version, Map<String, String> hashes, int actors, int queued) {
        return "2.0.6".equals(version) && hashes != null && hashes.entrySet().containsAll(PINNED_CLASSES.entrySet())
                && actors == 1 && queued == 1;
    }
    public static boolean supportsTopology(String version, Map<String, String> hashes, int add, int dispose,
                                            int bodyRemove, int constraintRemove, int valid) {
        return "2.0.6".equals(version) && hashes != null
                && hashes.entrySet().containsAll(PINNED_RAPIER_CLASSES.entrySet())
                && add == 1 && dispose == 1 && bodyRemove == 1 && constraintRemove == 1 && valid == 1;
    }
    public static boolean applied() { return appliedTargets == 7; }
    public static synchronized void markApplied(String target) {
        if (BODY.equals(target)) appliedTargets |= 1;
        else if (RAPIER.equals(target)) appliedTargets |= 2;
        else if (CONSTRAINT.equals(target)) appliedTargets |= 4;
    }
}
