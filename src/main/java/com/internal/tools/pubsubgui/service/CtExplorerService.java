package com.internal.tools.pubsubgui.service;

import com.commercetools.api.client.ProjectApiRoot;
import com.commercetools.api.models.graph_ql.GraphQLRequest;
import com.commercetools.api.models.graph_ql.GraphQLResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internal.tools.pubsubgui.config.CtProperties;
import com.internal.tools.pubsubgui.ct.CtClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only commercetools (CT) explorer. Given a catalog {@code productId}, fetches the product
 * ({@code tb-product-type}, CT key = productId) and all its color-variant products
 * ({@code tb-variant-sku-type}, discovered via the {@code productKey} variant attribute), whose CT
 * variants are the SKUs (+prices). Assembles a Product -&gt; Variants -&gt; SKUs tree.
 *
 * <p>Reads use GraphQL on {@code masterData.staged}, locale {@code en-US} — the same surface the
 * reference project (tb-catalog-data-processor) uses. No mutations are performed.
 */
@Service
public class CtExplorerService {

    private static final Logger log = LoggerFactory.getLogger(CtExplorerService.class);

    private static final String LOCALE = "en-US";
    /** Catalog ids are part-number style; reject anything outside this set for the CT predicate. */
    private static final String SAFE_ID = "[A-Za-z0-9_.\\-]+";

    private static final String PRODUCT_BY_KEY_QUERY = """
        query Product($key: String!) {
          product(key: $key) {
            id
            key
            version
            masterData {
              published
              staged {
                nameAllLocales { locale value }
                descriptionAllLocales { locale value }
                categories { id }
                masterVariant { sku images { url } }
                attributesRaw { name value }
              }
            }
          }
        }
        """;

    private static final String VARIANT_PRODUCTS_QUERY = """
        query Variants($where: String!) {
          products(where: $where, limit: 100) {
            results {
              id
              key
              version
              masterData {
                published
                staged {
                  nameAllLocales { locale value }
                  allVariants {
                    sku
                    attributesRaw { name value }
                    prices {
                      country
                      value { centAmount currencyCode fractionDigits }
                    }
                  }
                }
              }
            }
          }
        }
        """;

    private static final String CATEGORIES_BY_IDS_QUERY = """
        query Categories($where: String!) {
          categories(where: $where, limit: 500) {
            results {
              id
              key
              externalId
              nameAllLocales { locale value }
            }
          }
        }
        """;

    private final CtProperties properties;
    private final CtClientFactory clientFactory;
    private final ObjectMapper mapper = new ObjectMapper();

    public CtExplorerService(CtProperties properties, CtClientFactory clientFactory) {
        this.properties = properties;
        this.clientFactory = clientFactory;
    }

    // ── Status probe (CT auth / connectivity) ──────────────────────────────────

