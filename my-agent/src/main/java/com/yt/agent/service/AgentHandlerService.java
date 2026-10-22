package com.yt.agent.service;


import com.yt.agent.agent.AgentContext;
import com.yt.agent.req.AgentRequest;

/**
 * Agent 处理器：一种 Agent 形态（ReAct / Plan-Solve / Router…）对应一个实现。
 *
 * <p>新增 order() 的原因：原工厂靠 {@code ConcurrentHashMap.values()} 遍历匹配，
 * 而 HashMap 的迭代顺序不保证稳定——当多个 handler 的 support 条件出现重叠时，
 * 每次启动可能命中不同实现。显式 order 让优先级成为可读、可测的契约。</p>
 */
public interface AgentHandlerService {

    /**
     * 处理Agent请求
     */
    String handle(AgentContext context, AgentRequest request);

    /**
     * 进入handler条件
     */
    Boolean support(AgentContext context, AgentRequest request);

    /**
     * 优先级：数值越小越先匹配。默认 100，兜底型 handler 用更大的值。
     */
    default int order() {
        return 100;
    }

}
