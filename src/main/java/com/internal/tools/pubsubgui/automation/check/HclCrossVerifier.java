package com.internal.tools.pubsubgui.automation.check;

import com.internal.tools.pubsubgui.automation.model.CheckResult;
import com.internal.tools.pubsubgui.automation.model.FieldDiff;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * HCL-vs-streaming field cross-verification (workbook tabs {@code Xform_*} / {@code HCL_vs_Streaming_Diffs}).
 * For a supplied part number it builds the documents the HCL migration would produce (via
 * {@code HclBuildService}, read-only) and compares them <b>type-wise, field-by-field</b> with the documents
 * the streaming pipeline actually stored in Mongo — one scenario per document type (Product, Variant, SKU,
 * Price, EnrichedProduct). Requires VPN (for DB2) and an entered productId (the streaming Product {@code _id}).
 *
 * <p>Verdicts: {@code MATCH} (equal), {@code DIFFERS} (both present, values differ — a real migration defect),
 * {@code MISSING} (HCL builds a value the streaming doc lacks — a migration gap), and {@code EXTRA} (streaming
 * has a value the HCL migration does not build — informational, e.g. fields set by other feeds). Only
 * {@code DIFFERS} and {@code MISSING} fail the check.
 */
public final class HclCrossVerifier {

    /** Volatile / audit / time fields that are re-stamped per build and would always differ — not compared. */
    private static final Set<String> IGNORED_FIELDS = Set.of(
            "_class", "createdAt", "updatedAt", "updatedBy",
            "startDate", "endDate", "publishedAt");

    /**
     * Fields shown for information only (never fail the check). {@code createdBy} is the most useful signal of
     * the replatform: the streaming doc should carry the ingesting processor name (e.g.
     * {@code UniverseItemIngestionProcessor}), which confirms the transformation path ran — whereas the HCL
     * build stamps a fixed audit actor, so a plain equality check would always (and misleadingly) fail.
     */
    private static final Set<String> INFO_FIELDS = Set.of("createdBy");

    /** Identity fields that must match exactly (a mismatch is a real defect, not a transformation). */
    private static final Set<String> IDENTITY_FIELDS = Set.of("_id", "productId", "variantId", "sku");

    /** Verdicts that count as a failure (real defect or migration gap). */
    private static final Set<String> FAILING = Set.of("DIFFERS", "MISSING");

    /** Safety cap on the number of documents compared per type (products rarely exceed this). */
    private static final int DOC_CAP = 60;

    private HclCrossVerifier() {
    }

    /** XF-PRODUCT: compare the Product document field-by-field. */
    public static Validator product() {
        return (id, ctx) -> {
            Holder h = build(ctx);
            if (h.skip != null) {
                return h.skip.apply(id);
            }
            Map<String, Object> hclProduct = asMap(h.hcl.get("product"));
            Document stream = streamingProduct(ctx);
            if (stream == null) {
                return CheckResult.skip(id, "No streaming Product found with _id='" + ctx.productId()
                        + "' (streaming/HCL identities differ; enter the streaming productId).");
            }
            List<FieldDiff> diffs = compareFields(label("Product", ctx.productId()), hclProduct, stream);
            return CheckResult.diffGraded(id, diffs.size(), diffs,
                    "Compared Product fields (HCL vs streaming).", FAILING);
        };
    }

    /** XF-VARIANT: compare every Variant document field-by-field. */
    public static Validator variant() {
        return typeValidator("Variant", "Variant", HclCrossVerifier::hclVariants);
    }

    /** XF-SKU: compare every SKU document field-by-field. */
    public static Validator sku() {
        return typeValidator("SKU", "SKU", HclCrossVerifier::hclSkus);
    }

    /** XF-PRICE: compare every Price document field-by-field. */
    public static Validator price() {
        return typeValidator("Price", "Price", HclCrossVerifier::hclPrices);
    }

    /** XF-ENRICHED: compare every publish-ready EnrichedProduct document field-by-field. */
    public static Validator enriched() {
        return typeValidator("EnrichedProduct", "EnrichedProduct", HclCrossVerifier::hclEnriched);
    }

    // ── generic type-wise comparison ──────────────────────────────────────────────

    private interface DocExtractor {
        List<Map<String, Object>> apply(Map<String, Object> hclBuild);
    }

