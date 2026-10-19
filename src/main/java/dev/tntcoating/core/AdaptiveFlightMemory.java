package dev.tntcoating.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * Adaptive flight speeds and correction guidance remembered independently per server identity.
 * Unknown axes retain sentinel guidance; total displacement is a separate shared limit.
 * Profiles expire 180 days after their last learned update and least recently used profiles are bounded.
 */
public final class AdaptiveFlightMemory {
    public static final int MAX_SERVER_PROFILES = 128;
    public static final double MAX_SPEED = 64;
    public static final double MAX_VERTICAL_ACCELERATION = 2;
    public static final long MAX_AGE_MILLIS = Duration.ofDays(180).toMillis();

    private static final int MAGIC = 0x4341464d; // CAFM
    private static final int LEGACY_VERSION = 1;
    private static final int GUIDANCE_VERSION = 2;
    private static final int VERSION = 3;
    private static final int MAX_IDENTITY_LENGTH = 1_024;
    private static final long HEADER_AND_CHECKSUM_BYTES = 20;
    private static final long MAX_FILE_BYTES = HEADER_AND_CHECKSUM_BYTES
        + MAX_SERVER_PROFILES * (2L + 3L * MAX_IDENTITY_LENGTH + 8 + 10 * 8);

    /** Speeds are blocks per client tick; vertical acceleration is blocks per tick squared. */
    public record Limits(double horizontal, double ascent, double descent, double verticalAcceleration,
                         double total) {
        /** Older profiles and callers have no evidence of a combined displacement limit. */
        public Limits(double horizontal, double ascent, double descent, double verticalAcceleration) {
            this(horizontal, ascent, descent, verticalAcceleration, MAX_SPEED);
        }

        /** A sentinel only: callers still apply their configured limits and conservative initial learning. */
        public static final Limits UNKNOWN = new Limits(MAX_SPEED, MAX_SPEED, MAX_SPEED, MAX_VERTICAL_ACCELERATION);

        public Limits {
            validateLimit(horizontal, MAX_SPEED);
            validateLimit(ascent, MAX_SPEED);
            validateLimit(descent, MAX_SPEED);
            validateLimit(verticalAcceleration, MAX_VERTICAL_ACCELERATION);
            validateLimit(total, MAX_SPEED);
        }

        private static void validateLimit(double limit, double maximum) {
            if (!Double.isFinite(limit) || limit <= 0 || limit > maximum)
                throw new IllegalArgumentException("Learned flight limits must be finite, positive and within engine bounds.");
        }
    }

    /**
     * Cleanly demonstrated speeds start the next session. The legacy-named {@code ceilings} values
     * remember correction thresholds as soft guidance; demonstrated speeds may safely exceed them.
     */
    public record ProfileLimits(Limits limits, Limits ceilings) {
        public static final ProfileLimits UNKNOWN = new ProfileLimits(Limits.UNKNOWN, Limits.UNKNOWN);

        public ProfileLimits {
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(ceilings, "ceilings");
        }
    }

    private record Profile(String server, ProfileLimits limits, long learnedAt) {}

    private final Clock clock;
    private final LinkedHashMap<String, Profile> profiles = new LinkedHashMap<>(16, .75f, true);

    public AdaptiveFlightMemory() { this(Clock.systemUTC()); }

    AdaptiveFlightMemory(Clock clock) { this.clock = Objects.requireNonNull(clock, "clock"); }

    /** Identity normalization belongs to the caller; ports, dimensions and single-player worlds stay distinct. */
    public Limits get(String server) { return getProfile(server).limits(); }

    public synchronized ProfileLimits getProfile(String server) {
        String key = identity(server);
        pruneExpired();
        Profile profile = profiles.get(key);
        return profile == null ? ProfileLimits.UNKNOWN : profile.limits();
    }

    /** Store strong limits; callers with distinct clean hints should use {@link #putProfile}. */
    public void put(String server, Limits limits) { putProfile(server, new ProfileLimits(limits, limits)); }

    /** Replace only this server's warm start and correction guidance. */
    public synchronized void putProfile(String server, ProfileLimits limits) {
        String key = identity(server);
        Objects.requireNonNull(limits, "limits");
        pruneExpired();
        profiles.put(key, new Profile(key, limits, clock.millis()));
        while (profiles.size() > MAX_SERVER_PROFILES) profiles.remove(profiles.keySet().iterator().next());
    }

