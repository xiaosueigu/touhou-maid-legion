package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.ChunkLoadHelper;
import com.dsh.maidmanager.logic.MaidManagerService;
import com.dsh.maidmanager.network.NetworkHandler;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Entry point of the Maid Legion mod.
 *
 * <p>The mod is a companion for Touhou Little Maid (TLM). It never ships TLM code or
 * assets; every interaction with TLM lives behind {@link com.dsh.maidmanager.util.MaidUtil}
 * so that a TLM API change degrades gracefully instead of crashing the game.
 */
@Mod(MaidManagerMod.MOD_ID)
public final class MaidManagerMod {
    public static final String MOD_ID = "touhou_maid_legion";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MaidManagerMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        NetworkHandler.register();

        // Registered before any level is loaded: it is what drops the force-load tickets of an
        // earlier session instead of letting Forge reinstate them.
        ChunkLoadHelper.registerForcedChunkValidation();
        forgeBus.addListener(this::onRegisterCommands);
        forgeBus.addListener(this::onServerStarted);
        forgeBus.addListener(this::onServerStopping);
        forgeBus.addListener(this::onServerTick);

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

    private void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        MaidManagerService.onServerStopping(event.getServer());
    }

    /**
     * Drives the two things that cannot happen instantly: keeping force-loaded chunks held as
     * maids move, and finishing summons that are waiting for a chunk to come back.
     */
    private void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
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
