package dev.tntcoating.compat;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.interfaces.IGoalRenderPos;
import net.minecraft.core.BlockPos;

/** Uses only Baritone's public API: the rest of Baritone is obfuscated in release builds. */
final class BaritoneElytraBridge implements BaritoneBridge {
    /** The height Baritone's own #elytra gives a goal without one; its pathfinder works out the real height. */
    private static final int XZ_GOAL_Y = 64;

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
    public boolean readyForTakeoff() {
        IBaritone baritone = baritone();
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
