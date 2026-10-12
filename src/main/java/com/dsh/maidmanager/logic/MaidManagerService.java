package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.Config;
import com.dsh.maidmanager.MaidManagerMod;
import com.dsh.maidmanager.util.MaidUtil;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.github.tartaricacid.touhoulittlemaid.util.PlaceHelper;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side brain of the maid terminal.
 *
 * <p>All operations are authoritative here. The client sends intents (a set of maid UUIDs)
 * and this class re-validates ownership against the live entity before doing anything, so a
 * malicious client cannot touch another player's maids.
 *
 * <p>Summoning picks the cheapest technique that works for each situation, which was derived
 * from reading TLM's own code:
 * <ul>
 *   <li>same dimension, loaded -&gt; {@code teleportToOwner}</li>
 *   <li>cross dimension -&gt; re-create from NBT (needs no chunk loading at all, because
 *       {@code MaidWorldData} is an overworld singleton that already knows the maid)</li>
 *   <li>same dimension but unloaded -&gt; the maid's own entity must be brought back, which
 *       is the only case that genuinely benefits from a temporary chunk ticket</li>
 * </ul>
 */
public final class MaidManagerService {
    /**
     * A box large enough to contain every loaded entity in a level. Using one shared
     * constant avoids rebuilding a huge AABB per query.
     */
    private static final net.minecraft.world.phys.AABB ALL_ENTITIES = new net.minecraft.world.phys.AABB(
            -3.0E7D, -2048.0D, -3.0E7D, 3.0E7D, 2048.0D, 3.0E7D);

    private MaidManagerService() {
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Builds the full snapshot list shown in the GUI. */
    public static List<MaidEntry> snapshot(ServerPlayer player) {
        MaidRegistry registry = MaidRegistry.get(player.getServer());
        MaidStorage storage = MaidStorage.get(player.getServer());
        Map<UUID, MaidEntry> byId = new LinkedHashMap<>();
        // Experience is read at the point we already have each maid's NBT or entity in hand, so
        // decorating the roster afterwards costs no extra lookups.
        Map<UUID, Integer> experienceById = new HashMap<>();

        // 1) Loaded maids in the player's current dimension.
        ServerLevel level = player.serverLevel();
        for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, ALL_ENTITIES)) {
            if (maid.isOwnedBy(player)) {
                byId.putIfAbsent(maid.getUUID(), present(maid, player, registry));
                experienceById.put(maid.getUUID(), maid.getExperience());
            }
        }

        // 2) Stored maids (our own NBT records) - always summonable.
        for (MaidStorage.StoredMaid stored : storage.list(player.getUUID())) {
            experienceById.put(stored.id(), stored.data().getInt(EntityMaid.EXPERIENCE_TAG));
            byId.putIfAbsent(stored.id(), new MaidEntry(
                    stored.id(),
                    Component.literal(stored.name().isEmpty() ? "Maid" : stored.name()),
                    MaidState.STORED,
                    "",
                    BlockPos.ZERO,
                    -1.0F,
                    -1.0F,
                    registry.isForceLoad(player.getUUID(), stored.id()),
                    registry.isAcknowledged(player.getUUID()),
                    true,
                    stored.storedAt(),
                    registry.isFavourite(player.getUUID(), stored.id())));
        }

        // 3) Maids TLM knows about but which are not in this world right now.
        //    Verified: onRemovedFromWorld -> addInfo, onAddedToWorld -> removeInfo,
        //    so this list is exactly "currently unloaded".
        for (MaidInfo info : MaidUtil.getUnloadedMaidInfos(player)) {
            UUID id = info.getEntityId();
            if (id == null || byId.containsKey(id)) {
                continue;
            }
            Component name = info.getName();
            byId.put(id, new MaidEntry(
                    id,
                    name == null ? Component.literal("Maid") : name,
                    MaidState.UNLOADED,
                    info.getDimension() == null ? "?" : info.getDimension(),
                    info.getChunkPos() == null ? BlockPos.ZERO : info.getChunkPos(),
                    -1.0F,
                    -1.0F,
                    registry.isForceLoad(player.getUUID(), id),
                    registry.isAcknowledged(player.getUUID()),
                    info.getDimension() != null && MaidUtil.isSameDimension(player, info.getDimension()),
                    0L,
                    registry.isFavourite(player.getUUID(), id)));
        }

        // 3b) Dead maids we captured instead of leaving a tombstone. Recorded before TLM's own
        //     world data is consulted, so a captured death never shows up as "unloaded" too.
        for (MaidDeathStorage.DeadMaid dead : MaidDeathStorage.get(player.getServer()).list(player.getUUID())) {
            experienceById.put(dead.id(), dead.data().getInt(EntityMaid.EXPERIENCE_TAG));
            byId.put(dead.id(), new MaidEntry(
                    dead.id(),
                    Component.literal(dead.name().isEmpty() ? "Maid" : dead.name()),
                    MaidState.DEAD,
                    "",
                    BlockPos.ZERO,
                    -1.0F,
                    -1.0F,
                    false,
                    registry.isAcknowledged(player.getUUID()),
                    true,
                    dead.diedAt(),
                    registry.isFavourite(player.getUUID(), dead.id()),
                    dead.deathCount(),
                    dead.withItems()));
        }

