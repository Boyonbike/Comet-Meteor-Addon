package dev.tntcoating.core;

import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

public record TrajectoryFiredInput(Vec3 position, Vec3 velocity, Pose pose, int age, boolean noGravity,
                                   boolean inWater, boolean inGround, int steps) {}
