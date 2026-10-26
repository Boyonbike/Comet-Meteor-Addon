package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AutoTpyQueueTest {
    private AutoTpyQueue queue() { var q=new AutoTpyQueue(); q.configure(1024,4,3,3000,1000); return q; }
    private AutoTpyQueue.Request req(String name) { return new AutoTpyQueue.Request(name,false,120000,"/tpy "+name); }
    private void reconcile(AutoTpyQueue q, Set<String> friends, long now) { q.reconcile(new AutoTpyPolicy(Set.of(),Set.of(),false,false,true,false),friends,now); }
    private AutoTpyQueue.Attempt send(AutoTpyQueue q,long now) { var a=q.next(now).orElseThrow(); q.sent(a,now); return a; }
    @Test void saturatedHistoryRejectsNewAdmissionsWithoutForgettingCancelledPlayers() {
        var q=queue();q.configure(64,4,3,3000,1000);
        for(int i=0;i<AutoTpyQueue.MAX_TRACKED;i++) {q.offer(req("H"+i),0);q.cancel("H"+i,1);}
        q.offer(req("Overflow"),2);assertEquals(1,q.stats(2).overflow());assertEquals(0,q.stats(2).live());
        q.offer(req("H0"),3);reconcile(q,Set.of(),3);assertTrue(q.next(3).isEmpty());
        q.offer(new AutoTpyQueue.Request("Fresh",false,300000,"/tpy Fresh"),200000);
        // Expired history is reclaimed on tick; the following intake can proceed.
        reconcile(q,Set.of(),200000);q.offer(new AutoTpyQueue.Request("Fresh",false,300000,"/tpy Fresh"),200001);
        assertEquals(1,q.stats(200001).live());
    }
    @Test void ambiguousGenericReplyRemainsAmbiguousAfterNamedResolution() {
        var q=queue();q.offer(req("Alice"),0);q.offer(req("Bob"),0);reconcile(q,Set.of(),0);send(q,0);send(q,1);
        var generic=new AutoTpyFeedback.Observation(AutoTpyFeedback.Kind.Accepted,null,null,"same");
        q.apply(generic,2);
        q.apply(new AutoTpyFeedback.Observation(AutoTpyFeedback.Kind.Accepted,"Alice",null,"named"),3);
        assertTrue(q.apply(generic,4).isEmpty());
        assertEquals(1,q.stats(4).awaiting());
    }
    @Test void terminalHistoryNeverEvictsUnexpiredSuppression() {
        var q=queue();q.configure(64,4,3,3000,1000);
        for(int i=0;i<65;i++) {q.offer(req("P"+i),0);q.cancel("P"+i,1);}
        q.stats(2);q.offer(req("P0"),3);reconcile(q,Set.of(),3);assertTrue(q.next(3).isEmpty());
    }
    @Test void burstOf1000IsRetainedWhileSenderBlocked() {
        var q=queue(); for(int i=0;i<1000;i++) q.offer(req("Player"+i),0);
        reconcile(q,Set.of(),0); assertEquals(1000,q.stats(0).live());
        for(int i=0;i<4;i++) send(q,i);
        assertTrue(q.next(10).isEmpty()); assertEquals(1000,q.stats(10).live());
    }
    @Test void capacityRejectsNewButRefreshesExisting() {
        var q=queue(); q.configure(64,4,3,3000,1000);
        for(int i=0;i<65;i++) q.offer(req("Player"+i),0);
        q.offer(req("Player0"),1); assertEquals(64,q.stats(1).live()); assertEquals(1,q.stats(1).overflow());
    }
    @Test void dueRetriesThenFriendsThenArrivalAndBackoffDoesNotBlockOtherPlayers() {
        var q=queue(); q.offer(req("Alice"),0); reconcile(q,Set.of(),0); send(q,0);
        q.offer(req("Bob"),1); q.offer(req("Carol"),2); reconcile(q,Set.of("carol"),3000);
        assertEquals("Carol",send(q,3000).request().player());
        reconcile(q,Set.of("carol"),4000); assertEquals("Alice",send(q,4000).request().player());
        assertEquals("Bob",send(q,4001).request().player());
    }
    @Test void duplicatesDoNotResetAttemptsAndRetryLimitStopsAtThreeTotalAttempts() {
        var q=queue(); q.offer(req("Alice"),0); reconcile(q,Set.of(),0); send(q,0);
        q.offer(req("alice"),100); reconcile(q,Set.of(),4000); assertEquals(2,send(q,4000).number());
        reconcile(q,Set.of(),9000); assertEquals(3,send(q,9000).number());
        reconcile(q,Set.of(),12000); assertTrue(q.next(20000).isEmpty()); assertEquals(AutoTpyQueue.State.Failed,q.entries(20000).getFirst().state());
    }
    @Test void expiryManualAndPolicyEditsStopRetriesAndClearSuppressesCurrentGeneration() {
        var q=queue(); q.offer(req("Alice"),0); reconcile(q,Set.of(),0); send(q,0); q.manual("Alice",1);
        q.offer(req("Alice"),2); reconcile(q,Set.of(),10000); assertTrue(q.next(10000).isEmpty());
        q.offer(req("Bob"),2); q.clear(3); q.offer(req("Bob"),4); reconcile(q,Set.of(),4); assertTrue(q.next(4).isEmpty());
        q.offer(req("Carol"),5); q.reconcile(new AutoTpyPolicy(Set.of(),Set.of("Carol"),false,true,true,true),Set.of("carol"),5);
        assertTrue(q.next(5).isEmpty()); assertEquals(0,q.stats(120000).live());
    }
    @Test void lateReplyCannotConfirmNewGenerationAndAmbiguousRepliesDoNotAccept() {
        var q=queue(); q.offer(req("Alice"),0); q.offer(req("Bob"),0); reconcile(q,Set.of(),0); send(q,0); send(q,1);
        assertTrue(q.apply(new AutoTpyFeedback.Observation(AutoTpyFeedback.Kind.Accepted,null,null,"generic"),2).isEmpty());
        assertEquals(2,q.stats(2).awaiting());
        q.offer(new AutoTpyQueue.Request("Alice",true,120000,"/tpy Alice"),3);
        reconcile(q,Set.of(),3);
        assertTrue(q.apply(new AutoTpyFeedback.Observation(AutoTpyFeedback.Kind.Accepted,"Alice",null,"late"),4).isEmpty());
    }
    @Test void maximumBufferStaysBounded() {
        var q=queue(); q.configure(16384,4,3,3000,1000);
        for(int i=0;i<16385;i++) q.offer(req("P"+i),0);
        assertEquals(16384,q.stats(0).live()); assertEquals(1,q.stats(0).overflow());
    }
}
