package com.internal.tools.pubsubgui.scenario.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Perf-only Scenario Runner. {@code enforcePerfOnly} hard-blocks every
 * environment except {@code Perf}; timings control the streaming wait and batch poll/timeout.
 */
@Component
@ConfigurationProperties(prefix = "scenario")
public class ScenarioProperties {

    /** The only environment name injection is allowed against when {@link #enforcePerfOnly} is true. */
    public static final String PERF = "Perf";

    private boolean enforcePerfOnly = true;
    private String projectId = "np-ecom-2-6d1a";
    private int streamWaitSeconds = 5;
    /** Extra seconds added to the verify wait when the run performed opt-in cleanup (data must be re-created). */
    private int cleanupWaitSeconds = 10;
    private int batchTimeoutSeconds = 420;
    private int batchPollSeconds = 15;

    public boolean isEnforcePerfOnly() {
        return enforcePerfOnly;
    }

    public void setEnforcePerfOnly(boolean enforcePerfOnly) {
        this.enforcePerfOnly = enforcePerfOnly;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId == null ? "" : projectId.trim();
    }

    public int getStreamWaitSeconds() {
        return streamWaitSeconds;
    }

    public void setStreamWaitSeconds(int streamWaitSeconds) {
        this.streamWaitSeconds = streamWaitSeconds;
    }

    public int getCleanupWaitSeconds() {
        return cleanupWaitSeconds;
    }

    public void setCleanupWaitSeconds(int cleanupWaitSeconds) {
        this.cleanupWaitSeconds = cleanupWaitSeconds;
    }

    public int getBatchTimeoutSeconds() {
        return batchTimeoutSeconds;
    }

    public void setBatchTimeoutSeconds(int batchTimeoutSeconds) {
        this.batchTimeoutSeconds = batchTimeoutSeconds;
    }

    public int getBatchPollSeconds() {
        return batchPollSeconds;
    }

    public void setBatchPollSeconds(int batchPollSeconds) {
        this.batchPollSeconds = batchPollSeconds;
    }

    /** True when the given environment name is allowed for injection. */
    public boolean isAllowed(String env) {
        return !enforcePerfOnly || PERF.equalsIgnoreCase(env == null ? "" : env.trim());
    }
}
