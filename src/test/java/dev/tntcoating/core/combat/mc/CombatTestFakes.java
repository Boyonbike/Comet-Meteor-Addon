/*
 * Original Comet implementation by Boyonabike.
 * Independent: test doubles written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.Snapshot;
import dev.tntcoating.core.combat.Vec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/** In-memory client and packet sink for dispatch/intake tests. */
final class CombatTestFakes {
    private CombatTestFakes() {
    }

    static final class FakeClient implements CombatClient {
        boolean onClientThread = true;
        boolean connectionOpen = true;
        Level level;
        EntityKey self;
        ConnectionKey connection;

        record EntityState(EntityKey key, Vec position, Box box, String type) {
        }

        final Map<Integer, EntityState> entities = new LinkedHashMap<>();
        final List<Snapshot.Crystal> crystals = new ArrayList<>();
        Vec eye = new Vec(0.5, 65.0, 0.5);
        double blockRange = 4.5;
        double entityRange = 3.0;
        boolean visible = true;
        double yaw;
        double pitch;
        boolean rotationSatisfied = true;
        final List<double[]> rotationRequests = new ArrayList<>();
        boolean screenOpen;
        int selectedSlot;
        final String[] hotbar = new String[9];
        String offhand = "";
        final List<Integer> selections = new ArrayList<>();

        FakeClient(ConnectionKey connection) {
            this.connection = connection;
        }

        int putEntity(int id, UUID uuid, String type, Vec position, Box box) {
            entities.put(id, new EntityState(new EntityKey(connection, id, uuid), position, box, type));
            return id;
        }

        void putSelf(int id, UUID uuid) {
            self = new EntityKey(connection, id, uuid);
            entities.put(id, new EntityState(self, eye, Box.around(eye, 0.9), "minecraft:player"));
        }

        int putCrystal(int id, UUID uuid, Vec position) {
            EntityKey key = new EntityKey(connection, id, uuid);
            crystals.add(new Snapshot.Crystal(key, position));
            return putEntity(id, uuid, "minecraft:end_crystal", position, Box.fromCell(
                new dev.tntcoating.core.combat.Cell((int) Math.floor(position.x()),
                    (int) Math.floor(position.y()), (int) Math.floor(position.z()))));
        }

        @Override
        public boolean onClientThread() {
            return onClientThread;
        }

        @Override
        public boolean connectionOpen() {
            return connectionOpen;
        }

        @Override
        public Level level() {
            return level;
        }

        @Override
        public EntityKey selfKey() {
            return self;
        }

        @Override
        public EntityKey entity(int id) {
            EntityState state = entities.get(id);
            return state == null ? null : state.key();
        }

        @Override
        public String entityType(EntityKey entity) {
            if (entity == null) return "";
            EntityState state = entities.get(entity.id());
            return state == null ? "" : state.type();
        }

        @Override
        public EntityKey crystalAt(Vec position, double tolerance) {
            EntityKey match = null;
            for (Snapshot.Crystal crystal : crystals) {
                if (crystal.position().distance(position) <= tolerance) {
                    if (match != null) return null;
                    match = crystal.entity();
                }
            }
            return match;
        }

        @Override
        public String dimensionId() {
            return "minecraft:overworld";
        }

        @Override
        public List<Snapshot.Crystal> crystals() {
            return List.copyOf(crystals);
        }

        @Override
        public Vec eyePosition() {
            return eye;
        }

        @Override
        public Vec entityPosition(EntityKey entity) {
            if (entity == null) return null;
            EntityState state = entities.get(entity.id());
            return state == null ? null : state.position();
        }

        @Override
        public Box entityBox(EntityKey entity) {
            if (entity == null) return null;
            EntityState state = entities.get(entity.id());
            return state == null ? null : state.box();
        }

        @Override
        public boolean entityPresent(EntityKey entity) {
            if (entity == null) return false;
            EntityState state = entities.get(entity.id());
            return state != null && state.key().uuid() != null
                && state.key().uuid().equals(entity.uuid());
        }

        @Override
        public double blockInteractionRange() {
            return blockRange;
        }

        /** Phase 4.6: default fake says every base is sturdy; tests can lower the set. */
        boolean supportBelow = true;

        @Override
        public boolean supportBelow(Cell base) {
            return supportBelow;
        }

        /** Piston-plan structural dispatch probes: the fake world starts empty and free. */
        @Override
        public boolean replaceable(Cell cell) {
            return true;
        }

        /** Block ids observed by the fake world; absent cells are air. */
        final Map<Cell, String> blocks = new LinkedHashMap<>();

