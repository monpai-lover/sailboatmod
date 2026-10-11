package com.monpai.sailboatmod.compat;

import com.mojang.serialization.Dynamic;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Registry-aware stack persistence and explicit copy-on-write custom item data. */
public final class ItemStackNbt {
    private static final int LEGACY_1201_DATA_VERSION = 3465;
    private static final ThreadLocal<HolderLookup.Provider> ACTIVE_PROVIDER = new ThreadLocal<>();

    private ItemStackNbt() {}

    public static HolderLookup.Provider provider() {
        var active = ACTIVE_PROVIDER.get();
        if (active != null) return active;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) return server.registryAccess();
        if (FMLEnvironment.dist.isClient()) {
            var clientProvider = ClientLookup.provider();
            if (clientProvider != null) return clientProvider;
        }
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    public static <T> T withProvider(HolderLookup.Provider provider, Supplier<T> action) {
        var previous = ACTIVE_PROVIDER.get();
        ACTIVE_PROVIDER.set(provider);
        try {
            return action.get();
        } finally {
            if (previous == null) ACTIVE_PROVIDER.remove();
            else ACTIVE_PROVIDER.set(previous);
        }
    }

    public static ItemStack read(CompoundTag tag) {
        return read(provider(), tag);
    }

    public static ItemStack read(HolderLookup.Provider provider, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return ItemStack.EMPTY;
        CompoundTag modern = tag;
        if (tag.contains("Count", Tag.TAG_ANY_NUMERIC)) {
            Tag upgraded = DataFixers.getDataFixer().update(References.ITEM_STACK,
                    new Dynamic<Tag>(NbtOps.INSTANCE, tag.copy()), LEGACY_1201_DATA_VERSION,
                    SharedConstants.getCurrentVersion().getDataVersion().getVersion()).getValue();
            if (upgraded instanceof CompoundTag compound) modern = compound;
        }
        return ItemStack.parseOptional(provider, modern);
    }

    public static CompoundTag save(ItemStack stack, CompoundTag tag) {
        return save(provider(), stack, tag);
    }

    public static CompoundTag save(HolderLookup.Provider provider, ItemStack stack, CompoundTag tag) {
        if (stack == null || stack.isEmpty()) return tag;
        tag.merge((CompoundTag) stack.save(provider));
        return tag;
    }

    public static boolean hasTag(ItemStack stack) {
        return stack != null && stack.has(DataComponents.CUSTOM_DATA);
    }

    /** Snapshot only. Use updateTag or setTag to persist edits. */
    public static CompoundTag tag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    public static void setTag(ItemStack stack, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
        else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static void updateTag(ItemStack stack, Consumer<CompoundTag> updater) {
        CompoundTag tag = tag(stack);
        updater.accept(tag);
        setTag(stack, tag);
    }

    private static final class ClientLookup {
        static HolderLookup.Provider provider() {
            var client = net.minecraft.client.Minecraft.getInstance();
            if (client.level != null) return client.level.registryAccess();
            return client.getConnection() == null ? null : client.getConnection().registryAccess();
        }
    }
}
