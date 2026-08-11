package com.internal.tools.pubsubgui.automation.web;

import com.internal.tools.pubsubgui.automation.AutomationEngine;
import com.internal.tools.pubsubgui.automation.ai.AiAnalysisService;
import com.internal.tools.pubsubgui.automation.model.AiAnalyzeRequest;
import com.internal.tools.pubsubgui.automation.model.AiAnalyzeResponse;
import com.internal.tools.pubsubgui.automation.model.RunRequest;
import com.internal.tools.pubsubgui.automation.model.RunSummary;
import com.internal.tools.pubsubgui.automation.model.ScenarioDef;
import com.internal.tools.pubsubgui.automation.model.ScenarioGroup;
import com.internal.tools.pubsubgui.automation.scenario.ScenarioRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the Automation view. The catalog and AI-status calls are cheap; the run and
 * analyze calls do blocking Mongo/DB2/HTTP work and are scheduled on the bounded-elastic scheduler.
 * All checks are read-only. Connection strings / secrets never leave the server.
 */
@RestController
@RequestMapping("/api/automation")
public class AutomationController {

    private final AutomationEngine engine;
    private final ScenarioRegistry registry;
    private final AiAnalysisService ai;

    public AutomationController(AutomationEngine engine, ScenarioRegistry registry, AiAnalysisService ai) {
        this.engine = engine;
        this.registry = registry;
        this.ai = ai;
    }

    /** The consolidated scenario catalog + group metadata that drives the UI's tabs and selectors. */
    @GetMapping("/scenarios")
    public Map<String, Object> scenarios() {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (ScenarioGroup g : ScenarioGroup.values()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("id", g.name());
            gm.put("label", g.label());
            gm.put("description", g.description());
            groups.add(gm);
        }
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (ScenarioDef d : registry.definitions()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", d.id());
            sm.put("group", d.group().name());
            sm.put("groupLabel", d.group().label());
            sm.put("category", d.category());
            sm.put("title", d.title());
            sm.put("priority", d.priority());
            sm.put("feasibility", d.feasibility().name());
            sm.put("requiresProductId", d.requiresProductId());
            sm.put("note", d.note());
            sm.put("spec", d.spec());
            scenarios.add(sm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", groups);
        out.put("scenarios", scenarios);
        return out;
    }

    /** Run selected scenarios against one environment (read-only). */
    @PostMapping("/run")
    public Mono<RunSummary> run(@RequestBody RunRequest request) {
        return Mono.fromCallable(() -> engine.run(request)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Explain one or more failed scenarios (LLM if configured, heuristic otherwise). */
    @PostMapping("/analyze")
    public Mono<AiAnalyzeResponse> analyze(@RequestBody AiAnalyzeRequest request) {
        return Mono.fromCallable(() -> ai.analyze(request)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Whether a real LLM is wired, and which provider/model is active. */
    @GetMapping("/ai/status")
    public Map<String, Object> aiStatus() {
        return ai.status();
    }
}
