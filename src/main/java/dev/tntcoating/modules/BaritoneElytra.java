package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.compat.BaritoneBridge;
import dev.tntcoating.compat.BaritoneBridge.Trip;
import dev.tntcoating.core.ElytraHandoff;
import dev.tntcoating.core.ElytraHandoff.Refusal;
import dev.tntcoating.core.ElytraTakeoff;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;

public final class BaritoneElytra extends Module {
    /** Blocks of open air needed above the player's head to jump, open the elytra and climb away. */
    private static final double HEADROOM = 3;
    /** A landing at least this close to the goal is walked from, even when min-distance is set lower. Baritone looks for a landing spot once within 48 blocks of the end of its path. */
    private static final int NEAR = 128;
    private static final int RECHECK_TICKS = 10;
    private static final int RESTOCK_TICKS = 10;
    /** Baritone stays active with no path when its pathfinder fails, so a take-off that never gets one gives up after this. */
    private static final int PATH_TICKS = 200;
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Integer> minDistance = general.add(new IntSetting.Builder().name("min-distance").description("Trips shorter than this many blocks are walked.").defaultValue(300).min(32).sliderRange(32, 2000).build());
    private final Setting<Boolean> takeOff = general.add(new BoolSetting.Builder().name("take-off").description("Jump and open the elytra from flat ground. Without this, Baritone needs a drop to fall from, or its elytraAutoJump setting.").defaultValue(true).build());
    private final Setting<Boolean> equip = general.add(new BoolSetting.Builder().name("equip-elytra").description("Put on an elytra from your inventory for the flight.").defaultValue(true).build());
    private final Setting<Boolean> swapBack = general.add(new BoolSetting.Builder().name("swap-back").description("Put the chestplate the elytra replaced back on after landing.").defaultValue(true).visible(equip::get).build());
    private final Setting<Boolean> restock = general.add(new BoolSetting.Builder().name("restock-fireworks").description("Move fireworks into the hotbar when it has none. Baritone only uses hotbar fireworks unless its allowInventory setting is on.").defaultValue(true).build());
    private final ElytraTakeoff takeoff = new ElytraTakeoff();
    private BaritoneBridge baritone;
    /** The flight being looked after, or null. */
    private Trip trip;
    /**
     * True when this module started the flight from a #goto: it walks the rest after landing, manages the elytra and
     * ElytraFly, and reports how the flight ended. A flight started by #elytra or another addon (such as Stash-Hunter,
     * which toggles ElytraFly itself) only gets take-off help and firework restocking.
     */
    private boolean ours;
    private boolean glided;
    /** Take-off help has given up on a flight this module didn't start, which is left to Baritone and the player. */
    private boolean handsOff;
    /** Goal that is walked to the end and not checked again. */
    private Object settled;
    /** Goal refused for a reason that can clear up; checked again every {@link #RECHECK_TICKS}. */
    private Object waiting;
    private Refusal waitingReason;
    private int recheck;
    private int restockWait;
    private int pathWait;
    /** Inventory slot holding the chestplate the elytra replaced, or -1. */
    private int chestplateSlot = -1;
    private boolean resumeElytraFly;

    public BaritoneElytra() { super(TntCoatingAddon.CATEGORY, "baritone-elytra", "Baritone flies long #goto trips with an elytra and fireworks, then walks the rest."); }

    @Override public String getInfoString() {
        if (trip == null || mc.player == null) return null;
        return glided ? String.valueOf((int) remaining(trip)) : "Taking off";
    }

    @Override public void onActivate() {
        reset();
        baritone = BaritoneBridge.create();
        if (baritone == null) {
            error("Baritone isn't installed.");
            toggle();
        }
    }

    @Override public void onDeactivate() {
        try {
            if (trip != null && baritone != null && mc.player != null && baritone.flight() != null) {
                // Cancelling mid-air would leave the player gliding with nobody steering.
                if (mc.player.isFallFlying()) info("Baritone keeps flying; #stop cancels it.");
                else baritone.stop();
            }
        } catch (LinkageError | RuntimeException e) {
            MeteorClient.LOG.error("Stopping Baritone's elytra flight failed", e);
        }
        finishFlight();
        if (mc.player != null && !mc.player.isFallFlying()) swapBack();
        baritone = null;
    }

