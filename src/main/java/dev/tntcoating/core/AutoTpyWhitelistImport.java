package dev.tntcoating.core;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Pure, atomic staging of username CSVs; IO and client-thread settings updates belong to the caller. */
public final class AutoTpyWhitelistImport {
    public static final int MAX_BYTES = 1 << 20;
    public record Result(List<String> merged, int added, int duplicates, int invalid, int blocked) {}
    public record Ticket(long revision, String session, List<String> original) {
        public Ticket { original=List.copyOf(original); }
        public boolean canApply(long currentRevision,String currentSession,List<String> currentNames) {
            return revision==currentRevision && session.equals(currentSession) && original.equals(currentNames);
        }
    }
    private AutoTpyWhitelistImport() {}
    public static Result parse(List<String> existing, Set<String> blocked, List<byte[]> files) {
        var names = new LinkedHashMap<String,String>();
        ChatClan.members(existing).forEach(n -> names.put(AutoTpyPolicy.key(n),n));
        var denied = AutoTpyPolicy.keys(blocked);
        int added=0, duplicates=0, invalid=0, overlaps=0;
        var cells = new ArrayList<String>();
        for (byte[] file : files) {
            if (file.length > MAX_BYTES) throw new IllegalArgumentException("Each whitelist file must be at most 1 MiB.");
            String text = ChatClan.decode(file);
            validateQuotes(text);
            cells.addAll(ChatClan.csvEntries(text));
        }
        for (String raw : cells) {
            String name=raw.strip();
            if (name.isEmpty()) continue;
            if (!ChatMessages.validName(name)) { invalid++; continue; }
            String key=AutoTpyPolicy.key(name);
            if (names.putIfAbsent(key,name)!=null) duplicates++;
            else { added++; if (denied.contains(key)) overlaps++; }
        }
        return new Result(List.copyOf(names.values()),added,duplicates,invalid,overlaps);
    }
    private static void validateQuotes(String text) {
        boolean quoted=false;
        for (int i=0; i<text.length(); i++) {
            char c=text.charAt(i);
            if (c=='"') {
                if (quoted && i+1<text.length() && text.charAt(i+1)=='"') i++;
                else quoted=!quoted;
            } else if (quoted && (c=='\r'||c=='\n')) throw new IllegalArgumentException("Multiline CSV cells are not supported.");
        }
        if (quoted) throw new IllegalArgumentException("CSV has an unmatched quote.");
    }
}
