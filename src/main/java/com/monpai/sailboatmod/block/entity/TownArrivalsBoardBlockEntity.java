package com.monpai.sailboatmod.block.entity;

import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.board.TownVoyageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Passive public panel. Binding always comes from the block's current server position. */
public final class TownArrivalsBoardBlockEntity extends BlockEntity {
    private static final int PUBLIC_SCHEMA_VERSION = 1;
    private static final Map<String, Byte> PUBLIC_FIELD_TYPES = Map.of(
            "Version", Tag.TAG_INT, "TownName", Tag.TAG_STRING, "Filter", Tag.TAG_STRING,
            "Page", Tag.TAG_INT, "Pages", Tag.TAG_INT, "Revision", Tag.TAG_LONG,
            "ObservedTick", Tag.TAG_LONG, "Syncing", Tag.TAG_BYTE, "Overflow", Tag.TAG_INT, "Rows", Tag.TAG_LIST);
    private static final ClientMoment NO_CLIENT_CLOCK = new ClientMoment(0, 0, true);
    private static volatile Supplier<ClientMoment> clientClock = () -> NO_CLIENT_CLOCK;
    private Settings settings = new Settings(Filter.ALL, 0);
    private Panel panel = unbound(Filter.ALL, 0);
    private String boundTownId = ""; // Session-only; never read from copied or persisted NBT.
    private final ClientState client = new ClientState();

