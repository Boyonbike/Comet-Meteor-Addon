package dev.tntcoating.core.scaffold;

import java.util.function.BooleanSupplier;

/** One ordinary movement intent. Never owns, removes or submits a Meteor rotation queue entry. */
public final class OrdinaryRotationSlot {
    public record Intent(float yaw, float pitch, BooleanSupplier valid, Runnable callback) {}
    private Intent prepared, active;
    private boolean observed, hasFacing;
    private float yaw, pitch;

    public boolean prepare(float yaw, float pitch, BooleanSupplier valid, Runnable callback) {
        if (prepared != null || active != null || !Float.isFinite(yaw) || !Float.isFinite(pitch)
            || pitch < -90 || pitch > 90 || !valid.getAsBoolean()) return false;
        prepared = new Intent(yaw, pitch, valid, callback);
        return true;
    }
    public boolean prepared() { return prepared != null; }
    /** Call after all Meteor Pre handlers; busy includes queue entries added by late handlers and retained holds. */
    public Intent begin(boolean busy) {
        Intent intent = prepared;
        prepared = null;
        if (busy || intent == null || !intent.valid.getAsBoolean()) return null;
        active = intent; observed = false;
        return intent;
    }
    /** Only actual PacketEvent.Sent movement packets update this state; planned serverYaw is not evidence. */
    public void observeMovement(boolean hasRotation, float yaw, float pitch) {
        if (hasRotation) {
            hasFacing = Float.isFinite(yaw) && Float.isFinite(pitch);
            this.yaw = yaw; this.pitch = pitch;
        }
        if (active != null) observed = true;
    }
    public boolean orientationMatches(float yaw, float pitch) {
        if (!hasFacing) return false;
        double difference = (this.yaw - yaw) % 360;
        if (difference >= 180) difference -= 360;
        if (difference < -180) difference += 360;
        return Math.abs(difference) < .001 && Math.abs(this.pitch - pitch) < .001;
    }
    /** Adapter must restore the visual view before calling the returned callback. */
    public Runnable finish(boolean successful) {
        Intent intent = active;
        active = prepared = null;
        boolean sent = observed; observed = false;
        return successful && sent && intent != null && intent.valid.getAsBoolean()
            && orientationMatches(intent.yaw, intent.pitch) ? intent.callback : null;
    }
    public void cancel() { prepared = active = null; observed = false; }
    public void clear() { cancel(); hasFacing = false; }
}
