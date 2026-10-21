package dev.tntcoating.modules;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.GriefWebhook;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
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

/** One press posts a live grief alert to a Discord webhook, then the module turns itself off again. */
public final class GriefAlert extends Module {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** Long enough to upload a screenshot on a slow connection. */
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(30);
    /** How long the screenshot waits for an open menu to close before the alert goes without it. */
    private static final int MENU_WAIT_TICKS = 200;
    /** How long the GUI may stay hidden waiting for a frame to photograph. */
    private static final int FRAME_WAIT_TICKS = 40;

    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup contents = settings.createGroup("Contents");
    private final Map<GriefWebhook.Part, Setting<Boolean>> shown = new EnumMap<>(GriefWebhook.Part.class);
    private final Setting<String> webhookUrl = general.add(new StringSetting.Builder().name("webhook-url")
        .description("Discord webhook the alert is posted to: channel settings → Integrations → Webhooks → Copy Webhook URL. Anyone with this URL can post in the channel, so keep it private.")
        .defaultValue("").build());
    private final Setting<String> discordUserId = general.add(new StringSetting.Builder().name("discord-user-id")
        .description("Your Discord user ID, required: the clan's stash bot credits the alert to this account. In Discord, turn on Developer Mode, click your name and Copy User ID.")
        .defaultValue("").build());
    private final Setting<String> ping = general.add(new StringSetting.Builder().name("ping")
        .description("Who the alert pings: everyone, here (members online now), a role's ID (Developer Mode on, right-click the role, Copy Role ID), or blank for no ping.")
        .defaultValue("everyone").build());
    private final Setting<String> callToAction = general.add(new StringSetting.Builder().name("call-to-action")
        .description("Text under the alert's title.")
        .defaultValue("A live grief is happening right now. TPA in and help clear it out!").build());
    private final Setting<Integer> cooldown = general.add(new IntSetting.Builder().name("cooldown-seconds")
        .description("Ignore presses for this long after an alert is sent, so a double press doesn't ping twice. Zero disables the cooldown.")
        .defaultValue(30).range(0, 600).sliderRange(0, 120).build());
    private final Setting<Boolean> confirm = general.add(new BoolSetting.Builder().name("confirm")
        .description("Ask \"Send grief alert?\" before posting, so a stray key press doesn't ping anyone.")
        .defaultValue(true).build());

    {
        show(GriefWebhook.Part.NAME, "show-name", "Show your in-game name.");
        show(GriefWebhook.Part.FACE, "show-face", "Show your skin's face as the thumbnail.");
        show(GriefWebhook.Part.COORDINATES, "show-coordinates", "Show your block coordinates.");
        show(GriefWebhook.Part.DIMENSION, "show-dimension", "Show which dimension you're in.");
        show(GriefWebhook.Part.DUBS, "show-dubs", "Show the dubs in your loaded chunks.");
        show(GriefWebhook.Part.TPA, "show-tpa", "Show a copyable /tpa command with your name.");
        show(GriefWebhook.Part.CALL_TO_ACTION, "show-call-to-action", "Show the call-to-action text under the title.");
        show(GriefWebhook.Part.SERVER, "show-server", "Show the server address in the footer.");
        show(GriefWebhook.Part.SCREENSHOT, "show-screenshot", "Attach a screenshot of your view, taken without the HUD, chat or hand, as F1 does.");
    }

    /** When the last alert went out, in epoch milliseconds, or 0 when the cooldown is clear. */
    private long sentAt;
    private boolean sending;

    public GriefAlert() {
        super(TntCoatingAddon.CATEGORY, "grief-alert", "Posts a live grief alert to Discord with your name, coordinates and dub count, then turns itself off. Bind it to a key.");
        // Also runs from the main menu, to say there's nothing to report instead of firing on the next join.
        runInMainMenu = true;
        // The alert's result is reported instead.
        chatFeedback = false;
    }

    @Override public void onActivate() {
        toggle();
        if (!ready()) return;
        if (confirm.get()) ask();
        else send();
    }

