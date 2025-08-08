package com.yt.agent.agent;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSON;
import com.yt.agent.agent.enums.AgentState;
import com.yt.agent.agent.prompt.ToolCallPrompt;
import com.yt.agent.config.AgentConfig;
import com.yt.agent.response.AgentResponse;
import com.yt.agent.utils.MessageUtils;
import com.yt.agent.utils.SpringContextHolder;
import com.yt.agent.utils.ThreadUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationContext;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class ExecutorAgent extends ReActAgent {


    // 可用的工具
    private ToolCallback[] availableTools;

    // 保存工具调用信息的响应结果（要调用那些工具）
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者
    private ToolCallingManager toolCallingManager;

    private ChatOptions chatOptions;

    private Integer maxObserve;
    private String systemPromptSnapshot;
    private String nextStepPromptSnapshot;

    private Integer taskId;


    public ExecutorAgent(AgentContext context) {
        setName("executorAgent");
        setDescription("an agent that can execute tool calls.");
        ApplicationContext applicationContext = SpringContextHolder.getApplicationContext();
        AgentConfig agentConfig = applicationContext.getBean(AgentConfig.class);

        this.availableTools = context.getAvailableTools();
        StringBuilder toolPrompt = new StringBuilder();
        for (ToolCallback toolCallback : this.availableTools) {
            ToolDefinition toolDefinition = toolCallback.getToolDefinition();
            toolPrompt.append(String.format("工具名：%s 工具描述：%s\n", toolDefinition.name(), toolDefinition.description()));
        }

        String promptKey = "default";
        String sopPromptKey = "default";
        String nextPromptKey = "default";
        setSystemPrompt(agentConfig.getExecutorSystemPromptMap().getOrDefault(promptKey, ToolCallPrompt.SYSTEM_PROMPT)
                .replace("{{tools}}", toolPrompt.toString())
                .replace("{{query}}", context.getQuery())
              //  .replace("{{date}}", context.getDateInfo())
                .replace("{{sopPrompt}}", context.getSopPrompt())
                .replace("{{executorSopPrompt}}", agentConfig.getExecutorSopPromptMap().getOrDefault(sopPromptKey, "")));
        setNextStepPrompt(agentConfig.getExecutorNextStepPromptMap().getOrDefault(nextPromptKey, ToolCallPrompt.NEXT_STEP_PROMPT)
                .replace("{{tools}}", toolPrompt.toString())
                .replace("{{query}}", context.getQuery())
              //  .replace("{{date}}", context.getDateInfo())
                .replace("{{sopPrompt}}", context.getSopPrompt())
                .replace("{{executorSopPrompt}}", agentConfig.getExecutorSopPromptMap().getOrDefault(sopPromptKey, "")));

        setSystemPromptSnapshot(getSystemPrompt());
        setNextStepPromptSnapshot(getNextStepPrompt());

        setPrinter(context.printer);

        setMaxSteps(agentConfig.getPlannerMaxSteps());

        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        setContext(context);

        setMaxObserve(Integer.parseInt(agentConfig.getMaxObserve()));

        // 初始化工具集合
        availableTools = context.getAvailableTools();
        // setDigitalEmployeePrompt(genieConfig.getDigitalEmployeePrompt());

        setChatClient(context.getChatClient());

        setTaskId(0);

    }


    @Override
    public boolean think() {
        // 获取文件内容
//        String filesStr = FileUtil.formatFileInfo(context.getProductFiles(), true);
//        setSystemPrompt(getSystemPromptSnapshot().replace("{{files}}", filesStr));
//        setNextStepPrompt(getNextStepPromptSnapshot().replace("{{files}}", filesStr));

        if (getMemory().getLastMessage().getMessageType() != MessageType.USER) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMemory().addMessage(userMessage);
        }

        try {
            // 获取带工具选项的响应
            //log.info("{} executor ask tool {}", context.getRequestId(), JSON.toJSONString(availableTools));

            Prompt prompt = new Prompt(getMemory().getMessages(), this.chatOptions);
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
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            if(!StringUtils.isEmpty( result)){
                log.info("{} 【think()】 选择了{}个工具来使用  工具信息:{} 思考:{}",getName(), toolCallList.size(), toolCallInfo, result.replace("\n", ""));
            }
            // 记录响应信息
            if (!StringUtils.isEmpty(result)) {
                if (!chatResponse.hasToolCalls()) {
                    Map<String, Object> taskSummary = new HashMap<>();
                    taskSummary.put("taskSummary", result);
                    printer.send("task_summary", taskSummary);
                } else {
                    printer.send("tool_thought", result);
                }
            }
            //将消息存入到Memory中
            getMemory().addMessage(assistantMessage);

        } catch (Exception e) {
            log.error("Oops! The {} 's thinking process hit a snag: {}", getName(), e.getMessage());
            getMemory().addMessage(new AssistantMessage("Error encountered while processing: " + e.getMessage()));
            setState(AgentState.FINISHED);
            return false;
        }
        return true;
    }

    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            AgentConfig genieConfig = SpringContextHolder.getApplicationContext().getBean(AgentConfig.class);
            setState(AgentState.FINISHED);
            // 删除工具结果
            if ("1".equals(genieConfig.getClearToolMessage())) {
                getMemory().clearToolContext();
            }
            // 返回固定话术
            if (!genieConfig.getTaskCompleteDesc().isEmpty()) {
                return genieConfig.getTaskCompleteDesc();
            }
            return MessageUtils.getMessageContent(getMemory().getLastMessage());
        }

        // 调用工具
        Prompt prompt = new Prompt(getMemory().getMessages(), this.chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        List<String> results = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
            String result = toolResponse.responseData();
            String toolName = toolResponse.name();

            if (!Arrays.asList("report_tool").contains(toolName)) {
                printer.send("tool_result", AgentResponse.ToolResult.builder()
                        .toolName(toolName)
                        // .toolParam(JSON.parseObject(command.getFunction().getArguments(), Map.class))
                        .toolResult(result)
                        .build(),
                        null);
            }
            if (maxObserve != null) {
                result = result.substring(0, Math.min(result.length(), maxObserve));
            }
            results.add(result);
        }

        //打印日志
        String toolCallResponse = toolResponseMessage.getResponses().stream()
                .map(toolResponse -> String.format("工具名称：%s，返回结果：%s", toolResponse.name(), toolResponse.responseData()))
                .collect(Collectors.joining("\n"));
        log.info("{} 【act()】 工具调用的结果：{}",getName() , toolCallResponse);

        getMemory().addMessage(toolResponseMessage);
        return String.join("\n\n", results);
    }



    @Override
    public String run(String request) {
       // generateDigitalEmployee(request);
        AgentConfig agentConfig = SpringContextHolder.getApplicationContext().getBean(AgentConfig.class);
        request = agentConfig.getTaskPrePrompt() + request;
        // 更新当前task
        context.setTask(request);
        return super.run(request);
    }


}
