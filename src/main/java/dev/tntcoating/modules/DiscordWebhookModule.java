package dev.tntcoating.modules;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.DiscordWebhook;
import dev.tntcoating.core.WebhookDelivery;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.prompts.YesNoPrompt;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * A module that posts one Discord webhook message per key press, optionally with a screenshot, then turns itself off
 * again. Call to Arms and Stash Report differ only in what they check, ask and post.
 */
abstract class DiscordWebhookModule extends Module {
    /** Long enough to upload a screenshot on a slow connection. */
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(30);
    /** How long the screenshot waits for an open menu to close before the message goes without it. */
    private static final int MENU_WAIT_TICKS = 200;
    /** How long the GUI may stay hidden waiting for a frame to photograph. */
    private static final int FRAME_WAIT_TICKS = 40;

    protected final SettingGroup general = settings.getDefaultGroup();
    private final Map<DiscordWebhook.Part, Setting<Boolean>> shown = new EnumMap<>(DiscordWebhook.Part.class);
    private final Setting<String> webhookUrl;
    private final Setting<Integer> cooldown;
    private final Setting<Boolean> confirm;
    private final SettingGroup contents;
    /** What is sent, as chat names it: "call to arms", "stash report". */
    private final String noun;

    /** When the last message went out, in epoch milliseconds, or 0 when the cooldown is clear. */
    private long sentAt;
    private boolean sending;

    protected DiscordWebhookModule(String name, String description, String noun, String webhookDescription) {
        super(TntCoatingAddon.CATEGORY, name, description);
        this.noun = noun;
        webhookUrl = general.add(new StringSetting.Builder().name("webhook-url")
            .description(webhookDescription + " Channel settings → Integrations → Webhooks → Copy Webhook URL. Anyone with this URL can post in the channel, so keep it private.")
            .defaultValue("").build());
        SettingGroup sendingGroup = settings.createGroup("Sending");
        cooldown = sendingGroup.add(new IntSetting.Builder().name("cooldown-seconds")
            .description("Ignore presses for this long after one is sent, so a double press doesn't post twice. Zero disables the cooldown.")
            .defaultValue(30).range(0, 600).sliderRange(0, 120).build());
        confirm = sendingGroup.add(new BoolSetting.Builder().name("confirm")
            .description("Ask before posting, so a stray key press doesn't post anything. While show-coordinates is on, it always asks.")
            .defaultValue(true).build());
        contents = settings.createGroup("Contents");
        // Also runs from the main menu, to say there's nothing to report instead of firing on the next join.
        runInMainMenu = true;
        // The result is reported instead.
        chatFeedback = false;
    }

    /** What stops this module's message going out, such as a setting it needs, or null when nothing does. */
    protected abstract String problem();

    /** Sets the confirmation prompt's title and says what will be posted. */
    protected abstract void ask(YesNoPrompt prompt);

    /** The message to post, describing where you are now. */
    protected abstract JsonObject payload(Set<DiscordWebhook.Part> parts);

    /** The chat line once Discord has accepted the message. */
    protected abstract String sentMessage();

    @Override public void onActivate() {
        toggle();
        if (!ready()) return;
        // Coordinates can't be taken back once posted, so sharing them always asks first.
        boolean coordinates = shown(DiscordWebhook.Part.COORDINATES);
        if (!confirm.get() && !coordinates) {
            send();
            return;
        }
        YesNoPrompt prompt = YesNoPrompt.create().dontShowAgainCheckboxVisible(false);
        ask(prompt);
        if (coordinates) {
            BlockPos pos = mc.player.blockPosition();
            prompt.message("Are you sure? Your coordinates, %d %d %d, will be posted for everyone who can see the channel.", pos.getX(), pos.getY(), pos.getZ());
        }
        prompt.onYes(() -> {
            // Checked again: you may have left the world while the prompt was open.
            if (ready()) send();
        }).onNo(() -> info("Cancelled; nothing was posted.")).show();
    }

    /** Adds a Contents switch for one part of the message. Coordinates start off; everything else starts on. */
    protected void show(DiscordWebhook.Part part, String name, String description) {
        boolean on = part != DiscordWebhook.Part.COORDINATES;
        shown.put(part, contents.add(new BoolSetting.Builder().name(name).description(description).defaultValue(on).build()));
    }

