package com.monpai.sailboatmod.util;

import java.util.function.Supplier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** Keeps lazy references to client handlers behind the physical-side check. */
public final class ClientSideExecutor {
    private ClientSideExecutor() {}

    public static void unsafeRunWhenOn(Dist side, Supplier<Runnable> work) {
        if (FMLEnvironment.dist == side) work.get().run();
    }
}
