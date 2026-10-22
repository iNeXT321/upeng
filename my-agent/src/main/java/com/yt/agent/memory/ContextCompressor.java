package com.yt.agent.memory;

import com.yt.agent.utils.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩器（Context Compression）—— 长对话/多步任务的 token 治理
 *
 * <p>解决的问题：Agent 每轮 think-act 都往 Memory 追加消息，工具结果（尤其 SQL 查询结果、搜索网页）
 * 动辄几千 token。20 步任务轻松突破上下文窗口，且历史噪音会稀释模型注意力、拉高成本。</p>
 *
 * <p>压缩策略（按保留优先级）：
 * <ol>
 *   <li><b>System/首条 User 永不压缩</b>：任务目标丢失等于任务失败；</li>
 *   <li><b>工具结果摘要化</b>：TOOL 消息超阈值时保留头尾 + 中间摘要标记
 *       （工具结果的价值随轮次衰减，越旧越只需要结论）；</li>
 *   <li><b>旧轮次滑动窗口折叠</b>：超出窗口的旧对话折叠为一条摘要 AssistantMessage；</li>
 *   <li><b>最近 K 轮完整保留</b>：模型对最近上下文依赖最强，无损保留。</li>
 * </ol>
 * </p>
 *
 * <p>工程取舍：这里用「截断 + 结构化折叠」的确定性压缩，而不是调用 LLM 做摘要压缩。
 * 原因：LLM 摘要会引入额外延迟、成本与新的幻觉风险；生产上推荐混合方案——
 * 阈值内用本类的确定性压缩，超过硬阈值再触发一次 LLM 摘要（可复用项目已有的
 * SummaryAgent 做历史折叠）。触发时机：每轮 think 前检查 token 水位，而非每轮都压。</p>
 */
@Slf4j
public class ContextCompressor {

    /** token 软阈值：超过则触发压缩 */
    private final int softTokenLimit;
    /** 最近 K 轮消息完整保留 */
    private final int keepRecentMessages;
    /** 单条 TOOL 消息的最大保留字符数 */
    private final int maxToolMessageChars;

    public ContextCompressor() {
        this(24000, 6, 2000);
    }

    public ContextCompressor(int softTokenLimit, int keepRecentMessages, int maxToolMessageChars) {
        this.softTokenLimit = softTokenLimit > 0 ? softTokenLimit : 24000;
        this.keepRecentMessages = Math.max(2, keepRecentMessages);
        this.maxToolMessageChars = maxToolMessageChars > 0 ? maxToolMessageChars : 2000;
    }

    /**
     * 压缩入口：token 水位未超阈值时原样返回（零开销快路径）。
     *
     * @param messages 原始消息列表（不会被修改，返回新列表）
     * @return 压缩后的消息列表
     */
    public List<Message> compressIfNeeded(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages == null ? List.of() : messages;
        }
        int tokens = TokenCounter.calculateTotalTokens(messages);
        if (tokens <= softTokenLimit) {
            return messages;
        }
        log.info("[ContextCompressor] token={} 超过阈值 {}，触发压缩, 消息数={}", tokens, softTokenLimit, messages.size());
        List<Message> compressed = compress(messages);
        log.info("[ContextCompressor] 压缩完成: {} -> {} tokens, 消息数 {} -> {}",
                tokens, TokenCounter.calculateTotalTokens(compressed), messages.size(), compressed.size());
        return compressed;
    }

    /**
     * 执行压缩：System 保留 → 旧消息折叠为摘要 → TOOL 消息截断 → 最近 K 条无损
     */
    public List<Message> compress(List<Message> messages) {
        List<Message> result = new ArrayList<>();

        // 1) 头部锚定：System 消息 + 第一条 User 消息（任务目标）永不压缩
        int anchorEnd = 0;
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.getMessageType() == MessageType.SYSTEM) {
                result.add(m);
                anchorEnd = i + 1;
            } else if (m.getMessageType() == MessageType.USER && anchorEnd == i) {
                result.add(m);
                anchorEnd = i + 1;
                break;
            } else {
                break;
            }
        }

        int tailStart = Math.max(anchorEnd, messages.size() - keepRecentMessages);

        // 2) 中段折叠：把 anchorEnd..tailStart 的旧消息折叠为一条结构化摘要
        if (tailStart > anchorEnd) {
            String summary = foldToSummary(messages.subList(anchorEnd, tailStart));
            result.add(new AssistantMessage("[历史步骤摘要（已压缩）]\n" + summary));
        }

        // 3) 尾部保留但 TOOL 消息仍做单条截断（最近一条超大结果同样危险）
        for (int i = tailStart; i < messages.size(); i++) {
            result.add(truncateToolMessage(messages.get(i)));
        }
        return result;
    }

    /**
     * 旧消息折叠：抽取每步的「动作 → 结论」骨架，丢弃过程细节。
     * 工具结果只保留首行结论；助手思考保留前 100 字。
     */
    private String foldToSummary(List<Message> oldMessages) {
        StringBuilder sb = new StringBuilder();
        int step = 0;
        for (Message m : oldMessages) {
            String text = m.getText();
            if (text == null || text.isBlank()) continue;
            if (m.getMessageType() == MessageType.TOOL) {
                String firstLine = text.lines().findFirst().orElse("");
                sb.append(String.format("- 工具结果#%d: %s%n", ++step, clip(firstLine, 150)));
            } else if (m.getMessageType() == MessageType.ASSISTANT) {
                AssistantMessage am = (AssistantMessage) m;
                if (am.hasToolCalls()) {
                    String calls = am.getToolCalls().stream()
                            .map(tc -> tc.name())
                            .reduce((a, b) -> a + "," + b).orElse("");
                    sb.append(String.format("- 调用工具: %s%n", calls));
                } else {
                    sb.append(String.format("- 阶段结论: %s%n", clip(text, 100)));
                }
            }
            // USER 中间消息（nextStepPrompt 注入）无信息量，直接丢弃
        }
        return sb.length() == 0 ? "(无有效历史)" : sb.toString();
    }

    /** 单条 TOOL 消息截断：保留头尾，中间用省略标记（头尾信息密度最高） */
    private Message truncateToolMessage(Message m) {
        if (m.getMessageType() != MessageType.TOOL) {
            return m;
        }
        String text = m.getText();
        if (text == null || text.length() <= maxToolMessageChars) {
            return m;
        }
        int head = (int) (maxToolMessageChars * 0.7);
        int tail = maxToolMessageChars - head;
        String clipped = text.substring(0, head)
                + "\n...[中间 " + (text.length() - head - tail) + " 字符已压缩，如需完整数据请重新查询并缩小范围]...\n"
                + text.substring(text.length() - tail);

        // ToolResponseMessage 需保留 responses 结构以维持 tool_call_id 配对，重建消息
        if (m instanceof ToolResponseMessage trm && !trm.getResponses().isEmpty()) {
            List<ToolResponseMessage.ToolResponse> newResponses = trm.getResponses().stream()
                    .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), clip(r.responseData(), maxToolMessageChars)))
                    .toList();
            return new ToolResponseMessage(newResponses);
        }
        return new AssistantMessage(clipped);
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
