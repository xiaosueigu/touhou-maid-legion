package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.MaidManagerMod;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the chunks of "force loaded" maids loaded.
 *
 * <p>Uses Forge's {@link ForgeChunkManager#forceChunk} rather than the vanilla
 * {@code setChunkForced} for the per-owner bookkeeping: several maids can hold the same chunk,
 * and releasing one must not release the others'.
 *
 * <p><strong>A ticket is persistent, it does not die with the process.</strong> {@code forceChunk}
 * stores the ticket in the level's {@code ForcedChunksSavedData}, and Forge reinstates every
 * stored ticket when the level is loaded again - it does <em>not</em> skip a ticket whose owning
 * mod or entity is gone, and it does not drop anything on shutdown. A ticket that is never
 * released therefore keeps a fully entity-ticking chunk (plus the chunks around it) simulated
 * forever, for every subsequent start of that world, which is enough to drag down the whole
 * server's tick rate.
 *
 * <p>Three things follow from that, and they are the reason this class is written the way it is:
 * <ul>
 *   <li>Every ticket we take is recorded here, including the level it was taken in, so
 *       {@link #release} can always find it again. (Entity UUIDs are not unique per level: the
 *       same maid id can hold a ticket in the overworld and one in the nether.)</li>
 *   <li>{@link #validateTickets} runs while a level's stored tickets are being reinstated, and
 *       drops the ones which are not wanted any more - that is also what cleans up tickets left
 *       behind by older builds of this mod.</li>
 *   <li>A world damaged by an older build can hold several tickets for the same maid, so keeping
 *       them all is not enough. The ticket matching the chunk she is really in wins, and the
 *       others are released as soon as she is seen - see {@link #take} and
 *       {@link #dropRestoredExtras}.</li>
 * </ul>
 */
public final class ChunkLoadHelper {
    /** One ticket we hold: the level it lives in and the chunk it keeps loaded. */
    private record Ticket(ResourceKey<Level> dimension, ChunkPos chunk) {
    }

    /** maid UUID -> the ticket we are currently holding for her. */
    private static final Map<UUID, Ticket> HELD = new HashMap<>();

    /**
     * maid UUID -> the tickets that were reinstated from the level's saved data at load time and
     * that we decided to keep (see {@link #validateTickets}).
     *
     * <p>They are not in {@link #HELD} yet because the maid may be anywhere; they stay here until
     * she is seen, so that the ones which turn out to be stale can still be released like any
     * other ticket.
     */
    private static final Map<UUID, Set<Ticket>> RESTORED = new HashMap<>();

    private ChunkLoadHelper() {
    }

    /** Registers the level-load cleanup hook. Called once, during mod construction. */
    public static void registerForcedChunkValidation() {
        ForgeChunkManager.setForcedChunkLoadingCallback(
                MaidManagerMod.MOD_ID, ChunkLoadHelper::validateTickets);
    }

    /**
     * Holds {@code chunk} of {@code level} for a maid, replacing whatever we already held for her.
     *
     * <p>Takes the maid's UUID and chunk instead of the entity so the summon path can use it
     * before the maid entity exists again.
     */
    public static void take(ServerLevel level, UUID maidId, ChunkPos chunk) {
        Ticket previous = HELD.get(maidId);
        if (previous != null
                && previous.dimension().equals(level.dimension())
                && previous.chunk().equals(chunk)) {
            return;
        }
        if (previous != null) {
            HELD.remove(maidId);
            releaseTicket(level.getServer(), maidId, previous);
        }
        force(level, maidId, chunk, true);
        // Recorded even when forceChunk reported false: false means the ticket was already
        // registered (e.g. it was reinstated from the saved data while the level loaded), which
        // is not a failure - and forgetting it here is exactly how a ticket gets leaked.
        Ticket ticket = new Ticket(level.dimension(), chunk);
        HELD.put(maidId, ticket);
        dropRestoredExtras(level.getServer(), maidId, ticket);
    }

    /**
     * Holds the chunk containing {@code entity} for as long as the maid exists.
     * Safe to call repeatedly; only the first call for a given chunk does work.
     */
    public static void hold(ServerLevel level, Entity entity) {
        take(level, entity.getUUID(), entity.chunkPosition());
    }

    /** Releases the chunk held for this maid, if any. */
    public static void release(MinecraftServer server, UUID maidId) {
        Ticket held = HELD.remove(maidId);
        if (held != null) {
            releaseTicket(server, maidId, held);
        }
        Set<Ticket> restored = RESTORED.remove(maidId);
        if (restored != null) {
            for (Ticket ticket : restored) {
                releaseTicket(server, maidId, ticket);
            }
        }
    }

    private static void releaseTicket(MinecraftServer server, UUID maidId, Ticket ticket) {
        ServerLevel level = server == null ? null : server.getLevel(ticket.dimension());
        if (level == null) {
            MaidManagerMod.LOGGER.warn("Cannot release the force-load ticket of maid {} in {}: "
                    + "the dimension is not loaded any more", maidId, ticket.dimension().location());
            return;
        }
        force(level, maidId, ticket.chunk(), false);
    }

    private static void force(ServerLevel level, UUID maidId, ChunkPos chunk, boolean add) {
        try {
            ForgeChunkManager.forceChunk(
                    level, MaidManagerMod.MOD_ID, maidId, chunk.x, chunk.z, add, true);
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.warn("forceChunk(add={}) failed for maid {} in chunk {}: {}",
                    add, maidId, chunk, t.toString());
        }
    }

    /** True when we currently hold a chunk for this maid. */
    public static boolean isHeld(UUID maidId) {
        return HELD.containsKey(maidId) || RESTORED.containsKey(maidId);
    }

    /** Snapshot of the maids we hold a chunk for; safe to iterate while holding/releasing. */
    public static Set<UUID> heldMaids() {
        Set<UUID> maids = new HashSet<>(HELD.keySet());
        maids.addAll(RESTORED.keySet());
        return maids;
    }

    /**
     * Drops our in-memory bookkeeping. Called on server stop.
     *
     * <p>The tickets themselves are deliberately left alone: they are stored per mod id in the
     * level's saved data, and that is what brings a force-loaded maid back after a restart.
     * Tickets that stopped being wanted in the meantime are removed by {@link #validateTickets}
     * before they can take effect again.
     */
    public static void clear() {
        HELD.clear();
        RESTORED.clear();
    }

    /**
     * Forge's level-load hook: removes stored tickets that nothing wants any more.
     *
     * <p>Runs while the level's stored tickets are being reinstated, so a stale ticket never takes
     * effect - it is dropped before it would load (and tick) its chunk. A ticket is kept only
     * while its maid is still force-loaded for somebody.
     */
    private static void validateTickets(ServerLevel level, ForgeChunkManager.TicketHelper helper) {
        try {
            MaidRegistry registry = registryFor(level);
            // TicketHelper's maps are documented as not updating when tickets are removed, so
            // iterating them while removing is safe.
            for (Map.Entry<UUID, Pair<LongSet, LongSet>> entry
                    : helper.getEntityTickets().entrySet()) {
                UUID maidId = entry.getKey();
                if (!registry.isForceLoadByAnyone(maidId)) {
                    helper.removeAllTickets(maidId);
                    MaidManagerMod.LOGGER.info("Dropped the stale force-load ticket of maid {} in {}",
                            maidId, level.dimension().location());
                    continue;
                }
                // Kept: this is the ticket that brings her back after a restart. Remembered, so
                // that a second ticket for the same maid - which an older build leaked - can be
                // released as soon as we know where she really is.
                remember(level, maidId, entry.getValue().getFirst(), entry.getValue().getSecond());
            }
            // This mod only ever takes entity tickets, so a block ticket can only be left over
            // from an older build. Nothing records it, so it could never be released.
            for (BlockPos pos : helper.getBlockTickets().keySet()) {
                helper.removeAllTickets(pos);
                MaidManagerMod.LOGGER.info("Dropped the stale force-load ticket of block {} in {}",
                        pos, level.dimension().location());
            }
        } catch (Throwable t) {
            // A cleanup failure must never stop a world from loading.
            MaidManagerMod.LOGGER.warn("Force-load ticket validation failed for {}: {}",
                    level.dimension().location(), t.toString());
        }
    }

    /** Remembers tickets that were reinstated from the saved data and are still wanted. */
    private static void remember(ServerLevel level, UUID maidId, LongSet nonTicking, LongSet ticking) {
        Set<Ticket> chunks = new HashSet<>();
        for (long packed : nonTicking.toLongArray()) {
            chunks.add(new Ticket(level.dimension(), chunkOf(packed)));
        }
        for (long packed : ticking.toLongArray()) {
            chunks.add(new Ticket(level.dimension(), chunkOf(packed)));
        }
        if (!chunks.isEmpty()) {
            RESTORED.put(maidId, chunks);
        }
    }

    /**
     * Releases the reinstated tickets of a maid that are not the chunk she is actually in.
     *
     * <p>An older build took a fresh ticket for every force-load summon and never released it, so
     * a maid who was summoned a few times can hold several tickets. Only the one matching her
     * current chunk is still wanted; the others would keep an empty chunk ticking forever.
     */
    private static void dropRestoredExtras(MinecraftServer server, UUID maidId, Ticket current) {
        Set<Ticket> restored = RESTORED.remove(maidId);
        if (restored == null) {
            return;
        }
        for (Ticket ticket : restored) {
            if (!ticket.equals(current)) {
                releaseTicket(server, maidId, ticket);
            }
        }
    }

    private static ChunkPos chunkOf(long packed) {
        return new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed));
    }

    /**
     * The registry of the server the given level belongs to.
     *
     * <p>Normally that is the overworld's data storage, but this hook fires from inside a level's
     * own constructor - for the overworld, {@code server.overworld()} is not available yet, and
     * the level's own storage <em>is</em> the overworld storage. Hence the fallback.
     */
    private static MaidRegistry registryFor(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        DimensionDataStorage storage =
                overworld != null ? overworld.getDataStorage() : level.getDataStorage();
        return MaidRegistry.getFrom(storage);
    }
}