    private static Validator typeValidator(String collection, String docType, DocExtractor extractor) {
        return (id, ctx) -> {
            Holder h = build(ctx);
            if (h.skip != null) {
                return h.skip.apply(id);
            }
            // HCL expected docs, indexed by _id (build order preserved).
            LinkedHashMap<String, Map<String, Object>> expected = new LinkedHashMap<>();
            for (Map<String, Object> m : extractor.apply(h.hcl)) {
                String key = str(m.get("_id"));
                if (key != null) {
                    expected.put(key, m);
                }
            }
            // Streaming actual docs for this product, indexed by _id.
            Map<String, Document> actual = new LinkedHashMap<>();
            try {
                Bson filter = Filters.eq("productId", ctx.productId());
                for (Document d : ctx.collection(collection).find(filter)) {
                    actual.put(str(d.get("_id")), d);
                }
            } catch (RuntimeException e) {
                return CheckResult.error(id, "Streaming " + collection + " query failed: " + e.getMessage());
            }

            if (expected.isEmpty() && actual.isEmpty()) {
                return CheckResult.skip(id, "No " + docType + " documents on either side for productId='"
                        + ctx.productId() + "'.");
            }

            LinkedHashSet<String> ids = new LinkedHashSet<>(expected.keySet());
            ids.addAll(actual.keySet());

            List<FieldDiff> diffs = new ArrayList<>();
            int docs = 0;
            boolean truncated = false;
            for (String key : ids) {
                if (docs >= DOC_CAP) {
                    truncated = true;
                    break;
                }
                docs++;
                Map<String, Object> exp = expected.get(key);
                Document act = actual.get(key);
                String label = label(docType, key);
                if (exp == null) {
                    diffs.add(new FieldDiff("(document)", label, null, "present", "EXTRA"));
                } else if (act == null) {
                    diffs.add(new FieldDiff("(document)", label, "present", null, "MISSING"));
                } else {
                    diffs.addAll(compareFields(label, exp, act));
                }
            }

            String msg = docType + " fields (HCL vs streaming): " + expected.size() + " HCL, "
                    + actual.size() + " streaming" + (truncated ? " — truncated at " + DOC_CAP + " docs" : "") + ".";
            return CheckResult.diffGraded(id, diffs.size(), diffs, msg, FAILING);
        };
    }

    /** Compare the union of fields of two documents (ignoring volatile/audit fields), graded per field. */
    private static List<FieldDiff> compareFields(String label, Map<String, Object> expected,
                                                 Map<String, Object> actual) {
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(expected.keySet());
        keys.addAll(actual.keySet());
        keys.removeAll(IGNORED_FIELDS);

        List<FieldDiff> diffs = new ArrayList<>();
        for (String k : keys) {
            String e = canon(expected.get(k));
            String a = canon(actual.get(k));
            String verdict;
            if (INFO_FIELDS.contains(k)) {
                // Informational (e.g. createdBy = ingesting processor) — surfaced, never fails the check.
                if (e == null && a == null) {
                    continue;
                }
                verdict = "INFO";
            } else if (Objects.equals(e, a)) {
                verdict = "MATCH";
            } else if (e != null && a == null) {
                verdict = "MISSING";
            } else if (e == null) {
                verdict = "EXTRA";
            } else {
                verdict = "DIFFERS";
            }
            diffs.add(new FieldDiff(k, label, e, a, verdict));
        }
        return diffs;
    }

    // ── raw HCL DB record vs streaming (one document) ──────────────────────────────

    /**
     * Compares one <b>raw</b> HCL DB record (values keyed by their streaming field names, untransformed)
     * against the streaming Catalog document. Verdicts: identity fields must {@code MATCH} (else DIFFERS/
     * MISSING — real defect); a non-identity value present on both but different is {@code XFORM} (the
     * streaming pipeline transformed the raw source — expected, not a failure); {@code MISSING} means the raw
     * source had a value the streaming doc dropped (data loss — fails); {@code EXTRA} is streaming-only; and
     * {@code createdBy} is surfaced as {@code INFO} so the ingesting processor is visible.
     */
    public static CheckResult rawCompare(String scenarioId, String docType, String docId,
                                         Map<String, Object> raw, Document streaming) {
        String label = label(docType, docId);
        if (streaming == null) {
            List<FieldDiff> diffs = List.of(
                    new FieldDiff("(document)", label, "present (HCL)", null, "MISSING"));
            return CheckResult.diffGraded(scenarioId, 1, diffs,
                    docType + " " + docId + " exists in raw HCL but not in the streaming Catalog.", FAILING);
        }
        List<FieldDiff> diffs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String k = entry.getKey();
            String exp = canon(entry.getValue());
            String act = canon(streaming.get(k));
            if (exp == null && act == null) {
                continue;
            }
            String verdict;
            if (Objects.equals(exp, act)) {
                verdict = "MATCH";
            } else if (IDENTITY_FIELDS.contains(k)) {
                verdict = exp == null ? "EXTRA" : act == null ? "MISSING" : "DIFFERS";
            } else if (exp != null && act == null) {
                verdict = "MISSING";
            } else if (exp == null) {
                verdict = "EXTRA";
            } else {
                verdict = "XFORM";
            }
            diffs.add(new FieldDiff(k, label, exp, act, verdict));
        }
        // createdBy: raw HCL has none — surface the streaming processor that created the doc (replatform proof).
        String createdBy = streaming.get("createdBy") == null ? null : String.valueOf(streaming.get("createdBy"));
        diffs.add(new FieldDiff("createdBy", label, null, createdBy, "INFO"));

