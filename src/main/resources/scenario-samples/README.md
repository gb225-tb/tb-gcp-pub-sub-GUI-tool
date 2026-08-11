# Scenario Runner sample payloads

Small, readable, per-feed sample inputs used by the Perf-only **Scenario Runner**
(`ScenarioCatalog` -> `ScenarioRunService`). Streaming samples are published to a Perf topic;
batch samples are uploaded to GCS and the batch workflow is dispatched. Each file is intentionally
tiny (1-5 records) and sliced from the large local `automation/` dumps or the two processors'
bundled `src/main/resources` samples.

## Golden thread

Catalog samples share one coherent product family so cross-feed scenarios line up and verification is
meaningful:

- Parent product: `TMW_100L`  ·  color variant / productId: `TMW_100L_15`  ·  SKU: `TMW100L38115`
- Category (HCL catGroupId): `44064`

Inventory samples share one SKU/location so presence checks are consistent:

- SKU: `TMW5B7U75372`  ·  store location: `2706`  ·  network/DC location: `0000`
- CDC sample uses SKU `TMW5B7U75372` keyed `TMW5B7U75372_2706`

## Catalog

| File | Feed / processor | Kind | Verify |
|------|------------------|------|--------|
| `catalog/universe_item_message.json` | UniverseItemIngestionProcessor | streaming | validators (UNIVERSE_ITEM) |
| `catalog/universe_price_message.json` | UniverseItemPricingIngestionProcessor | streaming | validators (PRICE) |
| `catalog/enriched_product_message.json` | CfRetailItemIngestionProcessor | streaming | validators (ENRICHED) |
| `catalog/bv_ratings_message.json` | BVProductRatingsIngestionProcessor | streaming | presence Rating |
| `catalog/hcl_promo_message.json` | PromoMessageProcessor | streaming | presence Variant |
| `catalog/hcl_promo_price_message.json` | PromoPriceIngestionProcessor | streaming | presence Price |
| `catalog/hcl_category_message.json` | PromoCategoryMessageProcessor | streaming | presence Category |
| `catalog/category_product_association_message.json` | CategoryProductAssociationProcessor | streaming | presence CategoryProductAssociation |
| `catalog/variant_association_message.json` | FitVariantAssociationProcessor | streaming | presence VariantAssociation |
| `catalog/category_published_message.json` | CategoryPublishedProcessor | streaming | presence Category |
| `catalog/universe_item_full_load.txt` | UniverseItemFullLoadBatchProcessor | batch (full-load) | validators (UNIVERSE_ITEM) |
| `catalog/universe_price_full_load.csv` | UniversePriceFullLoadBatchProcessor | batch (full-load) | validators (PRICE) |
| `catalog/cf_retail_items.json` | CfRetailItemBatchProcessor | batch (full-load) | validators (ENRICHED) |
| `catalog/attributes.csv` | AttributeBatchProcessor | batch | presence Attribute |
| `catalog/badges_bestseller.csv` | ProductBadgesUpdateProcessor | batch | presence Product.isBestSeller=true |
| `catalog/badges_goingfast.csv` | ProductBadgesUpdateProcessor | batch | presence Product.isGoingFast=true |
| `catalog/badges_popular.csv` | ProductBadgesUpdateProcessor | batch | presence Product.isPopular=true |
| `catalog/onetime_load.csv` | OneTimeDataLoadProcessor | batch | presence SKU |

## Catalog · Bundles (critical, separate group)

| File | Feed / processor | Kind | Verify |
|------|------------------|------|--------|
| `catalog/bundle_readiness_note.txt` (context only) | UniverseItemBundleBatchProcessor | batch (**dispatch-only, Mongo-derived**) | presence Product.isBundle=true (golden `TMW_30AB`) |

`UniverseItemBundleBatchProcessor` consumes **no file** — it derives bundle SKU size/price/clearance and
bundle Product attributes from existing Config `Product`/`Variant`/`SKU`/`Price` + runtime `Inventory`,
gated on the components being `AVAILABLE`, and patches the results back to Config. The runner therefore
**skips the GCS upload** and only dispatches the batch workflow (`np-batch-scheduler.yaml`,
`processor=UniverseItemBundleBatchProcessor`, `environment=perf`, `version`) then polls the run. It must
run **after** the universe full load has created the bundle `Product`/`Variant`/`SKU` and
`Variant.productAssociation` links.

## Inventory

| File | Feed / processor | Kind | Verify |
|------|------------------|------|--------|
| `inventory/inv_full_feed_bopis.json` | InventoryFullFeedIngestionProcessor | streaming | presence inventory-config.Inventory |
| `inventory/availability_bopis_message.json` | InventoryAvailabilityFeedIngestionProcessor | streaming | presence inventory-runtime.Inventory |
| `inventory/inv_delta_message.json` | InventoryDeltaFeedIngestionProcessor | streaming | presence inventory-runtime.Inventory |
| `catalog/universe_item_message.json` (reused) | InventoryUniverseItemIngestionProcessor | streaming | presence inventory-config.Item |
| `inventory/inv_cdc_change.json` | RuntimeInventoryIngestionProcessor | streaming | presence inventory-runtime.Inventory |
| `inventory/inventory_full_load_bopis.csv` | InventoryFullLoadBatchProcessor | batch (full-load, **disabled**) | presence inventory-runtime.Inventory |
| `inventory/inventory_full_load_shipping_sts.csv` | InventoryFullLoadBatchProcessor | batch (full-load, **disabled**) | presence inventory-runtime.Inventory |

## Important notes

- **Full-load reconciliation jobs** (`requiresFullFeed=true`: universe item/price full load, CF retail
  enriched, inventory full load) deactivate/zero-out anything NOT in the uploaded file. The tiny files
  above are **format references only** — the runner blocks these scenarios unless a COMPLETE feed is
  uploaded. The inventory full-load batch scenarios are additionally **disabled** (production feed too large).
- **Cleanup** is opt-in (Perf-only). When enabled, the runner deletes just this scenario's golden id from
  the target collections before injecting, so the presence verify proves the current run wrote the data.
  Only scenarios whose injection recreates the full document declare `cleanupCollections`.
