package dev.tntcoating.core;

/** Manual benchmark: java -cp build/classes/java/main:build/classes/java/test dev.tntcoating.core.TrailSimReport */
final class TrailSimReport {
    public static void main(String[] args) {
        boolean verbose = args.length > 0 && args[0].equals("-v");
        System.out.println("== Crossing a single trail (start heading south, trail crosses 600 blocks ahead)");
        for (int angle : new int[] {0, 30, 60, 75, 90}) {
            for (TrailSim.Mode mode : new TrailSim.Mode[] {TrailSim.Mode.OLD_112, TrailSim.Mode.EITHER}) {
                var world = crossing(angle);
                var sim = new TrailSim(world, mode, 8, 1.5, 8, 8, 0);
                sim.run(20 * 60 * 5);
                System.out.printf("angle %3d %-9s %s%n", angle, mode, sim.summary());
                if (verbose) sim.log.forEach(line -> System.out.println("    " + line));
            }
        }
        System.out.println("== Bent trail (40 degrees after 1500 blocks), started on the trail");
        for (TrailSim.Mode mode : new TrailSim.Mode[] {TrailSim.Mode.OLD_112, TrailSim.Mode.EITHER}) {
            var world = new TrailSim.World().trail(80, 0, -200, 0, 1_500, -Math.sin(Math.toRadians(40)) * 2_000, 1_500 + Math.cos(Math.toRadians(40)) * 2_000);
            world.base(-Math.sin(Math.toRadians(40)) * 2_000, 1_500 + Math.cos(Math.toRadians(40)) * 2_000, 100);
            var sim = new TrailSim(world, mode, 8, 1.5, 8, 8, 0);
            sim.run(20 * 60 * 4);
            System.out.printf("bend 40   %-9s %s%n", mode, sim.summary());
            if (verbose) sim.log.forEach(line -> System.out.println("    " + line));
        }
        System.out.println("== Random networks (16 spokes), 20 minutes from 1500 blocks out");
        int totalBases = 0;
        for (long seed = 1; seed <= 6; seed++) {
            for (TrailSim.Mode mode : new TrailSim.Mode[] {TrailSim.Mode.OLD_112, TrailSim.Mode.EITHER}) {
                var world = TrailSim.network(seed, 16).noise(0.003, 0.02);
                double angle = seed * 1.1;
                double sx = Math.cos(angle) * 1_500, sz = Math.sin(angle) * 1_500;
                float yaw = ElytraSteer.yawTo(sx, sz);
                var sim = new TrailSim(world, mode, 8, 1.5, sx, sz, yaw);
                sim.run(20 * 60 * 20);
                if (mode == TrailSim.Mode.OLD_112) totalBases += sim.basesFound.size();
                System.out.printf("seed %d %-9s %s%n", seed, mode, sim.summary());
                if (verbose) sim.log.forEach(line -> System.out.println("    " + line));
            }
        }
        System.out.println("OLD_112 network bases total: " + totalBases);
        System.out.println("== Realistic worlds, 30 minutes from 3000 blocks out, OLD_112");
        for (TrailExplorer.Pattern pattern : TrailExplorer.Pattern.values()) {
            int bases = 0, ends = 0, endsAtBases = 0;
            for (long seed = 1; seed <= 8; seed++) {
                var world = TrailSim.realistic(seed);
                double angle = seed * 0.77;
                double sx = Math.cos(angle) * 3_000, sz = Math.sin(angle) * 3_000;
                // Face 30 degrees off tangential, as a player might.
                float yaw = ElytraSteer.wrap(ElytraSteer.yawTo(sx, sz) + 60);
                var sim = new TrailSim(world, TrailSim.Mode.OLD_112, 8, 1.5, sx, sz, yaw);
                sim.navigator.setPattern(pattern, 768);
                sim.run(20 * 60 * 30);
                bases += sim.basesFound.size();
                for (var lead : sim.leads) {
                    if (lead.kind() != TrailExplorer.LeadKind.TRAIL_END) continue;
                    ends++;
                    if (world.bases.stream().anyMatch(base -> Math.hypot(base[0] - lead.x(), base[1] - lead.z()) < 300)) endsAtBases++;
                }
                System.out.printf("%-8s seed %d %s%n", pattern, seed, sim.summary());
                if (verbose) sim.log.forEach(line -> System.out.println("    " + line));
            }
            System.out.printf("%-8s TOTAL bases %d, trail-end leads %d (%d at bases)%n", pattern, bases, ends, endsAtBases);
        }
    }

    static TrailSim.World crossing(int angle) {
        double radians = Math.toRadians(angle);
        double dx = -Math.sin(radians), dz = Math.cos(radians);
        double cx = 0, cz = 600, length = 2_500;
        return new TrailSim.World().trail(80, cx - dx * length, cz - dz * length, cx + dx * length, cz + dz * length)
            .base(cx - dx * length, cz - dz * length, 100).base(cx + dx * length, cz + dz * length, 100);
    }
}
