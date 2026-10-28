/* Adapted from Quiettee Utils regression checks. SPDX-License-Identifier: GPL-3.0-or-later */
package dev.tntcoating.modules.boatfly;

import dev.tntcoating.modules.boatfly.BoatPhaseRemount;
import dev.tntcoating.modules.boatfly.BoatPhaseTravel;
import dev.tntcoating.modules.boatfly.BoatPhaseTravel.Mode;

public class BoatPhaseRemountTest {
    @org.junit.jupiter.api.Test
    void regression() { main(new String[0]); }
    public static void main(String[] args) {
        BoatPhaseRemount r=new BoatPhaseRemount();
        int checks=0;
        for(int mask=0;mask<16;mask++) {
            boolean grounded=(mask&1)!=0, deliberate=(mask&2)!=0, fallback=(mask&4)!=0, enabled=(mask&8)!=0;
            r.reset();r.intent(42,100,grounded,deliberate,fallback,enabled);
            if(r.board(42,101,true,enabled)!=(mask==15))throw new AssertionError("Incorrect landed retry "+mask);
            if(r.board(42,102,true,true))throw new AssertionError("Token reused");
            checks+=2;
        }
        r.intent(42,100,true,true,true,true);
        if(r.board(43,101,true,true))throw new AssertionError("Different boat");checks++;
        r.intent(42,100,true,true,true,true);
        if(r.board(42,101,false,true))throw new AssertionError("Passenger seat");checks++;
        if(!r.board(42,102,true,true))throw new AssertionError("Passenger update before control must retain token");checks++;
        r.intent(42,100,true,true,true,true);r.reset();
        if(r.board(42,101,true,true))throw new AssertionError("Changed connection/world");checks++;
        r.intent(42,100,true,true,true,true);
        if(r.board(42,301,true,true))throw new AssertionError("Expired token");checks++;
        r.intent(42,100,true,true,true,true);
        if(r.board(42,99,true,true))throw new AssertionError("Tick rollback");checks++;
        if(r.intent(42,100,false,false,true,true)||r.board(42,105,true,true))throw new AssertionError("Ejection must not retry");checks++;
        if(!r.intent(42,120,true,true,true,true)||!r.board(42,122,true,true))throw new AssertionError("Reboard after boat lands");checks++;
        r.intent(91,150,true,true,true,true);
        if(!r.board(91,153,true,true))throw new AssertionError("Replacement grounded boat");checks++;
        r.intent(91,160,true,true,true,true);
        if(r.board(91,161,true,false)||r.board(91,162,true,true))throw new AssertionError("Disabled retry");checks++;
        BoatPhaseTravel p=new BoatPhaseTravel();p.reject(Mode.SUBSTEPS);
        r.intent(42,200,true,true,true,true);
        if(r.board(42,202,true,true))p.retry(Mode.SUBSTEPS);
        if(p.select(true)!=Mode.SUBSTEPS)throw new AssertionError("Deliberate remount must retry substeps");checks++;
        System.out.println("BoatPhaseRemountTest: "+checks+" checks passed");
    }
}
