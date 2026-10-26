package dev.tntcoating.core;

import java.util.LinkedHashSet;
import java.util.Set;

/** Millisecond deadlines and a tick guard: never accumulate credit for catch-up bursts. */
public final class AutoTpyPacer {
    private long fixed=500, minimum=50, maximum=5000, current=500, lastSent, pauseUntil;
    private long sentTick=Long.MIN_VALUE;
    private boolean adaptive, hasSent;
    private int streak;
    private final Set<Long> confirmed = new LinkedHashSet<>();
    private final Set<String> rejections = new LinkedHashSet<>();
    public void configure(long fixedMs, boolean dynamic, long minMs, long maxMs) {
        if(fixedMs<0||fixedMs>60000||minMs<0||maxMs<minMs||maxMs>60000) throw new IllegalArgumentException("Invalid pacing bounds");
        boolean restart=dynamic!=adaptive||fixedMs!=fixed;
        fixed=fixedMs; minimum=minMs; maximum=maxMs; adaptive=dynamic;
        current=dynamic ? Math.clamp(restart?fixed:current,minimum,maximum):fixed;
        if(restart) streak=0;
    }
    public boolean ready(long now,long tickId) { return tickId!=sentTick && now>=nextSendAt(); }
    public void sent(long now,long tickId) { lastSent=now; sentTick=tickId; hasSent=true; }
    public long nextSendAt() { return Math.max(pauseUntil,hasSent?lastSent+intervalMs():Long.MIN_VALUE); }
    public long intervalMs() { return adaptive?current:fixed; }
    public void confirmed(long attemptId,long now) {
        if(!confirmed.add(attemptId)) return;
        trim(confirmed);
        if(adaptive&&++streak>=10) { current=Math.max(minimum,(long)Math.ceil(current*.9)); streak=0; }
    }
    public void rateLimited(String fingerprint,Long waitMs,long now) {
        if(!rejections.add(fingerprint)) return;
        trim(rejections); streak=0;
        if(adaptive) current=Math.min(maximum,Math.max(current*2,current+50));
        pauseUntil=Math.max(pauseUntil,now+Math.max(waitMs==null?intervalMs():waitMs,50));
    }
    public void pause(String fingerprint,Long waitMs,long now) {
        if(!rejections.add(fingerprint)) return;
        trim(rejections);
        pauseUntil=Math.max(pauseUntil,now+Math.max(waitMs==null?intervalMs():waitMs,50));
    }
    private static <T> void trim(Set<T> set) { if(set.size()>1024) set.remove(set.iterator().next()); }
    public void resetLearning() { current=adaptive?Math.clamp(fixed,minimum,maximum):fixed; streak=0; confirmed.clear(); }
    public void reset() { resetLearning(); hasSent=false; pauseUntil=0; sentTick=Long.MIN_VALUE; rejections.clear(); }
}