    public TownArrivalsBoardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TOWN_ARRIVALS_BOARD_BLOCK_ENTITY.get(), pos, state);
    }

    public record Settings(Filter filter, int page) {
        public Settings {
            filter = filter == null ? Filter.ALL : filter;
            page = Math.clamp(page, 0, 7);
        }
        public Settings nextFilter() { return new Settings(Filter.values()[(filter.ordinal() + 1) % Filter.values().length], 0); }
    }
    public record Panel(String townName, BoardView view) {
        public Panel { townName = displayName(townName, 64); }
        public boolean bound() { return !townName.isBlank(); }
    }
    @FunctionalInterface
    public interface Views { BoardView view(String town, Filter filter, int page, long now); }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) refreshFromTown();
    }
    public static void serverTick(Level level, BlockPos pos, BlockState state, TownArrivalsBoardBlockEntity board) {
        if (level instanceof ServerLevel server && server.getServer().overworld().getGameTime() % 20 == 0) board.refreshFromTown();
    }
    private void refreshFromTown() {
        if (!(level instanceof ServerLevel server)) return;
        long now = Math.max(0, server.getServer().overworld().getGameTime());
        TownRecord actual = TownService.getTownAt(server, worldPosition);
        int pages = actual != null && actual.townId().equals(boundTownId) ? panel.view().totalPages() : 1;
        Settings request = new Settings(settings.filter(), pageAt(now, pages, settings.page()));
        var service = TownVoyageService.existing(server.getServer());
        panel = collect(actual, request, now, (town, filter, page, tick) -> service
                .map(shared -> shared.view(town, filter, page, tick)).orElseGet(() -> empty(filter, tick, true)));
        boundTownId = actual == null ? "" : actual.townId();
        // Send a heartbeat even if the shared revision has not changed: clients age receipts locally.
        server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public boolean cycleFilter(Player player) {
        if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer actor)) return false;
        TownRecord current = TownService.getTownAt(server, worldPosition);
        boolean manager = current != null && TownService.canManageTown(actor, NationSavedData.get(server), current);
        if (!canConfigure(true, actor.serverLevel() == server, actor.distanceToSqr(Vec3.atCenterOf(worldPosition)),
                current == null ? "" : current.townId(), manager)) return false;
        settings = settings.nextFilter();
        panel = unbound(settings.filter(), 0);
        setChanged();
        refreshFromTown();
        return true;
    }
    /** Client-local receipt clock and trust generation; never persisted or sent in public NBT. */
    public record ClientMoment(long ticks, long epoch, boolean frozen) { }
    public static void installClientClock(Supplier<ClientMoment> source) { clientClock = java.util.Objects.requireNonNull(source); }
    public Filter getFilter() { return settings.filter(); }
    public boolean isTownBound() { return panel.bound(); }
    public Panel getClientPanel() { return client.display(clientClock.get()); }

    /** Production permission seam; caller supplies evidence from a real ServerPlayer and the positional TownRecord. */
    public static boolean canConfigure(boolean realServerPlayer, boolean sameLevel, double distanceSqr, String currentTownId, boolean managesTown) {
        return realServerPlayer && sameLevel && Double.isFinite(distanceSqr) && distanceSqr >= 0 && distanceSqr <= 64
                && currentTownId != null && !currentTownId.isBlank() && managesTown;
    }
    /** Only the supplied shared service projection is read; rows are never re-filtered or recomputed here. */
    public static Panel collect(TownRecord actual, Settings settings, long now, Views views) {
        if (actual == null) return unbound(settings.filter(), now);
        return new Panel(actual.name(), views.view(actual.townId(), settings.filter(), settings.page(), now));
    }
    public static int pageAt(long now, int totalPages, int offset) {
        int pages = Math.clamp(totalPages, 1, 8);
        return (int) Math.floorMod(Math.max(0, now) / 200 + Math.clamp(offset, 0, 7), pages);
    }
    private static BoardView empty(Filter filter, long now, boolean syncing) {
        return new BoardView(0, Math.max(0, now), filter, 0, 1, 0, syncing, List.of());
    }
    private static Panel unbound(Filter filter, long now) { return new Panel("", empty(filter, now, true)); }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompoundTag own = encodeSettings(settings);
        tag.putString("Filter", own.getString("Filter"));
        tag.putInt("Page", own.getInt("Page"));
    }
    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        settings = decodeSettings(tag);
        boundTownId = "";
        panel = unbound(settings.filter(), 0);
        client.loadDisk(tag);
    }
    public static CompoundTag encodeSettings(Settings settings) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Filter", settings.filter().name()); tag.putInt("Page", settings.page());
        return tag;
    }
    public static Settings decodeSettings(CompoundTag tag) {
        return new Settings(enumOr(Filter.class, enumText(tag, "Filter"), Filter.ALL), tag.getInt("Page"));
    }
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return encodePublic(panel); }
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (level == null || level.isClientSide) client.receive(tag, clientClock.get());
    }
    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }

    /** Deliberate whitelist; never serialize the block entity's saved data for a public update. */
    public static CompoundTag encodePublic(Panel panel) {
        CompoundTag tag = new CompoundTag();
        BoardView view = panel.view();
        tag.putInt("Version", PUBLIC_SCHEMA_VERSION);
        tag.putString("TownName", panel.townName()); tag.putString("Filter", view.filter().name());
        tag.putInt("Page", Math.clamp(view.page(), 0, 7)); tag.putInt("Pages", Math.clamp(view.totalPages(), 1, 8));
        tag.putLong("Revision", view.revision()); tag.putLong("ObservedTick", view.observedTick());
        tag.putBoolean("Syncing", view.syncing()); tag.putInt("Overflow", view.overflow());
        ListTag rows = new ListTag();
        for (PublicRow row : view.rows().stream().limit(MAX_ROWS).toList()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("RowId", row.rowId()); entry.putString("Kind", row.kind().name());
            entry.putString("CarrierName", row.carrierName()); entry.putString("RouteName", row.routeName());
            entry.putString("SourceName", row.sourceName()); entry.putString("TargetName", row.targetName());
            entry.putString("Relation", row.relation().name()); entry.putString("Phase", row.publicPhase());
            entry.putString("State", row.state().name());
            if (row.etaTicks().isPresent()) entry.putLong("EtaTicks", row.etaTicks().getAsLong());
            rows.add(entry);
        }
        tag.put("Rows", rows);
        return tag;
    }
    public static Panel decodePublic(CompoundTag tag) {
        if (!validPublicMetadata(tag)) return unbound(Filter.ALL, 0);
        Filter filter = Filter.valueOf(tag.getString("Filter"));
        String townName = displayName(tag.getString("TownName"), 64);
        long observed = tag.getLong("ObservedTick");
        if (townName.isBlank()) return unbound(filter, observed);
        int pages = tag.getInt("Pages");
        int page = tag.getInt("Page");
        List<PublicRow> rows = new ArrayList<>();
        ListTag encoded = tag.getList("Rows", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(MAX_ROWS, encoded.size()); i++) {
            CompoundTag row = encoded.getCompound(i);
            try {
                String rowId = row.getString("RowId");
                OptionalLong eta = row.contains("EtaTicks", Tag.TAG_LONG) ? OptionalLong.of(row.getLong("EtaTicks")) : OptionalLong.empty();
                rows.add(new PublicRow(rowId, LogisticsCarrier.Kind.valueOf(enumText(row, "Kind")),
                        displayName(row.getString("CarrierName"), 64), displayName(row.getString("RouteName"), 96),
                        displayName(row.getString("SourceName"), 64), displayName(row.getString("TargetName"), 64),
                        Relation.valueOf(enumText(row, "Relation")), enumText(row, "Phase"), State.valueOf(enumText(row, "State")), eta));
            } catch (IllegalArgumentException invalidRow) {
                // One malformed public row must not break the other rows or the game client.
            }
        }
        return new Panel(townName, new BoardView(tag.getLong("Revision"), observed, filter, page, pages,
                tag.getInt("Overflow"), tag.getByte("Syncing") == 1, rows));
    }
    private static boolean validPublicMetadata(CompoundTag tag) {
        for (var field : PUBLIC_FIELD_TYPES.entrySet()) if (!tag.contains(field.getKey(), field.getValue())) return false;
        if (tag.getInt("Version") != PUBLIC_SCHEMA_VERSION || enumOr(Filter.class, enumText(tag, "Filter"), null) == null) return false;
        String townName = tag.getString("TownName");
        if (townName.length() > 128 || townName.codePointCount(0, townName.length()) > 64) return false;
        int page = tag.getInt("Page"), pages = tag.getInt("Pages");
        if (pages < 1 || pages > 8 || page < 0 || page >= pages || tag.getInt("Overflow") < 0
                || tag.getLong("Revision") < 0 || tag.getLong("ObservedTick") < 0) return false;
        byte syncing = tag.getByte("Syncing");
        if (syncing != 0 && syncing != 1) return false;
        return tag.get("Rows") instanceof ListTag rows && (rows.isEmpty() || rows.getElementType() == Tag.TAG_COMPOUND);
    }
    private static String enumText(CompoundTag tag, String key) {
        String value = tag.getString(key);
        return value.length() <= 32 ? value : "";
    }
    private static <T extends Enum<T>> T enumOr(Class<T> type, String value, T fallback) {
        try { return Enum.valueOf(type, value); } catch (IllegalArgumentException invalid) { return fallback; }
    }
    private static String displayName(String value, int max) {
        if (value == null || value.isBlank()) return "";
        StringBuilder result = new StringBuilder();
        int count = 0;
        boolean format = false, space = false;
        for (int offset = 0; offset < Math.min(value.length(), max * 4) && count < max;) {
            int cp = value.codePointAt(offset); offset += Character.charCount(cp);
            if (format) { format = false; continue; }
            if (cp == 0xA7) { format = true; continue; }
            if (Character.getType(cp) == Character.FORMAT) continue;
            if (Character.isISOControl(cp) || Character.isWhitespace(cp)) { space = !result.isEmpty(); continue; }
            if (Character.getType(cp) == Character.SURROGATE) cp = 0xFFFD;
            if (space) { result.append(' '); count++; space = false; }
            if (count >= max) break;
            result.appendCodePoint(cp); count++;
        }
        return result.toString().strip();
    }
    public static final class ClientState {
        private Panel panel = unbound(Filter.ALL, 0);
        private ClientMoment receivedMoment;
        private long countdownTick = -1;
        private Panel countdown;
        private boolean expired;
        public void loadDisk(CompoundTag tag) {
            panel = unbound(decodeSettings(tag).filter(), 0);
            receivedMoment = null; expired = false; countdownTick = -1; countdown = null;
        }
        public void receive(CompoundTag tag, ClientMoment moment) {
            Panel next = decodePublic(tag);
            if (!moment.frozen() && receivedMoment != null && receivedMoment.epoch() == moment.epoch()
                    && panel.bound() && next.bound() && next.view().observedTick() <= panel.view().observedTick()) {
                // Board heartbeats can resend a cached projection or select a different page of it.
                if (!expired && next.view().observedTick() == panel.view().observedTick()) {
                    panel = next;
                    countdownTick = -1;
                }
                return;
            }
            panel = next; receivedMoment = moment.frozen() ? null : moment; expired = false; countdownTick = -1;
            if (moment.frozen()) expire();
        }
        public Panel display(ClientMoment moment) {
            // The global clock records freezes and world changes even while this board is culled.
            if (!expired && !moment.frozen() && receivedMoment != null && moment.epoch() == receivedMoment.epoch()
                    && moment.ticks() >= receivedMoment.ticks() && moment.ticks() - receivedMoment.ticks() <= 100) {
                long elapsed = moment.ticks() - receivedMoment.ticks();
                if (elapsed == 0 || panel.view().syncing()) return panel;
                if (countdownTick != moment.ticks()) {
                    BoardView view = panel.view();
                    countdown = panel;
                    if (view.rows().stream().anyMatch(row -> row.etaTicks().isPresent())) {
                        List<PublicRow> rows = view.rows().stream().map(row -> row.afterTicks(elapsed)).toList();
                        countdown = new Panel(panel.townName(), new BoardView(view.revision(), view.observedTick(),
                                view.filter(), view.page(), view.totalPages(), view.overflow(), false, rows));
                    }
                    countdownTick = moment.ticks();
                }
                return countdown;
            }
            if (receivedMoment != null && (moment.frozen() || moment.epoch() != receivedMoment.epoch()
                    || moment.ticks() < receivedMoment.ticks())) receivedMoment = null;
            expire();
            return panel;
        }
        public void receive(CompoundTag tag, long tick) { receive(tag, tick, false); }
        public void receive(CompoundTag tag, long tick, boolean frozenWorld) { receive(tag, new ClientMoment(Math.max(0, tick), 0, frozenWorld)); }
        public Panel display(long tick) { return display(tick, false); }
        public Panel display(long tick, boolean frozenWorld) { return display(new ClientMoment(tick, 0, frozenWorld)); }
        private void expire() {
            if (!expired) {
                List<PublicRow> rows = panel.view().rows().stream().map(row -> new PublicRow(row.rowId(), row.kind(),
                        row.carrierName(), row.routeName(), row.sourceName(), row.targetName(), row.relation(),
                        row.publicPhase(), State.STALE, OptionalLong.empty())).toList();
                BoardView old = panel.view();
                panel = new Panel(panel.townName(), new BoardView(old.revision(), old.observedTick(), old.filter(), old.page(), old.totalPages(), old.overflow(), true, rows));
                expired = true;
            }
        }
    }
}
