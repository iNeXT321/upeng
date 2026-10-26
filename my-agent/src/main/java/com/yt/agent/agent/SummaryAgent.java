package com.yt.agent.agent;

import com.yt.agent.agent.dto.TaskSummaryResult;
import com.yt.agent.config.AgentConfig;
import com.yt.agent.platform.llm.ModelRole;
import com.yt.agent.printer.Printer;
import com.yt.agent.utils.MessageUtils;
import com.yt.agent.utils.SpringContextHolder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.context.ApplicationContext;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 任务完成，使用SummaryAgent对任务总结
 */
@Data
@Slf4j
@EqualsAndHashCode(callSuper = true)
public class SummaryAgent extends BaseAgent {

    private String requestId;
    private Integer messageSizeLimit;
    public static final String logFlag = "summaryTaskResult";

    private final ChatOptions chatOptions;


    @Override
    public String step() {
        return "";
    }

    public SummaryAgent(AgentContext  context) {
        ApplicationContext applicationContext = SpringContextHolder.getApplicationContext();
        AgentConfig agentConfig = applicationContext.getBean(AgentConfig.class);
        setSystemPrompt(agentConfig.getSummarySystemPrompt());

        setContext(context);
        setRequestId(context.getRequestId());
        setChatClient(context.getLlm() != null ? context.getLlm().chat(ModelRole.SUMMARY) : context.getChatClient());
        setMessageSizeLimit(agentConfig.getMessageSizeLimit());

        this.chatOptions = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
    }

    // 总结任务
    public TaskSummaryResult summaryTaskResult(List<Message> messages, String query) {
        long startTime = System.currentTimeMillis();
        // 1. 参数校验（可选）
        if (CollectionUtils.isEmpty(messages) || StringUtils.isEmpty(query)) {
            log.warn("requestId: {}  summaryTaskResult messages:{}  or query:{} is empty", requestId, messages, query);
            return TaskSummaryResult.builder().taskSummary("").build();
        }

        try {
            // 2. 构建系统消息（提取为独立方法）
            log.info("requestId: {} summaryTaskResult: messages:{}", requestId, messages.size());
            String formattedPrompt = buildSummaryPrompt(messages, query);
            Message userMessage = createSystemMessage(formattedPrompt);

            // 调用LLM并处理结果
            Prompt prompt = new Prompt(userMessage, this.chatOptions);
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .call()
                    .chatResponse();

            // 解析工具调用结果，获取要调用的工具
            String llmResponse = chatResponse.getResult().getOutput().getText();
            log.info("requestId: {} summaryTaskResult: {}", requestId, llmResponse);

            return parseLlmResponse(llmResponse);
        } catch (Exception e) {
            log.error("requestId: {} in summaryTaskResult failed,", requestId, e);

            return TaskSummaryResult.builder().taskSummary("任务执行失败，请联系管理员！").build();
        }
    }

    /**
     * 流式总结：逐 token 通过 printer 推送 agent_stream 增量事件（前端打字机效果的数据源），
     * 同时聚合完整文本返回给调用方（用于最终 result 事件与落库）。
     *
     * <p>设计要点：
     * <ul>
     *   <li>非流式请求或无 printer 时自动退回一次性生成，调用方无需关心差异；</li>
     *   <li>流式任何环节异常都降级为一次性生成——打字效果是体验增强，不能成为新的故障点；</li>
     *   <li>每个增量片段独立经过 GuardedPrinter 脱敏，与整段发送的安全语义一致。</li>
     * </ul>
     * </p>
     */
    public TaskSummaryResult streamSummaryTaskResult(List<Message> messages, String query) {
        if (CollectionUtils.isEmpty(messages) || StringUtils.isEmpty(query)) {
            return TaskSummaryResult.builder().taskSummary("").build();
        }
        AgentContext ctx = getContext();
        Printer printer = ctx != null ? ctx.getPrinter() : null;
        boolean canStream = printer != null && Boolean.TRUE.equals(ctx.getIsStream());
        if (!canStream) {
            return summaryTaskResult(messages, query);
        }
        try {
            log.info("requestId: {} streamSummaryTaskResult: messages:{}", requestId, messages.size());
            String formattedPrompt = buildSummaryPrompt(messages, query);
            Message userMessage = createSystemMessage(formattedPrompt);
            Prompt prompt = new Prompt(userMessage, this.chatOptions);

            StringBuilder answer = new StringBuilder();
            getChatClient().prompt(prompt)
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        if (chunk == null || chunk.isEmpty()) {
                            return;
                        }
                        answer.append(chunk);
                        // 增量推送给前端：agent_stream 事件 finish=false，最终 result 事件才是结束信号
                        printer.send("agent_stream", chunk);
                    })
                    .blockLast();

