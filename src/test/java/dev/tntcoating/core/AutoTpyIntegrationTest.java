package dev.tntcoating.core;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class AutoTpyIntegrationTest {
    private AutoTpyController c() {
        var c=new AutoTpyController(); c.configure(new AutoTpyPolicy(Set.of(),Set.of(),false,false,true,false),Set.of(),1024,4,3,3000,1000,500,false,50,5000); c.enabled(true); return c;
    }
    @Test void explicitCancellationChangesStopQueuedAndInflightAttempts() {
        var c=c();c.offer(new AutoTpyQueue.Request("Alice",false,120000,"/tpy Alice"),0);
        c.lifecycle(new ChatTpaRequests.Change(ChatTpaRequests.ChangeKind.Cancelled,new ChatTpaRequests.Request("Alice",false,120000)),1);
        c.tick(2,1,cmd->fail("Cancelled request was sent"));
        c.offer(new AutoTpyQueue.Request("Bob",false,120000,"/tpy Bob"),3);c.tick(3,2,cmd->c.commandSent(cmd,3,2));
        c.message("Bob's teleport request has been cancelled.",4);
        c.tick(10000,3,cmd->fail("Cancelled request was retried"));
    }
    @Test void ambiguousRateLimitPausesWithoutTrainingAndSettingsDoNotBurst() {
        var c=c();c.configure(new AutoTpyPolicy(Set.of(),Set.of(),false,false,true,false),Set.of(),1024,4,3,3000,1000,500,true,50,5000);
        for(String n:List.of("Alice","Bob")) c.offer(new AutoTpyQueue.Request(n,false,120000,"/tpy "+n),0);
        c.tick(0,1,cmd->c.commandSent(cmd,0,1));c.tick(500,2,cmd->c.commandSent(cmd,500,2));
        c.message("Please wait 30 seconds before using this command again.",600);
        assertEquals(500,c.pacer().intervalMs());assertFalse(c.pacer().ready(30000,3));
    }
    @Test void disableClearsAndFilterChangeBeforeSendDeniesFriend() {
        var c=c();c.offer(new AutoTpyQueue.Request("Alice",false,120000,"/tpy Alice"),0);
        c.configure(new AutoTpyPolicy(Set.of("Alice"),Set.of("Alice"),true,true,true,false),Set.of("alice"),1024,4,3,3000,1000,500,false,50,5000);
        c.tick(0,1,cmd->fail("Blocked friend was sent"));assertEquals(0,c.queue().stats(0).live());
        c.enabled(false); assertTrue(c.queue().entries(1).isEmpty());
    }
    @Test void automaticSendDoesNotMeanAcceptedAndManualAnswerStopsRetries() {
        var c=c(); c.offer(new AutoTpyQueue.Request("Alice",false,120000,"/tpy Alice"),0);
        var sent=new java.util.ArrayList<String>();
        c.tick(0,1,cmd->{sent.add(cmd);c.commandSent(cmd,0,1);});
        assertEquals(List.of("/tpy Alice"),sent); assertEquals(1,c.queue().stats(0).awaiting());
        c.commandSent("tpy Alice",1,1); c.tick(10000,2,sent::add); assertEquals(1,sent.size());
    }
    @Test void localSendFailureRollsBackReservationAndCanRetry() {
        var c=c(); c.offer(new AutoTpyQueue.Request("Alice",false,120000,"/tpy Alice"),0);
        c.tick(0,1,cmd->{throw new IllegalStateException("closed");});
        assertEquals(0,c.queue().stats(0).awaiting());
        var sent=new java.util.ArrayList<String>();c.tick(1000,2,sent::add);
        assertEquals(List.of("/tpy Alice"),sent);
    }
    @Test void confirmsOnceAndResetNeverReplays() {
        var c=c(); c.offer(new AutoTpyQueue.Request("Alice",false,120000,"/tpy Alice"),0);
        c.tick(0,1,cmd->c.commandSent(cmd,0,1)); c.message("You accepted Alice's teleport request.",10);
        assertEquals(AutoTpyQueue.State.Accepted,c.queue().entries(10).getFirst().state());
        c.reset(); assertEquals(0,c.queue().stats(11).live());
    }
    @Test void unknownAndAmbiguousRepliesDoNotResolveAttempts() {
        var c=c(); for(String name:List.of("Alice","Bob")) c.offer(new AutoTpyQueue.Request(name,false,120000,"/tpy "+name),0);
        c.tick(0,1,cmd->c.commandSent(cmd,0,1)); c.tick(500,2,cmd->c.commandSent(cmd,500,2));
        c.message("Teleport request accepted.",600); assertEquals(2,c.queue().stats(600).awaiting());
        c.tick(4000,3,cmd->c.commandSent(cmd,4000,3)); c.message("Teleport request accepted.",4001);
        assertTrue(c.queue().entries(4001).stream().noneMatch(e->e.state()==AutoTpyQueue.State.Accepted));
    }
    @Test void revokedRoleCancelsQueuedAndRetryingRequestsBeforeEmission() {
        var c=c();
        c.configure(new AutoTpyPolicy(Set.of(),Set.of(),true,true,true,false,Set.of("Alice"),true),Set.of("alice"),1024,4,3,3000,1000,500,false,50,5000);
        c.offer(new AutoTpyQueue.Request("Alice",false,120000,"/tpy Alice"),0);
        c.configure(new AutoTpyPolicy(Set.of("Alice"),Set.of(),false,true,true,false,Set.of(),true),Set.of("alice"),1024,4,3,3000,1000,500,false,50,5000);
        c.tick(0,1,cmd->fail("Revoked role sent a queued request"));
        assertEquals(0,c.queue().stats(0).live());
        assertEquals(AutoTpyQueue.State.Cancelled,c.queue().entries(0).getFirst().state());
    }
}
