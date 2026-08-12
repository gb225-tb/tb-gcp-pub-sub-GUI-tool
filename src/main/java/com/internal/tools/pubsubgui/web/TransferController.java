package com.internal.tools.pubsubgui.web;

import com.internal.tools.pubsubgui.model.TransferRequest;
import com.internal.tools.pubsubgui.model.TransferResult;
import com.internal.tools.pubsubgui.service.PubSubService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Cross-environment topic message transfer. Reads a source topic's currently-available (unacknowledged)
 * backlog non-destructively and republishes it to the same-named topic in another environment. Prod is
 * never allowed as source or target (enforced in {@link PubSubService#transferTopic}).
 */
@RestController
@RequestMapping("/api/transfer")
public class TransferController {

    private final PubSubService service;

    public TransferController(PubSubService service) {
        this.service = service;
    }

    @PostMapping("/topic")
    public Mono<TransferResult> transfer(@RequestBody TransferRequest request) {
        return Mono.fromCallable(() -> service.transferTopic(request)).subscribeOn(Schedulers.boundedElastic());
    }
}
