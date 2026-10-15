package dev.tntcoating.core;

/** Pure tick state machine. The caller supplies physical clearance and server confirmation. */
public final class JumpController {
    public enum State { NORMAL, WAITING_FOR_CLEARANCE, STABILISING, CORRECTION_COOLDOWN }
    public enum Action { COAT, PAUSE, ATTEMPT_UNDERFOOT, RESUMED }

    private State state = State.NORMAL;
    private int airborneTicks;
    private int stableTicks;
    private int cooldown;

    public State state() { return state; }

    public void reset() {
        state = State.NORMAL;
        airborneTicks = stableTicks = cooldown = 0;
    }

    public void corrected(int ticks) {
        state = State.CORRECTION_COOLDOWN;
        cooldown = ticks;
        stableTicks = 0;
    }

    public Action tick(boolean jumpStarted, boolean rising, boolean grounded,
                       boolean stable, boolean clearance, boolean resolved,
                       boolean requireGround, int minimumStableTicks) {
        if (state == State.CORRECTION_COOLDOWN) {
            if (cooldown > 0) { cooldown--; return Action.PAUSE; }
            state = State.STABILISING;
        }

        if (state == State.NORMAL) {
            if (!jumpStarted) return Action.COAT;
            state = State.WAITING_FOR_CLEARANCE;
            airborneTicks = stableTicks = 0;
        }

        if (state == State.WAITING_FOR_CLEARANCE) {
            airborneTicks++;
            // The takeoff cell is inside the player at the start of a jump.
            // Wait until the entire body is above it, and consume the opportunity once.
            if (rising && clearance) {
                state = State.STABILISING;
                return Action.ATTEMPT_UNDERFOOT;
            }
            if (!rising || grounded || airborneTicks >= 10) state = State.STABILISING;
            return Action.PAUSE;
        }

        if (stable && resolved && (!requireGround || grounded)) stableTicks++;
        else stableTicks = 0;
        if (stableTicks < minimumStableTicks) return Action.PAUSE;
        state = State.NORMAL;
        stableTicks = 0;
        return Action.RESUMED;
    }
}
