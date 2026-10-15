package dev.tntcoating.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiPlayerGameMode.class)
public interface GameModeInvoker {
    @Invoker("startPrediction")
    void comet$startPrediction(ClientLevel level, PredictiveAction action);

    @Invoker("ensureHasSentCarriedItem")
    void comet$ensureHasSentCarriedItem();
}
