package com.internal.tools.pubsubgui.scenario.model;

/**
 * How a scenario injects its controlled input:
 * <ul>
 *   <li>{@link #STREAMING} — publish a sample message to the inbound Pub/Sub topic and let the
 *       always-on Dataflow streaming job consume it.</li>
 *   <li>{@link #BATCH} — upload a sample file to the inbound GCS path and trigger the batch Dataflow
 *       job via a GitHub {@code workflow_dispatch}.</li>
 * </ul>
 */
public enum ScenarioKind {
    STREAMING,
    BATCH
}
