package com.yt.agent.platform.persistence;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存会话实现：零依赖、启动即用，适合本地开发与单机演示。
 *
 * <p>容量控制是刻意设计的：Agent 会话单轮可达数十条消息（含工具结果），
 * 无上限的内存 Map 在长时间运行后必然 OOM。这里做两级限制——
 * 单会话最近 N 轮 + 全局最近 M 个会话（LRU 淘汰），保证内存占用可预测。</p>
 *
 * <p>线程安全：Agent 侧存在并行执行（Plan-Solve 的子任务并发），因此所有读写都加锁；
 * 锁粒度是方法级，考虑到内存操作极快、且这只是开发态实现，可接受。</p>
 */
@Slf4j
public class InMemorySessionStore implements SessionStore {

    private final int maxTurnsPerSession;
    private final int maxSessions;
    private final Map<String, List<ChatTurn>> sessions;
    private final Map<String, SessionInfo> infos = new LinkedHashMap<>(16, 0.75f, true);

    public InMemorySessionStore(int maxTurnsPerSession, int maxSessions) {
        this.maxTurnsPerSession = Math.max(10, maxTurnsPerSession);
        this.maxSessions = Math.max(10, maxSessions);
        this.sessions = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<ChatTurn>> eldest) {
                boolean evict = size() > InMemorySessionStore.this.maxSessions;
                if (evict) {
                    infos.remove(eldest.getKey());
                    log.debug("[InMemorySessionStore] 淘汰最久未使用会话: {}", eldest.getKey());
                }
                return evict;
            }
        };
    }

    @Override
    public synchronized void append(String sessionId, String userId, List<ChatTurn> turns) {
        if (sessionId == null || turns == null || turns.isEmpty()) {
            return;
        }
        List<ChatTurn> existing = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
        int seq = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getSeq() + 1;
        long now = System.currentTimeMillis();
        for (ChatTurn turn : turns) {
            turn.setSeq(seq++);
            if (turn.getCreatedAt() == null) {
                turn.setCreatedAt(now);
            }
            existing.add(turn);
        }
        // 只保留最近 N 轮，防止长会话无限增长
        if (existing.size() > maxTurnsPerSession) {
            List<ChatTurn> trimmed = new ArrayList<>(existing.subList(existing.size() - maxTurnsPerSession, existing.size()));
            sessions.put(sessionId, trimmed);
        }
        infos.put(sessionId, SessionInfo.builder()
                .sessionId(sessionId)
                .userId(userId)
                .turns(sessions.get(sessionId).size())
                .updatedAt(now)
                .build());
    }

    @Override
    public synchronized List<ChatTurn> load(String sessionId, int limit) {
        List<ChatTurn> all = sessions.get(sessionId);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, all.size() - Math.max(1, limit));
        return List.copyOf(all.subList(from, all.size()));
    }

    @Override
    public synchronized void clear(String sessionId) {
        sessions.remove(sessionId);
        infos.remove(sessionId);
    }

    @Override
    public synchronized List<SessionInfo> list(String userId, int limit) {
        List<SessionInfo> result = new ArrayList<>();
        for (SessionInfo info : infos.values()) {
            if (userId == null || userId.equals(info.getUserId())) {
                result.add(info);
            }
        }
        Collections.reverse(result);
        return result.size() > limit ? result.subList(0, Math.max(1, limit)) : result;
    }

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public String backend() {
        return "memory";
    }
}
