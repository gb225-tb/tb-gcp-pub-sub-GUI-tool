package com.internal.tools.pubsubgui.service;

import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.ProjectName;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.protobuf.util.Timestamps;
import com.internal.tools.pubsubgui.config.PubSubClientFactory;
import com.internal.tools.pubsubgui.model.SubscriptionCounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Reads subscription message counts from Cloud Monitoring. This is purely
 * read-only metadata and has zero effect on message delivery.
 *
 * <ul>
 *   <li>Non-ACK (backlog) = latest {@code subscription/num_undelivered_messages}</li>
 *   <li>ACK (consumed) = {@code subscription/ack_message_count} summed over a window</li>
 *   <li>Total = ACK + Non-ACK</li>
 * </ul>
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    /** Lookback window for the "consumed" (ack) metric: 24 hours. */
    private static final long ACK_WINDOW_MS = 24L * 60 * 60 * 1000;
    /** Backlog is a gauge sampled ~every 60s; look back a few minutes for the latest point. */
    private static final long BACKLOG_WINDOW_MS = 10L * 60 * 1000;

    private static final String ACK_METRIC = "pubsub.googleapis.com/subscription/ack_message_count";
    private static final String BACKLOG_METRIC = "pubsub.googleapis.com/subscription/num_undelivered_messages";

    private final PubSubClientFactory clients;

    public MonitoringService(PubSubClientFactory clients) {
        this.clients = clients;
    }

    public SubscriptionCounts countsFor(String projectId, String subscriptionId) {
        if (clients.isEmulator()) {
            return SubscriptionCounts.unavailable(subscriptionId, "Counts require Cloud Monitoring (not available with the emulator).");
        }
        String project = clients.resolveProjectId(projectId);
        try {
            MetricServiceClient client = clients.metricServiceClient();
            long ack = sumOverWindow(client, project, subscriptionId, ACK_METRIC, ACK_WINDOW_MS);
            long backlog = latestValue(client, project, subscriptionId, BACKLOG_METRIC, BACKLOG_WINDOW_MS);
            return SubscriptionCounts.of(subscriptionId, ack, backlog);
        } catch (Exception e) {
            log.warn("Failed to read monitoring counts for {}: {}", subscriptionId, e.getMessage());
            return SubscriptionCounts.unavailable(subscriptionId,
                    "Could not read Cloud Monitoring metrics: " + e.getMessage());
        }
    }

    private long sumOverWindow(MetricServiceClient client, String project, String subscriptionId,
                               String metricType, long windowMs) {
        long sum = 0;
        for (TimeSeries series : query(client, project, subscriptionId, metricType, windowMs)) {
            for (Point p : series.getPointsList()) {
                sum += pointValue(p);
            }
        }
        return sum;
    }

    private long latestValue(MetricServiceClient client, String project, String subscriptionId,
                             String metricType, long windowMs) {
        long latest = 0;
        for (TimeSeries series : query(client, project, subscriptionId, metricType, windowMs)) {
            // points come back newest-first; take the first one of each series
            if (!series.getPointsList().isEmpty()) {
                latest += pointValue(series.getPoints(0));
            }
        }
        return latest;
    }

    private Iterable<TimeSeries> query(MetricServiceClient client, String project, String subscriptionId,
                                       String metricType, long windowMs) {
        long now = System.currentTimeMillis();
        TimeInterval interval = TimeInterval.newBuilder()
                .setStartTime(Timestamps.fromMillis(now - windowMs))
                .setEndTime(Timestamps.fromMillis(now))
                .build();
        String filter = String.format(
                "metric.type=\"%s\" AND resource.label.\"subscription_id\"=\"%s\"",
                metricType, subscriptionId);
        ListTimeSeriesRequest request = ListTimeSeriesRequest.newBuilder()
                .setName(ProjectName.of(project).toString())
                .setFilter(filter)
                .setInterval(interval)
                .setView(ListTimeSeriesRequest.TimeSeriesView.FULL)
                .build();
        return client.listTimeSeries(request).iterateAll();
    }

    private long pointValue(Point p) {
        return switch (p.getValue().getValueCase()) {
            case INT64_VALUE -> p.getValue().getInt64Value();
            case DOUBLE_VALUE -> Math.round(p.getValue().getDoubleValue());
            default -> 0L;
        };
    }
}
