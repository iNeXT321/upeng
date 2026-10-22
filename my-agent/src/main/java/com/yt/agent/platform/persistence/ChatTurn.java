package com.yt.agent.platform.persistence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条可持久化的对话记录。
 *
 * <p>为什么不直接序列化 Spring AI 的 {@code Message}：Message 是框架内部模型，
 * 字段会随版本变化（例如 1.0 引入 Media、ToolContext），直接落库会导致升级即数据不兼容。
 * 这里定义一个稳定的存储契约（role/content/toolName/toolCallId/meta），
 * 由 {@link SessionMapper} 负责与框架模型互转——存储层与框架解耦。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatTurn {

    /** 会话内自增序号，保证重放顺序 */
    private Integer seq;

    /** user / assistant / tool */
    private String role;

    private String content;

    /** 工具调用时的工具名 */
    private String toolName;

    /** 工具调用 id，必须与 assistant 消息中的 tool_call id 对应，否则回放会破坏上下文合法性 */
    private String toolCallId;

    /** 扩展信息（如 assistant 的 toolCalls 列表 JSON），遵循"主字段稳定、扩展字段自由"原则 */
    private String meta;

    private Long createdAt;
}
