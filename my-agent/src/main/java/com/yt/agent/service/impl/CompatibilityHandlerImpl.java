package com.yt.agent.service.impl;

import com.yt.agent.agent.AgentContext;
import com.yt.agent.agent.enums.AgentType;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.service.AgentHandlerService;
import org.springframework.stereotype.Component;

/**
 * 兼容层：把"声明了但未实现"的 agentType 映射到已有能力，避免直接 500。
 *
 * <p>背景：{@link AgentType} 里定义了 COMPREHENSIVE(1) 与 WORKFLOW(2)，
 * 但项目里从来没有对应实现，调用即 NPE。这里给出明确的降级语义：
 * <ul>
 *   <li>COMPREHENSIVE(1) → ReAct：通用对话 + 全部工具，语义上最接近"综合智能体"；</li>
 *   <li>WORKFLOW(2) → Plan-Solve：多步编排，是目前最接近"工作流"的实现。</li>
 * </ul>
 * 这是一种"诚实的近似"，而不是假装工作流引擎已经存在。真正的工作流需要
 * DAG 定义、节点重试、人工审批、断点续跑，属于下一阶段工作（见架构评审文档）。</p>
 */
@Component
public class CompatibilityHandlerImpl implements AgentHandlerService {

    private final AgentHandlerFactory handlerFactory;

    public CompatibilityHandlerImpl(AgentHandlerFactory handlerFactory) {
        this.handlerFactory = handlerFactory;
    }

    @Override
    public String handle(AgentContext context, AgentRequest request) {
        AgentType target = AgentType.COMPREHENSIVE.getValue().equals(request.getAgentType())
                ? AgentType.REACT : AgentType.PLAN_SOLVE;
        request.setAgentType(target.getValue());
        context.setAgentType(target.getValue());
        if (context.getPrinter() != null) {
            context.getPrinter().updateAgentType(target);
        }
        return handlerFactory.getHandler(context, request).handle(context, request);
    }

    @Override
    public Boolean support(AgentContext context, AgentRequest request) {
        return AgentType.COMPREHENSIVE.getValue().equals(request.getAgentType())
                || AgentType.WORKFLOW.getValue().equals(request.getAgentType());
    }

    /** 兜底优先级最低：只有在没有更精确的实现时才接管 */
    @Override
    public int order() {
        return 900;
    }
}
