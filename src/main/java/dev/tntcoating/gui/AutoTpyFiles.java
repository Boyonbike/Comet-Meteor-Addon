package dev.tntcoating.gui;

import dev.tntcoating.core.AutoTpyWhitelistImport;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;

/** Blocking IO helpers used only on the whitelist import worker, never on the game thread. */
final class AutoTpyFiles {
    private AutoTpyFiles() {}
    static List<Path> choose() {
        try (MemoryStack stack=MemoryStack.stackPush()) {
            PointerBuffer filters=stack.pointers(stack.UTF8("*.csv"),stack.UTF8("*.txt"));
            String path=TinyFileDialogs.tinyfd_openFileDialog("Import Auto TPY whitelist",null,filters,"Username CSV files",false);
            return path==null?List.of():List.of(Path.of(path));
        }
    }
    static List<byte[]> read(List<Path> paths) throws IOException {
        var result=new ArrayList<byte[]>();
        for(Path path:paths) {
            String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
            if(!name.endsWith(".csv")&&!name.endsWith(".txt")) throw new IOException("Select a .csv or .txt username list.");
            // Bound the read itself, including files that grow after a size check.
            try(var input=Files.newInputStream(path)) {
                byte[] data=input.readNBytes(AutoTpyWhitelistImport.MAX_BYTES+1);
                if(data.length>AutoTpyWhitelistImport.MAX_BYTES) throw new IOException("Each whitelist file must be at most 1 MiB.");
                result.add(data);
            }
        }
        return List.copyOf(result);
    }
}
