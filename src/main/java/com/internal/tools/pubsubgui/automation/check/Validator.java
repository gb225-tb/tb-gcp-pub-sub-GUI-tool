package com.internal.tools.pubsubgui.automation.check;

import com.internal.tools.pubsubgui.automation.model.CheckResult;

/**
 * A single read-only scenario check. Implementations inspect already-ingested documents via the
 * {@link AutomationContext} and return a {@link CheckResult}. They must never write.
 */
@FunctionalInterface
public interface Validator {

    /** Scenario id this validator belongs to; used to tag the returned {@link CheckResult}. */
    CheckResult run(String scenarioId, AutomationContext ctx);
}
