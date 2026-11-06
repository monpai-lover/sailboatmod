package com.monpai.sailboatmod.compat.aeronautics.mixin;

import com.monpai.sailboatmod.compat.aeronautics.RailAircraftMotionCompatibility;
import com.monpai.sailboatmod.compat.aeronautics.RailAircraftNativeResources;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks optional files without loading any Sable or Flight Control class. */
public final class RailMotionMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(RailMotionMixinPlugin.class);
    private Boolean inspected;
    private static final Map<String, String> MIXINS = Map.of(
            RailAircraftMotionCompatibility.BODY, "com.monpai.sailboatmod.compat.aeronautics.mixin.SableRailMotionMixin",
            RailAircraftMotionCompatibility.RAPIER, "com.monpai.sailboatmod.compat.aeronautics.mixin.RapierRailConstraintMixin",
            RailAircraftMotionCompatibility.CONSTRAINT, "com.monpai.sailboatmod.compat.aeronautics.mixin.RapierRailConstraintHandleMixin");
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (!mixin.equals(MIXINS.get(target))) return false;
        return inspectedCompatibility();
    }
    private synchronized boolean inspectedCompatibility() {
        if (inspected != null) return inspected;
        try {
            var list = LoadingModList.get();
            var file = list == null ? null : list.getModFileById("sable");
            if (file == null) return false;
            String version = file.getMods().stream().filter(mod -> "sable".equals(mod.getModId()))
                    .map(mod -> mod.getVersion().toString()).findFirst().orElse("");
            inspected = supportsNativeResources(version, resource -> Files.newInputStream(file.getFile().findResource(resource)));
            if (!inspected) LOGGER.warn("[AirRail] Optional native motion disabled: inspected Sable/Rapier ABI differs");
        } catch (Exception | LinkageError unavailable) {
            inspected = false;
            LOGGER.warn("[AirRail] Optional native motion resource inspection failed: {}", unavailable.toString());
        }
        return inspected;
    }
    static boolean supportsNativeResources(String version, RailAircraftNativeResources.Source outerSable) throws Exception {
        var hashes = new HashMap<String, String>();
        var nodes = new HashMap<String, ClassNode>();
        for (var resource : RailAircraftNativeResources.read(outerSable).entrySet()) {
            String name = resource.getKey(); byte[] bytes = resource.getValue();
            hashes.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            nodes.put(name, node);
        }
        ClassNode body = nodes.get(RailAircraftMotionCompatibility.BODY);
        ClassNode pipeline = nodes.get(RailAircraftMotionCompatibility.RAPIER);
        ClassNode handle = nodes.get(RailAircraftMotionCompatibility.CONSTRAINT);
        return RailAircraftMotionCompatibility.supports(version, hashes,
                    count(body, "prePhysicsTick", RailAircraftMotionCompatibility.PHYSICS_METHOD),
                    count(body, "applyQueuedForces", RailAircraftMotionCompatibility.PHYSICS_METHOD))
                    && RailAircraftMotionCompatibility.supportsTopology(version, hashes,
                    count(pipeline, "addConstraint", RailAircraftMotionCompatibility.ADD_CONSTRAINT_METHOD),
                    count(pipeline, "dispose", "()V"),
                    count(pipeline, "remove", RailAircraftMotionCompatibility.REMOVE_BODY_METHOD),
                    count(handle, "remove", "()V"), count(handle, "isValid", "()Z"));
    }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (mixin.equals(MIXINS.get(target))) RailAircraftMotionCompatibility.markApplied(target);
    }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    private static int count(ClassNode node, String name, String descriptor) {
        return (int) node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).count();
    }
}
