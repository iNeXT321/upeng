package com.yt.agent.platform.persistence;

import java.util.List;

/**
 * 会话存储抽象 —— 这是"以后接数据库"的那道接口。
 *
 * <p>为什么必须是接口：开发期用内存实现（零依赖、重启即清空，适合本地调试），
 * 生产期用 JDBC 实现（H2 文件库或 PostgreSQL）。切换只改配置，不改业务代码。
 * 未来要做多租户/分布式，也只需新增一个 Redis/MySQL 实现。</p>
 *
 * <p>接口刻意保持"追加写入"语义（append 而非 save 全量）：
 * Agent 的消息是只增不改的时间序列，全量覆盖既浪费又会有并发丢写风险。</p>
 */
public interface SessionStore {

    /**
     * 追加若干轮对话。实现需保证同一 session 内的 seq 连续。
     *
     * @param sessionId 会话 ID
     * @param userId    用户标识（用于按人查询会话列表，可空）
     * @param turns     待追加记录（seq 字段由实现填充）
     */
    void append(String sessionId, String userId, List<ChatTurn> turns);

    /**
     * 读取最近 limit 轮对话，按时间正序返回（便于直接回放成上下文）。
     */
    List<ChatTurn> load(String sessionId, int limit);

    /** 清空指定会话 */
    void clear(String sessionId);

    /** 列出某用户的会话（按最近活跃倒序） */
    List<SessionInfo> list(String userId, int limit);

    /** 是否持久化（false 表示重启丢失，用于健康检查与前端提示） */
    boolean persistent();

    /** 实现标识，如 memory / jdbc:h2 / jdbc:postgresql */
    String backend();
}
