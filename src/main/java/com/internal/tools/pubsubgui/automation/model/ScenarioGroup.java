package com.internal.tools.pubsubgui.automation.model;

/**
 * The processor "families" the Automation view groups scenarios under (drive the top-menu tabs).
 * Mirrors the tabs of the Ingestion Processors Integration Test Plan workbook.
 */
public enum ScenarioGroup {
    UNIVERSE_ITEM("UniverseItem", "Product / Variant / SKU ingestion invariants"),
    ENRICHED("Enriched", "EnrichedProduct extraction, publish gate, seoUrl"),
    PRICE("Price", "list/sale routing, catalog-id enrichment, SKU.isSale"),
    CROSS_PROCESSOR("Cross-Processor", "Merge-preserve & cross-collection consistency"),
    HCL_XFORM("HCL vs Streaming", "Field-by-field cross-verification against the HCL migration");

    private final String label;
    private final String description;

    ScenarioGroup(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
