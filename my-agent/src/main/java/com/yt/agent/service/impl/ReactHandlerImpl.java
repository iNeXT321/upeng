package com.yt.agent.service.impl;

import com.yt.agent.agent.AgentContext;
import com.yt.agent.agent.ReActImpAgent;
import com.yt.agent.agent.SummaryAgent;
import com.yt.agent.agent.enums.AgentType;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.service.AgentHandlerService;
import org.springframework.stereotype.Component;

@Component
public class ReactHandlerImpl implements AgentHandlerService {
    @Override
    public String handle(AgentContext context, AgentRequest request) {

        ReActImpAgent reActImpAgent = new ReActImpAgent(context);

        SummaryAgent summaryAgent = new SummaryAgent(context);

        reActImpAgent.run(request.getQuery());



        return "";
    }

    @Override
    public Boolean support(AgentContext context, AgentRequest request) {
        return AgentType.REACT.getValue().equals(request.getAgentType());
    }
}
