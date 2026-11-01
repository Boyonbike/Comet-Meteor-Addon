/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Bound;
import dev.tntcoating.core.combat.CombatIncidents;
import dev.tntcoating.core.combat.CombatDebugSink;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.Debt;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.SuspensionCause;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;

/**
 * Minecraft-facing half of the opt-in tester debug log. It keeps the P0.2 observation recorder in
 * <em>memory</em> mode (no file), watches the risk budget and dispatch path for incidents, and writes every
 * incident as a label line into the same rolling five-minute buffer.
 *
 * <p>Diagnostics only: this class never sends, places, attacks, rotates or mutates risk. It subscribes to no
 * event bus of its own — {@link CrystalCombatController} hands it applied observations after the runtime and
 * budget have already seen them, and {@link CombatRuntime#setDispatchObserver} hands it dispatch results.</p>
 */
public final class CombatDebugHost {
    private final Minecraft mc;
    private final CombatRuntime runtime;
    private final CombatRiskBudget risk;
    private final CombatClient client;
    private final BooleanSupplier moduleEnabled;
    private final CombatRecorderHost recorder;
    private final CombatDebugSink sink;
    private final CombatIncidents.DebtWatch debtWatch = new CombatIncidents.DebtWatch();
    private final EnumSet<SuspensionCause> lastSuspensions = EnumSet.noneOf(SuspensionCause.class);

    private EntityKey self;
    private double lastEffectiveHealth = Double.NaN;
    private Bound lastBound;
    private boolean stopped;

    private CombatDebugHost(Minecraft mc, CombatRuntime runtime, CombatRiskBudget risk,
                           CombatClient client, BooleanSupplier moduleEnabled,
                           CombatRecorderHost recorder, CombatDebugSink sink) {
        this.mc = mc;
        this.runtime = runtime;
        this.risk = risk;
        this.client = client;
        this.moduleEnabled = moduleEnabled;
        this.recorder = recorder;
        this.sink = sink;
        this.self = client.selfKey();
    }

