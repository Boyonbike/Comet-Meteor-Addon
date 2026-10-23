package dev.tntcoating.modules;

import dev.tntcoating.mixin.AllayInvoker;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;

/**
 * Breeds dancing allays with amethyst shards and gives empty-handed allays shulker boxes, one right-click at a time.
 *
 * <p>A shard is only used on an allay that is dancing and off its duplication cooldown: any other allay with an empty
 * hand would just take the shard as its held item. Allays holding a shulker are bred too, since holding an item stops
 * neither dancing nor duplication.
 *
 * <p>With keep-population on, an allay given a shulker is not assumed dead: it holds one breed's worth of credit while
 * it lives, keeps it if it dies, and frees it again once seen alive without the shulker. Shulkers go out only while
 * that credit is below the duplications seen, so even if every allay holding one dies, no more are lost than bred.
 */
public final class AllayFarm extends AllayModule {
    /** The entity event an allay's duplication broadcasts, shown as hearts. */
    private static final byte DUPLICATED = 18;

    private final Setting<Boolean> breed = general.add(new BoolSetting.Builder().name("breed")
        .description("Duplicate allays dancing to a jukebox, using amethyst shards from your inventory.")
        .defaultValue(true).build());
    private final Setting<Boolean> giveShulkers = general.add(new BoolSetting.Builder().name("give-shulkers")
        .description("Give a shulker box from your inventory to each empty-handed allay in reach. Any shulker you carry can be given away.")
        .defaultValue(true).build());
    private final Setting<Boolean> keepPopulation = general.add(new BoolSetting.Builder().name("keep-population")
        .description("Give out no more shulkers than allays bred while this is on, so you never lose more allays than you breed.")
        .defaultValue(true).visible(giveShulkers::get).build());

    // Packet callbacks may run on the network thread; only this queue is shared with it.
    private final Queue<ClientboundEntityEventPacket> events = new ConcurrentLinkedQueue<>();
    // Allays given a shulker, by UUID, with the tick it was given, until seen alive without it.
    private final Map<UUID, Integer> exposed = new HashMap<>();
    private int bred;

    public AllayFarm() {
        super("allay-farm", "Breeds allays dancing to a jukebox and gives empty-handed allays shulker boxes, never to more allays than you breed.");
    }

    @Override protected void reset() {
        super.reset();
        events.clear();
        exposed.clear();
        bred = 0;
    }

    @EventHandler private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundEntityEventPacket packet && packet.getEventId() == DUPLICATED) events.add(packet);
    }

    @Override protected void tick() {
        ClientboundEntityEventPacket event;
        while ((event = events.poll()) != null) if (event.getEntity(mc.level) instanceof Allay) bred++;
        if (!exposed.isEmpty()) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                // Wait for the server's answer first; until then the client shows its own prediction.
                if (entity instanceof Allay allay && allay.isAlive() && !isShulker(allay.getMainHandItem())) {
                    exposed.computeIfPresent(allay.getUUID(), (uuid, given) -> ticks - given > REST_TICKS ? null : given);
                }
            }
        }
        if (!canRightClick()) return;
        boolean shards = breed.get() && findItem(AllayFarm::isShard).found();
        boolean shulkers = giveShulkers.get() && findItem(AllayModule::isShulker).found();
        Predicate<Allay> breedable = allay -> inReach(allay) && ready(allay) && visible(allay);
        // While breeding, allays ready to duplicate are left for that, so they are bred before they can be lost.
        Predicate<Allay> givable = allay -> inReach(allay) && creditLeft() && allay.getMainHandItem().isEmpty()
            && !(shards && ready(allay)) && visible(allay);
        Allay allay;
        if (shards && (allay = nearest(breedable)) != null) {
            status = "Breeding";
            use(allay, AllayFarm::isShard, breedable, false);
        } else if (shulkers && creditLeft() && (allay = nearest(givable)) != null) {
            status = "Giving a shulker";
            use(allay, AllayModule::isShulker, givable, true);
        } else {
            status = idle(shards, shulkers);
        }
    }

    private String idle(boolean shards, boolean shulkers) {
        if (!breed.get() && !giveShulkers.get()) return "Breed and give-shulkers are off";
        if (breed.get() && !shards) return "No amethyst shards";
        if (giveShulkers.get() && !shulkers) return "No shulker boxes";
        if (giveShulkers.get() && !creditLeft()) return "Waiting for a breed (" + exposed.size() + " given, " + bred + " bred)";
        return "No allay ready";
    }

    private static boolean isShard(ItemStack stack) {
        return stack.is(ItemTags.DUPLICATES_ALLAYS);
    }

    /** Whether a shard would duplicate this allay now, rather than be handed to it. */
    private static boolean ready(Allay allay) {
        return allay.isDancing() && ((AllayInvoker) allay).comet$canDuplicate();
    }

    private boolean inReach(Allay allay) {
        return mc.player.isWithinEntityInteractionRange(allay, 0);
    }

    private boolean creditLeft() {
        return !giveShulkers.get() || !keepPopulation.get() || exposed.size() < bred;
    }

    /** Faces the allay and right-clicks the part in view with the item from the inventory, if it is still wanted then. */
    private void use(Allay allay, Predicate<ItemStack> item, Predicate<Allay> wanted, boolean give) {
        rightClicked();
        aim(allay, REST_TICKS, point -> {
            FindItemResult found = findItem(item);
            if (!found.found() || !wanted.test(allay)) return;
            holding(found, hand -> {
                InteractionResult result = mc.gameMode.interactAt(mc.player, allay, new EntityHitResult(allay, point), hand);
                if (!result.consumesAction()) result = mc.gameMode.interact(mc.player, allay, hand);
                if (result instanceof InteractionResult.Success success) {
                    if (give) exposed.put(allay.getUUID(), ticks);
                    if (success.swingSource() == InteractionResult.SwingSource.CLIENT) mc.player.swing(hand);
                }
            });
        });
    }
}
