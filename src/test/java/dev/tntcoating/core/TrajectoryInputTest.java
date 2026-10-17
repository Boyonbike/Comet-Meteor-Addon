package dev.tntcoating.core;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrajectoryInputTest {
    @BeforeAll static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup())
            .forEach(components -> components.apply());
    }

    @Test void heldStackSnapshotsDetectLaterComponentAndItemChanges() {
        ItemStack stack = new ItemStack(Items.CROSSBOW);
        var input = new TrajectoryHeldInput(motion(Vec3.ZERO, 0, 0), stack);
        var cached = input.snapshot();
        assertEquals(cached, input);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Changed components"));
        assertNotEquals(cached, input);
        assertNotEquals(cached, new TrajectoryHeldInput(cached.motion(), new ItemStack(Items.BOW)));
        assertEquals(cached, new TrajectoryHeldInput(cached.motion(), new ItemStack(Items.CROSSBOW, 2)),
            "Stack count does not change the simulated projectile");
    }

    @Test void movingInterpolatedOriginsTurningAimAndChargingBowPreventCacheReuse() {
        var base = new TrajectoryHeldInput(motion(Vec3.ZERO, 0, 0), new ItemStack(Items.BOW));
        for (var changed : List.of(motion(new Vec3(0.001, 0, 0), 0, 0),
            motion(Vec3.ZERO, 0.001f, 0), motion(Vec3.ZERO, 0, 1))) {
            assertNotEquals(base, new TrajectoryHeldInput(changed, base.stack()));
        }
    }

    @Test void firedMotionAndAgeChangesInvalidateEvenWithoutAWorldTickChange() {
        var base = new TrajectoryFiredInput(Vec3.ZERO, new Vec3(1, 0, 0), Pose.STANDING,
            3, false, false, false, 500);
        assertNotEquals(base, new TrajectoryFiredInput(Vec3.ZERO, new Vec3(0, 1, 0), Pose.STANDING,
            3, false, false, false, 500));
        assertNotEquals(base, new TrajectoryFiredInput(Vec3.ZERO, new Vec3(1, 0, 0), Pose.STANDING,
            4, false, false, false, 500));
        assertNotEquals(base, new TrajectoryFiredInput(Vec3.ZERO, new Vec3(1, 0, 0), Pose.STANDING,
            3, false, false, true, 500));
    }

    @Test void externallyAppliedCollisionUpdatesInvalidateAnOtherwiseIdenticalPrediction() {
        var prediction = new TrajectoryPrediction<String>();
        Object world = new Object();
        long revision = TrajectoryCollisionRevision.current();
        prediction.update(world, 1, revision, "unchanged player", 500, false);
        TrajectoryCollisionRevision.invalidate();
        assertFalse(prediction.matches(world, 1, TrajectoryCollisionRevision.current(), "unchanged player", 500));
    }

    private static TrajectoryHeldInput.Motion motion(Vec3 origin, float yaw, int useTicks) {
        return new TrajectoryHeldInput.Motion(origin, Vec3.ZERO, Pose.STANDING, 1.62f, yaw, 0,
            Vec3.ZERO, Vec3.ZERO, useTicks, false, false, 500, 3);
    }
}
