package com.internal.tools.pubsubgui.automation.scenario;

import com.internal.tools.pubsubgui.automation.check.CatalogValidators;
import com.internal.tools.pubsubgui.automation.check.HclCrossVerifier;
import com.internal.tools.pubsubgui.automation.check.Validator;
import com.internal.tools.pubsubgui.automation.model.CheckResult;
import com.internal.tools.pubsubgui.automation.model.Feasibility;
import com.internal.tools.pubsubgui.automation.model.ScenarioDef;
import com.internal.tools.pubsubgui.automation.model.ScenarioGroup;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The consolidated catalog of scenarios from the Ingestion Processors Integration Test Plan, each
 * bound to a read-only {@link Validator}. Scenarios that require controlled input (empty DB /
 * synthetic message / active injection) are registered with {@link Feasibility#NOT_APPLICABLE} and a
 * validator that reports {@code NA} so the full matrix stays visible without pretending to verify them.
 */
@Component
public class ScenarioRegistry {

    /** A scenario definition paired with the validator that runs it. */
    public record Entry(ScenarioDef def, Validator validator) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> byId = new LinkedHashMap<>();

    public ScenarioRegistry() {
        // ── UniverseItem (Product / Variant / SKU) ──────────────────────────────
        add("UI-05", ScenarioGroup.UNIVERSE_ITEM, "Transformation", "colorCode zero-padded on Variant",
                "P2", "Numeric ProductColorCode is left zero-padded to colorCodePadWidth (5 -> '05').",
                CatalogValidators.colorCodePadded());
        add("UI-06", ScenarioGroup.UNIVERSE_ITEM, "Transformation", "SKU.sizeCode = '<division>_<rawSizeCode>'",
                "P1", "When Division present, sizeCode is prefixed with '<division>_'; else raw SizeCode.",
                CatalogValidators.sizeCodeFormat());
        add("UI-07", ScenarioGroup.UNIVERSE_ITEM, "Transformation", "division stamped on SKU mirrors Product",
                "P1", "SKU.division mirrors Product.division for threshold gating.",
                CatalogValidators.divisionMirror());
        add("UI-10", ScenarioGroup.UNIVERSE_ITEM, "Transformation", "INITCAP applied to text attributes",
                "P2", "fit/material/color/colorFamily are INITCAP'd, not screaming-caps.",
                CatalogValidators.initCapText());
        na("UI-01", ScenarioGroup.UNIVERSE_ITEM, "Identity", "New item creates Product+Variant+SKU", "P1");
        na("UI-04", ScenarioGroup.UNIVERSE_ITEM, "BigAndTall merge", "BT row merges into regular product", "P1");
        na("UI-13", ScenarioGroup.UNIVERSE_ITEM, "Delta delete", "_DeltaStatus=DELETED emits SKU tombstone", "P1");
        na("UI-14", ScenarioGroup.UNIVERSE_ITEM, "Rollup", "All SKUs inactive -> Variant/Product inactive", "P1");
        na("UI-17", ScenarioGroup.UNIVERSE_ITEM, "Schema", "Missing required field routed to errors", "P1");
        na("UI-24", ScenarioGroup.UNIVERSE_ITEM, "Idempotency", "Duplicate message does not duplicate docs", "P1");

        // ── EnrichedProduct ─────────────────────────────────────────────────────
        add("EN-01", ScenarioGroup.ENRICHED, "Identity", "_id/variantId derivation; productId strip last '_'",
                "P1", "EnrichedProduct._id == variantId; productId = variantId up to last '_'.",
                CatalogValidators.enrichedIdentity());
        add("EN-02", ScenarioGroup.ENRICHED, "Publish gate", "Publish criteria met stamps publishedAt",
                "P1", "publishedAt is only stamped when a mainImage is present.",
                CatalogValidators.publishRequiresMainImage());
        add("EN-03", ScenarioGroup.ENRICHED, "Publish gate", "Missing mainImage -> publishedAt NOT set",
                "P1", "No mainImage means the variant must not be published.",
                CatalogValidators.noMainImageNoPublish());
        add("EN-05", ScenarioGroup.ENRICHED, "seoUrl", "seoUrl present on published products",
                "P1", "A published Product carries a generated seoUrl.",
                CatalogValidators.seoUrlOnPublished());
        na("EN-04", ScenarioGroup.ENRICHED, "Unpublish", "Removing mainImage unsets publishedAt", "P1");
        na("EN-16", ScenarioGroup.ENRICHED, "Merge-preserve", "Upsert preserves foreign fields", "P2");
        na("EN-18", ScenarioGroup.ENRICHED, "Schema", "Missing longDescription -> errors", "P1");

        // ── Price ───────────────────────────────────────────────────────────────
        add("PR-03", ScenarioGroup.PRICE, "Identity", "_id/sku uppercased & trimmed",
                "P1", "Price._id == uppercase(trim(CatentryPartNumber)).",
                CatalogValidators.priceIdNormalized());
        add("PR-04", ScenarioGroup.PRICE, "Enrichment", "Catalog ids match the referenced SKU",
                "P1", "Price.productId/variantId equal the linked SKU's ids.",
                CatalogValidators.priceCatalogIdsMatchSku());
        add("PR-09", ScenarioGroup.PRICE, "SKU isSale", "salePrice < listPrice -> SKU.isSale=true",
                "P1", "SKU.isSale is true exactly when salePrice < listPrice.",
                CatalogValidators.isSaleConsistency());
        add("PR-TYPE", ScenarioGroup.PRICE, "Types", "Price values are numeric",
                "P2", "listPrice/salePrice/promoPrice are stored as numbers, not strings.",
                CatalogValidators.priceNumericTypes());
        na("PR-06", ScenarioGroup.PRICE, "Merge-preserve", "promoPrice preserved through list/sale update", "P1");
        na("PR-13", ScenarioGroup.PRICE, "Delta/Delete", "_DeltaStatus/Delete are NO-OP on streaming", "P1");
        na("PR-15", ScenarioGroup.PRICE, "Schema", "Missing PriceinUSD -> errors", "P1");

        // ── Cross-processor ───────────────────────────────────────────────────────
        add("CP-08", ScenarioGroup.CROSS_PROCESSOR, "Consistency", "Cross-collection ids consistent",
                "P1", "SKU/Variant references resolve to existing Product/Variant docs.",
                CatalogValidators.crossCollectionIdentity());
        add("ERR-01", ScenarioGroup.CROSS_PROCESSOR, "Errors", "errors collection inspection",
                "P1", "Surfaces rejected/failed messages currently sitting in the errors collection.",
                CatalogValidators.schemaErrorsInErrorsCollection());
        na("CP-01", ScenarioGroup.CROSS_PROCESSOR, "Merge-preserve", "seoUrl survives item re-ingest", "P1");
        na("CP-02", ScenarioGroup.CROSS_PROCESSOR, "Merge-preserve", "promoPrice survives price update", "P1");
        na("CP-03", ScenarioGroup.CROSS_PROCESSOR, "Merge-preserve", "publishedAt survives item re-ingest", "P1");
        na("CP-04", ScenarioGroup.CROSS_PROCESSOR, "Backfill", "SKU upsert backfills Price catalog ids", "P1");

        // ── HCL vs streaming cross-verification (require productId) ──────────────
        add("XF-PRODUCT", ScenarioGroup.HCL_XFORM, "Xform", "Product fields: HCL vs streaming",
                "P1", "Product-level field comparison against the HCL migration output.",
                HclCrossVerifier.product());
        add("XF-VARIANT", ScenarioGroup.HCL_XFORM, "Xform", "Variant fields: HCL vs streaming",
                "P1", "Per-Variant field-by-field comparison against the HCL migration output.",
                HclCrossVerifier.variant());
        add("XF-SKU", ScenarioGroup.HCL_XFORM, "Xform", "SKU fields: HCL vs streaming",
                "P1", "Per-SKU field-by-field comparison against the HCL migration output.",
                HclCrossVerifier.sku());
        add("XF-PRICE", ScenarioGroup.HCL_XFORM, "Xform", "Price fields: HCL vs streaming",
                "P1", "Per-Price field-by-field comparison against the HCL migration output.",
                HclCrossVerifier.price());
        add("XF-ENRICHED", ScenarioGroup.HCL_XFORM, "Xform", "EnrichedProduct fields: HCL vs streaming",
                "P2", "Per-EnrichedProduct field-by-field comparison against the HCL migration output.",
                HclCrossVerifier.enriched());
    }

    private void add(String id, ScenarioGroup group, String category, String title, String priority,
                     String note, Validator validator) {
        Entry entry = new Entry(
                new ScenarioDef(id, group, category, title, priority, Feasibility.READONLY, note,
                        SPEC.getOrDefault(id, note)), validator);
        entries.add(entry);
        byId.put(id, entry);
    }

    private void na(String id, ScenarioGroup group, String category, String title, String priority) {
        String note = "Requires controlled input (empty DB / synthetic message / active injection) — "
                + "not verifiable read-only.";
        Entry entry = new Entry(
                new ScenarioDef(id, group, category, title, priority, Feasibility.NOT_APPLICABLE, note,
                        SPEC.getOrDefault(id, note)),
                (scenarioId, ctx) -> CheckResult.na(scenarioId, note));
        entries.add(entry);
        byId.put(id, entry);
    }

    /**
     * Verbatim scenario specs from {@code Ingestion_Processors_Integration_Test_Plan.xlsx}
     * (each is the workbook's "Scenario" text followed by its "Expected result"). Shown in the UI as a
     * readable summary so a run's PASS/FAIL is understandable against the documented expectation.
     */
    private static final Map<String, String> SPEC = new LinkedHashMap<>();

    static {
        // UniverseItem
        SPEC.put("UI-01", "New item creates Product+Variant+SKU with derived ids → Product._id=productId=TMW_100L; "
                + "Variant._id=TMW_100L_15; SKU._id=sku=TMW100L38115; all status=active, source=universe.");
        SPEC.put("UI-04", "BigAndTall row merges into the regular product by shared RegClassCode → still 1 Product, "
                + "1 Variant, 2 SKUs; BT SKU.productId=parent, isBigAndTall=true, isMerged=true.");
        SPEC.put("UI-05", "colorCode zero-padded on Variant → Variant.colorCode='05' (padded to colorCodePadWidth); "
                + "Variant._id uses the RAW color (e.g. TMW_..._5).");
        SPEC.put("UI-06", "SKU.sizeCode = '<division>_<rawSizeCode>' → e.g. Division=30, SizeCode=40R gives "
                + "SKU.sizeCode='30_40R'; if Division blank, sizeCode is the raw SizeCode.");
        SPEC.put("UI-07", "division stamped on SKU mirrors Product (for threshold gating) → SKU.division='10' AND "
                + "Product.division='10'.");
        SPEC.put("UI-10", "INITCAP applied to text attributes → Product.fit='Slim', Product.material='Wool Blend'; "
                + "Variant.color='Blue Neat', Variant.colorFamily='Blue'.");
        SPEC.put("UI-13", "_DeltaStatus=DELETED emits a SKU tombstone only → SKU.status=inactive, endDate=now; "
                + "Product/Variant are NOT re-emitted.");
        SPEC.put("UI-14", "All SKUs inactive → Variant inactive+endDate; all Variants inactive → Product inactive "
                + "(cascade via CatalogRollupService).");
        SPEC.put("UI-17", "Missing required field is routed to errors and no product is written → 0 Product docs; "
                + "1 errors doc (errorType=SCHEMA_VALIDATION).");
        SPEC.put("UI-24", "Duplicate message does not duplicate docs → exactly 1 Product, 1 Variant, 1 SKU.");
        // Enriched
        SPEC.put("EN-01", "_id/variantId = raw productId; productId = strip after last '_' → "
                + "EnrichedProduct._id=variantId=TMW_100L_15; productId=TMW_100L.");
        SPEC.put("EN-02", "Publish criteria met stamps publishedAt (set-once) → publishedAt stamped; "
                + "productName/productDescription/mainImage present.");
        SPEC.put("EN-03", "Missing mainImage → publishedAt NOT set (passes schema, fails publish) → "
                + "EnrichedProduct has NO publishedAt; still persisted (status active).");
        SPEC.put("EN-04", "Previously published, then a mandatory field drops → unpublish cascade clears publishedAt "
                + "on the variant and all its SKUs; Product cleared iff no sibling variant is published.");
        SPEC.put("EN-05", "seoUrl generated from brand + shortDescription + productId → EnrichedProduct.seoUrl is "
                + "variant-specific; Product.seoUrl generated set-once.");
        SPEC.put("EN-16", "Enriched upsert preserves foreign fields omitted by the feed → foreign field preserved "
                + "(merge-preserve; audit fields re-stamped).");
        SPEC.put("EN-18", "Missing longDescription (required) → errors collection → 0 EnrichedProduct; 1 errors doc.");
        // Price
        SPEC.put("PR-03", "_id/sku uppercased & trimmed from CatentryPartNumber → e.g. ' tmw94fm36101 ' becomes "
                + "Price._id=sku='TMW94FM36101'.");
        SPEC.put("PR-04", "Catalog ids enriched from the SKU collection on INSERT → Price.productId=TMW_94FM, "
                + "Price.variantId=TMW_94FM_36.");
        SPEC.put("PR-09", "salePrice < listPrice → SKU.isSale=true (SkuAttributesFirestoreWriter); "
                + "salePrice absent or >= listPrice → SKU.isSale=false.");
        SPEC.put("PR-TYPE", "Price monetary values are stored as numeric types (not strings): listPrice / salePrice / "
                + "promoPrice must be numbers so downstream consumers don't reject them.");
        SPEC.put("PR-06", "promoPrice preserved through a list/sale update → Price.listPrice updated AND existing "
                + "promoPrice preserved.");
        SPEC.put("PR-13", "_DeltaStatus and Delete are NO-OP on the streaming path → no soft-delete, no status change; "
                + "price fields are still upserted.");
        SPEC.put("PR-15", "Missing PriceinUSD (required) → errors → 0 Price; 1 errors doc.");
        // Cross-processor
        SPEC.put("CP-01", "seoUrl written by enriched must survive a Universe item replace → Product.seoUrl preserved "
                + "(merge-preserve). THE core data-loss guard.");
        SPEC.put("CP-02", "promoPrice written by the promo writer must survive a Universe price update → "
                + "Price.promoPrice preserved.");
        SPEC.put("CP-03", "publishedAt (enriched patcher) must survive item feed replaces → publishedAt preserved on "
                + "Product/Variant/SKU.");
        SPEC.put("CP-04", "SKU upsert backfills Price catalog ids → Price.productId/variantId backfilled "
                + "(Wait.on skusDone → PriceCatalogIdBackfillWriter).");
        SPEC.put("CP-08", "End-to-end config consistency for one logical SKU → cross-collection ids consistent "
                + "(productId/variantId/sku); no field clobbered across processors.");
        SPEC.put("ERR-01", "Schema_Requirements gap-finder → inspect the errors collection for rejected/failed "
                + "messages (errorType=SCHEMA_VALIDATION) across processors; expected 0 after a clean go-live.");
        // HCL vs streaming (Xform_* / HCL_vs_Streaming_Diffs)
        SPEC.put("XF-PRODUCT", "Xform_Product / HCL_vs_Streaming_Diffs → compare the Product document the HCL "
                + "migration would build against the streaming Product doc, field by field.");
        SPEC.put("XF-VARIANT", "Xform_Variant → for each Variant the HCL migration would build, compare every field "
                + "against the streaming Variant doc (paired by _id). MISSING = HCL value not migrated; EXTRA = "
                + "streaming-only field (other feeds); DIFFERS = value mismatch.");
        SPEC.put("XF-SKU", "Xform_SKU → for each SKU the HCL migration would build, compare every field against the "
                + "streaming SKU doc (paired by _id).");
        SPEC.put("XF-PRICE", "Xform_Price → for each Price the HCL migration would build, compare every field "
                + "(listPrice/salePrice/promoPrice/status/...) against the streaming Price doc (paired by _id).");
        SPEC.put("XF-ENRICHED", "Xform_EnrichedProduct → for each publish-ready EnrichedProduct the HCL migration "
                + "would build, compare every field against the streaming EnrichedProduct doc (paired by _id).");
    }

    public List<Entry> all() {
        return List.copyOf(entries);
    }

    public List<ScenarioDef> definitions() {
        return entries.stream().map(Entry::def).toList();
    }

    public Entry get(String id) {
        return byId.get(id);
    }
}