        return CheckResult.diffGraded(scenarioId, diffs.size(), diffs,
                "Raw HCL vs streaming " + docType + " " + docId
                        + " (untransformed source vs Catalog; XFORM = transformed by the pipeline).", FAILING);
    }

    // ── HCL expected-doc extractors ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Object> variantsOf(Map<String, Object> hcl) {
        Object v = hcl.get("variants");
        return v instanceof List ? (List<Object>) v : List.of();
    }

    private static List<Map<String, Object>> hclVariants(Map<String, Object> hcl) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : variantsOf(hcl)) {
            Map<String, Object> vm = asMap(o);
            Map<String, Object> variant = asMap(vm.get("variant"));
            if (!variant.isEmpty()) {
                out.add(variant);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> hclEnriched(Map<String, Object> hcl) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : variantsOf(hcl)) {
            Map<String, Object> vm = asMap(o);
            Object e = vm.get("enrichedProduct");
            if (e instanceof Map) {
                out.add(asMap(e));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hclSkuChild(Map<String, Object> hcl, String childKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : variantsOf(hcl)) {
            Map<String, Object> vm = asMap(o);
            Object skus = vm.get("skus");
            if (!(skus instanceof List)) {
                continue;
            }
            for (Object s : (List<Object>) skus) {
                Map<String, Object> child = asMap(asMap(s).get(childKey));
                if (!child.isEmpty()) {
                    out.add(child);
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> hclSkus(Map<String, Object> hcl) {
        return hclSkuChild(hcl, "sku");
    }

    private static List<Map<String, Object>> hclPrices(Map<String, Object> hcl) {
        return hclSkuChild(hcl, "price");
    }

    // ── build + fetch (cached per run) ───────────────────────────────────────────

    private static Holder build(AutomationContext ctx) {
        if (!ctx.hasProductId()) {
            Holder h = new Holder();
            h.skip = id -> CheckResult.skip(id, "Enter a productId (part number) to run HCL cross-verification.");
            return h;
        }
        return ctx.cached("hcl:" + ctx.productId(), () -> {
            Holder h = new Holder();
            try {
                Map<String, Object> result = ctx.hcl().buildForProductId(ctx.env(), ctx.productId());
                if (!Boolean.TRUE.equals(result.get("found"))) {
                    String reason = str(result.get("reason"));
                    h.skip = id -> CheckResult.skip(id, reason == null ? "Product not found in HCL." : reason);
                } else {
                    h.hcl = result;
                }
            } catch (RuntimeException e) {
                String msg = rootMessage(e);
                h.skip = id -> CheckResult.error(id, "HCL build failed (VPN/DB2?): " + msg);
            }
            return h;
        });
    }

    private static Document streamingProduct(AutomationContext ctx) {
        return ctx.cached("streamProduct:" + ctx.productId(),
                () -> ctx.collection("Product").find(Filters.eq("_id", ctx.productId())).first());
    }

    // ── value canonicalization + helpers ──────────────────────────────────────────

    /** Normalizes a value to a comparable, display-friendly string (number/decimal/set/map aware). */
    private static String canon(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Decimal128 d) {
            return num(d.bigDecimalValue());
        }
        if (v instanceof BigDecimal bd) {
            return num(bd);
        }
        if (v instanceof Double || v instanceof Float) {
            return num(BigDecimal.valueOf(((Number) v).doubleValue()));
        }
        if (v instanceof Number n) {
            return n.toString();
        }
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, String> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), canon(e.getValue()));
            }
            return sorted.toString();
        }
        if (v instanceof Collection<?> c) {
            // Order-independent: sort canonical element strings so set/array ordering never causes a false diff.
            List<String> items = new ArrayList<>();
            for (Object e : c) {
                items.add(canon(e));
            }
            items.sort((a, b) -> Objects.compare(a, b, java.util.Comparator.nullsFirst(String::compareTo)));
            return items.toString();
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String num(BigDecimal bd) {
        BigDecimal stripped = bd.stripTrailingZeros();
        if (stripped.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return stripped.toPlainString();
    }

    private static String label(String docType, String id) {
        return id == null ? docType : docType + "[" + id + "]";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }

    /** Holds either the HCL build result or a terminal skip/error to short-circuit each doc-type check. */
    private static final class Holder {
        Map<String, Object> hcl;
        java.util.function.Function<String, CheckResult> skip;
    }
}
