package dev.tntcoating.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable save identity without writing a private filesystem path into recovery markers. */
public final class BoatTripIdentity {
    private BoatTripIdentity() {}
    public static String serverKey(String address, Path singleplayerSave) {
        if (address != null) return address;
        if (singleplayerSave == null) return null;
        try {
            byte[] path = singleplayerSave.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8);
            return "local:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
