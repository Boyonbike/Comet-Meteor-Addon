package dev.tntcoating.core;

import com.google.common.hash.Hashing;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SipHash-2-4 under a secret key. Seeding textures from a block position hashed with it varies them as much as vanilla
 * does, but nobody without the key can tell from them where the block is.
 */
public final class KeyedHash {
    private static final Logger LOG = LoggerFactory.getLogger("Comet");
    private final long k0, k1;

    public KeyedHash(long k0, long k1) {
        this.k0 = k0;
        this.k1 = k1;
    }

    /** The key saved at {@code file}, or a new one saved there. A key that cannot be saved still works until the game closes. */
    public static KeyedHash loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                byte[] bytes = HexFormat.of().parseHex(Files.readString(file).trim());
                if (bytes.length == 16) return fromBytes(bytes);
            }
        } catch (Exception e) {
            // Unreadable or malformed; replaced below.
        }
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, HexFormat.of().formatHex(bytes));
        } catch (Exception e) {
            LOG.warn("Couldn't save the Anti Coords key to {}; textures will vary differently next session", file, e);
        }
        return fromBytes(bytes);
    }

    private static KeyedHash fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return new KeyedHash(buffer.getLong(), buffer.getLong());
    }

    /** A separate key for one server and dimension, so textures there can't be matched against anywhere else. */
    public KeyedHash derive(String context) {
        var function = Hashing.sipHash24(k0, k1);
        return new KeyedHash(function.hashString("0\0" + context, StandardCharsets.UTF_8).asLong(),
            function.hashString("1\0" + context, StandardCharsets.UTF_8).asLong());
    }

    /** SipHash-2-4 of one 8-byte message, without the allocations Guava's hasher makes for every block meshed. */
    public long hash(long message) {
        long v0 = k0 ^ 0x736f6d6570736575L, v1 = k1 ^ 0x646f72616e646f6dL;
        long v2 = k0 ^ 0x6c7967656e657261L, v3 = k1 ^ 0x7465646279746573L ^ message;
        long lengthBlock = 8L << 56;
        // Two rounds compress the message, two the length block, and four finalise.
        for (int round = 0; round < 8; round++) {
            if (round == 2) {
                v0 ^= message;
                v3 ^= lengthBlock;
            } else if (round == 4) {
                v0 ^= lengthBlock;
                v2 ^= 0xff;
            }
            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
            v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
            v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
        }
        return v0 ^ v1 ^ v2 ^ v3;
    }
}
