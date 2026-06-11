package com.internal.tools.pubsubgui.config;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.ServiceOptions;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.TopicName;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Centralizes creation of Pub/Sub clients. Transparently supports either real
 * GCP (via Application Default Credentials) or the local Pub/Sub emulator.
 */
@Component
public class PubSubClientFactory {

    private static final Logger log = LoggerFactory.getLogger(PubSubClientFactory.class);

    private final PubSubProperties properties;

    private volatile ManagedChannel emulatorChannel;
    private volatile TransportChannelProvider channelProvider;

    private volatile TopicAdminClient topicAdminClient;
    private volatile SubscriptionAdminClient subscriptionAdminClient;
    private volatile SubscriberStub subscriberStub;
    private volatile MetricServiceClient metricServiceClient;

    /** One cached, reusable Publisher per "project/topic". */
    private final Map<String, Publisher> publishers = new ConcurrentHashMap<>();

    public PubSubClientFactory(PubSubProperties properties) {
        this.properties = properties;
        if (properties.isEmulator()) {
            log.info("Pub/Sub emulator mode enabled. Host: {}", properties.getEmulatorHost());
            this.emulatorChannel = ManagedChannelBuilder.forTarget(properties.getEmulatorHost())
                    .usePlaintext()
                    .build();
            this.channelProvider = FixedTransportChannelProvider.create(
                    GrpcTransportChannel.create(emulatorChannel));
        } else {
            log.info("Pub/Sub real-GCP mode (Application Default Credentials).");
        }
    }

    public boolean isEmulator() {
        return properties.isEmulator();
    }

    public String getEmulatorHost() {
        return properties.getEmulatorHost();
    }

    /**
     * Resolve the project id for a request. Explicit value wins, then the
     * configured default, then the ADC / environment default.
     */
    public String resolveProjectId(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        if (!properties.getProjectId().isBlank()) {
            return properties.getProjectId();
        }
        String detected = ServiceOptions.getDefaultProjectId();
        if (detected == null || detected.isBlank()) {
            throw new IllegalStateException(
                    "No GCP project id available. Set it in the UI, configure pubsub.project-id, "
                            + "or define GOOGLE_CLOUD_PROJECT / run 'gcloud config set project'.");
        }
        return detected;
    }

    public synchronized TopicAdminClient topicAdminClient() throws IOException {
        if (topicAdminClient == null) {
            TopicAdminSettings.Builder b = TopicAdminSettings.newBuilder();
            if (isEmulator()) {
                b.setTransportChannelProvider(channelProvider);
                b.setCredentialsProvider(NoCredentialsProvider.create());
            }
            topicAdminClient = TopicAdminClient.create(b.build());
        }
        return topicAdminClient;
    }

    public synchronized SubscriptionAdminClient subscriptionAdminClient() throws IOException {
        if (subscriptionAdminClient == null) {
            SubscriptionAdminSettings.Builder b = SubscriptionAdminSettings.newBuilder();
            if (isEmulator()) {
                b.setTransportChannelProvider(channelProvider);
                b.setCredentialsProvider(NoCredentialsProvider.create());
            }
            subscriptionAdminClient = SubscriptionAdminClient.create(b.build());
        }
        return subscriptionAdminClient;
    }

    public synchronized SubscriberStub subscriberStub() throws IOException {
        if (subscriberStub == null) {
            SubscriberStubSettings.Builder b = SubscriberStubSettings.newBuilder();
            if (isEmulator()) {
                b.setTransportChannelProvider(channelProvider);
                b.setCredentialsProvider(NoCredentialsProvider.create());
            }
            subscriberStub = GrpcSubscriberStub.create(b.build());
        }
        return subscriberStub;
    }

    /**
     * Cloud Monitoring client for reading subscription metrics. Not available
     * against the emulator (returns {@code null}); always uses ADC on real GCP.
     */
    public synchronized MetricServiceClient metricServiceClient() throws IOException {
        if (isEmulator()) {
            return null;
        }
        if (metricServiceClient == null) {
            metricServiceClient = MetricServiceClient.create();
        }
        return metricServiceClient;
    }

    /**
     * Cached Publisher for a topic. Message ordering is enabled so callers may
     * optionally set an ordering key; messages without one publish normally.
     */
    public Publisher publisher(String project, String topicId) throws IOException {
        String key = project + "/" + topicId;
        Publisher existing = publishers.get(key);
        if (existing != null) {
            return existing;
        }
        synchronized (publishers) {
            existing = publishers.get(key);
            if (existing != null) {
                return existing;
            }
            Publisher.Builder b = Publisher.newBuilder(TopicName.of(project, topicId))
                    .setEnableMessageOrdering(true);
            if (isEmulator()) {
                b.setChannelProvider(channelProvider);
                b.setCredentialsProvider(NoCredentialsProvider.create());
            }
            Publisher publisher = b.build();
            publishers.put(key, publisher);
            return publisher;
        }
    }

    /** Build a streaming-pull Subscriber that pushes messages to {@code receiver}. */
    public Subscriber buildSubscriber(ProjectSubscriptionName subscription, MessageReceiver receiver) {
        Subscriber.Builder b = Subscriber.newBuilder(subscription, receiver);
        if (isEmulator()) {
            b.setChannelProvider(channelProvider);
            b.setCredentialsProvider(NoCredentialsProvider.create());
        }
        return b.build();
    }

    public PubSubProperties properties() {
        return properties;
    }

    @PreDestroy
    public void shutdown() {
        for (Publisher publisher : publishers.values()) {
            try {
                publisher.shutdown();
                publisher.awaitTermination(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Error closing publisher", e);
            }
        }
        publishers.clear();
        closeQuietly(topicAdminClient);
        closeQuietly(subscriptionAdminClient);
        closeQuietly(subscriberStub);
        closeQuietly(metricServiceClient);
        if (emulatorChannel != null) {
            emulatorChannel.shutdown();
        }
    }

    private void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("Error closing client", e);
            }
        }
    }
}