        @Override
        public String blockId(Cell cell) {
            return cell == null ? null : blocks.get(cell);
        }

        @Override
        public boolean entityFree(Cell cell) {
            return true;
        }

        @Override
        public PistonFacing pistonFacing(Cell cell) {
            return null;
        }

        @Override
        public double entityInteractionRange() {
            return entityRange;
        }

        @Override
        public boolean visible(Vec eyePosition, Box target) {
            return visible;
        }

        @Override
        public double yaw() {
            return yaw;
        }

        @Override
        public double pitch() {
            return pitch;
        }

        @Override
        public boolean rotationSatisfied(double targetYaw, double targetPitch) {
            return rotationSatisfied;
        }

        @Override
        public boolean requestRotation(double targetYaw, double targetPitch, Runnable afterRotate) {
            rotationRequests.add(new double[] {targetYaw, targetPitch});
            return true;
        }

        @Override
        public boolean screenOpen() {
            return screenOpen;
        }

        @Override
        public int selectedSlot() {
            return selectedSlot;
        }

        @Override
        public String hotbarItem(int slot) {
            if (slot < 0 || slot > 8 || hotbar[slot] == null) return "";
            return hotbar[slot];
        }

        @Override
        public String offhandItem() {
            return offhand;
        }

        @Override
        public void selectHotbarSlot(int slot) {
            selectedSlot = slot;
            selections.add(slot);
        }

        @Override
        public ConnectionKey connection() {
            return connection;
        }
    }

    static final class FakeSink implements CombatPacketSink {
        enum Kind {
            PLACE,
            SUPPORT,
            PISTON,
            POWER,
            ATTACK,
            BREAK,
            SWING,
            SLOT,
            STOP
        }

        record Send(int attempt, Kind kind, int number, BlockHitResult hit, boolean enqueued) {
        }

        boolean open = true;
        /** 1-based attempt number that is refused, or -1 for none. */
        int failOnAttempt = -1;
        int ackSequence = -1;
        int attempts;
        final List<Send> sends = new ArrayList<>();
        /** The cell whose vanilla destruction is currently in progress, if any. */
        Cell breaking;
        int stops;

        void reset() {
            attempts = 0;
            sends.clear();
            failOnAttempt = -1;
            ackSequence = -1;
            breaking = null;
            stops = 0;
        }

        Cell breaking() {
            return breaking;
        }

        int stops() {
            return stops;
        }

        int count(Kind kind) {
            return (int) sends.stream().filter(send -> send.kind() == kind).count();
        }

        Send last() {
            return sends.isEmpty() ? null : sends.get(sends.size() - 1);
        }

        Send last(Kind kind) {
            for (int index = sends.size() - 1; index >= 0; index--) {
                Send send = sends.get(index);
                if (send.kind() == kind) {
                    return send;
                }
            }
            return null;
        }

        private SendResult attempt(Kind kind, int number, BlockHitResult hit) {
            attempts++;
            boolean accepted = attempts != failOnAttempt;
            sends.add(new Send(attempts, kind, number, hit, accepted));
            return accepted ? SendResult.accepted(true, ackSequence) : SendResult.refused();
        }

        @Override
        public boolean open() {
            return open;
        }

        @Override
        public SendResult placeCrystal(BlockHitResult hit) {
            return attempt(Kind.PLACE, 0, hit);
        }

        @Override
        public SendResult placeSupport(BlockHitResult hit) {
            return attempt(Kind.SUPPORT, 0, hit);
        }

        @Override
        public SendResult placePiston(BlockHitResult hit) {
            return attempt(Kind.PISTON, 0, hit);
        }

        @Override
        public SendResult placePower(BlockHitResult hit) {
            return attempt(Kind.POWER, 0, hit);
        }

        @Override
        public SendResult attack(int entityId) {
            return attempt(Kind.ATTACK, entityId, null);
        }

        @Override
        public SendResult breakBlock(BlockHitResult hit) {
            SendResult result = attempt(Kind.BREAK, 0, hit);
            if (result.enqueued() && hit != null) {
                var pos = hit.getBlockPos();
                breaking = new Cell(pos.getX(), pos.getY(), pos.getZ());
            }
            return result;
        }

        @Override
        public void stopBreak() {
            stops++;
            sends.add(new Send(attempts, Kind.STOP, 0, null, true));
            breaking = null;
        }

        @Override
        public SendResult swing() {
            return attempt(Kind.SWING, 0, null);
        }

        @Override
        public SendResult selectSlot(int slot) {
            return attempt(Kind.SLOT, slot, null);
        }
    }
}
