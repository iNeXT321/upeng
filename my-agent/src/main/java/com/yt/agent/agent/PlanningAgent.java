package com.yt.agent.agent;

import cn.hutool.core.collection.CollUtil;
import com.yt.agent.agent.enums.AgentState;
import com.yt.agent.agent.prompt.PlanningPrompt;
import com.yt.agent.config.AgentConfig;
import com.yt.agent.tools.PlanningTool;
import com.yt.agent.utils.SpringContextHolder;
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
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class PlanningAgent extends ReActAgent {

    // 可用的工具
    private ToolCallback[] availableTools;

    // 保存工具调用信息的响应结果（要调用那些工具）
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者
    private ToolCallingManager toolCallingManager;

    private ChatOptions chatOptions;

    private Integer maxObserve;

    private PlanningTool planningTool = new PlanningTool();

    private Boolean isCloseUpdate;

    private String systemPromptSnapshot;

    private String nextStepPromptSnapshot;

    private String planId;

    public PlanningAgent(AgentContext context) {

        setName("planningAgent");

        setDescription("An agent that creates and manages plans to solve tasks");
        setChatClient(context.getChatClient());

        ApplicationContext applicationContext = SpringContextHolder.getApplicationContext();
        AgentConfig agentConfig = applicationContext.getBean(AgentConfig.class);

        // 初始化工具集合
        ToolCallback[] planningTools = ToolCallbacks.from(planningTool);
        this.availableTools = Stream.concat(
                Arrays.stream(planningTools),
                Arrays.stream(context.getAvailableTools())
        ).toArray(ToolCallback[]::new);

        StringBuilder toolPrompt = new StringBuilder();
        for (ToolCallback toolCallback : this.availableTools) {
            ToolDefinition toolDefinition = toolCallback.getToolDefinition();
            toolPrompt.append(String.format("工具名：%s 工具描述：%s\n", toolDefinition.name(), toolDefinition.description()));
        }

        String promptKey = "default";
        String nextPromptKey = "default";
//        setSystemPrompt(agentConfig.getPlannerSystemPromptMap().getOrDefault(promptKey, PlanningPrompt.SYSTEM_PROMPT)
//                .replace("{{tools}}", toolPrompt.toString())
//                .replace("{{query}}", context.getQuery())
//             //   .replace("{{date}}", context.getDateInfo())
//                .replace("{{sopPrompt}}", context.getSopPrompt()));

        setSystemPrompt(agentConfig.getPlannerSystemPromptMap().getOrDefault(promptKey, PlanningPrompt.PPT_SYSTEM_PROMPT)
                .replace("{{tools}}", toolPrompt.toString())
                .replace("{{query}}", context.getQuery())
                .replace("{{sopPrompt}}", context.getSopPrompt()));
        setNextStepPrompt(agentConfig.getPlannerNextStepPromptMap().getOrDefault(nextPromptKey, PlanningPrompt.NEXT_STEP_PROMPT)
                .replace("{{tools}}", toolPrompt.toString())
                .replace("{{query}}", context.getQuery())
              //  .replace("{{date}}", context.getDateInfo())
                .replace("{{sopPrompt}}", context.getSopPrompt()));

        setSystemPromptSnapshot(getSystemPrompt());
        setNextStepPromptSnapshot(getNextStepPrompt());

        setPrinter(context.printer);
        setMaxSteps(agentConfig.getPlannerMaxSteps());

        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        setContext(context);

        setIsCloseUpdate("1".equals(agentConfig.getPlanningCloseUpdate()));

        planningTool.setAgentContext(context);
    }


    @Override
    public boolean think() {
        // 获取文件内容
//        setSystemPrompt(getSystemPromptSnapshot().replace("{{files}}", filesStr));
//        setNextStepPrompt(getNextStepPromptSnapshot().replace("{{files}}", filesStr));
//        log.info("{} planer fileStr {}", context.getRequestId(), filesStr);

        // 关闭了动态更新Plan，直接执行下一个task
        if (isCloseUpdate) {
            if (Objects.nonNull(planningTool.getPlan())) {
                planningTool.stepPlan();
                return true;
            }
        }

        try {
            if (getMemory().getLastMessage().getMessageType() != MessageType.USER) {
                UserMessage userMessage = new UserMessage(getNextStepPrompt());
                getMemory().addMessage(userMessage);
            }

            context.setStreamMessageType("plan_thought");
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

            //将消息通过Sse输出
            if (context.getIsStream() && !StringUtils.isEmpty(result)) {
                printer.send("plan_thought", result);
            }
            //将消息存入到Memory中
            getMemory().addMessage(assistantMessage);

        } catch (Exception e) {
            // 原实现只打日志并返回 true，随后 act() 会拿着 null 的 toolCallChatResponse 触发 NPE，
            // 表现为"规划阶段莫名失败且没有可读原因"。这里显式终止并保留错误信息。
            log.error("{} think error ", context.getRequestId(), e);
            setState(AgentState.ERROR);
            return false;
        }
        return true;
    }

    @Override
    public String act() {
        if (toolCallChatResponse == null) {
            setState(AgentState.ERROR);
            return "规划失败：未获得模型响应";
        }
        // 关闭了动态更新Plan，直接执行下一个task
        if (isCloseUpdate) {
            if (Objects.nonNull(planningTool.getPlan())) {
                return getNextTask();
            }
        }

        // 调用工具
        Prompt prompt = new Prompt(getMemory().getMessages(), this.chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        List<String> results = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
            String result = toolResponse.responseData();
            if (maxObserve != null) {
                result = com.yt.agent.utils.StringUtil.truncateSafely(result, maxObserve);
            }
            results.add(result);

        }
        //打印日志
        String toolCallResponse = toolResponseMessage.getResponses().stream()
                .map(toolResponse -> String.format("工具名称：%s，返回结果：%s", toolResponse.name(), toolResponse.responseData()))
                .collect(Collectors.joining("\n"));
        log.info("{} 【act()】 工具调用的结果：{}",getName() , toolCallResponse);

        getMemory().addMessage(toolResponseMessage);

        if (Objects.nonNull(planningTool.getPlan())) {
            if (isCloseUpdate) {
                planningTool.stepPlan();
            }
            return getNextTask();
        }
        return String.join("\n\n", results);
    }

    /**
     * 获取下一个要执行的任务
     *
     * <p>该方法主要完成以下功能：
     * 1. 检查计划中的所有步骤是否都已完成
     * 2. 如果所有步骤都已完成，则标记代理为完成状态并返回"finish"
     * 3. 如果还有未完成的步骤，但当前步骤不为空，则发送当前步骤信息并返回当前步骤内容
     * 4. 如果没有可执行的步骤，则返回空字符串
     *
     * <p>执行流程：
     * - 遍历计划中所有步骤的状态，检查是否全部为"completed"
     * - 如果全部完成：
     *   - 设置代理状态为FINISHED
     *   - 通过printer发送完整的计划信息
     *   - 返回"finish"表示计划执行完毕
     * - 如果有未完成步骤且当前步骤不为空：
     *   - 设置代理状态为FINISHED
     *   - 将当前步骤按"<sep>"分隔符拆分
     *   - 通过printer分别发送计划信息和每个子步骤
     *   - 返回当前步骤内容
     *
     * @return String - 下一步要执行的任务内容，"finish"表示计划完成，空字符串表示无任务
     */
    private String getNextTask() {
        boolean allComplete = true;
        for (String status : planningTool.getPlan().getStepStatus()) {
            if (!"completed".equals(status)) {
                allComplete = false;
                break;
            }
        }

        if (allComplete) {
            setState(AgentState.FINISHED);
            printer.send("plan", planningTool.getPlan());
            return "finish";
        }

        if (!planningTool.getPlan().getCurrentStep().isEmpty()) {
            setState(AgentState.FINISHED);
            String[] currentSteps = planningTool.getPlan().getCurrentStep().split("<sep>");
            printer.send("plan", planningTool.getPlan());
            Arrays.stream(currentSteps).forEach(step -> printer.send("task", step));
            return planningTool.getPlan().getCurrentStep();
        }
        return "";
    }


    @Override
    public String run(String query) {
        if (Objects.isNull(planningTool.getPlan())) {
            AgentConfig genieConfig = SpringContextHolder.getApplicationContext().getBean(AgentConfig.class);
            query = genieConfig.getPlanPrePrompt() + query;
        }
        return super.run(query);
    }
}
