package com.internal.tools.pubsubgui.scenario;

import com.internal.tools.pubsubgui.scenario.model.ScenarioCategory;
import com.internal.tools.pubsubgui.scenario.model.ScenarioKind;
import com.internal.tools.pubsubgui.scenario.model.ScenarioSpec;
import com.internal.tools.pubsubgui.scenario.model.VerifyMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Perf-only catalog of injectable job scenarios across {@code tb-catalog-data-processor} and
 * {@code tb-catalog-inventory-processor}. Topics / GCS paths / workflows are the Perf
 * (project {@code np-ecom-2-6d1a}) values from each repo's {@code application-perf.yml} and
 * {@code .github/workflows}.
 *
 * <p>Coverage rule: one scenario per file/message-drivable feed. File-less feeds (CDC-&gt;CT, runtime CT
 * ingest, outbound feeds, DB2 HCL migration) are intentionally not registered. Streaming feeds publish a
 * small bundled JSON sample; batch feeds upload a file and dispatch the batch workflow.
 *
 * <p>Safety: full-load RECONCILIATION batch jobs (universe item/price full load, CF retail enriched,
 * inventory full load) set {@code requiresFullFeed=true} — the tiny bundled file is a format reference only
 * and the run is blocked unless a COMPLETE feed is uploaded, because these jobs inactivate/zero-out anything
 * not present in the file. The inventory full-load batch scenarios are additionally disabled here because
 * their production feeds are far too large to drive from this UI.
 */
@Component
public class ScenarioCatalog {

    private static final String PERF_BUCKET = "np-ecom-2-catalog";
    private static final String CATALOG_REPO = "MensWearhouse/tb-catalog-data-processor";
    private static final String CATALOG_WORKFLOW = "np-batch-scheduler.yaml";
    private static final String INVENTORY_REPO = "MensWearhouse/tb-catalog-inventory-processor";
    private static final String INVENTORY_WORKFLOW = "np-deploy.yaml";

    private final List<ScenarioSpec> specs = new ArrayList<>();
    private final Map<String, ScenarioSpec> byId = new LinkedHashMap<>();

