package com.internal.tools.pubsubgui.model;

import java.util.List;

/**
 * Aggregated message counts for a topic across all of its subscriptions, plus
 * the per-subscription breakdown.
 */
public record TopicCounts(
        String topicId,
        long total,
        long ack,
        long nonAck,
        boolean available,
        String note,
        List<SubscriptionCounts> subscriptions) {
}
