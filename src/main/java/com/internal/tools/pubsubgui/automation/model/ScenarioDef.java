package com.internal.tools.pubsubgui.automation.model;

/**
 * Static definition of a scenario from the test plan: its stable id (e.g. {@code UI-04}), the group
 * it belongs to, a human title, its priority (P1/P2/P3), whether it is read-only verifiable, a short
 * note (used verbatim by the heuristic AI analyzer), and the verbatim {@code spec} from the workbook
 * (Scenario -> Expected result) shown as a readable summary so results are self-explanatory.
 */
public record ScenarioDef(
        String id,
        ScenarioGroup group,
        String category,
        String title,
        String priority,
        Feasibility feasibility,
        String note,
        String spec) {

    public boolean requiresProductId() {
        return group == ScenarioGroup.HCL_XFORM;
    }
}
