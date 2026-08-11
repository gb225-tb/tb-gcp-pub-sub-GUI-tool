package com.internal.tools.pubsubgui.automation.check;

import com.internal.tools.pubsubgui.automation.model.CheckResult;
import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Read-only invariant checks derived from the Ingestion Processors test plan. Each factory returns a
 * {@link Validator} that samples already-ingested documents and asserts a single invariant. None of
 * these write to Mongo.
 */
public final class CatalogValidators {

    private static final int MAX_SAMPLE_IDS = 20;

    private CatalogValidators() {
    }

    // ── Identity / linkage ───────────────────────────────────────────────────────

    /** CP-08: every active SKU points at an existing Product and Variant; Variant points at a Product. */
    public static Validator crossCollectionIdentity() {
        return (id, ctx) -> {
            List<Document> skus = ctx.sampleActive("SKU");
            List<Document> variants = ctx.sampleActive("Variant");
            if (skus.isEmpty() && variants.isEmpty()) {
                return CheckResult.skip(id, "No active SKU/Variant documents sampled.");
            }
            Set<String> skuProductIds = AutomationContext.distinct(skus, "productId");
            Set<String> skuVariantIds = AutomationContext.distinct(skus, "variantId");
            Set<String> variantProductIds = AutomationContext.distinct(variants, "productId");

            Set<String> presentProducts = ctx.existingIds("Product", union(skuProductIds, variantProductIds));
            Set<String> presentVariants = ctx.existingIds("Variant", skuVariantIds);

            List<String> bad = new ArrayList<>();
            for (Document sku : skus) {
                String pid = str(sku.get("productId"));
                String vid = str(sku.get("variantId"));
                if (pid != null && !presentProducts.contains(pid)) {
                    bad.add(str(sku.get("_id")) + " -> missing Product " + pid);
                } else if (vid != null && !presentVariants.contains(vid)) {
                    bad.add(str(sku.get("_id")) + " -> missing Variant " + vid);
                }
            }
            for (Document v : variants) {
                String pid = str(v.get("productId"));
                if (pid != null && !presentProducts.contains(pid)) {
                    bad.add(str(v.get("_id")) + " -> missing Product " + pid);
                }
            }
            int checked = skus.size() + variants.size();
            if (bad.isEmpty()) {
                return CheckResult.pass(id, checked, "All sampled SKU/Variant references resolve.");
            }
            return CheckResult.fail(id, checked, bad.size(),
                    "Dangling cross-collection references found.",
                    "SKU.productId∈Product._id, SKU.variantId∈Variant._id, Variant.productId∈Product._id",
                    bad.size() + " broken references", cap(bad));
        };
    }

    /** EN-01: EnrichedProduct._id == variantId and productId == strip-after-last-'_' of variantId. */
    public static Validator enrichedIdentity() {
        return (id, ctx) -> {
            List<Document> docs = ctx.sampleActive("EnrichedProduct");
            if (docs.isEmpty()) {
                return CheckResult.skip(id, "No active EnrichedProduct documents sampled.");
            }
            List<String> bad = new ArrayList<>();
            for (Document d : docs) {
                String idVal = str(d.get("_id"));
                String variantId = str(d.get("variantId"));
                String productId = str(d.get("productId"));
                if (variantId != null && !variantId.equals(idVal)) {
                    bad.add(idVal + " (_id != variantId " + variantId + ")");
                    continue;
                }
                if (variantId != null && productId != null) {
                    int us = variantId.lastIndexOf('_');
                    String expected = us > 0 ? variantId.substring(0, us) : variantId;
                    if (!expected.equals(productId)) {
                        bad.add(idVal + " (productId " + productId + " != " + expected + ")");
                    }
                }
            }
            if (bad.isEmpty()) {
                return CheckResult.pass(id, docs.size(), "EnrichedProduct identity derivation holds.");
            }
            return CheckResult.fail(id, docs.size(), bad.size(),
                    "EnrichedProduct identity mismatch.",
                    "_id == variantId; productId == variantId up to last '_'",
                    bad.size() + " mismatched docs", cap(bad));
        };
    }

    // ── Transformation invariants ────────────────────────────────────────────────