    /** Whether an alert can go out now; otherwise says in chat why not. */
    private boolean ready() {
        if (mc.player == null || mc.level == null) {
            error("Join a world first.");
            return false;
        }
        if (!GriefWebhook.isWebhookUrl(webhookUrl.get())) {
            error("Set webhook-url to your Discord webhook URL first.");
            return false;
        }
        if (GriefWebhook.userId(discordUserId.get()) == null) {
            error(discordUserId.get().isBlank()
                ? "Set discord-user-id first: in Discord, turn on Developer Mode, click your name and Copy User ID."
                : "discord-user-id must be your Discord user ID, the long number from Copy User ID.");
            return false;
        }
        if (GriefWebhook.mention(ping.get()) == null) {
            error("ping must be everyone, here, a role's ID, or blank.");
            return false;
        }
        if (sending) {
            warning("Still sending the last alert.");
            return false;
        }
        long now = System.currentTimeMillis();
        long left = sentAt == 0 ? 0 : sentAt + cooldown.get() * 1000L - now;
        if (left > 0) {
            warning("An alert went out %ds ago; you can send another in %ds.", (now - sentAt) / 1000, (left + 999) / 1000);
            return false;
        }
        return true;
    }

    private void ask() {
        String mention = GriefWebhook.mention(ping.get());
        YesNoPrompt prompt = YesNoPrompt.create().title("Send grief alert?").dontShowAgainCheckboxVisible(false)
            .message(mention.isEmpty() ? "This posts a live grief alert to Discord without a ping."
                : "This posts a live grief alert to Discord and pings " + (mention.startsWith("<@&") ? "your role" : mention) + ".");
        if (shown.get(GriefWebhook.Part.COORDINATES).get()) prompt.message("Everyone who can see the channel will get your coordinates.");
        if (shown.get(GriefWebhook.Part.SCREENSHOT).get()) prompt.message("A screenshot of your view, without the HUD, is attached.");
        prompt.onYes(() -> {
            // Checked again: you may have left the world while the prompt was open.
            if (ready()) send();
        }).onNo(() -> info("Grief alert cancelled.")).show();
    }

    private void send() {
        Set<GriefWebhook.Part> parts = EnumSet.noneOf(GriefWebhook.Part.class);
        shown.forEach((part, setting) -> {
            if (setting.get()) parts.add(part);
        });
        BlockPos pos = mc.player.blockPosition();
        // Counted now, so the dubs are right whether or not Dub Counter is on.
        int chests = 0;
        if (parts.contains(GriefWebhook.Part.DUBS)) {
            for (ChunkAccess chunk : Utils.chunks()) {
                if (chunk instanceof LevelChunk levelChunk) chests += DubCounter.chestBlocks(levelChunk);
            }
        }
        ServerData server = mc.getCurrentServer();
        GriefWebhook.Alert alert = new GriefWebhook.Alert(mc.player.getName().getString(), GriefWebhook.userId(discordUserId.get()),
            pos.getX(), pos.getY(), pos.getZ(), GriefWebhook.dimensionName(mc.level.dimension().identifier().toString()), chests,
            server == null ? "" : server.ip);
        String mention = GriefWebhook.mention(ping.get());
        Upload upload = new Upload(URI.create(webhookUrl.get().strip()), GriefWebhook.payload(alert, parts, mention, callToAction.get(), Instant.now()), !mention.isEmpty());
        sending = true;
        sentAt = System.currentTimeMillis();
        if (parts.contains(GriefWebhook.Part.SCREENSHOT)) MeteorClient.EVENT_BUS.subscribe(new Capture(upload));
        else post(upload, null);
    }

    private void show(GriefWebhook.Part part, String name, String description) {
        shown.put(part, contents.add(new BoolSetting.Builder().name(name).description(description).defaultValue(true).build()));
    }

    /** An alert ready to post, built when you confirmed it so the position and dubs are from that moment. */
    private record Upload(URI webhook, JsonObject payload, boolean pinged) {}

