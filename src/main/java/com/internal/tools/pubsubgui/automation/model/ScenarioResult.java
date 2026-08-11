package com.internal.tools.pubsubgui.automation.model;

/** A scenario definition paired with its run outcome (what the UI renders per row). */
public record ScenarioResult(ScenarioDef scenario, CheckResult result) {
}
