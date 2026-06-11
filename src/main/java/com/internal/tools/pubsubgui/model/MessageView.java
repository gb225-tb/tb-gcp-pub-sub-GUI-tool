package com.internal.tools.pubsubgui.model;

import java.util.Map;

/** A single pulled message rendered for the UI. */
public record MessageView(
        String messageId,
        String ackId,
        String data,
        Map<String, String> attributes,
        String orderingKey,
        String publishTime,
        int deliveryAttempt,
        String source) {
}
