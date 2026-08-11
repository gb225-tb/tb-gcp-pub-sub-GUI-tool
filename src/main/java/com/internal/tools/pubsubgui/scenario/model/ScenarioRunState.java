package com.internal.tools.pubsubgui.scenario.model;

import com.internal.tools.pubsubgui.automation.model.RunSummary;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live, evolving state of a single scenario run. Created when a run starts and mutated by the
 * orchestration on a worker thread; the controller returns snapshots to the polling UI. Fields use
 * plain getters so Jackson serializes it directly.
 */
public class ScenarioRunState {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final String runId;
    private final String scenarioId;
    private final String shortName;
    private final String category;
    private final String kind;
    private final String env;
    private final String startedAt;

    private volatile String finishedAt;
    /** RUNNING | PASS | FAIL | ERROR */
    private volatile String status = "RUNNING";
    private volatile String message = "";
    private volatile boolean done = false;

    private final Map<String, Object> injection = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<RunPhase> phases = Collections.synchronizedList(new ArrayList<>());
    private volatile RunSummary verify;

    public ScenarioRunState(String runId, ScenarioSpec spec, String env) {
        this.runId = runId;
        this.scenarioId = spec.id();
        this.shortName = spec.shortName();
        this.category = spec.category().label();
        this.kind = spec.kind().name();
        this.env = env;
        this.startedAt = ISO.format(Instant.now());
    }

    // ------------------------------------------------------------- phase helpers

    public synchronized RunPhase addPhase(String name) {
        RunPhase p = new RunPhase(name);
        phases.add(p);
        return p;
    }

    public void startPhase(RunPhase phase, String detail) {
        phase.setStatus(PhaseStatus.RUNNING);
        phase.setStartedAt(ISO.format(Instant.now()));
        if (detail != null) {
            phase.setDetail(detail);
        }
    }

    public void finishPhase(RunPhase phase, PhaseStatus status, String detail) {
        phase.setStatus(status);
        phase.setFinishedAt(ISO.format(Instant.now()));
        if (detail != null) {
            phase.setDetail(detail);
        }
    }

    public void putInjection(String key, Object value) {
        injection.put(key, value);
    }

    public void complete(String status, String message) {
        this.status = status;
        this.message = message == null ? "" : message;
        this.finishedAt = ISO.format(Instant.now());
        this.done = true;
    }

    // -------------------------------------------------------------------- getters

    public String getRunId() {
        return runId;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public String getShortName() {
        return shortName;
    }

    public String getCategory() {
        return category;
    }

    public String getKind() {
        return kind;
    }

    public String getEnv() {
        return env;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public String getFinishedAt() {
        return finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public boolean isDone() {
        return done;
    }

    public Map<String, Object> getInjection() {
        return injection;
    }

    public List<RunPhase> getPhases() {
        return phases;
    }

    public RunSummary getVerify() {
        return verify;
    }

    public void setVerify(RunSummary verify) {
        this.verify = verify;
    }
}
