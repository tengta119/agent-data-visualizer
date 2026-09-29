package top.lbwxxc.ai.domain.agent.service.paicli;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Final workflow answer and the output-key values produced during this turn. */
public record PaiCliWorkflowResult(String content, Map<String, String> outputs) {
    public PaiCliWorkflowResult {
        outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
    }
}
