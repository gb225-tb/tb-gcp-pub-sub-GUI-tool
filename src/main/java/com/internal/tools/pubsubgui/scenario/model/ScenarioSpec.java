package com.internal.tools.pubsubgui.scenario.model;

/**
 * Static definition of one injectable job scenario (Perf only). Streaming scenarios publish
 * {@code sampleResource} to {@code topicId}; batch scenarios upload a file under
 * {@code gcsBucket/gcsObjectPrefix} then dispatch {@code workflowFile} in {@code githubRepo} with the
 * {@code processor} + {@code environment=perf} + {@code version} inputs. After a wait, the outcome is
 * verified per {@link #verifyMode()}.
 *
 * @param id             stable id, e.g. {@code CAT-STREAM-ITEM}
 * @param category       dropdown bucket
 * @param shortName      short human label shown in the Scenario dropdown, e.g. "Universe Item"
 * @param kind           streaming or batch
 * @param processor      Dataflow processor / job name (batch dispatch input; informational for streaming)
 * @param description    one-line explanation of what is injected + verified
 * @param enabled        false for selectable-but-disabled scenarios (registered, not yet wired)
 * @param topicId        streaming: short Pub/Sub topic id to publish to (must be allow-listed)
 * @param gcsBucket      batch: inbound bucket (no gs:// prefix), e.g. {@code np-ecom-2-catalog}
 * @param gcsObjectPrefix batch: object folder under the bucket, e.g. {@code retail/universe/item/}
 * @param defaultFileName batch: default object file name for the uploaded sample
 * @param githubRepo     batch: {@code owner/repo} hosting the workflow
 * @param workflowFile   batch: workflow file name, e.g. {@code np-batch-scheduler.yaml}
 * @param sampleResource classpath sample payload/file used to prefill the UI
 * @param verifyMode     how to verify the outcome
 * @param verifyGroup    for CATALOG_VALIDATORS: the {@code ScenarioGroup} name to scope validators to
 * @param verifyItemDb   for INVENTORY_PRESENCE: the logical mongo db to check (inventory-config / inventory-runtime)
 */
public record ScenarioSpec(
        String id,
        ScenarioCategory category,
        String shortName,
        ScenarioKind kind,
        String processor,
        String description,
        boolean enabled,
        String topicId,
        String gcsBucket,
        String gcsObjectPrefix,
        String defaultFileName,
        String githubRepo,
        String workflowFile,
        String sampleResource,
        VerifyMode verifyMode,
        String verifyGroup,
        String verifyItemDb) {

    /** Human-readable injection target for confirmation dialogs / display. */
    public String target() {
        if (kind == ScenarioKind.STREAMING) {
            return "topic " + topicId;
        }
        return "gs://" + gcsBucket + "/" + gcsObjectPrefix + defaultFileName
                + " + workflow " + workflowFile + " (" + githubRepo + ")";
    }
}