            String llmResponse = answer.toString();
            if (StringUtils.isBlank(llmResponse)) {
                // 流式返回空（个别模型兼容问题）：退回一次性生成兜底
                log.warn("requestId: {} 流式总结返回为空，降级为一次性生成", requestId);
                return summaryTaskResult(messages, query);
            }
            log.info("requestId: {} streamSummaryTaskResult: {}", requestId, llmResponse);
            return parseLlmResponse(llmResponse);
        } catch (Exception e) {
            log.error("requestId: {} streamSummaryTaskResult failed, fallback to sync", requestId, e);
            return summaryTaskResult(messages, query);
        }
    }

    /** 构建总结 prompt：把消息流水拼成文本并套系统提示词模板 */
    private String buildSummaryPrompt(List<Message> messages, String query) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            String content = MessageUtils.getMessageContent(message);
            if (content != null && content.length() > getMessageSizeLimit()) {
                log.info("requestId: {} message truncate,{}", requestId, message);
                content = content.substring(0, getMessageSizeLimit());
            }
            sb.append(String.format("role:%s content:%s\n", message.getMessageType().getValue(), content));
        }
        return formatSystemPrompt(sb.toString(), query);
    }

    // 提取系统提示格式化逻辑
    private String formatSystemPrompt(String taskHistory, String query) {
        String systemPrompt = getSystemPrompt();
        if (systemPrompt == null) {
            log.error("requestId: {} {} systemPrompt is null", requestId, logFlag);
            throw new IllegalStateException("System prompt is not configured");
        }

        // 替换占位符
        return systemPrompt
                .replace("{{taskHistory}}", taskHistory)
            //    .replace("{{fileNameDesc}}", createFileInfo())
                .replace("{{query}}", query);
    }

    // 提取消息创建逻辑
    private Message createSystemMessage(String content) {
        return new UserMessage(content); // 如果需要更复杂的消息构建，可扩展
    }

    /**
     * 解析LLM响应并处理文件关联
     */
    private TaskSummaryResult parseLlmResponse(String llmResponse) {
        if (StringUtils.isEmpty(llmResponse)) {
            log.error("requestId: {} pattern matcher failed for response is null", requestId);
        }

//        String[] parts1 = llmResponse.split("\\$\\$\\$");
//        if (parts1.length < 2) {
//            return TaskSummaryResult.builder().taskSummary(parts1[0]).build();
//        }
//
//        String summary = parts1[0];
//        String fileNames = parts1[1];
//
//        List<File> files = context.getProductFiles();
//        if (!CollectionUtils.isEmpty(files)) {
//            Collections.reverse(files);
//        } else {
//            log.error("requestId: {} llmResponse:{} productFile list is empty", requestId, llmResponse);
//            // 文件列表为空，交付物中不显示文件
//            return TaskSummaryResult.builder().taskSummary(summary).build();
//        }
//        List<File> product = new ArrayList<>();
//        String[] items = fileNames.split("、");
//        for (String item : items) {
//            String trimmedItem = item.trim();
//            if (StringUtils.isBlank(trimmedItem)) {
//                continue;
//            }
//            for (File file : files) {
//                if (item.contains(file.getFileName().trim())) {
//                    log.info("requestId: {} add file:{}", requestId, file);
//                    product.add(file);
//                    break;
//                }
//            }
//        }

        return TaskSummaryResult.builder().taskSummary(llmResponse).files(null).build();
    }




}
