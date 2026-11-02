/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.compat.CombatCapabilities;
import dev.tntcoating.core.CrystalCombatSettingsMigration;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.mc.CombatDamageAdapter;
import dev.tntcoating.core.combat.CombatEngine;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatProfiles;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.DirectPlanner;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.Mode;
import dev.tntcoating.core.combat.PacketLedger;
import dev.tntcoating.core.combat.PistonGeometry;
import dev.tntcoating.core.combat.PistonLayoutSelection;
import dev.tntcoating.core.combat.RiskDebtSnapshot;
import dev.tntcoating.core.combat.RiskModel;
import dev.tntcoating.core.combat.Safety;
import dev.tntcoating.core.combat.Vec;
import dev.tntcoating.core.combat.mc.ClientPacketSink;
import dev.tntcoating.core.combat.mc.CombatInventoryAdapter;
import dev.tntcoating.core.combat.mc.CombatObservationBridge;
import dev.tntcoating.core.combat.mc.CombatRuntime;
import dev.tntcoating.core.combat.mc.CrystalCombatController;
import dev.tntcoating.core.combat.mc.CrystalCombatObservationHost;
import dev.tntcoating.core.combat.mc.DirectEngineHost;
import dev.tntcoating.core.combat.mc.DirectWorldReader;
import dev.tntcoating.core.combat.mc.MinecraftCombatClient;
import dev.tntcoating.core.combat.mc.ObservationHooks;
import dev.tntcoating.core.combat.mc.ObservationIntake;
import dev.tntcoating.core.combat.mc.PistonTrapController;
import dev.tntcoating.core.combat.mc.PistonTrapWorld;
import dev.tntcoating.core.combat.mc.RuntimeDirectGateway;
import dev.tntcoating.render.CrystalCombatRender;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

/**
 * CrystalCombat v2.1 module shell: activation, disable and visibility for the audited combat runtime.
 *
 * <p>This module is a lifecycle shell. It owns settings and the visible wait reason; every send remains in
 * {@link CombatRuntime#dispatch}. It never toggles another module, never writes Meteor settings and never
 * touches the offhand. Enabling first runs {@link CombatCapabilities#inspect()} and every settings guard
 * through {@link CrystalCombatController.Readiness}; only a clean check creates the runtime stack, binds the
 * ledger's read-only hazard protector and activates {@link dev.tntcoating.core.combat.CrystalCombatGate}.
 * A missing critical injection leaves the module disabled and the visible reason is the module info string.
 *
 * <p>Disabling deactivates the gate first, ends the action generation immediately and sends nothing — no
 * cleanup packets, no hazard clearing, and the sent risk stays with the still-observing ledger and budget.
 * The observation hooks stay installed so a later enable resumes the same ledger instead of losing evidence.
 * Because the module's own handlers unsubscribe on disable, the always-subscribed observation host also
 * forwards client ticks: while disabled it keeps draining receipts and applying observations so sent risk can
 * retire, without sending or publishing an active status.
 *
 * <p>Phase 3.1 wires the verified DirectOnly baseline: the controller owns one engine host that reads a world
 * snapshot after the runtime applied the tick's receipts and hands at most one place or one verified break
 * per tick to {@link dev.tntcoating.core.combat.CombatEngine}. The visible wait reason now includes the
 * engine pause ("paused: eating"), while "no crystals", "waiting for your crystals to resolve", "paused: &lt;cause&gt;" and
 * "waiting for connection" keep their existing priority. Metrics record tick time, suspensions and risk-debt
 * samples; the remaining Phase 4.7 counters stay zero until their phases.
 */
