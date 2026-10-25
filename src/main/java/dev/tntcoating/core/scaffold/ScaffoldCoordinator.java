package dev.tntcoating.core.scaffold;

import dev.tntcoating.core.ScaffoldPlacementFeedback;
import dev.tntcoating.modules.BlockCoat;
import dev.tntcoating.modules.CometScaffold;
import dev.tntcoating.modules.SmartSponge;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.ActiveModulesChangedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Permanent observer: disabling a module never drops packet accounting or makes a local prediction an anchor. */
public final class ScaffoldCoordinator {
    private static final ScaffoldCoordinator INSTANCE = new ScaffoldCoordinator();
    private final ScaffoldOwnership ownership = new ScaffoldOwnership();
    private final Set<BlockPos> legacyPredictions = new HashSet<>();
    private final ScaffoldAuthority<BlockState> authority = new ScaffoldAuthority<>();
    private static final ThreadLocal<LegacyDispatch> LEGACY = new ThreadLocal<>();
    private static boolean initialized;
    private ClientLevel level;
    private Object connection;
    private record LegacyDispatch(BlockPos pos, long generation, ClientLevel level, LocalPlayer player, BooleanSupplier active) {}
    private ScaffoldCoordinator() {}
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        MeteorClient.EVENT_BUS.subscribe(INSTANCE);
        INSTANCE.activeChanged(null);
    }
    private static CometScaffold comet() { return Modules.get() == null ? null : Modules.get().get(CometScaffold.class); }
    public static boolean buildersPaused() {
        Modules modules = Modules.get();
        if (modules == null) return false;
        CometScaffold comet = modules.get(CometScaffold.class);
        return comet != null && comet.isActive();
    }
    @EventHandler(priority = 10000) private void activeChanged(ActiveModulesChangedEvent event) {
        if (Modules.get() == null) return;
        CometScaffold comet = comet();
        Scaffold legacy = Modules.get().get(Scaffold.class);
        long before = ownership.generation();
        ScaffoldOwnership.Disable disable = ownership.changed(comet != null && comet.isActive(), legacy != null && legacy.isActive());
        if (ownership.generation() != before) {
            ScaffoldRotationBridge.INSTANCE.cancel();
            if (comet != null) comet.invalidateUnsent("scaffold ownership changed");
        }
        // Publish ownership first: disable posts ActiveModulesChangedEvent recursively, before onActivate/subscription.
        if (disable == ScaffoldOwnership.Disable.LEGACY) legacy.disable();
        if (disable == ScaffoldOwnership.Disable.COMET) comet.disable();
        BlockCoat coat = Modules.get().get(BlockCoat.class);
        SmartSponge sponge = Modules.get().get(SmartSponge.class);
        if (coat != null) coat.scaffoldSuspension(buildersPaused());
        if (sponge != null) sponge.scaffoldSuspension(buildersPaused());
    }
    @EventHandler(priority = 10000) private void tick(TickEvent.Pre event) {
        if (level != mc.level || connection != mc.getConnection()) {
            level = mc.level; connection = mc.getConnection();
            legacyPredictions.clear();
            authority.clear();
            ScaffoldRotationBridge.INSTANCE.clear();
            CometScaffold comet = comet();
            if (comet != null) comet.disconnected();
        }
        authority.reconcile(ScaffoldCoordinator::loadedState, ScaffoldCoordinator::applied);
        CometScaffold comet = comet();
        if (comet != null) comet.maintainConnection();
    }
    @EventHandler(priority = 10000) private void sent(PacketEvent.Sent event) {
        ScaffoldRotationBridge.INSTANCE.observePacket(event.packet);
        CometScaffold comet = comet();
        if (comet != null) comet.observePacket(event.packet);
    }
    public static boolean knownUnconfirmed(BlockPos pos) {
        if (INSTANCE.authority.blocked(pos.asLong()) || INSTANCE.legacyPredictions.contains(pos)) return true;
        CometScaffold comet = comet();
        if (comet != null && comet.knownUnconfirmed(pos)) return true;
        if (Modules.get() == null) return false;
        BlockCoat coat = Modules.get().get(BlockCoat.class);
        SmartSponge sponge = Modules.get().get(SmartSponge.class);
        return coat != null && coat.knownUnconfirmed(pos) || sponge != null && sponge.knownUnconfirmed(pos);
    }
    /** Handler TAIL can retain a local prediction; deliver only when the live world matches the entire state. */
    public static void authoritative(BlockPos pos, BlockState state) {
        boolean overflowed = INSTANCE.authority.overflowed();
        INSTANCE.authority.receive(pos.asLong(), state, ScaffoldCoordinator::loadedState, ScaffoldCoordinator::applied);
        if (!overflowed && INSTANCE.authority.overflowed()) {
            MeteorClient.LOG.warn("Comet Scaffold authoritative reconciliation exceeded 256 unresolved cells; placement and support trust are paused until world departure.");
            CometScaffold comet = comet();
            if (comet != null) comet.invalidateUnsent("authoritative reconciliation capacity");
        }
    }
    private static BlockState loadedState(long packed) {
        if (mc.level == null) return null;
        BlockPos pos = BlockPos.of(packed);
        return mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) ? mc.level.getBlockState(pos) : null;
    }
    private static void applied(long packed, BlockState state) {
        BlockPos pos = BlockPos.of(packed);
        INSTANCE.legacyPredictions.remove(pos);
        CometScaffold comet = comet();
        if (comet != null) comet.authoritative(pos, state);
        Scaffold legacy = Modules.get() == null ? null : Modules.get().get(Scaffold.class);
        if (legacy instanceof ScaffoldPlacementFeedback feedback) feedback.comet$serverBlockUpdate(pos, state);
    }
    /** Shared lifetime quarantine outlives each builder's local retry timeout and enabled state. */
    public static boolean prediction(BlockPos pos) {
        if (INSTANCE.authority.overflowed()) return false;
        if (!INSTANCE.legacyPredictions.contains(pos) && INSTANCE.legacyPredictions.size() >= 256) return false;
        INSTANCE.legacyPredictions.add(pos.immutable());
        CometScaffold comet = comet();
        if (comet != null) comet.quarantinePrediction(pos);
        return true;
    }
    @EventHandler private void chunk(ChunkDataEvent event) {
        if (mc.level == null) return;
        var chunk = event.chunk().getPos();
        // Fresh chunk replacement supersedes retained per-block packet expectations, including property changes.
        authority.replaceRegion(packed -> {
            BlockPos pos = BlockPos.of(packed);
            return pos.getX() >> 4 == chunk.x && pos.getZ() >> 4 == chunk.z;
        }, ScaffoldCoordinator::loadedState, ScaffoldCoordinator::applied);
        for (BlockPos pos : java.util.List.copyOf(INSTANCE.legacyPredictions))
            if (pos.getX() >> 4 == chunk.x && pos.getZ() >> 4 == chunk.z) applied(pos.asLong(), mc.level.getBlockState(pos));
    }
    public static void setback() {
        ScaffoldRotationBridge.INSTANCE.cancel();
        CometScaffold comet = comet();
        if (comet != null) comet.setback();
        Scaffold legacy = Modules.get() == null ? null : Modules.get().get(Scaffold.class);
        if (legacy instanceof ScaffoldPlacementFeedback feedback) feedback.comet$serverSetback();
    }
    /** Wrap only BlockUtils.place calls made by the upgraded legacy Scaffold; all other Meteor users remain untouched. */
    public static boolean legacyPlacement(BlockPos pos, BooleanSupplier active, BooleanSupplier place) {
        if (!active.getAsBoolean() || comet() != null && comet().isActive() || INSTANCE.authority.overflowed()
            || !INSTANCE.legacyPredictions.contains(pos) && INSTANCE.legacyPredictions.size() >= 256) return false;
        LegacyDispatch previous = LEGACY.get();
        LEGACY.set(new LegacyDispatch(pos.immutable(), INSTANCE.ownership.generation(), mc.level, mc.player, active));
        try { return place.getAsBoolean(); }
        finally { if (previous == null) LEGACY.remove(); else LEGACY.set(previous); }
    }
    /** Called at BlockUtils's scheduling point, not globally for unrelated queued callbacks. */
    public static Runnable guardLegacyRotation(Runnable callback) {
        LegacyDispatch dispatch = LEGACY.get();
        if (dispatch == null) return callback;
        return () -> {
            if (!valid(dispatch)) return;
            LegacyDispatch previous = LEGACY.get();
            LEGACY.set(dispatch);
            try { callback.run(); }
            finally { if (previous == null) LEGACY.remove(); else LEGACY.set(previous); }
        };
    }
    private static boolean valid(LegacyDispatch dispatch) {
        return dispatch.active.getAsBoolean() && dispatch.generation == INSTANCE.ownership.generation()
            && dispatch.level == mc.level && dispatch.player == mc.player && mc.player != null && !(comet() != null && comet().isActive());
    }
    /** Called immediately before the actual legacy interaction, including non-rotating dispatches. */
    public static boolean legacyDispatch() {
        LegacyDispatch dispatch = LEGACY.get();
        if (dispatch == null) return true;
        if (!valid(dispatch)) return false;
        return prediction(dispatch.pos);
    }
}
