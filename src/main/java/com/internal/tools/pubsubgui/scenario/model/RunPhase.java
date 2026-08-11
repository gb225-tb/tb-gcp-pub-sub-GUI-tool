package com.internal.tools.pubsubgui.scenario.model;

/**
 * One step of the scenario run timeline (Injecting -> Waiting -> Triggering -> Polling -> Verifying ->
 * Done). Mutable so the {@code ScenarioRunService} can flip status/detail as the run progresses; the
 * controller serializes a snapshot for the polling UI.
 */
public class RunPhase {

    private final String name;
    private volatile PhaseStatus status;
    private volatile String startedAt;
    private volatile String finishedAt;
    private volatile String detail;

    public RunPhase(String name) {
        this.name = name;
        this.status = PhaseStatus.PENDING;
    }

    public String getName() {
        return name;
    }

    public PhaseStatus getStatus() {
        return status;
    }

    public void setStatus(PhaseStatus status) {
        this.status = status;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public String getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
