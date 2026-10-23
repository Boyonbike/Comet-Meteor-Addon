package dev.tntcoating.core;

import com.google.common.hash.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class KeyedHashTest {
    @Test void hashIsSipHash24() {
        SplittableRandom random = new SplittableRandom(7);
        long[] edges = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE};
        for (int i = 0; i < 1000; i++) {
            long k0 = random.nextLong(), k1 = random.nextLong();
            KeyedHash key = new KeyedHash(k0, k1);
            var reference = Hashing.sipHash24(k0, k1);
            long message = i < edges.length ? edges[i] : random.nextLong();
            assertEquals(reference.hashLong(message).asLong(), key.hash(message));
        }
    }

    @Test void derivedKeysDependOnlyOnTheContext() {
        KeyedHash master = new KeyedHash(1, 2);
        assertEquals(master.derive("6b6t.org overworld").hash(42), master.derive("6b6t.org overworld").hash(42));
        assertNotEquals(master.derive("6b6t.org overworld").hash(42), master.derive("6b6t.org the_nether").hash(42));
        assertNotEquals(master.hash(42), master.derive("").hash(42));
    }

    @Test void keyIsCreatedOnceThenReused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("comet/anti-coords.key");
        long first = KeyedHash.loadOrCreate(file).hash(42);
        assertTrue(Files.readString(file).matches("[0-9a-f]{32}"));
        assertEquals(first, KeyedHash.loadOrCreate(file).hash(42));
    }

    @Test void unreadableKeyIsReplaced(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("anti-coords.key");
        Files.writeString(file, "not a key");
        KeyedHash key = KeyedHash.loadOrCreate(file);
        assertTrue(Files.readString(file).matches("[0-9a-f]{32}"));
        assertEquals(key.hash(42), KeyedHash.loadOrCreate(file).hash(42));
    }
}
