package com.dsh.maidmanager.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persists the per-maid "force load" switches, favourites, panel enrolment and the one-time
 * heavy-load acknowledgement.
 *
 * <p>Kept separate from {@link MaidStorage} because these have different lifecycles: a
 * maid's switch must survive being summoned and stored again, so it cannot live inside the
 * stored NBT.
 */
public final class MaidRegistry extends SavedData {
    private static final String DATA_ID = "touhou_maid_legion_registry";
    /**
     * The id this data was written under before the mod was renamed. Kept so an existing world can be migrated instead of silently starting empty.
     */
    private static final String LEGACY_DATA_ID = "maid_legion_registry";
    private static final String FORCE_LOAD = "ForceLoad";
    private static final String FAVOURITES = "Favourites";
    private static final String ENROLLED = "Enrolled";
    private static final String ACKNOWLEDGED = "Acknowledged";

    private final Map<UUID, Set<UUID>> forceLoad = new HashMap<>();
    private final Map<UUID, Set<UUID>> favourites = new HashMap<>();

    /**
     * Maids the player has explicitly opted into panel management by shift-right-clicking
     * with a gohei. Deliberately opt-in: the panel would otherwise silently take over every
     * maid the player owns, including ones they never intended to command from a GUI.
     */
    private final Map<UUID, Set<UUID>> enrolled = new HashMap<>();

    private final Set<UUID> acknowledged = new HashSet<>();

    public static MaidRegistry get(MinecraftServer server) {
        return getFrom(server.overworld().getDataStorage());
    }

    /**
     * Fetches the registry from one specific data storage, adopting pre-rename data.
     *
     * <p>Needed while a level is still being constructed: the force-load ticket validation hook in
     * {@link ChunkLoadHelper} fires from there, and for the overworld
     * {@link MinecraftServer#overworld()} does not answer yet. In that case the level's own storage
     * is the overworld's storage, so both paths lead to the same file.
     */
    public static MaidRegistry getFrom(
            net.minecraft.world.level.storage.DimensionDataStorage storage) {
        SavedData.Factory<MaidRegistry> factory =
                new SavedData.Factory<>(MaidRegistry::new, MaidRegistry::load);
        MaidRegistry live = storage.computeIfAbsent(factory, DATA_ID);
        MaidRegistry legacy = storage.get(factory, LEGACY_DATA_ID);
        if (adoptIfEmpty(live, legacy)) {
            live.setDirty();
        }
        return live;
    }

    /**
     * Copies pre-rename data in, but only into an empty registry.
     *
     * <p>Separated from {@link #get(MinecraftServer)} so the decision can be unit tested without
     * a server: the live registry can only hold something if the renamed build already wrote it,
     * and then that data must win, so refusing to adopt is always the safe direction.
     *
     * @return whether anything was adopted
     */
    public static boolean adoptIfEmpty(MaidRegistry live, MaidRegistry legacy) {
        if (legacy == null || !live.isEmpty()) {
            return false;
        }
        return live.adoptFrom(legacy);
    }

    public boolean isEmpty() {
        return forceLoad.isEmpty() && favourites.isEmpty() && enrolled.isEmpty()
                && acknowledged.isEmpty();
    }

    /**
     * One-time copy of everything a pre-rename registry held.
     *
     * <p>Copied into fresh sets so the two instances never share mutable state.
     */
    public boolean adoptFrom(MaidRegistry legacy) {
        if (legacy.isEmpty()) {
            return false;
        }
        legacy.forceLoad.forEach((owner, ids) -> forceLoad.put(owner, new HashSet<>(ids)));
        legacy.favourites.forEach((owner, ids) -> favourites.put(owner, new HashSet<>(ids)));
        legacy.enrolled.forEach((owner, ids) -> enrolled.put(owner, new HashSet<>(ids)));
        acknowledged.addAll(legacy.acknowledged);
        return true;
    }

