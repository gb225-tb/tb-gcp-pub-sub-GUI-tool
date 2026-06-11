package com.internal.tools.pubsubgui.model;

/** A Pub/Sub subscription with the details most useful in a GUI. */
public record SubscriptionInfo(
        String id,
        String name,
        String topic,
        String topicId,
        int ackDeadlineSeconds,
        boolean retainAckedMessages,
        String messageRetentionDuration,
        boolean hasPush,
        String pushEndpoint) {
}
