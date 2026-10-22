package com.yt.agent.agent;

import com.yt.agent.platform.llm.LlmGateway;
import com.yt.agent.platform.runtime.CancellationToken;
import com.yt.agent.printer.Printer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次 Agent 运行的上下文。
 *
 * <p>它是"请求作用域"的对象：一个 HTTP 请求一个实例，绝不跨请求复用。
 * 原实现把 printer、chatClient 等直接挂在 Agent 字段上，导致并行子任务共享同一个
 * 可变上下文（例如 Plan-Solve 的多个 Executor 并发写同一个 printer/消息列表），
 * 这里通过显式字段 + 线程安全集合把共享范围收敛到"只读/线程安全"的部分。</p>
 */
@Data
@Builder
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {

    String requestId;
    String sessionId;
    String userId;
    String query;
    String task;

    /** 出站消息出口（通常是 GuardedPrinter → SSEPrinter） */
    Printer printer;

    String dateInfo;
    Boolean isStream;
    String streamMessageType;
    String sopPrompt;
    String basePrompt;
    Integer agentType;

    ToolCallback[] availableTools;
    ChatClient chatClient;

    /** 模型网关：各 Agent 通过角色取自己的模型，而不是共用同一个硬编码模型 */
    LlmGateway llm;

    /** 语义记忆注入文本：指标口径/业务术语/用户偏好，拼入 System Prompt（问数场景） */
    String semanticMemoryPrompt;

    /** 多轮改写后的自包含问题（QueryRewriter 产物），召回与SQL生成应基于它而非原始省略句 */
    String rewrittenQuery;

    /** 从会话存储回放的历史消息（只读；由 BaseAgent 在 run 开始时注入 Memory） */
    List<Message> history;

    /**
     * 本次运行新产生的消息流水（线程安全）。
     * 由 Memory 的 appendListener 推送，用于请求结束后增量落库——
     * 这样不需要让 Agent 感知"持久化"这件事。
     */
    @Builder.Default
    List<Message> transcript = Collections.synchronizedList(new java.util.ArrayList<>());

    /** 取消令牌：客户端断开或调用 /ai/stop 时置位，Agent 主循环会在下一步检查并退出 */
    CancellationToken cancellation;

    /** 单次请求的绝对截止时间（epoch millis），0 表示不限制 */
    long deadlineEpochMs;

    /** 同一请求内"工具名+入参"的调用计数，用于抑制模型原地打转 */
    @Builder.Default
    Map<String, Integer> toolCallCounter = new ConcurrentHashMap<>();

    /** 记录一条新消息（供 Memory 监听器回调） */
    public void recordMessage(Message message) {
        if (message != null && transcript != null) {
            transcript.add(message);
        }
    }

    public List<Message> transcriptSnapshot() {
        if (transcript == null) {
            return List.of();
        }
        synchronized (transcript) {
            return List.copyOf(transcript);
        }
    }

    public boolean isCancelled() {
        return cancellation != null && cancellation.isCancelled();
    }

    public boolean isExpired() {
        return deadlineEpochMs > 0 && System.currentTimeMillis() > deadlineEpochMs;
    }
}