    protected boolean shown(DiscordWebhook.Part part) {
        Setting<Boolean> setting = shown.get(part);
        return setting != null && setting.get();
    }

    /** Ordinary chest blocks in the loaded chunks, counted now so it's right whether or not Dub Counter is on. */
    protected int loadedChestBlocks() {
        int chests = 0;
        for (ChunkAccess chunk : Utils.chunks()) {
            if (chunk instanceof LevelChunk levelChunk) chests += DubCounter.chestBlocks(levelChunk);
        }
        return chests;
    }

    /** Who and where you are now; dubs are only counted when the message shows them. */
    protected DiscordWebhook.Alert here(String discordId, boolean countDubs) {
        BlockPos pos = mc.player.blockPosition();
        ServerData server = mc.getCurrentServer();
        return new DiscordWebhook.Alert(mc.player.getName().getString(), discordId, pos.getX(), pos.getY(), pos.getZ(),
            DiscordWebhook.dimensionName(mc.level.dimension().identifier().toString()), countDubs ? loadedChestBlocks() : 0,
            server == null ? "" : server.ip);
    }

    /** Whether a message can go out now; otherwise says in chat why not. */
    private boolean ready() {
        if (mc.player == null || mc.level == null) {
            error("Join a world first.");
            return false;
        }
        if (!DiscordWebhook.isWebhookUrl(webhookUrl.get())) {
            error("Set webhook-url to your Discord webhook URL first.");
            return false;
        }
        String problem = problem();
        if (problem != null) {
            error(problem);
            return false;
        }
        if (sending) {
            warning("Still sending the last %s.", noun);
            return false;
        }
        long now = System.currentTimeMillis();
        long left = sentAt == 0 ? 0 : sentAt + cooldown.get() * 1000L - now;
        if (left > 0) {
            warning("A %s went out %ds ago; you can send another in %ds.", noun, (now - sentAt) / 1000, (left + 999) / 1000);
            return false;
        }
        return true;
    }

    private void send() {
        Set<DiscordWebhook.Part> parts = EnumSet.noneOf(DiscordWebhook.Part.class);
        shown.forEach((part, setting) -> {
            if (setting.get()) parts.add(part);
        });
        Upload upload = new Upload(DiscordWebhook.confirmedUri(webhookUrl.get()), payload(parts), sentMessage());
        sending = true;
        sentAt = System.currentTimeMillis();
        if (parts.contains(DiscordWebhook.Part.SCREENSHOT)) MeteorClient.EVENT_BUS.subscribe(new Capture(upload));
        else post(upload, null);
    }

    /** A message ready to post, built when you confirmed it so the position and dubs are from that moment. */
    private record Upload(URI webhook, JsonObject payload, String sentMessage) {}

