package com.internal.tools.pubsubgui.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internal.tools.pubsubgui.config.ConstructorProperties;
import com.internal.tools.pubsubgui.config.MongoClientFactory;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** Cap on how many sample rows we ship back to the UI per source. */
    private static final int SAMPLE_LIMIT = 50;
    private static final int CONSTRUCTOR_RESULTS = 50;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(12);

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
