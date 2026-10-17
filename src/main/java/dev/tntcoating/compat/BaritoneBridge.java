package dev.tntcoating.compat;

import dev.tntcoating.core.TrailRoutePath;
import net.fabricmc.loader.api.FabricLoader;

import java.util.List;

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

    /** True while the elytra process is active, including landing before it has a destination. */
    boolean flightActive();

    /** True once the elytra path is calculated and no walking path is still running. */
    boolean readyForTakeoff();

    /** A bounded copy of the calculated elytra path, in travel order, for checking curved flight corridors. */
    default List<TrailRoutePath.Point> flightPath() { return List.of(); }

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
