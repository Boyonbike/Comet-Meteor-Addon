/*
 * Part of Comet's auto-set-home module.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tracks the server cooldown between sethome commands. A cooldown can be stated by the reply to a command or by a
 * later line that mentions setting a home; a line about teleporting home never delays setting one. Once a sethome
 * has been confirmed, the longest length the server has stated this session estimates the next wait, so the module
 * does not have to run into the refusal first.
 */
public final class SetHomeCooldown {
    private static final Pattern HOME = Pattern.compile("\\b(?:home|homes)\\b");
    private static final Pattern SET = Pattern.compile(
        "\\b(?:set|sets|setting|create|creates|created|creating|save|saves|saved|saving"
            + "|move|moves|moved|moving|replace|replaces|replaced|replacing|rename|renames|renamed|renaming)\\b"
            + "|\\b(?:another|new|extra) home\\b");
    private static final Pattern SETHOME = Pattern.compile("\\bsethome\\b");
    private static final Pattern COOLDOWN = Pattern.compile(
        "cooldown|\\bwait\\b|\\bagain\\b|\\bbefore\\b|\\bcan(?:not|'t)?\\b|\\bunable\\b|\\buse\\b"
            + "|\\bavailable\\b|\\bready\\b|\\bremaining\\b|\\bleft\\b|\\btry\\b");
    private static final Pattern FINISHED = Pattern.compile(
        "\\b(?:cooldown|wait|timer)\\b.*\\b(?:expired|ended|finished|over|complete(?:d)?)\\b"
            + "|\\b(?:can|may)\\s+(?:now\\s+)?(?:set|use|create|save)\\b.*\\bagain\\b"
            + "|\\byou can set (?:a |another )?home (?:now|again)\\b");
    private static final Pattern TELEPORT = Pattern.compile("\\bteleport(?:ing|ed|s|ation)?\\b");
    private static final Pattern FAILURE = Pattern.compile(
        "\\b(?:cannot|can't|unable|limit|maximum|reached|already|denied|refused|blocked|not allowed|no more)\\b");
    private static final Pattern NEGATED = Pattern.compile(
        "\\b(?:not|never|cannot|can't|hasn't|isn't|haven't|wasn't|won't)\\b");
    private static final long MAX = 86_400_000L;
    /** A generic "wait before using this command" reply only counts while it can be answering our command. */
    private static final long REPLY_WINDOW = 15_000L;

    private long learned;
    private long deadline;
    // Unlike deadline, this survives ready() consuming an expired cooldown.
    private long cooldownEnd;
    private boolean cooldownKnown;
    private long revision;
    private long sentAt;
    private long successAt;
    private boolean estimated;
    private boolean awaiting;
    // A refusal that stated no usable wait ("wait 0m 0sec") holds the next command until then.
    private long retryAt;

    /** The longest cooldown the server has stated this session, in milliseconds; 0 until it says one. */
    public long learned() {
        return learned;
    }

    /** Changes whenever a reported or estimated cooldown is established or ends early. */
    public long revision() { return revision; }

    /** Restores a learned length, for a caller that keeps it between sessions. */
    public void learned(long millis) {
        learned = Math.clamp(millis, 0, MAX);
    }

    /** Records that a sethome command went out, so a generic reply can be recognised as its cooldown. */
    public void sent(long now) {
        awaiting = true;
        sentAt = now;
        retryAt = 0;
    }

    /** Reads one server chat line. Returns true when the line stated a cooldown to wait for. */
    public boolean message(String raw, long now) {
        return message(raw, now, 0);
    }

