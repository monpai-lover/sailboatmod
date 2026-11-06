package com.monpai.sailboatmod.transport.rail;

import com.monpai.sailboatmod.compat.ItemStackNbt;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Server-thread ownership of the active freight job and its order claims. */
public final class RailFreightSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_rail_freight";
    private static final Factory<RailFreightSavedData> FACTORY = new Factory<>(RailFreightSavedData::new, RailFreightSavedData::load);
    private static final int VERSION = 1;
    private static final int MAX_TRAINS = 256;
    private static final int MAX_MANIFEST_ENTRIES = 32;
    private static final int MAX_SCHEDULE_BYTES = 32_768;
    private static final int MAX_TEXT_LENGTH = 256;
    private static final Set<String> ROOT_KEYS = Set.of("Version", "Registrations", "Jobs");
    private static final Set<String> REGISTRATION_KEYS = Set.of("TrainId", "OwnerId", "Home", "TrainName", "Enabled");
    private static final Set<String> JOB_KEYS = Set.of("JobId", "TrainId", "Route", "Manifest", "OwnedSchedule", "Phase", "Status");
    private static final Set<String> STATION_KEYS = Set.of("DimensionId", "StationPos", "StationId");
    private static final Set<String> ROUTE_KEYS = Set.of("Source", "Target", "SourceTerminal", "TargetTerminal", "BuyerWarehouse", "DistanceMeters", "RouteName");
    private static final Set<String> MANIFEST_KEYS = Set.of("ListingId", "ItemStack", "PurchaseOrderId", "ShippingOrderId", "RecipientUuid", "RecipientName", "Quantity");

    public enum Phase { PREPARED, OUTBOUND, RETURN }

    public record Registration(UUID trainId, UUID ownerId, RailStationRef home, String trainName, boolean enabled) {
        public Registration {
            Objects.requireNonNull(trainId);
            Objects.requireNonNull(ownerId);
            validateStation(home);
            validateText(trainName, true);
        }
    }

    public record Job(UUID jobId, UUID trainId, RailRoute route, List<ShipmentManifestEntry> manifest,
                      CompoundTag ownedSchedule, Phase phase, String status) {
        public Job {
            Objects.requireNonNull(jobId);
            Objects.requireNonNull(trainId);
            Objects.requireNonNull(route);
            Objects.requireNonNull(phase);
            validateStation(route.source());
            validateStation(route.target());
            validateText(route.routeName(), false);
            validateText(status, true);
            validateManifest(manifest);
            if (ownedSchedule == null || ownedSchedule.isEmpty() != (phase == Phase.PREPARED)
                    || ownedSchedule.sizeInBytes() > MAX_SCHEDULE_BYTES) {
                throw invalid();
            }
            manifest = copyManifest(manifest);
            ownedSchedule = ownedSchedule.copy();
        }

        @Override
        public List<ShipmentManifestEntry> manifest() {
            return copyManifest(manifest);
        }

        @Override
        public CompoundTag ownedSchedule() {
            return ownedSchedule.copy();
        }
    }

    private final Map<UUID, Registration> registrations = new LinkedHashMap<>();
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Set<String> claimedPurchases = new HashSet<>();
    private final boolean readable;

    public RailFreightSavedData() {
        this(true);
    }

    private RailFreightSavedData(boolean readable) {
        this.readable = readable;
    }

    public static RailFreightSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static RailFreightSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            requireKeys(tag, ROOT_KEYS);
            requireType(tag, "Version", Tag.TAG_INT);
            if (tag.getInt("Version") != VERSION) throw invalid();

            RailFreightSavedData data = new RailFreightSavedData();
            for (Tag row : compoundList(tag, "Registrations", MAX_TRAINS)) {
                Registration entry = readRegistration((CompoundTag) row);
                if (data.registrations.putIfAbsent(entry.trainId(), entry) != null) throw invalid();
            }
            for (Tag row : compoundList(tag, "Jobs", MAX_TRAINS)) {
                Job job = readJob((CompoundTag) row, registries);
                if (!data.canClaim(job)) throw invalid();
                data.addJob(job);
            }
            return data;
        } catch (RuntimeException malformed) {
            // Losing a single row could free an order whose physical cargo is still aboard a train.
            LogUtils.getLogger().error("Rail freight saved data is invalid. Freight is disabled and the original data is preserved.");
            return new RailFreightSavedData(false);
        }
    }

    public boolean register(Registration registration) {
        if (!readable || registration == null || registrations.size() >= MAX_TRAINS
                || registrations.putIfAbsent(registration.trainId(), registration) != null) {
            return false;
        }
        setDirty();
        return true;
    }

    public @Nullable Registration registration(UUID trainId) {
        return registrations.get(trainId);
    }

    public List<Registration> registrations() {
        return List.copyOf(registrations.values());
    }

    public boolean setEnabled(UUID trainId, boolean enabled) {
        Registration current = registration(trainId);
        if (!readable || current == null) return false;
        if (current.enabled() != enabled) {
            registrations.put(trainId, new Registration(trainId, current.ownerId(), current.home(), current.trainName(), enabled));
            setDirty();
        }
        return true;
    }

    public boolean unregister(UUID trainId) {
        if (!readable || jobs.containsKey(trainId) || registrations.remove(trainId) == null) return false;
        setDirty();
        return true;
    }

    public boolean begin(Job job) {
        if (!readable || job == null || job.phase() != Phase.PREPARED || !canClaim(job)
                || !registrations.get(job.trainId()).enabled()) return false;
        addJob(job);
        setDirty();
        return true;
    }

    public @Nullable Job job(UUID trainId) {
        return jobs.get(trainId);
    }

    public List<Job> jobs() {
        return List.copyOf(jobs.values());
    }

    boolean claimsPurchase(String purchaseOrderId) {
        return !readable || claimedPurchases.contains(purchaseOrderId);
    }

    private void addJob(Job job) {
        jobs.put(job.trainId(), job);
        for (ShipmentManifestEntry entry : job.manifest) claimedPurchases.add(entry.purchaseOrderId());
    }

    public boolean replace(Job replacement) {
        if (!readable || replacement == null) return false;
        Job current = job(replacement.trainId());
        if (current == null || !current.jobId().equals(replacement.jobId())
                || !current.route().equals(replacement.route()) || !sameManifest(current.manifest, replacement.manifest)) return false;

        int step = replacement.phase().ordinal() - current.phase().ordinal();
        if (step < 0 || step > 1 || step == 0 && !current.ownedSchedule.equals(replacement.ownedSchedule)) return false;
        jobs.put(replacement.trainId(), replacement);
        setDirty();
        return true;
    }

    public boolean complete(UUID trainId, UUID jobId) {
        Job current = job(trainId);
        if (!readable || current == null || !current.jobId().equals(jobId)) return false;
        jobs.remove(trainId);
        for (ShipmentManifestEntry entry : current.manifest) claimedPurchases.remove(entry.purchaseOrderId());
        setDirty();
        return true;
    }

    private boolean canClaim(Job candidate) {
        Registration registered = registration(candidate.trainId());
        if (registered == null || jobs.containsKey(candidate.trainId()) || !registered.home().equals(candidate.route().source())) return false;

        Set<String> purchaseIds = new HashSet<>();
        Set<String> shippingIds = new HashSet<>();
        for (ShipmentManifestEntry entry : candidate.manifest) {
            purchaseIds.add(entry.purchaseOrderId());
            shippingIds.add(entry.shippingOrderId());
        }
        for (Job current : jobs.values()) {
            if (current.jobId().equals(candidate.jobId())) return false;
            for (ShipmentManifestEntry entry : current.manifest) {
                if (purchaseIds.contains(entry.purchaseOrderId()) || shippingIds.contains(entry.shippingOrderId())) return false;
            }
        }
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!readable) throw new IllegalStateException("Cannot rewrite invalid rail freight saved data");
        ListTag registrationTags = new ListTag();
        registrations.values().forEach(entry -> registrationTags.add(writeRegistration(entry)));
        ListTag jobTags = new ListTag();
        for (Job job : jobs.values()) jobTags.add(writeJob(job, registries));
        tag.putInt("Version", VERSION);
        tag.put("Registrations", registrationTags);
        tag.put("Jobs", jobTags);
        return tag;
    }

    private static CompoundTag writeRegistration(Registration entry) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("TrainId", entry.trainId());
        tag.putUUID("OwnerId", entry.ownerId());
        tag.put("Home", writeStation(entry.home()));
        tag.putString("TrainName", entry.trainName());
        tag.putBoolean("Enabled", entry.enabled());
        return tag;
    }

    private static Registration readRegistration(CompoundTag tag) {
        requireKeys(tag, REGISTRATION_KEYS);
        requireType(tag, "Enabled", Tag.TAG_BYTE);
        byte enabled = tag.getByte("Enabled");
        if (enabled != 0 && enabled != 1) throw invalid();
        return new Registration(uuid(tag, "TrainId"), uuid(tag, "OwnerId"), readStation(compound(tag, "Home")),
                string(tag, "TrainName"), enabled == 1);
    }

    private static CompoundTag writeJob(Job job, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("JobId", job.jobId());
        tag.putUUID("TrainId", job.trainId());
        tag.put("Route", writeRoute(job.route()));
        ListTag manifest = new ListTag();
        ItemStackNbt.withProvider(registries, () -> {
            job.manifest.forEach(entry -> manifest.add(entry.save()));
            return manifest;
        });
        tag.put("Manifest", manifest);
        tag.put("OwnedSchedule", job.ownedSchedule());
        tag.putString("Phase", job.phase().name());
        tag.putString("Status", job.status());
        return tag;
    }

    private static Job readJob(CompoundTag tag, HolderLookup.Provider registries) {
        requireKeys(tag, JOB_KEYS);
        ListTag entries = compoundList(tag, "Manifest", MAX_MANIFEST_ENTRIES);
        List<ShipmentManifestEntry> manifest = ItemStackNbt.withProvider(registries, () -> entries.stream()
                .map(raw -> readManifestEntry((CompoundTag) raw)).toList());
        return new Job(uuid(tag, "JobId"), uuid(tag, "TrainId"), readRoute(compound(tag, "Route")),
                manifest, compound(tag, "OwnedSchedule"), Phase.valueOf(string(tag, "Phase")), string(tag, "Status"));
    }

    private static CompoundTag writeStation(RailStationRef station) {
        CompoundTag tag = new CompoundTag();
        tag.putString("DimensionId", station.dimensionId());
        tag.putLong("StationPos", station.stationPos().asLong());
        tag.putUUID("StationId", station.stationId());
        return tag;
    }

    private static RailStationRef readStation(CompoundTag tag) {
        requireKeys(tag, STATION_KEYS);
        return new RailStationRef(string(tag, "DimensionId"), pos(tag, "StationPos"), uuid(tag, "StationId"));
    }

    private static CompoundTag writeRoute(RailRoute route) {
        CompoundTag tag = new CompoundTag();
        tag.put("Source", writeStation(route.source()));
        tag.put("Target", writeStation(route.target()));
        tag.putLong("SourceTerminal", route.sourceTerminal().asLong());
        tag.putLong("TargetTerminal", route.targetTerminal().asLong());
        tag.putLong("BuyerWarehouse", route.buyerWarehouse().asLong());
        tag.putInt("DistanceMeters", route.distanceMeters());
        tag.putString("RouteName", route.routeName());
        return tag;
    }

    private static RailRoute readRoute(CompoundTag tag) {
        requireKeys(tag, ROUTE_KEYS);
        requireType(tag, "DistanceMeters", Tag.TAG_INT);
        return new RailRoute(readStation(compound(tag, "Source")), readStation(compound(tag, "Target")),
                pos(tag, "SourceTerminal"), pos(tag, "TargetTerminal"), pos(tag, "BuyerWarehouse"),
                tag.getInt("DistanceMeters"), string(tag, "RouteName"));
    }

    private static ShipmentManifestEntry readManifestEntry(CompoundTag tag) {
        requireKeys(tag, MANIFEST_KEYS);
        requireType(tag, "ItemStack", Tag.TAG_COMPOUND);
        requireType(tag, "Quantity", Tag.TAG_INT);
        for (String name : List.of("ListingId", "PurchaseOrderId", "ShippingOrderId", "RecipientUuid", "RecipientName")) string(tag, name);
        return ShipmentManifestEntry.load(tag);
    }

    private static void validateManifest(List<ShipmentManifestEntry> manifest) {
        if (manifest == null || manifest.isEmpty() || manifest.size() > MAX_MANIFEST_ENTRIES) throw invalid();
        Set<String> purchases = new HashSet<>();
        Set<String> shippings = new HashSet<>();
        for (ShipmentManifestEntry entry : manifest) {
            if (entry == null || entry.itemStack().isEmpty() || entry.quantity() <= 0) throw invalid();
            validateText(entry.listingId(), false);
            validateText(entry.purchaseOrderId(), false);
            validateText(entry.shippingOrderId(), false);
            validateText(entry.recipientUuid(), false);
            validateText(entry.recipientName(), true);
            if (!UUID.fromString(entry.recipientUuid()).toString().equals(entry.recipientUuid())
                    || !purchases.add(entry.purchaseOrderId()) || !shippings.add(entry.shippingOrderId())) throw invalid();
        }
    }

    private static List<ShipmentManifestEntry> copyManifest(List<ShipmentManifestEntry> entries) {
        return entries.stream().map(entry -> new ShipmentManifestEntry(entry.listingId(), entry.itemStack(),
                entry.purchaseOrderId(), entry.shippingOrderId(), entry.recipientUuid(), entry.recipientName(), entry.quantity())).toList();
    }

    private static boolean sameManifest(List<ShipmentManifestEntry> left, List<ShipmentManifestEntry> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            ShipmentManifestEntry old = left.get(i);
            ShipmentManifestEntry next = right.get(i);
            if (old.quantity() != next.quantity() || !old.listingId().equals(next.listingId())
                    || !old.purchaseOrderId().equals(next.purchaseOrderId()) || !old.shippingOrderId().equals(next.shippingOrderId())
                    || !old.recipientUuid().equals(next.recipientUuid()) || !old.recipientName().equals(next.recipientName())
                    || !ItemStack.matches(old.itemStack(), next.itemStack())) return false;
        }
        return true;
    }

    private static ListTag compoundList(CompoundTag tag, String name, int limit) {
        if (!(tag.get(name) instanceof ListTag list) || list.size() > limit
                || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw invalid();
        return list;
    }

    private static CompoundTag compound(CompoundTag tag, String name) {
        requireType(tag, name, Tag.TAG_COMPOUND);
        return tag.getCompound(name);
    }

    private static BlockPos pos(CompoundTag tag, String name) {
        requireType(tag, name, Tag.TAG_LONG);
        return BlockPos.of(tag.getLong(name));
    }

    private static UUID uuid(CompoundTag tag, String name) {
        if (!tag.hasUUID(name)) throw invalid();
        return tag.getUUID(name);
    }

    private static String string(CompoundTag tag, String name) {
        requireType(tag, name, Tag.TAG_STRING);
        String value = tag.getString(name);
        validateText(value, true);
        return value;
    }

    private static void validateStation(RailStationRef station) {
        validateText(Objects.requireNonNull(station).dimensionId(), false);
    }

    private static void validateText(String value, boolean allowEmpty) {
        if (value == null || value.length() > MAX_TEXT_LENGTH || !allowEmpty && value.isBlank()
                || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
    }

    private static void requireKeys(CompoundTag tag, Set<String> keys) {
        if (tag == null || !tag.getAllKeys().equals(keys)) throw invalid();
    }

    private static void requireType(CompoundTag tag, String name, int type) {
        if (!tag.contains(name, type)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid rail freight data");
    }
}
