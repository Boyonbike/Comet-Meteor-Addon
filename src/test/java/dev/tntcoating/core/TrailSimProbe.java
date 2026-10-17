package dev.tntcoating.core;

/** Ad-hoc single scenario runs for investigating the simulator. */
final class TrailSimProbe {
    public static void main(String[] args) {
        TrailSim sim;
        if (args[0].startsWith("r")) {
            long seed = Long.parseLong(args[0].substring(1));
            var world = TrailSim.realistic(seed);
            double angle = seed * 0.77;
            double sx = Math.cos(angle) * 3_000, sz = Math.sin(angle) * 3_000;
            sim = new TrailSim(world, TrailSim.Mode.OLD_112, 8, 1.5, sx, sz, ElytraSteer.wrap(ElytraSteer.yawTo(sx, sz) + 60));
            sim.navigator.setPattern(TrailExplorer.Pattern.SPIRAL, 768);
        } else {
            sim = new TrailSim(TrailSimReport.crossing(Integer.parseInt(args[0])), TrailSim.Mode.OLD_112, 8, 1.5, 8, 8, 0);
        }
        int every = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int from = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int ticks = args.length > 3 ? Integer.parseInt(args[3]) : 20 * 60 * 30;
        for (int t = 0; t < ticks && !sim.stoppedBlocked; t++) {
            sim.step();
            if (t >= from && t % every == 0) {
                var d = sim.navigator.decision();
                System.out.printf("t=%5d pos %6.0f,%6.0f yaw %5.0f cmd %5.0f axis %5.0f %-9s trail %5.0f%n", t, sim.x, sim.z, sim.yaw,
                    d.yaw(), sim.navigator.explorer().axis(), d.state(), sim.navigator.trailBlocks());
            }
        }
        sim.log.forEach(System.out::println);
        sim.leads.forEach(System.out::println);
        System.out.println(sim.summary());
    }
}
