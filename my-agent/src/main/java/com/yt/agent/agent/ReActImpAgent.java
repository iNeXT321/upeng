package com.yt.agent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.yt.agent.agent.enums.AgentState;
import com.yt.agent.agent.prompt.ToolCallPrompt;
import com.yt.agent.config.AgentConfig;
import com.yt.agent.platform.llm.ModelRole;
import com.yt.agent.response.AgentResponse;
import com.yt.agent.utils.SpringContextHolder;
import com.yt.agent.utils.StringUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ReActImpAgent extends ReActAgent {

    // 可用的工具
    private final ToolCallback[] availableTools;

    // 保存工具调用信息的响应结果（要调用那些工具）
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者
    private final ToolCallingManager toolCallingManager;

    // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
    private final ChatOptions chatOptions;

    // 最大观察长度 (对工具调用结果的截取的最大长度)
    private Integer maxObserve;

    private String systemPromptSnapshot;

    private String nextStepPromptSnapshot;


    public ReActImpAgent(AgentContext  context){
        setName("react");
        setDescription("an agent that can execute tool calls.");

        setContext(context);
        // 按角色取模型：REACT 角色可以独立于 planner/executor 配置不同模型
        setChatClient(context.getLlm() != null ? context.getLlm().chat(ModelRole.REACT) : context.getChatClient());

        StringBuilder toolPrompt = new StringBuilder();
        ToolCallback[] availableToolList = context.getAvailableTools();
        for (ToolCallback toolCallback : availableToolList){
            ToolDefinition toolDefinition = toolCallback.getToolDefinition();
            toolPrompt.append(String.format("工具名：%s 工具描述：%s\n", toolDefinition.name(), toolDefinition.description()));
        }

        String promptKey = "default";
        String nextPromptKey = "default";
        ApplicationContext applicationContext = SpringContextHolder.getApplicationContext();
        AgentConfig agentConfig = applicationContext.getBean(AgentConfig.class);
        String resolvedSystemPrompt = agentConfig.getReactSystemPromptMap().getOrDefault(promptKey, ToolCallPrompt.SYSTEM_PROMPT)
                .replace("{{tools}}", toolPrompt.toString())
                .replace("{{query}}", context.getQuery())

               // .replace("{{date}}", context.getDateInfo())
                .replace("{{basePrompt}}", context.getBasePrompt());

        // 注入语义记忆（指标口径/业务术语/用户偏好）：问数正确性的锚点，优先级高于模型常识推断
        if (context.getSemanticMemoryPrompt() != null && !context.getSemanticMemoryPrompt().isBlank()) {
            resolvedSystemPrompt = resolvedSystemPrompt + "\n" + context.getSemanticMemoryPrompt();
        }
        setSystemPrompt(resolvedSystemPrompt);

        setNextStepPrompt(agentConfig.getReactNextStepPromptMap().getOrDefault(nextPromptKey, ToolCallPrompt.NEXT_STEP_PROMPT)
                .replace("{{tools}}", toolPrompt.toString())
                .replace("{{query}}", context.getQuery())
              //  .replace("{{date}}", context.getDateInfo())
                .replace("{{basePrompt}}", context.getBasePrompt()));


        setSystemPromptSnapshot(getSystemPrompt());
        setNextStepPromptSnapshot(getNextStepPrompt());

        setPrinter(context.getPrinter());

        this.availableTools = context.getAvailableTools();
        this.toolCallingManager = ToolCallingManager.builder().build();

        // 统一由模型网关产出选项：保证"路由到哪个模型就请求哪个模型"，并关闭内置工具执行
        this.chatOptions = context.getLlm() != null
                ? context.getLlm().toolOptions(ModelRole.REACT)
                : ToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build();

    }


    @Override
    public boolean think() {
        // 1、校验提示词，拼接用户提示词
        if (getMemory().getLastMessage().getMessageType() != MessageType.USER) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMemory().addMessage(userMessage);
        }
        // 2、调用 AI 大模型，获取工具调用结果
        // 上下文压缩：token 超过软阈值时折叠旧步骤摘要化，防止长任务撑爆上下文窗口
        try {
            com.yt.agent.memory.ContextCompressor compressor =
                    SpringContextHolder.getApplicationContext().getBean(com.yt.agent.memory.ContextCompressor.class);
            List<Message> compressed = compressor.compressIfNeeded(getMemory().getMessages());
            if (compressed != getMemory().getMessages()) {
                getMemory().getMessages().clear();
                getMemory().addMessages(compressed);
            }
        } catch (Exception e) {
            log.warn("上下文压缩跳过: {}", e.getMessage());
        }
        Prompt prompt = new Prompt( getMemory().getMessages(), this.chatOptions);
        try{
            // 获取带工具选项的响应
            context.setStreamMessageType("tool_thought");

            ChatResponse chatResponse = getChatClient()
                    .prompt(prompt)
                    .system(getSystemPrompt())
                    .options(chatOptions)
                    .toolCallbacks(availableTools)
                    .call()
                    .chatResponse();


            // 记录响应，用于等下 Act
            this.toolCallChatResponse = chatResponse;
            // 解析工具调用结果，获取要调用的工具
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // 获取要调用的工具列表
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            // 获取助手消息的文本
            String result = assistantMessage.getText();

            //打印日志
            log.info(getName() + "的思考：" + result);
            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);

            //将消息通过Sse输出
            if (context.getIsStream() && !StringUtils.isEmpty(result)) {
                printer.send("tool_thought", result);
            }
            // 推送工具调用意图（先于执行）：前端据此展示工具名与参数（SQL 等关键信息在参数里）
            if (context.getIsStream() && !toolCallList.isEmpty()) {
                for (AssistantMessage.ToolCall toolCall : toolCallList) {
                    printer.send("tool_call", AgentResponse.ToolCall.builder()
                            .toolName(toolCall.name())
                            .toolParam(parseToolParam(toolCall.arguments()))
                            .build(), null);
                }
            }
            //将消息存入到Memory中
            getMemory().addMessage(assistantMessage);

            return true;
        }catch (Exception e){
            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
            getMemory().addMessage(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
            setState(AgentState.FINISHED);
            return false;
        }
    }

    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            setState(AgentState.FINISHED);
            return getMemory().getLastMessage().getText();
        }
        // 调用工具
        Prompt prompt = new Prompt(getMemory().getMessages(), this.chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        // id -> 调用参数 映射：把模型传入的参数回填到 tool_result，前端才能展示"用什么参数调的"
        Map<String, AssistantMessage.ToolCall> callById = new HashMap<>();
        if (toolCallChatResponse != null) {
            for (AssistantMessage.ToolCall call : toolCallChatResponse.getResult().getOutput().getToolCalls()) {
                callById.put(call.id(), call);
            }
        }

        List<String> results = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()){
            // 解包二次 JSON 编码：部分 Spring AI 版本会把 String 返回值整体 JSON 编码，统一还原
            String result = unwrapToolResponse(toolResponse.responseData());
            String toolName = toolResponse.name();
            AssistantMessage.ToolCall call = callById.get(toolResponse.id());
            printer.send("tool_result", AgentResponse.ToolResult.builder()
                    .toolName(toolName)
                    .toolParam(call != null ? parseToolParam(call.arguments()) : null)
                    .toolResult(truncateForSse(result))
                    .build(), null);

            if (maxObserve != null) {
                result = StringUtil.truncateSafely(result, maxObserve);
            }
            results.add(result);

        }
        getMemory().addMessage(toolResponseMessage);

        return String.join("\n\n", results);
    }

    @Override
    public String run(String request) {
        return super.run(request);
    }

    /** SSE 通道中工具结果的最大长度：完整结果喂给模型，展示侧截断防止前端渲染卡顿 */
    private static final int MAX_SSE_TOOL_RESULT = 6000;

    private String truncateForSse(String result) {
        if (result == null || result.length() <= MAX_SSE_TOOL_RESULT) {
            return result;
        }
        return result.substring(0, MAX_SSE_TOOL_RESULT)
                + "\n…（结果过长已截断，共 " + result.length() + " 字符，完整结果见服务端日志）";
    }

    /** 模型给出的工具参数是 JSON 字符串，解析失败时降级为原始串，绝不因参数格式问题中断主流程 */
    private Map<String, Object> parseToolParam(String arguments) {
        if (StringUtils.isEmpty(arguments)) {
            return new HashMap<>();
        }
        try {
            return JSON.parseObject(arguments);
        } catch (Exception e) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("_raw", arguments);
            return fallback;
        }
    }

    /**
     * 解包工具结果的二次 JSON 编码。
     * Spring AI 的 DefaultToolCallResultConverter 对 String 返回值做 JsonParser.toJson，
     * 产物形如 "\"多行文本\""（首尾引号 + 换行变 \n 字面量）。此处只做一次解码：
     * 仅当整体是一个 JSON 字符串字面量时还原，其他形态原样返回，绝不影响结构化结果。
     */
    private String unwrapToolResponse(String response) {
        if (response == null) {
            return null;
        }
        String s = response.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            try {
                Object decoded = JSON.parse(s);
                if (decoded instanceof String decodedStr) {
                    return decodedStr;
                }
            } catch (Exception ignore) {
                // 不是合法 JSON 字符串字面量，按原文返回
            }
        }
        return response;
    }
}
