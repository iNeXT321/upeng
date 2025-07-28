package com.yt.agent.agent;

import cn.hutool.core.util.StrUtil;
import com.yt.agent.agent.dto.Memory;
import com.yt.agent.agent.dto.SseResult;
import com.yt.agent.agent.enums.AgentState;
import com.yt.agent.printer.Printer;
import com.yt.agent.utils.StringUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Data
@Slf4j
public abstract class BaseAgent {

    //核心属性
    private String name;
    private String description;
    private String systemPrompt;
    private String nextStepPrompt;

//    public ToolCollection availableTools = new ToolCollection();
    private Memory memory = new Memory();
//    protected LLM llm;
    protected AgentContext context;

    // 执行控制
    private AgentState state = AgentState.IDLE;
    private int maxSteps = 10;
    private int currentStep = 0;
    private int duplicateThreshold = 2;

    // emitter
    Printer printer;


    // LLM 大模型
    private ChatClient chatClient;

    // Memory 记忆（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();


    public String run(String query){
        setState(AgentState.RUNNING);

        if(!query.isEmpty()){
            updateMemory(MessageType.USER, query, null, null);
        }

        List<String> results = new ArrayList<>();
        try {
            while (currentStep < maxSteps && state != AgentState.FINISHED) {
                currentStep++;
                log.info("{} {} Executing step {}/{}", context.getRequestId(), getName(), currentStep, maxSteps);
                String stepResult = step();
                results.add(stepResult);

            }

            if (currentStep >= maxSteps) {
                currentStep = 0;
                setState(AgentState.FINISHED);
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
        }catch (Exception e){
            setState(AgentState.ERROR);
            throw e;
        }
        return results.isEmpty() ? "No steps executed" : results.get(results.size() - 1);
    }



    // 执行一个步骤,由子类实现
    public abstract String step();

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }

    /**
     * 更新代理记忆
     */
    public void updateMemory(MessageType role, String content, String base64Image, List<ToolResponseMessage.ToolResponse> toolResponses) {
        Message message;
        switch (role) {
            case USER:
                message = new UserMessage(content);
                break;
            case SYSTEM:
                message = new SystemMessage( content);
                break;
            case ASSISTANT:
                message = new AssistantMessage(content);
                break;
            case TOOL:
                message = new ToolResponseMessage(toolResponses);
                break;
            default:
                throw new IllegalArgumentException("Unsupported role type: " + role);
        }
        memory.addMessage(message);
    }

}