    /** UI-05: numeric Variant.colorCode is zero-padded (never a bare single digit). */
    public static Validator colorCodePadded() {
        return (id, ctx) -> {
            List<Document> variants = ctx.sampleActive("Variant");
            if (variants.isEmpty()) {
                return CheckResult.skip(id, "No active Variant documents sampled.");
            }
            List<String> bad = new ArrayList<>();
            int checked = 0;
            for (Document v : variants) {
                String cc = str(v.get("colorCode"));
                if (cc == null || cc.isBlank()) {
                    continue;
                }
                checked++;
                if (cc.matches("\\d") ) {
                    bad.add(str(v.get("_id")) + " colorCode='" + cc + "'");
                }
            }
            if (checked == 0) {
                return CheckResult.skip(id, "No Variant.colorCode values to check.");
            }
            if (bad.isEmpty()) {
                return CheckResult.pass(id, checked, "All numeric colorCodes are zero-padded.");
            }
            return CheckResult.fail(id, checked, bad.size(), "Un-padded single-digit colorCode(s).",
                    "numeric colorCode padded to >=2 chars (e.g. '05')", "bare single digit", cap(bad));
        };
    }

    /** UI-06: when SKU.division is present, SKU.sizeCode is prefixed with '<division>_'. */
    public static Validator sizeCodeFormat() {
        return (id, ctx) -> {
            List<Document> skus = ctx.sampleActive("SKU");
            if (skus.isEmpty()) {
                return CheckResult.skip(id, "No active SKU documents sampled.");
            }
            List<String> bad = new ArrayList<>();
            int checked = 0;
            for (Document s : skus) {
                String division = str(s.get("division"));
                String sizeCode = str(s.get("sizeCode"));
                if (division == null || division.isBlank() || sizeCode == null || sizeCode.isBlank()) {
                    continue;
                }
                checked++;
                if (!sizeCode.startsWith(division + "_")) {
                    bad.add(str(s.get("_id")) + " sizeCode='" + sizeCode + "' division='" + division + "'");
                }
            }
            if (checked == 0) {
                return CheckResult.skip(id, "No SKU with both division and sizeCode to check.");
            }
            if (bad.isEmpty()) {
                return CheckResult.pass(id, checked, "sizeCode is '<division>_<rawSizeCode>'.");
            }
            return CheckResult.fail(id, checked, bad.size(), "sizeCode not prefixed by division.",
                    "sizeCode == '<division>_<rawSizeCode>'", "prefix != division", cap(bad));
        };
    }

    /** UI-07: SKU.division mirrors its Product.division. */
    public static Validator divisionMirror() {
        return (id, ctx) -> {
            List<Document> skus = ctx.sampleActive("SKU");
            if (skus.isEmpty()) {
                return CheckResult.skip(id, "No active SKU documents sampled.");
            }
            // Build productId -> division map for the products referenced by the sample.
            Set<String> pids = AutomationContext.distinct(skus, "productId");
            Map<String, String> productDivision = new LinkedHashMap<>();
            if (!pids.isEmpty()) {
                for (Document p : ctx.collection("Product")
                        .find(Filters.in("_id", new ArrayList<>(pids))).limit(pids.size())) {
                    productDivision.put(str(p.get("_id")), str(p.get("division")));
                }
            }
            List<String> bad = new ArrayList<>();
            int checked = 0;
            for (Document s : skus) {
                String pid = str(s.get("productId"));
                String skuDiv = str(s.get("division"));
                if (pid == null || !productDivision.containsKey(pid)) {
                    continue;
                }
                checked++;
                String prodDiv = productDivision.get(pid);
                if (!Objects.equals(skuDiv, prodDiv)) {
                    bad.add(str(s.get("_id")) + " sku.division='" + skuDiv + "' product.division='" + prodDiv + "'");
                }
            }
            if (checked == 0) {
                return CheckResult.skip(id, "No SKU whose Product was found in the sample.");
            }
            if (bad.isEmpty()) {
                return CheckResult.pass(id, checked, "SKU.division mirrors Product.division.");
            }
            return CheckResult.fail(id, checked, bad.size(), "SKU.division diverges from Product.division.",
                    "SKU.division == Product.division", "mismatch", cap(bad));
        };
    }

