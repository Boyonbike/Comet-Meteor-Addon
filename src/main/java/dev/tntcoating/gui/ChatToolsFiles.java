package dev.tntcoating.gui;

import dev.tntcoating.core.ChatClan;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Chat Tools' native file picker and clan-list reader. Kept out of the screen so the window only
 * deals with layout and the picker can wait on its own thread.
 */
final class ChatToolsFiles {
    private static final long MAX_IMPORT_BYTES = 1 << 20;

    record Imported(List<String> names, int invalid) {}

    /** A picker is modal, so one window asks for at most one file at a time. */
    private boolean choosing;

    boolean choosing() {
        return choosing;
    }

    /**
     * Meteor's own Import buttons use this native picker too. It waits on its own thread, because
     * blocking the game thread while the player browses would also stop keep-alive replies.
     */
    void choose(Minecraft client, Consumer<List<Path>> onFiles, Consumer<String> onNotice) {
        if (choosing) return;
        choosing = true;
        Thread picker = new Thread(() -> {
            Path chosen = null;
            boolean failed = false;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.pointers(stack.UTF8("*.csv"), stack.UTF8("*.txt"));
                String path = TinyFileDialogs.tinyfd_openFileDialog("Import clan members", null, filters, "CSV files", false);
                if (path != null) chosen = Path.of(path);
            } catch (RuntimeException | LinkageError e) {
                failed = true;
            }
            Path file = chosen;
            boolean error = failed;
            client.execute(() -> {
                choosing = false;
                if (error) onNotice.accept("Could not open a file picker. Drop the .csv file on this window instead.");
                else if (file != null) onFiles.accept(List.of(file));
            });
        }, "Comet clan import");
        picker.setDaemon(true);
        picker.start();
    }

    /**
     * Reads CSV or TXT clan lists. Returns {@code null} after reporting the first problem, so a
     * partly read batch is never added.
     */
    static Imported read(List<Path> files, Consumer<String> onNotice) {
        List<String> names = List.of();
        int invalid = 0;
        for (Path file : files) {
            String name = String.valueOf(file.getFileName());
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".csv") && !lower.endsWith(".txt")) {
                onNotice.accept(name + " is not a .csv file.");
                return null;
            }
            try {
                if (Files.size(file) > MAX_IMPORT_BYTES) {
                    onNotice.accept(name + " is too large for a clan list.");
                    return null;
                }
                var result = ChatClan.csv(ChatClan.decode(Files.readAllBytes(file)));
                names = ChatClan.add(names, result.names());
                invalid += result.invalid();
            } catch (IOException | RuntimeException e) {
                onNotice.accept("Could not read " + name + ".");
                return null;
            }
        }
        return new Imported(names, invalid);
    }
}
