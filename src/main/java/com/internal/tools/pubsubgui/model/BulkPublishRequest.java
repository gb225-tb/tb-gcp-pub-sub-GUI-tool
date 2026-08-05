package com.internal.tools.pubsubgui.model;

import java.util.List;
import java.util.Map;

/**
 * Payload for publishing many messages to a topic in one request.
 *
 * @param messages    ready-to-send message bodies (each typically a JSON string)
 * @param attributes  optional attributes applied to every message
 * @param orderingKey optional ordering key applied to every message
 */
public record BulkPublishRequest(List<String> messages, Map<String, String> attributes, String orderingKey) {
}