    /** Posts the message, with the screenshot when there is one, and reports the result in chat. */
    private void post(Upload upload, byte[] screenshot) {
        HttpRequest.Builder request = HttpRequest.newBuilder(upload.webhook()).timeout(UPLOAD_TIMEOUT).header("User-Agent", "Comet " + name);
        if (screenshot == null) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(upload.payload().toString()));
        } else {
            String boundary = "comet-" + UUID.randomUUID();
            request.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(DiscordWebhook.multipart(boundary, DiscordWebhook.withScreenshot(upload.payload()), screenshot)));
        }
        WebhookDelivery.send(request.build())
            .thenAccept(result -> mc.execute(() -> finished(upload, result)));
    }

    /**
     * Takes the screenshot as F1 then F2 would: hides the GUI, waits for a whole frame drawn without it, grabs that frame and
     * puts the GUI back. Menus are drawn even with the GUI hidden, so it first waits for any open one to close.
     */
    private final class Capture {
        private final Upload upload;
        private int ticks, frames;
        private boolean hidGui, guiWasHidden;

        private Capture(Upload upload) {
            this.upload = upload;
        }

        @EventHandler private void onGameLeft(GameLeftEvent event) {
            // Nothing to photograph and nobody to tell.
            stop();
            sending = false;
            sentAt = 0;
        }

        @EventHandler private void onTick(TickEvent.Pre event) {
            if (mc.level == null) {
                onGameLeft(null);
                return;
            }
            if (!hidGui) {
                // The confirmation prompt closes on its own next frame; any other menu has to be closed by you.
                if (mc.gui.screen() != null) {
                    if (++ticks == 20) info("Close the menu to take the %s's screenshot.", noun);
                    if (ticks >= MENU_WAIT_TICKS) {
                        stop();
                        warning("A menu stayed open, so the %s went without a screenshot.", noun);
                        post(upload, null);
                    }
                    return;
                }
                guiWasHidden = mc.gui.hud.isHidden();
                if (!guiWasHidden) mc.gui.hud.toggle();
                hidGui = true;
                ticks = 0;
                return;
            }
            // A frame that started after the GUI was hidden has finished by the next tick.
            if (frames == 0) {
                // Never leave the GUI hidden if no frame is drawn, such as while the window is minimised.
                if (++ticks >= FRAME_WAIT_TICKS) {
                    stop();
                    warning("No frame was drawn for the screenshot, so the %s went without one.", noun);
                    post(upload, null);
                }
                return;
            }
            stop();
            RenderTarget target = mc.gameRenderer.mainRenderTarget();
            try {
                Screenshot.takeScreenshot(target, DiscordWebhook.downscale(target.width, target.height), image -> Util.ioPool().execute(() -> encode(image)));
            } catch (RuntimeException e) {
                MeteorClient.LOG.warn("Taking the {} screenshot failed", noun, e);
                warning("Couldn't take the screenshot, so the %s went without one.", noun);
                post(upload, null);
            } finally {
                if (mc.gui.hud.isHidden() != guiWasHidden) mc.gui.hud.toggle();
            }
        }

        @EventHandler private void onRender(Render3DEvent event) {
            if (hidGui) frames++;
        }

        private void stop() {
            MeteorClient.EVENT_BUS.unsubscribe(this);
            if (hidGui && mc.gui.hud.isHidden() != guiWasHidden) mc.gui.hud.toggle();
        }

        /** Runs off the render thread: Minecraft only writes PNGs to files, so this reads one back. */
        private void encode(NativeImage image) {
            byte[] png = null;
            try {
                Path file = Files.createTempFile("comet-screenshot-", ".png");
                try {
                    image.writeToFile(file);
                    png = Files.readAllBytes(file);
                } finally {
                    Files.deleteIfExists(file);
                }
            } catch (IOException e) {
                MeteorClient.LOG.warn("Saving the {} screenshot failed", noun, e);
            } finally {
                image.close();
            }
            byte[] screenshot = png;
            mc.execute(() -> {
                if (screenshot == null) warning("Couldn't save the screenshot, so the %s went without one.", noun);
                post(upload, screenshot);
            });
        }
    }

    private void finished(Upload upload, WebhookDelivery.Result result) {
        sending = false;
        if (result.outcome() == WebhookDelivery.Outcome.DELIVERED) {
            info(upload.sentMessage());
            return;
        }
        // A failed/discarded delivery does not consume the manual sender's cooldown.
        sentAt = 0;
        int status = result.status();
        if (result.outcome() == WebhookDelivery.Outcome.SUPPRESSED || status == 401 || status == 403 || status == 404) {
            error("Discord doesn't recognise this webhook (HTTP %d). It may have been deleted; copy its URL again.", status);
        } else if (status == 429) warning("Discord is still rate limiting this webhook; the queued %s expired. Try again later.", noun);
        else if (status == 413) error("The screenshot was too big for Discord (HTTP 413). Turn show-screenshot off or lower your resolution.");
        else if (result.outcome() == WebhookDelivery.Outcome.DISCARDED) {
            error("Couldn't deliver the %s within the queue's size, time or retry limit. Try again later.", noun);
        } else {
            MeteorClient.LOG.warn("Discord refused the {} with HTTP {}", noun, status);
            error("Discord refused the %s (HTTP %d).", noun, status);
        }
    }
}
