package dev.tntcoating.compat;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.RotationMoveEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.interfaces.IGoalRenderPos;
import dev.tntcoating.core.SettingOverride;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/** Uses only Baritone's public API: the rest of Baritone is obfuscated in release builds. */
final class BaritoneElytraBridge implements BaritoneBridge {
    /** The height Baritone's own #elytra gives a goal without one; its pathfinder works out the real height. */
    private static final int XZ_GOAL_Y = 64;

    private static final BaritoneElytraBridge INSTANCE = new BaritoneElytraBridge();

    private final IBaritone steeringBaritone;
    private final SettingOverride<Integer> rocketlessMinimum = new SettingOverride<>(
        () -> BaritoneAPI.getSettings().elytraMinFireworksBeforeLanding.value,
        value -> BaritoneAPI.getSettings().elytraMinFireworksBeforeLanding.value = value, -1);
    private final SettingOverride<Double> rocketlessSpeed = new SettingOverride<>(
        () -> BaritoneAPI.getSettings().elytraFireworkSpeed.value,
        value -> BaritoneAPI.getSettings().elytraFireworkSpeed.value = value, 0.0);
    private Steering latestSteering;
    private LocalPlayer steeringPlayer;
    private BlockPos steeringDestination;
    private int steeringTick = -1;

    static BaritoneElytraBridge instance() {
        return INSTANCE;
    }

