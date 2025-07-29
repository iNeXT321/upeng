package com.yt.agent.service.impl;

import com.yt.agent.agent.AgentContext;
import com.yt.agent.agent.ReActImpAgent;
import com.yt.agent.agent.SummaryAgent;
import com.yt.agent.agent.dto.TaskSummaryResult;
import com.yt.agent.agent.enums.AgentType;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.service.AgentHandlerService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ReactHandlerImpl implements AgentHandlerService {
    @Override
    public String handle(AgentContext context, AgentRequest request) {

        ReActImpAgent reActImpAgent = new ReActImpAgent(context);

        SummaryAgent summary = new SummaryAgent(context);
        summary.setSystemPrompt(summary.getSystemPrompt().replace("{{query}}", request.getQuery()));

        reActImpAgent.run(request.getQuery());

        TaskSummaryResult result = summary.summaryTaskResult(reActImpAgent.getMemory().getMessages(), request.getQuery());

        Map<String, Object> taskResult = new HashMap<>();
        taskResult.put("taskSummary", result.getTaskSummary());
        context.getPrinter().send("result", taskResult);

        return "";
    }

    @Override
    public Boolean support(AgentContext context, AgentRequest request) {
        return AgentType.REACT.getValue().equals(request.getAgentType());
    }
}
