package com.yt.agent.platform.persistence;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring AI Message ↔ 存储记录的双向映射。
 *
 * <p>这是"多轮对话真正可用"的关键。原实现里 Controller 从不把请求携带的 messages 灌入 Agent Memory，
 * 每轮对话实际上都是无状态重来——用户说"那华南呢"，模型完全不知道上一轮问的是什么。
 * 修复方式不是简单拼字符串，而是按框架要求<b>原样重建消息结构</b>：
 * assistant 的 tool_calls 与 tool 的 tool_call_id 必须严格配对，
 * 否则下一轮请求会被模型服务端拒绝（400 invalid tool role）——很多"接上历史就报错"的根因都在这里。</p>
 *
 * <p>为什么不用 Java 原生序列化/Kryo 直接存 Message：框架类跨版本不稳定，
 * 且序列化后的二进制不可读、无法用 SQL 排查问题。结构化存储 + 显式映射更可控。</p>
 */
@Slf4j
public final class SessionMapper {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    private SessionMapper() {
    }

    public static List<ChatTurn> toTurns(List<Message> messages) {
        List<ChatTurn> turns = new ArrayList<>();
        if (messages == null) {
            return turns;
        }
        long now = System.currentTimeMillis();
        for (Message message : messages) {
            MessageType type = message.getMessageType();
            switch (type) {
                case USER -> turns.add(ChatTurn.builder()
                        .role(ROLE_USER)
                        .content(message.getText())
                        .createdAt(now)
                        .build());
                case ASSISTANT -> {
                    AssistantMessage assistant = (AssistantMessage) message;
                    turns.add(ChatTurn.builder()
                            .role(ROLE_ASSISTANT)
                            .content(assistant.getText())
                            .meta(assistant.hasToolCalls() ? writeToolCalls(assistant.getToolCalls()) : null)
                            .createdAt(now)
                            .build());
                }
                case TOOL -> {
                    ToolResponseMessage toolMessage = (ToolResponseMessage) message;
                    for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                        turns.add(ChatTurn.builder()
                                .role(ROLE_TOOL)
                                .toolName(response.name())
                                .toolCallId(response.id())
                                .content(response.responseData())
                                .createdAt(now)
                                .build());
                    }
                }
                default -> {
                    // SYSTEM 消息由代码生成，不入库
                }
            }
        }
        return turns;
    }

    public static List<Message> toMessages(List<ChatTurn> turns) {
        List<Message> messages = new ArrayList<>();
        if (turns == null || turns.isEmpty()) {
            return messages;
        }
        int i = 0;
        while (i < turns.size()) {
            ChatTurn turn = turns.get(i);
            String role = turn.getRole() == null ? "" : turn.getRole();
            switch (role) {
                case ROLE_USER -> messages.add(new UserMessage(nullToEmpty(turn.getContent())));
                case ROLE_ASSISTANT -> messages.add(new AssistantMessage(
                        nullToEmpty(turn.getContent()), Map.of(), readToolCalls(turn.getMeta())));
                case ROLE_TOOL -> {
                    // 连续的 tool 记录合并为一条 ToolResponseMessage：框架要求一次 assistant 的多个
                    // 并行工具调用，其返回必须聚合在同一条 tool 消息里
                    List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                    while (i < turns.size() && ROLE_TOOL.equals(turns.get(i).getRole())) {
                        ChatTurn toolTurn = turns.get(i);
                        responses.add(new ToolResponseMessage.ToolResponse(
                                toolTurn.getToolCallId(),
                                toolTurn.getToolName(),
                                nullToEmpty(toolTurn.getContent())));
                        i++;
                    }
                    messages.add(new ToolResponseMessage(responses));
                    continue;
                }
                default -> {
                    // 未知角色跳过，保证回放健壮性（不因一条脏数据打断整段历史）
                }
            }
            i++;
        }
        return messages;
    }

    private static String writeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        JSONArray array = new JSONArray();
        for (AssistantMessage.ToolCall call : toolCalls) {
            JSONObject item = new JSONObject();
            item.put("id", call.id());
            item.put("type", call.type());
            item.put("name", call.name());
            item.put("arguments", call.arguments());
            array.add(item);
        }
        return array.toJSONString();
    }

    private static List<AssistantMessage.ToolCall> readToolCalls(String meta) {
        if (meta == null || meta.isBlank()) {
            return List.of();
        }
        try {
            JSONArray array = JSON.parseArray(meta);
            List<AssistantMessage.ToolCall> calls = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                calls.add(new AssistantMessage.ToolCall(
                        item.getString("id"),
                        item.getString("type") == null ? "function" : item.getString("type"),
                        item.getString("name"),
                        item.getString("arguments")));
            }
            return calls;
        } catch (Exception e) {
            log.warn("[SessionMapper] toolCalls 解析失败，按无工具调用处理: {}", e.getMessage());
            return List.of();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 供单测与调试：把历史渲染成可读文本 */
    public static String render(List<ChatTurn> turns) {
        StringBuilder sb = new StringBuilder();
        for (ChatTurn t : turns) {
            sb.append('[').append(t.getSeq()).append("] ").append(t.getRole());
            if (t.getToolName() != null) {
                sb.append('(').append(t.getToolName()).append(')');
            }
            sb.append(": ").append(t.getContent() == null ? "" : t.getContent());
            sb.append('\n');
        }
        return sb.toString();
    }

    @SuppressWarnings("unused")
    private static SystemMessage system(String content) {
        return new SystemMessage(content);
    }
}