public final class CrystalCombat extends Module {
    /**
     * Conservative pins the audited floors and re-applies them when selected; Custom keeps the
     * player's own values. Both are validated by CombatProfiles.productionSafety, so no profile can
     * move a conservative floor. The plan's third profile is deliberately not offered.
     */
    public enum Profile {
        CONSERVATIVE,
        CUSTOM
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgDirect = settings.createGroup("Direct");
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgPiston = settings.createGroup("Piston");
    private final SettingGroup sgDefence = settings.createGroup("Defence");
    private final SettingGroup sgPauses = settings.createGroup("Pauses");
    private final SettingGroup sgExperimental = settings.createGroup("Experimental");
    private final SettingGroup sgRender = settings.createGroup("Render");
    // Documented deviation from the Phase 8 table: tester diagnostics stay in their own group.
    private final SettingGroup sgDiagnostics = settings.createGroup("Diagnostics");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("DirectOnly: normal crystal aura. PistonOnly (beta): piston trap only. Hybrid: uses "
            + "whichever plan is better, one at a time.")
        .defaultValue(Mode.DIRECT_ONLY)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Profile> profile = sgGeneral.add(new EnumSetting.Builder<Profile>()
        .name("profile")
        .description("Conservative resets the safety settings to the tested defaults. Custom keeps your own "
            + "values; safety limits still apply.")
        .defaultValue(Profile.CONSERVATIVE)
        .onChanged(value -> profileChanged())
        .build());

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("How far away a player can be targeted.")
        .defaultValue(6.0)
        .min(0.0)
        .max(24.0)
        .sliderRange(2.0, 10.0)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> antiSuicide = sgSafety.add(new BoolSetting.Builder()
        .name("anti-suicide")
        .description("Always on. CrystalCombat will not run with this off.")
        .defaultValue(true)
        .onChanged(value -> configChanged())
        .build());

