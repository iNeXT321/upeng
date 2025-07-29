package com.yt.agent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.yt.agent.agent.enums.AgentState;
import com.yt.agent.agent.prompt.ToolCallPrompt;
import com.yt.agent.config.AgentConfig;
import com.yt.agent.response.AgentResponse;
import com.yt.agent.utils.SpringContextHolder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
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
        setChatClient(context.getChatClient());

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
        setSystemPrompt(agentConfig.getReactSystemPromptMap().getOrDefault(promptKey, ToolCallPrompt.SYSTEM_PROMPT)
                .replace("{{tools}}", toolPrompt.toString())
                .replace("{{query}}", context.getQuery())

               // .replace("{{date}}", context.getDateInfo())
                .replace("{{basePrompt}}", context.getBasePrompt()));

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

        Map<String, String> metadata = Map.of("enable_thinking", "false");
        this.chatOptions = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .streamUsage(false)
                .metadata(metadata)
                .build();
    }


    @Override
    public boolean think() {
        // 1、校验提示词，拼接用户提示词
        if (getMemory().getLastMessage().getMessageType() != MessageType.USER) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMemory().addMessage(userMessage);
        }
        // 2、调用 AI 大模型，获取工具调用结果
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

        List<String> results = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()){
            String toolName = toolResponse.name();
            String result = toolResponse.responseData();
            printer.send("tool_result", AgentResponse.ToolResult.builder()
                    .toolName(toolName)
                   // .toolParam(JSON.parseObject(command.getFunction().getArguments(), Map.class))
                    .toolResult(result)
                    .build(), null);

            if (maxObserve != null) {
                result = result.substring(0, Math.min(result.length(), maxObserve));
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
}
