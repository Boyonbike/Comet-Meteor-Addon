package dev.tntcoating.core;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Temporarily overrides a setting while retaining edits made by its owner. */
public final class SettingOverride<T> {
    private final Supplier<T> read;
    private final Consumer<T> write;
    private final T forcedValue;
    private T savedValue;
    private boolean acquired;

    public SettingOverride(Supplier<T> read, Consumer<T> write, T forcedValue) {
        this.read = read;
        this.write = write;
        this.forcedValue = forcedValue;
    }

    /** Acquires or refreshes the override, remembering any external edit before reasserting it. */
    public void acquire() {
        T current = read.get();
        if (!acquired || !Objects.equals(current, forcedValue)) savedValue = current;
        acquired = true;
        write.accept(forcedValue);
    }

    /** Restores the saved value only while this override still owns the setting's current value. */
    public void release() {
        if (!acquired) return;
        if (Objects.equals(read.get(), forcedValue)) write.accept(savedValue);
        acquired = false;
        savedValue = null;
    }
}
