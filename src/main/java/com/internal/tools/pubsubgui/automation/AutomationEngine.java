package com.internal.tools.pubsubgui.automation;

import com.internal.tools.pubsubgui.automation.check.AutomationContext;
import com.internal.tools.pubsubgui.automation.check.HclCrossVerifier;
import com.internal.tools.pubsubgui.automation.model.CheckResult;
import com.internal.tools.pubsubgui.automation.model.HclRawCompareRequest;
import com.internal.tools.pubsubgui.automation.model.HclRawCompareResponse;
import com.internal.tools.pubsubgui.automation.model.RunRequest;
import com.internal.tools.pubsubgui.automation.model.RunSummary;
import com.internal.tools.pubsubgui.automation.model.ScenarioGroup;
import com.internal.tools.pubsubgui.automation.model.ScenarioResult;
import com.internal.tools.pubsubgui.automation.scenario.ScenarioRegistry;
import com.internal.tools.pubsubgui.config.MongoClientFactory;
import com.internal.tools.pubsubgui.service.HclBuildService;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runs the selected scenarios' read-only validators against a single environment and aggregates the
 * outcomes into a {@link RunSummary}. All execution is synchronous/blocking here; the controller wraps
 * it on the bounded-elastic scheduler.
 */
@Service
public class AutomationEngine {

