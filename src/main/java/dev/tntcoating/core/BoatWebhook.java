/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Boat-specific reporting using Comet's validated Discord delivery and mention handling. */
public final class BoatWebhook {
    private static final String BRAND = "Comet Auto Boat Fly";
    private BoatWebhook() {}

    private static List<HighwayWebhook.Field> fields(List<HighwayWebhook.Field> extra, int egaps) {
        var fields = new ArrayList<HighwayWebhook.Field>();
        fields.add(new HighwayWebhook.Field("eGaps remaining", String.valueOf(Math.max(0, egaps)), true));
        fields.addAll(extra);
        return fields;
    }

    public static void send(String url, HighwayWebhook.Event event, String description,
        HighwayWebhook.Context context, List<HighwayWebhook.Field> extra, int egaps, String mention) {
        HighwayWebhook.sendBranded(url, event, description, context, fields(extra, egaps), mention, BRAND);
    }

    static JsonObject payload(HighwayWebhook.Event event, String description, HighwayWebhook.Context context,
        List<HighwayWebhook.Field> extra, int egaps, Instant time, String mention) {
        return HighwayWebhook.payload(event, description, context, fields(extra, egaps), time, mention, BRAND);
    }
}
