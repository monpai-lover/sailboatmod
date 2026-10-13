package com.monpai.sailboatmod.market;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/** A versioned identity of the item and its serialized components, independent of the stock count. */
public final class MarketStockIdentity {
    private MarketStockIdentity() { }

    public static String fingerprint(HolderLookup.Provider registries, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ItemStack sample = stack.copy();
        sample.setCount(1);
        Tag serialized = sample.save(registries);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeCanonical(output, serialized);
            }
            return "stack-v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Cannot compute market stock identity", impossible);
        }
    }

    /** Sort compound keys recursively; retain tag types, numeric bits, list order and array contents. */
    private static void writeCanonical(DataOutputStream output, Tag tag) throws IOException {
        output.writeByte(tag.getId());
        switch (tag.getId()) {
            case Tag.TAG_END -> { }
            case Tag.TAG_BYTE -> output.writeByte(((NumericTag) tag).getAsByte());
            case Tag.TAG_SHORT -> output.writeShort(((NumericTag) tag).getAsShort());
            case Tag.TAG_INT -> output.writeInt(((NumericTag) tag).getAsInt());
            case Tag.TAG_LONG -> output.writeLong(((NumericTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> output.writeInt(Float.floatToRawIntBits(((NumericTag) tag).getAsFloat()));
            case Tag.TAG_DOUBLE -> output.writeLong(Double.doubleToRawLongBits(((NumericTag) tag).getAsDouble()));
            case Tag.TAG_BYTE_ARRAY -> {
                byte[] array = ((ByteArrayTag) tag).getAsByteArray();
                output.writeInt(array.length);
                output.write(array);
            }
            case Tag.TAG_STRING -> writeText(output, tag.getAsString());
            case Tag.TAG_LIST -> {
                ListTag list = (ListTag) tag;
                output.writeByte(list.getElementType());
                output.writeInt(list.size());
                for (Tag element : list) {
                    writeCanonical(output, element);
                }
            }
            case Tag.TAG_COMPOUND -> {
                CompoundTag compound = (CompoundTag) tag;
                List<String> keys = new ArrayList<>(compound.getAllKeys());
                Collections.sort(keys);
                output.writeInt(keys.size());
                for (String key : keys) {
                    writeText(output, key);
                    writeCanonical(output, compound.get(key));
                }
            }
            case Tag.TAG_INT_ARRAY -> {
                int[] array = ((IntArrayTag) tag).getAsIntArray();
                output.writeInt(array.length);
                for (int value : array) output.writeInt(value);
            }
            case Tag.TAG_LONG_ARRAY -> {
                long[] array = ((LongArrayTag) tag).getAsLongArray();
                output.writeInt(array.length);
                for (long value : array) output.writeLong(value);
            }
            default -> throw new IllegalArgumentException("Unsupported NBT tag: " + tag.getId());
        }
    }

    private static void writeText(DataOutputStream output, String text) throws IOException {
        // NBT preserves UTF-16 code units, including lone surrogates. UTF-8 replacement would merge variants.
        output.writeInt(text.length());
        for (int index = 0; index < text.length(); index++) {
            output.writeChar(text.charAt(index));
        }
    }
}
