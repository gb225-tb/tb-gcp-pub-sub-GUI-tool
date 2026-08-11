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
 * @param verifyCollection for CATALOG_PRESENCE: the {@code item-config} collection to check (e.g. Rating, Attribute)
 * @param verifyKeyField for CATALOG_PRESENCE streaming: JSON field to read the key from (e.g. productId, partNumber);
 *                       arrays use the first element. Ignored for batch.
 * @param verifyKeyColumn for CATALOG_PRESENCE batch: CSV column name to read the key from (first data row)
 * @param verifyMatchField the document field the key is matched against (default {@code _id} when blank)
 * @param verifyAssertField optional: after finding the doc, assert this field equals {@code verifyAssertValue}
 * @param verifyAssertValue optional: expected value for {@code verifyAssertField} (case-insensitive)
 * @param cleanupCollections optional comma-separated collections to purge (by the scenario key) BEFORE injection
 *                          when the user opts in. Blank/null means cleanup is not supported for this scenario.
 *                          The cleanup db is {@code verifyItemDb} for inventory scenarios, else {@code item-config}.
 * @param requiresFullFeed true for full-load RECONCILIATION batch jobs (universe item/price full load, CF retail
 *                        enriched full sync, inventory full load) that deactivate/zero-out anything NOT in the
 *                        uploaded file. Such scenarios must NOT run with the tiny bundled sample (it would
 *                        inactivate the rest of Perf) — the run is blocked unless a COMPLETE feed file is uploaded.
 *                        The bundled sample is retained only as a format reference.
 * @param verifyFixedKey  optional literal key to verify against when no key can be derived from an injected
 *                        payload/file. Used by dispatch-only, Mongo-derived batch jobs (e.g. the bundle readiness
 *                        batch) that consume no file — the verify checks a known golden id instead.
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
        String verifyItemDb,
        String verifyCollection,
        String verifyKeyField,
        String verifyKeyColumn,
        String verifyMatchField,
        String verifyAssertField,
        String verifyAssertValue,
        String cleanupCollections,
        boolean requiresFullFeed,
        String verifyFixedKey) {

    /** Backwards-compatible constructor for scenarios that derive their verify key from the payload/file. */
    public ScenarioSpec(
            String id, ScenarioCategory category, String shortName, ScenarioKind kind, String processor,
            String description, boolean enabled, String topicId, String gcsBucket, String gcsObjectPrefix,
            String defaultFileName, String githubRepo, String workflowFile, String sampleResource,
            VerifyMode verifyMode, String verifyGroup, String verifyItemDb, String verifyCollection,
            String verifyKeyField, String verifyKeyColumn, String verifyMatchField, String verifyAssertField,
            String verifyAssertValue, String cleanupCollections, boolean requiresFullFeed) {
        this(id, category, shortName, kind, processor, description, enabled, topicId, gcsBucket, gcsObjectPrefix,
                defaultFileName, githubRepo, workflowFile, sampleResource, verifyMode, verifyGroup, verifyItemDb,
                verifyCollection, verifyKeyField, verifyKeyColumn, verifyMatchField, verifyAssertField,
                verifyAssertValue, cleanupCollections, requiresFullFeed, null);
    }

    /** True when this batch scenario dispatches a workflow without uploading a file (Mongo-derived job). */
    public boolean dispatchOnly() {
        return kind == ScenarioKind.BATCH && (gcsBucket == null || gcsBucket.isBlank());
    }

    /** Human-readable injection target for confirmation dialogs / display. */
    public String target() {
        if (kind == ScenarioKind.STREAMING) {
            return "topic " + topicId;
        }
        if (dispatchOnly()) {
            return "workflow " + workflowFile + " (" + githubRepo + ") — processor " + processor
                    + " · Mongo-derived, no file upload";
        }
        return "gs://" + gcsBucket + "/" + gcsObjectPrefix + defaultFileName
                + " + workflow " + workflowFile + " (" + githubRepo + ")";
    }

    /** True when this scenario supports opt-in pre-injection cleanup of its minimal golden data. */
    public boolean supportsCleanup() {
        return cleanupCollections != null && !cleanupCollections.isBlank();
    }
}
