package dev.tntcoating.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Baritone, which is optional. Only {@link BaritoneElytraBridge} refers to Baritone's classes, and {@link #create()} loads
 * it only once Baritone is known to be installed. Callers should treat a {@link LinkageError} from any method as "this
 * Baritone version is not supported".
 */
public interface BaritoneBridge {
    /** Null when neither Meteor's Baritone build nor the standalone Baritone mod is installed. */
    static BaritoneBridge create() {
        FabricLoader loader = FabricLoader.getInstance();
        return loader.isModLoaded("baritone-meteor") || loader.isModLoaded("baritone") ? new BaritoneElytraBridge() : null;
    }

    /** False when Baritone ships no native elytra pathfinder for this CPU and operating system. */
    boolean elytraSupported();

    /**
     * The place Baritone is walking to (#goto, #path, Meteor's waypoint "go to"), or null when it is walking nowhere or
     * towards something that isn't a place, such as a Y level.
     */
    Trip walking();

    /**
     * Where Baritone's elytra process is flying, or null when it isn't. The goal is the one most recently given to Baritone
     * when the flight is headed for it (#goto and #goal redirect a flight in progress), otherwise null, as while Baritone
     * flies to a landing spot.
     */
    Trip flight();

    /** True once the elytra path is calculated and no walking path is still running. */
    boolean readyForTakeoff();

    /** Baritone's own description of what its elytra process is doing, for diagnostics. */
    String flightStatus();

    /** Hands the trip to Baritone's elytra process. Throws {@link IllegalArgumentException} with Baritone's reason when it refuses. */
    void fly(Trip trip);

    /** Cancels everything Baritone is doing, as #stop does. */
    void stop();

    /** Gives the trip to Baritone's walking pathfinder, as #goto does. The trip must have a goal. */
    void walk(Trip trip);

    /** Baritone's elytraAutoJump: Baritone walks to a drop and takes off by itself. */
    boolean autoJump();

    /** Baritone's allowInventory: Baritone also takes fireworks from the main inventory, not just the hotbar. */
    boolean useInventory();

    /** Baritone's elytraMinFireworksBeforeLanding. */
    int minFireworks();

    /** Baritone's elytraMinimumDurability. */
    int minDurability();

    /**
     * @param goal Baritone's goal object, or null when the destination came from somewhere else. Two trips are the same
     *             trip only when this is the same object, so giving Baritone the same coordinates again is a new trip.
     * @param x    the destination given to the elytra process
     */
    record Trip(Object goal, int x, int y, int z) {}
}
