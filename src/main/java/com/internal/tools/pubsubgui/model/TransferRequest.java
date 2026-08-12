package com.internal.tools.pubsubgui.model;

/**
 * Request to copy a topic's currently-available (unacknowledged) messages from one environment to the
 * same-named topic in another environment. The read is non-destructive (peek): the source subscription's
 * backlog is left untouched. Prod is never a valid source or target.
 *
 * @param sourceEnv            source environment name (e.g. {@code Dev}); Prod is rejected
 * @param targetEnv            target environment name (e.g. {@code QA}); Prod is rejected
 * @param sourceTopicId        the source topic id (must belong to {@code sourceEnv})
 * @param targetTopicId        the target topic id (must belong to {@code targetEnv})
 * @param sourceSubscriptionId subscription whose backlog is read; optional when the source topic has a
 *                             single subscription (then it is used automatically)
 * @param max                  max messages to copy (1..1000; default 100)
 * @param dryRun               when true, only peek + report what would be copied (nothing is published)
 */
public record TransferRequest(
        String sourceEnv,
        String targetEnv,
        String sourceTopicId,
        String targetTopicId,
        String sourceSubscriptionId,
        Integer max,
        Boolean dryRun) {
}
