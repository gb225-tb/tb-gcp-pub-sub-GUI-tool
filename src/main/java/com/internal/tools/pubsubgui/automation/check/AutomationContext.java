package com.internal.tools.pubsubgui.automation.check;

import com.internal.tools.pubsubgui.config.MongoClientFactory;
import com.internal.tools.pubsubgui.service.HclBuildService;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared, read-only context handed to every {@link Validator}. Provides cached access to the
 * item-config collections for the selected environment plus a few sampling / lookup helpers so
 * validators don't each re-query. Never performs writes.
 */
public class AutomationContext {

    /** Logical Mongo database holding the config-catalog collections. */
    public static final String CONFIG_DB = "item-config";
    public static final String RUNTIME_DB = "item-runtime";

    private final String env;
    private final String productId;
    private final int sampleSize;
    private final MongoClientFactory mongo;
    private final HclBuildService hclBuildService;

    /** Cached sampled document lists keyed by "db/collection[/active]". */
    private final Map<String, List<Document>> sampleCache = new ConcurrentHashMap<>();

    /** General-purpose per-run cache (e.g. the HCL build result reused across doc-type scenarios). */
    private final Map<String, Object> objectCache = new ConcurrentHashMap<>();

    public AutomationContext(String env, String productId, int sampleSize,
                             MongoClientFactory mongo, HclBuildService hclBuildService) {
        this.env = env;
        this.productId = productId;
        this.sampleSize = sampleSize;
        this.mongo = mongo;
        this.hclBuildService = hclBuildService;
    }

    public String env() {
        return env;
    }

    public String productId() {
        return productId;
    }

    public boolean hasProductId() {
        return productId != null && !productId.isBlank();
    }

    public int sampleSize() {
        return sampleSize;
    }

    public HclBuildService hcl() {
        return hclBuildService;
    }

    public MongoDatabase configDb() {
        return mongo.database(env, CONFIG_DB);
    }

    public MongoCollection<Document> collection(String name) {
        return configDb().getCollection(name);
    }

    /** Sample up to {@link #sampleSize} documents from a config collection (cached). */
    public List<Document> sample(String collection) {
        return sampleCache.computeIfAbsent(CONFIG_DB + "/" + collection, k -> {
            List<Document> out = new ArrayList<>();
            for (Document d : collection(collection).find().limit(sampleSize)) {
                out.add(d);
            }
            return out;
        });
    }

    /** Sample up to {@link #sampleSize} active documents, optionally scoped to the entered productId. */
    public List<Document> sampleActive(String collection) {
        String key = CONFIG_DB + "/" + collection + "/active" + (hasProductId() ? "/" + productId : "");
        return sampleCache.computeIfAbsent(key, k -> {
            Bson filter = Filters.eq("status", "active");
            if (hasProductId()) {
                filter = Filters.and(filter, Filters.or(
                        Filters.eq("productId", productId),
                        Filters.eq("_id", productId)));
            }
            List<Document> out = new ArrayList<>();
            for (Document d : collection(collection).find(filter).limit(sampleSize)) {
                out.add(d);
            }
            return out;
        });
    }

    /** Returns the subset of {@code ids} that exist as {@code _id} in the given collection. */
    public Set<String> existingIds(String collection, Set<String> ids) {
        Set<String> present = new HashSet<>();
        if (ids == null || ids.isEmpty()) {
            return present;
        }
        List<String> idList = new ArrayList<>(ids);
        for (Document d : collection(collection).find(Filters.in("_id", idList)).limit(idList.size())) {
            present.add(String.valueOf(d.get("_id")));
        }
        return present;
    }

    /** Memoize an arbitrary value for the duration of the run (used by the HCL cross-verifier). */
    @SuppressWarnings("unchecked")
    public <T> T cached(String key, java.util.function.Supplier<T> supplier) {
        return (T) objectCache.computeIfAbsent(key, k -> supplier.get());
    }

    /** Collect distinct non-null string values of {@code field} across a document list. */
    public static Set<String> distinct(List<Document> docs, String field) {
        Set<String> out = new LinkedHashSet<>();
        for (Document d : docs) {
            Object v = d.get(field);
            if (Objects.nonNull(v)) {
                out.add(String.valueOf(v));
            }
        }
        return out;
    }
}
