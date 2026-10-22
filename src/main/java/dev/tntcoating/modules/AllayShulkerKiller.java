package dev.tntcoating.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.allay.Allay;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Kills allays holding shulker boxes so they drop them. An allay ignores all damage from the player it likes, which is
 * whoever gave it its item, so one that takes no damage from several hits is skipped instead of being hit forever.
 *
 * <p>A charged sword hit on the ground sweeps every living thing next to the target, with or without Sweeping Edge,
 * which would hurt the allays kept for breeding. With no-sweep on it jumps and hits while falling instead: a critical
 * hit, which never sweeps.
 */
public final class AllayShulkerKiller extends AllayModule {
    private static final int UNANSWERED_HITS = 3;
    /** Roughly how long after a jump the player starts falling. */
    private static final int JUMP_TICKS = 6;

    private static boolean sweepFreeHit;

    private final Setting<Boolean> noSweep = general.add(new BoolSetting.Builder().name("no-sweep")
        .description("Never sweep the allays around the target: jump and hit while falling, a critical hit that can't sweep. Needs room to jump.")
        .defaultValue(true).build());

    // Packet callbacks may run on the network thread; only this queue is shared with it.
    private final Queue<Integer> damaged = new ConcurrentLinkedQueue<>();
    // Hits sent to an allay since the server last reported it taking damage, by entity ID.
    private final Map<Integer, Integer> unanswered = new HashMap<>();
    private final Set<Integer> immune = new HashSet<>();
    private int jumpedAt;

    public AllayShulkerKiller() {
        super("allay-shulker-killer", "Kills allays holding shulker boxes so they drop them. Skips allays that like you, since your hits can't hurt them.");
    }

    @Override protected void reset() {
        super.reset();
        damaged.clear();
        unanswered.clear();
        immune.clear();
        jumpedAt = -JUMP_TICKS;
    }

    /**
     * True during a no-sweep hit. Auto Weapon then takes its best hotbar weapon: an inventory weapon's hit is held back
     * and sent later, maybe after landing, where a sword would sweep.
     */
    public static boolean isSweepFreeHit() {
        return sweepFreeHit;
    }

    @EventHandler private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundDamageEventPacket packet) damaged.add(packet.entityId());
    }

    @Override protected void tick() {
        Integer id;
        while ((id = damaged.poll()) != null) unanswered.remove(id);
        Allay allay = nearest(target -> !immune.contains(target.getId()) && attackable(target));
        if (allay == null) { status = "No allay with a shulker"; return; }
        if (unanswered.getOrDefault(allay.getId(), 0) >= UNANSWERED_HITS) {
            immune.add(allay.getId());
            warning("Skipping the allay at %s: %d hits did no damage. Allays can't be hurt by the player they like, which is whoever gave them their item.",
                allay.blockPosition().toShortString(), UNANSWERED_HITS);
            return;
        }
        float charge = mc.player.getAttackStrengthScale(0.5f);
        if (noSweep.get() && !falling()) {
            if (!mc.player.onGround()) { status = "Jumping"; return; }
            // Jump early enough to be falling once fully charged.
            if (charge + JUMP_TICKS / mc.player.getCurrentItemAttackStrengthDelay() < 1) { status = "Charging"; return; }
            // Still on the ground just after a jump: something above is in the way.
            status = ticks - jumpedAt <= 2 ? "No room to jump" : "Jumping";
            jumpedAt = ticks;
            mc.player.jumpFromGround();
            return;
        }
        if (charge < 1) { status = "Charging"; return; }
        status = "Attacking";
        aim(allay, 0, point -> {
            // Checked again after this tick's movement, which the server judges the hit by.
            if (!attackable(allay) || mc.player.getAttackStrengthScale(0.5f) < 1 || noSweep.get() && !falling()) return;
            unanswered.merge(allay.getId(), 1, Integer::sum);
            sweepFreeHit = noSweep.get();
            try {
                mc.gameMode.attack(mc.player, allay);
            } finally {
                sweepFreeHit = false;
            }
            mc.player.swing(InteractionHand.MAIN_HAND);
        });
    }

    /** Off the ground and falling, so a charged hit is a critical, which never sweeps. */
    private boolean falling() {
        return !mc.player.onGround() && mc.player.fallDistance > 0;
    }

    private boolean attackable(Allay allay) {
        return isShulker(allay.getMainHandItem()) && mc.player.isWithinAttackRange(mc.player.getMainHandItem(), allay.getBoundingBox(), 0)
            && visible(allay);
    }
}
