/*
 * Comet highway travel helpers.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules;

import dev.tntcoating.core.ChatSender;
import dev.tntcoating.core.HighwayCourse;
import dev.tntcoating.core.HighwayRecoveryTargets;
import dev.tntcoating.core.HighwayWebhook;
import dev.tntcoating.core.LobbyLogin;
import dev.tntcoating.core.LobbyLocation;
import dev.tntcoating.core.LobbyPortalFinder;
import dev.tntcoating.core.LobbyRecoveryStore;
import dev.tntcoating.core.LobbyRecoveryDecisions;
import dev.tntcoating.core.PortalLookahead;
import java.io.IOException;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.movement.AutoWalk;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.ServerConnectBeginEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.NopPathManager;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Recovers saved Highway Fly trips and mounted Quiettee BoatPhase/AutoWalk travel. */
public final class AutoLobby extends HighwayHelper {
    public AutoLobby() { super("auto-lobby", "Recover Highway Fly or a mounted BoatPhase and AutoWalk trip through the lobby."); }

    private static final int RESUME_DELAY_TICKS = 20;
    private static final int PATH_START_GRACE_TICKS = 20;
    private static final int PATH_TIMEOUT_TICKS = 400;
    private static final int PATH_CLOSE_DISTANCE_SQ = 16;
    private static final int WALK_STUCK_TICKS = 100;
    private static final int PORTAL_WAIT_WARNING_TICKS = 200;
    private static final double MOVEMENT_EPSILON_SQ = .0025;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    final Setting<Boolean> reconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-reconnect")
        .description("Reconnect after an unexpected connection loss. Safety logouts stay disconnected; manually leaving does not open a reconnect screen.")
        .defaultValue(true)
        .onChanged(value -> { if (!value) highway().releaseReconnect(); else highway().prepareReconnectDelay(); }).build());
    final Setting<Double> reconnectDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("reconnect-delay").description("Seconds to wait before retrying an unexpectedly lost connection.")
        .defaultValue(3.5).range(1, 300).sliderRange(1, 30)
        .visible(() -> reconnect.get())
        .onChanged(value -> highway().prepareReconnectDelay()).build());

    private final Setting<String> targetDimension = sgGeneral.add(new StringSetting.Builder()
        .name("target-dimension")
        .description("Dimension to reach when AutoLobby runs without a saved flight. Saved trips keep their own dimension.")
        .defaultValue("minecraft:the_nether").build());

    private final Setting<Boolean> resumeFlight = sgGeneral.add(new BoolSetting.Builder()
        .name("resume-flight")
        .description("Resume Highway Fly or boat AutoWalk after reaching the target dimension.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> crackedAccount = sgGeneral.add(new BoolSetting.Builder()
        .name("cracked-account")
        .description("Also log in on unrecognised recovery spawns. The known cracked lobby always requires /login.")
        .defaultValue(false)
        .build());

    private final Setting<String> loginPassword = sgGeneral.add(new StringSetting.Builder()
        .name("login-password")
        .description("Password sent with /login. It is stored in plain text in the Meteor config.")
        .defaultValue("")
        .visible(() -> crackedAccount.get() || this.location == LobbyLocation.CRACKED_LOBBY)
        .wide()
        .build());

    private final Setting<Integer> loginTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("login-timeout")
        .description("Ticks to wait for the server's login confirmation before recovery continues. A rejected password waits for a manual login.")
        .defaultValue(200)
        .range(20, 1200)
        .sliderRange(20, 400)
        .visible(() -> crackedAccount.get() || this.location == LobbyLocation.CRACKED_LOBBY)
        .build());

    private final Setting<Boolean> enterPortal = sgGeneral.add(new BoolSetting.Builder()
        .name("enter-portal")
        .description("Walk into a nearby Nether portal when the rejoin is not in the target dimension.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> useBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("use-baritone")
        .description("Use Baritone's path manager to reach the portal when one is installed, then walk in directly.")
        .defaultValue(true)
        .visible(enterPortal::get)
        .build());

    private final Setting<Integer> searchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("search-radius")
        .description("Horizontal radius in blocks to search for a Nether portal.")
        .defaultValue(64)
        .range(8, 128)
        .sliderRange(8, 96)
        .visible(enterPortal::get)
        .build());

    private final Setting<Integer> searchHeight = sgGeneral.add(new IntSetting.Builder()
        .name("search-height")
        .description("Vertical distance above and below the player to search for a Nether portal.")
        .defaultValue(32)
        .range(4, 128)
        .sliderRange(4, 64)
        .visible(enterPortal::get)
        .build());

    private final Setting<Integer> joinDelay = sgGeneral.add(new IntSetting.Builder()
        .name("join-delay")
        .description("Ticks to wait after joining before scanning, so the server can place the player.")
        .defaultValue(40)
        .range(0, 200)
        .sliderRange(0, 100)
        .build());

    private final Setting<Integer> rescanDelay = sgGeneral.add(new IntSetting.Builder()
        .name("rescan-delay")
        .description("Ticks between portal scans while no portal is in range.")
        .defaultValue(100)
        .range(20, 600)
        .sliderRange(20, 300)
        .visible(enterPortal::get)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("lobby-debug")
        .description("Log extra recovery details.")
        .defaultValue(false)
        .build());

    private final LobbyRecoveryStore recoveryStore =
        new LobbyRecoveryStore(MeteorClient.FOLDER.toPath().resolve("auto-lobby-recovery.json"));

    enum Stage {
        IDLE,
        AWAITING_JOIN,
        SETTLING,
        LOGIN,
        QUEUED,
        SEARCHING,
        WALKING,
        ENTERING,
        WAITING,
        RESUMING,
        EXITING,
        DONE
    }

    private final LobbyPortalFinder.Probe finderProbe = new LobbyPortalFinder.Probe() {
        @Override public boolean loaded(int chunkX, int chunkZ) {
            return mc.level != null && mc.level.hasChunk(chunkX, chunkZ);
        }

        @Override public boolean maybePortal(int chunkX, int sectionY, int chunkZ) {
            if (mc.level == null) return false;
            LevelChunk chunk = mc.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null) return false;
            int index = chunk.getSectionIndexFromSectionY(sectionY);
            LevelChunkSection[] sections = chunk.getSections();
            if (index < 0 || index >= sections.length) return false;
            LevelChunkSection section = sections[index];
            return !section.hasOnlyAir()
                && section.maybeHas(state -> state.is(Blocks.NETHER_PORTAL));
        }

        @Override public boolean portal(BlockPos pos) {
            if (mc.level == null || !mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
            return mc.level.getBlockState(pos).is(Blocks.NETHER_PORTAL);
        }
    };

    private final PortalLookahead.Probe bodyProbe = new PortalLookahead.Probe() {
        @Override public boolean loaded(int chunkX, int chunkZ) {
            return finderProbe.loaded(chunkX, chunkZ);
        }

        @Override public boolean portal(BlockPos pos) {
            return finderProbe.portal(pos);
        }
    };

    private Stage stage = Stage.IDLE;
    private boolean armed;
    private boolean boatTrip;
    private boolean standaloneTrip;
    private String boatModule = "boat-phase";
    private float boatYaw;
    private boolean warnedBoatBridge;
    private Object recoveryWorld;
    private LobbyLocation location = LobbyLocation.NONE;
    private boolean loginStarted;
    private boolean loginSent;
    private boolean loginConfirmed;
    private boolean loginRejected;
    private boolean warnedLoginRejected;
    private int loginTicks;
        String resumeDimension;
    private String resumeServer;
    private BlockPos portal;
    private int stageTicks;
    private int pathTicks;
    private int stuckTicks;
    private boolean pathing;
    private boolean inputActive;
    private Vec3 lastWalkPosition = Vec3.ZERO;
    private boolean warnedNoPortal;
    private boolean warnedStuck;
    private boolean warnedAirborne;
    private boolean warnedCantResume;
    boolean waitingForTripRecord;
    private Vec3 portalExitTarget;
    private int portalExitTicks;
    private final Map<BlockPos,Long> failedPortals = new HashMap<>();

    /** A saved marker can be retained while another server is open, without moving there. */
    boolean resumePending() {
        stopWalking();
        resetTransientState();
        if (nativeBoatTrip() && !nativeRecoveryAuthorized()) { releaseNativeBoatRuntime(); return false; }
        Optional<LobbyRecoveryStore.Recovery> saved = recoveryStore.load();
        if (saved.isPresent() && LobbyRecoveryStore.NATIVE_BOAT.equals(saved.get().boatModule()) && !nativeRecoveryAuthorized()) return false;
        waitingForTripRecord = saved.isEmpty()
            && Files.exists(MeteorClient.FOLDER.toPath().resolve("auto-lobby-recovery.json"));
        if (waitingForTripRecord) {
            warning("Saved trip cannot be read yet; waiting instead of starting on an unknown server.");
            return true;
        }
        if (!highway().session.recovering() && saved.isPresent()) {
            LobbyRecoveryStore.Recovery recovery = saved.get();
            boatTrip = recovery.boatYaw() != null;
            standaloneTrip = false;
            boatModule = boatTrip ? recovery.boatModule() : "boat-phase";
            if (boatTrip) boatYaw = recovery.boatYaw();
            highway().session.recover(recovery.dimension(), recovery.server());
            highway().durableTripAvailable = true;
            if (recovery.destination() != null) {
                highway().tripDestination = recovery.destination();
                highway().tripTargetY = recovery.targetY();
                highway().destination.set(highway().tripDestination);
                highway().lockAltitude.set(true);
                highway().targetY.set(highway().tripTargetY);
            }
        }
        if (!highway().session.recovering()) return false;
        resumeDimension = highway().session.dimension();
        resumeServer = highway().session.server();
        armed = true;
        if (boatTrip) {
            if (highway().session.recoveryAllowed(serverAddress())) pauseBoatWalk();
            else setBoatPaused(false);
        }
        stage = Stage.SETTLING;
        if (highway().session.recoveryAllowed(serverAddress())) {
            info("Recovering the interrupted %s trip.", boatTrip ? "boat" : "Highway Fly");
            notifyTrip(HighwayWebhook.Event.RECOVERY,
                "Found the saved trip and is recovering it before resuming flight.");
        }
        recoveryWorld = null;
        resetLogin();
        return true;
    }

    void pauseForDisconnect() {
        resumeDimension = highway().session.dimension();
        resumeServer = highway().session.server();
        armed = true;
        resetLogin();
        saveRecovery();
        stopWalking();
        stage = Stage.AWAITING_JOIN;
        stageTicks = 0;
        if (boatTrip) pauseBoatWalk();
        info("Travel paused; it will resume on the next join.");
    }

    private void beginLogin() {
        boolean needsLogin = location.requiresLogin(crackedAccount.get(), isResumeDimension());
        if (!needsLogin || loginStarted) return;
        loginStarted = true;
        stage = Stage.LOGIN;
        stageTicks = 0;
        if (LobbyLogin.command(loginPassword.get()) == null) {
            warning("Set login-password or log in manually before portal recovery can continue.");
            notifyTrip(HighwayWebhook.Event.NEEDS_HELP,
                "Login is required, but login-password is blank; set it or log in manually.");
            return;
        }
        loginSent = false;
        loginConfirmed = false;
        loginRejected = false;
        warnedLoginRejected = false;
        loginTicks = 0;
        stage = Stage.LOGIN;
        stageTicks = 0;
        info("Waiting to log in before lobby recovery.");
    }

    /** Waits for the join delay, sends the password once, then waits for a reply or the timeout. */
    private void tickLogin() {
        if (loginConfirmed) {
            finishLogin();
            return;
        }
        if (!crackedAccount.get() && location != LobbyLocation.CRACKED_LOBBY) {
            finishLogin();
            return;
        }
        if (mc.getConnection() == null) return;
        if (!loginSent) {
            if (++loginTicks < joinDelay.get()) return;
            String command = LobbyLogin.command(loginPassword.get());
            if (command == null) {
                // Stay still until credentials are configured or a manual login is confirmed.
                return;
            }
            mc.getConnection().sendCommand(command);
            loginSent = true;
            loginTicks = 0;
            if (debug.get()) info("Sent /login before lobby recovery.");
            return;
        }
        if (++loginTicks < loginTimeout.get()) return;
        if (loginRejected) {
            if (!warnedLoginRejected) {
                warning("The server rejected the login password; waiting for a manual login before recovery.");
                warnedLoginRejected = true;
            }
            return;
        }
        warning("No login confirmation arrived; continuing with lobby recovery.");
        finishLogin();
    }

    private void finishLogin() {
        loginTicks = 0;
        stage = Stage.SETTLING;
        stageTicks = 0;
        if (debug.get()) info("Login finished; continuing lobby recovery.");
    }

    private void resetLogin() {
        loginStarted = false;
        loginSent = false;
        loginConfirmed = false;
        loginRejected = false;
        warnedLoginRejected = false;
        loginTicks = 0;
    }

    /** Remembers a confirmation, or a rejection of the password this module sent. */
    private void onMessage(String raw) {
        if (stage != Stage.LOGIN || loginConfirmed) return;
        if (ChatSender.fromPlayer(raw, this::online)) return;
        LobbyLogin.Result result = LobbyLogin.classify(raw);
        if (result == LobbyLogin.Result.SUCCESS) {
            loginConfirmed = true;
            if (debug.get()) info("The server confirmed the login.");
        } else if (result == LobbyLogin.Result.FAILURE && loginSent && !loginRejected) {
            loginRejected = true;
            if (!warnedLoginRejected) {
                warning("The server rejected /login: %s",
                    LobbyLogin.redact(ChatSender.plain(raw).strip(), loginPassword.get()));
                notifyTrip(HighwayWebhook.Event.LOGIN_FAILED,
                    "The server rejected the login password; portal recovery is waiting for a manual login.");
                warnedLoginRejected = true;
            }
        }
    }

    private boolean online(String name) {
        return mc.getConnection() != null && mc.getConnection().getPlayerInfoIgnoreCase(name) != null;
    }

    @EventHandler(priority = -100)
    private void onTick(TickEvent.Pre event) {
        if (loadingProfile() || highway().profileLoading || mc.player == null || mc.level == null) return;
        if (nativeBoatTrip() && !nativeRecoveryAuthorized()) { releaseNativeBoatRuntime(); return; }
        AutoBoatFly nativeBoat = Modules.get().get(AutoBoatFly.class);
        if (nativeBoat != null && nativeBoat.isActive() && !nativeBoat.isAutomaticTravel() && mountedBoat()) {
            stopWalking();
            return;
        }
        if (standaloneTrip && nativeBoatActive()) {
            standaloneTrip = false;
            boatTrip = true;
            boatModule = LobbyRecoveryStore.NATIVE_BOAT;
            boatYaw = mc.player.getYRot();
            pauseBoatWalk();
            saveRecovery();
        }
        if (!highway().isActive() && !highway().session.recovering()) {
            if (boatTrip && highway().session.flying()) {
                if (!sameBoatServer()) return;
                if (!resumeDimension.equals(dimensionId()) || detectedLocation() != LobbyLocation.NONE) {
                    highway().session.recover(resumeDimension, resumeServer);
                    armed = true;
                    pauseBoatWalk();
                    stage = Stage.SETTLING;
                    recoveryWorld = null;
                } else {
                    if (!dev.tntcoating.core.BoatRecoveryPolicy.keepTrip(nativeBoatTrip(), nativeBoatActive(), boatWalking())) {
                        cancelRecovery();
                        boatTrip = false;
                    } else {
                        if (!boatReady()) { pauseBoatWalk(); return; }
                        float desiredYaw = nativeBoatTrip() && nativeBoat != null ? (float) nativeBoat.heading() : mc.player.getYRot();
                        if (mc.player.tickCount % 20 == 0 && boatYaw != desiredYaw) {
                            boatYaw = desiredYaw;
                            saveRecovery();
                        }
                        highway().prepareReconnectDelay();
                    }
                    return;
                }
            } else {
                if (Files.exists(MeteorClient.FOLDER.toPath().resolve("auto-lobby-recovery.json"))) {
                    resumePending();
                    return;
                }
                if (!armBoatTrip() && (stage != Stage.DONE || recoveryWorld != mc.level)) armStandaloneRecovery();
                return;
            }
        }
        if (!highway().session.recoveryAllowed(serverAddress()) || !armed) return;
        if (!highway().isActive()) highway().prepareReconnectDelay();
        updateLocation();
        if (location == LobbyLocation.WAITING_AREA) {
            stopWalking();
            stage = Stage.QUEUED;
            return;
        }
        beginLogin();
        if (stage == Stage.LOGIN) {
            stopWalking();
            tickLogin();
            return;
        }
        if (isResumeDimension()) {
            if (stage != Stage.RESUMING && stage != Stage.EXITING) {
                stopWalking();
                stage = Stage.RESUMING;
                stageTicks = 0;
                info("Back in %s; resuming %s.", dimensionLabel(), boatTrip ? "boat travel" : "Highway Fly");
                notifyTrip(HighwayWebhook.Event.DIMENSION,
                    "Back in " + dimensionLabel() + "; resuming the route.");
            }
            tickResuming();
            return;
        }
        if (stage == Stage.RESUMING || stage == Stage.EXITING) {
            stopWalking();
            portalExitTarget = null;
            portalExitTicks = 0;
            stage = Stage.SETTLING;
            stageTicks = 0;
            return;
        }
        switch (stage) {
            case SETTLING -> {
                if (++stageTicks < joinDelay.get()) return;
                if (enterPortal.get()) searchForPortal();
                else waitForReconnect("Waiting for a reconnect into " + dimensionLabel() + ".");
            }
            case WAITING -> {
                if (++stageTicks < rescanDelay.get()) return;
                stageTicks = 0;
                if (enterPortal.get()) searchForPortal();
            }
            case SEARCHING -> {
                if (enterPortal.get()) searchForPortal();
                else waitForReconnect("Waiting for a reconnect into " + dimensionLabel() + ".");
            }
            case WALKING -> tickWalking();
            case ENTERING -> tickEntering();
            default -> {}
        }
    }

    private void tickResuming() {
        if (++stageTicks < RESUME_DELAY_TICKS || mc.gui.screen() != null) {
            releaseMovement();
            return;
        }
        if (insidePortal()) {
            if (boatTrip && mc.player.isPassenger()) {
                if (boatReady()) {
                    setBoatPaused(false);
                    mc.player.setYRot(boatYaw);
                    mc.options.keyUp.setDown(true);
                    inputActive = true;
                } else releaseMovement();
                return;
            }
            tickPortalExit();
            return;
        }
        releaseMovement();
        portalExitTarget = null;
        if (boatTrip ? !boatReady() : !standaloneTrip && mc.player.isPassenger() || mc.player.isInWater() || mc.player.isInLava()
            || mc.player.isDeadOrDying()) {
            if (!warnedCantResume) {
                warning("Cannot resume travel here; waiting for %s.", boatTrip ? "the boat driver seat" : "a safe position");
                notifyTrip(HighwayWebhook.Event.NEEDS_HELP,
                    "Cannot resume Highway Fly here; waiting for a safe, supported position.");
                warnedCantResume = true;
            }
            return;
        }
        var arrival = LobbyRecoveryDecisions.arrived(standaloneTrip, resumeFlight.get());
        if (arrival == LobbyRecoveryDecisions.Arrival.COMPLETE_STANDALONE) {
            finishRecovery();
            highway().session.stop();
            standaloneTrip = false;
            highway().releaseReconnect();
            info("Lobby recovery complete.");
            return;
        }
        if (arrival == LobbyRecoveryDecisions.Arrival.STOP_TRIP) {
            info("Back in %s; resume-flight is off, ending the trip.", dimensionLabel());
            notifyTrip(HighwayWebhook.Event.STOPPED,
                "Back in " + dimensionLabel() + ", but resume-flight is off; the trip was ended.");
            boolean nativeTrip = nativeBoatTrip();
            cancelRecovery();
            stage = Stage.DONE;
            recoveryWorld = mc.level;
            if (nativeTrip) {
                AutoBoatFly nativeBoat = Modules.get().get(AutoBoatFly.class);
                if (nativeBoat != null && nativeBoat.isActive()) nativeBoat.disable();
            }
            if (highway().isActive()) highway().disable();
            return;
        }
        // Hand movement back to the controller that owns the saved trip.
        finishRecovery();
        if (boatTrip) {
            mc.player.setYRot(boatYaw);
            highway().session.start(resumeDimension, resumeServer);
            setBoatPaused(false);
            AutoBoatFly nativeBoat = Modules.get().get(AutoBoatFly.class);
            if (!nativeBoatTrip() || nativeBoat != null && nativeBoat.autoWalkEnabled())
                Modules.get().get(AutoWalk.class).enable();
            highway().releaseReconnect();
            saveRecovery();
            info("Boat travel resumed.");
        } else highway().resumeRecoveredFlight();
    }

    private void tickPortalExit() {
        stage = Stage.EXITING;
        if (portalExitTarget == null) {
            if (portalExitTicks++ % 100 != 0) return;
            BlockPos target = highway().destination.get();
            Vec3 desired = standaloneTrip || boatTrip ? Vec3.directionFromRotation(0, boatTrip ? boatYaw : mc.player.getYRot())
                : new Vec3(target.getX()-mc.player.getX(),0,target.getZ()-mc.player.getZ());
            portalExitTarget = HighwayRecoveryTargets.portalExit(mc.player.getBoundingBox(), mc.player.position(),
                desired, new HighwayRecoveryTargets.ExitProbe() {
                    public boolean clear(AABB box) { return safeExitSweep(box); }
                    public boolean portal(AABB box) { return PortalLookahead.contains(box,bodyProbe); }
                    public boolean supported(AABB box) {
                        return highway().loaded(box.expandTowards(0,-.25,0))
                            && mc.level.getBlockCollisions(mc.player,box.move(0,-.2,0)).iterator().hasNext();
                    }
                }).orElse(null);
            if (portalExitTarget == null) {
                releaseMovement();
                if (!warnedCantResume) {
                    warning("Waiting for a loaded, supported exit from the destination portal.");
                    notifyTrip(HighwayWebhook.Event.NEEDS_HELP,
                        "Waiting for a loaded, supported exit from the destination portal.");
                    warnedCantResume = true;
                }
                return;
            }
            portalExitTicks = 0;
        }
        AABB swept = mc.player.getBoundingBox().minmax(mc.player.getBoundingBox()
            .move(portalExitTarget.subtract(mc.player.position())));
        if (++portalExitTicks > 100 || !safeExitSweep(swept) || !supportedExitRoute(portalExitTarget)) {
            releaseMovement();
            portalExitTarget = null;
            portalExitTicks = 0;
            return;
        }
        mc.player.setYRot(HighwayCourse.yaw(mc.player.position(),portalExitTarget));
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(false);
        inputActive = true;
    }

    private boolean safeExitSweep(AABB box) {
        if (!highway().loaded(box) || !mc.level.noCollision(mc.player,box)) return false;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),
            BlockPos.containing(box.maxX,box.maxY,box.maxZ))) {
            if (!mc.level.getFluidState(pos).isEmpty()) return false;
        }
        return true;
    }

    private boolean supportedExitRoute(Vec3 target) {
        Vec3 delta = target.subtract(mc.player.position());
        int steps = Math.max(1,(int)Math.ceil(delta.length()/.25));
        if (steps > 20) return false;
        for (int step=1;step<=steps;step++) {
            AABB box=mc.player.getBoundingBox().move(delta.scale((double)step/steps));
            if (!highway().loaded(box.expandTowards(0,-.25,0))
                || !mc.level.getBlockCollisions(mc.player,box.move(0,-.2,0)).iterator().hasNext()) return false;
        }
        return true;
    }

    private void searchForPortal() {
        stage = Stage.SEARCHING;
        Optional<LobbyPortalFinder.Result> found = findPortal();
        if (found.isEmpty()) {
            waitForReconnect("No Nether portal within " + searchRadius.get()
                + " blocks; waiting for a reconnect into " + dimensionLabel() + ".", true);
            return;
        }

        warnedNoPortal = false;
        warnedStuck = false;
        warnedAirborne = false;
        BlockPos target = found.get().pos();
        startWalking(target);
        info("Walking to the Nether portal at %d, %d, %d.", target.getX(), target.getY(), target.getZ());
        notifyTrip(HighwayWebhook.Event.PORTAL,
            "Found the Nether portal; walking to it.",
            new HighwayWebhook.Field("🌀 Portal", target.getX() + " " + target.getY() + " "
                + target.getZ(), true, true));
    }

    private Optional<LobbyPortalFinder.Result> findPortal() {
        if (mc.level == null || mc.player == null) return Optional.empty();
        failedPortals.entrySet().removeIf(entry -> System.nanoTime() - entry.getValue() >= 0);
        return LobbyPortalFinder.nearest(mc.player.position(), searchRadius.get(), searchHeight.get(),
            mc.level.getMinY(), mc.level.getMaxY() - 1, new LobbyPortalFinder.Probe() {
                public boolean loaded(int x,int z) { return finderProbe.loaded(x,z); }
                public boolean maybePortal(int x,int y,int z) { return finderProbe.maybePortal(x,y,z); }
                public boolean portal(BlockPos pos) {
                    return finderProbe.portal(pos) && failedPortals.keySet().stream()
                        .noneMatch(failed -> failed.distSqr(pos) <= 64);
                }
            });
    }

    private void waitForReconnect(String message) {
        waitForReconnect(message, false);
    }

    private void waitForReconnect(String message, boolean needsHelp) {
        stopWalking();
        stage = Stage.WAITING;
        stageTicks = 0;
        if (!warnedNoPortal) {
            warning(message);
            notifyTrip(needsHelp ? HighwayWebhook.Event.NEEDS_HELP : HighwayWebhook.Event.RECOVERY,
                message);
            warnedNoPortal = true;
        } else if (debug.get()) {
            info("Still waiting for %s.", dimensionLabel());
        }
    }

    private void startWalking(BlockPos target) {
        portal = target;
        stage = Stage.WALKING;
        stageTicks = 0;
        pathTicks = 0;
        stuckTicks = 0;
        pathing = false;
        lastWalkPosition = mc.player.position();

        if (recoveryMovement() == LobbyRecoveryDecisions.Movement.BOAT || !useBaritone.get()
            || PathManagers.get() instanceof NopPathManager) return;
        try {
            PathManagers.get().moveTo(target);
            pathing = true;
        } catch (RuntimeException | LinkageError e) {
            if (debug.get()) warning("Baritone could not path to the portal; walking directly.");
        }
    }

    private void tickWalking() {
        if (portal == null) {
            stage = Stage.SEARCHING;
            return;
        }
        if (mc.gui.screen() != null) {
            releaseMovement();
            return;
        }
        if (insidePortal()) {
            enterPortal();
            return;
        }
        if (!finderProbe.portal(portal)) {
            stopWalking();
            stage = Stage.SEARCHING;
            stageTicks = 0;
            return;
        }
        if (mc.player.isFallFlying()) {
            releaseMovement();
            if (!warnedAirborne) {
                warning("Land before walking into the portal.");
                notifyTrip(HighwayWebhook.Event.NEEDS_HELP,
                    "Land before walking into the Nether portal; recovery is waiting.");
                warnedAirborne = true;
            }
            return;
        }
        if (recoveryMovement() == LobbyRecoveryDecisions.Movement.WAIT) {
            releaseMovement();
            if (!warnedCantResume) {
                warning("Get out of the vehicle first.");
                notifyTrip(HighwayWebhook.Event.NEEDS_HELP,
                    "Get out of the vehicle so portal recovery can walk to the Nether portal.");
                warnedCantResume = true;
            }
            return;
        }

        if (pathing) {
            pathTicks++;
            boolean close = horizontalDistanceSq(portal) <= PATH_CLOSE_DISTANCE_SQ;
            boolean stopped;
            try {
                stopped = pathTicks > PATH_START_GRACE_TICKS && !PathManagers.get().isPathing();
            } catch (RuntimeException | LinkageError e) {
                stopped = true;
            }
            boolean timedOut = pathTicks > PATH_TIMEOUT_TICKS;
            if (!close && !stopped && !timedOut) return;
            if (!close && (stopped || timedOut) && debug.get())
                info("Baritone did not reach the portal; trying direct walking.");
            stopPathing();
        }

        walkDirectly();
    }

    private void walkDirectly() {
        BlockPos target = portal;
        if (target == null) return;
        Vec3 center = Vec3.atCenterOf(target);
        mc.player.setYRot(HighwayCourse.yaw(mc.player.position(), center));
        boolean boatSteering = recoveryMovement() == LobbyRecoveryDecisions.Movement.BOAT;
        if (boatTrip) setBoatPaused(!boatSteering);
        mc.options.keyUp.setDown(true);
        inputActive = true;

        boolean jump = boatSteering ? center.y > mc.player.getY() + .3 : mc.player.onGround()
            && (mc.player.horizontalCollision || center.y > mc.player.getY() + 1.1);
        mc.options.keyJump.setDown(jump);
        if (boatTrip) mc.options.keySprint.setDown(boatSteering && center.y < mc.player.getY() - .3);

        Vec3 position = mc.player.position();
        if (position.distanceToSqr(lastWalkPosition) >= MOVEMENT_EPSILON_SQ) {
            lastWalkPosition = position;
            stuckTicks = 0;
        } else if (++stuckTicks > WALK_STUCK_TICKS) {
            if (!warnedStuck) {
                warning("Cannot reach the portal; move closer or turn on use-baritone.");
                notifyTrip(HighwayWebhook.Event.NEEDS_HELP,
                    "Cannot reach the portal; move closer or turn on use-baritone.");
                warnedStuck = true;
            }
            failPortalCandidate();
        }
    }

    private void failPortalCandidate() {
        if (portal != null) {
            if (failedPortals.size() >= 16) failedPortals.clear();
            failedPortals.put(portal.immutable(),System.nanoTime()+120_000_000_000L);
        }
        stopWalking();
        stage = Stage.WAITING;
        stageTicks = 0;
    }

    private void enterPortal() {
        stopPathing();
        releaseMovement();
        stage = Stage.ENTERING;
        stageTicks = 0;
    }

    private void tickEntering() {
        stageTicks++;
        releaseMovement();
        if (!insidePortal()) {
            if (stageTicks > 20) {
                stage = Stage.WALKING;
                stageTicks = 0;
            }
            return;
        }
        if (stageTicks == 1) {
            info("In the portal; waiting for the Nether.");
            notifyTrip(HighwayWebhook.Event.PORTAL, "In the Nether portal; waiting for the dimension change.");
        } else if (stageTicks == PORTAL_WAIT_WARNING_TICKS) {
            warning("The portal has not transported us yet; still waiting.");
            notifyTrip(HighwayWebhook.Event.NEEDS_HELP,
                "The portal has not transported us after ten seconds; recovery is still waiting.");
        }
    }

    private boolean insidePortal() {
        return mc.player != null && PortalLookahead.contains(mc.player.getBoundingBox(), bodyProbe);
    }

    private double horizontalDistanceSq(BlockPos target) {
        double dx = target.getX() + .5 - mc.player.getX();
        double dz = target.getZ() + .5 - mc.player.getZ();
        return dx * dx + dz * dz;
    }

    boolean isKnownLobby() {
        return detectedLocation() != LobbyLocation.NONE;
    }

    private LobbyLocation detectedLocation() {
        return mc.player == null ? LobbyLocation.NONE : LobbyLocation.detect(dimensionId(),
            mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    private void updateLocation() {
        LobbyLocation detected = detectedLocation();
        boolean changedWorld = recoveryWorld != mc.level;
        if (!changedWorld && (detected == LobbyLocation.NONE || detected == location)) return;
        stopWalking();
        resetTransientState();
        resetLogin();
        failedPortals.clear();
        recoveryWorld = mc.level;
        // Keep the spawn classification while walking away from it towards the portal.
        location = detected;
        stage = Stage.SETTLING;
        if (location != LobbyLocation.NONE) info("Detected %s.", switch (location) {
            case CRACKED_LOBBY -> "cracked lobby; login is required before the portal";
            case LOBBY -> "lobby; looking for the portal";
            case WAITING_AREA -> "waiting area; waiting for the server to move you";
            default -> "recovery area";
        });
    }

    private boolean isResumeDimension() {
        return location == LobbyLocation.NONE && mc.level != null && resumeDimension != null && resumeDimension.equals(dimensionId());
    }

    private String dimensionId() {
        return mc.level == null ? null : mc.level.dimension().identifier().toString();
    }

    private String dimensionLabel() {
        if (resumeDimension == null) return "the target dimension";
        int colon = resumeDimension.indexOf(':');
        return colon < 0 ? resumeDimension : resumeDimension.substring(colon + 1);
    }

    void stopWalking() {
        stopPathing();
        releaseMovement();
        portal = null;
    }

    private void stopPathing() {
        if (!pathing) return;
        pathing = false;
        try {
            if (!(PathManagers.get() instanceof NopPathManager)) PathManagers.get().stop();
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void releaseMovement() {
        if (boatTrip && highway().session.recovering()) setBoatPaused(true);
        if (!inputActive) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        if (boatTrip) mc.options.keySprint.setDown(false);
        inputActive = false;
    }

    void saveRecovery() {
        if (standaloneTrip) return;
        // A newly started Highway Fly trip takes ownership of the shared recovery marker.
        if (highway().isActive() && boatTrip) {
            boatTrip = false;
            setBoatPaused(false);
        }
        try {
            String tripDimension = highway().session.dimension() == null ? resumeDimension : highway().session.dimension();
            String tripServer = highway().session.dimension() == null ? resumeServer : highway().session.server();
            if (tripDimension == null) return;
            if (boatTrip) recoveryStore.saveBoat(tripDimension, tripServer, boatYaw, boatModule);
            else if (highway().tripDestination != null) recoveryStore.save(tripDimension,tripServer,highway().tripDestination,highway().tripTargetY);
            else recoveryStore.save(tripDimension,tripServer);
            highway().durableTripAvailable = true;
        } catch (IOException | RuntimeException e) {
            warning("Could not save the Highway Fly lobby recovery state: %s", e.getMessage());
        }
    }

    private String serverAddress() {
        AutoBoatFly boat = Modules.get().get(AutoBoatFly.class);
        if (boat != null && (nativeBoatTrip() || nativeBoatActive())) return boat.recoveryServer();
        return mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip;
    }

    private boolean sameServer(String first, String second) {
        if (first == null || second == null) return first == second;
        return first.equalsIgnoreCase(second);
    }

    private void clearRecoveryFile() {
        try {
            recoveryStore.clear();
        } catch (IOException e) {
            if (debug.get()) warning("Could not clear the Highway Fly lobby recovery state: %s", e.getMessage());
        }
    }

    void cancelRecovery() {
        highway().session.stop();
        if (boatTrip) setBoatPaused(false);
        Optional<LobbyRecoveryStore.Recovery> saved = recoveryStore.load();
        if (mc.level == null || saved.isEmpty() || saved.get().isForServer(serverAddress())) clearRecoveryFile();
        resetRecovery();
        boatTrip = false;
        standaloneTrip = false;
        highway().durableTripAvailable = false;
        highway().tripDestination = null;
    }

    private void finishRecovery() {
        armed = false;
        stage = Stage.DONE;
        resetLogin();
        stopWalking();
    }

    private void resetTransientState() {
        stageTicks = 0;
        pathTicks = 0;
        stuckTicks = 0;
        pathing = false;
        warnedNoPortal = false;
        warnedStuck = false;
        warnedAirborne = false;
        warnedCantResume = false;
        portalExitTarget = null;
        portalExitTicks = 0;
    }

    void resetRecovery() {
        stopWalking();
        armed = false;
        stage = Stage.IDLE;
        resumeDimension = null;
        resumeServer = null;
        recoveryWorld = null;
        location = LobbyLocation.NONE;
        portal = null;
        resetLogin();
        resetTransientState();
        releaseMovement();
    }

    public String getInfoString() {
        return switch (stage) {
            case AWAITING_JOIN -> "Waiting for join";
            case SETTLING -> "Settling";
            case LOGIN -> "Logging in";
            case QUEUED -> "Waiting area";
            case SEARCHING -> "Finding portal";
            case WALKING -> "Walking to portal";
            case ENTERING -> "Entering portal";
            case WAITING -> "Waiting for Nether";
            case RESUMING -> "Resuming flight";
            case EXITING -> "Leaving portal";
            case DONE -> "Done";
            case IDLE -> "Waiting for saved trip";
        };
    }

    void cancelNativeBoatRecovery() {
        if (nativeBoatTrip()) { cancelRecovery(); return; }
        var saved = recoveryStore.load();
        if (saved.isPresent() && LobbyRecoveryStore.NATIVE_BOAT.equals(saved.get().boatModule())) clearRecoveryFile();
    }

    /** Explicitly selecting Auto Boat Fly transfers an old external boat marker without losing its route identity. */
    void claimNativeBoatController() {
        if (!nativeBoatActive()) return;
        if (!boatTrip && !standaloneTrip && highway().session.recovering()) {
            cancelRecovery();
            armBoatTrip();
        }
        if (!boatTrip || nativeBoatTrip()) return;
        boatModule = LobbyRecoveryStore.NATIVE_BOAT;
        if (highway().session.recoveryAllowed(serverAddress())) pauseBoatWalk();
        saveRecovery();
    }

    private boolean nativeRecoveryAuthorized() {
        AutoBoatFly boat = Modules.get().get(AutoBoatFly.class);
        return boat != null && boat.permitsSavedRecovery();
    }

    /** End in-memory ownership without erasing a marker needed by another profile/server. */
    void releaseNativeBoatRuntime() {
        if (!nativeBoatTrip()) return;
        highway().session.stop();
        resetRecovery(); boatTrip = standaloneTrip = false;
        reconnectAfterLoss = false;
        highway().releaseReconnect();
    }

    Optional<LobbyRecoveryStore.Recovery> savedNativeBoatTrip() {
        return recoveryStore.load().filter(marker -> LobbyRecoveryStore.NATIVE_BOAT.equals(marker.boatModule()) && marker.boatYaw() != null);
    }

    /** A new explicit boat trip replaces the shared marker before helpers can load stale trips. */
    boolean startNativeBoatTrip(String dimension, String server, float yaw) {
        cancelRecovery();
        boatTrip = true; standaloneTrip = false; boatModule = LobbyRecoveryStore.NATIVE_BOAT;
        boatYaw = yaw; resumeDimension = dimension; resumeServer = server;
        highway().session.start(dimension, server);
        stage = Stage.DONE; recoveryWorld = mc.level;
        highway().durableTripAvailable = false;
        saveRecovery();
        return dev.tntcoating.core.BoatRecoveryPolicy.saveActive(savedNativeBoatTrip().orElse(null),
            server, dimension, yaw, true, true);
    }

    boolean restoreNativeBoatTrip() {
        if (savedNativeBoatTrip().isEmpty()) return false;
        highway().session.stop(); resetRecovery(); boatTrip = standaloneTrip = false;
        return resumePending() && nativeBoatTrip();
    }

    void nativeBoatHeading(float yaw) {
        if (!nativeBoatTrip() || boatYaw == yaw) return;
        boatYaw = yaw; saveRecovery();
    }

    String nativeBoatDimension() { return nativeBoatTrip() ? resumeDimension : null; }
    String nativeBoatServer() { return resumeServer == null || resumeServer.startsWith("local:") ? "Singleplayer" : resumeServer; }

    public boolean nativeBoatTrip() { return boatTrip && LobbyRecoveryStore.NATIVE_BOAT.equals(boatModule); }
    public boolean isBoatRecovering() { return boatTrip && highway().session.recovering(); }
    public boolean isNativeBoatRecovering() { return nativeBoatTrip() && highway().session.recovering(); }

    private boolean nativeBoatActive() {
        AutoBoatFly nativeBoat = Modules.get().get(AutoBoatFly.class);
        return nativeBoat != null && nativeBoat.wantsLobbyRecovery();
    }

    private void notifyTrip(HighwayWebhook.Event event, String description, HighwayWebhook.Field... fields) {
        notifyTrip(event, description, java.util.List.of(fields));
    }

    private void notifyTrip(HighwayWebhook.Event event, String description, java.util.List<HighwayWebhook.Field> fields) {
        AutoBoatFly nativeBoat = Modules.get().get(AutoBoatFly.class);
        if (nativeBoatTrip() && nativeBoat != null && nativeBoat.isActive()) nativeBoat.notifyTrip(event, description, fields);
        else highway().notifyWebhook(event, description, fields);
    }

    /** Enabled AutoLobby can recover a fresh join without a Highway Fly marker. */
    private boolean armStandaloneRecovery() {
        if (!isActive() || mc.player == null || mc.level == null || mc.getConnection() == null
            || highway().isActive() || waitingForTripRecord
            || !LobbyRecoveryDecisions.standaloneAllowed(independentlyEnabled(), nativeBoatActive(),
                detectedLocation() != LobbyLocation.NONE)) return false;
        standaloneTrip = true;
        boatTrip = false;
        resumeDimension = targetDimension.get().strip();
        if (resumeDimension.isBlank()) resumeDimension = "minecraft:the_nether";
        resumeServer = serverAddress();
        highway().session.recover(resumeDimension, resumeServer);
        armed = true;
        stage = Stage.SETTLING;
        recoveryWorld = null;
        resetTransientState();
        resetLogin();
        highway().prepareReconnectDelay();
        return true;
    }

    private boolean mountedBoat() {
        return mc.player != null && mc.player.isAlive()
            && mc.player.getRootVehicle() instanceof AbstractBoat boat
            && boat.getControllingPassenger() == mc.player;
    }

    private LobbyRecoveryDecisions.Movement recoveryMovement() {
        return LobbyRecoveryDecisions.movement(boatTrip, mc.player.isPassenger(), boatReady());
    }

    private boolean boatReady() {
        Module phase = Modules.get().get(boatModule);
        return mountedBoat() && phase != null && phase.isActive()
            && (!(phase instanceof AutoBoatFly nativeBoat) || nativeBoat.isAutomaticTravel());
    }

    private boolean boatWalking() {
        return boatReady() && (nativeBoatActive() || Modules.get().isActive(AutoWalk.class));
    }

    boolean boatHomesAllowed() {
        return !boatTrip || highway().session.flying() && sameBoatServer()
            && java.util.Objects.equals(resumeDimension, dimensionId())
            && detectedLocation() == LobbyLocation.NONE;
    }

    private boolean sameBoatServer() {
        return resumeServer == null ? serverAddress() == null
            : resumeServer.equalsIgnoreCase(serverAddress());
    }

    private boolean armBoatTrip() {
        if (!independentlyEnabled() && !nativeBoatActive() || detectedLocation() != LobbyLocation.NONE
            || waitingForTripRecord) return false;
        boatModule = nativeBoatActive() ? LobbyRecoveryStore.NATIVE_BOAT : "boat-phase";
        if (!boatWalking()) return false;
        boatTrip = true;
        standaloneTrip = false;
        AutoBoatFly nativeBoat = Modules.get().get(AutoBoatFly.class);
        boatYaw = nativeBoatActive() && nativeBoat != null ? (float) nativeBoat.heading() : mc.player.getYRot();
        resumeDimension = dimensionId();
        resumeServer = serverAddress();
        highway().session.start(resumeDimension, resumeServer);
        saveRecovery();
        highway().prepareReconnectDelay();
        return true;
    }

    private void pauseBoatWalk() {
        Modules.get().get(AutoWalk.class).disable();
        setBoatPaused(true);
    }

    /** Optional addon bridge: Comet must also load without Quiettee installed. */
    private void setBoatPaused(boolean paused) {
        Module phase = Modules.get().get(boatModule);
        if (phase == null) return;
        if (phase instanceof AutoBoatFly nativeBoat) {
            nativeBoat.pauseAutomation(nativeBoat.isAutomaticTravel() && paused,
                nativeBoat.isAutomaticTravel() && boatTrip && highway().session.recoveryAllowed(serverAddress()));
            return;
        }
        try {
            phase.getClass().getMethod("pauseAutomation", boolean.class, boolean.class)
                .invoke(phase, paused, boatTrip && highway().session.recoveryAllowed(serverAddress()));
        } catch (ReflectiveOperationException e) {
            if (!warnedBoatBridge) {
                warning("Update Quiettee Utils for boat lobby recovery; its automation bridge is missing.");
                warnedBoatBridge = true;
            }
        }
    }

    @Override protected void started() {
        if (nativeBoatTrip() && nativeBoatActive() && highway().session.flying()) {
            highway().prepareReconnectDelay(); return;
        }
        if (!highway().isActive() && !highway().profileLoading && !resumePending() && !armBoatTrip()) armStandaloneRecovery();
    }
    @Override protected void stopped() {
        stopWalking();
        if (boatTrip) {
            boatTrip = false;
            setBoatPaused(false);
            if (!loadingProfile() && !highway().profileLoading) clearRecoveryFile();
        }
        if (!highway().profileLoading) {
            highway().releaseReconnect();
            if (!highway().isActive()) {
                resetRecovery();
                highway().session.stop();
            }
        }
    }
    @EventHandler private void onChat(ReceiveMessageEvent event) {
        if (!loadingProfile() && highway().session.recovering()) onMessage(event.getMessage().getString());
    }
    @EventHandler private void joined(GameJoinedEvent event) {
        if (!loadingProfile() && !highway().profileLoading && !highway().isActive()) {
            highway().releaseReconnect();
            if (!resumePending() && !armBoatTrip()) armStandaloneRecovery();
            highway().prepareReconnectDelay();
        }
    }
    @EventHandler private void left(GameLeftEvent event) {
        if (!highway().isActive() && (highway().session.recovering() || boatTrip && highway().session.flying())) {
            highway().session.disconnected(true);
            pauseForDisconnect();
            if (reconnectAfterLoss) highway().armReconnect();
            else highway().releaseReconnect();
        }
    }

    private boolean reconnectAfterLoss;
    void onClientDisconnect(boolean transferring, boolean unexpected) {
        if (transferring || !highway().session.recovering() && !(boatTrip && highway().session.flying())) return;
        reconnectAfterLoss = unexpected;
        if (!unexpected) highway().releaseReconnect();
    }
    @EventHandler private void connecting(ServerConnectBeginEvent event) {
        if (!highway().isActive()) highway().onServerConnect(event);
    }
    @EventHandler private void screen(OpenScreenEvent event) {
        if (!highway().isActive()) highway().onOpenScreen(event);
    }
}
