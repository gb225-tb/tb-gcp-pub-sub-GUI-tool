package com.internal.tools.pubsubgui.automation.model;

/**
 * One field comparison in the HCL-vs-streaming cross-verification.
 *
 * @param field    document field name (e.g. {@code colorCode})
 * @param docType  which document the field belongs to (Product / Variant / SKU / Price / EnrichedProduct)
 * @param expected value the HCL migration would produce
 * @param actual   value currently stored by the streaming pipeline
 * @param verdict  MATCH / DIFFERS / GAP (aligned with the workbook's Match legend)
 */
public record FieldDiff(
        String field,
        String docType,
        String expected,
        String actual,
        String verdict) {
}
