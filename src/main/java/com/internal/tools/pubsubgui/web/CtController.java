package com.internal.tools.pubsubgui.web;

import com.internal.tools.pubsubgui.model.CtCleanupRequest;
import com.internal.tools.pubsubgui.service.CtCleanupService;
import com.internal.tools.pubsubgui.service.CtExplorerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * REST endpoints for the CT Data Explorer. The blocking commercetools SDK calls run on the bounded
 * elastic scheduler. CT credentials never leave the server — only the connection status and the
 * assembled (read-only) product tree are returned.
 */
@RestController
@RequestMapping("/api/ct")
public class CtController {

    private final CtExplorerService service;
    private final CtCleanupService cleanupService;

    public CtController(CtExplorerService service, CtCleanupService cleanupService) {
        this.service = service;
        this.cleanupService = cleanupService;
    }

    /** CT auth / connectivity probe for the environment (feeds the status chip). */
    @GetMapping("/status")
    public Mono<Map<String, Object>> status(@RequestParam String env) {
        return Mono.fromCallable(() -> service.probe(env)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Fetches the CT product tree (product + variant products + SKUs/prices) for a productId. */
    @GetMapping("/product")
    public Mono<Map<String, Object>> product(@RequestParam String env,
                                             @RequestParam String productId) {
        return Mono.fromCallable(() -> service.buildForProductId(env, productId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Scans what exists in CT for a productId (master product + variant products + sku counts). */
    @GetMapping("/cleanup/scan")
    public Mono<Map<String, Object>> cleanupScan(@RequestParam String env,
                                                 @RequestParam String productId) {
        return Mono.fromCallable(() -> cleanupService.scan(env, productId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Deletes the selected CT product tree (master product and/or variant products). Destructive. */
    @PostMapping("/cleanup/delete")
    public Mono<Map<String, Object>> cleanupDelete(@RequestBody CtCleanupRequest request) {
        return Mono.fromCallable(() -> cleanupService.delete(request))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
