package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.MaidManagerService;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * Entry point of the Maid Legion mod.
 *
 * <p>The mod is a companion for Touhou Little Maid (TLM). It never ships TLM code or
 * assets; every interaction with TLM lives behind {@link com.dsh.maidmanager.util.MaidUtil}
 * so that a TLM API change degrades gracefully instead of crashing the game.
 *
 * <p>Differences from the 1.20/Forge branch: the constructor receives the {@link IEventBus}
 * and {@link ModContainer} instead of pulling them from static context, {@code MinecraftForge}
 * became {@code NeoForge}, config registration moved onto the container, and
 * {@code TickEvent.ServerTickEvent} became {@code ServerTickEvent.Post}.
 */
@Mod(MaidManagerMod.MOD_ID)
public final class MaidManagerMod {
    public static final String MOD_ID = "touhou_maid_legion";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MaidManagerMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // NeoForge requires a TicketController to be registered before it can hold chunks; ours
        // also carries the level-load hook that drops stale force-load tickets. This is a mod-bus
        // event, and the controller's forceChunk() throws if it never ran.
        modBus.addListener(com.dsh.maidmanager.logic.ChunkLoadHelper::registerController);

        // Payloads register themselves through NetworkHandler.Registrar; nothing to do here.
        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(this::onRegisterCommands);
        gameBus.addListener(this::onServerStarted);
        gameBus.addListener(this::onServerStopping);
        gameBus.addListener(this::onServerTick);

        LOGGER.info("Maid Legion loaded.");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        MaidManagerService.registerCommands(event.getDispatcher());
    }

    private void onServerStarted(ServerStartedEvent event) {
        // Warm up the reflection cache early so the first GUI open is not slow, and so any
        // TLM incompatibility is reported in the log at startup rather than mid-game.
        if (!com.dsh.maidmanager.util.MaidUtil.selfCheck()) {
            LOGGER.error("Touhou Little Maid API self-check reported problems; "
                    + "the maid terminal will run in degraded (online-maids-only) mode.");
        }
        MaidManagerService.onServerStarted(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        MaidManagerService.onServerStopping(event.getServer());
    }

    /**
     * Drives the things that cannot happen instantly: keeping force-loaded chunks held as
     * maids move, finishing summons that are waiting for a chunk to come back, and completing
     * revives once their delay elapses.
     *
     * <p>NeoForge fires {@code ServerTickEvent.Post} once at the end of each server tick, which
     * is the exact equivalent of Forge's {@code Phase.END}.
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        MaidManagerService.tickPendingSummons(server);
        // Casts are checked every tick, not once a second: a cast that finished must land
        // promptly, and the loop returns immediately when nothing is channelling.
        MaidManagerService.tickReviveCasts(server);
        // Re-assert held chunks about once a second; cheap, and covers maids that moved.
        if (server.getTickCount() % 20 == 0) {
            MaidManagerService.tickForceLoadedMaids(server);
            // Polled rather than event-driven: TLM fires nothing when a player picks up a
            // P-point, so sweeping the wallet every second is the only way to keep pickup room
            // free. Without it, points overflow into vanilla experience at TLM's fixed rate.
            com.dsh.maidmanager.logic.MaidProgressionService.tickAutoDeposit(server);
            // The bound shrine lamp is the efficient route: it absorbs nearby P-points itself and
            // holds far more than the wallet, so sweeping it once a second is what actually lets a
            // player accumulate points instead of watching them overflow into vanilla experience.
            com.dsh.maidmanager.logic.MaidProgressionService.tickLampDrain(server);
        }
    }
}
