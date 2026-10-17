package dev.tntcoating.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Places worth checking for a base, kept as a CSV file per world and dimension so they can be reviewed after an
 * unattended flight or opened in a spreadsheet. Leads near an earlier one of the same kind are the same place.
 */
public final class TrailLeadLog {
    public static final String HEADER = "time,kind,x,z,heading,trail_blocks";
    /** Within this distance of an earlier lead of the same kind, a lead is a repeat sighting of it. */
    public static final double SAME_PLACE = 256;

    public record Entry(long epochMillis, String kind, int x, int z, int heading, int trailBlocks) {
        public Entry {
            if (kind == null || kind.isBlank() || kind.contains(",") || kind.contains("\n"))
                throw new IllegalArgumentException("lead kind must be a plain word");
        }

        String line() {
            return String.format(Locale.ROOT, "%d,%s,%d,%d,%d,%d", epochMillis, kind, x, z, heading, trailBlocks);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** A missing file is an empty log. Unreadable lines, such as a hand-edited header, are skipped. */
    public static TrailLeadLog load(Path path) throws IOException {
        TrailLeadLog log = new TrailLeadLog();
        if (Files.notExists(path)) return log;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String[] fields = line.split(",");
            if (fields.length != 6) continue;
            try {
                log.entries.add(new Entry(Long.parseLong(fields[0].trim()), fields[1].trim(), Integer.parseInt(fields[2].trim()),
                    Integer.parseInt(fields[3].trim()), Integer.parseInt(fields[4].trim()), Integer.parseInt(fields[5].trim())));
            } catch (IllegalArgumentException ignored) {}
        }
        return log;
    }

    /** Whether an earlier lead of this kind lies within SAME_PLACE blocks. */
    public boolean seen(String kind, double x, double z) {
        for (Entry entry : entries)
            if (entry.kind().equals(kind) && Math.hypot(entry.x() - x, entry.z() - z) < SAME_PLACE) return true;
        return false;
    }

    /** Records a new lead in memory; returns false for a repeat sighting, which is not recorded again. */
    public boolean add(Entry entry) {
        if (seen(entry.kind(), entry.x(), entry.z())) return false;
        entries.add(entry);
        return true;
    }

    public int count(String kind) {
        int count = 0;
        for (Entry entry : entries) if (entry.kind().equals(kind)) count++;
        return count;
    }

    public List<Entry> entries() { return List.copyOf(entries); }

    /** Appends one line, writing the header first for a new file. Safe to call from a background writer. */
    public static void append(Path path, Entry entry) throws IOException {
        Path directory = path.toAbsolutePath().getParent();
        if (directory != null) Files.createDirectories(directory);
        boolean fresh = Files.notExists(path) || Files.size(path) == 0;
        String text = (fresh ? HEADER + "\n" : "") + entry.line() + "\n";
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