    /**
     * Reads one server chat line. Returns true when the line stated a cooldown to wait for.
     *
     * @param retryMillis how long to hold the next command after a refusal that states no usable wait, such as
     *     "wait 0m 0sec"; 0 keeps the old behaviour of only waiting for the buffer
     */
    public boolean message(String raw, long now, long retryMillis) {
        if (awaiting && now - sentAt >= REPLY_WINDOW) awaiting = false;
        String text = ChatSender.plain(raw).toLowerCase(Locale.ROOT);
        if (text.isBlank()) return false;
        List<ChatTeleportState.Duration> durations = ChatTeleportState.durations(text);
        long stated = statedDuration(text, durations);
        boolean sethome = SETHOME.matcher(text).find();
        boolean home = HOME.matcher(text).find();
        boolean set = SET.matcher(text).find();
        boolean teleport = TELEPORT.matcher(text).find();
        boolean homeSet = sethome || home && set;

        // A /home teleport cooldown also says "home", and must never hold the next sethome back.
        if (teleport && !sethome) return false;

        boolean cue = COOLDOWN.matcher(text).find() || text.contains("cooldown");
        boolean recentSuccess = successAt != 0 && now >= successAt && now - successAt < 10_000;
        // A success line only answers us while our command can still be in flight; a join-time "your home is set"
        // status line must not look like a confirmation.
        if ((awaiting || recentSuccess) && ChatHomeEdit.setSuccess(raw)) {
            boolean duplicate = successAt != 0 && now >= successAt && now - successAt < 5_000 && deadline > now;
            if (duplicate) return false;
            successAt = now;
            awaiting = false;
            if (stated > 0) {
                report(stated, now);
                return true;
            }
            confirm(now);
            return false;
        }

        if (homeSet && durations.isEmpty() && FINISHED.matcher(text).find() && !NEGATED.matcher(text).find()) {
            finish(now);
            return false;
        }

        // The server may answer a refused command with a generic "wait N minutes before using this command", and may
        // announce the cooldown right after a success as "Home cooldown: 4m".
        boolean reply = (awaiting || recentSuccess) && cue
            && (homeSet || home && text.contains("cooldown")
                || !home && (text.contains("command") || text.contains("again") || text.contains("wait")
                    || text.contains("cooldown")));
        if (!durations.isEmpty() && stated == 0 && (homeSet && cue || reply)) {
            // A zero-second refusal still refuses the command; keep the next attempt from being immediate.
            if (reply) refused(now, retryMillis);
            else finish(now);
            return false;
        }
        if (stated > 0 && (homeSet && cue || reply)) {
            report(stated, now);
            awaiting = false;
            return true;
        }

        if (homeSet && FAILURE.matcher(text).find()) awaiting = false;
        return false;
    }

    /** A clicked override button or a success line confirms that the command finished. */
    public void confirmed(long now) {
        awaiting = false;
        successAt = now;
        confirm(now);
    }

    private void confirm(long now) {
        retryAt = 0;
        if (learned <= 0) return;
        long until = now + learned;
        if (until > deadline) {
            deadline = until;
            cooldownEnd = until;
            cooldownKnown = true;
            revision++;
            estimated = true;
        }
    }

    private void report(long millis, long now) {
        if (millis <= 0) return;
        retryAt = 0;
        // A refusal before the first confirmed success still provides a usable minimum
        // delay. Later refusals after a confirmation refine it to the full cycle length.
        learned = Math.max(learned, Math.min(millis, MAX));
        if (successAt != 0 && now >= successAt && now - successAt <= MAX)
            learned = Math.clamp(Math.max(learned, millis + now - successAt), 0, MAX);
        deadline = now + millis;
        cooldownEnd = deadline;
        cooldownKnown = true;
        revision++;
        estimated = false;
    }

    private void finish(long now) {
        // Early completion corrects an estimate. Duplicate/late completion lines preserve the first end.
        if (!cooldownKnown || cooldownEnd > now) {
            cooldownEnd = now;
            revision++;
        }
        cooldownKnown = true;
        deadline = 0;
        estimated = false;
        awaiting = false;
    }

