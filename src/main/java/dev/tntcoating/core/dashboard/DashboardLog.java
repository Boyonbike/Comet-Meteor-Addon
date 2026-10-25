/*
 * Comet highway dashboard server logging seam.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core.dashboard;

/** Minimal logger used by the dashboard server so its package does not depend on Meteor. */
public interface DashboardLog {
    DashboardLog NONE = new DashboardLog() {
        @Override public void info(String message) {}

        @Override public void warn(String message, Throwable error) {}
    };

    void info(String message);

    void warn(String message, Throwable error);
}
