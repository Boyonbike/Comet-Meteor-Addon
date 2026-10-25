package dev.tntcoating.compat;

import dev.tntcoating.core.scaffold.ScaffoldAuthority;
import dev.tntcoating.core.scaffold.ScaffoldRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ScaffoldAuthorityPinTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void actualPinnedRetainedServerAirCannotResolveLiveSolidUntilApplied() throws Exception {
        var pos = new BlockPos(1, 70, 3);
        var prediction = new BlockStatePredictionHandler();
        // The pinned handler retains a server state while the world contains the client's predicted solid.
        Class<?> retained = Class.forName("net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler$ServerVerifiedState");
        var constructor = retained.getDeclaredConstructor(int.class, BlockState.class, Vec3.class); constructor.setAccessible(true);
        Object state = constructor.newInstance(1, Blocks.AIR.defaultBlockState(), Vec3.ZERO);
        var states = BlockStatePredictionHandler.class.getDeclaredField("serverVerifiedStates"); states.setAccessible(true);
        @SuppressWarnings("unchecked") var map = (it.unimi.dsi.fastutil.longs.Long2ObjectMap<Object>) states.get(prediction);
        map.put(pos.asLong(), state);
        var live = new AtomicReference<>(Blocks.STONE.defaultBlockState());
        assertTrue(prediction.updateKnownServerState(pos, Blocks.AIR.defaultBlockState()));
        assertSame(Blocks.STONE.defaultBlockState(), live.get(), "retained update does not write the client world");
        var authority = new ScaffoldAuthority<BlockState>(); var delivered = new ArrayList<BlockState>();
        var runtime = new ScaffoldRuntime(() -> 0);
        var attempt = runtime.reserve(pos.asLong(), true, 1, 3);
        assertNotNull(attempt); assertTrue(runtime.beginDispatch(attempt));
        runtime.observePacket(attempt, ScaffoldRuntime.PacketKind.USE_ITEM); runtime.finishDispatch(attempt);
        java.util.function.BiConsumer<Long, BlockState> apply = (key, applied) -> {
            delivered.add(applied); runtime.authoritative(key, !applied.canBeReplaced(), applied.canBeReplaced());
        };
        authority.receive(pos.asLong(), Blocks.AIR.defaultBlockState(), key -> live.get(), apply);
        authority.reconcile(key -> live.get(), apply);
        assertTrue(authority.blocked(pos.asLong())); assertTrue(runtime.knownUnconfirmed(pos.asLong()));
        assertEquals(1, runtime.stats().pending()); assertTrue(delivered.isEmpty());
        var expected = retained.getDeclaredField("blockState"); expected.setAccessible(true);
        live.set((BlockState) expected.get(state)); // exactly the state passed to syncBlockState on ack
        authority.reconcile(key -> live.get(), apply);
        assertEquals(java.util.List.of(Blocks.AIR.defaultBlockState()), delivered); assertFalse(authority.blocked(pos.asLong()));
        assertFalse(runtime.knownUnconfirmed(pos.asLong())); assertEquals(0, runtime.stats().pending());
    }
    @Test void pinnedBlockPropertiesAndShapesMustBothReachAppliedState() {
        var bottom = Blocks.OAK_SLAB.defaultBlockState();
        var top = bottom.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
        var authority = new ScaffoldAuthority<BlockState>(); var live = new AtomicReference<>(bottom);
        var delivered = new ArrayList<BlockState>();
        authority.receive(1, top, key -> live.get(), (key, state) -> delivered.add(state));
        assertTrue(authority.blocked(1)); assertTrue(delivered.isEmpty());
        live.set(top); authority.reconcile(key -> live.get(), (key, state) -> delivered.add(state));
        assertEquals(java.util.List.of(top), delivered); assertFalse(authority.blocked(1));
    }
    @Test void pinnedClientWorldDefersRetainedUpdatesAndSequenceAckAppliesThem() throws Exception {
        assertTrue(calls("net/minecraft/client/multiplayer/ClientLevel", "setServerVerifiedBlockState", "updateKnownServerState"));
        assertTrue(calls("net/minecraft/client/multiplayer/prediction/BlockStatePredictionHandler", "endPredictionsUpTo", "syncBlockState"));
    }
    @Test void productionCoordinatorReconcilesBeforePlanningAndCannotDirectlyReleaseOnPacketReceipt() throws Exception {
        assertTrue(calls("dev/tntcoating/core/scaffold/ScaffoldCoordinator", "authoritative", "receive"));
        assertFalse(calls("dev/tntcoating/core/scaffold/ScaffoldCoordinator", "authoritative", "comet$serverBlockUpdate"));
        assertFalse(calls("dev/tntcoating/core/scaffold/ScaffoldCoordinator", "authoritative", "authoritative"));
        assertTrue(calls("dev/tntcoating/core/scaffold/ScaffoldCoordinator", "tick", "reconcile"));
        assertTrue(calls("dev/tntcoating/core/scaffold/ScaffoldCoordinator", "knownUnconfirmed", "blocked"));
        assertTrue(calls("dev/tntcoating/core/scaffold/ScaffoldCoordinator", "prediction", "overflowed"));
        assertTrue(calls("dev/tntcoating/core/scaffold/ScaffoldCoordinator", "chunk", "replaceRegion"));
    }
    private static boolean calls(String owner, String method, String called) throws Exception {
        var node = new ClassNode();
        try (var stream = ScaffoldAuthorityPinTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            assertNotNull(stream); new ClassReader(stream).accept(node, 0);
        }
        for (var member : node.methods) if (member.name.equals(method))
            for (var instruction : member.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals(called)) return true;
        return false;
    }
}
