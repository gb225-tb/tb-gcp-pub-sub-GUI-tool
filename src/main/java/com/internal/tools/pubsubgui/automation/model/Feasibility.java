package com.internal.tools.pubsubgui.automation.model;

/**
 * Whether a scenario can be verified read-only against live data, or requires controlled input
 * (empty DB / synthetic payload / active injection) that this read-only runner intentionally skips.
 */
public enum Feasibility {
    /** Verifiable by asserting invariants on real, already-ingested documents. */
    READONLY,
    /** Requires publishing a controlled message / an empty DB — out of scope for the read-only runner. */
    NOT_APPLICABLE
}
