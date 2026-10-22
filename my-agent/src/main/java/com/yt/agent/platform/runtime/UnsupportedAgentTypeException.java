package com.yt.agent.platform.runtime;

/**
 * 请求的 agentType 没有对应处理器。
 *
 * <p>原实现里 {@code AgentHandlerFactory.getHandler} 找不到就返回 null，
 * 控制器直接 {@code handler.handle(...)} → NPE，用户收到一个没有上下文的 500。
 * 更糟的是 COMPREHENSIVE/WORKFLOW/ROUTER 三种类型在枚举里声明了却从未实现，
 * 属于"接口承诺了但没兑现"——这类缺陷只有在真实调用时才会暴露。</p>
 */
public class UnsupportedAgentTypeException extends RuntimeException {

    public UnsupportedAgentTypeException(Integer agentType) {
        super("不支持的 agentType=" + agentType + "。可用类型: 1=综合, 2=工作流(Plan-Solve), 3=Plan-Solve, 4=Router, 5=ReAct");
    }
}