    public static MaidRegistry load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        MaidRegistry registry = new MaidRegistry();
        readPerOwner(tag, FORCE_LOAD, registry.forceLoad);
        readPerOwner(tag, FAVOURITES, registry.favourites);
        readPerOwner(tag, ENROLLED, registry.enrolled);
        ListTag ack = tag.getList(ACKNOWLEDGED, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < ack.size(); i++) {
            int[] raw = ack.getIntArray(i);
            if (raw.length == 4) {
                registry.acknowledged.add(toUuid(raw));
            }
        }
        return registry;
    }

    /** Reads a {@code owner -> set of maid UUIDs} compound, skipping malformed entries. */
    private static void readPerOwner(CompoundTag tag, String key, Map<UUID, Set<UUID>> into) {
        CompoundTag root = tag.getCompound(key);
        for (String ownerKey : root.getAllKeys()) {
            UUID owner = parse(ownerKey);
            if (owner == null) {
                continue;
            }
            Set<UUID> maids = new HashSet<>();
            ListTag list = root.getList(ownerKey, Tag.TAG_INT_ARRAY);
            for (int i = 0; i < list.size(); i++) {
                int[] raw = list.getIntArray(i);
                if (raw.length == 4) {
                    maids.add(toUuid(raw));
                }
            }
            into.put(owner, maids);
        }
    }

    /**
     * 1.21 added the {@link net.minecraft.core.HolderLookup.Provider} parameter to
     * {@link SavedData#save}. This data is pure UUID bookkeeping, so the provider is unused;
     * the signature must still match for the {@code SavedData.Factory} to accept this method.
     */
    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        tag.put(FORCE_LOAD, writePerOwner(forceLoad));
        tag.put(FAVOURITES, writePerOwner(favourites));
        tag.put(ENROLLED, writePerOwner(enrolled));
        ListTag ack = new ListTag();
        acknowledged.forEach(owner -> ack.add(new net.minecraft.nbt.IntArrayTag(fromUuid(owner))));
        tag.put(ACKNOWLEDGED, ack);
        return tag;
    }

    private static CompoundTag writePerOwner(Map<UUID, Set<UUID>> source) {
        CompoundTag root = new CompoundTag();
        source.forEach((owner, maids) -> {
            ListTag list = new ListTag();
            maids.forEach(maid -> list.add(new net.minecraft.nbt.IntArrayTag(fromUuid(maid))));
            root.put(owner.toString(), list);
        });
        return root;
    }

    public boolean isForceLoad(UUID owner, UUID maidId) {
        Set<UUID> set = forceLoad.get(owner);
        return set != null && set.contains(maidId);
    }

    /**
     * True when <em>anybody</em> still keeps this maid force loaded.
     *
     * <p>Used to decide whether a stored chunk ticket is still wanted: one whose maid nobody has
     * switched on any more is stale, and keeping it would hold and tick its chunk forever.
     */
    public boolean isForceLoadByAnyone(UUID maidId) {
        for (Set<UUID> maids : forceLoad.values()) {
            if (maids.contains(maidId)) {
                return true;
            }
        }
        return false;
    }

    /** True when at least one maid is force loaded for somebody. */
    public boolean hasAnyForceLoad() {
        // An owner entry can exist with nothing in it (turning a switch off leaves the set
        // behind), so the empty ones must not count as a use.
        for (Set<UUID> maids : forceLoad.values()) {
            if (!maids.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public void setForceLoad(UUID owner, UUID maidId, boolean enabled) {
        Set<UUID> set = forceLoad.computeIfAbsent(owner, k -> new HashSet<>());
        if (enabled ? set.add(maidId) : set.remove(maidId)) {
            setDirty();
        }
    }

    // ------------------------------------------------------------------
    // Panel enrolment (shift-right-click a maid with a gohei)
    // ------------------------------------------------------------------

    /**
     * True when this maid is managed by the panel.
     *
     * <p>A maid the player owns but never enrolled is intentionally invisible to the
     * terminal: the panel commands maids (summon, store, revive), so silently including
     * every owned maid would let a mis-click move a maid the player never meant to manage.
     */
    public boolean isEnrolled(UUID owner, UUID maidId) {
        Set<UUID> set = enrolled.get(owner);
        return set != null && set.contains(maidId);
    }

    /** Enrols or removes a maid. Returns true when the state actually changed. */
    public boolean setEnrolled(UUID owner, UUID maidId, boolean value) {
        Set<UUID> set = enrolled.computeIfAbsent(owner, k -> new HashSet<>());
        boolean changed = value ? set.add(maidId) : set.remove(maidId);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /** Number of maids this player has enrolled, for the GUI's empty-state message. */
    public int enrolledCount(UUID owner) {
        Set<UUID> set = enrolled.get(owner);
        return set == null ? 0 : set.size();
    }

    /** Every enrolled maid id, used to filter the snapshot. */
    public Set<UUID> enrolledMaids(UUID owner) {
        Set<UUID> set = enrolled.get(owner);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    // ------------------------------------------------------------------
    // Favourites (starred maids, pinned to the top of the terminal)
    // ------------------------------------------------------------------

    public boolean isFavourite(UUID owner, UUID maidId) {
        Set<UUID> set = favourites.get(owner);
        return set != null && set.contains(maidId);
    }

    public void setFavourite(UUID owner, UUID maidId, boolean favourite) {
        Set<UUID> set = favourites.computeIfAbsent(owner, k -> new HashSet<>());
        if (favourite ? set.add(maidId) : set.remove(maidId)) {
            setDirty();
        }
    }

    /** Number of starred maids, so the GUI can enable or disable the filter button. */
    public int favouriteCount(UUID owner) {
        Set<UUID> set = favourites.get(owner);
        return set == null ? 0 : set.size();
    }

    public boolean isAcknowledged(UUID owner) {
        return acknowledged.contains(owner);
    }

    public void setAcknowledged(UUID owner) {
        if (acknowledged.add(owner)) {
            setDirty();
        }
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID toUuid(int[] raw) {
        return new UUID((long) raw[0] << 32 | (raw[1] & 0xFFFFFFFFL),
                (long) raw[2] << 32 | (raw[3] & 0xFFFFFFFFL));
    }

    private static int[] fromUuid(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        return new int[]{
                (int) (msb >> 32), (int) msb,
                (int) (lsb >> 32), (int) lsb
        };
    }
}
