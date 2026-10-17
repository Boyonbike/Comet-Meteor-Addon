package dev.tntcoating.core;

/** Replays one tuning seed and dumps the route situation at a blocked decision. */
final class TrailBlockProbe {
    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        int minutes = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        var world = TrailSim.realistic(seed);
        double angle = seed * 0.77;
        double sx = Math.cos(angle) * 3_000, sz = Math.sin(angle) * 3_000;
        var sim = new TrailSim(world, TrailSim.Mode.OLD_112, 8, 1.5, sx, sz,
            ElytraSteer.wrap(ElytraSteer.yawTo(sx, sz) + 60), new TrailExplorer.Config(256, 256, 96));
        sim.navigator.setPattern(TrailExplorer.Pattern.SPIRAL, 768);
        sim.run(20 * 60 * minutes);
        sim.log.subList(Math.max(0, sim.log.size() - 16), sim.log.size()).forEach(System.out::println);
        System.out.printf("player %.0f,%.0f yaw %.0f%n", sim.x, sim.z, sim.yaw);
        StringBuilder blockedHeadings = new StringBuilder();
        for (int yaw = -180; yaw < 180; yaw += 15) {
            double r = Math.toRadians(yaw);
            boolean narrow = sim.memory.wouldRetrace(sim.x, sim.z, sim.x - Math.sin(r) * 256, sim.z + Math.cos(r) * 256, 2, 80);
            boolean wide = sim.memory.wouldParallelRetrace(sim.x, sim.z, sim.x - Math.sin(r) * 256, sim.z + Math.cos(r) * 256);
            blockedHeadings.append(String.format("%4d:%s%s ", yaw, narrow ? "N" : "-", wide ? "W" : "-"));
        }
        System.out.println(blockedHeadings);
        // ASCII map of flown cells around the player, 12 chunks each way.
        int pcx = Math.floorDiv((int) Math.floor(sim.x), 16), pcz = Math.floorDiv((int) Math.floor(sim.z), 16);
        for (int dz = -30; dz <= 30; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -40; dx <= 40; dx++) {
                if (dx == 0 && dz == 0) { row.append('@'); continue; }
                boolean flown = sim.memory.isVisitedChunk(pcx + dx, pcz + dz);
                byte kind = world.kind(pcx + dx, pcz + dz);
                row.append(flown ? '#' : kind == 2 ? 'o' : '.');
            }
            System.out.println(row);
        }
    }
}
