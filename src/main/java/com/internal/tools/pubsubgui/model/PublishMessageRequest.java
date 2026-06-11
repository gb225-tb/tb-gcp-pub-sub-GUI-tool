package com.internal.tools.pubsubgui.model;

import java.util.Map;

/**
 * Payload for publishing a message to a topic.
 *
 * @param data        UTF-8 message body
 * @param attributes  optional message attributes (string key/value)
 * @param orderingKey optional ordering key
 */
public record PublishMessageRequest(String data, Map<String, String> attributes, String orderingKey) {
}
