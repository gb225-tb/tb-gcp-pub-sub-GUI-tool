package com.internal.tools.pubsubgui.automation.check;

import com.internal.tools.pubsubgui.automation.model.CheckResult;
import com.internal.tools.pubsubgui.automation.model.FieldDiff;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * HCL-vs-streaming field cross-verification (workbook tabs {@code Xform_*} / {@code HCL_vs_Streaming_Diffs}).
 * For a supplied part number it builds the documents the HCL migration would produce (via
 * {@code HclBuildService}, read-only) and compares them field-by-field with the documents the streaming
 * pipeline actually stored in Mongo. Requires VPN (for DB2) and an entered productId.
 */
public final class HclCrossVerifier {

    private static final String[] PRODUCT_FIELDS = {
            "banner", "division", "divisionDescription", "productName", "productDescription",
            "type", "seoUrl", "fit", "material", "pattern"};

    private HclCrossVerifier() {
    }

    /** XF-PRODUCT: compare the Product document. */
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
            List<FieldDiff> diffs = new ArrayList<>();
            diffs.add(diff("_id", "Product", str(hclProduct.get("_id")), str(stream.get("_id"))));
            for (String f : PRODUCT_FIELDS) {
                diffs.add(diff(f, "Product", str(hclProduct.get(f)), str(stream.get(f))));
            }
            return CheckResult.diff(id, 1, diffs, "Compared Product fields (HCL vs streaming).");
        };
    }

    /** XF-VARIANT: compare active Variant counts for the product. */
    public static Validator variant() {
        return countValidator("Variant", "variant", "Variant");
    }

    /** XF-SKU: compare active SKU counts for the product. */
    public static Validator sku() {
        return countValidator("SKU", "sku", "SKU");
    }

    /** XF-PRICE: compare active Price counts for the product. */
    public static Validator price() {
        return countValidator("Price", "price", "Price");
    }

    /** XF-ENRICHED: compare publish-ready EnrichedProduct counts for the product. */
    public static Validator enriched() {
        return countValidator("EnrichedProduct", "enrichedProduct", "EnrichedProduct");
    }

    private static Validator countValidator(String collection, String hclCountKey, String docType) {
        return (id, ctx) -> {
            Holder h = build(ctx);
            if (h.skip != null) {
                return h.skip.apply(id);
            }
            Map<String, Object> counts = asMap(h.hcl.get("counts"));
            long hclCount = asLong(counts.get(hclCountKey));
            Bson filter = Filters.and(
                    Filters.eq("productId", ctx.productId()),
                    Filters.eq("status", "active"));
            long streamCount;
            try {
                streamCount = ctx.collection(collection).countDocuments(filter);
            } catch (RuntimeException e) {
                return CheckResult.error(id, "Count query failed: " + e.getMessage());
            }
            String verdict = hclCount == streamCount ? "MATCH" : "DIFFERS";
            List<FieldDiff> diffs = List.of(new FieldDiff(
                    "count(active " + collection + ")", docType,
                    String.valueOf(hclCount), String.valueOf(streamCount), verdict));
            return CheckResult.diff(id, (int) Math.max(hclCount, streamCount), diffs,
                    docType + " count HCL=" + hclCount + " vs streaming=" + streamCount + ".");
        };
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

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static FieldDiff diff(String field, String docType, String expected, String actual) {
        String verdict;
        if (Objects.equals(expected, actual)) {
            verdict = "MATCH";
        } else if (expected == null || actual == null) {
            verdict = "GAP";
        } else {
            verdict = "DIFFERS";
        }
        return new FieldDiff(field, docType, expected, actual, verdict);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
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
