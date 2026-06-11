package com.internal.tools.pubsubgui.web;

import com.internal.tools.pubsubgui.model.MessageView;
import com.internal.tools.pubsubgui.model.SubscriptionCounts;
import com.internal.tools.pubsubgui.model.SubscriptionInfo;
import com.internal.tools.pubsubgui.service.PubSubService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final PubSubService service;

    public SubscriptionController(PubSubService service) {
        this.service = service;
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<List<SubscriptionInfo>> list(@RequestParam(required = false) String project) {
        return blocking(() -> service.listSubscriptions(project));
    }

    @GetMapping("/{subscriptionId}/counts")
    public Mono<SubscriptionCounts> counts(@RequestParam(required = false) String project,
                                           @PathVariable String subscriptionId) {
        return blocking(() -> service.subscriptionCounts(project, subscriptionId));
    }

    /** Non-destructive peek: view messages without consuming them. */
    @PostMapping("/{subscriptionId}/peek")
    public Mono<List<MessageView>> peek(@RequestParam(required = false) String project,
                                        @PathVariable String subscriptionId,
                                        @RequestParam(defaultValue = "10") int max) {
        return blocking(() -> service.peek(project, subscriptionId, max));
    }

    /** View the single latest message (non-destructive). */
    @PostMapping("/{subscriptionId}/latest")
    public Mono<List<MessageView>> latest(@RequestParam(required = false) String project,
                                          @PathVariable String subscriptionId) {
        return blocking(() -> service.latest(project, subscriptionId));
    }

    /**
     * Live tail: stream messages flowing through this subscription in real time
     * via Server-Sent Events, without acknowledging them (released back so the
     * real consumer still receives them). No temporary subscription is created.
     * A heartbeat comment keeps idle connections open.
     */
    @GetMapping(value = "/{subscriptionId}/tail", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<MessageView>> tail(@RequestParam(required = false) String project,
                                                   @PathVariable String subscriptionId) {
        Flux<ServerSentEvent<MessageView>> messages = service.tailSubscription(project, subscriptionId)
                .map(m -> ServerSentEvent.builder(m).event("message").build());

        Flux<ServerSentEvent<MessageView>> heartbeat = Flux.interval(Duration.ofSeconds(20))
                .map(i -> ServerSentEvent.<MessageView>builder().comment("ping").build());

        return Flux.merge(messages, heartbeat);
    }

    /** Destructive: drain (purge) all messages from the subscription. */
    @PostMapping("/{subscriptionId}/purge")
    public Mono<Map<String, Object>> purge(@RequestParam(required = false) String project,
                                           @PathVariable String subscriptionId) {
        return blocking(() -> {
            long purged = service.purgeSubscription(project, subscriptionId);
            return Map.of("status", "purged", "subscriptionId", subscriptionId, "purged", purged);
        });
    }
}
