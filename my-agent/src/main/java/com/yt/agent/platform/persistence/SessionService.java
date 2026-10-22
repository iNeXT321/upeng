package com.yt.agent.platform.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话读写服务：把"存储层"与"Agent 内存"桥接起来。
 *
 * <p>职责边界很明确：
 * <ul>
 *   <li>请求开始时：{@link #load} 从存储还原最近 N 轮为 Spring AI Message；</li>
 *   <li>请求结束时：{@link #persist} 把本次运行的增量消息落库（不是全量覆盖）。</li>
 * </ul>
 * 不把这两件事塞进 Agent 里，是因为 Agent 应该只关心推理，持久化是平台能力。</p>
 */
@Slf4j
@Component
public class SessionService {

    private final SessionStore store;
    private final PersistenceProperties properties;

    public SessionService(SessionStore store, PersistenceProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public SessionStore store() {
        return store;
    }

    public PersistenceProperties properties() {
        return properties;
    }

    /**
     * 加载会话历史。
     *
     * @param sessionId 会话 ID（为空时返回空历史，等价于单轮模式）
     */
    public LoadedSession load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return LoadedSession.EMPTY;
        }
        List<ChatTurn> turns = store.load(sessionId, Math.max(1, properties.getHistoryTurns()));
        List<Message> messages = SessionMapper.toMessages(turns);
        if (!messages.isEmpty()) {
            log.info("[SessionService] 回放会话 {}: {} 条记录 / {} 条消息", sessionId, turns.size(), messages.size());
        }
        return new LoadedSession(messages, turns.size());
    }

    /**
     * 追加本次运行的增量消息。
     *
     * @param transcript 本次运行产生的消息（不含回放的历史，避免重复入库）
     */
    public void persist(String sessionId, String userId, List<Message> transcript) {
        if (sessionId == null || sessionId.isBlank() || transcript == null || transcript.isEmpty()) {
            return;
        }
        try {
            List<ChatTurn> turns = SessionMapper.toTurns(transcript);
            if (!turns.isEmpty()) {
                store.append(sessionId, userId, turns);
            }
        } catch (Exception e) {
            // 持久化是旁路能力，失败只影响下次回放，不应影响本次答案返回
            log.error("[SessionService] 会话落库失败 session={}: {}", sessionId, e.getMessage(), e);
        }
    }

    public void clear(String sessionId) {
        store.clear(sessionId);
    }

    public List<SessionInfo> list(String userId, int limit) {
        return store.list(userId, limit);
    }

    /**
     * 历史加载结果：turnCount 用于调试与前端展示（例如"已恢复 12 条上下文"）。
     */
    public record LoadedSession(List<Message> messages, int turnCount) {
        public static final LoadedSession EMPTY = new LoadedSession(List.of(), 0);
    }
}