    public Map<String, Object> probe(String envName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", envName);
        CtProperties.Environment env = properties.environment(envName);
        if (Objects.isNull(env)) {
            out.put("connected", false);
            out.put("error", "Unknown CT environment: " + envName);
            return out;
        }
        out.put("projectKey", env.getProjectKey());
        if (!env.isConfigured()) {
            out.put("connected", false);
            out.put("error", "CT credentials not configured for " + envName
                    + " (set CT_" + envName.toUpperCase() + "_PROJECT_KEY / CLIENT_ID / CLIENT_SECRET).");
            return out;
        }
        // Probe against the OAuth token endpoint (scope-independent): a successful client_credentials
        // grant proves the credentials are valid and CT is reachable. This avoids false negatives on
        // clients that lack view_project_settings / view_products for the API endpoints.
        try {
            String tokenUrl = CtClientFactory.tokenUrl(env.getAuthUrl());
            String basic = Base64.getEncoder().encodeToString(
                    (env.getClientId() + ":" + env.getClientSecret()).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                out.put("connected", true);
                JsonNode body = mapper.readTree(response.body());
                String scope = body.path("scope").asText(null);
                if (scope != null && !scope.isBlank()) {
                    out.put("scope", scope);
                }
            } else {
                out.put("connected", false);
                out.put("error", oauthError(response.statusCode(), response.body()));
                log.warn("ct probe | env={} | down | http {} | {}", envName, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            out.put("connected", false);
            out.put("error", rootMessage(e));
            log.warn("ct probe | env={} | down | {}", envName, rootMessage(e));
        }
        return out;
    }

    /** Extracts a human-readable message from a CT OAuth error response. */
    private String oauthError(int statusCode, String body) {
        try {
            JsonNode node = mapper.readTree(body);
            String desc = node.path("error_description").asText(null);
            String err = node.path("error").asText(null);
            if (desc != null && !desc.isBlank()) {
                return "HTTP " + statusCode + ": " + desc;
            }
            if (err != null && !err.isBlank()) {
                return "HTTP " + statusCode + ": " + err;
            }
        } catch (Exception ignored) {
            // fall through to raw body
        }
        return "HTTP " + statusCode + (body == null || body.isBlank() ? "" : ": " + body);
    }

    // ── Build (fetch product + variant products, assemble tree) ─────────────────

    public Map<String, Object> buildForProductId(String envName, String productId) {
        CtProperties.Environment env = properties.environment(envName);
        if (Objects.isNull(env)) {
            throw new IllegalArgumentException("Unknown CT environment: " + envName);
        }
        if (Objects.isNull(productId) || productId.isBlank()) {
            throw new IllegalArgumentException("productId is required");
        }
        String pid = productId.trim();
        if (!pid.matches(SAFE_ID)) {
            throw new IllegalArgumentException("productId contains unsupported characters");
        }

        ProjectApiRoot apiRoot = clientFactory.clientFor(env);
        try {
            JsonNode product = queryProductByKey(apiRoot, pid);
            if (product.isMissingNode() || product.isNull() || !product.hasNonNull("id")) {
                return notFound(envName, pid, "No CT product with key '" + pid + "' (tb-product-type).");
            }
            // Prefer the parent's colorVariants references (the authoritative parent→children link);
            // fall back to the productKey attribute predicate when colorVariants is empty.
            JsonNode staged = product.path("masterData").path("staged");
            List<String> colorVariantIds = colorVariantIds(staged);
            JsonNode variantResults = colorVariantIds.isEmpty()
                    ? queryVariantProductsByProductKey(apiRoot, pid)
                    : queryVariantProductsByIds(apiRoot, colorVariantIds);
            return assemble(apiRoot, envName, pid, env.getProjectKey(), product, variantResults);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("CT fetch failed for '" + pid + "': " + rootMessage(e), e);
        }
    }

    private JsonNode queryProductByKey(ProjectApiRoot apiRoot, String key) {
        GraphQLRequest request = GraphQLRequest.builder()
                .query(PRODUCT_BY_KEY_QUERY)
                .variables(b -> b.addValue("key", key))
                .build();
        return execute(apiRoot, request).path("product");
    }

    private JsonNode queryVariantProductsByProductKey(ProjectApiRoot apiRoot, String productId) {
        String where = "masterData(staged(variants(attributes(name=\"productKey\" and value=\"" + productId + "\")))) "
                + "or masterData(staged(masterVariant(attributes(name=\"productKey\" and value=\"" + productId + "\"))))";
        return execute(apiRoot, GraphQLRequest.builder()
                .query(VARIANT_PRODUCTS_QUERY)
                .variables(b -> b.addValue("where", where))
                .build())
                .path("products").path("results");
    }

    /** Fetches the color-variant products by their CT ids (from the parent's colorVariants refs). */
    private JsonNode queryVariantProductsByIds(ProjectApiRoot apiRoot, List<String> ids) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                in.append(", ");
            }
            in.append('"').append(ids.get(i).replace("\"", "")).append('"');
        }
        String where = "id in (" + in + ")";
        return execute(apiRoot, GraphQLRequest.builder()
                .query(VARIANT_PRODUCTS_QUERY)
                .variables(b -> b.addValue("where", where))
                .build())
                .path("products").path("results");
    }

    /**
     * Resolves category references (id -&gt; {id, name, key, externalId}) for every referenced
     * category. Best-effort: if the API client lacks {@code view_categories}, the ids are still shown.
     */
    private Map<String, Object> resolveCategories(ProjectApiRoot apiRoot, Collection<String> ids) {
        Map<String, Object> byId = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return byId;
        }
        StringBuilder in = new StringBuilder();
        boolean first = true;
        for (String id : ids) {
            if (!first) {
                in.append(", ");
            }
            in.append('"').append(id.replace("\"", "")).append('"');
            first = false;
        }
        String where = "id in (" + in + ")";
        try {
            JsonNode results = execute(apiRoot, GraphQLRequest.builder()
                    .query(CATEGORIES_BY_IDS_QUERY)
                    .variables(b -> b.addValue("where", where))
                    .build())
                    .path("categories").path("results");
            if (results.isArray()) {
                for (JsonNode c : results) {
                    String id = text(c, "id");
                    if (id == null) {
                        continue;
                    }
                    Map<String, Object> category = new LinkedHashMap<>();
                    category.put("id", id);
                    category.put("name", localeValue(c.path("nameAllLocales")));
                    category.put("key", text(c, "key"));
                    category.put("externalId", text(c, "externalId"));
                    byId.put(id, category);
                }
            }
        } catch (Exception e) {
            // Category resolution is optional enrichment — degrade gracefully to bare ids.
            log.warn("ct category resolution skipped | {} id(s) | {}", ids.size(), rootMessage(e));
        }
        return byId;
    }

    /** Adds category ids from an attribute named {@code categories} (a set of category references). */
    private static void collectCategoryRefIds(JsonNode attributesRaw, Set<String> out) {
        out.addAll(refIdList(attributesRaw, "categories"));
    }

    /** Returns the referenced ids from a reference/reference-set attribute by name (or empty). */
    private static List<String> refIdList(JsonNode attributesRaw, String attributeName) {
        List<String> ids = new ArrayList<>();
        for (JsonNode attribute : attributesRaw) {
            if (attributeName.equals(attribute.path("name").asText())) {
                JsonNode value = attribute.path("value");
                if (value.isArray()) {
                    for (JsonNode ref : value) {
                        String id = ref.path("id").asText(null);
                        if (id != null && !id.isBlank()) {
                            ids.add(id);
                        }
                    }
                } else if (value.isObject() && value.hasNonNull("id")) {
                    ids.add(value.path("id").asText());
                }
            }
        }
        return ids;
    }

    /** Extracts the product ids referenced by the parent's {@code colorVariants} set attribute. */
    private static List<String> colorVariantIds(JsonNode staged) {
        List<String> ids = new ArrayList<>();
        for (JsonNode attribute : staged.path("attributesRaw")) {
            if ("colorVariants".equals(attribute.path("name").asText())) {
                JsonNode value = attribute.path("value");
                if (value.isArray()) {
                    for (JsonNode ref : value) {
                        String id = ref.path("id").asText(null);
                        if (id != null && !id.isBlank()) {
                            ids.add(id);
                        }
                    }
                }
            }
        }
        return ids;
    }

    private JsonNode execute(ProjectApiRoot apiRoot, GraphQLRequest request) {
        GraphQLResponse response = apiRoot.graphql().post(request).executeBlocking().getBody();
        if (Objects.isNull(response)) {
            throw new IllegalStateException("CT GraphQL returned an empty response");
        }
        if (Objects.nonNull(response.getErrors()) && !response.getErrors().isEmpty()) {
            throw new IllegalStateException(formatGraphQlErrors(response));
        }
        return mapper.valueToTree(response.getData());
    }

    /** Turns CT GraphQL errors into a concise, actionable message (special-cases missing scopes). */
    private String formatGraphQlErrors(GraphQLResponse response) {
        List<String> messages = new ArrayList<>();
        boolean insufficientScope = false;
        for (var error : response.getErrors()) {
            String message = error.getMessage();
            if (message != null) {
                messages.add(message);
                if (message.toLowerCase().contains("insufficient scope")
                        || message.toLowerCase().contains("insufficient_scope")) {
                    insufficientScope = true;
                }
            }
        }
        String joined = messages.isEmpty() ? response.getErrors().toString() : String.join("; ", messages);
        if (insufficientScope) {
            return "commercetools rejected the read: " + joined
                    + " — the API client is missing the required product-read scope. In the CT "
                    + "Merchant Center, grant this API client 'view_products' (and 'view_published_products' "
                    + "for published data), then update the credentials.";
        }
        return "commercetools GraphQL error: " + joined;
    }

    // ── Assembly ────────────────────────────────────────────────────────────────

    private Map<String, Object> assemble(ProjectApiRoot apiRoot, String envName, String productId,
                                         String projectKey, JsonNode productNode, JsonNode variantResults) {
        JsonNode staged = productNode.path("masterData").path("staged");

        // Accumulate every category id referenced across the product and its SKUs so we can resolve
        // them (id -> name/key) in a single follow-up query.
        Set<String> categoryIds = new LinkedHashSet<>();

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("id", text(productNode, "id"));
        product.put("key", text(productNode, "key"));
        product.put("version", productNode.path("version").asLong());
        product.put("published", productNode.path("masterData").path("published").asBoolean(false));
        product.put("name", localeValue(staged.path("nameAllLocales")));
        product.put("description", localeValue(staged.path("descriptionAllLocales")));
        List<String> productCategories = idList(staged.path("categories"));
        product.put("categories", productCategories);
        categoryIds.addAll(productCategories);
        collectCategoryRefIds(staged.path("attributesRaw"), categoryIds);
        product.put("images", imageUrls(staged.path("masterVariant").path("images")));
        product.put("attributes", attributes(staged.path("attributesRaw")));
        product.put("raw", toPlain(productNode));

        List<Map<String, Object>> variants = new ArrayList<>();
        int skuCount = 0;
        if (variantResults.isArray()) {
            for (JsonNode vp : variantResults) {
                JsonNode vStaged = vp.path("masterData").path("staged");
                List<Map<String, Object>> skus = new ArrayList<>();
                for (JsonNode variant : vStaged.path("allVariants")) {
                    collectCategoryRefIds(variant.path("attributesRaw"), categoryIds);
                    Map<String, Object> sku = new LinkedHashMap<>();
                    sku.put("sku", text(variant, "sku"));
                    sku.put("attributes", attributes(variant.path("attributesRaw")));
                    sku.put("categories", refIdList(variant.path("attributesRaw"), "categories"));
                    sku.put("prices", prices(variant.path("prices")));
                    sku.put("raw", toPlain(variant));
                    skus.add(sku);
                }
                skus.sort((a, b) -> String.valueOf(a.get("sku")).compareTo(String.valueOf(b.get("sku"))));
                skuCount += skus.size();

                Map<String, Object> variant = new LinkedHashMap<>();
                variant.put("id", text(vp, "id"));
                variant.put("variantId", text(vp, "key"));
                variant.put("version", vp.path("version").asLong());
                variant.put("published", vp.path("masterData").path("published").asBoolean(false));
                variant.put("name", localeValue(vStaged.path("nameAllLocales")));
                variant.put("skus", skus);
                variant.put("raw", toPlain(vp));
                variants.add(variant);
            }
            variants.sort((a, b) -> String.valueOf(a.get("variantId")).compareTo(String.valueOf(b.get("variantId"))));
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("variant", variants.size());
        counts.put("sku", skuCount);
        counts.put("category", categoryIds.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", envName);
        out.put("productId", productId);
        out.put("projectKey", projectKey);
        out.put("found", true);
        out.put("product", product);
        out.put("variants", variants);
        out.put("categoriesById", resolveCategories(apiRoot, categoryIds));
        out.put("counts", counts);
        return out;
    }

    private Map<String, Object> notFound(String envName, String productId, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", envName);
        out.put("productId", productId);
        out.put("found", false);
        out.put("reason", reason);
        return out;
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────────

    /** Picks the {@code en-US} value from a {@code *AllLocales [{locale,value}]} list (else first). */
    private static String localeValue(JsonNode allLocales) {
        if (!allLocales.isArray() || allLocales.isEmpty()) {
            return null;
        }
        String first = null;
        for (JsonNode entry : allLocales) {
            String value = entry.path("value").asText(null);
            if (first == null) {
                first = value;
            }
            if (LOCALE.equals(entry.path("locale").asText())) {
                return value;
            }
        }
        return first;
    }

    /** attributesRaw [{name,value}] -&gt; ordered map name -&gt; plain value. */
    private Map<String, Object> attributes(JsonNode attributesRaw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (attributesRaw.isArray()) {
            for (JsonNode a : attributesRaw) {
                String name = a.path("name").asText(null);
                if (name != null) {
                    out.put(name, toPlain(a.path("value")));
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> prices(JsonNode pricesNode) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (pricesNode.isArray()) {
            for (JsonNode p : pricesNode) {
                JsonNode value = p.path("value");
                long centAmount = value.path("centAmount").asLong();
                int fractionDigits = value.path("fractionDigits").asInt(2);
                Map<String, Object> price = new LinkedHashMap<>();
                price.put("country", p.path("country").asText(null));
                price.put("currency", value.path("currencyCode").asText(null));
                price.put("centAmount", centAmount);
                price.put("fractionDigits", fractionDigits);
                price.put("amount", BigDecimal.valueOf(centAmount).movePointLeft(fractionDigits).toPlainString());
                out.add(price);
            }
        }
        return out;
    }

    private static List<String> idList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                String id = n.path("id").asText(null);
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static List<String> imageUrls(JsonNode images) {
        List<String> out = new ArrayList<>();
        if (images.isArray()) {
            for (JsonNode img : images) {
                String url = img.path("url").asText(null);
                if (url != null) {
                    out.add(url);
                }
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /** Converts a JsonNode into plain Java maps/lists/scalars for clean JSON serialization. */
    private Object toPlain(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return mapper.convertValue(node, Object.class);
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