    // Strict is the default in every profile. The model is live state: changing it never rebuilds the
    // stack, never reprofiles safety and never clears risk that has already left.
    private final Setting<RiskModel> riskModel = sgSafety.add(new EnumSetting.Builder<RiskModel>()
        .name("risk-model")
        .description(RiskModel.STRICT.description() + " " + RiskModel.BARRIER.description() + " "
            + RiskModel.METEOR_STYLE.description())
        .defaultValue(RiskModel.defaultModel())
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Double> minTargetDamage = sgSafety.add(new DoubleSetting.Builder()
        .name("min-target-damage")
        .description("Skip crystals that would deal less than this to the target. Never below 4.")
        .defaultValue(4.0)
        .min(0.0)
        .max(100.0)
        .sliderRange(0.0, 20.0)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Double> maxSelfDamage = sgSafety.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("Skip crystals that could deal you more than this, up to 36. Selecting Conservative "
            + "resets it to 6. The remaining-health margin still applies.")
        .defaultValue(6.0)
        .min(0.0)
        .max(CombatDamagePolicy.MAX_SELF_DAMAGE_CEILING)
        .sliderRange(0.0, CombatDamagePolicy.MAX_SELF_DAMAGE_CEILING)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Double> remainingHealthMargin = sgSafety.add(new DoubleSetting.Builder()
        .name("remaining-health-margin")
        .description("Health you must keep after the worst-case hit. Never below 2.")
        .defaultValue(2.0)
        .min(0.0)
        .max(20.0)
        .sliderRange(0.0, 10.0)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Double> maxFriendDamage = sgSafety.add(new DoubleSetting.Builder()
        .name("max-friend-damage")
        .description("Most damage a crystal may deal to each friend. Never above 2.")
        .defaultValue(2.0)
        .min(0.0)
        .max(20.0)
        .sliderRange(0.0, 10.0)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Double> placeRange = sgDirect.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("How far away crystals can be placed.")
        .defaultValue(4.5)
        .min(0.0)
        .max(8.0)
        .sliderRange(0.0, 6.0)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Double> attackRange = sgDirect.add(new DoubleSetting.Builder()
        .name("attack-range")
        .description("How far away crystals can be broken.")
        .defaultValue(3.0)
        .min(0.0)
        .max(8.0)
        .sliderRange(0.0, 6.0)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Double> wallsRange = sgDirect.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("How far through walls you can place or break. 0 means only what you can see.")
        .defaultValue(3.0)
        .min(0.0)
        .max(8.0)
        .sliderRange(0.0, 4.0)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> ownOnly = sgDirect.add(new BoolSetting.Builder()
        .name("own-only")
        .description("Only break crystals this module placed.")
        .defaultValue(false)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> support = sgDirect.add(new BoolSetting.Builder()
        .name("support")
        .description("Experimental: if there is no obsidian to place on, place one obsidian block first. "
            + "Uses two actions.")
        .defaultValue(false)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> surroundGap = sgDirect.add(new BoolSetting.Builder()
        .name("surround-gap")
        .description("Experimental: if the target is in a one-block hole, prefer placing in the open gap "
            + "when obsidian or bedrock is already under it.")
        .defaultValue(false)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> motionPrediction = sgTiming.add(new BoolSetting.Builder()
        .name("motion-prediction")
        .description("Experimental: aim where the target is about to move, and check your own safety where "
            + "you are about to move. Needs live testing.")
        .defaultValue(false)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> hurtSync = sgTiming.add(new BoolSetting.Builder()
        .name("hurt-sync")
        .description("Experimental: time hits around the target's damage cooldown. Has no effect in-game "
            + "yet. Needs live testing.")
        .defaultValue(false)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> pistonTrap = sgPiston.add(new BoolSetting.Builder()
        .name("piston-trap")
        .description("Beta: push a crystal into the target with a piston. One trap at a time. Clean up "
            + "afterwards, then run .crystalcombat cleanup confirm.")
        .defaultValue(true)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> autoCleanup = sgPiston.add(new BoolSetting.Builder()
        .name("auto-cleanup")
        .description("Beta: break the power, piston and supports this module placed after a trap. Blocks it "
            + "is unsure about are left for you.")
        .defaultValue(false)
        .onChanged(value -> configChanged())
        .build());

    /** One beta toggle per measured layout: direction x height x power, sixteen combinations. */
    private final Map<PistonLayoutSelection.Combination, Setting<Boolean>> pistonLayoutSettings =
        new LinkedHashMap<>();

    private final Setting<Boolean> dangerousCrystalBreak = sgDefence.add(new BoolSetting.Builder()
        .name("dangerous-crystal-break")
        .description("Experimental defence: break an enemy crystal near you that could take you below your "
            + "health margin. Never breaks your own.")
        .defaultValue(false)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> antiPiston = sgDefence.add(new BoolSetting.Builder()
        .name("anti-piston")
        .description("Experimental defence: if an enemy piston faces you, break the crystal in its path or "
            + "block it with obsidian.")
        .defaultValue(false)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> pauseWhenEating = sgPauses.add(new BoolSetting.Builder()
        .name("pause-when-eating")
        .description("Pause while eating food.")
        .defaultValue(true)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> pauseWhenDrinking = sgPauses.add(new BoolSetting.Builder()
        .name("pause-when-drinking")
        .description("Pause while drinking a potion.")
        .defaultValue(true)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> pauseOnBaritone = sgPauses.add(new BoolSetting.Builder()
        .name("pause-on-baritone")
        .description("Pause while Baritone is pathing.")
        .defaultValue(true)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> pauseWhenMining = sgPauses.add(new BoolSetting.Builder()
        .name("pause-when-mining")
        .description("Pause while you are mining a block.")
        .defaultValue(true)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> pauseOnScreens = sgPauses.add(new BoolSetting.Builder()
        .name("pause-on-screens")
        .description("Pause while any screen is open.")
        .defaultValue(true)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Double> healthFloor = sgPauses.add(new DoubleSetting.Builder()
        .name("health-floor")
        .description("Pause at or below this health.")
        .defaultValue(6.0)
        .min(0.0)
        .max(20.0)
        .sliderRange(0.0, 20.0)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> pauseOnLag = sgPauses.add(new BoolSetting.Builder()
        .name("pause-on-lag")
        .description("Pause when the server is lagging.")
        .defaultValue(true)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> footprintConcurrency = sgExperimental.add(new BoolSetting.Builder()
        .name("footprint-concurrency")
        .description("Experimental: in Hybrid, allow a normal crystal plan to run during a piston trap when "
            + "it cannot interfere. Off: one plan at a time.")
        .defaultValue(false)
        .onChanged(value -> configChanged())
        .build());

    private final Setting<Boolean> showRiskDebt = sgRender.add(new BoolSetting.Builder()
        .name("show-risk-debt")
        .description("Show how many of your crystals are still unresolved and why the aura is paused. "
            + "Display only.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debugLog = sgDiagnostics.add(new BoolSetting.Builder()
        .name("debug-log")
        .description("Keep the last five minutes of combat events in memory for a tester export. Nothing is "
            + "saved unless you export.")
        .defaultValue(false)
        .onChanged(value -> debugLogChanged())
        .build());

    private final Setting<Boolean> exportIncludeIdentities = sgDiagnostics.add(new BoolSetting.Builder()
        .name("export-include-identities")
        .description("Include other players' names and UUIDs in exports. Chat and server addresses are "
            + "never included.")
        .defaultValue(false)
        .build());

    private final Setting<Keybind> incidentKey = sgDiagnostics.add(new KeybindSetting.Builder()
        .name("incident-marker-key")
        .description("Press to mark 'something went wrong here' in the debug log.")
        .defaultValue(Keybind.none())
        .action(this::writeManualIncident)
        .build());

    /** The one controller; creates the runtime stack lazily on the first successful enable. */
    private final CrystalCombatController controller =
        new CrystalCombatController(this::unavailableReason, this::buildStack);

    /**
     * After-application spawn/removal observations. Subscribed once for the life of the client so the ledger
     * keeps observing passively even while the module is disabled.
     */
    private final CrystalCombatObservationHost observationHost;

    /** Raw Minecraft connection identity so a missed GameLeft while disabled cannot reuse a stale session. */
    private Object connectionToken;
    /** The connection identity the open session belongs to; null while no session is open. */
    private Object sessionToken;
    /** Monotonic server-tick counter for the runtime; never reset, because the runtime rejects backwards ticks. */
    private int tickCounter;

    public CrystalCombat() {
        super(TntCoatingAddon.CATEGORY, "crystal-combat",
            "Audited crystal combat with one send path, conservative safety bounds and visible wait reasons.");
        // The module must be able to refuse in the main menu and must see the first world join.
        runInMainMenu = true;
        addLayoutSettings();
        observationHost = new CrystalCombatObservationHost(new CrystalCombatObservationHost.Observer() {
            @Override
            public ConnectionKey connection() {
                return controller.connection();
            }

            @Override
            public void spawned(EntityKey key, Vec position, String entityType) {
                controller.observeSpawn(key, position, entityType);
            }

            @Override
            public void removed(EntityKey key) {
                controller.observeRemoval(key);
            }

            @Override
            public void blockUpdated(Cell cell) {
                controller.observeBlockUpdate(cell);
            }

            @Override
            public void blockUpdated(Cell cell, String oldBlock, String newBlock) {
                controller.observeBlockUpdate(cell, oldBlock, newBlock);
            }

            @Override
            public void tick() {
                // Active ticks are driven by the module's own handler; while disabled this is the only
                // always-subscribed tick source, so passive receipt application never stops.
                if (isActive() && controller.enabled()) return;
                passiveTick();
            }
        });
        // Constructor subscription, not activation: passive ledger protection must not depend on the module
        // being enabled, and the host lives as long as the client session.
        MeteorClient.EVENT_BUS.subscribe(observationHost);
    }

    @Override
    public void onActivate() {
        controller.setMode(mode.get());
        controller.setRiskModel(riskModel.get());
        controller.setPistonLayouts(selection());
        controller.setTrapEnabled(pistonTrap.get());
        if (!controller.enable()) {
            error("CrystalCombat refused to enable: %s", controller.infoString());
            disable();
            return;
        }
        connectionToken = currentConnection();
        syncSession(connectionToken);
        if (debugLog.get()) controller.startDebugLog(mc, this::isActive);
    }

    @Override
    public void onDeactivate() {
        controller.disable();
    }

    @Override
    public String getInfoString() {
        // Published controller snapshot plus the enum-backed risk-model key: no world, budget or
        // capability queries from the render thread.
        String status = controller.infoString();
        if (isActive() && "idle".equals(status)) {
            status = "running: no verified opportunity";
        }
        return status + " [risk: " + riskModel.get().key() + "] [" + TntCoatingAddon.buildInfo().display()
            + (TntCoatingAddon.buildInfo().tester() ? " " + TntCoatingAddon.buildInfo().testerLabel() : "")
            + "]";
    }

    /**
     * Explicit tester action after manually removing a piston trap. Releases only the controller's trap
     * slot; the ledger hazards and the risk budget's sent debt stay untouched, and nothing is sent.
     *
     * @return whether an active trap existed and was reconciled
     */
    public boolean confirmManualCleanup() {
        return controller != null && controller.confirmTrapCleanup();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (isActive() && !antiSuicide.get()) {
            // The setting can only be changed by an external writer or a config reload; never keep running.
            error("CrystalCombat disabled: anti-suicide is locked on.");
            disable();
            return;
        }
        if (!isActive()) return;
        if (!controller.enabled()) return;
        advance();
    }

    /** One active-path client tick; shared with the host's passive path so both use one implementation. */
    private void advance() {
        Object current = currentConnection();
        if (current == null) {
            if (connectionToken != null) {
                controller.onLeft();
                connectionToken = null;
                sessionToken = null;
            }
            return;
        }
        if (!Objects.equals(current, connectionToken)) {
            controller.onLeft();
            connectionToken = current;
            sessionToken = null;
        }
        syncSession(current);
        controller.tick(tickCounter++);
    }

    /**
     * Passive-path tick: applies queued observations (so sent risk can retire) and tracks world/session
     * identity while the module is disabled. It never publishes an active wait reason because the controller
     * only updates its visible status while enabled, and it can never send — the runtime only sends from
     * {@code dispatch}, which needs a valid planner ticket that does not exist in this milestone.
     */
    private void passiveTick() {
        if (!controller.hasStack()) return;
        advance();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        connectionToken = currentConnection();
        syncSession(connectionToken);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        connectionToken = null;
        sessionToken = null;
        controller.onLeft();
    }

    /**
     * Opens the session when none is open, and closes a session that belongs to a previous connection (for
     * example after leaving the world while the module was disabled). A stale session must never observe or
     * act under the new world's connection.
     */
    private void syncSession(Object current) {
        if (current == null) return;
        if (controller.sessionOpen() && !Objects.equals(current, sessionToken)) {
            controller.onLeft();
            sessionToken = null;
        }
        if (!controller.sessionOpen()) {
            controller.onJoined();
            sessionToken = current;
        }
    }

    /** Capability check first; only a clean check lets the controller exist/start/activate. */
    private String unavailableReason() {
        CombatCapabilities.Status status = CombatCapabilities.inspect();
        if (!status.ready()) return "capability missing: " + status.summary();
        return settingsRefusal(mode.get(), profile.get(), antiSuicide.get(), safety());
    }

    /**
     * The settings half of readiness. Kept pure so a mid-session change can be checked with the same
     * rules as enable, and tested without a client. A live runtime must not keep running a mode, profile
     * or safety envelope that the enable check would refuse.
     */
    static String settingsRefusal(Mode mode, Profile profile, boolean antiSuicide, Safety safety) {
        if (!antiSuicide) return "anti-suicide must stay on";
        if (profile == null) return "profile is required";
        if (profile != Profile.CONSERVATIVE && profile != Profile.CUSTOM) {
            return "profile " + profile + " is not implemented yet";
        }
        try {
            CombatProfiles.productionSafety(safety);
        } catch (IllegalArgumentException weaker) {
            return "unsafe safety settings: " + weaker.getMessage();
        }
        return null;
    }

    /** Called only after readiness passed, so the runtime never sees a weaker safety profile. */
    private CrystalCombatController.Stack buildStack() {
        Minecraft minecraft = Minecraft.getInstance();
        dev.tntcoating.core.combat.CombatSession session =
            new dev.tntcoating.core.combat.CombatSession();
        MinecraftCombatClient client = new MinecraftCombatClient(minecraft, session);
        ObservationIntake intake = ObservationIntake.production(session, client);
        // Keep every hook installed across disable: the ledger and budget must keep observing passively.
        ObservationHooks.install(intake);
        // Phase 4.1: per-target invalidation follows the applied packet stream for the module's lifetime.
        ObservationHooks.install(new CombatObservationBridge(controller::observeTargetChanged));
        PacketLedger packets = PacketLedger.conservative();
        dev.tntcoating.core.combat.CrystalLedger ledger =
            new dev.tntcoating.core.combat.CrystalLedger();
        // No mitigation data source exists yet; refusing the widening suspends instead of keeping stale bounds.
        CombatRiskBudget risk = CombatProfiles.conservativeBudget((envelope, current) -> null);
        risk.setRiskModel(riskModel.get());
        dev.tntcoating.core.combat.CombatScheduler scheduler =
            new dev.tntcoating.core.combat.CombatScheduler(session, risk, packets);
        CombatInventoryAdapter inventory = CombatInventoryAdapter.production(client);
        ClientPacketSink sink = new ClientPacketSink(minecraft);
        CombatRuntime runtime = new CombatRuntime(session, scheduler, risk, intake, inventory, client,
            sink, safety());
        DirectSettings direct = directSettings();
        CombatDamageAdapter damageAdapter = new CombatDamageAdapter();
        DirectPlanner planner = new DirectPlanner(direct);
        CombatEngine engine = new CombatEngine(session, scheduler, risk, ledger, direct, planner);
        engine.attachMetrics(controller.metrics());
        RuntimeDirectGateway gateway = new RuntimeDirectGateway(runtime, client, direct);
        dev.tntcoating.core.combat.CombatHurtModel hurtModel =
            new dev.tntcoating.core.combat.CombatHurtModel();
        DirectWorldReader reader = new DirectWorldReader(minecraft, session, client, intake, ledger,
            damageAdapter, hurtModel, direct);
        DirectEngineHost engineHost = new DirectEngineHost(runtime, reader, engine, gateway);
        // Read-only production world for the Phase 5 trap; constructed with the stack and dormant unless
        // the piston-trap setting is on. It has no send path of its own.
        PistonTrapWorld trapWorld = new PistonTrapWorld(reader, damageAdapter, direct, safety());
        PistonTrapController trap = new PistonTrapController(runtime, session, scheduler, risk, ledger,
            PistonTrapController.inventory(inventory), client, trapWorld);
        // The trap's cleanup stage asks this shared priority for its starvation window; the engine owns
        // the instance so Hybrid can yield the next free slot to it.
        trap.setCleanupPriority(engine.cleanupPriority());
        // The Pauses settings apply to the trap in every mode, read through the engine host's world.
        trap.setPauseSource(engineHost::trapPause);
        return new CrystalCombatController.Stack(runtime, session, ledger, scheduler, risk, client,
            intake, engineHost, hurtModel, trap);
    }

    /** Maps the visible module settings onto the pure engine configuration. */
    private DirectSettings directSettings() {
        return new DirectSettings(
            targetRange.get(), placeRange.get(), attackRange.get(), wallsRange.get(), safety(),
            ownOnly.get(), 0, DirectSettings.DEFAULT_MAX_ATTEMPTS, true,
            DirectSettings.DEFAULT_FACEPLACE_HEALTH, DirectSettings.DEFAULT_FACEPLACE_ARMOUR_FRACTION,
            false, support.get(), DirectSettings.DEFAULT_SWITCH_DELAY_TICKS, true,
            pauseWhenEating.get(), pauseWhenDrinking.get(), pauseWhenMining.get(), pauseOnScreens.get(),
            pauseOnBaritone.get(), healthFloor.get(), pauseOnLag.get(),
            DirectSettings.DEFAULT_MIN_TPS, DirectSettings.DEFAULT_STALE_FEEDBACK_NANOS,
            motionPrediction.get(), DirectSettings.MOTION_TICKS_CONSERVATIVE, hurtSync.get(),
            surroundGap.get(), dangerousCrystalBreak.get(), antiPiston.get(),
            footprintConcurrency.get());
    }

    private Safety safety() {
        return new Safety(minTargetDamage.get(), maxSelfDamage.get(), remainingHealthMargin.get(),
            maxFriendDamage.get());
    }

    /** The tester command's read-only view of the opt-in debug log. */
    public List<String> debugLines() {
        return controller.debugLines();
    }

    public long debugDroppedLines() {
        return controller.debugDroppedLines();
    }

    public boolean debugLogRunning() {
        return controller.debugLogRunning();
    }

    public boolean includeIdentitiesInExport() {
        return exportIncludeIdentities.get();
    }

    public boolean debugLogEnabled() {
        return debugLog.get();
    }

    /**
     * Starts or stops the in-memory log when the setting changes. Starting before the first successful
     * enable is a no-op; {@link #onActivate()} retries once the stack exists.
     */
    private void debugLogChanged() {
        if (controller == null) return;
        if (debugLog.get()) {
            if (controller.hasStack()) controller.startDebugLog(mc, this::isActive);
        } else {
            controller.stopDebugLog();
        }
    }

    /** Hotkey: writes a tester "something went wrong here" marker; diagnostics only, never a send. */
    private void writeManualIncident() {
        if (controller.debugMarker("something went wrong here")) return;
        error("Debug log is not running; enable CrystalCombat's Debug log setting first.");
    }

    /** Registers the sixteen beta layout toggles once; descriptions are explicit about beta status. */
    private void addLayoutSettings() {
        for (PistonLayoutSelection.Combination combination : PistonLayoutSelection.combinations()) {
            PistonGeometry.Layout layout = combination.layout();
            Setting<Boolean> setting = sgPiston.add(new BoolSetting.Builder()
                .name("piston-layout-" + combination.id())
                .description("Beta single-push layout: push " + combination.direction() + " from "
                    + layout.heightName() + " height with " + combination.powerName()
                    + ". Not yet server-confirmed; disable to exclude this combination. All sixteen "
                    + "off leaves PistonOnly inert.")
                .defaultValue(true)
                .onChanged(value -> configChanged())
                .build());
            pistonLayoutSettings.put(combination, setting);
        }
    }

    /** The exact planner filter derived from the visible toggles. */
    PistonLayoutSelection selection() {
        List<PistonLayoutSelection.Combination> enabled = new ArrayList<>();
        for (Map.Entry<PistonLayoutSelection.Combination, Setting<Boolean>> entry
            : pistonLayoutSettings.entrySet()) {
            if (entry.getValue().get()) enabled.add(entry.getKey());
        }
        return PistonLayoutSelection.of(enabled);
    }

    /** Read-only HUD: paints the snapshot the client tick published; never queries combat state. */
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!showRiskDebt.get()) return;
        RiskDebtSnapshot snapshot = controller.riskSnapshot();
        if (!snapshot.visible()) return;
        CrystalCombatRender.render(event, snapshot.displayLines());
    }

    /**
     * Meteor persists settings per group, so a plain regroup would silently drop saved values. Remap the
     * old groups before the normal load; the migration is defensive and leaves unknown data untouched.
     */
    @Override
    public Module fromTag(CompoundTag tag) {
        CompoundTag migrated = CrystalCombatSettingsMigration.migrate(tag);
        return super.fromTag(migrated);
    }

    /** Conservative re-pins the audited defaults on selection; Custom keeps the player's own values. */
    private void profileChanged() {
        if (profile.get() == Profile.CONSERVATIVE) applyConservativeSafety();
        configChanged();
    }

    /** A profile switch can never restore a weaker envelope: the floors are re-applied, not relaxed. */
    private void applyConservativeSafety() {
        Safety defaults = CombatProfiles.CONSERVATIVE_SAFETY;
        minTargetDamage.set(defaults.minTarget());
        maxSelfDamage.set(defaults.maxSelf());
        remainingHealthMargin.set(defaults.minRemaining());
        maxFriendDamage.set(defaults.maxFriend());
    }

    private void configChanged() {
        if (controller == null) return;
        if (isActive() || controller.enabled()) {
            // Refuse to keep a live runtime with settings the enable check would reject: disable
            // immediately instead of silently running a different mode/profile/safety than displayed.
            String refusal = settingsRefusal(mode.get(), profile.get(), antiSuicide.get(), safety());
            if (refusal != null) {
                error("CrystalCombat disabled: %s", refusal);
                disable();
                if (controller.hasStack()) controller.reconfigure(directSettings());
                return;
            }
        }
        controller.setPistonLayouts(selection());
        controller.setMode(mode.get());
        controller.setRiskModel(riskModel.get());
        controller.onConfigChanged();
        controller.setAutoCleanup(autoCleanup.get());
        if (controller.hasStack()) controller.reconfigure(directSettings());
        controller.setTrapEnabled(pistonTrap.get());
    }

    private Object currentConnection() {
        if (mc.getConnection() == null || mc.level == null) return null;
        return mc.getConnection();
    }
}