    /**
     * A refused command whose reply stated no usable countdown still tells us not to try again immediately. It ends
     * the cooldown like {@link #finish(long)}, but holds the next command for the configured retry delay instead of
     * letting the short buffer alone decide, which used to make the module answer the same refusal every buffer.
     */
    private void refused(long now, long retryMillis) {
        boolean endedEarly = !cooldownKnown || cooldownEnd > now;
        if (endedEarly) cooldownEnd = now;
        cooldownKnown = true;
        deadline = 0;
        estimated = false;
        awaiting = false;
        if (retryMillis > 0) {
            retryAt = Math.max(retryAt, now + retryMillis);
            // The retry delay starts a new wait; the fallback interval must not stack on top of it.
            revision++;
        } else if (endedEarly) {
            revision++;
        }
    }

    /** True once the server has stated a cooldown, even if that cooldown has since ended. */
    public boolean known() {
        return cooldownKnown;
    }

    /**
     * Time left before a sethome command should wait, including an optional buffer after a stated cooldown and any
     * retry hold after a refusal without a usable countdown. This is intentionally independent of {@link #ready(long)}
     * so a HUD can read it without consuming the cooldown.
     */
    public long waitMillis(long now, long bufferMillis) {
        long buffer = Math.max(0, bufferMillis);
        long untilCooldown = deadline == 0 ? 0 : Math.max(0, deadline - now);
        if (untilCooldown > 0) return untilCooldown + buffer;
        if (!cooldownKnown) return 0;
        long bufferLeft = Math.max(0, buffer - (now - cooldownEnd));
        return Math.max(bufferLeft, retryAt == 0 ? 0 : Math.max(0, retryAt - now));
    }

    /** True once the server's cooldown is up, or when the server has not stated one. */
    public boolean ready(long now) {
        if (deadline == 0) return true;
        if (now < deadline) return false;
        deadline = 0;
        estimated = false;
        return true;
    }

    /** True after the server cooldown, the retry delay and an additional buffer; the first command needs no buffer. */
    public boolean readyAfter(long now, long bufferMillis) {
        return ready(now)
            && (retryAt == 0 || now >= retryAt)
            && (!cooldownKnown || now - cooldownEnd >= Math.max(0, bufferMillis));
    }

    /** Includes a buffer or retry label after the server countdown has ended. */
    public String statusAfter(long now, long bufferMillis) {
        String active = status(now);
        if (!active.isEmpty()) return active;
        long retryLeft = retryAt == 0 ? 0 : Math.max(0, retryAt - now);
        long bufferLeft = cooldownKnown
            ? Math.max(0, Math.max(0, bufferMillis) - (now - cooldownEnd))
            : 0;
        if (retryLeft > 0) return "Retry " + ChatTeleportState.remaining(Math.max(retryLeft, bufferLeft));
        if (!cooldownKnown) return "";
        return bufferLeft <= 0 ? "" : "Buffer " + ChatTeleportState.remaining(bufferLeft);
    }

    /** Time left before the next command, in milliseconds. */
    public long remaining(long now) {
        return deadline == 0 ? 0 : Math.max(0, deadline - now);
    }

    /** A short label such as "4m 30s" or "~5m", or "" while the command is allowed. */
    public String status(long now) {
        long left = remaining(now);
        return left <= 0 ? "" : (estimated ? "~" : "") + ChatTeleportState.remaining(left);
    }

    /** Forgets everything, for a new connection or a module reset. */
    public void reset() {
        learned = 0;
        deadline = 0;
        cooldownEnd = 0;
        cooldownKnown = false;
        revision = 0;
        sentAt = 0;
        successAt = 0;
        estimated = false;
        awaiting = false;
        retryAt = 0;
    }

    private static long statedDuration(String text, List<ChatTeleportState.Duration> durations) {
        if (durations.isEmpty()) return 0;
        var cue = COOLDOWN.matcher(text);
        if (cue.find()) for (var duration : durations) if (duration.start() >= cue.end()) return duration.millis();
        return durations.getLast().millis();
    }
}
