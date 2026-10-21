package dev.tntcoating.modules;

import com.google.gson.JsonObject;
import dev.tntcoating.core.DiscordWebhook;
import dev.tntcoating.core.DiscordWebhook.Part;
import java.time.Instant;
import java.util.Set;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.render.prompts.YesNoPrompt;

/** One press pings the clan's Discord to join a live grief, then the module turns itself off again. */
public final class CallToArms extends DiscordWebhookModule {
    private final Setting<String> ping = general.add(new StringSetting.Builder().name("ping")
        .description("Who the call to arms pings: everyone, here (members online now), a role's ID (Developer Mode on, right-click the role, Copy Role ID), or blank for no ping.")
        .defaultValue("everyone").build());
    private final Setting<String> callToAction = general.add(new StringSetting.Builder().name("call-to-action")
        .description("Text under the call to arms' title.")
        .defaultValue("A live grief is happening right now. TPA in and help clear it out!").build());

    {
        show(Part.NAME, "show-name", "Show your in-game name.");
        show(Part.FACE, "show-face", "Show your skin's face as the thumbnail.");
        show(Part.COORDINATES, "show-coordinates", "Show your block coordinates. Off by default; while on, posting always asks first and shows them.");
        show(Part.DIMENSION, "show-dimension", "Show which dimension you're in.");
        show(Part.DUBS, "show-dubs", "Show the dubs in your loaded chunks.");
        show(Part.TPA, "show-tpa", "Show a copyable /tpa command with your name.");
        show(Part.CALL_TO_ACTION, "show-call-to-action", "Show the call-to-action text under the title.");
        show(Part.SERVER, "show-server", "Show the server address in the footer.");
        show(Part.SCREENSHOT, "show-screenshot", "Attach a screenshot of your view, taken without the HUD, chat or hand, as F1 does.");
    }

    public CallToArms() {
        super("call-to-arms", "Pings your Discord to join a live grief, with your name, coordinates, dub count and a /tpa to copy, then turns itself off. Bind it to a key.",
            "call to arms", "Discord webhook the call to arms is posted to.");
    }

    @Override protected String problem() {
        return DiscordWebhook.mention(ping.get()) == null ? "ping must be everyone, here, a role's ID, or blank." : null;
    }

    @Override protected void ask(YesNoPrompt prompt) {
        String mention = DiscordWebhook.mention(ping.get());
        prompt.title("Send call to arms?").message(mention.isEmpty() ? "This posts a call to arms to Discord without a ping."
            : "This posts a call to arms to Discord and pings " + (mention.startsWith("<@&") ? "your role" : mention) + ".");
        if (shown(Part.SCREENSHOT)) prompt.message("A screenshot of your view, without the HUD, is attached.");
    }

    @Override protected JsonObject payload(Set<Part> parts) {
        return DiscordWebhook.callToArms(here(null, parts.contains(Part.DUBS)), parts, DiscordWebhook.mention(ping.get()), callToAction.get(), Instant.now());
    }

    @Override protected String sentMessage() {
        return DiscordWebhook.mention(ping.get()).isEmpty() ? "Call to arms sent." : "Call to arms sent with a ping.";
    }
}
