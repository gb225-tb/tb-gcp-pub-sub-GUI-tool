package com.internal.tools.pubsubgui.scenario.model;

/**
 * How the runner verifies the outcome after injection + wait:
 * <ul>
 *   <li>{@link #CATALOG_VALIDATORS} — re-use the read-only {@code AutomationEngine} validators scoped
 *       to a {@code ScenarioGroup} (and the injected productId when derivable).</li>
 *   <li>{@link #CATALOG_PRESENCE} — a light read-only presence check of the injected key in an
 *       {@code item-config} collection (optionally asserting a field value), used for feeds that
 *       have no dedicated validator group (badges, attributes, promo, category, associations…).</li>
 *   <li>{@link #INVENTORY_PRESENCE} — a light read-only presence check of the injected ItemId in the
 *       inventory-config / inventory-runtime Mongo collections.</li>
 *   <li>{@link #NONE} — no automated verify (selectable-but-disabled scenarios).</li>
 * </ul>
 */
public enum VerifyMode {
    CATALOG_VALIDATORS,
    CATALOG_PRESENCE,
    INVENTORY_PRESENCE,
    NONE
}
