package dev.tntcoating.mixin;

import dev.tntcoating.core.TrajectoryPathAccess;
import dev.tntcoating.core.TrajectorySimulationContext;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.systems.modules.render.Trajectories;
import meteordevelopment.meteorclient.utils.entity.simulator.ProjectileEntitySimulator;
import meteordevelopment.meteorclient.utils.entity.simulator.SimulationStep;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(targets = "meteordevelopment.meteorclient.systems.modules.render.Trajectories$Path", remap = false)
public abstract class TrajectoriesPathMixin implements TrajectoryPathAccess {
    @Shadow @Final private Trajectories this$0;
    @Shadow public Vector3d lastPoint;
    @Unique private Entity comet$startEntity;
    @Unique private final Vector3d comet$start = new Vector3d();

    @Override @Invoker("render") public abstract void comet$render(Render3DEvent event);
    @Override @Invoker("clear") public abstract void comet$clear();

    @Inject(method = "clear", at = @At("RETURN"))
    private void comet$clearStart(CallbackInfo ci) {
        comet$startEntity = null;
    }

    @Inject(method = "setStart", at = @At("RETURN"))
    private void comet$rememberStart(Entity entity, double tickDelta, CallbackInfoReturnable<?> cir) {
        comet$startEntity = entity;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void comet$resetRenderStart(Render3DEvent event, CallbackInfo ci) {
        // Meteor's render method advances lastPoint to the end of the path. Cached renders must
        // restart it, and fired projectiles still need an interpolated origin on every frame.
        if (comet$startEntity == null) lastPoint = null;
        else {
            double delta = mc.level.tickRateManager().isFrozen() ? 1 : event.tickDelta;
            lastPoint = comet$start.set(
                Mth.lerp(delta, comet$startEntity.xOld, comet$startEntity.getX()),
                Mth.lerp(delta, comet$startEntity.yOld, comet$startEntity.getY()),
                Mth.lerp(delta, comet$startEntity.zOld, comet$startEntity.getZ()));
        }
    }

    @Inject(method = "calculate", at = @At("HEAD"))
    private void comet$beginCalculation(CallbackInfoReturnable<?> cir) {
        ((TrajectorySimulationContext) this$0).comet$beginPath();
    }

    @Inject(method = "calculate", at = @At("RETURN"))
    private void comet$finishCalculation(CallbackInfoReturnable<?> cir) {
        ((TrajectorySimulationContext) this$0).comet$endPath();
    }

    @Redirect(method = "calculate", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/utils/entity/simulator/ProjectileEntitySimulator;tick()Lmeteordevelopment/meteorclient/utils/entity/simulator/SimulationStep;"))
    private SimulationStep comet$budgetedStep(ProjectileEntitySimulator simulator) {
        return ((TrajectorySimulationContext) this$0).comet$step(simulator);
    }
}
