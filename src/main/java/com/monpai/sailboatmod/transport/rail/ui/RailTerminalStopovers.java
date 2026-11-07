package com.monpai.sailboatmod.transport.rail.ui;

import com.monpai.sailboatmod.transport.rail.RailRoute;
import com.monpai.sailboatmod.transport.rail.RailStationRef;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The configured terminal identities and the exact native stations they were bound to. */
public final class RailTerminalStopovers {
    public static final int MAX_ROUTES = 32;

    public record Choice(BlockPos terminalPos, UUID terminalId, RailStationRef station) {
        public Choice {
            terminalPos = Objects.requireNonNull(terminalPos).immutable();
            Objects.requireNonNull(terminalId);
            Objects.requireNonNull(station);
            if (!validPosition(terminalPos) || !validPosition(station.stationPos()))
                throw new IllegalArgumentException("Invalid rail stopover position");
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("TerminalPos", terminalPos.asLong());
            tag.putUUID("TerminalId", terminalId);
            tag.putString("Dimension", station.dimensionId());
            tag.putLong("StationPos", station.stationPos().asLong());
            tag.putUUID("StationId", station.stationId());
            return tag;
        }

        public static Choice load(CompoundTag tag) {
            if (!tag.contains("TerminalPos", Tag.TAG_LONG) || !tag.hasUUID("TerminalId")
                    || !tag.contains("StationPos", Tag.TAG_LONG) || !tag.hasUUID("StationId"))
                throw new IllegalArgumentException("Missing rail stopover identity");
            return new Choice(BlockPos.of(tag.getLong("TerminalPos")), tag.getUUID("TerminalId"),
                    new RailStationRef(tag.getString("Dimension"), BlockPos.of(tag.getLong("StationPos")), tag.getUUID("StationId")));
        }
    }

    public record Plan(Choice destination, List<Choice> stops, List<Integer> dwellSeconds) {
        public Plan(Choice destination, List<Choice> stops) {
            this(destination, stops, Collections.nCopies(stops.size(), RailRoute.DEFAULT_STOPOVER_SECONDS));
        }

        public Plan {
            Objects.requireNonNull(destination);
            stops = List.copyOf(stops);
            dwellSeconds = List.copyOf(dwellSeconds);
            if (stops.size() > RailRoute.MAX_STOPOVERS || stops.size() != dwellSeconds.size()
                    || dwellSeconds.stream().anyMatch(seconds ->
                    seconds < RailRoute.DEFAULT_STOPOVER_SECONDS || seconds > RailRoute.MAX_STOPOVER_SECONDS))
                throw new IllegalArgumentException("Invalid rail stopover dwell times");
        }

        public boolean validFrom(BlockPos origin, RailStationRef source) {
            if (!RailRoute.validStopovers(source, destination.station(), stops.stream().map(Choice::station).toList())
                    || origin.equals(destination.terminalPos())) return false;
            var terminals = new HashSet<BlockPos>();
            terminals.add(origin);
            terminals.add(destination.terminalPos());
            for (Choice stop : stops)
                if (!terminals.add(stop.terminalPos())) return false;
            return true;
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.put("Destination", destination.save());
            ListTag points = new ListTag();
            for (int i = 0; i < stops.size(); i++) {
                CompoundTag point = stops.get(i).save();
                int seconds = dwellSeconds.get(i);
                if (seconds != RailRoute.DEFAULT_STOPOVER_SECONDS) point.putInt("DwellSeconds", seconds);
                points.add(point);
            }
            tag.put("Stops", points);
            return tag;
        }

        public static Plan load(CompoundTag tag) {
            if (!(tag.get("Stops") instanceof ListTag list) || list.size() > RailRoute.MAX_STOPOVERS
                    || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
                throw new IllegalArgumentException("Invalid rail stopover list");
            List<Choice> stops = new ArrayList<>(list.size());
            List<Integer> dwell = new ArrayList<>(list.size());
            for (Tag raw : list) {
                CompoundTag point = (CompoundTag) raw;
                if (point.contains("DwellSeconds") && !point.contains("DwellSeconds", Tag.TAG_INT))
                    throw new IllegalArgumentException("Invalid rail stopover dwell time");
                stops.add(Choice.load(point));
                dwell.add(point.contains("DwellSeconds") ? point.getInt("DwellSeconds") : RailRoute.DEFAULT_STOPOVER_SECONDS);
            }
            return new Plan(Choice.load(tag.getCompound("Destination")), stops, dwell);
        }
    }

    private static boolean validPosition(BlockPos pos) {
        return Math.abs((long) pos.getX()) <= 30_000_000 && Math.abs((long) pos.getZ()) <= 30_000_000
                && BlockPos.of(pos.asLong()).equals(pos);
    }

    private RailTerminalStopovers() {}
}
