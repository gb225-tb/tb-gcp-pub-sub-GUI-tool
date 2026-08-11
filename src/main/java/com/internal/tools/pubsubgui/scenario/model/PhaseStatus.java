package com.internal.tools.pubsubgui.scenario.model;

/** Status of a single phase in a scenario run's live timeline. */
public enum PhaseStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    SKIPPED
}