    public ScenarioCatalog() {
        // ─────────────────────────── Catalog · Streaming ───────────────────────────
        // publish -> streaming Dataflow job consumes -> verify config catalog.

        add(new ScenarioSpec(
                "CAT-STREAM-ITEM", ScenarioCategory.CATALOG_STREAMING, "Universe Item",
                ScenarioKind.STREAMING, "UniverseItemIngestionProcessor",
                "Publish one Universe retail item; verify Product/Variant/SKU identity invariants.",
                true,
                "np-ecom-2-catalog_inbound_universe_item-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/universe_item_message.json",
                VerifyMode.CATALOG_VALIDATORS, "UNIVERSE_ITEM", null,
                null, null, null, null, null, null,
                "Variant,SKU", false));

        add(new ScenarioSpec(
                "CAT-STREAM-PRICE", ScenarioCategory.CATALOG_STREAMING, "Universe Price",
                ScenarioKind.STREAMING, "UniverseItemPricingIngestionProcessor",
                "Publish one Universe price message; verify list/sale price routing + SKU.isSale.",
                true,
                "np-ecom-2-catalog_inbound_universe_price-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/universe_price_message.json",
                VerifyMode.CATALOG_VALIDATORS, "PRICE", null,
                null, null, null, null, null, null,
                null, false));

        add(new ScenarioSpec(
                "CAT-STREAM-ENRICHED", ScenarioCategory.CATALOG_STREAMING, "Enriched Product",
                ScenarioKind.STREAMING, "CfRetailItemIngestionProcessor",
                "Publish one CreativeForce enriched product; verify EnrichedProduct extraction + publish gate.",
                true,
                "np-ecom-2-catalog_inbound_cf_retail-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/enriched_product_message.json",
                VerifyMode.CATALOG_VALIDATORS, "ENRICHED", null,
                null, null, null, null, null, null,
                "EnrichedProduct", false));

        add(new ScenarioSpec(
                "CAT-STREAM-RATINGS", ScenarioCategory.CATALOG_STREAMING, "BazaarVoice Ratings",
                ScenarioKind.STREAMING, "BVProductRatingsIngestionProcessor",
                "Publish one BazaarVoice product rating; verify the Rating document is created.",
                true,
                "np-ecom-2-catalog_inbound_bazaarvoice-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/bv_ratings_message.json",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "Rating", "id", null, "_id", null, null,
                "Rating", false));

        add(new ScenarioSpec(
                "CAT-STREAM-PROMO-MSG", ScenarioCategory.CATALOG_STREAMING, "HCL Promo Message",
                ScenarioKind.STREAMING, "PromoMessageProcessor",
                "Publish one HCL promo message; verify the target Variant exists (promo fields patched).",
                true,
                "np-ecom-2-catalog_inbound_hcl_promo_messages-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/hcl_promo_message.json",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "Variant", "partNumber", null, "_id", null, null,
                null, false));

        add(new ScenarioSpec(
                "CAT-STREAM-PROMO-PRICE", ScenarioCategory.CATALOG_STREAMING, "HCL Promo Price",
                ScenarioKind.STREAMING, "PromoPriceIngestionProcessor",
                "Publish one HCL promo price; verify the target Price document exists.",
                true,
                "np-ecom-2-catalog_inbound_hcl_promo_prices-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/hcl_promo_price_message.json",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "Price", "partNumber", null, "_id", null, null,
                null, false));

        add(new ScenarioSpec(
                "CAT-STREAM-CATEGORY", ScenarioCategory.CATALOG_STREAMING, "HCL Category",
                ScenarioKind.STREAMING, "PromoCategoryMessageProcessor",
                "Publish one HCL category message; verify the Category upsert by catGroupId.",
                true,
                "np-ecom-2-catalog_inbound_hcl_categories-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/hcl_category_message.json",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "Category", "catGroupId", null, "catGroupId", null, null,
                null, false));

        add(new ScenarioSpec(
                "CAT-STREAM-CPA", ScenarioCategory.CATALOG_STREAMING, "Category-Product Assoc",
                ScenarioKind.STREAMING, "CategoryProductAssociationProcessor",
                "Publish one HCL category-product association; verify the CategoryProductAssociation exists.",
                true,
                "np-ecom-2-catalog_inbound_hcl_product_category_associations-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/category_product_association_message.json",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "CategoryProductAssociation", "partNumber", null, "productId", null, null,
                null, false));

        add(new ScenarioSpec(
                "CAT-STREAM-VARIANT-ASSOC", ScenarioCategory.CATALOG_STREAMING, "Fit Variant Assoc",
                ScenarioKind.STREAMING, "FitVariantAssociationProcessor",
                "Publish one fit variant association; verify the VariantAssociation exists.",
                true,
                "np-ecom-2-catalog_inbound_variantassociations-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/variant_association_message.json",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "VariantAssociation", "from_Class", null, "_id", null, null,
                null, false));

        add(new ScenarioSpec(
                "CAT-STREAM-CATEGORY-PUBLISH", ScenarioCategory.CATALOG_STREAMING, "Category Publish",
                ScenarioKind.STREAMING, "CategoryPublishedProcessor",
                "Publish one category-publish event; verify the referenced Category exists (rule eval trigger).",
                true,
                "np-ecom-2-catalog_inbound_category_publish-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/category_published_message.json",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "Category", "id", null, "_id", null, null,
                null, false));

        // ─────────────────────────── Catalog · Batch ───────────────────────────
        // upload to GCS -> workflow_dispatch -> poll -> verify. Full-load reconcilers require a full feed.

        add(new ScenarioSpec(
                "CAT-BATCH-ITEM", ScenarioCategory.CATALOG_BATCH, "Universe Item Full Load",
                ScenarioKind.BATCH, "UniverseItemFullLoadBatchProcessor",
                "FULL-LOAD RECONCILE: upload the COMPLETE pipe-delimited item feed + dispatch the batch job. "
                        + "Records not in the file are deactivated — a full feed is required.",
                true,
                null,
                PERF_BUCKET, "retail/universe/item/", "TBUniverseProducts.txt",
                CATALOG_REPO, CATALOG_WORKFLOW,
                "scenario-samples/catalog/universe_item_full_load.txt",
                VerifyMode.CATALOG_VALIDATORS, "UNIVERSE_ITEM", null,
                null, null, null, null, null, null,
                null, true));

        add(new ScenarioSpec(
                "CAT-BATCH-PRICE", ScenarioCategory.CATALOG_BATCH, "Universe Price Full Load",
                ScenarioKind.BATCH, "UniversePriceFullLoadBatchProcessor",
                "FULL-LOAD RECONCILE: upload the COMPLETE price CSV + dispatch the batch job. "
                        + "Prices not in the file are removed — a full feed is required.",
                true,
                null,
                PERF_BUCKET, "retail/universe/price/", "TBUniversePrices.csv",
                CATALOG_REPO, CATALOG_WORKFLOW,
                "scenario-samples/catalog/universe_price_full_load.csv",
                VerifyMode.CATALOG_VALIDATORS, "PRICE", null,
                null, null, null, null, null, null,
                null, true));

        add(new ScenarioSpec(
                "CAT-BATCH-ENRICHED", ScenarioCategory.CATALOG_BATCH, "CF Retail Enriched Full",
                ScenarioKind.BATCH, "CfRetailItemBatchProcessor",
                "FULL-LOAD RECONCILE: upload the COMPLETE CreativeForce enriched JSON + dispatch the batch job. "
                        + "Orphan EnrichedProducts are inactivated — a full feed is required.",
                true,
                null,
                PERF_BUCKET, "retail/cf/", "TBEnrichedProducts.json",
                CATALOG_REPO, CATALOG_WORKFLOW,
                "scenario-samples/catalog/cf_retail_items.json",
                VerifyMode.CATALOG_VALIDATORS, "ENRICHED", null,
                null, null, null, null, null, null,
                null, true));

        add(new ScenarioSpec(
                "CAT-BATCH-ATTRIBUTES", ScenarioCategory.CATALOG_BATCH, "Attributes",
                ScenarioKind.BATCH, "AttributeBatchProcessor",
                "Upload an attribute metadata CSV + dispatch the batch job; verify an Attribute is present.",
                true,
                null,
                PERF_BUCKET, "attributes/", "attributes.csv",
                CATALOG_REPO, CATALOG_WORKFLOW,
                "scenario-samples/catalog/attributes.csv",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "Attribute", null, "name", "_id", null, null,
                null, false));

        add(new ScenarioSpec(
                "CAT-BATCH-BADGE-BESTSELLER", ScenarioCategory.CATALOG_BATCH, "Badge: Best Seller",
                ScenarioKind.BATCH, "ProductBadgesUpdateProcessor",
                "Upload a bestseller badge CSV + dispatch the batch job; verify Product.isBestSeller=true.",
                true,
                null,
                PERF_BUCKET, "badges/bestseller/", "bestseller.csv",
                CATALOG_REPO, CATALOG_WORKFLOW,
                "scenario-samples/catalog/badges_bestseller.csv",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "Product", null, "PartNumber", "_id", "isBestSeller", "true",
                null, false));

        add(new ScenarioSpec(
                "CAT-BATCH-BADGE-GOINGFAST", ScenarioCategory.CATALOG_BATCH, "Badge: Going Fast",
                ScenarioKind.BATCH, "ProductBadgesUpdateProcessor",
                "Upload a going-fast badge CSV + dispatch the batch job; verify Product.isGoingFast=true.",
                true,
                null,
                PERF_BUCKET, "badges/goingfast/", "goingfast.csv",
                CATALOG_REPO, CATALOG_WORKFLOW,
                "scenario-samples/catalog/badges_goingfast.csv",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "Product", null, "productId", "_id", "isGoingFast", "true",
                null, false));

        add(new ScenarioSpec(
                "CAT-BATCH-BADGE-POPULAR", ScenarioCategory.CATALOG_BATCH, "Badge: Popular",
                ScenarioKind.BATCH, "ProductBadgesUpdateProcessor",
                "Upload a popular badge CSV + dispatch the batch job; verify Product.isPopular=true.",
                true,
                null,
                PERF_BUCKET, "badges/popular/", "popular.csv",
                CATALOG_REPO, CATALOG_WORKFLOW,
                "scenario-samples/catalog/badges_popular.csv",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "Product", null, "productId", "_id", "isPopular", "true",
                null, false));

        add(new ScenarioSpec(
                "CAT-BATCH-ONETIME", ScenarioCategory.CATALOG_BATCH, "One-Time Data Load",
                ScenarioKind.BATCH, "OneTimeDataLoadProcessor",
                "Upload a one-time load CSV + dispatch the batch job; verify the SKU is present.",
                true,
                null,
                PERF_BUCKET, "onetime/", "oneTimeLoadData.csv",
                CATALOG_REPO, CATALOG_WORKFLOW,
                "scenario-samples/catalog/onetime_load.csv",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "SKU", null, "sku", "_id", null, null,
                null, false));

        // ─────────────────────────── Catalog · Bundles ───────────────────────────
        // CRITICAL & SEPARATE: Mongo-derived bundle readiness. Consumes NO file — it derives bundle SKU
        // size/price/clearance + bundle Product attrs from existing Config data (gated by runtime Inventory)
        // and patches them back to Config. Dispatch-only: no GCS upload, just workflow_dispatch + poll.
        // Must run AFTER the universe full-load has created the bundle Product/Variant/SKU + productAssociation.

        add(new ScenarioSpec(
                "CAT-BUNDLE-READINESS", ScenarioCategory.CATALOG_BUNDLES, "Bundle Readiness",
                ScenarioKind.BATCH, "UniverseItemBundleBatchProcessor",
                "DERIVED (no file): dispatch the bundle readiness batch. It derives bundle SKU size/price/"
                        + "clearance and bundle Product attrs from Config Product/Variant/SKU/Price + runtime "
                        + "Inventory, then patches Config. Run AFTER the universe full load. Success = the batch "
                        + "run completes; verify confirms a bundle Product (isBundle=true) is present.",
                true,
                null,
                null, null, null,
                CATALOG_REPO, CATALOG_WORKFLOW,
                "scenario-samples/catalog/bundle_readiness_note.txt",
                VerifyMode.CATALOG_PRESENCE, null, null,
                "Product", null, null, "_id", "isBundle", "true",
                null, false, "TMW_30AB"));

        // ─────────────────────────── Inventory · Streaming ───────────────────────────
        // publish -> streaming inventory Dataflow job consumes -> verify inventory Mongo.

        add(new ScenarioSpec(
                "INV-STREAM-FULLFEED", ScenarioCategory.INVENTORY_STREAMING, "Full Feed (BOPIS)",
                ScenarioKind.STREAMING, "InventoryFullFeedIngestionProcessor",
                "Publish one BOPIS full-feed message; verify the ItemId lands in config Inventory.",
                true,
                "np-ecom-2-catalog_inbound_inventory_full_load-topic",
                null, null, null, null, null,
                "scenario-samples/inventory/inv_full_feed_bopis.json",
                VerifyMode.INVENTORY_PRESENCE, null, "inventory-config",
                "Inventory", null, null, null, null, null,
                "Inventory", false));

        add(new ScenarioSpec(
                "INV-STREAM-AVAIL", ScenarioCategory.INVENTORY_STREAMING, "Availability Alert (BOPIS)",
                ScenarioKind.STREAMING, "InventoryAvailabilityFeedIngestionProcessor",
                "Publish one BOPIS availability alert; verify the ItemId lands in runtime Inventory.",
                true,
                "np-ecom-2-catalog_inbound_inventory_availability_alert-topic",
                null, null, null, null, null,
                "scenario-samples/inventory/availability_bopis_message.json",
                VerifyMode.INVENTORY_PRESENCE, null, "inventory-runtime",
                null, null, null, null, null, null,
                null, false));

        add(new ScenarioSpec(
                "INV-STREAM-DELTA", ScenarioCategory.INVENTORY_STREAMING, "Delta Feed",
                ScenarioKind.STREAMING, "InventoryDeltaFeedIngestionProcessor",
                "Publish one delta message (ItemId/LocationId/Quantity/Status); verify runtime Inventory.",
                true,
                "np-ecom-2-catalog_inbound_inventory_delta_load-topic",
                null, null, null, null, null,
                "scenario-samples/inventory/inv_delta_message.json",
                VerifyMode.INVENTORY_PRESENCE, null, "inventory-runtime",
                null, null, null, null, null, null,
                null, false));

        add(new ScenarioSpec(
                "INV-STREAM-ITEM", ScenarioCategory.INVENTORY_STREAMING, "Universe Item (Inventory)",
                ScenarioKind.STREAMING, "InventoryUniverseItemIngestionProcessor",
                "Publish one universe item; verify the Item is built in inventory-config Item.",
                true,
                "np-ecom-2-catalog_inbound_inventory_universe_item-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/universe_item_message.json",
                VerifyMode.INVENTORY_PRESENCE, null, "inventory-config",
                "Item", null, null, null, null, null,
                "Item", false));

        add(new ScenarioSpec(
                "INV-STREAM-CDC", ScenarioCategory.INVENTORY_STREAMING, "Inventory CDC (config->runtime)",
                ScenarioKind.STREAMING, "RuntimeInventoryIngestionProcessor",
                "Publish one config-Inventory CDC change; verify the sku lands in runtime Inventory.",
                true,
                "np-ecom-2-catalog_inventory_change-topic",
                null, null, null, null, null,
                "scenario-samples/inventory/inv_cdc_change.json",
                VerifyMode.INVENTORY_PRESENCE, null, "inventory-runtime",
                "Inventory", null, null, null, null, null,
                "Inventory", false));

        // ─────────────────────────── Inventory · Batch ───────────────────────────
        // Disabled: full-load reconcilers whose production feeds are far too large to drive from this UI.

        add(new ScenarioSpec(
                "INV-BATCH-BOPIS", ScenarioCategory.INVENTORY_BATCH, "Inventory Full Load (BOPIS)",
                ScenarioKind.BATCH, "InventoryFullLoadBatchProcessor",
                "DISABLED (feed too large): full-load reconcile of the BOPIS store inventory CSV.",
                false,
                null,
                PERF_BUCKET, "inbound/inventory/", "TMW_BOPIS-Inventory-sample.csv",
                INVENTORY_REPO, INVENTORY_WORKFLOW,
                "scenario-samples/inventory/inventory_full_load_bopis.csv",
                VerifyMode.INVENTORY_PRESENCE, null, "inventory-runtime",
                null, null, null, null, null, null,
                null, true));

        add(new ScenarioSpec(
                "INV-BATCH-SHIPPING", ScenarioCategory.INVENTORY_BATCH, "Inventory Full Load (Shipping/STS)",
                ScenarioKind.BATCH, "InventoryFullLoadBatchProcessor",
                "DISABLED (feed too large): full-load reconcile of the Shipping/STS DC inventory CSV.",
                false,
                null,
                PERF_BUCKET, "inbound/inventory/", "TMW_Ecomm_Shipping_STS-Inventory-sample.csv",
                INVENTORY_REPO, INVENTORY_WORKFLOW,
                "scenario-samples/inventory/inventory_full_load_shipping_sts.csv",
                VerifyMode.INVENTORY_PRESENCE, null, "inventory-runtime",
                null, null, null, null, null, null,
                null, true));
    }

    private void add(ScenarioSpec spec) {
        specs.add(spec);
        byId.put(spec.id(), spec);
    }

    public List<ScenarioSpec> all() {
        return List.copyOf(specs);
    }

    public Optional<ScenarioSpec> find(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id.trim()));
    }
}
