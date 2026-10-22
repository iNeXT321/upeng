package com.yt.agent.printer;

import com.yt.agent.agent.enums.AgentType;
import com.yt.agent.platform.guard.GuardChain;

import java.util.HashMap;
import java.util.Map;

/**
 * 出站护栏装饰器：在消息真正写入 SSE 之前做一次脱敏。
 *
 * <p>为什么用装饰器而不是在每个 Agent 里调用脱敏方法：
 * 出站路径只有 {@link Printer} 一个出口，装饰它就能保证"没有任何一条路径能绕过脱敏"——
 * 这是安全设计里典型的"choke point（收口）"思路。若分散在各 Agent，
 * 新增一个 Agent 或新增一种消息类型就可能漏掉。</p>
 *
 * <p>只处理面向用户的文本类消息（result / task_summary / tool_thought / agent_stream），
 * 工具原始结果不做改写：它只是喂给模型的中间数据，改写反而可能破坏结构化数据。</p>
 */
public class GuardedPrinter implements Printer {

    private final Printer delegate;
    private final GuardChain guardChain;

    public GuardedPrinter(Printer delegate, GuardChain guardChain) {
        this.delegate = delegate;
        this.guardChain = guardChain;
    }

    @Override
    public void send(String messageId, String messageType, Object message, String digitalEmployee, Boolean isFinal) {
        delegate.send(messageId, messageType, sanitize(messageType, message), digitalEmployee, isFinal);
    }

    @Override
    public void send(String messageType, Object message) {
        delegate.send(messageType, sanitize(messageType, message));
    }

    @Override
    public void send(String messageType, Object message, String digitalEmployee) {
        delegate.send(messageType, sanitize(messageType, message), digitalEmployee);
    }

    @Override
    public void send(String messageId, String messageType, Object message, Boolean isFinal) {
        delegate.send(messageId, messageType, sanitize(messageType, message), isFinal);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void updateAgentType(AgentType agentType) {
        delegate.updateAgentType(agentType);
    }

    private Object sanitize(String messageType, Object message) {
        if (guardChain == null || !guardChain.enabled() || message == null) {
            return message;
        }
        if (!isTextBearing(messageType)) {
            return message;
        }
        if (message instanceof String text) {
            return guardChain.sanitizeOutput(text);
        }
        if (message instanceof Map<?, ?> map) {
            Map<String, Object> copy = new HashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k),
                    v instanceof String s ? guardChain.sanitizeOutput(s) : v));
            return copy;
        }
        return message;
    }

    private boolean isTextBearing(String messageType) {
        return "result".equals(messageType)
                || "task_summary".equals(messageType)
                || "tool_thought".equals(messageType)
                || "plan_thought".equals(messageType)
                || "agent_stream".equals(messageType);
    }
}
