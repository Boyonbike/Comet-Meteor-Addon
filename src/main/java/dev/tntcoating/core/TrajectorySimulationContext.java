package dev.tntcoating.core;

import meteordevelopment.meteorclient.utils.entity.simulator.ProjectileEntitySimulator;
import meteordevelopment.meteorclient.utils.entity.simulator.SimulationStep;

/** Shared by the existing Trajectories module and its private Path implementation. */
public interface TrajectorySimulationContext {
    void comet$beginPath();
    void comet$endPath();
    SimulationStep comet$step(ProjectileEntitySimulator simulator);
}
