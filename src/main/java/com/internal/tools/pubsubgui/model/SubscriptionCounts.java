package com.internal.tools.pubsubgui.model;

/**
 * Message counts for a single subscription, derived from Cloud Monitoring.
 *
 * @param ack    messages acknowledged (consumed) within the lookback window
 * @param nonAck messages currently un-acknowledged (the live backlog)
 * @param total  ack + nonAck
 */
public record SubscriptionCounts(
        String subscriptionId,
        long total,
        long ack,
        long nonAck,
        boolean available,
        String note) {

    public static SubscriptionCounts unavailable(String subscriptionId, String note) {
        return new SubscriptionCounts(subscriptionId, 0, 0, 0, false, note);
    }

    public static SubscriptionCounts of(String subscriptionId, long ack, long nonAck) {
        return new SubscriptionCounts(subscriptionId, ack + nonAck, ack, nonAck, true, null);
    }
}
