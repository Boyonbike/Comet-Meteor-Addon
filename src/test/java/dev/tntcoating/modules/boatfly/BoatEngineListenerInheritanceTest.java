package dev.tntcoating.modules.boatfly;

import meteordevelopment.orbit.EventBus;
import meteordevelopment.orbit.EventHandler;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The engine stores its event handlers privately in the coordinator's superclass. */
class BoatEngineListenerInheritanceTest {
    static class Tick {}
    static class Engine {
        int ticks;
        @EventHandler private void tick(Tick event) { ticks++; }
    }
    static class Coordinator extends Engine {}

    @Test
    void orbitInvokesPrivateInheritedEngineHandlersAndUnsubscribesThem() {
        EventBus bus = new EventBus();
        bus.registerLambdaFactory("dev.tntcoating", (method, klass) ->
            (MethodHandles.Lookup) method.invoke(null, klass, MethodHandles.lookup()));
        Coordinator coordinator = new Coordinator();
        bus.subscribe(coordinator);
        bus.post(new Tick());
        assertEquals(1, coordinator.ticks);
        bus.unsubscribe(coordinator);
        bus.post(new Tick());
        assertEquals(1, coordinator.ticks);
    }
}
