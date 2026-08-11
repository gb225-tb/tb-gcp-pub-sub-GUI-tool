package com.internal.tools.pubsubgui.automation.model;

import java.util.List;

/**
 * A run request from the UI.
 *
 * @param env         environment name (Dev/QA/Perf/Prod)
 * @param group       optional group filter (null/blank = any group)
 * @param scenarioIds optional explicit scenario ids; when empty and {@code all} is true, run everything
 * @param all         run every scenario in scope (ignores scenarioIds)
 * @param productId   optional part number, required by HCL cross-verification scenarios
 * @param sampleSize  max documents to sample per collection (defaults applied server-side)
 */
public record RunRequest(
        String env,
        String group,
        List<String> scenarioIds,
        boolean all,
        String productId,
        Integer sampleSize) {
}
