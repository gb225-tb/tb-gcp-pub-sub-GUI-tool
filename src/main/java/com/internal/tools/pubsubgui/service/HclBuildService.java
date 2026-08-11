package com.internal.tools.pubsubgui.service;

import com.internal.tools.pubsubgui.config.HclProperties;
import com.internal.tools.pubsubgui.hcl.config.HclConfig;
import com.internal.tools.pubsubgui.hcl.config.HclConfigLoader;
import com.internal.tools.pubsubgui.hcl.db2.Db2ProductReader;
import com.internal.tools.pubsubgui.hcl.mapper.DocumentMappers;
import com.internal.tools.pubsubgui.hcl.mapper.DocumentMappers.ProductSharedAttributes;
import com.internal.tools.pubsubgui.hcl.mapper.HclLifecycleRules;
import com.internal.tools.pubsubgui.hcl.model.ProductBundle;
import com.internal.tools.pubsubgui.hcl.support.CatalogSourceClassifier;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reproduces the read-and-build half of {@code HCLDataMigrationProcessor}: resolve a part-number to a
 * CATENTRY_ID, read its HCL DB2 subtree into a {@link ProductBundle}, run {@link DocumentMappers} plus
 * the {@code ProductFanoutFn} roll-ups / publish gate, and return the 7 documents it would upsert.
 *
 * <p><b>No writes.</b> The Mongo item-config/inventory-config upserts are intentionally omitted.
 */
@Service
public class HclBuildService {

    private static final Logger log = LoggerFactory.getLogger(HclBuildService.class);

    /** Short DB2 login timeout (seconds) so the status bulb and fetches fail fast when VPN is down. */
    private static final int LOGIN_TIMEOUT_SECONDS = 6;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final HclProperties properties;
    private final HclConfig.Queries queries;
    private final HclConfig.AttributeMappings attributes;

    private final Map<String, Db2ProductReader> readers = new ConcurrentHashMap<>();
    private final Map<String, DocumentMappers> mappersByEnv = new ConcurrentHashMap<>();

    public HclBuildService(HclProperties properties) {
        this.properties = properties;
        this.queries = HclConfigLoader.loadQueries();
        this.attributes = HclConfigLoader.loadAttributes();
    }

    // ── Status probe (VPN / DB2 reachability) ──────────────────────────────────

    /** Opens (and closes) a DB2 connection for the environment; result feeds the UI status bulb. */
    public Map<String, Object> probe(String envName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", envName);
        HclProperties.Environment env = properties.environment(envName);
        if (Objects.isNull(env)) {
            out.put("up", false);
            out.put("host", "");
            out.put("error", "Unknown HCL environment: " + envName);
            return out;
        }
        out.put("host", env.getDb2().host());
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        try (Connection ignored = reader(env).openConnection()) {
            out.put("up", true);
        } catch (Exception e) {
            out.put("up", false);
            out.put("error", rootMessage(e));
            log.warn("hcl probe | env={} | down | {}", envName, rootMessage(e));
        }
        return out;
    }

    // ── Category -> products (Categories view, read-only) ──────────────────────

