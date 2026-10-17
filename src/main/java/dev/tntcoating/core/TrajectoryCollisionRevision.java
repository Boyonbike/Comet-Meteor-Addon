package dev.tntcoating.core;

import java.util.concurrent.atomic.AtomicLong;

/** Invalidated after world updates are applied, rather than when packets first arrive on Netty. */
public final class TrajectoryCollisionRevision {
    private static final AtomicLong REVISION = new AtomicLong();
    private TrajectoryCollisionRevision() {}
    public static long current() { return REVISION.get(); }
    public static void invalidate() { REVISION.incrementAndGet(); }
}
