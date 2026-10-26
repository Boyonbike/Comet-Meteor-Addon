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
import dev.tntcoating.core.LobbyPortalFinder;
import dev.tntcoating.core.LobbyRecoveryStore;
import dev.tntcoating.core.PortalLookahead;
import java.io.IOException;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import meteordevelopment.meteorclient.MeteorClient;
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

/** Recovers an existing saved trip; general standalone lobby recovery is deferred. */
public final class AutoLobby extends HighwayHelper {
    public AutoLobby() { super("auto-lobby", "Recover a saved Highway Fly trip through the lobby."); }

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

    private final Setting<Boolean> resumeFlight = sgGeneral.add(new BoolSetting.Builder()
        .name("resume-flight")
        .description("Turn Highway Fly back on after reaching the target dimension.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> crackedAccount = sgGeneral.add(new BoolSetting.Builder()
        .name("cracked-account")
        .description("Log in with /login before walking to the recovery portal.")
        .defaultValue(false)
        .build());

    private final Setting<String> loginPassword = sgGeneral.add(new StringSetting.Builder()
        .name("login-password")
        .description("Password sent with /login. It is stored in plain text in the Meteor config.")
        .defaultValue("")
        .visible(crackedAccount::get)
        .wide()
        .build());

    private final Setting<Integer> loginTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("login-timeout")
        .description("Ticks to wait for the server's login confirmation before recovery continues. A rejected password waits for a manual login.")
        .defaultValue(200)
        .range(20, 1200)
        .sliderRange(20, 400)
        .visible(crackedAccount::get)
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
        Optional<LobbyRecoveryStore.Recovery> saved = recoveryStore.load();
        waitingForTripRecord = saved.isEmpty()
            && Files.exists(MeteorClient.FOLDER.toPath().resolve("auto-lobby-recovery.json"));
        if (waitingForTripRecord) {
            warning("Saved trip cannot be read yet; waiting instead of starting on an unknown server.");
            return true;
        }
        if (!highway().session.recovering() && saved.isPresent()) {
            LobbyRecoveryStore.Recovery recovery = saved.get();
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
        stage = Stage.SETTLING;
        if (highway().session.recoveryAllowed(serverAddress())) {
            info("Recovering the interrupted Highway Fly trip.");
            highway().notifyWebhook(HighwayWebhook.Event.RECOVERY,
                "Found the saved trip and is recovering it before resuming flight.");
        }
        beginLogin();
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
        info("Highway Fly trip paused; it will resume on the next join.");
    }

    private void beginLogin() {
        if (!crackedAccount.get() || loginStarted) return;
        if (LobbyLogin.command(loginPassword.get()) == null) {
            warning("Cracked account is on but login-password is blank; skipping the login.");
            highway().notifyWebhook(HighwayWebhook.Event.NEEDS_HELP,
                "Cracked account is on, but login-password is blank; portal recovery cannot log in.");
            return;
        }
        loginStarted = true;
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
        if (!crackedAccount.get()) {
            finishLogin();
            return;
        }
        if (mc.getConnection() == null) return;
        if (!loginSent) {
            if (++loginTicks < joinDelay.get()) return;
            String command = LobbyLogin.command(loginPassword.get());
            if (command == null) {
                warning("Cracked account is on but login-password is blank; skipping the login.");
                highway().notifyWebhook(HighwayWebhook.Event.NEEDS_HELP,
                    "Cracked account is on, but login-password is blank; portal recovery cannot log in.");
                finishLogin();
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
                highway().notifyWebhook(HighwayWebhook.Event.LOGIN_FAILED,
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
        if (!highway().isActive() && !highway().session.recovering()) {
            if (mc.player.tickCount % 100 == 0) resumePending();
            return;
        }
        if (!highway().session.recoveryAllowed(serverAddress()) || !armed) return;
        if (!highway().isActive()) highway().prepareReconnectDelay();
        if (stage == Stage.LOGIN) {
            tickLogin();
            return;
        }
        if (isResumeDimension()) {
            if (stage != Stage.RESUMING && stage != Stage.EXITING) {
                stopWalking();
                stage = Stage.RESUMING;
                stageTicks = 0;
                info("Back in %s; resuming Highway Fly.", dimensionLabel());
                highway().notifyWebhook(HighwayWebhook.Event.DIMENSION,
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
            tickPortalExit();
            return;
        }
        releaseMovement();
        portalExitTarget = null;
        if (mc.player.isPassenger() || mc.player.isInWater() || mc.player.isInLava()
            || mc.player.isDeadOrDying()) {
            if (!warnedCantResume) {
                warning("Cannot resume Highway Fly here; waiting.");
                highway().notifyWebhook(HighwayWebhook.Event.NEEDS_HELP,
                    "Cannot resume Highway Fly here; waiting for a safe, supported position.");
                warnedCantResume = true;
            }
            return;
        }
        if (!resumeFlight.get()) {
            info("Back in %s; resume-flight is off, ending the trip.", dimensionLabel());
            highway().notifyWebhook(HighwayWebhook.Event.STOPPED,
                "Back in " + dimensionLabel() + ", but resume-flight is off; the trip was ended.");
            cancelRecovery();
            if (highway().isActive()) highway().disable();
            return;
        }
        // Clear only this server's marker, then hand movement back to the flight controller.
        finishRecovery();
        highway().resumeRecoveredFlight();
    }

    private void tickPortalExit() {
        stage = Stage.EXITING;
        if (portalExitTarget == null) {
            if (portalExitTicks++ % 100 != 0) return;
            BlockPos target = highway().destination.get();
            Vec3 desired = new Vec3(target.getX()-mc.player.getX(),0,target.getZ()-mc.player.getZ());
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
                    highway().notifyWebhook(HighwayWebhook.Event.NEEDS_HELP,
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
        highway().notifyWebhook(HighwayWebhook.Event.PORTAL,
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
            highway().notifyWebhook(needsHelp ? HighwayWebhook.Event.NEEDS_HELP : HighwayWebhook.Event.RECOVERY,
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

        if (!useBaritone.get() || PathManagers.get() instanceof NopPathManager) return;
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
                highway().notifyWebhook(HighwayWebhook.Event.NEEDS_HELP,
                    "Land before walking into the Nether portal; recovery is waiting.");
                warnedAirborne = true;
            }
            return;
        }
        if (mc.player.isPassenger()) {
            releaseMovement();
            if (!warnedCantResume) {
                warning("Get out of the vehicle first.");
                highway().notifyWebhook(HighwayWebhook.Event.NEEDS_HELP,
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
            if (!close && (stopped || timedOut)) {
                failPortalCandidate();
                return;
            }
            stopPathing();
        }

        walkDirectly();
    }

    private void walkDirectly() {
        BlockPos target = portal;
        if (target == null) return;
        Vec3 center = Vec3.atCenterOf(target);
        mc.player.setYRot(HighwayCourse.yaw(mc.player.position(), center));
        mc.options.keyUp.setDown(true);
        inputActive = true;

        boolean jump = mc.player.onGround()
            && (mc.player.horizontalCollision || center.y > mc.player.getY() + 1.1);
        mc.options.keyJump.setDown(jump);

        Vec3 position = mc.player.position();
        if (position.distanceToSqr(lastWalkPosition) >= MOVEMENT_EPSILON_SQ) {
            lastWalkPosition = position;
            stuckTicks = 0;
        } else if (++stuckTicks > WALK_STUCK_TICKS) {
            if (!warnedStuck) {
                warning("Cannot reach the portal; move closer or turn on use-baritone.");
                highway().notifyWebhook(HighwayWebhook.Event.NEEDS_HELP,
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
            highway().notifyWebhook(HighwayWebhook.Event.PORTAL, "In the Nether portal; waiting for the dimension change.");
        } else if (stageTicks == PORTAL_WAIT_WARNING_TICKS) {
            warning("The portal has not transported us yet; still waiting.");
            highway().notifyWebhook(HighwayWebhook.Event.NEEDS_HELP,
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

    private boolean isResumeDimension() {
        return mc.level != null && resumeDimension != null && resumeDimension.equals(dimensionId());
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
        if (!inputActive) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        inputActive = false;
    }

    void saveRecovery() {
        try {
            String tripDimension = highway().session.dimension() == null ? resumeDimension : highway().session.dimension();
            String tripServer = highway().session.dimension() == null ? resumeServer : highway().session.server();
            if (tripDimension == null) return;
            if (highway().tripDestination != null) recoveryStore.save(tripDimension,tripServer,highway().tripDestination,highway().tripTargetY);
            else recoveryStore.save(tripDimension,tripServer);
            highway().durableTripAvailable = true;
        } catch (IOException | RuntimeException e) {
            warning("Could not save the Highway Fly lobby recovery state: %s", e.getMessage());
        }
    }

    private String serverAddress() {
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
        Optional<LobbyRecoveryStore.Recovery> saved = recoveryStore.load();
        if (mc.level == null || saved.isEmpty() || saved.get().isForServer(serverAddress())) clearRecoveryFile();
        resetRecovery();
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

    @Override protected void started() {
        if (!highway().isActive() && !highway().profileLoading) resumePending();
    }
    @Override protected void stopped() {
        stopWalking();
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
            resumePending();
            highway().prepareReconnectDelay();
        }
    }
    @EventHandler private void left(GameLeftEvent event) {
        if (!highway().isActive() && highway().session.recovering()) {
            pauseForDisconnect();
            if (reconnectAfterLoss) highway().armReconnect();
            else highway().releaseReconnect();
        }
    }

    private boolean reconnectAfterLoss;
    void onClientDisconnect(boolean transferring, boolean unexpected) {
        if (transferring || !highway().session.recovering()) return;
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