    // The world, Baritone's flight and the inventory slots it refers to are all gone.
    @EventHandler private void onGameLeft(GameLeftEvent event) {
        finishFlight();
        reset();
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || baritone == null) return;
        try {
            if (trip != null) tickFlight();
            else tickGround();
            if (trip == null && mc.player.onGround()) swapBack();
        } catch (LinkageError | RuntimeException e) {
            MeteorClient.LOG.error("Handing Baritone's trip to its elytra flight failed", e);
            error("Baritone didn't accept the elytra hand-off (%s). Turning off; see the log.", e);
            toggle();
        }
    }

    private void tickGround() {
        Trip flight = baritone.flight();
        if (flight != null) {
            // Started by #elytra or another addon.
            start(flight, false);
            return;
        }
        Trip walk = baritone.walking();
        if (walk == null || walk.goal() == settled) return;
        if (walk.goal() == waiting && ++recheck < RECHECK_TICKS) return;
        recheck = 0;
        Refusal refusal = refusal(walk);
        if (refusal == null) {
            launch(walk);
            return;
        }
        if (refusal.settled) settled = walk.goal();
        else if (walk.goal() == waiting && refusal == waitingReason) return;
        else {
            waiting = walk.goal();
            waitingReason = refusal;
        }
        report(refusal);
    }

    private void launch(Trip walk) {
        try {
            baritone.fly(walk);
        } catch (IllegalArgumentException e) {
            settled = walk.goal();
            warning("Baritone won't fly there: %s Walking instead.", e.getMessage());
            return;
        }
        start(walk, true);
        info("Flying to %d, %d, %d blocks away.", walk.x(), walk.z(), (int) remaining(walk));
    }

    private void start(Trip flight, boolean ours) {
        trip = flight;
        this.ours = ours;
        glided = mc.player.isFallFlying();
        handsOff = false;
        takeoff.reset(glided);
        restockWait = 0;
        pathWait = 0;
        ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
        if (ours && elytraFly.isActive()) {
            elytraFly.toggle();
            resumeElytraFly = true;
            info("Paused ElytraFly during the flight; Baritone steers with normal elytra physics.");
        }
    }

    private void tickFlight() {
        Trip flight = baritone.flight();
        if (flight == null) {
            endFlight();
            return;
        }
        // #goto and #goal mid-flight redirect it, and the new goal is the one to walk to after landing. A flight someone
        // else started is followed wherever it goes, landing spot included.
        if (!ours || flight.goal() != null && flight.goal() != trip.goal()) trip = flight;
        boolean fallFlying = mc.player.isFallFlying();
        glided |= fallFlying;
        if (ours && !fallFlying) equipElytra();
        restock();
        if (!takeOff.get() || handsOff || baritone.autoJump()) return;
        if (!baritone.readyForTakeoff()) {
            if (!glided && ++pathWait > PATH_TICKS) fallBack("Baritone couldn't find a flight path.", true);
            return;
        }
        boolean fluid = mc.player.isInWater() || mc.player.isInLava();
        ElytraTakeoff.Step step = fluid ? ElytraTakeoff.Step.COVERED : takeoff.next(mc.player.onGround(), fallFlying, mc.player.getDeltaMovement().y, openAbove());
        switch (step) {
            case JUMP -> mc.player.jumpFromGround();
            case OPEN -> {
                // What the client does for a second press of the jump key in the air.
                if (mc.player.tryToStartFallFlying()) mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
            case COVERED -> fallBack("Something is above you, or you're in water or lava, so you can't take off.", false);
            case GAVE_UP -> fallBack("Couldn't take off after %d jumps.".formatted(ElytraTakeoff.MAX_JUMPS), true);
            case WAIT -> {}
        }
    }

    /** Cancels a #goto flight that can't take off and walks instead. A flight someone else started is left to them. */
    private void fallBack(String reason, boolean settle) {
        Trip flight = trip;
        if (!ours || flight.goal() == null) {
            handsOff = true;
            warning("%s Take off by hand, or #stop.", reason);
            return;
        }
        baritone.stop();
        finishFlight();
        baritone.walk(flight);
        if (settle) {
            settled = flight.goal();
            warning("%s Walking instead.", reason);
        } else {
            // Checked again while walking, so it takes off once there is open sky.
            waiting = flight.goal();
            waitingReason = Refusal.COVERED;
            recheck = 0;
            warning("%s Walking until there's open sky.", reason);
        }
    }

    private void endFlight() {
        Trip flight = trip;
        boolean mine = ours, wasGliding = glided;
        finishFlight();
        // Whoever started it reports its end.
        if (!mine) return;
        double remaining = remaining(flight);
        switch (ElytraHandoff.end(mc.player.isFallFlying(), wasGliding, remaining, Math.max(minDistance.get(), NEAR))) {
            case WALK_REST -> {
                if (flight.goal() == null) return;
                settled = flight.goal();
                baritone.walk(flight);
                info("Landed %d blocks from the goal; walking the rest.", (int) remaining);
            }
            case LANDED_SHORT -> warning("Landed %d blocks short of %d, %d. Baritone lands early when fireworks or elytra durability run low; #goto again to carry on.", (int) remaining, flight.x(), flight.z());
            case STOPPED_MID_AIR -> warning("Baritone stopped the flight in mid-air; you're gliding on your own.");
            case STOPPED_ON_GROUND -> info("The elytra flight was cancelled before take-off.");
        }
    }

    private void finishFlight() {
        trip = null;
        ours = false;
        glided = false;
        if (resumeElytraFly) {
            resumeElytraFly = false;
            ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
            if (!elytraFly.isActive()) elytraFly.toggle();
        }
    }

    private void reset() {
        trip = null;
        settled = null;
        waiting = null;
        waitingReason = null;
        recheck = 0;
        chestplateSlot = -1;
    }

    private Refusal refusal(Trip walk) {
        int durability = elytraDurability(mc.player.getItemBySlot(EquipmentSlot.CHEST));
        if (equip.get()) {
            int slot = bestElytraSlot();
            if (slot >= 0) durability = Math.max(durability, elytraDurability(mc.player.getInventory().getItem(slot)));
        }
        boolean clear = !mc.player.isInWater() && !mc.player.isInLava() && openAbove();
        return ElytraHandoff.check(remaining(walk), minDistance.get(), baritone.elytraSupported(), durability, baritone.minDurability(),
            fireworks(), baritone.minFireworks(), mc.player.isFallFlying(), mc.player.onGround(), clear);
    }

    private void report(Refusal refusal) {
        switch (refusal) {
            case UNSUPPORTED -> warning("Baritone has no elytra pathfinder for this system. Walking.");
            case NO_ELYTRA -> warning("No elytra with at least %d durability %s (Baritone's elytraMinimumDurability). Walking.",
                baritone.minDurability(), equip.get() ? "worn or in your inventory" : "worn");
            case FIREWORKS -> warning("Only %d fireworks %s, and Baritone lands at %d (elytraMinFireworksBeforeLanding). Walking.",
                fireworks(), restock.get() || baritone.useInventory() ? "in your inventory" : "in your hotbar", baritone.minFireworks());
            case COVERED -> info("Something is above you, or you're in water or lava. Walking until there's open sky to take off.");
            case SHORT, AIRBORNE -> {}
        }
    }

    private boolean openAbove() {
        return mc.level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(0, HEADROOM, 0));
    }

    private double remaining(Trip trip) {
        return ElytraHandoff.horizontalDistance(mc.player.getX(), mc.player.getZ(), trip.x() + 0.5, trip.z() + 0.5);
    }

    private void equipElytra() {
        if (!equip.get() || mc.player.containerMenu != mc.player.inventoryMenu) return;
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (elytraDurability(chest) >= baritone.minDurability()) return;
        int slot = bestElytraSlot();
        if (slot < 0 || elytraDurability(mc.player.getInventory().getItem(slot)) < baritone.minDurability()) return;
        // The click swap leaves whatever was worn in the elytra's slot.
        boolean chestplate = !chest.isEmpty() && !chest.is(Items.ELYTRA) && mc.player.getEquipmentSlotForItem(chest) == EquipmentSlot.CHEST;
        InvUtils.move().from(slot).toArmor(2);
        if (chestplate) chestplateSlot = slot;
    }

    private void swapBack() {
        if (chestplateSlot < 0 || mc.player.containerMenu != mc.player.inventoryMenu) return;
        int slot = chestplateSlot;
        chestplateSlot = -1;
        ItemStack stored = mc.player.getInventory().getItem(slot);
        if (swapBack.get() && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) && !stored.isEmpty() && !stored.is(Items.ELYTRA)
            && mc.player.getEquipmentSlotForItem(stored) == EquipmentSlot.CHEST) InvUtils.move().from(slot).toArmor(2);
    }

    private void restock() {
        if (!restock.get() || baritone.useInventory()) return;
        if (restockWait > 0) {
            restockWait--;
            return;
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;
        Inventory inventory = mc.player.getInventory();
        int empty = -1;
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (isFirework(stack)) return;
            if (empty < 0 && stack.isEmpty()) empty = i;
        }
        for (int i = Inventory.SELECTION_SIZE; i < Inventory.INVENTORY_SIZE; i++) {
            if (!isFirework(inventory.getItem(i))) continue;
            // Without an empty hotbar slot, the item swapped out goes to where the fireworks were.
            int to = empty >= 0 ? empty : inventory.getSelectedSlot() == 8 ? 7 : 8;
            InvUtils.move().from(i).toHotbar(to);
            restockWait = RESTOCK_TICKS;
            return;
        }
    }

    /**
     * Fireworks Baritone can fire and also counts before landing for safety. It counts the 36 inventory slots (not the
     * offhand) but fires only from the hotbar, unless it or restock-fireworks moves them there.
     */
    private int fireworks() {
        int end = restock.get() || baritone.useInventory() ? Inventory.INVENTORY_SIZE : Inventory.SELECTION_SIZE;
        int count = 0;
        for (int i = 0; i < end; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (isFirework(stack)) count += stack.getCount();
        }
        return count;
    }

    /** Baritone's test: rockets with a star would hurt the player, so it never fires them. */
    private static boolean isFirework(ItemStack stack) {
        Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
        return stack.is(Items.FIREWORK_ROCKET) && fireworks != null && fireworks.explosions().isEmpty();
    }

    /** Remaining durability of an elytra Baritone can fly with (a real elytra, not about to break), otherwise -1. */
    private static int elytraDurability(ItemStack stack) {
        return stack.is(Items.ELYTRA) && !stack.nextDamageWillBreak() ? stack.getMaxDamage() - stack.getDamageValue() : -1;
    }

    private int bestElytraSlot() {
        int best = -1, bestDurability = -1;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            int durability = elytraDurability(mc.player.getInventory().getItem(i));
            if (durability > bestDurability) {
                best = i;
                bestDurability = durability;
            }
        }
        return best;
    }
}
