package com.internal.tools.pubsubgui.automation.model;

/** Terminal status of a single scenario check. */
public enum CheckStatus {
    /** Invariant held over every sampled document. */
    PASS,
    /** At least one sampled document violated the invariant. */
    FAIL,
    /** Nothing to check (no matching documents sampled, or missing productId for a scoped check). */
    SKIP,
    /** Scenario is registered but not verifiable read-only (needs active injection). */
    NA,
    /** The check itself blew up (e.g. connection/VPN failure). */
    ERROR
}
