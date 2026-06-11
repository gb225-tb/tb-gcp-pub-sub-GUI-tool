package com.internal.tools.pubsubgui.web;

import com.internal.tools.pubsubgui.model.MessageView;
import com.internal.tools.pubsubgui.model.PublishMessageRequest;
import com.internal.tools.pubsubgui.model.SubscriptionInfo;
import com.internal.tools.pubsubgui.model.TopicCounts;
import com.internal.tools.pubsubgui.model.TopicInfo;
import com.internal.tools.pubsubgui.service.PubSubService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
@RequestMapping("/api/topics")
public class TopicController {

    private final PubSubService service;

    public TopicController(PubSubService service) {
        this.service = service;
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<List<TopicInfo>> list(@RequestParam(required = false) String project) {
        return blocking(() -> service.listTopics(project));
    }

    @GetMapping("/{topicId}/subscriptions")
    public Mono<List<SubscriptionInfo>> subscriptions(@RequestParam(required = false) String project,
                                                      @PathVariable String topicId) {
        return blocking(() -> service.listSubscriptionsForTopic(project, topicId));
    }

    @GetMapping("/{topicId}/counts")
    public Mono<TopicCounts> counts(@RequestParam(required = false) String project,
                                    @PathVariable String topicId) {
        return blocking(() -> service.topicCounts(project, topicId));
    }

    @PostMapping("/{topicId}/publish")
    public Mono<Map<String, Object>> publish(@RequestParam(required = false) String project,
                                             @PathVariable String topicId,
                                             @RequestBody PublishMessageRequest body) {
        return blocking(() -> {
            String messageId = service.publish(project, topicId, body);
            return Map.of("status", "published", "topicId", topicId, "messageId", messageId);
        });
    }

    @PostMapping("/{topicId}/purge")
    public Mono<Map<String, Object>> purge(@RequestParam(required = false) String project,
                                           @PathVariable String topicId) {
        return blocking(() -> {
            Map<String, Long> perSub = service.purgeTopic(project, topicId);
            long total = perSub.values().stream().mapToLong(Long::longValue).sum();
            return Map.of("status", "purged", "topicId", topicId, "totalPurged", total, "perSubscription", perSub);
        });
    }

    /**
     * Topic-level live tail via a temporary subscription (auto-deleted on stop).
     * Sees every published message even when other subscriptions are actively
     * consumed. A heartbeat comment keeps idle connections open.
     */
    @GetMapping(value = "/{topicId}/tail", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<MessageView>> tail(@RequestParam(required = false) String project,
                                                   @PathVariable String topicId) {
        Flux<ServerSentEvent<MessageView>> messages = service.tailTopic(project, topicId)
                .map(m -> ServerSentEvent.builder(m).event("message").build());

        Flux<ServerSentEvent<MessageView>> heartbeat = Flux.interval(Duration.ofSeconds(20))
                .map(i -> ServerSentEvent.<MessageView>builder().comment("ping").build());

        return Flux.merge(messages, heartbeat);
    }
}
