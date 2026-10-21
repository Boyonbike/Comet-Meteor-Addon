package dev.tntcoating.modules;

import com.google.gson.JsonObject;
import dev.tntcoating.core.DiscordWebhook;
import dev.tntcoating.core.DiscordWebhook.Part;
import dev.tntcoating.core.DubCount;
import java.time.Instant;
import java.util.Set;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.render.prompts.YesNoPrompt;

/** One press reports the stash around you to the clan's stash bot, without pinging anyone, then the module turns itself off again. */
public final class StashReport extends DiscordWebhookModule {
    private final Setting<String> discordUserId = general.add(new StringSetting.Builder().name("discord-user-id")
        .description("Your Discord user ID, required: the stash bot credits the stash to this account. In Discord, turn on Developer Mode, click your name and Copy User ID.")
        .defaultValue("").build());

    {
        show(Part.NAME, "show-name", "Show your in-game name.");
        show(Part.FACE, "show-face", "Show your skin's face as the thumbnail.");
        show(Part.DIMENSION, "show-dimension", "Show which dimension you're in.");
        show(Part.SERVER, "show-server", "Show the server address in the footer.");
        show(Part.SCREENSHOT, "show-screenshot", "Attach a screenshot of your view, taken without the HUD, chat or hand, as F1 does. The stash bot only logs reports with one.");
    }

    public StashReport() {
        super("stash-report", "Reports the stash around you to the clan's stash bot with your Discord account, dub count and a screenshot, without pinging anyone, then turns itself off. Bind it to a key.",
            "stash report", "Discord webhook of the channel the clan's stash bot reads.");
    }

    @Override protected String problem() {
        if (discordUserId.get().isBlank()) return "Set discord-user-id first: in Discord, turn on Developer Mode, click your name and Copy User ID.";
        if (DiscordWebhook.userId(discordUserId.get()) == null) return "discord-user-id must be your Discord user ID, the long number from Copy User ID.";
        if (loadedChestBlocks() == 0) return "No chests are loaded around you, so there's no stash to report.";
        return null;
    }

    @Override protected void ask(YesNoPrompt prompt) {
        prompt.title("Report stash?").message("This reports " + DubCount.format(loadedChestBlocks()) + " dubs to the clan's stash bot under your Discord account.");
        if (!shown(Part.SCREENSHOT)) prompt.message("show-screenshot is off, and the stash bot only logs reports with a screenshot.");
    }

    @Override protected JsonObject payload(Set<Part> parts) {
        return DiscordWebhook.stashReport(here(DiscordWebhook.userId(discordUserId.get()), true), parts, Instant.now());
    }

    @Override protected String sentMessage() {
        return "Stash report sent; the stash bot reacts to it in Discord once it's logged.";
    }
}
