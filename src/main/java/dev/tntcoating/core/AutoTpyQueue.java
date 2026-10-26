package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Comparator;

/** A bounded per-player ledger, independent of Chat Tools' manual pending-request list. Client thread only. */
public final class AutoTpyQueue {
    public enum State { Queued, AwaitingReply, RetryWaiting, Accepted, Cancelled, Expired, Failed }
    public record Request(String player,boolean toThem,long expires,String command) {}
    public record Attempt(long id,long generation,Request request,int number,long sentAt) {}
    public record Entry(String player,State state,int attempts,long deadline,long expires,boolean friend,String reason) {}
    public record Stats(int live,int queued,int awaiting,int retries,int overflow,String latest) {}
    private static final class Node {
        Request request; long generation,order,deadline,terminalAt; int attempts; boolean friend,reserved,ambiguous;
        State state=State.Queued; String reason="Queued"; Attempt attempt;
        Node(Request request,long generation) { this.request=request; this.generation=generation; this.order=generation; }
        boolean live() { return state==State.Queued||state==State.AwaitingReply||state==State.RetryWaiting; }
    }
    public static final int MAX_TRACKED = 32768;
    private final Map<String,Node> nodes=new LinkedHashMap<>();
    private int capacity=1024,window=4,maxAttempts=3,live,overflow;
    private long timeout=3000,backoff=1000,sequence,attemptSequence;
    private boolean genericTainted;
    private String latest="No automatic attempts yet";
    public void configure(int cap,int inflight,int attempts,long replyTimeoutMs,long retryBackoffMs) {
        if(cap<1||cap>16384||inflight<1||inflight>32||attempts<1||attempts>10||replyTimeoutMs<1||retryBackoffMs<0) throw new IllegalArgumentException("Invalid queue configuration");
        capacity=cap;window=inflight;maxAttempts=attempts;timeout=replyTimeoutMs;backoff=retryBackoffMs;
    }
    public void offer(Request request,long now) {
        if(request==null||!ChatMessages.validName(request.player())||request.expires()<=now) return;
        String key=AutoTpyPolicy.key(request.player()); Node old=nodes.get(key);
        boolean same=old!=null&&old.request.toThem()==request.toThem()&&old.request.command().equalsIgnoreCase(request.command());
        if(same&&old.live()) { old.request=request; return; }
        // An identical prompt is indistinguishable from a delayed duplicate: suppress until the old invitation expires.
        if(same&&now<Math.max(old.request.expires(),old.terminalAt+120000)) return;
        if((live>=capacity && (old==null||!old.live())) || old==null&&nodes.size()>=MAX_TRACKED) {
            overflow++;latest="Buffer or duplicate history full: ignored "+request.player();return;
        }
        boolean ambiguous=old!=null&&old.attempts>0;
        if(old!=null&&old.live()) live--;
        var node=new Node(request,++sequence); node.ambiguous=ambiguous; nodes.put(key,node);live++;
    }
    public void reconcile(AutoTpyPolicy policy,Set<String> friends,long now) {
        expire(now);
        for(Node n:nodes.values()) if(n.live()) {
            n.friend=friends.contains(AutoTpyPolicy.key(n.request.player()));
            if(!policy.allows(n.request.player(),n.friend,n.request.toThem())) finish(n,State.Cancelled,"Not allowed by current filters",now);
        }
    }
    private void expire(long now) {
        var it=nodes.values().iterator();
        while(it.hasNext()) {
            Node n=it.next();
            if(!n.live()) { if(now>=Math.max(n.request.expires(),n.terminalAt+120000)) it.remove();continue; }
            if(now>=n.request.expires()) { finish(n,State.Expired,"Request expired",now);continue; }
            if(n.state==State.AwaitingReply&&now>=n.deadline) { genericTainted=true; retry(n,n.deadline,"No confirmation"); }
        }
        // Never evict an unexpired suppression record. New admissions backpressure at MAX_TRACKED instead.
    }
    private void retry(Node n,long now,String reason) {
        n.reserved=false;
        if(n.attempts>=maxAttempts) { finish(n,State.Failed,reason+"; attempt limit reached",now);return; }
        n.state=State.RetryWaiting;n.reason=reason;
        n.deadline=now+Math.min(30000,backoff*(1L<<Math.min(9,Math.max(0,n.attempts-1))));
    }
    public Optional<Attempt> next(long now) {
        expire(now);
        if(nodes.values().stream().filter(n->n.state==State.AwaitingReply||n.reserved).count()>=window) return Optional.empty();
        Node best=null;
        for(Node n:nodes.values()) if(n.live()&&n.state!=State.AwaitingReply&&!n.reserved&&now>=n.deadline) {
            if(best==null||compare(n,best)<0) best=n;
        }
        if(best==null) return Optional.empty();
        best.reserved=true; best.attempt=new Attempt(++attemptSequence,best.generation,best.request,best.attempts+1,now);
        return Optional.of(best.attempt);
    }
    private int compare(Node a,Node b) {
        return Comparator.<Node,Boolean>comparing(n->n.attempts==0).thenComparing(n->!n.friend).thenComparingLong(n->n.order).compare(a,b);
    }
    public void sent(Attempt attempt,long now) {
        Node n=matching(attempt); if(n==null||!n.reserved) return;
        if(nodes.values().stream().anyMatch(other -> other!=n && other.state==State.AwaitingReply)) genericTainted=true;
        n.reserved=false;n.attempts=attempt.number();n.state=State.AwaitingReply;n.deadline=now+timeout;n.reason="Awaiting server confirmation";
        latest="Sent acceptance for "+n.request.player()+" (attempt "+n.attempts+")";
    }
    public void sendFailed(Attempt attempt,long now) { Node n=matching(attempt); if(n!=null) { n.reserved=false;n.attempts=Math.max(0,attempt.number()-1);n.state=n.attempts==0?State.Queued:State.RetryWaiting;n.deadline=now+Math.max(50,backoff);n.reason="Local send failed"; } }
    private Node matching(Attempt a) { Node n=nodes.get(AutoTpyPolicy.key(a.request.player()));return n!=null&&n.live()&&n.attempt!=null&&n.attempt.id()==a.id()&&n.generation==a.generation()?n:null; }
    public void manual(String player,long now) { genericTainted=true; cancel(player,now); }
    public void invalidateGeneric() { genericTainted=true; }
    public boolean attributable(AutoTpyFeedback.Observation observation,long now) {
        expire(now);Node n=target(observation);
        return n!=null&&n.state==State.AwaitingReply&&!n.ambiguous;
    }
    private Node target(AutoTpyFeedback.Observation observation) {
        Node n;
        if(observation.player()!=null) n=nodes.get(AutoTpyPolicy.key(observation.player()));
        else {
            var pending=nodes.values().stream().filter(v->v.state==State.AwaitingReply).toList();
            n=!genericTainted&&pending.size()==1?pending.getFirst():null;
        }
        return n;
    }
    public Optional<Attempt> apply(AutoTpyFeedback.Observation observation,long now) {
        expire(now);Node n=target(observation);
        if(n==null||!n.live()) return Optional.empty();
        if(observation.kind()==AutoTpyFeedback.Kind.Accepted) {
            if(n.state!=State.AwaitingReply||n.ambiguous) return Optional.empty();
            Attempt trained=n.attempts==1?n.attempt:null;
            finish(n,State.Accepted,"Confirmed by server",now);return Optional.ofNullable(trained);
        }
        if(observation.kind()==AutoTpyFeedback.Kind.RateLimited) { if(n.state==State.AwaitingReply) retry(n,now,"Server rate limit"); }
        else finish(n,observation.kind()==AutoTpyFeedback.Kind.Expired?State.Expired:State.Cancelled,"Server: "+observation.kind(),now);
        return Optional.empty();
    }
    public void cancel(String player,long now) { Node n=nodes.get(AutoTpyPolicy.key(player));if(n!=null&&n.live()) finish(n,State.Cancelled,"Cancelled manually",now); }
    public void clear(long now) { for(Node n:nodes.values()) if(n.live()) finish(n,State.Cancelled,"Queue cleared",now);genericTainted=true; }
    private void finish(Node n,State state,String reason,long now) { if(n.live()) live--;n.state=state;n.reason=reason;n.terminalAt=now;n.reserved=false;latest=n.request.player()+": "+reason; }
    public void reset() { nodes.clear();live=overflow=0;genericTainted=false;latest="New session"; }
    public List<Entry> entries(long now) {
        expire(now);var result=new ArrayList<Entry>();
        for(Node n:nodes.values()) result.add(new Entry(n.request.player(),n.state,n.attempts,n.deadline,n.request.expires(),n.friend,n.reason));
        return List.copyOf(result);
    }
    public Stats stats(long now) {
        expire(now);int queued=0,awaiting=0,retries=0;
        for(Node n:nodes.values()) switch(n.state) { case Queued->queued++;case AwaitingReply->awaiting++;case RetryWaiting->retries++;default->{} }
        return new Stats(live,queued,awaiting,retries,overflow,latest);
    }
}
