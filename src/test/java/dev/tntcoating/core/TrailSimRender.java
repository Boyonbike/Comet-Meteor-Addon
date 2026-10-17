package dev.tntcoating.core;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Renders one realistic seeded flight: old chunks grey, modern traffic tan, path blue, bases red, leads green. */
final class TrailSimRender {
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        String out = args[1];
        int minutes = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        var world = TrailSim.realistic(seed);
        double angle = seed * 0.77;
        double sx = Math.cos(angle) * 3_000, sz = Math.sin(angle) * 3_000;
        var sim = new TrailSim(world, TrailSim.Mode.OLD_112, 8, 1.5, sx, sz,
            ElytraSteer.wrap(ElytraSteer.yawTo(sx, sz) + 60), new TrailExplorer.Config(256, 256, 96));
        sim.navigator.setPattern(TrailExplorer.Pattern.SPIRAL, 768);
        List<double[]> path = new ArrayList<>();
        List<TrailExplorer.State> states = new ArrayList<>();
        for (int t = 0; t < minutes * 1200 && !sim.stoppedBlocked; t++) {
            sim.step();
            if (t % 10 == 0) { path.add(new double[] {sim.x, sim.z}); states.add(sim.navigator.decision().state()); }
        }
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] p : path) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[1]); maxZ = Math.max(maxZ, p[1]); }
        minX -= 600; maxX += 600; minZ -= 600; maxZ += 600;
        double scale = 1200 / Math.max(maxX - minX, maxZ - minZ);
        int w = (int) ((maxX - minX) * scale), h = (int) ((maxZ - minZ) * scale);
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        int step = Math.max(1, (int) Math.ceil(1 / scale / 16));
        for (int px = 0; px < w; px++)
            for (int pz = 0; pz < h; pz++) {
                int cx = Math.floorDiv((int) (minX + px / scale), 16), cz = Math.floorDiv((int) (minZ + pz / scale), 16);
                byte kind = world.kind(cx, cz);
                if (kind == 2) image.setRGB(px, pz, 0xB0B0B0);
                else if (kind == 1) image.setRGB(px, pz, 0xF0E0C0);
            }
        g.setStroke(new BasicStroke(1.5f));
        for (int i = 1; i < path.size(); i++) {
            g.setColor(switch (states.get(i)) {
                case FOLLOWING -> new Color(0, 70, 220);
                case SEARCHING -> new Color(120, 0, 160);
                case SEEKING -> new Color(0, 160, 200);
                case ESCAPING -> new Color(230, 120, 0);
            });
            g.drawLine((int) ((path.get(i - 1)[0] - minX) * scale), (int) ((path.get(i - 1)[1] - minZ) * scale),
                (int) ((path.get(i)[0] - minX) * scale), (int) ((path.get(i)[1] - minZ) * scale));
        }
        for (int i = 0; i < world.bases.size(); i++) {
            double[] b = world.bases.get(i);
            g.setColor(sim.basesFound.contains(i) ? new Color(0, 170, 0) : Color.RED);
            g.fillOval((int) ((b[0] - minX) * scale) - 4, (int) ((b[1] - minZ) * scale) - 4, 8, 8);
        }
        for (var lead : sim.leads) {
            g.setColor(lead.kind() == TrailExplorer.LeadKind.FORK ? Color.MAGENTA : Color.BLACK);
            g.drawRect((int) ((lead.x() - minX) * scale) - 5, (int) ((lead.z() - minZ) * scale) - 5, 10, 10);
        }
        g.setColor(Color.BLACK);
        g.drawString(sim.summary().replaceAll(", states.*", ""), 5, 15);
        g.fillOval((int) ((sx - minX) * scale) - 5, (int) ((sz - minZ) * scale) - 5, 10, 10);
        ImageIO.write(image, "png", new File(out));
    }
}