    public synchronized void clear(String server) { profiles.remove(identity(server)); }

    public synchronized int size() {
        pruneExpired();
        return profiles.size();
    }

    /** Missing, unreadable, obsolete or damaged files simply start a fresh learning session. */
    public static AdaptiveFlightMemory load(Path path) { return load(path, Clock.systemUTC()); }

    static AdaptiveFlightMemory load(Path path, Clock clock) {
        Objects.requireNonNull(path, "path");
        var memory = new AdaptiveFlightMemory(clock);
        try {
            long length = Files.size(path);
            if (length < HEADER_AND_CHECKSUM_BYTES || length > MAX_FILE_BYTES) return memory;
            CRC32 crc = new CRC32();
            try (var in = new DataInputStream(new CheckedInputStream(
                new BufferedInputStream(Files.newInputStream(path)), crc))) {
                if (in.readInt() != MAGIC) return memory;
                int version = in.readInt();
                if (version != VERSION && version != GUIDANCE_VERSION && version != LEGACY_VERSION) return memory;
                int count = in.readInt();
                if (count < 0 || count > MAX_SERVER_PROFILES) return memory;
                var restored = new LinkedHashMap<String, Profile>();
                long now = clock.millis();
                for (int i = 0; i < count; i++) {
                    String server = in.readUTF();
                    if (!server.equals(identity(server))) return memory;
                    long learnedAt = in.readLong();
                    if (learnedAt < 0) return memory;
                    var starting = readLimits(in, version);
                    var limits = new ProfileLimits(starting, version == LEGACY_VERSION ? starting : readLimits(in, version));
                    // Clock corrections cannot make an otherwise valid profile immortal.
                    var profile = new Profile(server, limits, Math.min(learnedAt, now));
                    if (restored.put(server, profile) != null) return memory;
                }
                long actualChecksum = crc.getValue();
                if (in.readLong() != actualChecksum || in.read() != -1) return memory;
                memory.profiles.putAll(restored);
                memory.pruneExpired();
                return memory;
            }
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            // Limits are only a reconnect hint; broken history must never prevent enabling flight.
            return new AdaptiveFlightMemory(clock);
        }
    }

    /** Atomically replace a complete versioned snapshot; a failed write leaves the old file intact. */
    public void save(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        List<Profile> snapshot = snapshot();
        Path target = path.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || target.getFileName() == null) throw new IOException("Invalid flight-memory path");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".comet-flight-", ".tmp");
        try {
            CRC32 crc = new CRC32();
            try (var out = new DataOutputStream(new CheckedOutputStream(
                new BufferedOutputStream(Files.newOutputStream(temporary)), crc))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(snapshot.size());
                for (Profile profile : snapshot) {
                    out.writeUTF(profile.server());
                    out.writeLong(profile.learnedAt());
                    writeLimits(out, profile.limits().limits());
                    writeLimits(out, profile.limits().ceilings());
                }
                out.writeLong(crc.getValue());
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private synchronized List<Profile> snapshot() {
        pruneExpired();
        return new ArrayList<>(profiles.values());
    }

    private static Limits readLimits(DataInputStream in, int version) throws IOException {
        return new Limits(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
            version >= VERSION ? in.readDouble() : MAX_SPEED);
    }

    private static void writeLimits(DataOutputStream out, Limits limits) throws IOException {
        out.writeDouble(limits.horizontal());
        out.writeDouble(limits.ascent());
        out.writeDouble(limits.descent());
        out.writeDouble(limits.verticalAcceleration());
        out.writeDouble(limits.total());
    }

    private void pruneExpired() {
        long now = clock.millis();
        profiles.values().removeIf(profile -> now >= profile.learnedAt()
            && now - profile.learnedAt() >= MAX_AGE_MILLIS);
    }

    private static String identity(String server) {
        Objects.requireNonNull(server, "server");
        String key = server.trim();
        if (key.isEmpty() || key.length() > MAX_IDENTITY_LENGTH || key.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("A nonempty bounded server identity is required.");
        return key;
    }
}
