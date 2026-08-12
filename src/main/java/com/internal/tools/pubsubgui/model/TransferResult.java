package com.internal.tools.pubsubgui.model;

import java.util.List;

/**
 * Outcome of a cross-environment topic transfer.
 *
 * @param sourceEnv            source environment name
 * @param targetEnv            target environment name
 * @param sourceProject        resolved source GCP project id
 * @param targetProject        resolved target GCP project id
 * @param sourceTopicId        source topic id
 * @param targetTopicId        target topic id
 * @param sourceSubscriptionId subscription whose backlog was read
 * @param read                 number of messages peeked from the source (non-destructive)
 * @param published            number successfully published to the target
 * @param failed               number that failed to publish
 * @param dryRun               whether this was a preview (nothing published)
 * @param errors               up to a few per-message publish error strings
 * @param sampleIds            source message ids (capped) for reference
 * @param messages             a capped preview of the peeked messages (data + attributes)
 */
public record TransferResult(
        String sourceEnv,
        String targetEnv,
        String sourceProject,
        String targetProject,
        String sourceTopicId,
        String targetTopicId,
        String sourceSubscriptionId,
        int read,
        int published,
        int failed,
        boolean dryRun,
        List<String> errors,
        List<String> sampleIds,
        List<MessageView> messages) {
}