        // 4) Keep only maids the player explicitly enrolled (gohei shift-right-click).
        //
        //    Filtering here, at the single point where the snapshot is assembled, means every
        //    consumer - the GUI, the hotkey scope, the /maidlegion command - sees exactly the
        //    same roster. Doing it per-caller would let the hotkey act on maids the panel
        //    never showed, which is precisely the accident enrolment exists to prevent.
        Set<UUID> allowed = registry.enrolledMaids(player.getUUID());
        MaidProgressStorage progress = MaidProgressStorage.get(player.getServer());
        List<MaidEntry> result = new ArrayList<>(byId.size());
        for (MaidEntry entry : byId.values()) {
            if (allowed.contains(entry.id)) {
                // UNLOADED maids report 0: their NBT is inside TLM's world data, unreachable from
                // here, and the upgrade service refuses to sell them anything for the same reason.
                int experience = experienceById.getOrDefault(entry.id, 0);
                MaidProgressStorage.ActiveCast cast = progress.activeCast(entry.id);
                result.add(entry.withProgression(experience, levelsArray(progress, entry.id),
                        cast == null ? 0L : cast.endsAt(),
                        cast == null ? 0 : cast.totalTicks()));
            }
        }
        return result;
    }

    /** The stored levels of one maid as a dense array indexed by {@link MaidUpgrade#ordinal()}. */
    private static int[] levelsArray(MaidProgressStorage progress, UUID maidId) {
        MaidUpgrade[] upgrades = MaidUpgrade.values();
        int[] levels = new int[upgrades.length];
        Map<String, Integer> stored = progress.levelsOf(maidId);
        if (stored.isEmpty()) {
            return levels;
        }
        for (MaidUpgrade upgrade : upgrades) {
            levels[upgrade.ordinal()] = stored.getOrDefault(upgrade.id(), 0);
        }
        return levels;
    }

    /** The player-wide half of the snapshot: wallet, bank and owned abilities. */
    public static ProgressionInfo progression(ServerPlayer player) {
        MaidProgressStorage progress = MaidProgressStorage.get(player.getServer());
        GlobalUpgrade[] abilities = GlobalUpgrade.values();
        int[] states = new int[abilities.length];
        for (GlobalUpgrade ability : abilities) {
            if (!progress.hasGlobal(player.getUUID(), ability)) {
                states[ability.ordinal()] = ProgressionInfo.NOT_OWNED;
            } else if (progress.isAbilityEnabled(player.getUUID(), ability)) {
                states[ability.ordinal()] = ProgressionInfo.OWNED_ACTIVE;
            } else {
                states[ability.ordinal()] = ProgressionInfo.OWNED_DISABLED;
            }
        }
        return new ProgressionInfo(
                MaidProgressionService.walletOf(player),
                progress.banked(player.getUUID()),
                MaidProgressionService.bankCap(),
                progress.autoDepositEnabled(player.getUUID()),
                states,
                MaidProgressionService.shrinesHeld(player),
                MaidProgressionService.boundLamp(player) != null);
    }

    /** True when this maid is enrolled and therefore visible to the panel. */
    public static boolean isEnrolled(ServerPlayer player, UUID maidId) {
        return MaidRegistry.get(player.getServer()).isEnrolled(player.getUUID(), maidId);
    }


    // ------------------------------------------------------------------
    // Revival
    // ------------------------------------------------------------------

    /**
     * The materials TLM's own {@code reborn_maid} altar recipe costs, mirrored exactly so the
     * panel is not a cheaper route than the altar.
     *
     * <p>Read from {@code data/touhou_little_maid/recipes/altar/reborn_maid.json}: a film, plus
     * one each of lapis, gold, redstone, iron and coal. We charge items rather than the
     * recipe's {@code power} of 0.5 because the altar's power is drawn from the multiblock,
     * which the panel has no access to.
     */
    private static final List<ItemStack> REVIVE_MATERIALS = List.of(
            new ItemStack(net.minecraft.world.item.Items.LAPIS_LAZULI),
            new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT),
            new ItemStack(net.minecraft.world.item.Items.REDSTONE),
            new ItemStack(net.minecraft.world.item.Items.IRON_INGOT),
            new ItemStack(net.minecraft.world.item.Items.COAL));

    /** The material list the GUI shows, with the amounts the player must supply. */
    public static List<ItemStack> reviveMaterials() {
        return REVIVE_MATERIALS;
    }

    /** True when the player has every material (the film is supplied by the captured data). */
    public static boolean hasReviveMaterials(ServerPlayer player) {
        for (ItemStack required : REVIVE_MATERIALS) {
            if (!player.getInventory().hasAnyMatching(s -> s.is(required.getItem())
                    && s.getCount() >= required.getCount())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Consumes the altar materials.
     *
     * @return true when payment succeeded and something was actually consumed
     */
    private static boolean payForRevive(ServerPlayer player) {
        if (!hasReviveMaterials(player)) {
            return false;
        }
        for (ItemStack required : REVIVE_MATERIALS) {
            int remaining = required.getCount();
            for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(required.getItem())) {
                    int take = Math.min(remaining, stack.getCount());
                    stack.shrink(take);
                    remaining -= take;
                }
            }
        }
        return true;
    }

    /**
     * Revives a fallen maid: validates, takes the materials, and puts her back immediately.
     *
     * <p>There is no cast time on this route. The waiting period and the shrine alternative
     * were both specified as part of the upgrade panel, which does not exist yet, so reviving
     * here is a straight swap - materials in, maid out. Payment and placement therefore happen
     * in one step, which is also why nothing needs to be persisted mid-revive: there is no
     * window in which the player has paid but the maid has not come back.
     *
     * @return a result describing what happened, so the GUI can report it
     */
    public static ReviveResult beginRevive(ServerPlayer player, UUID maidId) {
        // Checked separately rather than through canControl, because "not yours" and "not
        // enrolled" are different problems with different fixes, and collapsing them into one
        // refusal produced a message that sent the player looking for the wrong cause.
        if (!ownsMaid(player, maidId)) {
            return ReviveResult.NOT_OWNER;
        }
        if (!isEnrolled(player, maidId)) {
            return ReviveResult.NOT_ENROLLED;
        }
        MaidDeathStorage storage = MaidDeathStorage.get(player.getServer());
        MaidDeathStorage.DeadMaid dead = storage.get(player.getUUID(), maidId);
        if (dead == null) {
            return ReviveResult.NOT_DEAD;
        }
        if (!payForRevive(player)) {
            return ReviveResult.NEED_MATERIALS;
        }
        if (!finishRevive(player, maidId)) {
            // Placement failed: give the materials back so the attempt is not wasted.
            refundRevive(player);
            return ReviveResult.FAILED;
        }
        player.displayClientMessage(Component.translatable("message.touhou_maid_legion.revive_done",
                dead.name()), true);
        return ReviveResult.STARTED;
    }

    /**
     * Rebuilds the maid from her captured NBT next to the player.
     *
     * <p>Her own four inventories, armour, both hands, experience and favour all travel inside
     * the snapshot, because it is taken before TLM moves anything off her. Two things do not:
     *
     * <ul>
     *   <li><b>Curios.</b> They live in a capability, not in her NBT, so they ride along in the
     *       snapshot under {@link CuriosAccess#CURIOS_TAG} and are put back in their original
     *       slots here.</li>
     *   <li><b>Anything else that tried to drop.</b> Held back by the death handler and handed
     *       back below, so a mod that adds death loot for maids cannot spill it either.</li>
     * </ul>
     */
    /**
     * Shrines needed to unlock the shrine revival route, once.
     *
     * <p>Read from the ability rather than repeated here, so the price the panel shows and the
     * price actually charged cannot drift apart. This is an unlock fee, not a per-revive cost:
     * once bought, channelling is free.
     */
    public static final int SHRINE_REVIVE_COST = GlobalUpgrade.SHRINE_REVIVE.shrineCost();

    /**
     * Starts the shrine revival route: three shrines up front, then a channelled cast.
     *
     * <p>Charging before the cast is deliberate - the player is committing to the wait - which is
     * exactly why the cast is persisted rather than kept in memory. A restart mid-cast would
     * otherwise swallow the shrines.
     *
     * <p>Unlike the material route this one does not place her immediately, so it must not be
     * used as a cheaper way to skip the altar: it costs three shrine blocks instead.
     */
    public static ReviveResult beginShrineRevive(ServerPlayer player, UUID maidId) {
        if (!ownsMaid(player, maidId)) {
            return ReviveResult.NOT_OWNER;
        }
        if (!isEnrolled(player, maidId)) {
            return ReviveResult.NOT_ENROLLED;
        }
        if (MaidDeathStorage.get(player.getServer()).get(player.getUUID(), maidId) == null) {
            return ReviveResult.NOT_DEAD;
        }
        MaidProgressStorage progress = MaidProgressStorage.get(player.getServer());
        if (progress.activeCast(maidId) != null) {
            return ReviveResult.ALREADY_CASTING;
        }
        long now = player.getServer().overworld().getGameTime();
        int ticks = ReviveCast.castTicksFor(currentHeat(progress, maidId));
        progress.setActiveCast(maidId,
                new MaidProgressStorage.ActiveCast(player.getUUID(), now + ticks, ticks));
        player.displayClientMessage(Component.translatable(
                "message.touhou_maid_legion.shrine_cast_started",
                Math.round(ticks / 20.0F)), true);
        return ReviveResult.STARTED_CASTING;
    }

    /** A maid's heat as of now, cooled, or 0 when she has no record. */
    private static int currentHeat(MaidProgressStorage progress, UUID maidId) {
        MaidProgressStorage.DeathHeat heat = progress.deathHeat(maidId);
        if (heat == null) {
            return 0;
        }
        return ReviveCast.decayHeat(heat.heat(), heat.at(), System.currentTimeMillis());
    }

    /** The cast in progress for a maid, or null. Used by the snapshot and the panel. */
    public static MaidProgressStorage.ActiveCast activeCast(MinecraftServer server, UUID maidId) {
        return MaidProgressStorage.get(server).activeCast(maidId);
    }

    /**
     * Advances every shrine revival. Called each server tick.
     *
     * <p>A cast that is due but whose owner is offline is <em>left pending</em> and retried on a
     * later tick rather than cancelled: the shrines are already spent, so dropping the cast would
     * destroy them for nothing. It completes as soon as they are back.
     */
    public static void tickReviveCasts(MinecraftServer server) {
        MaidProgressStorage progress = MaidProgressStorage.get(server);
        java.util.Map<UUID, MaidProgressStorage.ActiveCast> casts = progress.activeCasts();
        if (casts.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (java.util.Map.Entry<UUID, MaidProgressStorage.ActiveCast> entry : casts.entrySet()) {
            UUID maidId = entry.getKey();
            MaidProgressStorage.ActiveCast cast = entry.getValue();
            if (now < cast.endsAt()) {
                continue;
            }
            ServerPlayer owner = server.getPlayerList().getPlayer(cast.owner());
            if (owner == null) {
                // Offline: keep the cast so the shrines are not lost. Retried every tick.
                continue;
            }
            MaidDeathStorage.DeadMaid record =
                    MaidDeathStorage.get(server).get(owner.getUUID(), maidId);
            if (record == null) {
                // Her record is gone; there is nothing left to revive. Nothing was paid per
                // revive, so there is nothing to hand back either.
                progress.clearActiveCast(maidId);
                owner.displayClientMessage(Component.translatable(
                        "message.touhou_maid_legion.shrine_cast_refunded"), true);
                continue;
            }
            // Captured before finishRevive, which deletes the record.
            String name = record.name();
            try {
                if (finishRevive(owner, maidId)) {
                    progress.clearActiveCast(maidId);
                    owner.displayClientMessage(Component.translatable(
                            "message.touhou_maid_legion.revive_done", name), true);
                } else {
                    progress.clearActiveCast(maidId);
                    owner.displayClientMessage(Component.translatable(
                            "message.touhou_maid_legion.shrine_cast_failed"), true);
                }
            } catch (Throwable t) {
                MaidManagerMod.LOGGER.error("Shrine revival failed for maid {}", maidId, t);
                progress.clearActiveCast(maidId);
            }
        }
    }

    private static boolean finishRevive(ServerPlayer player, UUID maidId) {
        MaidDeathStorage storage = MaidDeathStorage.get(player.getServer());
        MaidDeathStorage.DeadMaid dead = storage.get(player.getUUID(), maidId);
        if (dead == null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        BlockPos target = findSpawnPos(level, player);
        if (target == null) {
            return false;
        }
        try {
            EntityMaid maid = InitEntities.MAID.get().create(level);
            if (maid == null) {
                return false;
            }
            CompoundTag data = dead.data().copy();
            maid.load(data);
            maid.setUUID(maidId);
            maid.moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                    player.getYRot(), 0.0F);
            maid.setDeltaMovement(0.0D, 0.0D, 0.0D);
            // She comes back at full health; a revive that returned her at 1 HP would just get
            // her killed again straight away.
            maid.setHealth(maid.getMaxHealth());
            level.addFreshEntity(maid);
            maid.spawnExplosionParticle();

            // Curios must go back after load(), so the capability exists on a live entity.
            CuriosAccess.restoreInto(maid, data);
            returnHeldItems(player, maid, data);

            // The snapshot was taken before TLM applied its death penalty, so restoring it hands
            // back points TLM meant to take. Put the penalty back, minus the legion ability that
            // waives it. Must run after load(), or it is overwritten.
            MaidProgressionService.applyDeathPenalty(maid);
            // Attribute modifiers are transient; the join hook would also cover this, but doing it
            // here means her panel stats are already correct the instant she is back.
            MaidProgressionService.applyTo(maid);

            storage.remove(player.getUUID(), maidId);
            // Re-register with TLM so she is tracked as a live maid again.
            MaidUtil.registerMaid(maid);
            return true;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Revive failed for maid {}", maidId, t);
            return false;
        }
    }

    /**
     * Hands back stacks that were stopped from hitting the ground.
     *
     * <p>They go into her inventory; anything that will not fit is dropped at the player's feet
     * rather than deleted, so a full inventory can never silently eat them.
     */
    private static void returnHeldItems(ServerPlayer player, EntityMaid maid, CompoundTag data) {
        net.minecraft.nbt.ListTag held = MaidDeathHandler.extraItemsOf(data);
        if (held.isEmpty()) {
            return;
        }
        var inv = maid.getMaidInv();
        for (int i = 0; i < held.size(); i++) {
            ItemStack stack = ItemStack.of(held.getCompound(i));
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack leftover = net.minecraftforge.items.ItemHandlerHelper
                    .insertItemStacked(inv, stack, false);
            if (!leftover.isEmpty()) {
                player.drop(leftover, false);
                MaidManagerMod.LOGGER.info("Maid {} revived with a full inventory; "
                        + "a stack was dropped at the player", maid.getUUID());
            }
        }
    }

    /**
     * Gives back whatever {@link #payForRevive} took.
     *
     * <p>Overflow is dropped at the player's feet rather than discarded, so a full inventory
     * cannot quietly eat the refund.
     */
    private static void refundRevive(ServerPlayer owner) {
        for (ItemStack required : REVIVE_MATERIALS) {
            ItemStack copy = required.copy();
            if (!owner.getInventory().add(copy)) {
                owner.drop(copy, false);
            }
        }
    }

    /** Outcome of asking to revive, so the caller can pick the right message. */
    public enum ReviveResult {
        /** The shrine route needs three shrines and the player does not have them. */
        NEED_SHRINES,
        /** A shrine revival is already channelling for this maid. */
        ALREADY_CASTING,
        /** The shrine route was started; see {@code activeCast} for how long it takes. */
        STARTED_CASTING,
        STARTED,
        /** She is not this player's maid at all. */
        NOT_OWNER,
        /** She is the player's, but was never enrolled in the legion. */
        NOT_ENROLLED,
        NOT_DEAD,
        NEED_MATERIALS,
        /** Payment went through but she could not be placed; the materials were refunded. */
        FAILED
    }

    private static MaidEntry present(EntityMaid maid, ServerPlayer player, MaidRegistry registry) {
        return new MaidEntry(
                maid.getUUID(),
                maid.getDisplayName(),
                MaidState.PRESENT,
                MaidUtil.dimensionOf(maid),
                maid.blockPosition(),
                maid.getHealth(),
                maid.getMaxHealth(),
                registry.isForceLoad(player.getUUID(), maid.getUUID()),
                registry.isAcknowledged(player.getUUID()),
                true,
                0L,
                registry.isFavourite(player.getUUID(), maid.getUUID()));
    }

    /** Finds a loaded maid owned by the player, searching every level. */
    @Nullable
    public static EntityMaid findLoadedMaid(ServerPlayer player, UUID maidId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(maidId);
            if (entity instanceof EntityMaid maid && maid.isOwnedBy(player)) {
                return maid;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Result of an attempted action, used to build the feedback message. */
    public record Result(int success, int failed, @Nullable String reasonKey) {
        public static Result ok(int count) {
            return new Result(count, 0, null);
        }
    }

    /** Stores a loaded maid into {@link MaidStorage}. Returns true on success. */
    public static boolean store(ServerPlayer player, UUID maidId) {
        if (!canControl(player, maidId)) {
            return false;
        }
        EntityMaid maid = findLoadedMaid(player, maidId);
        if (maid == null) {
            return false;
        }
        try {
            CompoundTag data = new CompoundTag();
            // Exactly what TLM's smart-slab does, so equipment/model/task/inventories survive.
            maid.saveWithoutId(data);
            String name = maid.getDisplayName().getString();
            MaidStorage.get(player.getServer()).put(player.getUUID(), maidId, data, name);
            maid.discard();
            return true;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Failed to store maid {}", maidId, t);
            return false;
        }
    }

    /**
     * Summons a stored maid back into the world next to the player.
     * Requires no chunk loading because the NBT is already in hand.
     */
    public static boolean releaseStored(ServerPlayer player, UUID maidId) {
        if (!canControl(player, maidId)) {
            // Say which half refused: "not yours" and "not enrolled" have different fixes, and
            // without this the panel just reports an unrelated chunk-loading problem.
            MaidManagerMod.LOGGER.warn(
                    "Summon {} refused: owns={} enrolled={}", maidId,
                    ownsMaid(player, maidId), isEnrolled(player, maidId));
            return false;
        }
        MaidStorage storage = MaidStorage.get(player.getServer());
        MaidStorage.StoredMaid stored = storage.get(player.getUUID(), maidId);
        if (stored == null) {
            MaidManagerMod.LOGGER.warn(
                    "Summon {} refused: not in the store (this player has {} stored)",
                    maidId, storage.countFor(player.getUUID()));
            return false;
        }
        ServerLevel level = player.serverLevel();
        BlockPos target = findSpawnPos(level, player);
        if (target == null) {
            return false;
        }
        try {
            EntityMaid maid = InitEntities.MAID.get().create(level);
            if (maid == null) {
                return false;
            }
            // load() restores the full entity; moveTo then places it.
            maid.load(stored.data().copy());
            maid.moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, player.getYRot(), 0.0F);
            maid.setDeltaMovement(0.0D, 0.0D, 0.0D);
            level.addFreshEntity(maid);
            maid.spawnExplosionParticle();
            storage.remove(player.getUUID(), maidId);
            return true;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Failed to release maid {}", maidId, t);
            return false;
        }
    }

    /**
     * Teleports an already-loaded maid to the player, clearing any state that would block
     * movement. Mirrors the checks TLM's own unload mixin performs.
     */
    public static boolean summonLoaded(ServerPlayer player, EntityMaid maid) {
        if (!canControl(player, maid.getUUID())) {
            return false;
        }
        try {
            MaidUtil.clearBlockingStates(maid);
            MaidUtil.disableHomeMode(maid);
            maid.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.GLOWING, 200, 1, true, false));

            if (maid.level() != player.level()) {
                return summonCrossDimension(player, maid);
            }
            if (maid.teleportToOwner(player)) {
                return true;
            }
            // Fall back to an explicit position when TLM's own placement search fails.
            BlockPos pos = findSpawnPos(player.serverLevel(), player);
            if (pos == null) {
                return false;
            }
            maid.teleportTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            return true;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Failed to summon maid {}", maid.getUUID(), t);
            return false;
        }
    }

    /**
     * Moves a maid across dimensions by round-tripping her through NBT.
     *
     * <p>Chosen over {@code changeDimension} because the bytecode of
     * {@code EntityMaid.changeDimension} shows it does not actually switch levels - it only
     * re-runs a placement search 16 times - so the vanilla/Forge path is required anyway, and
     * the NBT route additionally avoids touching chunk tickets.
     */
    private static boolean summonCrossDimension(ServerPlayer player, EntityMaid maid) {
        try {
            CompoundTag data = new CompoundTag();
            maid.saveWithoutId(data);
            UUID id = maid.getUUID();
            maid.discard();

            ServerLevel level = player.serverLevel();
            BlockPos pos = findSpawnPos(level, player);
            if (pos == null) {
                // Put her back rather than deleting her.
                restoreInPlace(maid, data);
                return false;
            }
            EntityMaid moved = InitEntities.MAID.get().create(level);
            if (moved == null) {
                return false;
            }
            moved.load(data);
            moved.setUUID(id);
            moved.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, player.getYRot(), 0.0F);
            level.addFreshEntity(moved);
            moved.spawnExplosionParticle();
            return true;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Cross-dimension summon failed for {}", maid.getUUID(), t);
            return false;
        }
    }

    private static void restoreInPlace(EntityMaid old, CompoundTag data) {
        try {
            ServerLevel level = (ServerLevel) old.level();
            EntityMaid restored = InitEntities.MAID.get().create(level);
            if (restored != null) {
                restored.load(data);
                level.addFreshEntity(restored);
            }
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Failed to restore maid after aborted teleport", t);
        }
    }

    /**
     * Finds a safe standing position near the player.
     *
     * <p>Reuses TLM's own {@code PlaceHelper} check so we accept exactly the spots TLM
     * would, then falls back to a small spiral search.
     */
    @Nullable
    /**
     * A spot to put a released maid, never null when the player is standing somewhere valid.
     *
     * <p>Three passes, from strict to permissive, and finally the player's own position. The
     * strict test is TLM's own placement helper, which is more particular than "can an entity
     * stand here"; when it vetoed every candidate the release silently failed, the player was
     * shown an unrelated "her chunk is not loaded" message, and a maid he had paid to store was
     * stranded with no button able to bring her back. Refusing to place her because no <em>ideal</em>
     * spot exists is far worse than placing her slightly awkwardly: she can be moved afterwards.
     */
    public static BlockPos findSpawnPos(ServerLevel level, ServerPlayer player) {
        BlockPos origin = player.blockPosition();
        if (isSafe(level, origin)) {
            return origin;
        }
        for (int radius = 1; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    for (int dy = 1; dy >= -1; dy--) {
                        BlockPos candidate = origin.offset(dx, dy, dz);
                        if (isSafe(level, candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Whether a maid can be released here: room at her feet and head, and something to stand on.
     *
     * <p>TLM's {@code PlaceHelper.notSuitableForPlaceMaid} used to be consulted first, but it is
     * not an extra rule. Called with {@code pos.below()} it resolves to
     * {@code getCollisionShape(pos).isEmpty() && getCollisionShape(pos.above()).isEmpty()} - the
     * same two blocks, through the same call, on the same level - so it could only ever agree with
     * the two checks below. It read like a third condition and was not one.
     */
    /**
     * Whether there is no ground for a released maid to stand on.
     *
     * <p>A maid is placed on the ground, so a flying player is surrounded by nothing but air and
     * every candidate position is rejected. That is the ordinary case here, not an edge case:
     * creative flight is the whole reason this mod exists. The player is asked to land rather than
     * being left with a refusal that blames an unloaded chunk.
     *
     * <p>The world is asked rather than the movement flag, because {@code onGround} is briefly
     * false after landing and while standing on an entity or a boat.
     */
    public static boolean isAirborne(ServerPlayer player) {
        if (player.getAbilities().flying) {
            return true;
        }
        return !isSafe(player.serverLevel(), player.blockPosition());
    }
    private static boolean isSafe(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) {
            return false;
        }
        BlockPos below = pos.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                || !level.getFluidState(below).isEmpty();
    }

    // ------------------------------------------------------------------
    // Force-load switch
    // ------------------------------------------------------------------

    /**
     * Maids waiting for their chunk to come back so they can be summoned.
     *
     * <p>Chunk loading is asynchronous, so a summon cannot complete in one tick. We remember
     * the request, try again each tick for a bounded time, and report failure if the maid
     * never shows up.
     */
    private record PendingSummon(UUID ownerId, UUID maidId, long deadline) {
    }

    private static final Map<UUID, PendingSummon> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Asks Forge to load the maid's chunk, remembering the request so the summon can finish
     * once the entity is back. Returns false when the maid cannot be located at all.
     */
    public static boolean beginForceLoadSummon(ServerPlayer player, UUID maidId) {
        if (!canControl(player, maidId)) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        String dimension = null;
        BlockPos pos = null;
        for (MaidInfo info : MaidUtil.getUnloadedMaidInfos(player)) {
            if (maidId.equals(info.getEntityId())) {
                dimension = info.getDimension();
                pos = info.getChunkPos();
                break;
            }
        }
        if (dimension == null || pos == null) {
            return false;
        }
        ServerLevel target = dimensionLevel(server, dimension);
        if (target == null) {
            return false;
        }
        long deadline = server.getTickCount() + Config.COMMON.forceLoadTimeoutTicks.get();
        PENDING.put(maidId, new PendingSummon(player.getUUID(), maidId, deadline));

        // Hold the chunk. ForgeChunkManager needs an owner and the maid does not exist yet, so
        // the ticket is keyed on her UUID, which is stable across the reload. ChunkLoadHelper
        // records it as well, so that it is released again when the summon finishes or times
        // out: a ticket nobody releases outlives the server process and keeps its chunk
        // entity-ticking forever.
        ChunkLoadHelper.take(target, maidId, new net.minecraft.world.level.ChunkPos(pos));
        return true;
    }

    /** Resolves a dimension id such as {@code minecraft:the_nether} to a level. */
    @Nullable
    private static ServerLevel dimensionLevel(MinecraftServer server, String dimensionId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimensionId)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Advances pending force-load summons. Called every server tick; completes a summon as
     * soon as the maid's entity is back, and gives up once the deadline passes so a request
     * can never hang forever.
     */
    public static void tickPendingSummons(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        var iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            PendingSummon pending = entry.getValue();
            ServerPlayer owner = server.getPlayerList().getPlayer(pending.ownerId());
            if (owner == null) {
                iterator.remove();
                keepOrReleaseTicket(server, pending.maidId());
                continue;
            }
            EntityMaid maid = findLoadedMaid(owner, pending.maidId());
            if (maid != null) {
                iterator.remove();
                if (summonLoaded(owner, maid)) {
                    owner.displayClientMessage(
                            Component.translatable("message.touhou_maid_legion.force_load_done"), true);
                } else {
                    owner.sendSystemMessage(Component.translatable("message.touhou_maid_legion.cannot_reach"));
                }
                // The temporary ticket has done its job. It is kept only while the maid's own
                // switch is on, in which case tickForceLoadedMaids holds her chunk from now on.
                keepOrReleaseTicket(server, pending.maidId());
                com.dsh.maidmanager.network.C2SMaidActionPacket.refresh(owner);
                continue;
            }
            if (now > pending.deadline()) {
                iterator.remove();
                owner.sendSystemMessage(Component.translatable("message.touhou_maid_legion.force_load_timeout"));
                // The summon gave up, so its ticket has to go with it.
                releaseForceLoad(server, pending.maidId());
            }
        }
    }

    /** Releases the force-load ticket held for a maid, in whichever level it was taken. */
    public static void releaseForceLoad(MinecraftServer server, UUID maidId) {
        // The level matters: the same maid id can hold a ticket in more than one dimension, and
        // the previous "try every level" loop cleared our record on the first level, so a ticket
        // in another dimension was never released at all.
        ChunkLoadHelper.release(server, maidId);
    }

    /**
     * Drops a finished summon's ticket unless somebody still keeps that maid force loaded.
     *
     * <p>A summon that ends without a maid to hand the chunk over to - the owner logged off, or
     * she could not be reached - must not leave its ticket behind. Forge stores tickets in the
     * level's saved data and reinstates them on every later start, so an orphaned ticket keeps
     * loading and ticking its chunk for good.
     */
    private static void keepOrReleaseTicket(MinecraftServer server, UUID maidId) {
        if (!MaidRegistry.get(server).isForceLoadByAnyone(maidId)) {
            releaseForceLoad(server, maidId);
        }
    }

    /** Removes any pending request for a maid (e.g. when the switch is turned off). */
    public static void cancelPending(UUID maidId) {
        PENDING.remove(maidId);
    }

    /**
     * On server start, re-asserts the ticket of every force-loaded maid that is currently loaded.
     *
     * <p>The tickets themselves are persistent - Forge stores them per mod id and reinstates them
     * while a level loads - but it reinstates them without knowing whether they are still wanted,
     * and a ticket whose maid was switched off in an earlier session would keep a chunk ticking
     * forever. Those are dropped by the validation hook in {@link ChunkLoadHelper}; this pass
     * re-takes the ones that remain, in the level each maid is actually in now.
     */
    public static void onServerStarted(MinecraftServer server) {
        MaidRegistry registry = MaidRegistry.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, ALL_ENTITIES)) {
                UUID owner = maid.getOwnerUUID();
                if (owner != null && registry.isForceLoad(owner, maid.getUUID())) {
                    ChunkLoadHelper.hold(level, maid);
                }
            }
        }
    }

    /**
     * Releases our bookkeeping when the server stops.
     *
     * <p>The tickets are deliberately left in place: they are what brings a force-loaded maid back
     * after a restart. The ones that stopped being wanted are removed by {@link ChunkLoadHelper}
     * while the levels load, before they can take effect again.
     */
    public static void onServerStopping(MinecraftServer server) {
        PENDING.clear();
        ChunkLoadHelper.clear();
    }

    /**
     * Re-asserts the held chunk for every force-loaded maid that is currently loaded.
     *
     * <p>Covers maids that walked into a new chunk, and re-takes a chunk that was dropped.
     */
    public static void tickForceLoadedMaids(MinecraftServer server) {
        if (!MaidRegistry.get(server).hasAnyForceLoad()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            // Only a maid we already hold a chunk for can need anything here: a maid without a
            // ticket is not loaded, so no entity of hers could have walked into a new chunk.
            // Going through our own bookkeeping keeps this off the entity list - the previous
            // version scanned every loaded entity of every level, once a second, for as long as
            // the server ran, even when nobody had the switch on at all.
            for (UUID maidId : ChunkLoadHelper.heldMaids()) {
                if (level.getEntity(maidId) instanceof EntityMaid maid) {
                    ChunkLoadHelper.hold(level, maid);
                }
            }
        }
    }

    /** Enables or disables force-loading for one maid, applying the change immediately. */
    public static void setForceLoad(ServerPlayer player, UUID maidId, boolean enabled) {
        if (!canControl(player, maidId)) {
            return;
        }
        MaidRegistry registry = MaidRegistry.get(player.getServer());
        registry.setForceLoad(player.getUUID(), maidId, enabled);
        if (enabled) {
            registry.setAcknowledged(player.getUUID());
            // If she is loaded right now, start holding her chunk straight away.
            EntityMaid maid = findLoadedMaid(player, maidId);
            if (maid != null && maid.level() instanceof ServerLevel level) {
                ChunkLoadHelper.hold(level, maid);
            }
        } else {
            cancelPending(maidId);
            if (player.getServer() != null) {
                releaseForceLoad(player.getServer(), maidId);
            }
        }
    }

    public static void acknowledge(ServerPlayer player) {
        MaidRegistry.get(player.getServer()).setAcknowledged(player.getUUID());
    }

    /**
     * Stars or unstars a maid.
     *
     * <p>Ownership is verified before storing the flag, so a crafted packet cannot add
     * entries for maids the player does not own. A maid that is currently unloaded is still
     * accepted when she appears in TLM's records for this player.
     */
    public static void setFavourite(ServerPlayer player, UUID maidId, boolean favourite) {
        if (!canControl(player, maidId)) {
            return;
        }
        MaidRegistry.get(player.getServer()).setFavourite(player.getUUID(), maidId, favourite);
    }

    /** True when the maid is one of the player's, whether loaded, stored or merely recorded. */
    public static boolean ownsMaid(ServerPlayer player, UUID maidId) {
        if (findLoadedMaid(player, maidId) != null) {
            return true;
        }
        if (MaidStorage.get(player.getServer()).contains(player.getUUID(), maidId)) {
            return true;
        }
        // A maid we captured on death has no entity, is not in our store, and TLM has stopped
        // tracking her, so all three checks miss her. Without this the panel listed her - the
        // roster is built from the death records - while every action on her was refused as
        // "not yours", which is exactly the contradiction the revive button hit.
        //
        // The death record is itself proof of ownership: MaidDeathHandler only writes one
        // after confirming the maid belongs to this player and is enrolled.
        if (MaidDeathStorage.get(player.getServer()).contains(player.getUUID(), maidId)) {
            return true;
        }
        for (MaidInfo info : MaidUtil.getUnloadedMaidInfos(player)) {
            if (maidId.equals(info.getEntityId())) {
                return true;
            }
        }
        return false;
    }
    /**
     * True when the player may act on this maid through the panel: they own her <em>and</em>
     * have enrolled her.
     *
     * <p>Every mutating entry point validates with this rather than {@link #ownsMaid}, because
     * ownership alone is not consent to be commanded from the terminal. The client only ever
     * sends ids it saw in an enrolled snapshot, so a request for an unenrolled maid means
     * either a stale client or a crafted packet - both are refused.
     */
    public static boolean canControl(ServerPlayer player, UUID maidId) {
        return ownsMaid(player, maidId) && isEnrolled(player, maidId);
    }

    // ------------------------------------------------------------------
    // Commands (useful for debugging without a client)
    // ------------------------------------------------------------------

    public static void registerCommands(com.mojang.brigadier.CommandDispatcher<
            net.minecraft.commands.CommandSourceStack> dispatcher) {
        dispatcher.register(net.minecraft.commands.Commands.literal("maidlegion")
                .requires(src -> src.hasPermission(0))
                .then(net.minecraft.commands.Commands.literal("list").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    List<MaidEntry> entries = snapshot(player);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Maid Legion: " + entries.size() + " maid(s)"), false);
                    for (MaidEntry e : entries) {
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                " - " + e.name.getString() + " [" + e.state + "] "
                                        + (e.dimension.isEmpty() ? "" : e.dimension + " " + e.pos)
                                        + (e.forceLoad ? " (force-load)" : "")), false);
                    }
                    return entries.size();
                })));
    }

    /** Convenience used by the config-driven throttle. */
    public static int maxPerAction() {
        return Config.COMMON.maxSummonPerAction.get();
    }

    /** Ticks to wait between maids within one action. */
    public static int intervalTicks() {
        return Config.COMMON.summonIntervalTicks.get();
    }

    /** Whether server owners allow chunk loading at all. */
    public static boolean forceLoadAllowed() {
        return Config.COMMON.allowForceLoad.get();
    }

    /** Unused placeholder to keep the Level import meaningful for future dimension work. */
    static boolean isOverworld(Level level) {
        return level.dimension().equals(Level.OVERWORLD);
    }

    /** Spawn type used when re-creating a maid, so vanilla hooks behave normally. */
    static MobSpawnType spawnType() {
        return MobSpawnType.MOB_SUMMONED;
    }
}
