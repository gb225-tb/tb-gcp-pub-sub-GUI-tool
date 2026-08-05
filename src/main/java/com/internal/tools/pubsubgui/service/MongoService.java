package com.internal.tools.pubsubgui.service;

import com.internal.tools.pubsubgui.config.MongoClientFactory;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read/compare/delete operations against the configured MongoDB connections for
 * the Mongo Compare view. Kept synchronous; controllers wrap calls on a
 * bounded-elastic scheduler to fit the WebFlux stack.
 */
@Service
public class MongoService {

    private static final JsonWriterSettings PRETTY = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED)
            .indent(true)
            .build();

    private final MongoClientFactory factory;

    public MongoService(MongoClientFactory factory) {
        this.factory = factory;
    }

    /** Sorted list of collection names in the given environment + database. */
    public List<String> listCollections(String environment, String database) {
        MongoDatabase db = factory.database(environment, database);
        List<String> names = new ArrayList<>();
        db.listCollectionNames().into(names);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Hard cap on how many documents we return for a single productId. */
    private static final int MAX_DOCUMENTS = 100;

    /**
     * Fetch <b>all</b> documents matching {@code productId} (the field seeded
     * across the related collections). A single productId may map to several
     * documents (e.g. multiple SKUs). Each entry carries a stringified {@code _id}
     * label and the document as pretty relaxed extended JSON. The value is matched
     * as a string and, when it parses as a number, as a numeric value too.
     */
    public List<Map<String, String>> getDocuments(String environment, String database, String collection, String productId) {
        MongoCollection<Document> coll = collection(environment, database, collection);
        List<Map<String, String>> out = new ArrayList<>();
        for (Document doc : coll.find(productFilter(productId)).limit(MAX_DOCUMENTS)) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", idLabel(doc.get("_id")));
            entry.put("json", doc.toJson(PRETTY));
            out.add(entry);
        }
        return out;
    }

    /**
     * Delete a single document by its {@code _id}, so the source pipeline
     * re-seeds/reprocesses it. The id is matched as a raw string first and, when
     * it is a valid 24-char hex value, as an {@link ObjectId}. Returns the number
     * of documents deleted (0 or 1).
     */
    public long deleteById(String environment, String database, String collection, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("_id is required to delete a document.");
        }
        MongoCollection<Document> coll = collection(environment, database, collection);
        long deleted = coll.deleteOne(Filters.eq("_id", id)).getDeletedCount();
        if (deleted == 0 && ObjectId.isValid(id)) {
            deleted = coll.deleteOne(Filters.eq("_id", new ObjectId(id))).getDeletedCount();
        }
        return deleted;
    }

    private String idLabel(Object id) {
        return id == null ? "(no _id)" : String.valueOf(id);
    }

    /** Count documents matching {@code productId} in a collection (Product Clean Up scan). */
    public long countByProductId(String environment, String database, String collection, String productId) {
        return collection(environment, database, collection).countDocuments(productFilter(productId));
    }

    /**
     * Delete <b>all</b> documents matching {@code productId} in a collection and
     * return the deleted count (Product Clean Up). A productId can map to several
     * documents (e.g. multiple SKUs), so this uses {@code deleteMany}.
     */
    public long deleteManyByProductId(String environment, String database, String collection, String productId) {
        return collection(environment, database, collection).deleteMany(productFilter(productId)).getDeletedCount();
    }

    /**
     * Build a {@code productId} filter that matches the value as a string and,
     * when applicable, as a numeric type (Firestore/Mongo may store either).
     */
    private Bson productFilter(String productId) {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId is required.");
        }
        List<Bson> variants = new ArrayList<>();
        variants.add(Filters.eq("productId", productId));
        try {
            variants.add(Filters.eq("productId", Long.parseLong(productId)));
        } catch (NumberFormatException ignored) {
            // not an integer; skip the numeric variant
        }
        return variants.size() == 1 ? variants.get(0) : Filters.or(variants);
    }

    private MongoCollection<Document> collection(String environment, String database, String collection) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("Collection name is required.");
        }
        return factory.database(environment, database).getCollection(collection);
    }
}
