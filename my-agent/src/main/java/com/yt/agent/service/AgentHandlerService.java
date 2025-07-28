package com.yt.agent.service;


import com.yt.agent.agent.AgentContext;
import com.yt.agent.req.AgentRequest;

public interface AgentHandlerService {

    /**
     * 处理Agent请求
     */
    String handle(AgentContext context, AgentRequest request);

    /**
     * 进入handler条件
     */
    Boolean support(AgentContext context, AgentRequest request);

}