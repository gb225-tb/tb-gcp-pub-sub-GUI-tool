package com.internal.tools.pubsubgui.web;

import com.internal.tools.pubsubgui.service.HclBuildService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * REST endpoints for the HCL Data Explorer. Both endpoints run the blocking DB2 work on the bounded
 * elastic scheduler. DB2 URLs / credentials never leave the server — only the status bulb result and
 * the assembled (read-only) documents are returned.
 */
@RestController
@RequestMapping("/api/hcl")
public class HclController {

    private final HclBuildService service;

    public HclController(HclBuildService service) {
        this.service = service;
    }

    /** VPN / DB2 reachability probe for the environment (feeds the status bulb). */
    @GetMapping("/status")
    public Mono<Map<String, Object>> status(@RequestParam String env) {
        return Mono.fromCallable(() -> service.probe(env)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Resolves the part number and returns the 7 documents the HCL migration would build (no writes). */
    @GetMapping("/product")
    public Mono<Map<String, Object>> product(@RequestParam String env,
                                             @RequestParam String productId) {
        return Mono.fromCallable(() -> service.buildForProductId(env, productId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
