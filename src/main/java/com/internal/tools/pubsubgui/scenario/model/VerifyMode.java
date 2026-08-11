package com.internal.tools.pubsubgui.scenario.model;

/**
 * How the runner verifies the outcome after injection + wait:
 * <ul>
 *   <li>{@link #CATALOG_VALIDATORS} — re-use the read-only {@code AutomationEngine} validators scoped
 *       to a {@code ScenarioGroup} (and the injected productId when derivable).</li>
 *   <li>{@link #INVENTORY_PRESENCE} — a light read-only presence check of the injected ItemId in the
 *       inventory-config / inventory-runtime Mongo collections.</li>
 *   <li>{@link #NONE} — no automated verify (selectable-but-disabled scenarios).</li>
 * </ul>
 */
public enum VerifyMode {
    CATALOG_VALIDATORS,
    INVENTORY_PRESENCE,
    NONE
}
