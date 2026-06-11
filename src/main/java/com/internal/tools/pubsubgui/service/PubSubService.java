package com.internal.tools.pubsubgui.service;

import com.google.api.core.ApiService;
import com.google.api.gax.rpc.NotFoundException;
import com.google.api.core.ApiFuture;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.stub.SubscriberStub;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.ModifyAckDeadlineRequest;
import com.google.pubsub.v1.ProjectName;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.SubscriptionName;
import com.google.pubsub.v1.Topic;
import com.google.pubsub.v1.TopicName;
import com.internal.tools.pubsubgui.config.PubSubClientFactory;
import com.internal.tools.pubsubgui.model.MessageView;
import com.internal.tools.pubsubgui.model.PublishMessageRequest;
import com.internal.tools.pubsubgui.model.SubscriptionCounts;
import com.internal.tools.pubsubgui.model.SubscriptionInfo;
import com.internal.tools.pubsubgui.model.TopicCounts;
import com.internal.tools.pubsubgui.model.TopicInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-and-monitor operations over Pub/Sub. Message viewing is strictly
 * non-destructive (peek), counts come from Cloud Monitoring, and the only
 * destructive action is the explicit drain-{@link #purgeSubscription purge}.
 */
@Service
public class PubSubService {

    private static final Logger log = LoggerFactory.getLogger(PubSubService.class);

    /** Purge is considered done once no message arrives for this quiet period. */
    private static final long PURGE_QUIET_MS = 3_000;
    /** Hard upper bound for a single purge, regardless of backlog. */
    private static final long PURGE_MAX_MS = 120_000;

    private final PubSubClientFactory clients;
    private final MonitoringService monitoring;

    public PubSubService(PubSubClientFactory clients, MonitoringService monitoring) {
        this.clients = clients;
        this.monitoring = monitoring;
    }

    /** Guard that fails when a topic is outside the configured allow-list. */
    private void requireAllowed(String topicId) {
        if (!clients.properties().isTopicAllowed(topicId)) {
            throw new TopicNotAllowedException(topicId);
        }
    }

    // ---------------------------------------------------------------- Topics

    public List<TopicInfo> listTopics(String projectId) throws IOException {
        String project = clients.resolveProjectId(projectId);

        // When restricted, never enumerate the whole project: return exactly
        // the configured topics (only needs per-topic permissions).
        if (clients.properties().isRestricted()) {
            List<TopicInfo> result = new ArrayList<>();
            for (String id : clients.properties().allTopics()) {
                result.add(new TopicInfo(id, TopicName.of(project, id).toString()));
            }
            result.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
            return result;
        }

        TopicAdminClient client = clients.topicAdminClient();
        List<TopicInfo> result = new ArrayList<>();
        for (Topic topic : client.listTopics(ProjectName.of(project)).iterateAll()) {
            TopicName name = TopicName.parse(topic.getName());
            result.add(new TopicInfo(name.getTopic(), topic.getName()));
        }
        result.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return result;
    }

    // --------------------------------------------------------- Subscriptions

    public List<SubscriptionInfo> listSubscriptions(String projectId) throws IOException {
        String project = clients.resolveProjectId(projectId);

        if (clients.properties().isRestricted()) {
            List<SubscriptionInfo> result = new ArrayList<>();
            for (String topicId : clients.properties().allTopics()) {
                try {
                    result.addAll(listSubscriptionsForTopic(project, topicId));
                } catch (NotFoundException e) {
                    // topic does not exist yet; skip it
                } catch (RuntimeException e) {
                    log.warn("Could not list subscriptions for topic {}: {}", topicId, e.getMessage());
                }
            }
            result.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
            return result;
        }

        SubscriptionAdminClient client = clients.subscriptionAdminClient();
        List<SubscriptionInfo> result = new ArrayList<>();
        for (Subscription sub : client.listSubscriptions(ProjectName.of(project)).iterateAll()) {
            result.add(toSubscriptionInfo(sub));
        }
        result.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return result;
    }

    public List<SubscriptionInfo> listSubscriptionsForTopic(String projectId, String topicId) throws IOException {
        requireAllowed(topicId);
        String project = clients.resolveProjectId(projectId);
        TopicAdminClient client = clients.topicAdminClient();
        SubscriptionAdminClient subClient = clients.subscriptionAdminClient();
        List<SubscriptionInfo> result = new ArrayList<>();
        for (String subName : client.listTopicSubscriptions(TopicName.of(project, topicId)).iterateAll()) {
            try {
                result.add(toSubscriptionInfo(subClient.getSubscription(subName)));
            } catch (NotFoundException ignored) {
                // subscription disappeared between listing and fetching
            }
        }
        result.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return result;
    }

    // ---------------------------------------------------------------- Counts

    /** Aggregated Total / ACK / Non-ACK across a topic's subscriptions. */
    public TopicCounts topicCounts(String projectId, String topicId) throws IOException {
        requireAllowed(topicId);
        String project = clients.resolveProjectId(projectId);
        List<SubscriptionInfo> subs = listSubscriptionsForTopic(project, topicId);

        if (subs.isEmpty()) {
            return new TopicCounts(topicId, 0, 0, 0, false,
                    "No subscriptions on this topic, so there are no messages to count.", List.of());
        }

        List<SubscriptionCounts> perSub = new ArrayList<>();
        long ack = 0;
        long nonAck = 0;
        boolean available = true;
        String note = null;
        for (SubscriptionInfo s : subs) {
            SubscriptionCounts c = monitoring.countsFor(project, s.id());
            perSub.add(c);
            if (c.available()) {
                ack += c.ack();
                nonAck += c.nonAck();
            } else {
                available = false;
                if (note == null) {
                    note = c.note();
                }
            }
        }
        return new TopicCounts(topicId, ack + nonAck, ack, nonAck, available, note, perSub);
    }

    public SubscriptionCounts subscriptionCounts(String projectId, String subscriptionId) {
        String project = clients.resolveProjectId(projectId);
        return monitoring.countsFor(project, subscriptionId);
    }

    // --------------------------------------------------------------- Publish

    /**
     * Publish a message to a topic and return the assigned message id. Pairs
     * nicely with the live tail: publish here, watch it stream in below.
     */
    public String publish(String projectId, String topicId, PublishMessageRequest req)
            throws IOException, InterruptedException {
        requireAllowed(topicId);
        String project = clients.resolveProjectId(projectId);
        Publisher publisher = clients.publisher(project, topicId);

        PubsubMessage.Builder b = PubsubMessage.newBuilder();
        if (req != null && req.data() != null) {
            b.setData(ByteString.copyFromUtf8(req.data()));
        }
        if (req != null && req.attributes() != null) {
            req.attributes().forEach((k, v) -> {
                if (k != null && !k.isBlank()) {
                    b.putAttributes(k, v == null ? "" : v);
                }
            });
        }
        if (req != null && req.orderingKey() != null && !req.orderingKey().isBlank()) {
            b.setOrderingKey(req.orderingKey().trim());
        }

        ApiFuture<String> future = publisher.publish(b.build());
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("Publish failed: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Publish timed out after 30s", e);
        }
    }

    // ---------------------------------------------------------- View (peek)

    /**
     * Peek up to {@code maxMessages} messages without consuming them. Messages
     * are pulled and immediately released back to the subscription (nack), so
     * this never removes anything or affects real consumers' delivery.
     */
    public List<MessageView> peek(String projectId, String subscriptionId, int maxMessages) throws IOException {
        String project = clients.resolveProjectId(projectId);
        SubscriberStub stub = clients.subscriberStub();
        String subscription = ProjectSubscriptionName.format(project, subscriptionId);

        PullRequest pullRequest = PullRequest.newBuilder()
                .setSubscription(subscription)
                .setMaxMessages(Math.max(1, Math.min(maxMessages, 1000)))
                .setReturnImmediately(true)
                .build();

        PullResponse response = stub.pullCallable().call(pullRequest);
        List<ReceivedMessage> received = response.getReceivedMessagesList();

        List<MessageView> views = new ArrayList<>(received.size());
        List<String> ackIds = new ArrayList<>(received.size());
        for (ReceivedMessage rm : received) {
            ackIds.add(rm.getAckId());
            views.add(toMessageView(rm));
        }

        // Release immediately so the messages stay in the subscription.
        if (!ackIds.isEmpty()) {
            stub.modifyAckDeadlineCallable().call(ModifyAckDeadlineRequest.newBuilder()
                    .setSubscription(subscription)
                    .addAllAckIds(ackIds)
                    .setAckDeadlineSeconds(0)
                    .build());
        }
        return views;
    }

    /** Convenience for the UI "View" action: the single latest available message. */
    public List<MessageView> latest(String projectId, String subscriptionId) throws IOException {
        return peek(projectId, subscriptionId, 1);
    }

    // ----------------------------------------------------------------- Purge

    /**
     * Destructively drains a subscription by acknowledging every message until
     * the backlog goes quiet. A {@link CountDownLatch} blocks until either no
     * message has arrived for {@link #PURGE_QUIET_MS} or {@link #PURGE_MAX_MS}
     * elapses. Returns the number of messages purged.
     *
     * <p>WARNING: this consumes messages on the given subscription, so any
     * consumers sharing that subscription will not receive them.
     */
    public long purgeSubscription(String projectId, String subscriptionId) throws InterruptedException {
        String project = clients.resolveProjectId(projectId);
        ProjectSubscriptionName sub = ProjectSubscriptionName.of(project, subscriptionId);

        AtomicLong purged = new AtomicLong();
        AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        CountDownLatch done = new CountDownLatch(1);

        MessageReceiver receiver = (PubsubMessage message, AckReplyConsumer consumer) -> {
            consumer.ack();
            purged.incrementAndGet();
            lastActivity.set(System.currentTimeMillis());
        };

        Subscriber subscriber = clients.buildSubscriber(sub, receiver);
        subscriber.addListener(new ApiService.Listener() {
            @Override
            public void failed(ApiService.State from, Throwable failure) {
                log.warn("Purge subscriber for {} failed: {}", subscriptionId, failure.getMessage());
                done.countDown();
            }
        }, MoreExecutors.directExecutor());

        ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        long start = System.currentTimeMillis();
        watcher.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            if (now - lastActivity.get() >= PURGE_QUIET_MS || now - start >= PURGE_MAX_MS) {
                done.countDown();
            }
        }, PURGE_QUIET_MS, 500, TimeUnit.MILLISECONDS);

        try {
            subscriber.startAsync().awaitRunning();
            log.info("Purge (drain) started on subscription {}", subscriptionId);
            done.await(PURGE_MAX_MS + PURGE_QUIET_MS + 5_000, TimeUnit.MILLISECONDS);
        } finally {
            watcher.shutdownNow();
            try {
                subscriber.stopAsync().awaitTerminated(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort shutdown
            }
        }
        long total = purged.get();
        log.info("Purge of subscription {} complete: {} message(s) acknowledged", subscriptionId, total);
        return total;
    }

    /** Purge every subscription attached to a topic; returns per-subscription counts. */
    public Map<String, Long> purgeTopic(String projectId, String topicId) throws IOException, InterruptedException {
        requireAllowed(topicId);
        String project = clients.resolveProjectId(projectId);
        Map<String, Long> result = new LinkedHashMap<>();
        for (SubscriptionInfo sub : listSubscriptionsForTopic(project, topicId)) {
            result.put(sub.id(), purgeSubscription(project, sub.id()));
        }
        return result;
    }

    // ------------------------------------------------------------- Live tail

    /**
     * Stream messages published to a topic in real time. A short-lived
     * subscription is created for the duration of the stream and deleted when
     * the client disconnects, so existing consumers are never affected.
     */
    public Flux<MessageView> tailTopic(String projectId, String topicId) {
        requireAllowed(topicId);
        String project = clients.resolveProjectId(projectId);
        TopicName topicName = TopicName.of(project, topicId);

        return Flux.<MessageView>create(sink -> {
            String tailId = "tail-" + topicId + "-" + UUID.randomUUID().toString().substring(0, 8);
            SubscriptionName subName = SubscriptionName.of(project, tailId);

            try {
                clients.subscriptionAdminClient().createSubscription(Subscription.newBuilder()
                        .setName(subName.toString())
                        .setTopic(topicName.toString())
                        .setAckDeadlineSeconds(10)
                        .build());
            } catch (Exception e) {
                sink.error(e);
                return;
            }

            MessageReceiver receiver = (PubsubMessage message, AckReplyConsumer consumer) -> {
                sink.next(toMessageView(message));
                consumer.ack();
            };
            Subscriber subscriber = clients.buildSubscriber(
                    ProjectSubscriptionName.of(project, tailId), receiver);

            AtomicBoolean cleaned = new AtomicBoolean(false);
            Runnable cleanup = () -> {
                if (!cleaned.compareAndSet(false, true)) {
                    return;
                }
                try {
                    subscriber.stopAsync();
                } catch (Exception ignored) {
                    // best effort
                }
                try {
                    clients.subscriptionAdminClient().deleteSubscription(subName);
                    log.info("Live tail on '{}' stopped; deleted {}", topicId, tailId);
                } catch (Exception e) {
                    log.warn("Failed to delete tail subscription {}: {}", subName, e.getMessage());
                }
            };

            subscriber.addListener(new ApiService.Listener() {
                @Override
                public void failed(ApiService.State from, Throwable failure) {
                    sink.error(failure);
                }
            }, MoreExecutors.directExecutor());

            sink.onDispose(cleanup::run);

            try {
                subscriber.startAsync().awaitRunning();
                log.info("Live tail started on topic '{}' via {}", topicId, tailId);
            } catch (Exception e) {
                cleanup.run();
                sink.error(e);
            }
        }, FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }

    // --------------------------------------------------------------- Mapping

    private SubscriptionInfo toSubscriptionInfo(Subscription sub) {
        SubscriptionName name = SubscriptionName.parse(sub.getName());
        String topicPath = sub.getTopic();
        String topicId = "_deleted-topic_";
        if (topicPath != null && topicPath.contains("/topics/")) {
            topicId = topicPath.substring(topicPath.lastIndexOf('/') + 1);
        }
        PushConfig push = sub.getPushConfig();
        boolean hasPush = push != null && push.getPushEndpoint() != null && !push.getPushEndpoint().isBlank();
        String retention = sub.hasMessageRetentionDuration()
                ? sub.getMessageRetentionDuration().getSeconds() + "s"
                : "";
        return new SubscriptionInfo(
                name.getSubscription(),
                sub.getName(),
                topicPath,
                topicId,
                sub.getAckDeadlineSeconds(),
                sub.getRetainAckedMessages(),
                retention,
                hasPush,
                hasPush ? push.getPushEndpoint() : "");
    }

    private MessageView toMessageView(ReceivedMessage rm) {
        PubsubMessage m = rm.getMessage();
        return new MessageView(
                m.getMessageId(),
                rm.getAckId(),
                m.getData().toStringUtf8(),
                m.getAttributesMap(),
                m.getOrderingKey(),
                formatPublishTime(m),
                rm.getDeliveryAttempt());
    }

    private MessageView toMessageView(PubsubMessage m) {
        return new MessageView(
                m.getMessageId(),
                "",
                m.getData().toStringUtf8(),
                m.getAttributesMap(),
                m.getOrderingKey(),
                formatPublishTime(m),
                0);
    }

    private String formatPublishTime(PubsubMessage m) {
        if (!m.hasPublishTime()) {
            return "";
        }
        Timestamp t = m.getPublishTime();
        return Instant.ofEpochSecond(t.getSeconds(), t.getNanos()).toString();
    }

    /** Thrown when an operation targets a topic outside the allow-list. */
    public static class TopicNotAllowedException extends RuntimeException {
        public TopicNotAllowedException(String topicId) {
            super("Topic '" + topicId + "' is not in the allowed-topics list configured for this tool.");
        }
    }
}
