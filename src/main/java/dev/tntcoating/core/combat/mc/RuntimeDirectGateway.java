/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.DirectContext;
import dev.tntcoating.core.combat.DirectDispatch;
import dev.tntcoating.core.combat.DirectGateway;
import dev.tntcoating.core.combat.DirectSettings;
import java.util.Objects;

/**
 * The only bridge from the pure {@link dev.tntcoating.core.combat.CombatEngine} to
 * {@link CombatRuntime#dispatch}. The engine reserves risk and builds the intent; this gateway attaches the
 * live client facts the runtime revalidates and forwards the single dispatch call. It has no packet API and
 * cannot bypass {@code CombatRuntime}.
 */
public final class RuntimeDirectGateway implements DirectGateway {
    private final CombatRuntime runtime;
    private final CombatClient client;
    private volatile DirectSettings settings;

    public RuntimeDirectGateway(CombatRuntime runtime, CombatClient client, DirectSettings settings) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.client = Objects.requireNonNull(client, "client");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public void reconfigure(DirectSettings next) {
        settings = Objects.requireNonNull(next, "next");
    }

    @Override
    public DirectGateway.DirectSendResult send(DirectDispatch dispatch) {
        if (dispatch == null) return DirectGateway.DirectSendResult.FAILED;
        DirectContext context = dispatch.context();
        CombatRuntime.PlanContext planContext = new CombatRuntime.PlanContext(
            dispatch.safetyState(),
            context.exposure(),
            context.estimate(),
            context.targetHealth(),
            settings.rotationsOn(),
            context.hotbarSlot(),
            context.requiredItemId(),
            client.selectedSlot(),
            client.offhandItem(),
            context.targetOverride(),
            context.faceplace(),
            context.defensive());
        runtime.plan(dispatch.intent(), planContext);
        CombatRuntime.DispatchResult result = runtime.dispatch(dispatch.intent(), dispatch.ticket());
        if (result == null) return DirectGateway.DirectSendResult.FAILED;
        return switch (result.status()) {
            case SENT -> DirectGateway.DirectSendResult.SENT;
            case BLOCKED -> DirectGateway.DirectSendResult.BLOCKED;
            case FAILED -> result.reason() == CombatRuntime.Reason.SUPPORTING_PACKET_FAILED
                ? DirectGateway.DirectSendResult.SENT
                : DirectGateway.DirectSendResult.FAILED;
        };
    }
}