    /** UI-10 / EN-12: text attributes are INITCAP'd (not left screaming-caps). */
    public static Validator initCapText() {
        return (id, ctx) -> {
            List<Document> products = ctx.sampleActive("Product");
            List<Document> variants = ctx.sampleActive("Variant");
            List<String> bad = new ArrayList<>();
            int checked = 0;
            checked += scanInitCap(products, "Product", new String[]{"fit", "material", "pattern"}, bad);
            checked += scanInitCap(variants, "Variant", new String[]{"color", "colorFamily"}, bad);
            if (checked == 0) {
                return CheckResult.skip(id, "No text attributes present to check.");
            }
            if (bad.isEmpty()) {
                return CheckResult.pass(id, checked, "Text attributes are INITCAP-formatted.");
            }
            return CheckResult.fail(id, checked, bad.size(), "All-caps text attribute(s) found.",
                    "INITCAP (e.g. 'Wool Blend')", "ALL CAPS", cap(bad));
        };
    }

    // ── Publish gate ─────────────────────────────────────────────────────────────

    /** EN-02: EnrichedProduct.publishedAt implies a mainImage is present. */
    public static Validator publishRequiresMainImage() {
        return (id, ctx) -> {
            List<Document> docs = ctx.sampleActive("EnrichedProduct");
            if (docs.isEmpty()) {
                return CheckResult.skip(id, "No active EnrichedProduct documents sampled.");
            }
            List<String> bad = new ArrayList<>();
            int checked = 0;
            for (Document d : docs) {
                if (Objects.nonNull(d.get("publishedAt"))) {
                    checked++;
                    if (isBlank(d.get("mainImage"))) {
                        bad.add(str(d.get("_id")) + " published without mainImage");
                    }
                }
            }
            if (checked == 0) {
                return CheckResult.skip(id, "No published EnrichedProduct documents to check.");
            }
            if (bad.isEmpty()) {
                return CheckResult.pass(id, checked, "Every published EnrichedProduct has a mainImage.");
            }
            return CheckResult.fail(id, checked, bad.size(), "Published EnrichedProduct missing mainImage.",
                    "publishedAt set => mainImage present", "publishedAt without mainImage", cap(bad));
        };
    }

    /** EN-03: no mainImage implies publishedAt is not set. */
    public static Validator noMainImageNoPublish() {
        return (id, ctx) -> {
            List<Document> docs = ctx.sampleActive("EnrichedProduct");
            if (docs.isEmpty()) {
                return CheckResult.skip(id, "No active EnrichedProduct documents sampled.");
            }
            List<String> bad = new ArrayList<>();
            int checked = 0;
            for (Document d : docs) {
                if (isBlank(d.get("mainImage"))) {
                    checked++;
                    if (Objects.nonNull(d.get("publishedAt"))) {
                        bad.add(str(d.get("_id")) + " has publishedAt but no mainImage");
                    }
                }
            }
            if (checked == 0) {
                return CheckResult.skip(id, "No EnrichedProduct without mainImage to check.");
            }
            if (bad.isEmpty()) {
                return CheckResult.pass(id, checked, "No publish stamped without a mainImage.");
            }
            return CheckResult.fail(id, checked, bad.size(), "publishedAt set despite missing mainImage.",
                    "mainImage absent => publishedAt absent", "publishedAt present", cap(bad));
        };
    }

    /** EN-05: published Products carry a seoUrl. */
    public static Validator seoUrlOnPublished() {
        return (id, ctx) -> {
            List<Document> products = ctx.sampleActive("Product");
            if (products.isEmpty()) {
                return CheckResult.skip(id, "No active Product documents sampled.");
            }
            List<String> bad = new ArrayList<>();
            int checked = 0;
            for (Document p : products) {
                if (Objects.nonNull(p.get("publishedAt"))) {
                    checked++;
                    if (isBlank(p.get("seoUrl"))) {
                        bad.add(str(p.get("_id")) + " published without seoUrl");
                    }
                }
            }
            if (checked == 0) {
                return CheckResult.skip(id, "No published Product documents to check.");
            }
            if (bad.isEmpty()) {
                return CheckResult.pass(id, checked, "Every published Product has a seoUrl.");
            }
            return CheckResult.fail(id, checked, bad.size(), "Published Product missing seoUrl.",
                    "publishedAt set => seoUrl present", "missing seoUrl", cap(bad));
        };
    }

    // ── Price ────────────────────────────────────────────────────────────────────

