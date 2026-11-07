package com.monpai.sailboatmod.transport.rail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

/** Marks the actual stacks travelling in Create's mounted storage. Only these stacks can be delivered. */
final class RailCargoCustody {
    private static final String KEY = "sailboatmod:rail_freight";
    private static final String ID = "Custody";
    private static final String HAD_DATA = "HadCustomData";

    private RailCargoCustody() {}

    static ItemStack mark(ItemStack original, UUID custodyId) {
        if (original.isEmpty() || custodyId == null || hasMarker(original)) {
            throw new IllegalArgumentException("Rail cargo must be a nonempty unmarked stack");
        }
        CustomData originalData = original.get(DataComponents.CUSTOM_DATA);
        CompoundTag data = originalData == null ? new CompoundTag() : originalData.copyTag();
        CompoundTag marker = new CompoundTag();
        marker.putUUID(ID, custodyId);
        marker.putBoolean(HAD_DATA, originalData != null);
        data.put(KEY, marker);
        ItemStack result = original.copy();
        result.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return result;
    }

    static boolean hasMarker(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(KEY);
    }

    static @Nullable UUID custodyId(ItemStack stack) {
        CompoundTag marker = marker(stack);
        return marker == null ? null : marker.getUUID(ID);
    }

    static @Nullable ItemStack restore(ItemStack actual, UUID expectedId) {
        if (actual.isEmpty() || expectedId == null) return null;
        CompoundTag marker = marker(actual);
        if (marker == null || !expectedId.equals(marker.getUUID(ID))) return null;
        CompoundTag remaining = actual.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        remaining.remove(KEY);
        boolean hadData = marker.getBoolean(HAD_DATA);
        if (!hadData && !remaining.isEmpty()) return null;
        ItemStack restored = actual.copy();
        if (hadData) restored.set(DataComponents.CUSTOM_DATA, CustomData.of(remaining));
        else restored.remove(DataComponents.CUSTOM_DATA);
        return restored;
    }

    static boolean matchesExact(List<ItemStack> actualMarked, List<ItemStack> expectedRaw, UUID id) {
        if (actualMarked.isEmpty() || expectedRaw.isEmpty()) return false;
        Map<CargoKey, Long> expected = new HashMap<>();
        for (ItemStack stack : expectedRaw) {
            if (stack == null || stack.isEmpty() || hasMarker(stack)) return false;
            expected.merge(new CargoKey(stack), (long) stack.getCount(), Long::sum);
        }
        for (ItemStack stack : actualMarked) {
            ItemStack restored = stack == null ? null : restore(stack, id);
            if (restored == null) return false;
            CargoKey key = new CargoKey(restored);
            long remaining = expected.getOrDefault(key, 0L) - restored.getCount();
            if (remaining < 0) return false;
            if (remaining == 0) expected.remove(key);
            else expected.put(key, remaining);
        }
        return expected.isEmpty();
    }

    private static @Nullable CompoundTag marker(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(KEY)) return null;
        CompoundTag marker = data.copyTag().getCompound(KEY);
        return marker.size() == 2 && marker.hasUUID(ID) && marker.contains(HAD_DATA, Tag.TAG_BYTE) ? marker : null;
    }

    private record CargoKey(ItemStack stack) {
        @Override public boolean equals(Object other) {
            return other instanceof CargoKey key && ItemStack.isSameItemSameComponents(stack, key.stack);
        }

        @Override public int hashCode() {
            return ItemStack.hashItemAndComponents(stack);
        }
    }
}
