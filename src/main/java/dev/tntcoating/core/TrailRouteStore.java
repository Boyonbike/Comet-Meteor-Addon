package dev.tntcoating.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * Compact, checked, atomic route files. The caller supplies a path scoped to its world/dimension. Version 2 stores
 * each chunk's flight directions; version 1 files load with every direction, so old history keeps blocking as before.
 */
public final class TrailRouteStore {
    private static final int MAGIC = 0x4354524d; // CTRM
    private static final int LEGACY_VERSION = 1;
    private static final int VERSION = 2;
    private static final long HEADER_AND_CHECKSUM_BYTES = 20;

    private TrailRouteStore() {}

    /** A missing file means a new route; unreadable or damaged existing files must be reported. */
    public static TrailRouteMemory load(Path path) throws IOException {
        if (Files.notExists(path)) return new TrailRouteMemory();
        long length = Files.size(path);
        if (length < HEADER_AND_CHECKSUM_BYTES || length > HEADER_AND_CHECKSUM_BYTES + 12L * TrailRouteMemory.MAX_ENTRIES)
            throw new IOException("Invalid trail-route file size");
        CRC32 crc = new CRC32();
        try (var in = new DataInputStream(new CheckedInputStream(new BufferedInputStream(Files.newInputStream(path)), crc))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid trail-route file header");
            int version = in.readInt();
            if (version != VERSION && version != LEGACY_VERSION) throw new IOException("Unsupported trail-route file version");
            int cellBytes = version == VERSION ? 12 : 8;
            int count = in.readInt();
            if (count < 0 || count > TrailRouteMemory.MAX_ENTRIES || length != HEADER_AND_CHECKSUM_BYTES + (long) cellBytes * count)
                throw new IOException("Invalid trail-route cell count");
            long[] keys = new long[count];
            int[] directions = version == VERSION ? new int[count] : null;
            for (int i = 0; i < count; i++) {
                keys[i] = in.readLong();
                if (directions != null) directions[i] = in.readInt();
            }
            long actualChecksum = crc.getValue();
            if (in.readLong() != actualChecksum || in.read() != -1)
                throw new IOException("Damaged trail-route file checksum");
            try {
                return directions == null ? TrailRouteMemory.restore(keys) : TrailRouteMemory.restore(keys, directions);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid trail-route cells", e);
            }
        } catch (EOFException e) {
            throw new IOException("Truncated trail-route file", e);
        }
    }

    /** Atomic replacement preserves the previous history if writing or renaming the new file fails. */
    public static void save(Path path, TrailRouteMemory memory) throws IOException {
        save(path, memory.snapshot());
    }

    /** Save an immutable capture on a worker without touching the live flight memory. */
    public static void save(Path path, TrailRouteMemory.Snapshot snapshot) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        Path directory = target.getParent();
        if (directory == null || target.getFileName() == null) throw new IOException("Invalid trail-route path");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".comet-route-", ".tmp");
        try {
            long[] keys = snapshot.keys();
            int[] directions = snapshot.directions();
            CRC32 crc = new CRC32();
            try (var out = new DataOutputStream(new CheckedOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)), crc))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(keys.length);
                for (int i = 0; i < keys.length; i++) {
                    out.writeLong(keys[i]);
                    out.writeInt(directions[i]);
                }
                long checksum = crc.getValue();
                out.writeLong(checksum);
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
