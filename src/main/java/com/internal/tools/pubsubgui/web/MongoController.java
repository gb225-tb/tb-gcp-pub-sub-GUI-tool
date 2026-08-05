package com.internal.tools.pubsubgui.web;

import com.internal.tools.pubsubgui.config.MongoClientFactory;
import com.internal.tools.pubsubgui.config.MongoProperties;
import com.internal.tools.pubsubgui.model.ProductCleanupRequest;
import com.internal.tools.pubsubgui.service.MongoService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * REST endpoints for the Mongo Compare view. Only environment / database /
 * collection names and document bodies are exposed to the client; connection
 * URIs and credentials never leave the server.
 */
@RestController
@RequestMapping("/api/mongo")
public class MongoController {

    private final MongoService service;
    private final MongoClientFactory factory;

    public MongoController(MongoService service, MongoClientFactory factory) {
        this.service = service;
        this.factory = factory;
    }

    private static <T> Mono<T> blocking(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    /** The configured environments and their database names (no URIs/credentials). */
    @GetMapping("/config")
    public Mono<Map<String, Object>> config() {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> environments = factory.properties().getEnvironments().stream()
                    .map(env -> {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("name", env.getName());
                        e.put("databases", env.getDatabases().stream()
                                .map(MongoProperties.Database::getName)
                                .toList());
                        return e;
                    })
                    .toList();
            List<Map<String, Object>> cleanupGroups = factory.properties().getProductCleanup().stream()
                    .map(g -> {
                        Map<String, Object> grp = new LinkedHashMap<>();
                        grp.put("label", g.getLabel());
                        grp.put("database", g.getDatabase());
                        grp.put("collections", g.getCollections());
                        return grp;
                    })
                    .toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("environments", environments);
            out.put("productCleanup", cleanupGroups);
            return out;
        });
    }

    /** Count how many docs match the productId in each configured cleanup collection. */
    @GetMapping("/cleanup/scan")
    public Mono<Map<String, Object>> cleanupScan(@RequestParam String env,
                                                 @RequestParam String productId) {
        return blocking(() -> {
            List<Map<String, Object>> groups = new ArrayList<>();
            long total = 0;
            for (MongoProperties.CleanupGroup group : factory.properties().getProductCleanup()) {
                List<Map<String, Object>> collections = new ArrayList<>();
                for (String collection : group.getCollections()) {
                    long count = service.countByProductId(env, group.getDatabase(), collection, productId);
                    total += count;
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("name", collection);
                    c.put("count", count);
                    collections.add(c);
                }
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("label", group.getLabel());
                g.put("database", group.getDatabase());
                g.put("collections", collections);
                groups.add(g);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("env", env);
            out.put("productId", productId);
            out.put("groups", groups);
            out.put("total", total);
            return out;
        });
    }

    /** Delete the productId's documents from the selected (database, collection) targets. */
    @PostMapping("/cleanup/delete")
    public Mono<Map<String, Object>> cleanupDelete(@RequestBody ProductCleanupRequest body) {
        return blocking(() -> {
            List<ProductCleanupRequest.Target> targets = body == null || body.targets() == null
                    ? List.of() : body.targets();
            String env = body == null ? null : body.env();
            String productId = body == null ? null : body.productId();
            List<Map<String, Object>> results = new ArrayList<>();
            long totalDeleted = 0;
            for (ProductCleanupRequest.Target t : targets) {
                long deleted = service.deleteManyByProductId(env, t.database(), t.collection(), productId);
                totalDeleted += deleted;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("database", t.database());
                r.put("collection", t.collection());
                r.put("deleted", deleted);
                results.add(r);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("env", env);
            out.put("productId", productId);
            out.put("results", results);
            out.put("totalDeleted", totalDeleted);
            return out;
        });
    }

    @GetMapping("/collections")
    public Mono<List<String>> collections(@RequestParam String env,
                                          @RequestParam String db) {
        return blocking(() -> service.listCollections(env, db));
    }

    @GetMapping("/document")
    public Mono<Map<String, Object>> document(@RequestParam String env,
                                              @RequestParam String db,
                                              @RequestParam String collection,
                                              @RequestParam String productId) {
        return blocking(() -> {
            List<Map<String, String>> documents = service.getDocuments(env, db, collection, productId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", !documents.isEmpty());
            out.put("count", documents.size());
            out.put("documents", documents);
            return out;
        });
    }

    @DeleteMapping("/document")
    public Mono<Map<String, Object>> delete(@RequestParam String env,
                                            @RequestParam String db,
                                            @RequestParam String collection,
                                            @RequestParam String id) {
        return blocking(() -> {
            long deleted = service.deleteById(env, db, collection, id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("deleted", deleted);
            return out;
        });
    }
}
