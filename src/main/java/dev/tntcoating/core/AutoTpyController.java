package dev.tntcoating.core;

import java.util.Set;
import java.util.function.Consumer;

/** Client-thread coordinator. Only replies already filtered as server messages may be passed to message(). */
public final class AutoTpyController {
    private final AutoTpyQueue queue=new AutoTpyQueue();
    private final AutoTpyPacer pacer=new AutoTpyPacer();
    private AutoTpyPolicy policy=new AutoTpyPolicy(Set.of(),Set.of(),true,false,true,false);
    private Set<String> friends=Set.of();
    private boolean enabled;
    private String emission;
    private long emissionAt,sendEpoch;
    public AutoTpyQueue queue() { return queue; }
    public AutoTpyPacer pacer() { return pacer; }
    public boolean enabled() { return enabled; }
    public void enabled(boolean value) { if(enabled!=value) { reset();enabled=value; } }
    public void configure(AutoTpyPolicy policy,Set<String> friends,int capacity,int window,int attempts,long timeout,long backoff,
                          long interval,boolean adaptive,long minimum,long maximum) {
        pacer.configure(interval,adaptive,minimum,maximum);
        queue.configure(capacity,window,attempts,timeout,backoff);
        this.policy=policy;this.friends=AutoTpyPolicy.keys(friends);
    }
    public void offer(AutoTpyQueue.Request request,long now) {
        if(enabled&&policy.allows(request.player(),friends.contains(AutoTpyPolicy.key(request.player())),request.toThem())) queue.offer(request,now);
    }
    public void tick(long now,long tick,Consumer<String> sender) {
        if(!enabled) return;
        queue.reconcile(policy,friends,now);
        if(emission!=null&&now-emissionAt>=3000) emission=null;
        if(!pacer.ready(now,tick)) return;
        var candidate=queue.next(now); if(candidate.isEmpty()) return;
        var attempt=candidate.get(); emission=normalise(attempt.request().command());emissionAt=now;
        // Reserve/finalise before calling back: PacketEvent.Sent can fire synchronously inside sender.accept.
        queue.sent(attempt,now);
        try {
            sender.accept(attempt.request().command());
            pacer.sent(now,tick);sendEpoch++;
        } catch(RuntimeException e) {
            emission=null;queue.sendFailed(attempt,now);
            pacer.sent(now,tick); // Back off even on local failure; do not loop every frame.
        }
    }
    public void commandSent(String command,long now,long tick) {
        String normal=normalise(command);
        if(emission!=null&&emission.equals(normal)) { emission=null;return; }
        String[] parts=normal.split("\\s+");
        String verb=parts[0].substring(parts[0].lastIndexOf(':')+1);
        if(!Set.of("tpy","tpaccept","tpyes","tpn","tpdeny","tpno","tpa","tpahere","tpacancel","tpcancel").contains(verb)) return;
        pacer.sent(now,tick);queue.invalidateGeneric();
        if(parts.length==2&&Set.of("tpy","tpaccept","tpyes","tpn","tpdeny","tpno").contains(verb)) queue.manual(parts[1],now);
    }
    public void lifecycle(ChatTpaRequests.Change change,long now) {
        if(!enabled||!change.named()||change.kind()==ChatTpaRequests.ChangeKind.Accepted) return;
        if(change.kind()==ChatTpaRequests.ChangeKind.Cancelled||change.kind()==ChatTpaRequests.ChangeKind.Expired) {
            var kind=change.kind()==ChatTpaRequests.ChangeKind.Expired?AutoTpyFeedback.Kind.Expired:AutoTpyFeedback.Kind.Cancelled;
            queue.apply(new AutoTpyFeedback.Observation(kind,change.request().player(),null,"request lifecycle"),now);
        }
    }
    public void message(String text,long now) {
        if(!enabled) return;
        var parsed=AutoTpyFeedback.parse(text);if(parsed.isEmpty()) return;
        var observation=parsed.get();
        if(observation.kind()==AutoTpyFeedback.Kind.RateLimited && queue.stats(now).awaiting()>0) {
            String fingerprint=sendEpoch+":"+observation.fingerprint();
            if(queue.attributable(observation,now)) pacer.rateLimited(fingerprint,observation.waitMs(),now);
            else pacer.pause(fingerprint,observation.waitMs(),now);
        }
        queue.apply(observation,now).ifPresent(a->pacer.confirmed(a.id(),now));
    }
    private static String normalise(String command) { return command.strip().replaceFirst("^/","").toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+"," "); }
    public void reset() { queue.reset();pacer.reset();emission=null;sendEpoch=0; }
}
