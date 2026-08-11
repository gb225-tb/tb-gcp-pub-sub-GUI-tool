package com.internal.tools.pubsubgui.scenario.model;

/**
 * The four short, understandable buckets the Scenario Runner groups injectable jobs under (drives the
 * Category dropdown). Splits the two processor repos (Catalog / Inventory) by injection mechanism
 * (Streaming publish vs Batch upload+dispatch).
 */
public enum ScenarioCategory {
    CATALOG_STREAMING("Catalog · Streaming"),
    CATALOG_BATCH("Catalog · Batch"),
    CATALOG_BUNDLES("Catalog · Bundles"),
    INVENTORY_STREAMING("Inventory · Streaming"),
    INVENTORY_BATCH("Inventory · Batch");

    private final String label;

    ScenarioCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