    /**
     * Resolves a category (numeric CATGROUP_ID or CATGROUP.IDENTIFIER) and returns the count of
     * ProductBean catentries in it plus a capped product list for the "view data" panel. No writes.
     */
    public Map<String, Object> categoryProductsInHcl(String envName, String categoryInput) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", envName);
        out.put("categoryId", categoryInput);
        HclProperties.Environment env = properties.environment(envName);
        if (Objects.isNull(env)) {
            throw new IllegalArgumentException("Unknown HCL environment: " + envName);
        }
        if (Objects.isNull(categoryInput) || categoryInput.isBlank()) {
            throw new IllegalArgumentException("categoryId is required");
        }
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        Db2ProductReader reader = reader(env);
        try (Connection connection = reader.openConnection()) {
            Db2ProductReader.CategoryRef ref = reader.resolveCategory(connection, categoryInput.trim());
            if (Objects.isNull(ref)) {
                out.put("found", false);
                out.put("reason", "No CATGROUP matched '" + categoryInput.trim() + "'");
                out.put("count", 0);
                out.put("products", new ArrayList<>());
                return out;
            }
            long count = reader.countProductsInCategory(connection, ref.catGroupId());
            List<Db2ProductReader.CategoryProduct> products =
                    reader.listProductsInCategory(connection, ref.catGroupId());
            List<Map<String, Object>> productOut = new ArrayList<>(products.size());
            for (Db2ProductReader.CategoryProduct p : products) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("catEntryId", p.catEntryId());
                row.put("partNumber", p.partNumber());
                row.put("name", p.name());
                row.put("published", p.published());
                productOut.add(row);
            }
            out.put("found", true);
            out.put("catGroupId", ref.catGroupId());
            out.put("identifier", ref.identifier());
            out.put("count", count);
            out.put("productsShown", productOut.size());
            out.put("products", productOut);
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HCL category lookup failed for '" + categoryInput.trim()
                    + "': " + rootMessage(e), e);
        }
    }

    /**
     * All distinct product part numbers in the category (read-only, VPN). Returns {@code found=false}
     * when the category can't be resolved; used by the cross-source reconciliation. Bounded by {@code maxRows}.
     */
    public Map<String, Object> categoryPartNumbersInHcl(String envName, String categoryInput, int maxRows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", envName);
        HclProperties.Environment env = properties.environment(envName);
        if (Objects.isNull(env)) {
            throw new IllegalArgumentException("Unknown HCL environment: " + envName);
        }
        if (Objects.isNull(categoryInput) || categoryInput.isBlank()) {
            throw new IllegalArgumentException("categoryId is required");
        }
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        Db2ProductReader reader = reader(env);
        try (Connection connection = reader.openConnection()) {
            Db2ProductReader.CategoryRef ref = reader.resolveCategory(connection, categoryInput.trim());
            if (Objects.isNull(ref)) {
                out.put("found", false);
                out.put("reason", "No CATGROUP matched '" + categoryInput.trim() + "'");
                out.put("partNumbers", new ArrayList<>());
                return out;
            }
            long count = reader.countProductsInCategory(connection, ref.catGroupId());
            List<String> partNumbers = reader.listAllProductPartNumbersInCategory(connection, ref.catGroupId(), maxRows);
            out.put("found", true);
            out.put("catGroupId", ref.catGroupId());
            out.put("identifier", ref.identifier());
            out.put("count", count);
            out.put("partNumbers", partNumbers);
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HCL category lookup failed for '" + categoryInput.trim()
                    + "': " + rootMessage(e), e);
        }
    }

    // ── Build (resolve -> read -> assemble, no writes) ─────────────────────────

    public Map<String, Object> buildForProductId(String envName, String partNumber) {
        HclProperties.Environment env = properties.environment(envName);
        if (Objects.isNull(env)) {
            throw new IllegalArgumentException("Unknown HCL environment: " + envName);
        }
        if (Objects.isNull(partNumber) || partNumber.isBlank()) {
            throw new IllegalArgumentException("productId (part number) is required");
        }
        String trimmed = partNumber.trim();
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);

        Db2ProductReader reader = reader(env);
        DocumentMappers mappers = mappers(env);

        try (Connection connection = reader.openConnection()) {
            Long catEntryId = reader.resolveProductCatEntryId(connection, trimmed);
            if (Objects.isNull(catEntryId)) {
                return notFound(envName, trimmed, "No ProductBean found for part number '" + trimmed + "'");
            }
            ProductBundle bundle = reader.fetchProduct(connection, catEntryId);
            String productId = bundle.partNumber(catEntryId);
            ProductBundle.CatalogEntry productDetails = bundle.details(catEntryId);
            if (Objects.isNull(productId) || Objects.isNull(productDetails)) {
                return notFound(envName, trimmed,
                        "Product " + catEntryId + " has no part number / details in HCL");
            }
            return assemble(envName, trimmed, catEntryId, bundle, mappers);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HCL build failed for '" + trimmed + "': " + rootMessage(e), e);
        }
    }

    /**
     * Port of {@code ProductFanoutFn.processElement} — buffers each variant subtree so child dates/status
     * roll up into the parent, applies the publish gate, mirrors SKU status onto Price/Item, then returns
     * the assembled documents (normalized for JSON). No documents are written anywhere.
     */
    private Map<String, Object> assemble(String envName, String partNumber, Long catEntryId,
                                         ProductBundle bundle, DocumentMappers mappers) {
        LocalDateTime now = LocalDateTime.now();
        ProductSharedAttributes shared = new ProductSharedAttributes();

        Document product = mappers.buildProduct(bundle, catEntryId, now, shared);
        Document rating = mappers.buildRating(bundle, catEntryId, now);
        String division = product.getString(DocumentMappers.DIVISION);

        List<VariantDocs> variants = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> entry : bundle.getSkuCatEntryIdsByVariantCatEntryId().entrySet()) {
            Long variantCatEntryId = entry.getKey();
            ProductBundle.CatalogEntry variantDetails = bundle.details(variantCatEntryId);
            if (Objects.isNull(bundle.partNumber(variantCatEntryId)) || Objects.isNull(variantDetails)) {
                continue;
            }
            String variantId = bundle.partNumber(variantCatEntryId);

            Document variantDoc = mappers.buildVariant(bundle, variantCatEntryId, product.getString(DocumentMappers.PRODUCT_ID), shared, now);
            Document enrichedDoc = mappers.buildEnrichedProduct(bundle, variantCatEntryId, product.getString(DocumentMappers.PRODUCT_ID), shared, now);

            List<Document> skuDocs = new ArrayList<>();
            List<Document> priceDocs = new ArrayList<>();
            List<Document> itemDocs = new ArrayList<>();
            List<String> skuSources = new ArrayList<>();
            Object variantPublishedAt = variantDoc.get(DocumentMappers.PUBLISHED_AT);
            for (Long skuCatEntryId : entry.getValue()) {
                String skuPartNumber = bundle.partNumber(skuCatEntryId);
                if (Objects.isNull(skuPartNumber) || Objects.isNull(bundle.details(skuCatEntryId))) {
                    continue;
                }
                Document sku = mappers.buildSku(bundle, skuCatEntryId, product.getString(DocumentMappers.PRODUCT_ID), variantId, now);
                if (Objects.nonNull(variantPublishedAt)) {
                    sku.put(DocumentMappers.PUBLISHED_AT, variantPublishedAt);
                }
                String skuSource = CatalogSourceClassifier.forSkuId(skuPartNumber);
                sku.put(DocumentMappers.SOURCE, skuSource);
                sku.put(DocumentMappers.DIVISION, division);
                skuSources.add(skuSource);
                skuDocs.add(sku);
                priceDocs.add(mappers.buildPrice(bundle, skuCatEntryId, product.getString(DocumentMappers.PRODUCT_ID), variantId, now));
                itemDocs.add(mappers.buildItem(sku, division, now));
            }

            String variantSource = CatalogSourceClassifier.rollup(skuSources);
            variantDoc.put(DocumentMappers.SOURCE, variantSource);
            enrichedDoc.put(DocumentMappers.SOURCE, variantSource);

            if (!DocumentMappers.isEnrichedPublishReady(enrichedDoc)) {
                clearPublishedAt(variantDoc);
                clearPublishedAt(enrichedDoc);
                for (Document sku : skuDocs) {
                    clearPublishedAt(sku);
                }
            }

            variants.add(new VariantDocs(variantDoc, enrichedDoc, skuDocs, priceDocs, itemDocs));
        }

        // Product roll-up.
        List<Document> variantDocs = new ArrayList<>(variants.size());
        List<String> variantSources = new ArrayList<>(variants.size());
        for (VariantDocs v : variants) {
            variantDocs.add(v.variant);
            variantSources.add(v.variant.getString(DocumentMappers.SOURCE));
        }
        product.put(DocumentMappers.SOURCE, CatalogSourceClassifier.rollup(variantSources));
        boolean productPublished = HclLifecycleRules.isPublished(bundle.details(catEntryId));
        boolean allVariantsInactive = !variants.isEmpty()
                && variants.stream().noneMatch(v -> isActive(v.variant));
        String productStatus = productPublished || !allVariantsInactive
                ? DocumentMappers.STATUS_ACTIVE : DocumentMappers.STATUS_INACTIVE;
        product.put(DocumentMappers.STATUS, productStatus);

        Date productStart = minDate(variantDocs, DocumentMappers.START_DATE);
        if (Objects.nonNull(productStart)) {
            product.put(DocumentMappers.START_DATE, productStart);
        }
        Date productPublishedAt = minDate(variantDocs, DocumentMappers.PUBLISHED_AT);
        if (Objects.nonNull(productPublishedAt)) {
            product.put(DocumentMappers.PUBLISHED_AT, productPublishedAt);
        }
        if (DocumentMappers.STATUS_INACTIVE.equals(productStatus)
                && allHave(variantDocs, DocumentMappers.END_DATE)) {
            Date productEnd = maxDate(variantDocs, DocumentMappers.END_DATE);
            if (Objects.nonNull(productEnd)) {
                product.put(DocumentMappers.END_DATE, productEnd);
            }
        }

        // Mirror each SKU's final status onto its Price and Item (1:1 by index).
        for (VariantDocs v : variants) {
            for (int i = 0; i < v.skus.size(); i++) {
                String skuStatus = v.skus.get(i).getString(DocumentMappers.STATUS);
                v.prices.get(i).put(DocumentMappers.STATUS, skuStatus);
                v.items.get(i).put(DocumentMappers.STATUS, skuStatus);
            }
        }

        return buildResponse(envName, partNumber, catEntryId, product, rating, variants);
    }

    private Map<String, Object> buildResponse(String envName, String partNumber, Long catEntryId,
                                              Document product, Document rating, List<VariantDocs> variants) {
        HclProperties.Collections cols = properties.getCollections();
        int skuCount = 0;
        int priceCount = 0;
        int itemCount = 0;
        int enrichedCount = 0;

        List<Map<String, Object>> variantOut = new ArrayList<>();
        for (VariantDocs v : variants) {
            boolean publishReady = DocumentMappers.isEnrichedPublishReady(v.enriched);
            if (publishReady) {
                enrichedCount++;
            }
            List<Map<String, Object>> skuOut = new ArrayList<>();
            for (int i = 0; i < v.skus.size(); i++) {
                Map<String, Object> sku = new LinkedHashMap<>();
                sku.put("sku", normalize(v.skus.get(i)));
                sku.put("price", normalize(v.prices.get(i)));
                sku.put("item", normalize(v.items.get(i)));
                skuOut.add(sku);
            }
            skuCount += v.skus.size();
            priceCount += v.prices.size();
            itemCount += v.items.size();

            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("variant", normalize(v.variant));
            variant.put("enrichedProduct", publishReady ? normalize(v.enriched) : null);
            variant.put("enrichedPublishReady", publishReady);
            variant.put("skus", skuOut);
            variantOut.add(variant);
        }

        boolean hasRating = DocumentMappers.hasRating(rating);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("product", 1);
        counts.put("rating", hasRating ? 1 : 0);
        counts.put("variant", variants.size());
        counts.put("enrichedProduct", enrichedCount);
        counts.put("sku", skuCount);
        counts.put("price", priceCount);
        counts.put("item", itemCount);

        Map<String, String> collections = new LinkedHashMap<>();
        collections.put("product", cols.getProduct());
        collections.put("rating", cols.getRating());
        collections.put("variant", cols.getVariant());
        collections.put("enrichedProduct", cols.getEnrichedProduct());
        collections.put("sku", cols.getSku());
        collections.put("price", cols.getPrice());
        collections.put("item", cols.getItem());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", envName);
        out.put("productId", partNumber);
        out.put("catEntryId", catEntryId);
        out.put("found", true);
        out.put("product", normalize(product));
        out.put("rating", hasRating ? normalize(rating) : null);
        out.put("variants", variantOut);
        out.put("counts", counts);
        out.put("collections", collections);
        return out;
    }

    private Map<String, Object> notFound(String envName, String partNumber, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", envName);
        out.put("productId", partNumber);
        out.put("found", false);
        out.put("reason", reason);
        return out;
    }

    // ── Per-env caches ─────────────────────────────────────────────────────────

    private Db2ProductReader reader(HclProperties.Environment env) {
        return readers.computeIfAbsent(env.getName(),
                k -> new Db2ProductReader(properties.toHclConfig(env), queries));
    }

    private DocumentMappers mappers(HclProperties.Environment env) {
        return mappersByEnv.computeIfAbsent(env.getName(),
                k -> DocumentMappers.from(properties.toHclConfig(env), attributes));
    }

    // ── Roll-up helpers (ported) ────────────────────────────────────────────────

    private static boolean isActive(Document doc) {
        return DocumentMappers.STATUS_ACTIVE.equalsIgnoreCase(doc.getString(DocumentMappers.STATUS));
    }

    private static void clearPublishedAt(Document doc) {
        doc.remove(DocumentMappers.PUBLISHED_AT);
    }

    private static Date dateOf(Document doc, String key) {
        Object value = doc.get(key);
        return (value instanceof Date) ? (Date) value : null;
    }

    private static Date minDate(List<Document> docs, String key) {
        Date min = null;
        for (Document doc : docs) {
            Date value = dateOf(doc, key);
            if (Objects.nonNull(value) && (Objects.isNull(min) || value.before(min))) {
                min = value;
            }
        }
        return min;
    }

    private static Date maxDate(List<Document> docs, String key) {
        Date max = null;
        for (Document doc : docs) {
            Date value = dateOf(doc, key);
            if (Objects.nonNull(value) && (Objects.isNull(max) || value.after(max))) {
                max = value;
            }
        }
        return max;
    }

    private static boolean allHave(List<Document> docs, String key) {
        if (docs.isEmpty()) {
            return false;
        }
        for (Document doc : docs) {
            if (Objects.isNull(dateOf(doc, key))) {
                return false;
            }
        }
        return true;
    }

    // ── JSON normalization ──────────────────────────────────────────────────────

    /** Recursively converts BSON/Java types into JSON-friendly values (clean for the UI). */
    @SuppressWarnings("unchecked")
    static Object normalize(Object value) {
        if (Objects.isNull(value)) {
            return null;
        }
        if (value instanceof Document doc) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : doc.entrySet()) {
                out.put(e.getKey(), normalize(e.getValue()));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            }
            return out;
        }
        if (value instanceof Decimal128 dec) {
            return dec.bigDecimalValue().toPlainString();
        }
        if (value instanceof java.math.BigDecimal bd) {
            return bd.toPlainString();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.format(ISO);
        }
        if (value instanceof Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).format(ISO);
        }
        if (value instanceof Set<?> || value instanceof Collection<?>) {
            List<Object> out = new ArrayList<>();
            for (Object item : (Collection<Object>) value) {
                out.add(normalize(item));
            }
            return out;
        }
        return value;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (Objects.nonNull(cur.getCause()) && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (Objects.isNull(msg) || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }

    /** Buffered per-variant subtree, held so child status can roll up into the parent before emit. */
    private record VariantDocs(Document variant, Document enriched, List<Document> skus,
                               List<Document> prices, List<Document> items) {
    }
}
