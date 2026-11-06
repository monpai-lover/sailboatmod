package com.monpai.sailboatmod.compat.aeronautics.mixin;

import com.monpai.sailboatmod.compat.aeronautics.RailAircraftSurfaceCompatibility;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** Reads the exact Create outer mod file, without any native class lookup. */
public final class RailSurfaceMixinPlugin implements IMixinConfigPlugin {
    private Boolean inspected;
    @Override public synchronized boolean shouldApplyMixin(String target, String mixin) {
        if (!RailAircraftSurfaceCompatibility.BEARING.equals(target)
                || !mixin.equals("com.monpai.sailboatmod.compat.aeronautics.mixin.CreateRailSurfaceMixin")) return false;
        if (inspected != null) return inspected;
        inspected = false;
        try {
            var mods = LoadingModList.get(); var file = mods == null ? null : mods.getModFileById("create");
            if (file == null) return false;
            String version = file.getMods().stream().filter(mod -> mod.getModId().equals("create"))
                    .map(mod -> mod.getVersion().toString()).findFirst().orElse("");
            var classes = new HashMap<String, byte[]>();
            for (String name : RailAircraftSurfaceCompatibility.PINS.keySet())
                try (var input = Files.newInputStream(file.getFile().findResource(name.replace('.', '/') + ".class"))) {
                    classes.put(name, input.readNBytes(1024 * 1024 + 1));
                }
            inspected = RailAircraftSurfaceCompatibility.supports(version, classes);
        } catch (Exception | LinkageError unavailable) { inspected = false; }
        return inspected;
    }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (mixin.equals("com.monpai.sailboatmod.compat.aeronautics.mixin.CreateRailSurfaceMixin"))
            RailAircraftSurfaceCompatibility.markApplied(target);
    }
    @Override public void onLoad(String p) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