    /** PR-03: Price._id is uppercased and trimmed (no lowercase / whitespace). */
    public static Validator priceIdNormalized() {
        return (id, ctx) -> {
            List<Document> prices = ctx.sampleActive("Price");
            if (prices.isEmpty()) {
                return CheckResult.skip(id, "No active Price documents sampled.");
            }
            List<String> bad = new ArrayList<>();
            for (Document p : prices) {
                String idVal = str(p.get("_id"));
                if (idVal == null) {
                    continue;
                }
                if (!idVal.equals(idVal.trim().toUpperCase())) {
                    bad.add("'" + idVal + "'");
                }
            }
            if (bad.isEmpty()) {
                return CheckResult.pass(id, prices.size(), "All Price._id are uppercased & trimmed.");
            }
            return CheckResult.fail(id, prices.size(), bad.size(), "Non-normalized Price._id found.",
                    "Price._id == uppercase(trim(CatentryPartNumber))", "lowercase/whitespace", cap(bad));
        };
    }

    /** PR-04: Price catalog ids match the referenced SKU's productId/variantId. */
    public static Validator priceCatalogIdsMatchSku() {
        return (id, ctx) -> {
            List<Document> prices = ctx.sampleActive("Price");
            if (prices.isEmpty()) {
                return CheckResult.skip(id, "No active Price documents sampled.");
            }
            Set<String> skuIds = AutomationContext.distinct(prices, "_id");
            Map<String, Document> skuById = new LinkedHashMap<>();
            if (!skuIds.isEmpty()) {
                for (Document s : ctx.collection("SKU")
                        .find(Filters.in("_id", new ArrayList<>(skuIds))).limit(skuIds.size())) {
                    skuById.put(str(s.get("_id")), s);
                }
            }
            List<String> bad = new ArrayList<>();
            int checked = 0;
            for (Document p : prices) {
                Document sku = skuById.get(str(p.get("_id")));
                if (sku == null) {
                    continue;
                }
                checked++;
                if (p.get("productId") != null
                        && !Objects.equals(str(p.get("productId")), str(sku.get("productId")))) {
                    bad.add(str(p.get("_id")) + " price.productId=" + str(p.get("productId"))
                            + " sku.productId=" + str(sku.get("productId")));
                } else if (p.get("variantId") != null
                        && !Objects.equals(str(p.get("variantId")), str(sku.get("variantId")))) {
                    bad.add(str(p.get("_id")) + " price.variantId=" + str(p.get("variantId"))
                            + " sku.variantId=" + str(sku.get("variantId")));
                }
            }
            if (checked == 0) {
                return CheckResult.skip(id, "No Price whose SKU was found in the sample.");
            }
            if (bad.isEmpty()) {
                return CheckResult.pass(id, checked, "Price catalog ids match their SKU.");
            }
            return CheckResult.fail(id, checked, bad.size(), "Price catalog ids diverge from SKU.",
                    "Price.productId/variantId == SKU.productId/variantId", "mismatch", cap(bad));
        };
    }

    /** PR-09/PR-10: SKU.isSale == (salePrice < listPrice) using the linked Price. */
    public static Validator isSaleConsistency() {
        return (id, ctx) -> {
            List<Document> skus = ctx.sampleActive("SKU");
            if (skus.isEmpty()) {
                return CheckResult.skip(id, "No active SKU documents sampled.");
            }
            Set<String> ids = AutomationContext.distinct(skus, "_id");
            Map<String, Document> priceById = new LinkedHashMap<>();
            if (!ids.isEmpty()) {
                for (Document p : ctx.collection("Price")
                        .find(Filters.in("_id", new ArrayList<>(ids))).limit(ids.size())) {
                    priceById.put(str(p.get("_id")), p);
                }
            }
            List<String> bad = new ArrayList<>();
            int checked = 0;
            for (Document s : skus) {
                Document price = priceById.get(str(s.get("_id")));
                if (price == null || s.get("isSale") == null) {
                    continue;
                }
                Double list = asDouble(price.get("listPrice"));
                Double sale = asDouble(price.get("salePrice"));
                boolean expected = list != null && sale != null && sale < list;
                boolean actual = Boolean.TRUE.equals(s.getBoolean("isSale"))
                        || Boolean.TRUE.equals(s.get("isSale"));
                checked++;
                if (expected != actual) {
                    bad.add(str(s.get("_id")) + " isSale=" + actual + " list=" + list + " sale=" + sale);
                }
            }
            if (checked == 0) {
                return CheckResult.skip(id, "No SKU with isSale and a linked Price to check.");
            }
            if (bad.isEmpty()) {
                return CheckResult.pass(id, checked, "SKU.isSale matches salePrice<listPrice.");
            }
            return CheckResult.fail(id, checked, bad.size(), "SKU.isSale inconsistent with prices.",
                    "isSale == (salePrice < listPrice)", "mismatch", cap(bad));
        };
    }

