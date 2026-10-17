package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

/**
 * Manual evaluation over many seeded worlds, run in parallel. Arguments are NAME=value pairs: pattern, spacing,
 * escape, view, speed, minutes, seeds, mode. To try other explorer constants, edit them and rerun.
 * java -cp build/classes/java/main:build/classes/java/test:fastutil.jar dev.tntcoating.core.TrailSimTune seeds=48
 */
final class TrailSimTune {
    public static void main(String[] args) {
        String pattern = "SPIRAL", mode = "OLD_112";
        boolean highways = false, avoid = false, nether = false;
        double spacing = 768, escape = 96, speed = 1.5;
        int view = 8, minutes = 30, seeds = 16, send = 4;
        for (String arg : args) {
            String[] pair = arg.split("=", 2);
            switch (pair[0]) {
                case "pattern" -> pattern = pair[1];
                case "mode" -> mode = pair[1];
                case "spacing" -> spacing = Double.parseDouble(pair[1]);
                case "escape" -> escape = Double.parseDouble(pair[1]);
                case "speed" -> speed = Double.parseDouble(pair[1]);
                case "view" -> view = Integer.parseInt(pair[1]);
                case "minutes" -> minutes = Integer.parseInt(pair[1]);
                case "seeds" -> seeds = Integer.parseInt(pair[1]);
                case "send" -> send = Integer.parseInt(pair[1]);
                case "highways" -> highways = Boolean.parseBoolean(pair[1]);
                case "avoid" -> avoid = Boolean.parseBoolean(pair[1]);
                case "nether" -> nether = Boolean.parseBoolean(pair[1]);
                default -> throw new IllegalArgumentException("unknown option " + pair[0]);
            }
        }
        final String p = pattern, m = mode;
        final double sp = spacing, es = escape, spd = speed;
        final int v = view, ticks = minutes * 60 * 20;
        final boolean hw = highways, av = avoid, nt = nether;
        final int sendRate = send;
        List<TrailSim> sims = LongStream.rangeClosed(1, seeds).parallel().mapToObj(seed -> {
            var world = hw ? TrailSim.withHighways(TrailSim.realistic(seed), seed) : TrailSim.realistic(seed);
            double angle = seed * 0.77;
            double sx = Math.cos(angle) * 3_000, sz = Math.sin(angle) * 3_000;
            var sim = new TrailSim(world, TrailSim.Mode.valueOf(m), v, spd, sx, sz,
                ElytraSteer.wrap(ElytraSteer.yawTo(sx, sz) + 60),
                nt ? new TrailExplorer.Config(512, 384, 128) : new TrailExplorer.Config(256, 256, es));
            sim.navigator.setPattern(TrailExplorer.Pattern.valueOf(p), sp);
            sim.avoidHighways = av;
            sim.sendPerTick = sendRate;
            sim.run(ticks);
            return sim;
        }).toList();
        int bases = 0, ends = 0, atBases = 0, blocked = 0, forks = 0;
        List<Double> leadErrors = new ArrayList<>();
        long overOld = 0, total = 0;
        List<String> perSeed = new ArrayList<>();
        for (var sim : sims) {
            bases += sim.basesFound.size();
            overOld += sim.ticksOverOld;
            total += sim.tick;
            if (sim.stoppedBlocked) {
                blocked++;
                System.out.println("  blocked: seed " + (sims.indexOf(sim) + 1) + " " + sim.log.get(sim.log.size() - 1)
                    + " after " + sim.log.get(Math.max(0, sim.log.size() - 2)));
            }
            for (var lead : sim.leads) {
                if (lead.kind() == TrailExplorer.LeadKind.FORK) { forks++; continue; }
                ends++;
                double nearest = sim.world.bases.stream().mapToDouble(b -> Math.hypot(b[0] - lead.x(), b[1] - lead.z())).min().orElse(1e9);
                if (nearest < 300) {
                    atBases++;
                    leadErrors.add(nearest);
                }
            }
            perSeed.add(String.valueOf(sim.basesFound.size()));
        }
        leadErrors.sort(null);
        double median = leadErrors.isEmpty() ? 0 : leadErrors.get(leadErrors.size() / 2);
        System.out.printf("%s => bases %d, ends %d (%d at bases, %.0f%%, median %.0f blocks off), forks %d, over-old %.1f%%, blocked %d  [%s]%n",
            String.join(" ", args), bases, ends, atBases, 100.0 * atBases / Math.max(1, ends), median, forks,
            100.0 * overOld / Math.max(1, total), blocked, String.join(",", perSeed));
    }
}