    private BaritoneElytraBridge() {
        steeringBaritone = baritone();
        // Baritone registers LookBehavior first, so this listener sees its processed movement rotation. The elytra
        // MOTION_UPDATE runs before Entity.move; camera rotations may be restored immediately after that move.
        // Its public event bus has no unregister operation: keep one bridge/listener across module activations.
        steeringBaritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onPlayerRotationMove(RotationMoveEvent event) {
                if (event.getType() != RotationMoveEvent.Type.MOTION_UPDATE) return;
                clearSteering();
                LocalPlayer player = steeringBaritone.getPlayerContext().player();
                if (!isSteering(steeringBaritone, player)) return;
                float yaw = event.getYaw();
                float pitch = event.getPitch();
                if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
                IElytraProcess elytra = steeringBaritone.getElytraProcess();
                BlockPos destination = elytra.currentDestination();
                if (destination == null) return;
                Trip landing = "Elytra - Landing".equals(elytra.displayName()) ? landingTarget(destination) : null;
                latestSteering = new Steering(yaw, pitch, landing);
                steeringPlayer = player;
                steeringDestination = destination.immutable();
                steeringTick = player.tickCount;
            }

            @Override
            public void onTick(TickEvent event) {
                clearSteering();
            }

            @Override
            public void onWorldEvent(WorldEvent event) {
                clearSteering();
            }
        });
    }

    @Override
    public Steering steering() {
        IBaritone baritone = baritone();
        LocalPlayer player = baritone.getPlayerContext().player();
        if (baritone != steeringBaritone || player == null || player != steeringPlayer
            || player.tickCount != steeringTick || latestSteering == null || !isSteering(baritone, player)) return null;
        IElytraProcess elytra = baritone.getElytraProcess();
        BlockPos destination = elytra.currentDestination();
        if (destination == null || !destination.equals(steeringDestination)) return null;
        boolean landing = "Elytra - Landing".equals(elytra.displayName());
        Trip target = latestSteering.landing();
        if (landing != (target != null) || landing && !target.equals(landingTarget(destination))) return null;
        return latestSteering;
    }

    private static boolean isSteering(IBaritone baritone, LocalPlayer player) {
        if (player == null || !player.isFallFlying() || baritone.getPathingBehavior().isPathing()) return false;
        IElytraProcess elytra = baritone.getElytraProcess();
        if (!elytra.isLoaded() || !elytra.isActive()
            || baritone.getPathingControlManager().mostRecentInControl().orElse(null) != elytra) return false;
        // In Baritone 26.2 a successful takeoff retains START_FLYING while airborne. FLYING is also used after a
        // rejected landing. Accept the known cruise and landing states, leaving every unknown state to Baritone.
        String status = elytra.displayName();
        return "Elytra - Begin flying".equals(status) || "Elytra - Flying".equals(status)
            || "Elytra - Landing".equals(status);
    }

    private static Trip landingTarget(BlockPos destination) {
        return new Trip(null, destination.getX(), destination.getY(), destination.getZ());
    }

    private void clearSteering() {
        latestSteering = null;
        steeringPlayer = null;
        steeringDestination = null;
        steeringTick = -1;
    }

    @Override
    public void rocketless(boolean enabled) {
        if (!enabled) {
            try {
                rocketlessSpeed.release();
            } finally {
                rocketlessMinimum.release();
            }
            return;
        }
        try {
            rocketlessMinimum.acquire();
            rocketlessSpeed.acquire();
        } catch (RuntimeException | Error failure) {
            try {
                rocketless(false);
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public boolean elytraSupported() {
        return baritone().getElytraProcess().isLoaded();
    }

    @Override
    public Trip walking() {
        IBaritone baritone = baritone();
        ICustomGoalProcess process = baritone.getCustomGoalProcess();
        IPathingControlManager control = baritone.getPathingControlManager();
        // #goal on its own also makes the process active, but it only shows the goal. Walking means it asked for a path.
        boolean walking = control.mostRecentInControl().orElse(null) == process && control.mostRecentCommand()
            .map(command -> command.commandType == PathingCommandType.SET_GOAL_AND_PATH
                || command.commandType == PathingCommandType.REVALIDATE_GOAL_AND_PATH
                || command.commandType == PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH)
            .orElse(false);
        return walking ? trip(process.getGoal()) : null;
    }

    @Override
    public Trip flight() {
        IBaritone baritone = baritone();
        IElytraProcess elytra = baritone.getElytraProcess();
        BlockPos destination = elytra.isActive() ? elytra.currentDestination() : null;
        if (destination == null) return null;
        Trip latest = trip(baritone.getCustomGoalProcess().mostRecentGoal());
        boolean headedThere = latest != null && latest.x() == destination.getX() && latest.y() == destination.getY() && latest.z() == destination.getZ();
        return new Trip(headedThere ? latest.goal() : null, destination.getX(), destination.getY(), destination.getZ());
    }

    @Override
    public String flightStatus() {
        return baritone().getElytraProcess().displayName();
    }

    @Override
    public boolean readyForTakeoff() {
        IBaritone baritone = baritone();
        // Baritone 26.2 exposes the calculated elytra path directly, so wait for it instead of
        // inferring readiness from the process's display name.
        return !baritone.getElytraProcess().getPath().isEmpty() && !baritone.getPathingBehavior().isPathing();
    }

    @Override
    public void fly(Trip trip) {
        baritone().getElytraProcess().pathTo(new BlockPos(trip.x(), trip.y(), trip.z()));
    }

    @Override
    public void stop() {
        baritone().getPathingBehavior().cancelEverything();
    }

    @Override
    public void walk(Trip trip) {
        baritone().getCustomGoalProcess().setGoalAndPath((Goal) trip.goal());
    }

    @Override
    public boolean autoJump() {
        return BaritoneAPI.getSettings().elytraAutoJump.value;
    }

    @Override
    public boolean useInventory() {
        return BaritoneAPI.getSettings().allowInventory.value;
    }

    @Override
    public int minFireworks() {
        return BaritoneAPI.getSettings().elytraMinFireworksBeforeLanding.value;
    }

    @Override
    public int minDurability() {
        return BaritoneAPI.getSettings().elytraMinimumDurability.value;
    }

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /** The same conversion Baritone's #elytra makes, extended to the other goals that name one block. */
    private static Trip trip(Goal goal) {
        if (goal instanceof GoalXZ xz) return new Trip(goal, xz.getX(), XZ_GOAL_Y, xz.getZ());
        if (goal instanceof IGoalRenderPos block) {
            BlockPos pos = block.getGoalPos();
            return new Trip(goal, pos.getX(), pos.getY(), pos.getZ());
        }
        return null;
    }
}
