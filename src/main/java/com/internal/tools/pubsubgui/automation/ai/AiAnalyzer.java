package com.internal.tools.pubsubgui.automation.ai;

import com.internal.tools.pubsubgui.automation.model.AiAnalyzeRequest;
import com.internal.tools.pubsubgui.automation.model.AiAnalyzeResponse;

/** Produces a human explanation of one or more failed automation scenarios. */
public interface AiAnalyzer {

    AiAnalyzeResponse analyze(AiAnalyzeRequest request);
}