    /** Posts the alert, with the screenshot when there is one, and reports the result in chat. */
    private void post(Upload upload, byte[] screenshot) {
        HttpRequest.Builder request = HttpRequest.newBuilder(upload.webhook()).timeout(UPLOAD_TIMEOUT).header("User-Agent", "Comet grief-alert");
        if (screenshot == null) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(upload.payload().toString()));
        } else {
            String boundary = "comet-" + UUID.randomUUID();
            request.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(GriefWebhook.multipart(boundary, GriefWebhook.withScreenshot(upload.payload()), screenshot)));
        }
        HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
            .whenComplete((response, failure) -> mc.execute(() -> finished(response, failure, upload.pinged())));
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
                if (mc.screen != null) {
                    if (++ticks == 20) info("Close the menu to take the grief alert's screenshot.");
                    if (ticks >= MENU_WAIT_TICKS) {
                        stop();
                        warning("A menu stayed open, so the alert went without a screenshot.");
                        post(upload, null);
                    }
                    return;
                }
                guiWasHidden = mc.options.hideGui;
                mc.options.hideGui = true;
                hidGui = true;
                ticks = 0;
                return;
            }
            // A frame that started after the GUI was hidden has finished by the next tick.
            if (frames == 0) {
                // Never leave the GUI hidden if no frame is drawn, such as while the window is minimised.
                if (++ticks >= FRAME_WAIT_TICKS) {
                    stop();
                    warning("No frame was drawn for the screenshot, so the alert went without one.");
                    post(upload, null);
                }
                return;
            }
            stop();
            RenderTarget target = mc.getMainRenderTarget();
            try {
                Screenshot.takeScreenshot(target, GriefWebhook.downscale(target.width, target.height), image -> Util.ioPool().execute(() -> encode(image)));
            } catch (RuntimeException e) {
                MeteorClient.LOG.warn("Taking the grief alert screenshot failed", e);
                warning("Couldn't take the screenshot, so the alert went without one.");
                post(upload, null);
            } finally {
                mc.options.hideGui = guiWasHidden;
            }
        }

        @EventHandler private void onRender(Render3DEvent event) {
            if (hidGui) frames++;
        }

        private void stop() {
            MeteorClient.EVENT_BUS.unsubscribe(this);
            if (hidGui) mc.options.hideGui = guiWasHidden;
        }

        /** Runs off the render thread: Minecraft only writes PNGs to files, so this reads one back. */
        private void encode(NativeImage image) {
            byte[] png = null;
            try {
                Path file = Files.createTempFile("comet-grief-", ".png");
                try {
                    image.writeToFile(file);
                    png = Files.readAllBytes(file);
                } finally {
                    Files.deleteIfExists(file);
                }
            } catch (IOException e) {
                MeteorClient.LOG.warn("Saving the grief alert screenshot failed", e);
            } finally {
                image.close();
            }
            byte[] screenshot = png;
            mc.execute(() -> {
                if (screenshot == null) warning("Couldn't save the screenshot, so the alert went without one.");
                post(upload, screenshot);
            });
        }
    }

    private void finished(HttpResponse<String> response, Throwable failure, boolean pinged) {
        sending = false;
        if (failure == null && response.statusCode() / 100 == 2) {
            info(pinged ? "Grief alert sent with a ping." : "Grief alert sent.");
            return;
        }
        // Nothing was posted, so a retry shouldn't wait out the cooldown.
        sentAt = 0;
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            MeteorClient.LOG.warn("Posting the grief alert to Discord failed", cause);
            error("Couldn't reach Discord (%s).", cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
            return;
        }
        int status = response.statusCode();
        if (status == 429) warning("Discord is rate limiting this webhook; try again in a few seconds.");
        else if (status == 413) error("The screenshot was too big for Discord (HTTP 413). Turn show-screenshot off or lower your resolution.");
        else if (status == 401 || status == 403 || status == 404) error("Discord doesn't recognise this webhook (HTTP %d). It may have been deleted; copy its URL again.", status);
        else {
            MeteorClient.LOG.warn("Discord refused the grief alert with HTTP {}: {}", status, response.body());
            error("Discord refused the alert (HTTP %d). See the log.", status);
        }
    }
}
