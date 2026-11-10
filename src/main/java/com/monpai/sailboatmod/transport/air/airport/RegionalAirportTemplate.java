package com.monpai.sailboatmod.transport.air.airport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.ReportedNbtException;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The bundled north-west-oriented template, anchored to its airport registration tile. */
public final class RegionalAirportTemplate {
    public static final int BLOCK_COUNT = 17_881;
    private static final String RESOURCE = "/data/sailboatmod/structure/regional_airport.nbt";
    private static final int WIDTH = 145, HEIGHT = 27, DEPTH = 96;
    private static final BlockPos TERMINAL_OFFSET = new BlockPos(68, 1, 78);

    private RegionalAirportTemplate() {}

    public record Piece(BlockPos pos, BlockState state) {
        public Piece { pos = pos.immutable(); }
    }

    public static List<Piece> load(BlockPos terminal) {
        try (var input = RegionalAirportTemplate.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalid("missing resource");
            return decode(NbtIo.readCompressed(input, NbtAccounter.create(8L * 1024 * 1024)), terminal);
        } catch (IOException | NbtException | ReportedNbtException | ArithmeticException e) {
            throw new IllegalStateException("Cannot load bundled regional airport", e);
        }
    }

    private static List<Piece> decode(CompoundTag root, BlockPos terminal) {
        ListTag size = list(root, "size", Tag.TAG_INT, 3);
        if (size.getInt(0) != WIDTH || size.getInt(1) != HEIGHT || size.getInt(2) != DEPTH
                || root.getInt("DataVersion") != 3955 || root.contains("palettes")
                || !(root.get("entities") instanceof ListTag entities) || !entities.isEmpty()) {
            throw invalid("size, format or entities");
        }
        ListTag paletteTag = list(root, "palette", Tag.TAG_COMPOUND, 26);
        var palette = new ArrayList<BlockState>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) palette.add(state(paletteTag.getCompound(i)));

        ListTag blocks = list(root, "blocks", Tag.TAG_COMPOUND, BLOCK_COUNT);
        var pieces = new ArrayList<Piece>(BLOCK_COUNT);
        var occupied = new BitSet(WIDTH * HEIGHT * DEPTH);
        boolean hasRegistrationTile = false;
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            ListTag position = list(block, "pos", Tag.TAG_INT, 3);
            int x = position.getInt(0), y = position.getInt(1), z = position.getInt(2);
            int stateId = block.getInt("state");
            if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT || z < 0 || z >= DEPTH
                    || block.contains("nbt") || !block.contains("state", Tag.TAG_INT)
                    || stateId < 0 || stateId >= palette.size()) throw invalid("block entry " + i);
            int cell = (y * DEPTH + z) * WIDTH + x;
            if (occupied.get(cell)) throw invalid("duplicate block entry " + i);
            occupied.set(cell);

            BlockPos offset = new BlockPos(x, y, z);
            if (offset.equals(TERMINAL_OFFSET)) throw invalid("registration position is occupied");
            BlockState state = palette.get(stateId);
            if (offset.equals(TERMINAL_OFFSET.below())) hasRegistrationTile = state.is(Blocks.LIME_CONCRETE);
            BlockPos pos = new BlockPos(Math.addExact(terminal.getX(), x - TERMINAL_OFFSET.getX()),
                    Math.addExact(terminal.getY(), y - TERMINAL_OFFSET.getY()),
                    Math.addExact(terminal.getZ(), z - TERMINAL_OFFSET.getZ()));
            pieces.add(new Piece(pos, state));
        }
        if (!hasRegistrationTile) throw invalid("registration tile is missing");
        return List.copyOf(pieces);
    }

    private static BlockState state(CompoundTag entry) {
        var id = ResourceLocation.tryParse(entry.getString("Name"));
        if (id == null || !id.getNamespace().equals("minecraft") || !BuiltInRegistries.BLOCK.containsKey(id)
                || entry.contains("Properties") && !entry.contains("Properties", Tag.TAG_COMPOUND)) {
            throw invalid("palette block " + entry.getString("Name"));
        }
        BlockState result = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry);
        if (result.isAir() || !result.getFluidState().isEmpty()) throw invalid("air or fluid palette block " + id);
        CompoundTag properties = entry.getCompound("Properties");
        CompoundTag decoded = NbtUtils.writeBlockState(result).getCompound("Properties");
        for (String key : properties.getAllKeys()) {
            if (!properties.get(key).equals(decoded.get(key))) throw invalid("palette property " + id + " " + key);
        }
        return result;
    }

    private static ListTag list(CompoundTag tag, String key, int type, int size) {
        ListTag list = tag.getList(key, type);
        if (list.size() != size) throw invalid("unexpected " + key + " length");
        return list;
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("Invalid bundled regional airport: " + detail);
    }
}
