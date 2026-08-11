package com.internal.tools.pubsubgui.web;

import com.internal.tools.pubsubgui.service.CategoryExplorerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * REST endpoints for the Categories view. Each source is a separate endpoint so the UI can load them
 * independently (HCL needs VPN; Constructor needs env-injected credentials). All blocking work runs on
 * the bounded elastic scheduler. Connection strings / secrets never leave the server.
 */
@RestController
@RequestMapping("/api/category")
public class CategoryController {

    private final CategoryExplorerService service;

    public CategoryController(CategoryExplorerService service) {
        this.service = service;
    }

    /** Product count + list in the category from HCL DB2 (requires VPN). */
    @GetMapping("/hcl")
    public Mono<Map<String, Object>> hcl(@RequestParam String env,
                                         @RequestParam String categoryId) {
        return Mono.fromCallable(() -> service.hcl(env, categoryId)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Association counts + sample in the category from the Catalog Mongo collections (config + runtime). */
    @GetMapping("/catalog")
    public Mono<Map<String, Object>> catalog(@RequestParam String env,
                                             @RequestParam String categoryId) {
        return Mono.fromCallable(() -> service.catalog(env, categoryId)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Product count + sample in the category from Constructor's Browse API. */
    @GetMapping("/constructor")
    public Mono<Map<String, Object>> constructor(@RequestParam String env,
                                                 @RequestParam String categoryId) {
        return Mono.fromCallable(() -> service.constructor(env, categoryId)).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Cross-source reconciliation: full HCL / Catalog / Constructor id sets, an in-stock check against the
     * runtime Inventory collection, and the resulting common / left-out / anomaly summary. Read-only.
     */
    @GetMapping("/reconcile")
    public Mono<Map<String, Object>> reconcile(@RequestParam String env,
                                               @RequestParam String categoryId) {
        return Mono.fromCallable(() -> service.reconcile(env, categoryId)).subscribeOn(Schedulers.boundedElastic());
    }
}
