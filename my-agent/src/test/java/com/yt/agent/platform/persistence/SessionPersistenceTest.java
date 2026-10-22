package com.yt.agent.platform.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话持久化单元测试：多轮历史「Message → ChatTurn → Message」往返必须无损，
 * 尤其是工具调用链（assistant.toolCalls ↔ tool.toolCallId 配对），否则回放会破坏上下文合法性。
 */
class SessionPersistenceTest {

    @Test
    @DisplayName("user/assistant 消息往返应保持内容一致")
    void shouldRoundTripPlainMessages() {
        List<Message> original = List.of(
                new UserMessage("上个月 GMV 是多少"),
                new AssistantMessage("上个月 GMV 为 1.2 亿"));

        List<ChatTurn> turns = SessionMapper.toTurns(original);
        assertEquals(2, turns.size());
        assertEquals(SessionMapper.ROLE_USER, turns.get(0).getRole());
        assertEquals(SessionMapper.ROLE_ASSISTANT, turns.get(1).getRole());

        List<Message> replayed = SessionMapper.toMessages(turns);
        assertEquals(2, replayed.size());
        assertEquals("上个月 GMV 是多少", replayed.get(0).getText());
        assertEquals("上个月 GMV 为 1.2 亿", replayed.get(1).getText());
    }

    @Test
    @DisplayName("工具调用链往返：toolCallId 必须配对保留，连续 tool 记录聚合为一条消息")
    void shouldRoundTripToolCallChain() {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call_001", "function", "execute_readonly_sql", "{\"sql\":\"SELECT 1\"}");
        List<Message> original = List.of(
                new UserMessage("查一下订单量"),
                new AssistantMessage("我来执行查询", Map.of(), List.of(call)),
                new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("call_001", "execute_readonly_sql", "{\"rows\":42}"),
                        new ToolResponseMessage.ToolResponse("call_002", "execute_readonly_sql", "{\"rows\":7}"))));

        List<ChatTurn> turns = SessionMapper.toTurns(original);
        // user + assistant + 两条 tool 记录
        assertEquals(4, turns.size());
        assertEquals("call_001", turns.get(2).getToolCallId());
        assertEquals("execute_readonly_sql", turns.get(2).getToolName());
        assertEquals("{\"rows\":7}", turns.get(3).getContent());

        List<Message> replayed = SessionMapper.toMessages(turns);
        assertEquals(3, replayed.size(), "两条连续 tool 记录必须聚合为一条 ToolResponseMessage");
        assertInstanceOf(ToolResponseMessage.class, replayed.get(2));
        ToolResponseMessage toolMessage = (ToolResponseMessage) replayed.get(2);
        assertEquals(2, toolMessage.getResponses().size());
        assertEquals("call_001", toolMessage.getResponses().get(0).id());
        assertEquals("call_002", toolMessage.getResponses().get(1).id());
        // assistant 的 toolCalls 元信息也应无损
        AssistantMessage assistant = (AssistantMessage) replayed.get(1);
        assertTrue(assistant.hasToolCalls());
        assertEquals("call_001", assistant.getToolCalls().get(0).id());
        assertEquals("execute_readonly_sql", assistant.getToolCalls().get(0).name());
    }

    @Test
    @DisplayName("脏数据（未知角色）不应打断整段历史回放")
    void shouldSkipCorruptTurns() {
        List<ChatTurn> turns = List.of(
                ChatTurn.builder().role("user").content("你好").build(),
                ChatTurn.builder().role("weird_role").content("脏数据").build(),
                ChatTurn.builder().role("assistant").content("你好，有什么可以帮你").build());
        List<Message> messages = SessionMapper.toMessages(turns);
        assertEquals(2, messages.size());
        assertEquals("你好，有什么可以帮你", messages.get(1).getText());
    }

    @Test
    @DisplayName("内存存储：append/load/clear/list 语义正确，且限制回放条数")
    void shouldStoreAndLoadTurns() {
        InMemorySessionStore store = new InMemorySessionStore(20, 100);
        List<ChatTurn> turns = List.of(
                ChatTurn.builder().role("user").content("第一轮").build(),
                ChatTurn.builder().role("assistant").content("答一").build(),
                ChatTurn.builder().role("user").content("第二轮").build(),
                ChatTurn.builder().role("assistant").content("答二").build());
        store.append("s1", "u1", turns);

        assertEquals(4, store.load("s1", 100).size());
        assertEquals(2, store.load("s1", 2).size(), "limit 应只取最近 N 条");
        assertEquals("第二轮", store.load("s1", 2).get(0).getContent(), "取的应是最近两条中的第一条");

        List<SessionInfo> sessions = store.list("u1", 10);
        assertEquals(1, sessions.size());
        assertEquals(4, sessions.get(0).getTurns(), "会话列表应统计轮数");

        store.clear("s1");
        assertTrue(store.load("s1", 100).isEmpty());
        assertFalse(store.persistent(), "内存后端不是持久化实现");
    }

    @Test
    @DisplayName("render 应输出可读文本（排查“模型到底看到了什么”）")
    void shouldRenderReadableText() {
        List<ChatTurn> turns = List.of(
                ChatTurn.builder().role("user").content("问题").build(),
                ChatTurn.builder().role("tool").toolName("search_knowledge")
                        .toolCallId("t1").content("片段").build());
        String rendered = SessionMapper.render(turns);
        assertTrue(rendered.contains("user"));
        assertTrue(rendered.contains("search_knowledge"));
        assertTrue(rendered.contains("片段"));
    }
}
