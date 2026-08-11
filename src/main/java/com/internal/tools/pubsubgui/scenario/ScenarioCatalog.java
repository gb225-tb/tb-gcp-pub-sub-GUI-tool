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
 * (project {@code np-ecom-2-6d1a}) values taken from each repo's {@code application-perf.yml} and
 * {@code .github/workflows}. The flagship subset is wired ({@code enabled=true}); a few adjacent jobs
 * are registered as selectable-but-disabled placeholders.
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
        // ── Catalog · Streaming (publish -> streaming Dataflow job consumes -> verify config catalog) ──
        add(new ScenarioSpec(
                "CAT-STREAM-ITEM", ScenarioCategory.CATALOG_STREAMING, "Universe Item",
                ScenarioKind.STREAMING, "UniverseItemIngestionProcessor",
                "Publish one Universe retail item message; verify Product/Variant/SKU identity invariants.",
                true,
                "np-ecom-2-catalog_inbound_universe_item-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/universe_item_message.json",
                VerifyMode.CATALOG_VALIDATORS, "UNIVERSE_ITEM", null));

        add(new ScenarioSpec(
                "CAT-STREAM-PRICE", ScenarioCategory.CATALOG_STREAMING, "Universe Price",
                ScenarioKind.STREAMING, "UniverseItemPricingIngestionProcessor",
                "Publish one Universe price message; verify list/sale price routing + SKU.isSale.",
                true,
                "np-ecom-2-catalog_inbound_universe_price-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/universe_price_message.json",
                VerifyMode.CATALOG_VALIDATORS, "PRICE", null));

        add(new ScenarioSpec(
                "CAT-STREAM-ENRICHED", ScenarioCategory.CATALOG_STREAMING, "Enriched Product",
                ScenarioKind.STREAMING, "CfRetailItemIngestionProcessor",
                "Publish one CreativeForce enriched product; verify EnrichedProduct extraction + publish gate.",
                true,
                "np-ecom-2-catalog_inbound_cf_retail-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/enriched_product_message.json",
                VerifyMode.CATALOG_VALIDATORS, "ENRICHED", null));

        // ── Catalog · Batch (upload to GCS -> workflow_dispatch -> poll -> verify config catalog) ──
        add(new ScenarioSpec(
                "CAT-BATCH-ITEM", ScenarioCategory.CATALOG_BATCH, "Universe Item Full Load",
                ScenarioKind.BATCH, "UniverseItemFullLoadBatchProcessor",
                "Upload a pipe-delimited universe item feed to GCS + dispatch the batch job; verify catalog.",
                true,
                null,
                PERF_BUCKET, "retail/universe/item/", "TBUniverseProducts.txt",
                CATALOG_REPO, CATALOG_WORKFLOW,
                "scenario-samples/catalog/universe_item_full_load.txt",
                VerifyMode.CATALOG_VALIDATORS, "UNIVERSE_ITEM", null));

        add(new ScenarioSpec(
                "CAT-BATCH-PRICE", ScenarioCategory.CATALOG_BATCH, "Universe Price Full Load",
                ScenarioKind.BATCH, "UniversePriceFullLoadBatchProcessor",
                "Upload a price full-load CSV to GCS + dispatch the batch job; verify price routing.",
                true,
                null,
                PERF_BUCKET, "retail/universe/price/", "TBUniversePrices.csv",
                CATALOG_REPO, CATALOG_WORKFLOW,
                "scenario-samples/catalog/universe_price_full_load.csv",
                VerifyMode.CATALOG_VALIDATORS, "PRICE", null));

        // ── Inventory · Streaming (publish -> streaming Dataflow job consumes -> verify inventory) ──
        add(new ScenarioSpec(
                "INV-STREAM-AVAIL", ScenarioCategory.INVENTORY_STREAMING, "Availability (BOPIS)",
                ScenarioKind.STREAMING, "InventoryAvailabilityFeedIngestionProcessor",
                "Publish one BOPIS availability message; verify the ItemId lands in runtime Inventory.",
                true,
                "np-ecom-2-catalog_inbound_inventory_availability_alert-topic",
                null, null, null, null, null,
                "scenario-samples/inventory/availability_bopis_message.json",
                VerifyMode.INVENTORY_PRESENCE, null, "inventory-runtime"));

        add(new ScenarioSpec(
                "INV-STREAM-DELTA", ScenarioCategory.INVENTORY_STREAMING, "Delta Feed",
                ScenarioKind.STREAMING, "InventoryDeltaFeedIngestionProcessor",
                "Publish one Shipping/STS delta message; verify the ItemId lands in runtime Inventory.",
                true,
                "np-ecom-2-catalog_inbound_inventory_delta_load-topic",
                null, null, null, null, null,
                "scenario-samples/inventory/shipping_sts_message.json",
                VerifyMode.INVENTORY_PRESENCE, null, "inventory-runtime"));

        add(new ScenarioSpec(
                "INV-STREAM-ITEM", ScenarioCategory.INVENTORY_STREAMING, "Universe Item (Inventory)",
                ScenarioKind.STREAMING, "InventoryUniverseItemIngestionProcessor",
                "Publish one universe item; verify the Item is built in inventory-config Item collection.",
                true,
                "np-ecom-2-catalog_inbound_inventory_universe_item-topic",
                null, null, null, null, null,
                "scenario-samples/catalog/universe_item_message.json",
                VerifyMode.INVENTORY_PRESENCE, null, "inventory-config"));

        // ── Inventory · Batch (upload to GCS -> workflow_dispatch -> poll -> verify inventory) ──
        add(new ScenarioSpec(
                "INV-BATCH-FULL", ScenarioCategory.INVENTORY_BATCH, "Inventory Full Load",
                ScenarioKind.BATCH, "InventoryFullLoadBatchProcessor",
                "Upload a BOPIS/Shipping full-load CSV to GCS + dispatch the batch job; verify runtime Inventory.",
                true,
                null,
                PERF_BUCKET, "inbound/inventory/", "TMW_BOPIS-Inventory-sample.csv",
                INVENTORY_REPO, INVENTORY_WORKFLOW,
                "scenario-samples/inventory/inventory_full_load_bopis.csv",
                VerifyMode.INVENTORY_PRESENCE, null, "inventory-runtime"));
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
