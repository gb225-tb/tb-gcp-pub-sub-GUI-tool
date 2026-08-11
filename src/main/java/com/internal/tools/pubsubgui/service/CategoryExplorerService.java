package com.internal.tools.pubsubgui.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internal.tools.pubsubgui.config.ConstructorProperties;
import com.internal.tools.pubsubgui.config.MongoClientFactory;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Backs the Categories view: given a category (numeric HCL {@code catGroupId} / {@code hclCategoryId})
 * it reports how many products are in that category across three sources and returns sample data.
 *
 * <ul>
 *   <li><b>HCL</b> (DB2) — delegated to {@link HclBuildService} (CATGPENREL join, read-only).</li>
 *   <li><b>Catalog collections</b> (Mongo) — counts {@code CategoryProductAssociation} rows in the
 *       item-config and item-runtime databases after resolving the {@code Category} document.</li>
 *   <li><b>Constructor</b> — Browse API ({@code /browse/group_id/{id}}) {@code total_num_results}.</li>
 * </ul>
 *
 * <p>All operations are read-only.
 */
@Service
public class CategoryExplorerService {

    private static final Logger log = LoggerFactory.getLogger(CategoryExplorerService.class);

    private static final JsonWriterSettings PRETTY = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED)
            .indent(true)
            .build();

    /** Mongo logical databases holding the Category / association collections. */
    private static final String CONFIG_DB = "item-config";
    private static final String RUNTIME_DB = "item-runtime";
    private static final String CATEGORY_COLLECTION = "Category";
    private static final String ASSOCIATION_COLLECTION = "CategoryProductAssociation";
    private static final String STATUS_ACTIVE = "active";

    /** Inventory (runtime) collection used to decide whether a product's SKUs are in stock. */
    private static final String INVENTORY_RUNTIME_DB = "inventory-runtime";
    private static final String SKU_COLLECTION = "SKU";
    private static final String INVENTORY_COLLECTION = "Inventory";

    /** Cap on how many sample rows we ship back to the UI per source. */
    private static final int SAMPLE_LIMIT = 50;
    private static final int CONSTRUCTOR_RESULTS = 50;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(12);

    // ── Reconciliation caps (keep the read-only reconcile bounded and responsive) ──
    /** Max distinct product ids collected per source. */
    private static final int MAX_IDS = 5000;
    /** Constructor Browse page size / max pages while paginating the full id set. */
    private static final int CONSTRUCTOR_PAGE_SIZE = 200;
    private static final int CONSTRUCTOR_MAX_PAGES = 30;
    /** productId chunk size for the SKU lookup, and sku chunk size for the inventory {@code $in} query. */
    private static final int SKU_QUERY_CHUNK = 200;
    private static final int INVENTORY_QUERY_CHUNK = 200;
    /** Cap on the membership matrix / diff lists we ship to the UI (aggregate counts are exact). */
    private static final int MATRIX_CAP = 2000;
    private static final int DIFF_CAP = 1000;

    private final HclBuildService hclBuildService;
    private final MongoClientFactory mongo;
    private final ConstructorProperties constructorProperties;
    private final ObjectMapper mapper = new ObjectMapper();

    public CategoryExplorerService(HclBuildService hclBuildService, MongoClientFactory mongo,
                                   ConstructorProperties constructorProperties) {
        this.hclBuildService = hclBuildService;
        this.mongo = mongo;
        this.constructorProperties = constructorProperties;
    }

    // ── HCL (DB2) ──────────────────────────────────────────────────────────────

    /** Products in the category from HCL DB2 (count + capped list). Requires VPN. */
    public Map<String, Object> hcl(String env, String categoryId) {
        return hclBuildService.categoryProductsInHcl(env, categoryId);
    }

    // ── Catalog collections (Mongo) ──────────────────────────────────────────────

    /** Products (associations) in the category across the item-config and item-runtime databases. */
    public Map<String, Object> catalog(String env, String categoryId) {
        if (Objects.isNull(categoryId) || categoryId.isBlank()) {
            throw new IllegalArgumentException("categoryId is required");
        }
        String input = categoryId.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", env);
        out.put("categoryId", input);
        out.put("config", catalogForDatabase(env, CONFIG_DB, input));
        out.put("runtime", catalogForDatabase(env, RUNTIME_DB, input));
        return out;
    }

    private Map<String, Object> catalogForDatabase(String env, String database, String input) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("database", database);
        MongoDatabase db;
        try {
            db = mongo.database(env, database);
        } catch (RuntimeException e) {
            block.put("available", false);
            block.put("error", rootMessage(e));
            return block;
        }

        Document category = resolveCategory(db, input);
        String categoryKey = category != null ? String.valueOf(category.get("_id")) : input;
        block.put("categoryFound", category != null);
        if (category != null) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", categoryKey);
            summary.put("hclCategoryId", asString(category.get("hclCategoryId")));
            summary.put("name", asString(category.get("name")));
            summary.put("seoUrl", asString(category.get("seoUrl")));
            summary.put("type", asString(category.get("type")));
            summary.put("subType", asString(category.get("subType")));
            summary.put("status", asString(category.get("status")));
            block.put("category", summary);
            block.put("categoryJson", category.toJson(PRETTY));
        }

        MongoCollection<Document> assoc = db.getCollection(ASSOCIATION_COLLECTION);
        Bson catFilter = Filters.or(
                Filters.eq("categoryId", categoryKey),
                Filters.eq("categoryId", input),
                Filters.eq("hclCategoryId", input));
        Bson active = Filters.eq("status", STATUS_ACTIVE);

        Bson activeCat = Filters.and(catFilter, active);
        long total = assoc.countDocuments(catFilter);
        long totalActive = assoc.countDocuments(activeCat);
        long activeProducts = assoc.countDocuments(Filters.and(activeCat,
                Filters.exists("productId"), Filters.ne("productId", null)));
        long activeVariants = assoc.countDocuments(Filters.and(activeCat,
                Filters.exists("variantId"), Filters.ne("variantId", null)));

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("activeProducts", activeProducts);
        counts.put("activeVariants", activeVariants);
        counts.put("totalActive", totalActive);
        counts.put("total", total);
        block.put("counts", counts);

        List<Map<String, String>> sample = new ArrayList<>();
        for (Document doc : assoc.find(activeCat).limit(SAMPLE_LIMIT)) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", String.valueOf(doc.get("_id")));
            entry.put("json", doc.toJson(PRETTY));
            sample.add(entry);
        }
        block.put("associations", sample);
        block.put("associationsShown", sample.size());
        block.put("available", true);
        return block;
    }

    /** Resolves a Category document by hclCategoryId, then _id, then seoUrl. */
    private Document resolveCategory(MongoDatabase db, String input) {
        MongoCollection<Document> categories = db.getCollection(CATEGORY_COLLECTION);
        Document byHcl = categories.find(Filters.eq("hclCategoryId", input)).first();
        if (byHcl != null) {
            return byHcl;
        }
        Document byIdString = categories.find(Filters.eq("_id", input)).first();
        if (byIdString != null) {
            return byIdString;
        }
        if (ObjectId.isValid(input)) {
            Document byObjectId = categories.find(Filters.eq("_id", new ObjectId(input))).first();
            if (byObjectId != null) {
                return byObjectId;
            }
        }
        return categories.find(Filters.eq("seoUrl", input)).first();
    }

    /** True when the value is a 24-char hex string, i.e. already a Catalog Category ObjectId. */
    private static boolean looksLikeObjectId(String value) {
        return value != null && value.matches("[0-9a-fA-F]{24}");
    }

    /**
     * Resolves the Catalog {@code Category} document for a (typically HCL/numeric) category id by
     * checking the item-config database first, then item-runtime. Returns {@code null} when Mongo is
     * unreachable or the category is unknown, in which case the raw id is used for the Browse query.
     */
    private Document resolveCatalogCategory(String env, String input) {
        for (String database : new String[]{CONFIG_DB, RUNTIME_DB}) {
            try {
                Document category = resolveCategory(mongo.database(env, database), input);
                if (category != null) {
                    return category;
                }
            } catch (RuntimeException e) {
                log.debug("constructor group_id resolve | env={} db={} | {}", env, database, rootMessage(e));
            }
        }
        return null;
    }

    // ── Constructor (Browse API) ─────────────────────────────────────────────────

    /** Number of products in the category per Constructor's Browse API ({@code total_num_results}). */
    public Map<String, Object> constructor(String env, String categoryId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", env);
        out.put("categoryId", categoryId);

        ConstructorProperties.Environment cfg = constructorProperties.environment(env);
        if (cfg == null) {
            out.put("configured", false);
            out.put("reason", "No Constructor environment configured for '" + env + "'.");
            return out;
        }
        out.put("apiUrl", cfg.getApiUrl());
        if (!cfg.isConfigured()) {
            out.put("configured", false);
            out.put("reason", "Constructor index key not set — supply CONSTRUCTOR_" + env.toUpperCase()
                    + "_INDEX_KEY (and _AUTH_TOKEN) to enable category counts.");
            return out;
        }
        if (Objects.isNull(categoryId) || categoryId.isBlank()) {
            throw new IllegalArgumentException("categoryId is required");
        }
        out.put("configured", true);

        // Constructor indexes categories by the Catalog Category _id (a 24-hex ObjectId), not the
        // numeric HCL catgroup id. When the user typed the HCL id, resolve it to the Catalog _id via
        // Mongo so the Browse group_id matches; otherwise the query returns 0 for a valid key.
        String rawId = categoryId.trim();
        String groupId = rawId;
        if (!looksLikeObjectId(rawId)) {
            Document category = resolveCatalogCategory(env, rawId);
            if (category != null) {
                groupId = String.valueOf(category.get("_id"));
                out.put("resolvedFromCategoryId", rawId);
                out.put("categoryName", asString(category.get("name")));
            }
        }
        out.put("groupId", groupId);

        String encodedId = URLEncoder.encode(groupId, StandardCharsets.UTF_8);
        String base = cfg.getApiUrl() + cfg.getBrowsePath() + "/" + encodedId;
        String query = "key=" + URLEncoder.encode(cfg.getIndexKey(), StandardCharsets.UTF_8)
                + "&num_results_per_page=" + CONSTRUCTOR_RESULTS
                + "&i=" + UUID.randomUUID()
                + "&s=1"
                + "&c=catalog-tools";
        String url = base + "?" + query;

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET();
            if (!cfg.getAuthToken().isBlank()) {
                String basic = Base64.getEncoder().encodeToString(
                        (cfg.getAuthToken() + ":").getBytes(StandardCharsets.UTF_8));
                request.header("Authorization", "Basic " + basic);
            }
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .build()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString());

            // Never leak the index key back to the browser.
            out.put("requestUrl", base + "?num_results_per_page=" + CONSTRUCTOR_RESULTS);
            if (response.statusCode() != 200) {
                out.put("ok", false);
                out.put("statusCode", response.statusCode());
                out.put("reason", "Constructor Browse returned HTTP " + response.statusCode()
                        + summarizeError(response.body()));
                return out;
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode resp = root.path("response");
            out.put("ok", true);
            out.put("count", resp.path("total_num_results").asLong(0));
            List<Map<String, Object>> results = new ArrayList<>();
            for (JsonNode item : resp.path("results")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.path("data").path("id").asText(item.path("value").asText(null)));
                row.put("value", item.path("value").asText(null));
                results.add(row);
            }
            out.put("resultsShown", results.size());
            out.put("results", results);
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("reason", "Constructor Browse call failed: " + rootMessage(e));
            log.warn("constructor browse | env={} | category={} | {}", env, categoryId, rootMessage(e));
            return out;
        }
    }

    // ── Cross-source reconciliation ──────────────────────────────────────────────

    /**
     * Reconciles the product sets of the three sources for a category and cross-checks inventory.
     *
     * <p>The comparison key is the normalized product id (HCL part number == Catalog {@code productId}
     * == Constructor result id, upper-cased/trimmed). Constructor only surfaces products that have
     * in-stock inventory, so we also read the runtime {@code Inventory} collection (docs keyed
     * {@code <SKU>_<locationId>}) and mark a product in-stock when any of its SKUs has
     * {@code totalQuantity > 0} — which explains the Constructor delta and flags anomalies. Read-only.
     */
    public Map<String, Object> reconcile(String env, String categoryId) {
        if (Objects.isNull(categoryId) || categoryId.isBlank()) {
            throw new IllegalArgumentException("categoryId is required");
        }
        String input = categoryId.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", env);
        out.put("categoryId", input);

        // 1) HCL part numbers ------------------------------------------------------
        Set<String> hclIds = new LinkedHashSet<>();
        Map<String, Object> hclBlock = new LinkedHashMap<>();
        boolean hclAvailable = false;
        try {
            Map<String, Object> hcl = hclBuildService.categoryPartNumbersInHcl(env, input, MAX_IDS);
            boolean found = Boolean.TRUE.equals(hcl.get("found"));
            hclBlock.put("categoryFound", found);
            if (found) {
                @SuppressWarnings("unchecked")
                List<String> parts = (List<String>) hcl.getOrDefault("partNumbers", List.of());
                for (String p : parts) {
                    String n = norm(p);
                    if (n != null) {
                        hclIds.add(n);
                    }
                }
                Object count = hcl.get("count");
                hclBlock.put("count", count instanceof Number num ? num.longValue() : hclIds.size());
                hclBlock.put("distinct", hclIds.size());
                if (hcl.get("catGroupId") != null) {
                    hclBlock.put("catGroupId", hcl.get("catGroupId"));
                }
                hclAvailable = true;
            } else {
                hclBlock.put("reason", hcl.get("reason"));
                hclBlock.put("count", 0);
            }
            hclBlock.put("available", hclAvailable);
        } catch (RuntimeException e) {
            hclBlock.put("available", false);
            hclBlock.put("error", rootMessage(e));
        }
        out.put("hcl", hclBlock);

        // 2) Catalog product ids (item-config, active associations) ----------------
        Set<String> catalogIds = new LinkedHashSet<>();
        Map<String, Object> catalogBlock = new LinkedHashMap<>();
        boolean catalogAvailable = false;
        String catalogCategoryId = null;
        MongoDatabase configDb = null;
        try {
            configDb = mongo.database(env, CONFIG_DB);
            Document category = resolveCategory(configDb, input);
            String categoryKey = category != null ? String.valueOf(category.get("_id")) : input;
            if (category != null) {
                catalogCategoryId = categoryKey;
                catalogBlock.put("categoryName", asString(category.get("name")));
            }
            catalogBlock.put("categoryFound", category != null);
            catalogBlock.put("categoryId", categoryKey);

            MongoCollection<Document> assoc = configDb.getCollection(ASSOCIATION_COLLECTION);
            Bson catFilter = Filters.or(
                    Filters.eq("categoryId", categoryKey),
                    Filters.eq("categoryId", input),
                    Filters.eq("hclCategoryId", input));
            Bson activeProducts = Filters.and(catFilter, Filters.eq("status", STATUS_ACTIVE),
                    Filters.exists("productId"), Filters.ne("productId", null));
            for (String pid : assoc.distinct("productId", activeProducts, String.class)) {
                String n = norm(pid);
                if (n != null && catalogIds.size() < MAX_IDS) {
                    catalogIds.add(n);
                }
            }
            catalogBlock.put("database", CONFIG_DB);
            catalogBlock.put("count", catalogIds.size());
            catalogAvailable = true;
            catalogBlock.put("available", true);
        } catch (RuntimeException e) {
            catalogBlock.put("available", false);
            catalogBlock.put("error", rootMessage(e));
        }
        out.put("catalog", catalogBlock);

        // 3) Constructor product ids (paginated) -----------------------------------
        Set<String> constructorIds = new LinkedHashSet<>();
        Map<String, Object> ctorBlock = new LinkedHashMap<>();
        boolean constructorAvailable = fetchConstructorIds(env, input, catalogCategoryId, constructorIds, ctorBlock);
        out.put("constructor", ctorBlock);

        // 4) Inventory in-stock check (runtime Inventory, totalQuantity > 0) --------
        Set<String> productUniverse = new LinkedHashSet<>();
        productUniverse.addAll(hclIds);
        productUniverse.addAll(catalogIds);
        productUniverse.addAll(constructorIds);

        Set<String> inStockProducts = new LinkedHashSet<>();
        Map<String, Object> invBlock = new LinkedHashMap<>();
        boolean inventoryAvailable = false;
        try {
            if (configDb == null) {
                configDb = mongo.database(env, CONFIG_DB);
            }
            // sku _id -> normalized productId, for the category's product universe.
            Map<String, String> skuToProduct = new LinkedHashMap<>();
            MongoCollection<Document> skuCol = configDb.getCollection(SKU_COLLECTION);
            for (List<String> chunk : chunk(new ArrayList<>(productUniverse), SKU_QUERY_CHUNK)) {
                if (chunk.isEmpty()) {
                    continue;
                }
                for (Document sku : skuCol.find(Filters.in("productId", chunk))
                        .projection(Projections.include("_id", "productId"))) {
                    String skuId = asString(sku.get("_id"));
                    String pid = norm(asString(sku.get("productId")));
                    if (skuId != null && !skuId.isBlank() && pid != null) {
                        skuToProduct.put(skuId, pid);
                    }
                }
            }
            invBlock.put("skuCount", skuToProduct.size());

            // Case-tolerant sku -> product lookup: the inventory feed upper-cases the sku, while the
            // item-config SKU _id may be stored as-is. Query the indexed top-level `sku` field directly
            // (with totalQuantity > 0) instead of an _id regex — the regex-OR forced a full-document
            // scan and blew Firestore's 128 MiB in-memory limit.
            Map<String, String> skuKeyToProduct = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : skuToProduct.entrySet()) {
                skuKeyToProduct.put(e.getKey(), e.getValue());
                skuKeyToProduct.put(e.getKey().toUpperCase(), e.getValue());
            }
            MongoCollection<Document> invCol = mongo.database(env, INVENTORY_RUNTIME_DB)
                    .getCollection(INVENTORY_COLLECTION);
            Set<String> inStockSkus = new LinkedHashSet<>();
            List<String> skuKeys = new ArrayList<>(skuKeyToProduct.keySet());
            for (List<String> chunk : chunk(skuKeys, INVENTORY_QUERY_CHUNK)) {
                if (chunk.isEmpty()) {
                    continue;
                }
                Bson filter = Filters.and(Filters.gt("totalQuantity", 0), Filters.in("sku", chunk));
                for (Document doc : invCol.find(filter)
                        .projection(Projections.fields(Projections.include("sku"), Projections.excludeId()))) {
                    String sku = asString(doc.get("sku"));
                    if (sku == null) {
                        continue;
                    }
                    String pid = skuKeyToProduct.get(sku);
                    if (pid == null) {
                        pid = skuKeyToProduct.get(sku.toUpperCase());
                    }
                    if (pid != null) {
                        inStockSkus.add(sku);
                        inStockProducts.add(pid);
                    }
                }
            }
            invBlock.put("database", INVENTORY_RUNTIME_DB);
            invBlock.put("inStockSkuCount", inStockSkus.size());
            invBlock.put("inStockProductCount", inStockProducts.size());
            inventoryAvailable = true;
            invBlock.put("available", true);
        } catch (RuntimeException e) {
            invBlock.put("available", false);
            invBlock.put("error", rootMessage(e));
        }
        out.put("inventory", invBlock);

        // 5) Reconciliation summary -----------------------------------------------
        out.put("summary", buildSummary(hclIds, catalogIds, constructorIds, inStockProducts, productUniverse,
                hclAvailable, catalogAvailable, constructorAvailable, inventoryAvailable));
        return out;
    }

    /**
     * Builds the membership matrix (one row per product with per-source booleans) plus the aggregate
     * counts and the capped "left out per source" and inventory-anomaly lists.
     */
    private Map<String, Object> buildSummary(Set<String> hcl, Set<String> catalog, Set<String> constructor,
                                             Set<String> inStock, Set<String> universe,
                                             boolean hclOk, boolean catalogOk, boolean constructorOk,
                                             boolean inventoryOk) {
        Set<String> commonAll = intersect(hcl, catalog, constructor);
        Set<String> missingFromHcl = minus(union(catalog, constructor), hcl);
        Set<String> missingFromCatalog = minus(union(hcl, constructor), catalog);
        Set<String> missingFromConstructor = minus(union(hcl, catalog), constructor);
        Set<String> inStockNotInConstructor = minus(inStock, constructor);
        Set<String> constructorNotInStock = minus(constructor, inStock);

        List<Map<String, Object>> matrix = new ArrayList<>();
        for (String id : new TreeSet<>(universe)) {
            if (matrix.size() >= MATRIX_CAP) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("hcl", hcl.contains(id));
            row.put("catalog", catalog.contains(id));
            row.put("constructor", constructor.contains(id));
            row.put("inStock", inStock.contains(id));
            matrix.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hclAvailable", hclOk);
        summary.put("catalogAvailable", catalogOk);
        summary.put("constructorAvailable", constructorOk);
        summary.put("inventoryAvailable", inventoryOk);
        summary.put("unionCount", universe.size());
        summary.put("commonAllCount", commonAll.size());
        summary.put("commonAll", cap(commonAll, DIFF_CAP));
        summary.put("missingFromHclCount", missingFromHcl.size());
        summary.put("missingFromHcl", cap(missingFromHcl, DIFF_CAP));
        summary.put("missingFromCatalogCount", missingFromCatalog.size());
        summary.put("missingFromCatalog", cap(missingFromCatalog, DIFF_CAP));
        summary.put("missingFromConstructorCount", missingFromConstructor.size());
        summary.put("missingFromConstructor", cap(missingFromConstructor, DIFF_CAP));
        summary.put("inStockNotInConstructorCount", inStockNotInConstructor.size());
        summary.put("inStockNotInConstructor", cap(inStockNotInConstructor, DIFF_CAP));
        summary.put("constructorNotInStockCount", constructorNotInStock.size());
        summary.put("constructorNotInStock", cap(constructorNotInStock, DIFF_CAP));
        summary.put("matrixShown", matrix.size());
        summary.put("matrix", matrix);
        return summary;
    }

    /**
     * Collects the full Constructor product id set for a category by paginating the Browse API. Populates
     * {@code block} with availability / count metadata. Returns {@code true} when the source was usable.
     */
    private boolean fetchConstructorIds(String env, String input, String catalogCategoryId,
                                        Set<String> ids, Map<String, Object> block) {
        ConstructorProperties.Environment cfg = constructorProperties.environment(env);
        if (cfg == null || !cfg.isConfigured()) {
            block.put("available", false);
            block.put("reason", cfg == null
                    ? "No Constructor environment configured for '" + env + "'."
                    : "Constructor index key not set for '" + env + "'.");
            return false;
        }

        String groupId = catalogCategoryId != null ? catalogCategoryId
                : (looksLikeObjectId(input) ? input : null);
        if (groupId == null) {
            Document category = resolveCatalogCategory(env, input);
            if (category != null) {
                groupId = String.valueOf(category.get("_id"));
            }
        }
        if (groupId == null) {
            groupId = input;
        }
        block.put("groupId", groupId);

        String encodedId = URLEncoder.encode(groupId, StandardCharsets.UTF_8);
        String base = cfg.getApiUrl() + cfg.getBrowsePath() + "/" + encodedId;
        long total = -1;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            for (int page = 1; page <= CONSTRUCTOR_MAX_PAGES; page++) {
                String query = "key=" + URLEncoder.encode(cfg.getIndexKey(), StandardCharsets.UTF_8)
                        + "&num_results_per_page=" + CONSTRUCTOR_PAGE_SIZE
                        + "&page=" + page
                        + "&i=" + UUID.randomUUID()
                        + "&s=1"
                        + "&c=catalog-tools";
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "?" + query))
                        .timeout(HTTP_TIMEOUT)
                        .header("Accept", "application/json")
                        .GET();
                if (!cfg.getAuthToken().isBlank()) {
                    String basic = Base64.getEncoder().encodeToString(
                            (cfg.getAuthToken() + ":").getBytes(StandardCharsets.UTF_8));
                    request.header("Authorization", "Basic " + basic);
                }
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    block.put("available", false);
                    block.put("reason", "Constructor Browse returned HTTP " + response.statusCode()
                            + summarizeError(response.body()));
                    return false;
                }
                JsonNode resp = mapper.readTree(response.body()).path("response");
                if (total < 0) {
                    total = resp.path("total_num_results").asLong(0);
                }
                JsonNode results = resp.path("results");
                if (!results.isArray() || results.isEmpty()) {
                    break;
                }
                for (JsonNode item : results) {
                    String id = item.path("data").path("id").asText(item.path("value").asText(null));
                    String n = norm(id);
                    if (n != null && ids.size() < MAX_IDS) {
                        ids.add(n);
                    }
                }
                if (ids.size() >= total || ids.size() >= MAX_IDS) {
                    break;
                }
            }
            block.put("available", true);
            block.put("count", total < 0 ? ids.size() : total);
            block.put("distinct", ids.size());
            return true;
        } catch (Exception e) {
            block.put("available", false);
            block.put("reason", "Constructor Browse call failed: " + rootMessage(e));
            log.warn("constructor reconcile | env={} | category={} | {}", env, input, rootMessage(e));
            return false;
        }
    }

    // ── set helpers ──────────────────────────────────────────────────────────────

    private static String norm(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t.toUpperCase();
    }

    @SafeVarargs
    private static Set<String> intersect(Set<String>... sets) {
        if (sets.length == 0) {
            return new LinkedHashSet<>();
        }
        Set<String> result = new LinkedHashSet<>(sets[0]);
        for (int i = 1; i < sets.length; i++) {
            result.retainAll(sets[i]);
        }
        return result;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new LinkedHashSet<>(a);
        result.addAll(b);
        return result;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> result = new LinkedHashSet<>(a);
        result.removeAll(b);
        return result;
    }

    private static List<String> cap(Set<String> values, int max) {
        List<String> sorted = new ArrayList<>(new TreeSet<>(values));
        return sorted.size() > max ? new ArrayList<>(sorted.subList(0, max)) : sorted;
    }

    private static <T> List<List<T>> chunk(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return chunks;
    }

    private String summarizeError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.length() > 200 ? body.substring(0, 200) + "…" : body;
        return " — " + trimmed.replaceAll("\\s+", " ").trim();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (Objects.nonNull(cur.getCause()) && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (Objects.isNull(msg) || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }
}
