package com.internal.tools.pubsubgui.service;

import com.commercetools.api.client.ProjectApiRoot;
import com.commercetools.api.models.graph_ql.GraphQLRequest;
import com.commercetools.api.models.graph_ql.GraphQLResponse;
import com.commercetools.api.models.product.Product;
import com.commercetools.api.models.product.ProductSetAttributeInAllVariantsActionBuilder;
import com.commercetools.api.models.product.ProductUnpublishActionBuilder;
import com.commercetools.api.models.product.ProductUpdateBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internal.tools.pubsubgui.config.CtProperties;
import com.internal.tools.pubsubgui.ct.CtClientFactory;
import com.internal.tools.pubsubgui.model.CtCleanupRequest;
import io.vrap.rmf.base.client.error.ConcurrentModificationException;
import io.vrap.rmf.base.client.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Destructive commercetools clean-up for a productId: deletes the master {@code tb-product-type}
 * product and/or its color-variant {@code tb-variant-sku-type} products (each of which carries the
 * SKUs + embedded prices — deleting a product removes those in one call).
 *
 * <p>Scan-first: {@link #scan} reports what exists (product + variants + sku counts) so the UI can
 * show counts and let the operator choose before {@link #delete} removes anything. A published
 * product is unpublished before deletion (CT forbids deleting a published product); reference
 * integrity is respected by deleting the master first (which clears its {@code colorVariants}
 * references) or, when the master is kept, by first removing the deleted ids from that reference set.
 */
@Service
public class CtCleanupService {

    private static final Logger log = LoggerFactory.getLogger(CtCleanupService.class);

    private static final String SAFE_ID = "[A-Za-z0-9_.\\-]+";
    private static final int MAX_CONFLICT_RETRIES = 3;

    private static final String SCAN_PRODUCT_QUERY = """
        query Scan($key: String!) {
          product(key: $key) {
            id
            key
            version
            masterData {
              published
              staged { attributesRaw { name value } }
            }
          }
        }
        """;

    private static final String SCAN_VARIANTS_QUERY = """
        query ScanVariants($where: String!) {
          products(where: $where, limit: 100) {
            results {
              id
              key
              version
              masterData {
                published
                staged { allVariants { sku } }
              }
            }
          }
        }
        """;

    private final CtProperties properties;
    private final CtClientFactory clientFactory;
    private final ObjectMapper mapper = new ObjectMapper();

    public CtCleanupService(CtProperties properties, CtClientFactory clientFactory) {
        this.properties = properties;
        this.clientFactory = clientFactory;
    }

    // ── Scan ─────────────────────────────────────────────────────────────────────

    public Map<String, Object> scan(String envName, String productId) {
        CtProperties.Environment env = resolveEnv(envName);
        String pid = requireSafeProductId(productId);
        ProjectApiRoot apiRoot = clientFactory.clientFor(env);

        JsonNode product = execute(apiRoot, GraphQLRequest.builder()
                .query(SCAN_PRODUCT_QUERY)
                .variables(b -> b.addValue("key", pid))
                .build()).path("product");

        if (product.isMissingNode() || product.isNull() || !product.hasNonNull("id")) {
            Map<String, Object> out = baseResult(envName, pid);
            out.put("found", false);
            out.put("reason", "No CT product with key '" + pid + "'.");
            return out;
        }

        JsonNode staged = product.path("masterData").path("staged");
        List<String> colorVariantIds = colorVariantIds(staged);

        List<Map<String, Object>> variants = new ArrayList<>();
        int skuTotal = 0;
        if (!colorVariantIds.isEmpty()) {
            JsonNode results = execute(apiRoot, GraphQLRequest.builder()
                    .query(SCAN_VARIANTS_QUERY)
                    .variables(b -> b.addValue("where", idInClause(colorVariantIds)))
                    .build()).path("products").path("results");
            if (results.isArray()) {
                for (JsonNode vp : results) {
                    int skus = vp.path("masterData").path("staged").path("allVariants").size();
                    skuTotal += skus;
                    Map<String, Object> variant = new LinkedHashMap<>();
                    variant.put("id", text(vp, "id"));
                    variant.put("variantId", text(vp, "key"));
                    variant.put("version", vp.path("version").asLong());
                    variant.put("published", vp.path("masterData").path("published").asBoolean(false));
                    variant.put("skuCount", skus);
                    variants.add(variant);
                }
                variants.sort((a, b) -> String.valueOf(a.get("variantId")).compareTo(String.valueOf(b.get("variantId"))));
            }
        }

        Map<String, Object> productInfo = new LinkedHashMap<>();
        productInfo.put("id", text(product, "id"));
        productInfo.put("key", text(product, "key"));
        productInfo.put("version", product.path("version").asLong());
        productInfo.put("published", product.path("masterData").path("published").asBoolean(false));

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("product", 1);
        counts.put("variant", variants.size());
        counts.put("sku", skuTotal);

        Map<String, Object> out = baseResult(envName, pid);
        out.put("found", true);
        out.put("product", productInfo);
        out.put("variants", variants);
        out.put("colorVariantIds", colorVariantIds);
        out.put("counts", counts);
        return out;
    }

    // ── Delete ────────────────────────────────────────────────────────────────────

    public Map<String, Object> delete(CtCleanupRequest request) {
        CtProperties.Environment env = resolveEnv(request.env());
        String pid = requireSafeProductId(request.productId());
        ProjectApiRoot apiRoot = clientFactory.clientFor(env);

        // Re-scan to get the master id + the authoritative colorVariants set (versions are fetched
        // fresh at delete time, so a slightly stale scan is fine).
        Map<String, Object> scan = scan(request.env(), pid);
        if (!Boolean.TRUE.equals(scan.get("found"))) {
            Map<String, Object> out = baseResult(request.env(), pid);
            out.put("totalDeleted", 0);
            out.put("results", List.of());
            out.put("reason", scan.get("reason"));
            return out;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> product = (Map<String, Object>) scan.get("product");
        String masterId = String.valueOf(product.get("id"));
        String masterKey = String.valueOf(product.get("key"));
        @SuppressWarnings("unchecked")
        List<String> allColorVariantIds = (List<String>) scan.getOrDefault("colorVariantIds", List.of());

        List<String> variantIds = new ArrayList<>(new LinkedHashSet<>(request.variantIds()));
        List<Map<String, Object>> results = new ArrayList<>();

        if (request.deleteProduct()) {
            // Deleting the master first removes its colorVariants references, so the selected child
            // variant products become freely deletable.
            results.add(deleteProduct(apiRoot, "product", masterId, masterKey));
            for (String vid : variantIds) {
                results.add(deleteProduct(apiRoot, "variant", vid, vid));
            }
        } else if (!variantIds.isEmpty()) {
            // Keep the master: strip the to-be-deleted ids from its colorVariants set first so CT does
            // not reject the child deletes with a reference-exists error.
            Set<String> remaining = new LinkedHashSet<>(allColorVariantIds);
            remaining.removeAll(variantIds);
            try {
                setColorVariants(apiRoot, masterId, remaining);
            } catch (Exception e) {
                log.warn("ct cleanup | env={} | failed to update parent colorVariants | {}",
                        request.env(), rootMessage(e));
            }
            for (String vid : variantIds) {
                results.add(deleteProduct(apiRoot, "variant", vid, vid));
            }
        }

        int totalDeleted = 0;
        for (Map<String, Object> r : results) {
            if (Boolean.TRUE.equals(r.get("deleted"))) {
                totalDeleted++;
            }
        }

        Map<String, Object> out = baseResult(request.env(), pid);
        out.put("totalDeleted", totalDeleted);
        out.put("results", results);
        return out;
    }

    /** Unpublishes (if published) and deletes one product by id, with a 409 conflict retry loop. */
    private Map<String, Object> deleteProduct(ProjectApiRoot apiRoot, String type, String id, String label) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("id", id);
        result.put("label", label);
        try {
            int conflict = 0;
            while (true) {
                try {
                    Product current = apiRoot.products().withId(id).get().executeBlocking().getBody();
                    long version = current.getVersion();
                    boolean published = current.getMasterData() != null
                            && Boolean.TRUE.equals(current.getMasterData().getPublished());
                    if (published) {
                        Product after = apiRoot.products().withId(id)
                                .post(ProductUpdateBuilder.of()
                                        .version(version)
                                        .actions(ProductUnpublishActionBuilder.of().build())
                                        .build())
                                .executeBlocking().getBody();
                        version = after.getVersion();
                    }
                    apiRoot.products().withId(id).delete().withVersion(version).executeBlocking();
                    result.put("deleted", true);
                    return result;
                } catch (ConcurrentModificationException e) {
                    if (++conflict >= MAX_CONFLICT_RETRIES) {
                        throw e;
                    }
                }
            }
        } catch (NotFoundException e) {
            // Already gone — treat as a successful (idempotent) delete.
            result.put("deleted", true);
            result.put("note", "already deleted");
            return result;
        } catch (Exception e) {
            result.put("deleted", false);
            result.put("error", rootMessage(e));
            log.warn("ct cleanup | delete failed | type={} id={} | {}", type, id, rootMessage(e));
            return result;
        }
    }

    // ── Low-level CT operations ────────────────────────────────────────────────────

    private void setColorVariants(ProjectApiRoot apiRoot, String masterId, Set<String> remainingIds) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (String id : remainingIds) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("typeId", "product");
            ref.put("id", id);
            refs.add(ref);
        }
        Product master = apiRoot.products().withId(masterId).get().executeBlocking().getBody();
        apiRoot.products().withId(masterId)
                .post(ProductUpdateBuilder.of()
                        .version(master.getVersion())
                        .actions(ProductSetAttributeInAllVariantsActionBuilder.of()
                                .name("colorVariants")
                                .value(refs)
                                .staged(true)
                                .build())
                        .build())
                .executeBlocking();
    }

    private JsonNode execute(ProjectApiRoot apiRoot, GraphQLRequest request) {
        GraphQLResponse response = apiRoot.graphql().post(request).executeBlocking().getBody();
        if (Objects.isNull(response)) {
            throw new IllegalStateException("CT GraphQL returned an empty response");
        }
        if (Objects.nonNull(response.getErrors()) && !response.getErrors().isEmpty()) {
            throw new IllegalStateException("CT GraphQL errors: " + response.getErrors());
        }
        return mapper.valueToTree(response.getData());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────

    private CtProperties.Environment resolveEnv(String envName) {
        CtProperties.Environment env = properties.environment(envName);
        if (Objects.isNull(env)) {
            throw new IllegalArgumentException("Unknown CT environment: " + envName);
        }
        return env;
    }

    private String requireSafeProductId(String productId) {
        if (Objects.isNull(productId) || productId.isBlank()) {
            throw new IllegalArgumentException("productId is required");
        }
        String pid = productId.trim();
        if (!pid.matches(SAFE_ID)) {
            throw new IllegalArgumentException("productId contains unsupported characters");
        }
        return pid;
    }

    private static Map<String, Object> baseResult(String envName, String productId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", envName);
        out.put("productId", productId);
        return out;
    }

    private static String idInClause(List<String> ids) {
        StringBuilder in = new StringBuilder("id in (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                in.append(", ");
            }
            in.append('"').append(ids.get(i).replace("\"", "")).append('"');
        }
        return in.append(')').toString();
    }

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

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
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
