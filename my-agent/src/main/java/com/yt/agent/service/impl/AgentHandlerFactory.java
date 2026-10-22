package com.yt.agent.service.impl;

import com.yt.agent.agent.AgentContext;
import com.yt.agent.platform.runtime.UnsupportedAgentTypeException;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.service.AgentHandlerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 处理器工厂：按 order 排序后，用 support() 选出唯一匹配的处理器。
 *
 * <p>相较原实现的三处修正：
 * <ol>
 *   <li>按 order 排序，消除 Map 迭代顺序带来的不确定性；</li>
 *   <li>找不到处理器时抛出具名异常，而不是返回 null 让调用方 NPE；</li>
 *   <li>多个匹配时记录告警并取优先级最高者，避免"谁先注册谁生效"的隐式行为。</li>
 * </ol>
 * </p>
 *
 * <p>为什么用 {@link ObjectProvider} 懒取而不是构造器注入 List：
 * Router/Compatibility 这类"委派型" handler 自身依赖本工厂，直接注入 List 会形成
 * 构造器循环依赖（Spring 启动即报错）。懒取的额外好处是新增 handler 后无需重启即可被感知
 * （取决于作用域），也避免了启动期的 bean 初始化顺序问题。</p>
 */
@Slf4j
@Component
public class AgentHandlerFactory {

    private final ObjectProvider<AgentHandlerService> handlerProvider;
    private volatile List<AgentHandlerService> cached;

    public AgentHandlerFactory(ObjectProvider<AgentHandlerService> handlerProvider) {
        this.handlerProvider = handlerProvider;
    }

    public AgentHandlerService getHandler(AgentContext context, AgentRequest request) {
        if (Objects.isNull(context) || Objects.isNull(request)) {
            throw new IllegalArgumentException("context 与 request 不能为空");
        }
        List<AgentHandlerService> handlers = handlers();
        List<AgentHandlerService> matched = new ArrayList<>();
        for (AgentHandlerService handler : handlers) {
            if (Boolean.TRUE.equals(handler.support(context, request))) {
                matched.add(handler);
            }
        }
        if (matched.isEmpty()) {
            throw new UnsupportedAgentTypeException(request.getAgentType());
        }
        if (matched.size() > 1) {
            // 不静默：条件重叠通常是扩展枚举时漏改 support 的征兆
            log.warn("agentType={} 命中多个处理器 {}，取优先级最高者 {}",
                    request.getAgentType(),
                    matched.stream().map(h -> h.getClass().getSimpleName()).toList(),
                    matched.get(0).getClass().getSimpleName());
        }
        return matched.get(0);
    }

    public List<String> registeredHandlers() {
        return handlers().stream().map(h -> h.getClass().getSimpleName()).toList();
    }

    private List<AgentHandlerService> handlers() {
        List<AgentHandlerService> snapshot = cached;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = cached;
                if (snapshot == null) {
                    List<AgentHandlerService> sorted = new ArrayList<>(handlerProvider.orderedStream().toList());
                    sorted.sort(Comparator.comparingInt(AgentHandlerService::order));
                    snapshot = List.copyOf(sorted);
                    cached = snapshot;
                    log.info("[AgentHandlerFactory] 已注册处理器(按优先级): {}",
                            snapshot.stream().map(h -> h.getClass().getSimpleName() + "(" + h.order() + ")").toList());
                }
            }
        }
        return snapshot;
    }
}
