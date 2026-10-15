package dev.tntcoating.core;

/** Conservative feedback control using authoritative block confirmations, never client prediction. */
public final class ServerPacer {
    private int rate = 1, successes, blockedUntil, lastFailure = -1, sentTick = -1, sent;
    private double latency = 4;
    public void reset() { rate = 1; successes = blockedUntil = sent = 0; lastFailure = sentTick = -1; latency = 4; }
    public int budget(int tick, int cap, int pending, int window) {
        if (tick < blockedUntil) return 0;
        return Math.max(0, Math.min(Math.min(rate, cap) - (sentTick == tick ? sent : 0), window - pending));
    }
    public void sent(int tick) { if (sentTick != tick) { sentTick = tick; sent = 0; } sent++; }
    public void accepted(int elapsed, int cap) {
        latency = latency * .8 + Math.max(1, elapsed) * .2;
        if (++successes >= 12) { rate = Math.min(cap, rate + 1); successes = 0; }
    }
    public void failed(int tick, int pause) {
        if (lastFailure == tick) return;
        lastFailure = tick; rate = Math.max(1, rate / 2); successes = 0;
        blockedUntil = Math.max(blockedUntil, tick + pause);
    }
    public int timeout(int minimum) { return Math.max(minimum, Math.min(200, (int) Math.ceil(latency * 3 + 8))); }
}
