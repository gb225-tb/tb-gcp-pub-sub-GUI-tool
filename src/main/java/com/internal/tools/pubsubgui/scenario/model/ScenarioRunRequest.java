package com.internal.tools.pubsubgui.scenario.model;

/**
 * UI request to run one scenario. For streaming, {@code payloadOverride} is the (edited) JSON to
 * publish. For batch, {@code fileBase64}/{@code fileName} carry the (optionally user-supplied) file to
 * upload and {@code version} is the required GitHub {@code workflow_dispatch} version input.
 *
 * @param env             environment (must be Perf when enforce-perf-only is on)
 * @param scenarioId      the {@link ScenarioSpec#id()} to run
 * @param payloadOverride streaming: JSON message body to publish (falls back to the bundled sample)
 * @param version         batch: version input for the workflow dispatch (required for batch)
 * @param fileName        batch: uploaded file name (falls back to the spec default)
 * @param fileBase64      batch: uploaded file content, base64 (falls back to the bundled sample)
 * @param cleanup         opt-in: when true and the scenario supports it, delete the minimal golden data
 *                        (Perf only, by the scenario key) BEFORE injecting so the verify proves this run
 */
public record ScenarioRunRequest(
        String env,
        String scenarioId,
        String payloadOverride,
        String version,
        String fileName,
        String fileBase64,
        boolean cleanup) {
}