    /**
     * Starts the in-memory recorder and installs the read-only observation/dispatch taps on an existing
     * runtime stack. Returns {@code null} when there is no stack or the recorder could not start; the caller
     * treats that as "debug log not running" and never throws from a module setting callback.
     */
    public static CombatDebugHost start(Minecraft mc, CrystalCombatController.Stack stack,
                                        BooleanSupplier moduleEnabled) {
        if (mc == null || stack == null) return null;
        try {
            CombatDebugSink sink = new CombatDebugSink();
            CombatRecorderHost recorder = CombatRecorderHost.startMemory(mc, sink);
            CombatDebugHost host = new CombatDebugHost(mc, stack.runtime(), stack.risk(), stack.client(),
                moduleEnabled, recorder, sink);
            stack.runtime().setDispatchObserver(host::onDispatch);
            recorder.label("debug-log-start" + motionPredictionHeader(stack) + hurtSyncHeader(stack)
                + supportHeader(stack) + surroundGapHeader(stack) + dangerousCrystalHeader(stack)
                + antiPistonHeader(stack));
            return host;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Phase 4.2: the tactic state is part of the debug-log header so a tester export is self-describing. */
    static String motionPredictionHeader(DirectSettings settings) {
        if (settings == null) return "";
        return " motion-prediction=" + (settings.motionPrediction() ? "on" : "off")
            + " motion-prediction-ticks=" + settings.motionPredictionTicks();
    }

    private static String motionPredictionHeader(CrystalCombatController.Stack stack) {
        if (stack.engineHost() == null) return "";
        return motionPredictionHeader(stack.engineHost().engine().settings());
    }

    /** Phase 4.3: the hurt-sync toggle is part of the debug-log header so a tester export is self-describing. */
    static String hurtSyncHeader(DirectSettings settings) {
        if (settings == null) return "";
        return " hurt-sync=" + (settings.hurtSync() ? "on" : "off");
    }

    private static String hurtSyncHeader(CrystalCombatController.Stack stack) {
        if (stack.engineHost() == null) return "";
        return hurtSyncHeader(stack.engineHost().engine().settings());
    }

    /** Phase 4.4: the support-block toggle is part of the debug-log header so a tester export is self-describing. */
    static String supportHeader(DirectSettings settings) {
        if (settings == null) return "";
        return " support=" + (settings.supportEnabled() ? "on" : "off");
    }

    private static String supportHeader(CrystalCombatController.Stack stack) {
        if (stack.engineHost() == null) return "";
        return supportHeader(stack.engineHost().engine().settings());
    }

    /** Phase 4.5: the gap preference toggle is part of the debug-log header so a tester export is self-describing. */
    static String surroundGapHeader(DirectSettings settings) {
        if (settings == null) return "";
        return " surround-gap=" + (settings.surroundGap() ? "on" : "off");
    }

    private static String surroundGapHeader(CrystalCombatController.Stack stack) {
        if (stack.engineHost() == null) return "";
        return surroundGapHeader(stack.engineHost().engine().settings());
    }

    /** Phase 4.6: the dangerous-crystal defence gate is part of the debug-log header. */
    static String dangerousCrystalHeader(DirectSettings settings) {
        if (settings == null) return "";
        return " dangerous-crystal-break=" + (settings.dangerousCrystalBreak() ? "on" : "off");
    }

    private static String dangerousCrystalHeader(CrystalCombatController.Stack stack) {
        if (stack.engineHost() == null) return "";
        return dangerousCrystalHeader(stack.engineHost().engine().settings());
    }

    /** Phase 4.6: the anti-piston defence gate is part of the debug-log header. */
    static String antiPistonHeader(DirectSettings settings) {
        if (settings == null) return "";
        return " anti-piston=" + (settings.antiPiston() ? "on" : "off");
    }

    private static String antiPistonHeader(CrystalCombatController.Stack stack) {
        if (stack.engineHost() == null) return "";
        return antiPistonHeader(stack.engineHost().engine().settings());
    }

    /** Disconnects the taps and flushes the label. The buffer is retained for a later export. */
    public void stop() {
        if (stopped) return;
        stopped = true;
        runtime.setDispatchObserver(null);
        recorder.label("debug-log-stop");
        recorder.stop();
    }

    public boolean running() {
        return !stopped;
    }

    /** The rolling buffer contents, oldest first; empty once rendered and evicted. */
    public List<String> lines() {
        return sink.lines();
    }

    public long droppedLines() {
        return sink.droppedRecords();
    }

    /** Writes one free-form debug record; tester diagnostics only, never a send or a decision. */
    public void debug(String text) {
        if (stopped || text == null) return;
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.isEmpty()) return;
        if (flat.length() > 320) flat = flat.substring(0, 320);
        recorder.label("debug: " + flat);
    }

    /** Writes a tester-visible incident marker; used by the hotkey and the automatic detectors. */
    public void marker(String text) {
        if (stopped || text == null || text.isBlank()) return;
        recorder.label(CombatIncidents.ensurePrefix(text));
    }

    /**
     * Called by {@link CrystalCombatController#tick(int)} after the runtime applied this tick's observations.
     * Read-only: the only side effect is appending incident labels to the in-memory sink.
     */
    public void apply(Observation observation) {
        if (stopped || observation == null) return;
        EntityKey currentSelf = client.selfKey();
        if (!Objects.equals(currentSelf, self)) {
            self = currentSelf;
            lastEffectiveHealth = Double.NaN;
            lastBound = null;
            debtWatch.reset();
        }
        if (observation.kind() == ObservationKind.DEATH && moduleEnabled.getAsBoolean()
            && (observation.entity() == null || observation.entity().equals(self))) {
            marker(CombatIncidents.selfDeathLabel(true));
            return;
        }
        Double effective = selfEffectiveHealth(observation);
        if (effective == null) return;
        if (Double.isFinite(lastEffectiveHealth)) {
            double damage = lastEffectiveHealth - effective;
            if (lastBound != null && CombatIncidents.selfDamageExceedsBound(lastEffectiveHealth, effective,
                lastBound)) {
                marker(CombatIncidents.selfDamageLabel(damage, lastBound));
            }
        }
        lastEffectiveHealth = effective;
        // Capture the predicted bound for the snapshot just applied; it becomes the "preceding coherent
        // snapshot" bound for the next health observation.
        lastBound = self == null ? null : risk.current(self);
    }

    /**
     * Called once per applied open-session tick: records suspension changes with their causes and the
     * "debt stuck over thirty seconds" incident exactly once per continuous episode.
     */
    public void afterTick(int serverTick) {
        if (stopped) return;
        EntityKey currentSelf = client.selfKey();
        if (!Objects.equals(currentSelf, self)) {
            self = currentSelf;
            lastEffectiveHealth = Double.NaN;
            lastBound = null;
            debtWatch.reset();
        }
        Set<SuspensionCause> current = risk.suspensionCauses();
        if (!current.equals(lastSuspensions)) {
            marker(CombatIncidents.suspensionLabel(lastSuspensions, current));
            lastSuspensions.clear();
            lastSuspensions.addAll(current);
        }
        if (self == null) return;
        Debt debt = risk.debt(self);
        long now = System.nanoTime();
        if (debtWatch.update(debt.count() > 0, now)) {
            marker(CombatIncidents.debtStuckLabel(debt, debtWatch.elapsedNanos(now)));
        }
    }

    private void onDispatch(CombatRuntime.DispatchResult result) {
        if (stopped) return;
        if (CombatDispatchInvariants.isInvariantFailure(result)) {
            marker(CombatDispatchInvariants.label(result));
        }
    }

    private Double selfEffectiveHealth(Observation observation) {
        if (observation.entity() == null || !observation.entity().equals(self)) return null;
        if (observation.kind() == ObservationKind.HEALTH
            && observation.payload() instanceof ObservationPayload.Health health) {
            return effective(health.health(), health.absorption());
        }
        if (observation.kind() == ObservationKind.ENTITY_DATA
            && observation.payload() instanceof ObservationPayload.EntityData data && data.healthKnown()) {
            return effective(data.health(), data.absorptionKnown() ? data.absorption() : 0.0);
        }
        return null;
    }

    private static Double effective(double health, double absorption) {
        if (!Double.isFinite(health)) return null;
        double safeAbsorption = Double.isFinite(absorption) ? Math.max(0.0, absorption) : 0.0;
        return health + safeAbsorption;
    }
}