    /** Price list/sale values are numeric types (not strings). */
    public static Validator priceNumericTypes() {
        return (id, ctx) -> {
            List<Document> prices = ctx.sampleActive("Price");
            if (prices.isEmpty()) {
                return CheckResult.skip(id, "No active Price documents sampled.");
            }
            List<String> bad = new ArrayList<>();
            for (Document p : prices) {
                if (hasNonNumeric(p, "listPrice") || hasNonNumeric(p, "salePrice")
                        || hasNonNumeric(p, "promoPrice")) {
                    bad.add(str(p.get("_id")));
                }
            }
            if (bad.isEmpty()) {
                return CheckResult.pass(id, prices.size(), "listPrice/salePrice/promoPrice are numeric.");
            }
            return CheckResult.fail(id, prices.size(), bad.size(), "Non-numeric price value(s).",
                    "listPrice/salePrice/promoPrice are numbers", "string/other", cap(bad));
        };
    }

    // ── Errors collection inspection (Schema_Requirements tab) ─────────────────────

    /** ERR-01: surface schema-validation failures currently sitting in the errors collection. */
    public static Validator schemaErrorsInErrorsCollection() {
        return (id, ctx) -> {
            long total;
            try {
                total = ctx.collection("errors").estimatedDocumentCount();
            } catch (RuntimeException e) {
                return CheckResult.skip(id, "No 'errors' collection available: " + e.getMessage());
            }
            if (total == 0) {
                return CheckResult.pass(id, 0, "errors collection is empty — no ingestion failures.");
            }
            // Break down by errorType across a sample and collect recent ids.
            Map<String, Integer> byType = new TreeMap<>();
            List<String> ids = new ArrayList<>();
            for (Document d : ctx.collection("errors").find().limit(ctx.sampleSize())) {
                String type = str(d.get("errorType"));
                byType.merge(type == null ? "(none)" : type, 1, Integer::sum);
                if (ids.size() < MAX_SAMPLE_IDS) {
                    ids.add(str(d.get("_id")));
                }
            }
            StringBuilder breakdown = new StringBuilder();
            for (Map.Entry<String, Integer> e : byType.entrySet()) {
                if (breakdown.length() > 0) {
                    breakdown.append(", ");
                }
                breakdown.append(e.getKey()).append('=').append(e.getValue());
            }
            return CheckResult.fail(id, (int) Math.min(total, Integer.MAX_VALUE), byType.values().stream()
                            .mapToInt(Integer::intValue).sum(),
                    "errors collection holds " + total + " document(s).",
                    "0 rejected/failed messages", breakdown.toString(), ids);
        };
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static int scanInitCap(List<Document> docs, String docType, String[] fields, List<String> bad) {
        int checked = 0;
        for (Document d : docs) {
            for (String f : fields) {
                String v = str(d.get(f));
                if (v == null || v.isBlank() || !v.matches(".*[A-Za-z].*")) {
                    continue;
                }
                checked++;
                // Screaming-caps if it has >1 letter and equals its own upper-case form.
                String letters = v.replaceAll("[^A-Za-z]", "");
                if (letters.length() > 1 && v.equals(v.toUpperCase())) {
                    bad.add(docType + " " + str(d.get("_id")) + " " + f + "='" + v + "'");
                }
            }
        }
        return checked;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static boolean hasNonNumeric(Document d, String field) {
        Object v = d.get(field);
        return v != null && !(v instanceof Number)
                && !(v instanceof org.bson.types.Decimal128);
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof org.bson.types.Decimal128 d) {
            return d.bigDecimalValue().doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static boolean isBlank(Object v) {
        return v == null || String.valueOf(v).isBlank();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static List<String> cap(List<String> in) {
        return in.size() > MAX_SAMPLE_IDS ? new ArrayList<>(in.subList(0, MAX_SAMPLE_IDS)) : in;
    }
}