    private static final Logger log = LoggerFactory.getLogger(AutomationEngine.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private static final int DEFAULT_SAMPLE = 200;
    private static final int MIN_SAMPLE = 1;
    private static final int MAX_SAMPLE = 2000;

    private final ScenarioRegistry registry;
    private final MongoClientFactory mongo;
    private final HclBuildService hclBuildService;

    public AutomationEngine(ScenarioRegistry registry, MongoClientFactory mongo,
                            HclBuildService hclBuildService) {
        this.registry = registry;
        this.mongo = mongo;
        this.hclBuildService = hclBuildService;
    }

    public RunSummary run(RunRequest request) {
        if (request == null || request.env() == null || request.env().isBlank()) {
            throw new IllegalArgumentException("env is required");
        }
        int sampleSize = clampSample(request.sampleSize());
        String productId = trimToNull(request.productId());
        AutomationContext ctx = new AutomationContext(request.env().trim(), productId, sampleSize,
                mongo, hclBuildService);

        List<ScenarioRegistry.Entry> selected = select(request);

        Instant start = Instant.now();
        List<ScenarioResult> results = new ArrayList<>(selected.size());
        int passed = 0, failed = 0, skipped = 0, na = 0, errored = 0;
        for (ScenarioRegistry.Entry entry : selected) {
            String id = entry.def().id();
            CheckResult result;
            try {
                result = entry.validator().run(id, ctx);
            } catch (RuntimeException e) {
                log.warn("automation | env={} scenario={} failed to run | {}", request.env(), id,
                        rootMessage(e));
                result = CheckResult.error(id, "Check threw: " + rootMessage(e));
            }
            switch (result.status()) {
                case PASS -> passed++;
                case FAIL -> failed++;
                case SKIP -> skipped++;
                case NA -> na++;
                case ERROR -> errored++;
            }
            results.add(new ScenarioResult(entry.def(), result));
        }
        Instant end = Instant.now();

        return new RunSummary(
                request.env().trim(), productId, sampleSize,
                ISO.format(start), ISO.format(end), Duration.between(start, end).toMillis(),
                results.size(), passed, failed, skipped, na, errored, results);
    }

    /** Resolve which scenarios to run based on explicit ids, group filter, or "all". */
    private List<ScenarioRegistry.Entry> select(RunRequest request) {
        List<ScenarioRegistry.Entry> all = registry.all();
        List<String> ids = request.scenarioIds();
        if (ids != null && !ids.isEmpty()) {
            Set<String> want = Set.copyOf(ids);
            return all.stream().filter(e -> want.contains(e.def().id())).toList();
        }
        ScenarioGroup group = parseGroup(request.group());
        if (group != null) {
            return all.stream().filter(e -> e.def().group() == group).toList();
        }
        return all; // "All"
    }

    private static ScenarioGroup parseGroup(String group) {
        if (group == null || group.isBlank() || "ALL".equalsIgnoreCase(group.trim())) {
            return null;
        }
        for (ScenarioGroup g : ScenarioGroup.values()) {
            if (g.name().equalsIgnoreCase(group.trim()) || g.label().equalsIgnoreCase(group.trim())) {
                return g;
            }
        }
        return null;
    }

    private static int clampSample(Integer requested) {
        int v = requested == null ? DEFAULT_SAMPLE : requested;
        return Math.max(MIN_SAMPLE, Math.min(MAX_SAMPLE, v));
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (Objects.nonNull(cur.getCause()) && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }

    /**
     * Fetch the raw HCL DB record for one representative document of the requested type and compare it,
     * field-by-field, against the streaming Catalog document of the same {@code _id}. Read-only; requires VPN
     * (for DB2) and a product part number. The representative document is auto-picked from the product's
     * subtree (Product → the product; Variant/Enriched → the first variant; SKU/Price → the first SKU).
     */
    public HclRawCompareResponse hclRawCompare(HclRawCompareRequest request) {
        if (request == null || request.env() == null || request.env().isBlank()) {
            throw new IllegalArgumentException("env is required");
        }
        String productId = trimToNull(request.productId());
        if (productId == null) {
            throw new IllegalArgumentException("productId (part number) is required");
        }
        String env = request.env().trim();
        String type = request.type() == null ? "PRODUCT" : request.type().trim().toUpperCase();

        Map<String, Object> raw;
        try {
            raw = hclBuildService.rawForProductId(env, productId);
        } catch (RuntimeException e) {
            log.warn("hcl-raw-compare | env={} product={} type={} | {}", env, productId, type, rootMessage(e));
            return new HclRawCompareResponse(false, "HCL read failed: " + rootMessage(e),
                    env, productId, type, null, null, null, "ERROR", 0, 0, List.of());
        }
        if (!Boolean.TRUE.equals(raw.get("found"))) {
            String msg = raw.get("reason") == null ? "Product not found in HCL" : String.valueOf(raw.get("reason"));
            return new HclRawCompareResponse(false, msg, env, productId, type, null, null, null,
                    "SKIP", 0, 0, List.of());
        }

        String docType;
        String collection;
        Map<String, Object> rawDoc;
        switch (type) {
            case "PRODUCT" -> {
                docType = "Product";
                collection = "Product";
                rawDoc = asMap(raw.get("product"));
            }
            case "VARIANT" -> {
                docType = "Variant";
                collection = "Variant";
                rawDoc = asMap(nodeField(firstVariant(raw), "variant"));
            }
            case "ENRICHED", "ENRICHEDPRODUCT" -> {
                docType = "EnrichedProduct";
                collection = "EnrichedProduct";
                rawDoc = asMap(nodeField(firstVariant(raw), "enrichedProduct"));
            }
            case "SKU" -> {
                docType = "SKU";
                collection = "SKU";
                rawDoc = asMap(nodeField(firstSku(raw), "sku"));
            }
            case "PRICE" -> {
                docType = "Price";
                collection = "Price";
                rawDoc = asMap(nodeField(firstSku(raw), "price"));
            }
            default -> throw new IllegalArgumentException("Unknown type: " + type
                    + " (expected PRODUCT | VARIANT | SKU | PRICE | ENRICHED)");
        }

        if (rawDoc == null) {
            return new HclRawCompareResponse(true,
                    "No " + docType + " found in the HCL subtree for product " + productId + ".",
                    env, productId, type, docType, null, collection, "SKIP", 0, 0, List.of());
        }
        String docId = rawDoc.get("_id") == null ? null : String.valueOf(rawDoc.get("_id"));

        Document streaming = mongo.database(env, AutomationContext.CONFIG_DB).getCollection(collection)
                .find(Filters.eq("_id", docId)).first();

        CheckResult cr = HclCrossVerifier.rawCompare("HCL-RAW-" + type, docType, docId, rawDoc, streaming);
        return new HclRawCompareResponse(true, cr.message(), env, productId, type, docType, docId, collection,
                cr.status().name(), cr.checked(), cr.failed(), cr.diffs());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstVariant(Map<String, Object> raw) {
        Object v = raw.get("variants");
        if (v instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map) {
            return (Map<String, Object>) list.get(0);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstSku(Map<String, Object> raw) {
        Map<String, Object> variant = firstVariant(raw);
        if (variant == null) {
            return null;
        }
        Object skus = variant.get("skus");
        if (skus instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map) {
            return (Map<String, Object>) list.get(0);
        }
        return null;
    }

    private static Object nodeField(Map<String, Object> node, String field) {
        return node == null ? null : node.get(field);
    }

    /** The scenario catalog (used by the controller's catalog endpoint). */
    public ScenarioRegistry registry() {
        return registry;
    }
}
